package dev.mooner.neonpython

import java.math.BigInteger

/**
 * A _csv.Dialect (its subclass's instance too): CPython's DialectObj, its characters code points ([PyCsv.NOT_SET] none),
 * its lineterminator the str it was given ([terminator] its text).
 */
class PyCsvDialect(type: PyType) : PyObject(type) {
    @JvmField var doublequote = true
    @JvmField var skipinitialspace = false
    @JvmField var strict = false
    @JvmField var quoting = 0
    @JvmField var delimiter = ','.code
    @JvmField var quotechar = '"'.code
    @JvmField var escapechar = PyCsv.NOT_SET
    @JvmField var lineterminator: Any = PyStr("\r\n")
    @JvmField var terminator = "\r\n"
}

/** A _csv.reader: CPython's ReaderObj, its field a code point buffer of [fieldLen] code points. */
class PyCsvReader(type: PyType, @JvmField val input: PyIterator, @JvmField val dialect: PyCsvDialect) : PyObject(type) {
    @JvmField var fields: PyList? = null
    @JvmField var state = 0
    @JvmField val field = StringBuilder()
    @JvmField var fieldLen = 0L
    @JvmField var unquoted = false
    @JvmField var lineNum = 0L
}

/** A _csv.writer: CPython's WriterObj, its file's write method, the record being joined. */
class PyCsvWriter(type: PyType, @JvmField val write: Any?, @JvmField val dialect: PyCsvDialect) : PyObject(type) {
    @JvmField val rec = StringBuilder()
    @JvmField var numFields = 0
}

/**
 * `_csv`, what `csv` is made of, as CPython 3.14 has it: Modules/_csv.c in Kotlin, its parser's states, its writer's
 * quoting, its dialects' checks and its messages. Characters are code points (Py_UCS4), as CPython's; the module's
 * types, its error, its registry and its field limit are each module's: each context's here.
 */
object PyCsv {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** No character (CPython's NOT_SET), and the end of a line the parser is given (EOL). */
    const val NOT_SET = -1
    private const val EOL = -2

    private const val START_RECORD = 0
    private const val START_FIELD = 1
    private const val ESCAPED_CHAR = 2
    private const val IN_FIELD = 3
    private const val IN_QUOTED_FIELD = 4
    private const val ESCAPE_IN_QUOTED_FIELD = 5
    private const val QUOTE_IN_QUOTED_FIELD = 6
    private const val EAT_CRNL = 7
    private const val AFTER_ESCAPED_CRNL = 8

    private const val QUOTE_MINIMAL = 0
    private const val QUOTE_ALL = 1
    private const val QUOTE_NONNUMERIC = 2
    private const val QUOTE_NONE = 3
    private const val QUOTE_STRINGS = 4
    private const val QUOTE_NOTNULL = 5
    private val QUOTE_STYLES = listOf("QUOTE_MINIMAL", "QUOTE_ALL", "QUOTE_NONNUMERIC", "QUOTE_NONE", "QUOTE_STRINGS", "QUOTE_NOTNULL")

    private const val NL = '\n'.code
    private const val CR = '\r'.code

    const val DOC = "CSV parsing and writing.\n"
    private const val DIALECT_DOC = "CSV dialect\n\nThe Dialect type records CSV parsing and generation options.\n"
    private const val READER_DOC = "CSV reader\n\nReader objects are responsible for reading and parsing tabular data\nin CSV format.\n"
    private const val WRITER_DOC = "CSV writer\n\nWriter objects are responsible for generating tabular data\nin CSV format from sequence input.\n"

    /** Dialect()'s arguments (dialect_kws), in order. */
    private val DIALECT_KWS = listOf("dialect", "delimiter", "doublequote", "escapechar", "lineterminator", "quotechar", "quoting", "skipinitialspace", "strict")

    // ------------------------------------------------------------------ the module's state

    /** The module's state (each context's): its error, its registry, its types, its field limit. */
    private class State(@JvmField val ctx: PyContext) {
        @JvmField val error: PyType = PyBinascii.newException(ctx, "_csv", "Error", PyExceptions.type("Exception"))
        @JvmField val dialects = PyDict()
        @JvmField val dialectType: PyType = moduleType(ctx, "Dialect", "_csv.Dialect")
        @JvmField val readerType: PyType = moduleType(ctx, "reader", "_csv.reader")
        @JvmField val writerType: PyType = moduleType(ctx, "writer", "_csv.writer")
        @JvmField var fieldLimit = 128L * 1024

        fun error(msg: String) = PyBinascii.error(ctx, error, msg)
    }

    /** A type of the module: a heap type, as CPython's made from a spec are, a base type as theirs are. */
    private fun moduleType(ctx: PyContext, name: String, tpName: String): PyType {
        val t = PyType(name, arrayOf(PyTypes.OBJECT), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "_csv"
        t.tpName = tpName
        // (no __dict__ nor weak references: a subclass's instances have them)
        t.hasDict = false
        t.hasWeakref = false
        PyTypes.OBJECT.addSubclass(ctx, t)
        return t
    }

    private fun chr(c: Int) = String(Character.toChars(c))

    /** PyUnicode_Check's str (a subclass's instance too), or null. */
    private fun str(v: Any?): PyStr? = v as? PyStr

    // ------------------------------------------------------------------ Dialect

    /** get_dialect_from_registry: the dialect registered as [name], else "unknown dialect". */
    private fun registered(st: State, name: Any?): Any? = st.dialects[name] ?: throw st.error("unknown dialect")

    /** _set_char: a str of one code point, else its TypeError; [dflt] where none was given. */
    private fun setChar(name: String, src: Any?, dflt: Int): Int {
        if (src == null) return dflt
        val s = str(src) ?: throw PyRt.typeError("\"$name\" must be a unicode character, not ${PyTypes.fullName(PyTypes.typeOf(src))}")
        if (s.length != 1) throw PyRt.typeError("\"$name\" must be a unicode character, not a string of length ${s.length}")
        return s.s.codePointAt(0)
    }

    /** _set_char_or_none: as [setChar], None NOT_SET. */
    private fun setCharOrNone(name: String, src: Any?, dflt: Int): Int {
        if (src == null) return dflt
        if (src === PyNone) return NOT_SET
        val s = str(src) ?: throw PyRt.typeError("\"$name\" must be a unicode character or None, not ${PyTypes.fullName(PyTypes.typeOf(src))}")
        if (s.length != 1) throw PyRt.typeError("\"$name\" must be a unicode character or None, not a string of length ${s.length}")
        return s.s.codePointAt(0)
    }

    /** _set_int: an int exactly (PyLong_CheckExact), as a C int. */
    private fun setInt(name: String, src: Any?, dflt: Int): Int {
        if (src == null) return dflt
        if (src !is Long && src !is BigInteger) throw PyRt.typeError("\"$name\" must be an integer, not ${PyTypes.fullName(PyTypes.typeOf(src))}")
        if (src !is Long || src < Int.MIN_VALUE || src > Int.MAX_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int")
        return src.toInt()
    }

    /** dialect_check_char: no CR, LF (nor a space unless [allowSpace]) and nothing of the line terminator. */
    private fun checkChar(name: String, c: Int, d: PyCsvDialect, allowSpace: Boolean) {
        if (c == CR || c == NL || (c == ' '.code && !allowSpace)) throw PyException("ValueError", "bad $name value")
        if (c != NOT_SET && terminatorHas(d, c)) throw PyException("ValueError", "bad $name or lineterminator value")
    }

    private fun checkChars(name1: String, name2: String, c1: Int, c2: Int) {
        if (c1 == c2 && c1 != NOT_SET) throw PyException("ValueError", "bad $name1 or $name2 value")
    }

    /** Whether the code point [c] is in [d]'s line terminator (PyUnicode_FindChar). */
    private fun terminatorHas(d: PyCsvDialect, c: Int): Boolean {
        val s = d.terminator
        var i = 0
        while (i < s.length) {
            val x = s.codePointAt(i)
            if (x == c) return true
            i += Character.charCount(x)
        }
        return false
    }

    /**
     * Dialect()'s arguments, as PyArg_ParseTupleAndKeywords takes "|OOOOOOOOO" (no function name: "function", "this
     * function"): each by position or name, null where not given.
     */
    private fun dialectArgs(a: Array<Any?>, kw: PyDict?): Array<Any?> {
        val len = DIALECT_KWS.size
        val nargs = a.size
        var nkw = kw?.size ?: 0
        if (nargs + nkw > len) {
            throw PyRt.typeError("function takes at most $len ${if (nargs == 0) "keyword " else ""}arguments (${nargs + nkw} given)")
        }
        val out = arrayOfNulls<Any?>(len)
        for (i in 0 until len) {
            val cur = if (i < nargs) a[i] else if (nkw > 0) kw!![PyStr(DIALECT_KWS[i])]?.also { nkw-- } else null
            if (cur != null) {
                out[i] = cur
                continue
            }
            if (nkw == 0) return out
        }
        if (nkw > 0) {
            for (i in 0 until nargs) {
                if (kw!![PyStr(DIALECT_KWS[i])] != null) throw PyRt.typeError("argument for function given by name ('${DIALECT_KWS[i]}') and position (${i + 1})")
            }
            for (k in kw!!.map.keys) {
                val key = (k.v as PyStr).s
                if (key in DIALECT_KWS) continue
                val hint = PyMath.suggestion(DIALECT_KWS, key)
                throw PyRt.typeError("this function got an unexpected keyword argument '$key'" + if (hint != null) ". Did you mean '$hint'?" else "")
            }
            throw PyRt.typeError("invalid keyword argument for this function")
        }
        return out
    }

    /** getattr(dialect, name), any exception its absence (DIALECT_GETATTR's PyErr_Clear). */
    private fun dialectAttr(dialect: Any?, name: String): Any? = try {
        PyRt.getAttr(dialect, name)
    } catch (e: PyException) {
        null
    }

    /**
     * dialect_new: a dialect of [cls] from a registered name, a class or an instance, the keywords over its settings;
     * a dialect given as it is where nothing overrides it.
     */
    private fun newDialect(st: State, cls: PyType, a: Array<Any?>, kw: PyDict?): Any? {
        val p = dialectArgs(a, kw?.takeIf { it.size > 0 })
        var dialect = p[0]
        if (dialect != null) {
            if (str(dialect) != null) dialect = registered(st, dialect)
            if (dialect is PyCsvDialect && (1 until p.size).all { p[it] == null }) return dialect
        }
        val self = PyCsvDialect(cls)
        if (dialect != null) for (i in 1 until p.size) if (p[i] == null) p[i] = dialectAttr(dialect, DIALECT_KWS[i])
        // (each checked and converted, in dialect_kws' order)
        val lineterminator = p[4]
        val quotechar = p[5]
        val quoting = p[6]
        self.delimiter = setChar("delimiter", p[1], ','.code)
        self.doublequote = p[2]?.let { PyRt.truthy(it) } ?: true
        self.escapechar = setCharOrNone("escapechar", p[3], NOT_SET)
        if (lineterminator != null) {
            val s = str(lineterminator) ?: throw PyRt.typeError("\"lineterminator\" must be a string, not ${PyTypes.fullName(PyTypes.typeOf(lineterminator))}")
            self.lineterminator = s
            self.terminator = s.s
        }
        self.quotechar = setCharOrNone("quotechar", quotechar, '"'.code)
        self.quoting = setInt("quoting", quoting, QUOTE_MINIMAL)
        self.skipinitialspace = p[7]?.let { PyRt.truthy(it) } ?: false
        self.strict = p[8]?.let { PyRt.truthy(it) } ?: false

        // (validate options)
        if (self.quoting !in 0 until QUOTE_STYLES.size) throw PyRt.typeError("bad \"quoting\" value")
        if (quotechar === PyNone && quoting == null) self.quoting = QUOTE_NONE
        if (self.quoting != QUOTE_NONE && self.quotechar == NOT_SET) throw PyRt.typeError("quotechar must be set if quoting enabled")
        checkChar("delimiter", self.delimiter, self, true)
        checkChar("escapechar", self.escapechar, self, !self.skipinitialspace)
        checkChar("quotechar", self.quotechar, self, !self.skipinitialspace)
        checkChars("delimiter", "escapechar", self.delimiter, self.escapechar)
        checkChars("delimiter", "quotechar", self.delimiter, self.quotechar)
        checkChars("escapechar", "quotechar", self.escapechar, self.quotechar)
        if (cls !== st.dialectType) PyWeakrefs.made(self)
        return self
    }

    /** _call_dialect: the module's Dialect called with [inst] (if given) and the keywords. */
    private fun callDialect(st: State, inst: Any?, kw: PyDict?): PyCsvDialect =
        newDialect(st, st.dialectType, if (inst != null) arrayOf(inst) else NO_ARGS, kw) as PyCsvDialect

    private fun defineDialect(st: State) {
        val t = st.dialectType
        fun new(a: Array<Any?>, kw: PyDict?): Any? {
            if (a.isEmpty()) throw PyRt.typeError("_csv.Dialect.__new__(): not enough arguments")
            val cls = a[0] as? PyType ?: throw PyRt.typeError("_csv.Dialect.__new__(X): X is not a type object (${PyTypes.name(a[0])})")
            if (!cls.isSubtype(t)) {
                val n = cls.tpName ?: cls.name
                throw PyRt.typeError("_csv.Dialect.__new__($n): $n is not a subtype of _csv.Dialect")
            }
            return newDialect(st, cls, a.copyOfRange(1, a.size), kw)
        }
        t.def("__new__", PyBuiltin("__new__", { _, a -> new(a, null) }, { _, a, kw -> new(a, kw) }))
        // (pickling refused, whatever the protocol: Dialect_reduce, METH_VARARGS)
        val reduce: (PyContext, Array<Any?>) -> Any? = { _, a -> throw PyRt.typeError("cannot pickle '${PyTypes.typeOf(a.getOrNull(0)).name}' instances") }
        t.def("__reduce__", PyBuiltin("__reduce__", reduce, { c, a, _ -> reduce(c, a) }))
        t.def("__reduce_ex__", PyBuiltin("__reduce_ex__", reduce, { c, a, _ -> reduce(c, a) }))
        fun self(a: Array<Any?>, n: String): PyCsvDialect = a.getOrNull(0) as? PyCsvDialect
            ?: throw PyRt.typeError("descriptor '$n' for '_csv.Dialect' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        member(t, "skipinitialspace") { a, n -> PyBool.of(self(a, n).skipinitialspace) }
        member(t, "doublequote") { a, n -> PyBool.of(self(a, n).doublequote) }
        member(t, "strict") { a, n -> PyBool.of(self(a, n).strict) }
        fun getter(n: String, get: (PyCsvDialect) -> Any?) {
            val notWritable = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "attribute '$n' of '_csv.Dialect' objects is not writable") }
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a, n)) }, notWritable, notWritable, null).also { it.name = n })
        }
        fun charOrNone(c: Int): Any = if (c == NOT_SET) PyNone else PyStr(chr(c))
        getter("delimiter") { charOrNone(it.delimiter) }
        getter("escapechar") { charOrNone(it.escapechar) }
        getter("lineterminator") { it.lineterminator }
        getter("quotechar") { charOrNone(it.quotechar) }
        getter("quoting") { it.quoting.toLong() }
        noDict(t)
        t.def("__doc__", PyStr(DIALECT_DOC))
        t.def("__module__", PyStr("_csv"))
        t.dispatchBuiltins = true
    }

    /** A read-only member (Py_READONLY): "readonly attribute". */
    private fun member(t: PyType, n: String, get: (Array<Any?>, String) -> Any?) {
        val readonly = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "readonly attribute") }
        t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(a, n) }, readonly, readonly, null).also { it.name = n })
    }

    /** Its instances have no __dict__ (a subclass's have): setting or deleting what is not its own an AttributeError. */
    private fun noDict(t: PyType) {
        fun name(v: Any?) = (v as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(v)}'")).s
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            val n = name(a[1])
            if (PyTypes.typeOf(a[0]) !== t || t.lookup(n) is PyProperty) PyClasses.genericSetAttr(a[0], n, a[2])
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            val n = name(a[1])
            if (PyTypes.typeOf(a[0]) !== t || t.lookup(n) is PyProperty) PyClasses.genericDelAttr(a[0], n)
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.hooks = 2 or 4
    }

    /**
     * object.__reduce_ex__ as CPython's is for reader and writer: from protocol 2, the state object_getstate requires
     * is one a C struct holds, "cannot pickle"; else object's.
     */
    private val REDUCE_EX = PyBuiltin("__reduce_ex__") { ctx, a ->
        val v = a.getOrNull(0)
        val proto = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("__reduce_ex__() missing required argument 'protocol' (pos 1)")
        if (proto >= 2) throw PyRt.typeError("cannot pickle '${PyTypes.name(v)}' object")
        PyDunder.reduceEx(ctx, v, proto.toInt())
    }

    /** A type of no instances made by calling it (Py_TPFLAGS_DISALLOW_INSTANTIATION), a subclass's neither. */
    private fun noNew(t: PyType) {
        val new: (PyContext, Array<Any?>) -> Any? = { _, a ->
            val cls = a.getOrNull(0) as? PyType ?: t
            throw PyRt.typeError("cannot create '${cls.tpName ?: cls.name}' instances")
        }
        t.def("__new__", PyBuiltin("__new__", new, { c, a, _ -> new(c, a) }))
    }

    // ------------------------------------------------------------------ the reader

    /** parse_save_field: the field to the record (None, a float, as quoting has it). */
    private fun saveField(st: State, r: PyCsvReader) {
        val quoting = r.dialect.quoting
        val field: Any?
        if (r.unquoted && r.fieldLen == 0L && (quoting == QUOTE_NOTNULL || quoting == QUOTE_STRINGS)) {
            field = PyNone
        } else {
            var f: Any? = PyStr(r.field.toString())
            if (r.unquoted && r.fieldLen != 0L && (quoting == QUOTE_NONNUMERIC || quoting == QUOTE_STRINGS)) {
                // (PyNumber_Float)
                f = PyCalls.callWith(st.ctx, PyTypes.FLOAT, arrayOf(f), NO_ARGS, NO_ARGS)
            }
            r.field.setLength(0)
            r.fieldLen = 0
            field = f
        }
        // (none where the input advanced the reader itself: PyList_Append's bad internal call)
        val fields = r.fields ?: throw PyException("SystemError", "bad argument to internal function")
        fields.items.add(field)
    }

    /** parse_add_char: the code point [c] to the field, within the field limit. */
    private fun addChar(st: State, r: PyCsvReader, c: Int) {
        val limit = st.fieldLimit
        if (r.fieldLen >= limit) throw st.error("field larger than field limit ($limit)")
        r.field.appendCodePoint(c)
        r.fieldLen++
    }

    /** parse_process_char: one code point ([EOL] at a line's end) through the parser's states. */
    private fun processChar(st: State, r: PyCsvReader, c0: Int) {
        val d = r.dialect
        var c = c0
        when (r.state) {
            START_RECORD -> {
                // (an empty line: [])
                if (c == EOL) return
                if (c == NL || c == CR) {
                    r.state = EAT_CRNL
                    return
                }
                // (a normal character: as START_FIELD)
                r.state = START_FIELD
                startField(st, r, c)
            }
            START_FIELD -> startField(st, r, c)
            ESCAPED_CHAR -> {
                if (c == NL || c == CR) {
                    addChar(st, r, c)
                    r.state = AFTER_ESCAPED_CRNL
                    return
                }
                if (c == EOL) c = NL
                addChar(st, r, c)
                r.state = IN_FIELD
            }
            // (as IN_FIELD, the state kept, but for the line's end)
            AFTER_ESCAPED_CRNL -> if (c != EOL) inField(st, r, c)
            IN_FIELD -> inField(st, r, c)
            IN_QUOTED_FIELD -> {
                if (c == EOL) {
                } else if (c == d.escapechar) {
                    r.state = ESCAPE_IN_QUOTED_FIELD
                } else if (c == d.quotechar && d.quoting != QUOTE_NONE) {
                    // (doublequote: " represented by "", else the quoted part's end)
                    r.state = if (d.doublequote) QUOTE_IN_QUOTED_FIELD else IN_FIELD
                } else {
                    addChar(st, r, c)
                }
            }
            ESCAPE_IN_QUOTED_FIELD -> {
                if (c == EOL) c = NL
                addChar(st, r, c)
                r.state = IN_QUOTED_FIELD
            }
            QUOTE_IN_QUOTED_FIELD -> {
                if (d.quoting != QUOTE_NONE && c == d.quotechar) {
                    // ("" saved as ")
                    addChar(st, r, c)
                    r.state = IN_QUOTED_FIELD
                } else if (c == d.delimiter) {
                    saveField(st, r)
                    r.state = START_FIELD
                } else if (c == NL || c == CR || c == EOL) {
                    saveField(st, r)
                    r.state = if (c == EOL) START_RECORD else EAT_CRNL
                } else if (!d.strict) {
                    addChar(st, r, c)
                    r.state = IN_FIELD
                } else {
                    throw st.error("'${chr(d.delimiter)}' expected after '${chr(d.quotechar)}'")
                }
            }
            EAT_CRNL -> {
                if (c == NL || c == CR) {
                } else if (c == EOL) {
                    r.state = START_RECORD
                } else {
                    throw st.error("new-line character seen in unquoted field - do you need to open the file with newline=''?")
                }
            }
        }
    }

    private fun startField(st: State, r: PyCsvReader, c: Int) {
        val d = r.dialect
        r.unquoted = true
        if (c == NL || c == CR || c == EOL) {
            // (an empty field saved: the record's end)
            saveField(st, r)
            r.state = if (c == EOL) START_RECORD else EAT_CRNL
        } else if (c == d.quotechar && d.quoting != QUOTE_NONE) {
            r.unquoted = false
            r.state = IN_QUOTED_FIELD
        } else if (c == d.escapechar) {
            r.state = ESCAPED_CHAR
        } else if (c == ' '.code && d.skipinitialspace) {
            // (spaces at a field's start ignored)
        } else if (c == d.delimiter) {
            saveField(st, r)
        } else {
            addChar(st, r, c)
            r.state = IN_FIELD
        }
    }

    private fun inField(st: State, r: PyCsvReader, c: Int) {
        val d = r.dialect
        if (c == NL || c == CR || c == EOL) {
            saveField(st, r)
            r.state = if (c == EOL) START_RECORD else EAT_CRNL
        } else if (c == d.escapechar) {
            r.state = ESCAPED_CHAR
        } else if (c == d.delimiter) {
            saveField(st, r)
            r.state = START_FIELD
        } else {
            addChar(st, r, c)
        }
    }

    /** parse_reset. */
    private fun reset(r: PyCsvReader) {
        r.fields = PyList()
        r.field.setLength(0)
        r.fieldLen = 0
        r.state = START_RECORD
        r.unquoted = false
    }

    /** Reader_iternext: the next record, its lines from the input; [PyIterator.STOP] at its end. */
    private fun nextRecord(st: State, r: PyCsvReader): Any? {
        reset(r)
        do {
            val line = r.input.next()
            if (line === PyIterator.STOP) {
                if (r.fieldLen != 0L || r.state == IN_QUOTED_FIELD) {
                    if (r.dialect.strict) throw st.error("unexpected end of data")
                    saveField(st, r)
                    break
                }
                return PyIterator.STOP
            }
            val s = str(line) ?: throw st.error("iterator should return strings, not ${PyTypes.name(line).take(200)} (the file should be opened in text mode)")
            if (r.fields == null) throw st.error("iterator has already advanced the reader")
            r.lineNum++
            val text = s.s
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                processChar(st, r, c)
                i += Character.charCount(c)
            }
            processChar(st, r, EOL)
        } while (r.state != START_RECORD)
        val fields = r.fields
        r.fields = null
        return fields
    }

    private fun defineReader(st: State) {
        val t = st.readerType
        noNew(t)
        fun self(a: Array<Any?>, n: String): PyCsvReader = a.getOrNull(0) as? PyCsvReader
            ?: throw PyRt.typeError("descriptor '$n' for '_csv.reader' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        // (slot wrappers: their message)
        fun slotSelf(a: Array<Any?>, n: String): PyCsvReader = a.getOrNull(0) as? PyCsvReader
            ?: throw PyRt.typeError("descriptor '$n' requires a '_csv.reader' object but received a '${PyTypes.name(a.getOrNull(0))}'")
        t.def("__iter__", PyBuiltin("__iter__") { _, a -> slotSelf(a, "__iter__") })
        t.def("__next__", PyBuiltin("__next__") { _, a ->
            val v = nextRecord(st, slotSelf(a, "__next__"))
            if (v === PyIterator.STOP) throw PyException("StopIteration", "") else v
        })
        member(t, "dialect") { a, n -> self(a, n).dialect }
        member(t, "line_num") { a, n -> self(a, n).lineNum }
        noDict(t)
        t.def("__reduce_ex__", REDUCE_EX)
        t.def("__doc__", PyStr(READER_DOC))
        t.def("__module__", PyStr("_csv"))
        t.dispatchBuiltins = true
    }

    // ------------------------------------------------------------------ the writer

    /** PyNumber_Check: an int, a float, a complex, or what has __index__, __int__ or __float__. */
    private fun isNumber(v: Any?): Boolean = when (v) {
        is Long, is BigInteger, is Double, is PyBool, is PyComplex -> true
        is PyNumSub -> v.num !is PyBytes || numberMethods(v)
        else -> numberMethods(v)
    }

    private fun numberMethods(v: Any?): Boolean {
        if (!PyRt.special(v)) return false
        val t = PyTypes.typeOf(v)
        return PyDunder.userMethod(t, "__index__") != null || PyDunder.userMethod(t, "__int__") != null || PyDunder.userMethod(t, "__float__") != null
    }

    /** join_append (its join_append_data's two phases in one): [field] (null for None) to the record, quoted where it must be. */
    private fun joinAppend(st: State, w: PyCsvWriter, field: String?, quoted0: Boolean) {
        val d = w.dialect
        var quoted = quoted0
        if (field.isNullOrEmpty() && d.delimiter == ' '.code && d.skipinitialspace) {
            if (d.quoting == QUOTE_NONE || (field == null && (d.quoting == QUOTE_STRINGS || d.quoting == QUOTE_NOTNULL))) {
                throw st.error("empty field must be quoted if delimiter is a space and skipinitialspace is true")
            }
            quoted = true
        }
        val body = StringBuilder()
        if (field != null) {
            var i = 0
            while (i < field.length) {
                val c = field.codePointAt(i)
                i += Character.charCount(c)
                var wantEscape = false
                if (c == d.delimiter || c == d.escapechar || c == d.quotechar || c == NL || c == CR || terminatorHas(d, c)) {
                    if (d.quoting == QUOTE_NONE) {
                        wantEscape = true
                    } else {
                        if (c == d.quotechar) {
                            if (d.doublequote) body.appendCodePoint(d.quotechar) else wantEscape = true
                        } else if (c == d.escapechar) {
                            wantEscape = true
                        }
                        if (!wantEscape) quoted = true
                    }
                    if (wantEscape) {
                        if (d.escapechar == NOT_SET) throw st.error("need to escape, but no escapechar set")
                        body.appendCodePoint(d.escapechar)
                    }
                }
                body.appendCodePoint(c)
            }
        }
        // (a field separator where it is not the first)
        if (w.numFields > 0) w.rec.appendCodePoint(d.delimiter)
        if (quoted) w.rec.appendCodePoint(d.quotechar)
        w.rec.append(body)
        if (quoted) w.rec.appendCodePoint(d.quotechar)
        w.numFields++
    }

    /** csv_writerow: the record of [seq]'s fields written, what write returned. */
    private fun writeRow(st: State, w: PyCsvWriter, seq: Any?): Any? {
        val d = w.dialect
        val it = try {
            PyRt.iter(seq)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("TypeError"))) throw st.error("iterable expected, not ${PyTypes.name(seq).take(200)}")
            throw e
        }
        w.rec.setLength(0)
        w.numFields = 0
        var nullField = false
        while (true) {
            val field = it.next()
            if (field === PyIterator.STOP) break
            val quoted = when (d.quoting) {
                QUOTE_NONNUMERIC -> !isNumber(field)
                QUOTE_ALL -> true
                QUOTE_STRINGS -> field is PyStr
                QUOTE_NOTNULL -> field !== PyNone
                else -> false
            }
            nullField = field === PyNone
            joinAppend(st, w, if (field is PyStr) field.s else if (nullField) null else PyRt.str(field), quoted)
        }
        if (w.numFields > 0 && w.rec.isEmpty()) {
            if (d.quoting == QUOTE_NONE || (nullField && (d.quoting == QUOTE_STRINGS || d.quoting == QUOTE_NOTNULL))) {
                throw st.error("single empty field record must be quoted")
            }
            w.numFields--
            joinAppend(st, w, null, true)
        }
        w.rec.append(d.terminator)
        return PyCalls.callWith(st.ctx, w.write, arrayOf(PyStr(w.rec.toString())), NO_ARGS, NO_ARGS)
    }

    /** csv_writerows: each of [rows] written. */
    private fun writeRows(st: State, w: PyCsvWriter, rows: Any?): Any? {
        val it = PyRt.iter(rows)
        while (true) {
            val row = it.next()
            if (row === PyIterator.STOP) break
            writeRow(st, w, row)
        }
        return PyNone
    }

    private fun defineWriter(st: State) {
        val t = st.writerType
        noNew(t)
        fun self(a: Array<Any?>, n: String): PyCsvWriter {
            if (a.isEmpty()) throw PyRt.typeError("unbound method writer.$n() needs an argument")
            return a[0] as? PyCsvWriter
                ?: throw PyRt.typeError("descriptor '$n' for '_csv.writer' objects doesn't apply to a '${PyTypes.name(a[0])}' object")
        }
        // (METH_O)
        fun method(n: String, impl: (PyCsvWriter, Any?) -> Any?) {
            t.def(n, PyBuiltin(n, { _, a ->
                val w = self(a, n)
                if (a.size != 2) throw PyRt.typeError("writer.$n() takes exactly one argument (${a.size - 1} given)")
                impl(w, a[1])
            }, { _, a, _ ->
                self(a, n)
                throw PyRt.typeError("writer.$n() takes no keyword arguments")
            }))
        }
        method("writerow") { w, row -> writeRow(st, w, row) }
        method("writerows") { w, rows -> writeRows(st, w, rows) }
        member(t, "dialect") { a, n -> self(a, n).dialect }
        noDict(t)
        t.def("__reduce_ex__", REDUCE_EX)
        t.def("__doc__", PyStr(WRITER_DOC))
        t.def("__module__", PyStr("_csv"))
        t.dispatchBuiltins = true
    }

    // ------------------------------------------------------------------ the module

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineDialect(st)
        defineReader(st)
        defineWriter(st)
        // (METH_VARARGS | METH_KEYWORDS: the keywords the dialect's)
        fun defVarargs(n: String, impl: (Array<Any?>, PyDict?) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw.takeIf { it.size > 0 }) })
        }
        fun defClinic(n: String, names: List<String>, minPos: Int, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(PyMath.clinic(n, a, null, names, 0, minPos, names.size)) },
                { _, a, kw -> impl(PyMath.clinic(n, a, kw, names, 0, minPos, names.size)) })
        }
        defVarargs("reader") { a, kw ->
            PyMath.checkPositional("reader", a.size, 1, 2)
            val input = PyRt.iter(a[0])
            PyCsvReader(st.readerType, input, callDialect(st, a.getOrNull(1), kw))
        }
        defVarargs("writer") { a, kw ->
            PyMath.checkPositional("writer", a.size, 1, 2)
            // (PyObject_GetOptionalAttr: an AttributeError its absence)
            val write = try {
                PyRt.getAttr(a[0], "write")
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
            }
            if (write == null || !PyCalls.isCallable(write)) throw PyRt.typeError("argument 1 must have a \"write\" method")
            PyCsvWriter(st.writerType, write, callDialect(st, a.getOrNull(1), kw))
        }
        defVarargs("register_dialect") { a, kw ->
            PyMath.checkPositional("register_dialect", a.size, 1, 2)
            val name = str(a[0]) ?: throw PyRt.typeError("dialect name must be a string")
            st.dialects[name] = callDialect(st, a.getOrNull(1), kw)
            PyNone
        }
        d[PyStr("list_dialects")] = PyBuiltin("list_dialects", { _, a ->
            if (a.isNotEmpty()) throw PyRt.typeError("_csv.list_dialects() takes no arguments (${a.size} given)")
            PyList().also { l -> for (k in st.dialects.map.keys) l.items.add(k.v) }
        }, { _, _, _ -> throw PyRt.typeError("_csv.list_dialects() takes no keyword arguments") })
        defClinic("unregister_dialect", listOf("name"), 1) { p ->
            if (st.dialects.map.remove(PyDict.Key(p[0])) == null) throw st.error("unknown dialect")
            PyNone
        }
        defClinic("get_dialect", listOf("name"), 1) { p -> registered(st, p[0]) }
        defClinic("field_size_limit", listOf("new_limit"), 0) { p ->
            val old = st.fieldLimit
            val v = p[0]
            if (v != null) {
                if (v !is Long && v !is BigInteger) throw PyRt.typeError("limit must be an integer")
                st.fieldLimit = v as? Long ?: (v as BigInteger).takeIf { it.bitLength() < 64 }?.toLong()
                    ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
            }
            old
        }
        d[PyStr("Dialect")] = st.dialectType
        d[PyStr("Reader")] = st.readerType
        d[PyStr("Writer")] = st.writerType
        d[PyStr("_dialects")] = st.dialects
        for ((i, n) in QUOTE_STYLES.withIndex()) d[PyStr(n)] = i.toLong()
        d[PyStr("Error")] = st.error
    }
}
