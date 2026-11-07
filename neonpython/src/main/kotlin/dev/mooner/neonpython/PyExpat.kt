package dev.mooner.neonpython

import java.math.BigInteger

/** A pyexpat.xmlparser: its Expat parser, its Python handlers, its character data buffer (CPython's xmlparseobject). */
class PyXmlParser(type: PyType) : PyObject(type) {
    @JvmField var itself: ExpatParser? = null
    @JvmField var parent: PyXmlParser? = null
    @JvmField var orderedAttributes = false
    @JvmField var specifiedAttributes = false
    @JvmField var inCallback = false
    @JvmField var nsPrefixes = false
    /** The character data buffered (buffer_text; null: off), its size and how much of it is used, in bytes of UTF-8. */
    @JvmField var buffer: ByteArray? = null
    @JvmField var bufferSize = 8192
    @JvmField var bufferUsed = 0
    @JvmField var reparseDeferralEnabled = true
    @JvmField var intern: PyDict? = null
    @JvmField val handlers = arrayOfNulls<Any?>(PyExpat.HANDLER_NAMES.size)
    /** The exception a handler raised (CPython's error indicator): what Parse raises when Expat returns. */
    @JvmField var pending: PyException? = null
}

/**
 * `pyexpat` (Modules/pyexpat.c, CPython 3.14.6) over Expat 2.8.1 in Kotlin ([ExpatParser], PyExpatTok.kt,
 * PyExpatRole.kt, PyExpatDtd.kt): ParserCreate and xmlparser, its handlers (each Expat's callback as pyexpat's
 * my_*Handler is), buffer_text, the errors and model submodules, ErrorString, the protection settings. Expat's
 * strings are UTF-8; a handler's arguments are decoded as conv_string_to_unicode and string_intern do. A handler that
 * raises stops the parser (XML_StopParser) and clears the handlers (flag_error); Parse raises its exception when Expat
 * returns. The unknown encoding handler decodes the 256 bytes with the named codec, as PyUnknownEncodingHandler does
 * (one byte a character).
 */
object PyExpat {
    const val DOC = "Python wrapper for Expat parser."

    @JvmField val HANDLER_NAMES = arrayOf("StartElementHandler", "EndElementHandler", "ProcessingInstructionHandler",
        "CharacterDataHandler", "UnparsedEntityDeclHandler", "NotationDeclHandler", "StartNamespaceDeclHandler",
        "EndNamespaceDeclHandler", "CommentHandler", "StartCdataSectionHandler", "EndCdataSectionHandler", "DefaultHandler",
        "DefaultHandlerExpand", "NotStandaloneHandler", "ExternalEntityRefHandler", "StartDoctypeDeclHandler",
        "EndDoctypeDeclHandler", "EntityDeclHandler", "XmlDeclHandler", "ElementDeclHandler", "AttlistDeclHandler",
        "SkippedEntityHandler")

    private const val START_ELEMENT = 0
    private const val END_ELEMENT = 1
    private const val PROCESSING_INSTRUCTION = 2
    private const val CHARACTER_DATA = 3
    private const val UNPARSED_ENTITY_DECL = 4
    private const val NOTATION_DECL = 5
    private const val START_NAMESPACE_DECL = 6
    private const val END_NAMESPACE_DECL = 7
    private const val COMMENT = 8
    private const val START_CDATA_SECTION = 9
    private const val END_CDATA_SECTION = 10
    private const val DEFAULT = 11
    private const val DEFAULT_EXPAND = 12
    private const val NOT_STANDALONE = 13
    private const val EXTERNAL_ENTITY_REF = 14
    private const val START_DOCTYPE_DECL = 15
    private const val END_DOCTYPE_DECL = 16
    private const val ENTITY_DECL = 17
    private const val XML_DECL = 18
    private const val ELEMENT_DECL = 19
    private const val ATTLIST_DECL = 20
    private const val SKIPPED_ENTITY = 21

    private const val CHARACTER_DATA_BUFFER_SIZE = 8192
    private const val MAX_CHUNK_SIZE = 1 shl 20
    private const val BUF_SIZE = 2048
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    private class State(@JvmField val ctx: PyContext) {
        @JvmField val error: PyType = PyBinascii.newException(ctx, "xml.parsers.expat", "ExpatError", PyExceptions.type("Exception"))
        @JvmField val parserType: PyType = PyType("xmlparser", arrayOf(PyTypes.OBJECT), heap = true).also { t ->
            t.mro = PyTypes.c3(t, t.bases)
            t.module = "pyexpat"
            t.tpName = "pyexpat.xmlparser"
            t.subclassable = false
            PyTypes.OBJECT.addSubclass(ctx, t)
        }
    }

    // ------------------------------------------------------------------ strings and errors

    /** A byte string of Expat's (UTF-8) as Python text, up to its NUL. */
    @JvmStatic
    fun text(s: String): String {
        val c = Expat.cstr(s)
        for (ch in c) if (ch.code >= 0x80) return String(Expat.bytes(c), Charsets.UTF_8)
        return c
    }

    /** conv_string_to_unicode: None for NULL. */
    private fun conv(s: String?): Any = if (s == null) PyNone else PyStr(text(s))

    /** conv_string_len_to_unicode */
    private fun convLen(b: ByteArray, off: Int, len: Int): PyStr = PyStr(String(b, off, len, Charsets.UTF_8))

    /** string_intern: the text, the intern dict's own copy where it has one. */
    private fun intern(self: PyXmlParser, s: String?): Any {
        val result = conv(s)
        if (result === PyNone) return result
        val d = self.intern ?: return result
        val v = d[result]
        if (v != null) return v
        d[result] = result
        return result
    }

    /** set_xml_error: ExpatError(message), its code, lineno and offset, raised. */
    private fun xmlError(st: State, code: Int, lineno: Long, column: Long, errmsg: String?): PyException {
        val msg = errmsg ?: "${Expat.ERROR_STRINGS.getOrNull(code) ?: "(null)"}: line $lineno, column $column"
        val ctx = st.ctx
        val res = PyCalls.callWith(ctx, st.error, arrayOf(PyStr(msg)), NO_ARGS, NO_ARGS)
        PyClasses.setAttr(res, "code", code.toLong())
        PyClasses.setAttr(res, "lineno", lineno)
        PyClasses.setAttr(res, "offset", column)
        val o = res as PyBaseException
        val h = ctx.currentHandled()
        if (h != null) o.context = h
        return PyException(o).also { o.thrown = it }
    }

    /** set_error: [code]'s ExpatError at the parser's current position. */
    private fun setError(st: State, self: PyXmlParser, code: Int): PyException {
        val p = self.itself!!
        return xmlError(st, code, p.currentLineNumber(), p.currentColumnNumber(), null)
    }

    /** set_invalid_arg */
    private fun setInvalidArg(st: State, self: PyXmlParser, msg: String): PyException {
        val p = self.itself!!
        return xmlError(st, Expat.XML_ERROR_INVALID_ARGUMENT, p.currentLineNumber(), p.currentColumnNumber(), msg)
    }

    // ------------------------------------------------------------------ the handlers' trampolines

    /** flag_error: every handler cleared, external entities refused from now on. */
    private fun flagError(self: PyXmlParser) {
        clearHandlers(self, false)
        self.itself!!.externalEntityRefHandler = { _, _, _, _ -> 0 }
    }

    /** call_with_frame: [f] called; where it raises, the parser stopped and the exception kept. */
    private fun callWithFrame(self: PyXmlParser, f: Any?, args: Array<Any?>): Any? {
        return try {
            PyCalls.callWith(PyContext.current(), f, args, NO_ARGS, NO_ARGS)
        } catch (e: PyException) {
            self.pending = e
            self.itself!!.stopParser(false)
            null
        }
    }

    /** A handler called (in_callback set meanwhile); false where it raised (flag_error done). */
    private fun call(self: PyXmlParser, which: Int, args: Array<Any?>): Any? {
        self.inCallback = true
        val rv = callWithFrame(self, self.handlers[which], args)
        self.inCallback = false
        if (self.pending != null) {
            flagError(self)
            return null
        }
        return rv
    }

    /** call_character_handler: the character data handler called with [b][off, off+len); false where it raised. */
    private fun callCharacterHandler(self: PyXmlParser, b: ByteArray, off: Int, len: Int): Boolean {
        if (self.handlers[CHARACTER_DATA] == null) return false
        val arg = convLen(b, off, len)
        self.inCallback = true
        callWithFrame(self, self.handlers[CHARACTER_DATA], arrayOf(arg))
        self.inCallback = false
        if (self.pending != null) {
            flagError(self)
            self.itself!!.characterDataHandler = NOOP_CHARACTER_DATA
            return false
        }
        return true
    }

    private val NOOP_CHARACTER_DATA: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }

    /** flush_character_buffer: false where the handler raised. */
    private fun flushCharacterBuffer(self: PyXmlParser): Boolean {
        val buf = self.buffer
        if (buf == null || self.bufferUsed == 0) return true
        val rc = callCharacterHandler(self, buf, 0, self.bufferUsed)
        self.bufferUsed = 0
        return rc
    }

    /** my_CharacterDataHandler: the data buffered (buffer_text), or given to the handler. */
    private fun characterData(self: PyXmlParser, b: ByteArray, off: Int, len: Int) {
        if (self.pending != null) return
        val buf = self.buffer
        if (buf == null) {
            callCharacterHandler(self, b, off, len)
            return
        }
        if (len > self.bufferSize - self.bufferUsed) {
            if (!flushCharacterBuffer(self)) return
            // (the handler might have changed: the rest dropped where there is none)
            if (self.handlers[CHARACTER_DATA] == null) return
        }
        if (len > self.bufferSize) {
            callCharacterHandler(self, b, off, len)
            self.bufferUsed = 0
        } else {
            System.arraycopy(b, off, self.buffer!!, self.bufferUsed, len)
            self.bufferUsed += len
        }
    }

    /** RC_HANDLER's start: whether to call the Python handler [which] (it is set, no error, the buffer flushed). */
    private fun ready(self: PyXmlParser, which: Int): Boolean =
        self.handlers[which] != null && self.pending == null && flushCharacterBuffer(self)

    /** VOID_HANDLER */
    private fun void(self: PyXmlParser, which: Int, args: () -> Array<Any?>) {
        if (!ready(self, which)) return
        call(self, which, args())
    }

    /** INT_HANDLER (and ExternalEntityRef's): the handler's result as a C long (-1 where it is no int). */
    private fun int(self: PyXmlParser, which: Int, args: () -> Array<Any?>): Int {
        if (!ready(self, which)) return 0
        val rv = call(self, which, args()) ?: return 0
        return try {
            when (val i = PyRt.indexValue(rv) ?: throw PyRt.typeError("'${PyTypes.name(rv)}' object cannot be interpreted as an integer")) {
                is Long -> if (i > Int.MAX_VALUE || i < Int.MIN_VALUE) (i.toInt() or 1) else i.toInt()
                else -> throw PyException("OverflowError", "Python int too large to convert to C long")
            }
        } catch (e: PyException) {
            self.pending = e
            -1
        }
    }

    /** my_StartElementHandler: the attributes as a dict (or list, ordered_attributes), the specified ones only where asked. */
    private fun startElement(self: PyXmlParser, name: String, atts: Array<String?>) {
        if (self.handlers[START_ELEMENT] == null) return
        if (self.pending != null) return
        if (!flushCharacterBuffer(self)) return
        val max = if (self.specifiedAttributes) self.itself!!.nSpecifiedAtts else {
            var m = 0
            while (atts[m] != null) m += 2
            m
        }
        val container: Any
        if (self.orderedAttributes) {
            val l = ArrayList<Any?>(max)
            var i = 0
            while (i < max) {
                l.add(intern(self, atts[i]))
                l.add(conv(atts[i + 1]))
                i += 2
            }
            container = PyList(l)
        } else {
            val d = PyDict()
            var i = 0
            while (i < max) {
                val n = intern(self, atts[i])
                d[n] = conv(atts[i + 1])
                i += 2
            }
            container = d
        }
        call(self, START_ELEMENT, arrayOf(intern(self, name), container))
    }

    /** conv_content_model */
    private fun contentModel(m: XContent): PyTuple {
        val children = Array<Any?>(m.children.size) { contentModel(m.children[it]) }
        return PyTuple(arrayOf(m.type.toLong(), m.quant.toLong(), conv(m.name), PyTuple(children)))
    }

    /** The handler [which] given to Expat ([on]), or taken from it (CALL_XML_HANDLER_SETTER). */
    private fun install(self: PyXmlParser, which: Int, on: Boolean) {
        val p = self.itself!!
        when (which) {
            START_ELEMENT -> p.startElementHandler = if (on) { n, a -> startElement(self, n, a) } else null
            END_ELEMENT -> p.endElementHandler = if (on) { n -> void(self, which) { arrayOf(intern(self, n)) } } else null
            PROCESSING_INSTRUCTION -> p.processingInstructionHandler = if (on) { t, d -> void(self, which) { arrayOf(intern(self, t), conv(d)) } } else null
            CHARACTER_DATA -> p.characterDataHandler = if (on) { b, o, l -> characterData(self, b, o, l) } else null
            UNPARSED_ENTITY_DECL -> p.unparsedEntityDeclHandler = if (on) { n, b, s, pu, no ->
                void(self, which) { arrayOf(intern(self, n), intern(self, b), intern(self, s), intern(self, pu), intern(self, no)) }
            } else null
            NOTATION_DECL -> p.notationDeclHandler = if (on) { n, b, s, pu ->
                void(self, which) { arrayOf(intern(self, n), intern(self, b), intern(self, s), intern(self, pu)) }
            } else null
            START_NAMESPACE_DECL -> p.startNamespaceDeclHandler = if (on) { pr, u -> void(self, which) { arrayOf(intern(self, pr), intern(self, u)) } } else null
            END_NAMESPACE_DECL -> p.endNamespaceDeclHandler = if (on) { pr -> void(self, which) { arrayOf(intern(self, pr)) } } else null
            COMMENT -> p.commentHandler = if (on) { d -> void(self, which) { arrayOf(conv(d)) } } else null
            START_CDATA_SECTION -> p.startCdataSectionHandler = if (on) { -> void(self, which) { NO_ARGS } } else null
            END_CDATA_SECTION -> p.endCdataSectionHandler = if (on) { -> void(self, which) { NO_ARGS } } else null
            DEFAULT -> p.setDefaultHandler(if (on) { b, o, l -> void(self, which) { arrayOf(convLen(b, o, l)) } } else null, false)
            DEFAULT_EXPAND -> p.setDefaultHandler(if (on) { b, o, l -> void(self, which) { arrayOf(convLen(b, o, l)) } } else null, true)
            NOT_STANDALONE -> p.notStandaloneHandler = if (on) { -> int(self, which) { NO_ARGS } } else null
            EXTERNAL_ENTITY_REF -> p.externalEntityRefHandler = if (on) { c, b, s, pu ->
                int(self, which) { arrayOf(conv(c), intern(self, b), intern(self, s), intern(self, pu)) }
            } else null
            START_DOCTYPE_DECL -> p.startDoctypeDeclHandler = if (on) { n, s, pu, has ->
                void(self, which) { arrayOf(intern(self, n), intern(self, s), intern(self, pu), has.toLong()) }
            } else null
            END_DOCTYPE_DECL -> p.endDoctypeDeclHandler = if (on) { -> void(self, which) { NO_ARGS } } else null
            ENTITY_DECL -> p.entityDeclHandler = if (on) { n, isParam, v, b, s, pu, no ->
                void(self, which) {
                    arrayOf(intern(self, n), isParam.toLong(), if (v == null) PyNone else convLen(v, 0, v.size), intern(self, b),
                        intern(self, s), intern(self, pu), intern(self, no))
                }
            } else null
            XML_DECL -> p.xmlDeclHandler = if (on) { v, e, s -> void(self, which) { arrayOf(conv(v), conv(e), s.toLong()) } } else null
            ELEMENT_DECL -> p.elementDeclHandler = if (on) { n, m -> void(self, which) { arrayOf(intern(self, n), contentModel(m)) } } else null
            ATTLIST_DECL -> p.attlistDeclHandler = if (on) { el, att, t, d, req ->
                void(self, which) { arrayOf(intern(self, el), intern(self, att), conv(t), conv(d), req.toLong()) }
            } else null
            SKIPPED_ENTITY -> p.skippedEntityHandler = if (on) { n, isParam -> void(self, which) { arrayOf(intern(self, n), isParam.toLong()) } } else null
        }
    }

    /** clear_handlers: the Python handlers dropped ([initial]: only that), else each taken from Expat too. */
    private fun clearHandlers(self: PyXmlParser, initial: Boolean) {
        for (i in HANDLER_NAMES.indices) {
            self.handlers[i] = null
            if (!initial) install(self, i, false)
        }
    }

    /** PyUnknownEncodingHandler: the 256 bytes decoded by [name]'s codec ("replace"), each byte's character. */
    private fun unknownEncoding(st: State, self: PyXmlParser, name: String): IntArray? {
        if (self.pending != null) return null
        val template = ByteArray(256) { it.toByte() }
        val u = try {
            PyCodecs.decodeText(st.ctx, template, text(name), "replace")
        } catch (e: PyException) {
            self.pending = e
            return null
        }
        val cps = u.s.codePoints().toArray()
        if (cps.size != 256) {
            self.pending = PyException("ValueError", "multi-byte encodings are not supported")
            return null
        }
        return IntArray(256) { if (cps[it] != 0xFFFD) cps[it] else -1 }
    }

    // ------------------------------------------------------------------ xmlparser's methods

    /** get_parse_result */
    private fun parseResult(st: State, self: PyXmlParser, rv: Int): Any? {
        self.pending?.let {
            self.pending = null
            throw it
        }
        if (rv == 0) throw setError(st, self, self.itself!!.errorCode)
        if (!flushCharacterBuffer(self)) {
            val e = self.pending!!
            self.pending = null
            throw e
        }
        return rv.toLong()
    }

    /** Parse(data, isfinal=False, /) */
    private fun parse(st: State, self: PyXmlParser, data: Any?, isFinal: Boolean): Any? {
        val p = self.itself!!
        val s: ByteArray
        if (data is PyStr) {
            s = PyCodecs.encodeUtf8(st.ctx, PyStr(data.s), "strict")
            // (explicitly UTF-8: the return code ignored)
            p.setEncoding("utf-8")
        } else s = PyBinascii.buffer(data)
        var off = 0
        var slen = s.size
        var rc: Int
        while (true) {
            if (slen <= MAX_CHUNK_SIZE) {
                rc = p.parse(s, off, slen, isFinal)
                break
            }
            rc = p.parse(s, off, MAX_CHUNK_SIZE, false)
            if (rc == 0) break
            off += MAX_CHUNK_SIZE
            slen -= MAX_CHUNK_SIZE
        }
        return parseResult(st, self, rc)
    }

    /** readinst: read(buf_size) into the parser's buffer at [at]: the count, or an exception thrown. */
    private fun readInst(p: ExpatParser, at: Int, meth: Any?): Int {
        val v = PyCalls.callWith(PyContext.current(), meth, arrayOf(BUF_SIZE.toLong()), NO_ARGS, NO_ARGS)
        val b = when (v) {
            is PyBytes -> v.b
            is PyByteArray -> v.bytes()
            else -> {
                val sub = (v as? PyNumSub)?.num as? PyBytes
                sub?.b ?: throw PyRt.typeError("read() did not return a bytes object (type=${PyTypes.name(v)})")
            }
        }
        if (b.size > BUF_SIZE) throw PyException("ValueError", "read() returned too much data: $BUF_SIZE bytes requested, ${b.size} returned")
        System.arraycopy(b, 0, p.buffer!!, at, b.size)
        return b.size
    }

    /** ParseFile(file, /) */
    private fun parseFile(st: State, self: PyXmlParser, file: Any?): Any? {
        val read = try {
            PyRt.getAttr(file, "read")
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
        } ?: throw PyRt.typeError("argument must have 'read' attribute")
        val p = self.itself!!
        var rv: Int
        while (true) {
            val at = p.getBuffer(BUF_SIZE)
            if (at < 0) return parseResult(st, self, 0)
            val n = readInst(p, at, read)
            rv = p.parseBuffer(n, n == 0)
            self.pending?.let {
                self.pending = null
                throw it
            }
            if (rv == 0 || n == 0) break
        }
        return parseResult(st, self, rv)
    }

    /** ExternalEntityParserCreate(context, encoding=None, /) */
    private fun externalEntityParserCreate(st: State, self: PyXmlParser, context: String?, encoding: String?): PyXmlParser {
        val np = PyXmlParser(st.parserType)
        np.bufferSize = self.bufferSize
        np.orderedAttributes = self.orderedAttributes
        np.specifiedAttributes = self.specifiedAttributes
        np.nsPrefixes = self.nsPrefixes
        np.reparseDeferralEnabled = self.reparseDeferralEnabled
        np.itself = self.itself!!.externalEntityParserCreate(context, encoding) ?: throw PyException("MemoryError", "")
        np.parent = self
        np.intern = self.intern
        if (self.buffer != null) np.buffer = ByteArray(np.bufferSize)
        clearHandlers(np, true)
        // (then the handlers copied from self)
        for (i in HANDLER_NAMES.indices) {
            val h = self.handlers[i] ?: continue
            np.handlers[i] = h
            install(np, i, true)
        }
        return np
    }

    /** A C string argument (PyUnicode_AsUTF8AndSize, no NUL in it) as Expat's byte string. */
    private fun cString(ctx: PyContext, v: PyStr): String {
        val b = PyCodecs.encodeUtf8(ctx, v, "strict")
        for (x in b) if (x.toInt() == 0) throw PyException("ValueError", "embedded null character")
        return String(b, Charsets.ISO_8859_1)
    }

    private fun isStr(v: Any?): Boolean = v is PyStr

    private fun strOf(v: Any?): PyStr = PyStr((v as PyStr).s)

    /** _PyArg_BadArgument */
    private fun badArgument(fn: String, what: String, expected: String, v: Any?) =
        PyRt.typeError("$fn() $what must be $expected, not ${if (v === PyNone) "None" else PyTypes.name(v)}")

    /** _PyLong_UnsignedLongLong_Converter: the bits of an unsigned long long. */
    private fun unsignedLongLong(v: Any?): Long {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        val big = if (i is Long) BigInteger.valueOf(i) else i as BigInteger
        if (big.signum() < 0) throw PyException("ValueError", "Cannot convert negative int")
        if (big.bitLength() > 64) throw PyException("OverflowError", "Python int too large for C unsigned long long")
        return big.toLong()
    }

    /** PyLong_AsLong (a C long, Linux's 64 bits). */
    private fun cLong(v: Any?): Long {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return i as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
    }

    private fun self(st: State, a: Array<Any?>, n: String): PyXmlParser {
        if (a.isEmpty()) throw PyRt.typeError("unbound method xmlparser.$n() needs an argument")
        val o = a[0]
        if (o !is PyXmlParser) throw PyRt.typeError("descriptor '$n' for 'pyexpat.xmlparser' objects doesn't apply to a '${PyTypes.name(o)}' object")
        return o
    }

    /** A method of positional arguments only, by Argument Clinic ([min] to [max] of them). */
    private fun clinic(st: State, t: PyType, n: String, min: Int, max: Int, impl: (PyXmlParser, Array<Any?>) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(st, a, n)
            val names = List(max) { "" }
            return impl(o, PyMath.clinic(n, a.copyOfRange(1, a.size), kw, names, max, min, max))
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A METH_O method. */
    private fun methO(st: State, t: PyType, n: String, impl: (PyXmlParser, Any?) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(st, a, n)
            if (kw != null && kw.size > 0) throw PyRt.typeError("xmlparser.$n() takes no keyword arguments")
            if (a.size != 2) throw PyRt.typeError("xmlparser.$n() takes exactly one argument (${a.size - 1} given)")
            return impl(o, a[1])
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A METH_NOARGS method. */
    private fun methNoArgs(st: State, t: PyType, n: String, impl: (PyXmlParser) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(st, a, n)
            if (kw != null && kw.size > 0) throw PyRt.typeError("xmlparser.$n() takes no keyword arguments")
            if (a.size != 1) throw PyRt.typeError("xmlparser.$n() takes no arguments (${a.size - 1} given)")
            return impl(o)
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A getset attribute: its getter, and its setter (null: not writable) which a deletion meets as CPython's does. */
    private fun getset(st: State, t: PyType, n: String, get: (PyXmlParser) -> Any?, set: ((PyXmlParser, Any?) -> Unit)?) {
        val fset: PyBuiltin
        val fdel: PyBuiltin
        if (set == null) {
            fset = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "attribute '$n' of 'pyexpat.xmlparser' objects is not writable") }
            fdel = fset
        } else {
            fset = PyBuiltin(n) { _, a ->
                set(self(st, a, n), a[1])
                PyNone
            }
            fdel = PyBuiltin(n) { _, _ -> throw PyException("RuntimeError", "Cannot delete attribute") }
        }
        t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(st, a, n)) }, fset, fdel, null).also { it.name = n })
    }

    /** xmlparse_handler_setter */
    private fun setHandler(self: PyXmlParser, which: Int, v0: Any?) {
        var v = v0
        // (changing the character data handler: what is buffered flushed with the old one)
        if (which == CHARACTER_DATA && !flushCharacterBuffer(self)) {
            val e = self.pending!!
            self.pending = null
            throw e
        }
        if (v === PyNone) {
            v = null
            self.handlers[which] = null
            // (the character data handler replaced inside one of its calls: a no-op one, refusing to call Python)
            if (which == CHARACTER_DATA && self.inCallback) {
                self.itself!!.characterDataHandler = NOOP_CHARACTER_DATA
                return
            }
            install(self, which, false)
            return
        }
        self.handlers[which] = v
        install(self, which, true)
    }

    private fun defineParserType(st: State) {
        val t = st.parserType
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (t.lookup(n) is PyProperty) PyClasses.genericSetAttr(a[0], n, a[2])
            else throw PyException("AttributeError", "'pyexpat.xmlparser' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (t.lookup(n) is PyProperty) PyClasses.genericDelAttr(a[0], n)
            else throw PyException("AttributeError", "'pyexpat.xmlparser' object has no attribute '$n'")
            PyNone
        })
        t.hooks = 2 or 4
        t.def("__module__", PyStr("pyexpat"))
        t.def("__doc__", PyStr("XML parser"))
        t.dispatchBuiltins = true
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'pyexpat.xmlparser' instances") }

        clinic(st, t, "Parse", 1, 2) { o, p -> parse(st, o, p[0], p[1] != null && PyRt.truthy(p[1])) }
        clinic(st, t, "ParseFile", 1, 1) { o, p -> parseFile(st, o, p[0]) }
        methO(st, t, "SetBase") { o, v ->
            if (!isStr(v)) throw badArgument("SetBase", "argument", "str", v)
            o.itself!!.setBase(cString(st.ctx, strOf(v)))
            PyNone
        }
        methNoArgs(st, t, "GetBase") { o -> conv(o.itself!!.curBase) }
        methNoArgs(st, t, "GetInputContext") { o ->
            if (o.inCallback) o.itself!!.inputContext()?.let { PyBytes(it) } ?: PyNone else PyNone
        }
        clinic(st, t, "ExternalEntityParserCreate", 1, 2) { o, p ->
            val c = p[0]
            val context = if (c === PyNone) null else if (isStr(c)) cString(st.ctx, strOf(c))
                else throw badArgument("ExternalEntityParserCreate", "argument 1", "str or None", c)
            val e = p[1]
            val encoding = if (e == null) null else if (isStr(e)) cString(st.ctx, strOf(e))
                else throw badArgument("ExternalEntityParserCreate", "argument 2", "str", e)
            externalEntityParserCreate(st, o, context, encoding)
        }
        methO(st, t, "SetParamEntityParsing") { o, v -> o.itself!!.setParamEntityParsing(PyBinascii.cInt(v)).toLong() }
        clinic(st, t, "UseForeignDTD", 0, 1) { o, p ->
            val flag = if (p[0] == null) true else PyRt.truthy(p[0])
            val rc = o.itself!!.useForeignDTD(flag)
            if (rc != Expat.XML_ERROR_NONE) throw setError(st, o, rc)
            PyNone
        }
        fun threshold(n: String, set: (ExpatParser, Long) -> Boolean) = clinic(st, t, n, 1, 1) { o, p ->
            if (!set(o.itself!!, unsignedLongLong(p[0]))) throw setInvalidArg(st, o, "parser must be a root parser")
            PyNone
        }
        fun amplification(n: String, set: (ExpatParser, Float) -> Boolean) = clinic(st, t, n, 1, 1) { o, p ->
            val f = PyMath.asDouble(p[0]).toFloat()
            if (!set(o.itself!!, f)) {
                throw setInvalidArg(st, o, if (f.isNaN() || f < 1.0f) "'max_factor' must be at least 1.0" else "parser must be a root parser")
            }
            PyNone
        }
        threshold("SetBillionLaughsAttackProtectionActivationThreshold") { p, v -> p.setBlapActivationThreshold(v) }
        amplification("SetBillionLaughsAttackProtectionMaximumAmplification") { p, v -> p.setBlapMaximumAmplification(v) }
        threshold("SetAllocTrackerActivationThreshold") { p, v -> p.setAllocActivationThreshold(v) }
        amplification("SetAllocTrackerMaximumAmplification") { p, v -> p.setAllocMaximumAmplification(v) }
        methO(st, t, "SetReparseDeferralEnabled") { o, v ->
            val enabled = PyRt.truthy(v)
            o.itself!!.reparseDeferralEnabled = enabled
            o.reparseDeferralEnabled = enabled
            PyNone
        }
        methNoArgs(st, t, "GetReparseDeferralEnabled") { o -> PyBool.of(o.reparseDeferralEnabled) }

        for ((i, n) in HANDLER_NAMES.withIndex()) getset(st, t, n, { o -> o.handlers[i] ?: PyNone }) { o, v -> setHandler(o, i, v) }
        getset(st, t, "ErrorCode", { o -> o.itself!!.errorCode.toLong() }, null)
        getset(st, t, "ErrorLineNumber", { o -> o.itself!!.currentLineNumber() }, null)
        getset(st, t, "ErrorColumnNumber", { o -> o.itself!!.currentColumnNumber() }, null)
        getset(st, t, "ErrorByteIndex", { o -> o.itself!!.currentByteIndex() }, null)
        getset(st, t, "CurrentLineNumber", { o -> o.itself!!.currentLineNumber() }, null)
        getset(st, t, "CurrentColumnNumber", { o -> o.itself!!.currentColumnNumber() }, null)
        getset(st, t, "CurrentByteIndex", { o -> o.itself!!.currentByteIndex() }, null)
        getset(st, t, "buffer_size", { o -> o.bufferSize.toLong() }) { o, v -> setBufferSize(o, v) }
        getset(st, t, "buffer_text", { o -> PyBool.of(o.buffer != null) }) { o, v ->
            if (PyRt.truthy(v)) {
                if (o.buffer == null) {
                    o.buffer = ByteArray(o.bufferSize)
                    o.bufferUsed = 0
                }
            } else if (o.buffer != null) {
                if (!flushCharacterBuffer(o)) {
                    val e = o.pending!!
                    o.pending = null
                    throw e
                }
                o.buffer = null
            }
        }
        getset(st, t, "buffer_used", { o -> o.bufferUsed.toLong() }, null)
        getset(st, t, "namespace_prefixes", { o -> PyBool.of(o.nsPrefixes) }) { o, v ->
            o.nsPrefixes = PyRt.truthy(v)
            o.itself!!.setReturnNSTriplet(o.nsPrefixes)
        }
        getset(st, t, "ordered_attributes", { o -> PyBool.of(o.orderedAttributes) }) { o, v -> o.orderedAttributes = PyRt.truthy(v) }
        getset(st, t, "specified_attributes", { o -> PyBool.of(o.specifiedAttributes) }) { o, v -> o.specifiedAttributes = PyRt.truthy(v) }
        val readonly = PyBuiltin("intern") { _, _ -> throw PyException("AttributeError", "readonly attribute") }
        t.def("intern", PyProperty(PyBuiltin("intern") { _, a -> self(st, a, "intern").intern ?: PyNone }, readonly, readonly, null).also { it.name = "intern" })
    }

    /** xmlparse_buffer_size_setter: a new buffer (buffer_text on), what was buffered flushed first. */
    private fun setBufferSize(o: PyXmlParser, v: Any?) {
        if (!(v is Long || v is BigInteger || v is PyBool || (v is PyNumSub && v.num !is Double))) throw PyRt.typeError("buffer_size must be an integer")
        val n = cLong(v)
        if (n <= 0) throw PyException("ValueError", "buffer_size must be greater than zero")
        if (n == o.bufferSize.toLong()) return
        if (n > Int.MAX_VALUE) throw PyException("ValueError", "buffer_size must not be greater than ${Int.MAX_VALUE}")
        if (o.buffer != null && o.bufferUsed != 0 && !flushCharacterBuffer(o)) {
            val e = o.pending!!
            o.pending = null
            throw e
        }
        o.buffer = ByteArray(n.toInt())
        o.bufferSize = n.toInt()
    }

    // ------------------------------------------------------------------ the module

    /** newxmlparseobject */
    private fun newParser(st: State, encoding: String?, nsSep: String?, intern: PyDict?): PyXmlParser {
        val self = PyXmlParser(st.parserType)
        self.bufferSize = CHARACTER_DATA_BUFFER_SIZE
        self.intern = intern
        val p = ExpatParser(encoding, if (nsSep == null) -1 else if (nsSep.isEmpty()) 0 else nsSep[0].code, null, null)
        self.itself = p
        p.unknownEncodingHandler = { name -> unknownEncoding(st, self, name) }
        clearHandlers(self, true)
        return self
    }

    private fun submodule(ctx: PyContext, d: PyDict, fullname: String, doc: String, fill: (PyDict) -> Unit) {
        val md = PyDict()
        md[PyStr("__name__")] = PyStr(fullname)
        md[PyStr("__doc__")] = PyNone
        md[PyStr("__package__")] = PyNone
        md[PyStr("__loader__")] = PyNone
        md[PyStr("__spec__")] = PyNone
        fill(md)
        md[PyStr("__doc__")] = PyStr(doc)
        val m = PyModule(fullname, md)
        ctx.modules[PyStr(fullname)] = m
        d[PyStr(fullname.substringAfterLast('.'))] = m
    }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineParserType(st)
        fun parserCreate(a: Array<Any?>, kw: PyDict?): Any? {
            val p = PyMath.clinic("ParserCreate", a, kw, listOf("encoding", "namespace_separator", "intern"), 0, 0, 3)
            val e = p[0]
            val encoding = if (e == null || e === PyNone) null else if (isStr(e)) cString(ctx, strOf(e))
                else throw badArgument("ParserCreate", "argument 'encoding'", "str or None", e)
            val s = p[1]
            val nsSep = if (s == null || s === PyNone) null else if (isStr(s)) cString(ctx, strOf(s))
                else throw badArgument("ParserCreate", "argument 'namespace_separator'", "str or None", s)
            if (nsSep != null && nsSep.length > 1) throw PyException("ValueError", "namespace_separator must be at most one character, omitted, or None")
            // (None: no interning; omitted: a new dict)
            val i = p[2]
            val intern = when {
                i === PyNone -> null
                i == null -> PyDict()
                i is PyDict -> i
                else -> throw PyRt.typeError("intern must be a dictionary")
            }
            return newParser(st, encoding, nsSep, intern)
        }
        d[PyStr("ParserCreate")] = PyBuiltin("ParserCreate", { _, a -> parserCreate(a, null) }, { _, a, kw -> parserCreate(a, kw) })
        fun errorString(a: Array<Any?>): Any? {
            if (a.size != 1) throw PyRt.typeError("ErrorString() takes exactly one argument (${a.size} given)")
            return conv(Expat.errorString(cLong(a[0])))
        }
        d[PyStr("ErrorString")] = PyBuiltin("ErrorString", { _, a -> errorString(a) }, { _, a, kw ->
            if (kw.size > 0) throw PyRt.typeError("ErrorString() takes no keyword arguments")
            errorString(a)
        })
        d[PyStr("error")] = st.error
        d[PyStr("ExpatError")] = st.error
        d[PyStr("XMLParserType")] = st.parserType
        d[PyStr("EXPAT_VERSION")] = PyStr("expat_${Expat.MAJOR_VERSION}.${Expat.MINOR_VERSION}.${Expat.MICRO_VERSION}")
        d[PyStr("version_info")] = PyTuple(arrayOf(Expat.MAJOR_VERSION.toLong(), Expat.MINOR_VERSION.toLong(), Expat.MICRO_VERSION.toLong()))
        // (XXX When Expat supports some way of figuring out how it was compiled, this should check and set native_encoding)
        d[PyStr("native_encoding")] = PyStr("UTF-8")
        submodule(ctx, d, "pyexpat.errors", "Constants used to describe error conditions.") { md ->
            val codes = PyDict()
            val messages = PyDict()
            for (code in 1 until Expat.ERROR_STRINGS.size) {
                val msg = Expat.ERROR_STRINGS[code]!!
                md[PyStr(ERROR_NAMES[code])] = PyStr(msg)
                codes[PyStr(msg)] = code.toLong()
                messages[code.toLong()] = PyStr(msg)
            }
            md[PyStr("codes")] = codes
            md[PyStr("messages")] = messages
        }
        submodule(ctx, d, "pyexpat.model", "Constants used to interpret content model information.") { md ->
            for ((n, v) in listOf("XML_CTYPE_EMPTY" to Expat.XML_CTYPE_EMPTY, "XML_CTYPE_ANY" to Expat.XML_CTYPE_ANY,
                "XML_CTYPE_MIXED" to Expat.XML_CTYPE_MIXED, "XML_CTYPE_NAME" to Expat.XML_CTYPE_NAME,
                "XML_CTYPE_CHOICE" to Expat.XML_CTYPE_CHOICE, "XML_CTYPE_SEQ" to Expat.XML_CTYPE_SEQ,
                "XML_CQUANT_NONE" to Expat.XML_CQUANT_NONE, "XML_CQUANT_OPT" to Expat.XML_CQUANT_OPT,
                "XML_CQUANT_REP" to Expat.XML_CQUANT_REP, "XML_CQUANT_PLUS" to Expat.XML_CQUANT_PLUS)) md[PyStr(n)] = v.toLong()
        }
        d[PyStr("features")] = PyList(ArrayList(Expat.FEATURES.map { (n, v) -> PyTuple(arrayOf(PyStr(n), v)) }))
        d[PyStr("XML_PARAM_ENTITY_PARSING_NEVER")] = Expat.XML_PARAM_ENTITY_PARSING_NEVER.toLong()
        d[PyStr("XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE")] = Expat.XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE.toLong()
        d[PyStr("XML_PARAM_ENTITY_PARSING_ALWAYS")] = Expat.XML_PARAM_ENTITY_PARSING_ALWAYS.toLong()
        d[PyStr("expat_CAPI")] = PyObject(PyUnicodedata.CAPSULE)
    }

    /** The errors module's names, by code. */
    private val ERROR_NAMES = arrayOf("", "XML_ERROR_NO_MEMORY", "XML_ERROR_SYNTAX", "XML_ERROR_NO_ELEMENTS",
        "XML_ERROR_INVALID_TOKEN", "XML_ERROR_UNCLOSED_TOKEN", "XML_ERROR_PARTIAL_CHAR", "XML_ERROR_TAG_MISMATCH",
        "XML_ERROR_DUPLICATE_ATTRIBUTE", "XML_ERROR_JUNK_AFTER_DOC_ELEMENT", "XML_ERROR_PARAM_ENTITY_REF",
        "XML_ERROR_UNDEFINED_ENTITY", "XML_ERROR_RECURSIVE_ENTITY_REF", "XML_ERROR_ASYNC_ENTITY", "XML_ERROR_BAD_CHAR_REF",
        "XML_ERROR_BINARY_ENTITY_REF", "XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF", "XML_ERROR_MISPLACED_XML_PI",
        "XML_ERROR_UNKNOWN_ENCODING", "XML_ERROR_INCORRECT_ENCODING", "XML_ERROR_UNCLOSED_CDATA_SECTION",
        "XML_ERROR_EXTERNAL_ENTITY_HANDLING", "XML_ERROR_NOT_STANDALONE", "XML_ERROR_UNEXPECTED_STATE",
        "XML_ERROR_ENTITY_DECLARED_IN_PE", "XML_ERROR_FEATURE_REQUIRES_XML_DTD", "XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING",
        "XML_ERROR_UNBOUND_PREFIX", "XML_ERROR_UNDECLARING_PREFIX", "XML_ERROR_INCOMPLETE_PE", "XML_ERROR_XML_DECL",
        "XML_ERROR_TEXT_DECL", "XML_ERROR_PUBLICID", "XML_ERROR_SUSPENDED", "XML_ERROR_NOT_SUSPENDED", "XML_ERROR_ABORTED",
        "XML_ERROR_FINISHED", "XML_ERROR_SUSPEND_PE", "XML_ERROR_RESERVED_PREFIX_XML", "XML_ERROR_RESERVED_PREFIX_XMLNS",
        "XML_ERROR_RESERVED_NAMESPACE_URI", "XML_ERROR_INVALID_ARGUMENT", "XML_ERROR_NO_BUFFER",
        "XML_ERROR_AMPLIFICATION_LIMIT_BREACH", "XML_ERROR_NOT_STARTED")
}
