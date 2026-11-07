package dev.mooner.neonpython

import java.math.BigInteger

/** The builtins module (the slice's part of it), and the methods of the runtime's types. */
object PyBuiltins {
    private fun argc(name: String, args: Array<Any?>, min: Int, max: Int = min) {
        if (args.size < min || args.size > max) {
            val want = if (min == max) "exactly $min" else if (args.size < min) "at least $min" else "at most $max"
            throw PyRt.typeError("$name() takes $want argument${if (max == 1 && min == 1) "" else "s"} (${args.size} given)")
        }
    }

    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** staticmethod's and classmethod's count: "staticmethod expected 1 argument, got 2". */
    private fun one(name: String, args: Array<Any?>) {
        if (args.size != 1) throw PyRt.typeError("$name expected 1 argument, got ${args.size}")
    }

    private fun longOf(v0: Any?, what: String): Long = when (val v = PyRt.indexValue(v0) ?: v0) {
        is Long -> v
        is PyBool -> if (v.value) 1 else 0
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C long")
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer ($what)")
    }

    /** int()'s argument: a subclass's instance's number, what a class's __int__ (else __index__) returns. */
    private fun intArg(v: Any?): Any? {
        if (v is PyNumSub) return v.num
        if (!PyRt.special(v)) return v
        val t = PyTypes.typeOf(v)
        PyDunder.userMethod(t, "__int__")?.let { f ->
            return when (val r = PyCalls.callWith(PyContext.current(), f, arrayOf(v), arrayOfNulls(0), arrayOfNulls(0))) {
                is Long, is BigInteger, is PyBool -> r
                is PyNumSub -> r.num.takeIf { it !is Double } ?: throw PyRt.typeError("__int__ returned non-int (type ${PyTypes.name(r)})")
                else -> throw PyRt.typeError("__int__ returned non-int (type ${PyTypes.name(r)})")
            }
        }
        return PyRt.indexValue(v) ?: v
    }

    /** A dict of the builtins. */
    fun module(): PyDict {
        val d = PyDict()
        fun def(name: String, impl: (PyContext, Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, impl)
        }
        // print(*objects, sep=' ', end='\n', file=None, flush=False): to sys.stdout as it is now where file is None
        fun print(ctx: PyContext, args: Array<Any?>, kw: PyDict?): Any? {
            var sep = " "
            var end = "\n"
            var file: Any? = PyNone
            var flush = false
            if (kw != null) for ((k, v) in kw.map) {
                when ((k.v as PyStr).s) {
                    "sep" -> sep = if (v === PyNone) " " else (v as? PyStr)?.s ?: throw PyRt.typeError("sep must be None or a string, not ${PyTypes.name(v)}")
                    "end" -> end = if (v === PyNone) "\n" else (v as? PyStr)?.s ?: throw PyRt.typeError("end must be None or a string, not ${PyTypes.name(v)}")
                    "flush" -> flush = PyRt.truthy(v)
                    "file" -> file = v
                    else -> throw PyRt.typeError("print() got an unexpected keyword argument '${k.v}'")
                }
            }
            if (file === PyNone) {
                file = ctx.sysModule.dict[PyStr("stdout")] ?: throw PyException("RuntimeError", "lost sys.stdout")
                // (sys.stdout None: nothing printed, as CPython does)
                if (file === PyNone) return PyNone
            }
            // (a standard stream: its text at once; else the file's write, each piece apart, as CPython calls it)
            if (file is PyStream) {
                if (file.closed) throw PyException("ValueError", "I/O operation on closed file.")
                file.out.append(args.joinToString(sep) { PyRt.str(it) } + end)
                return PyNone
            }
            val write = PyRt.getAttr(file, "write")
            val none = arrayOfNulls<Any?>(0)
            for ((i, x) in args.withIndex()) {
                if (i > 0) PyCalls.callWith(ctx, write, arrayOf(PyStr(sep)), none, none)
                PyCalls.callWith(ctx, write, arrayOf(PyStr(PyRt.str(x))), none, none)
            }
            PyCalls.callWith(ctx, write, arrayOf(PyStr(end)), none, none)
            if (flush) PyCalls.callWith(ctx, PyRt.getAttr(file, "flush"), none, none, none)
            return PyNone
        }
        d[PyStr("input")] = PyBuiltin("input") { ctx, args -> input(ctx, args) }
        // open(): _io's, imported the first time it is called, which then is the builtin itself (as CPython's is _io.open)
        fun open(ctx: PyContext, args: Array<Any?>, kw: PyDict?): Any? {
            val f = PyRt.getAttr(PyImport.importModule(ctx, "_io"), "open")
            ctx.builtins[PyStr("open")] = f
            val names = arrayOfNulls<Any?>(kw?.size ?: 0)
            val values = arrayOfNulls<Any?>(names.size)
            var i = 0
            kw?.map?.forEach { (k, v) ->
                names[i] = k.v
                values[i++] = v
            }
            return PyCalls.callWith(ctx, f, args, names, values)
        }
        d[PyStr("open")] = PyBuiltin("open", { ctx, args -> open(ctx, args, null) }, { ctx, args, kw -> open(ctx, args, kw) })
        d[PyStr("print")] = PyBuiltin("print", { ctx, args -> print(ctx, args, null) }, { ctx, args, kw -> print(ctx, args, kw) })
        def("len") { _, args ->
            argc("len", args, 1)
            PyRt.len(args[0])
        }
        fun type(t: PyType, impl: (PyContext, Array<Any?>, PyDict?) -> Any?) {
            t.newImpl = { ctx, _, args, kw -> impl(ctx, args, kw) }
            d[PyStr(t.name)] = t
        }
        fun noKw(name: String, kw: PyDict?) {
            if (kw != null && kw.size > 0) throw PyRt.typeError("$name() takes no keyword arguments")
        }
        type(PyTypes.RANGE) { _, args, kw ->
            noKw("range", kw)
            if (args.isEmpty()) throw PyRt.typeError("range expected at least 1 argument, got 0")
            if (args.size > 3) throw PyRt.typeError("range expected at most 3 arguments, got ${args.size}")
            PyRanges.make(args)
        }
        PyTypes.RANGE.def("index", PyBuiltin("index") { _, a ->
            if (a.size != 2) throw PyRt.typeError("range.index() takes exactly one argument (${a.size - 1} given)")
            PyRanges.index(a[0] as PyRange, a[1])
        })
        PyTypes.RANGE.def("count", PyBuiltin("count") { _, a ->
            if (a.size != 2) throw PyRt.typeError("range.count() takes exactly one argument (${a.size - 1} given)")
            PyRanges.count(a[0] as PyRange, a[1])
        })
        type(PyTypes.STR) { _, args, kw -> newStr(args, kw, false) }
        def("repr") { _, args ->
            argc("repr", args, 1)
            PyStr(PyRt.repr(args[0]))
        }
        type(PyTypes.INT) { _, args, kw ->
            val base = kw?.get(PyStr("base")) ?: if (args.size > 1) args[1] else null
            if (kw != null && kw.map.keys.any { (it.v as PyStr).s != "base" }) {
                throw PyRt.typeError("int() got an unexpected keyword argument '${kw.map.keys.first { (it.v as PyStr).s != "base" }.v}'")
            }
            if (args.size > 2) throw PyRt.typeError("int() takes at most 2 arguments (${args.size} given)")
            if (base != null) {
                val b = longOf(base, "base")
                val v = args.getOrNull(0) ?: throw PyRt.typeError("int() missing string argument")
                // (bytes and bytearray too: their ASCII digits)
                val text = bytesText(v) ?: (v as? PyStr) ?: ((v as? PyNumSub)?.num as? PyStr)
                    ?: throw PyRt.typeError("int() can't convert non-string with explicit base")
                if (b != 0L && (b < 2 || b > 36)) throw PyException("ValueError", "int() base must be >= 2 and <= 36, or 0")
                return@type parseInt(text, b.toInt(), PyRt.repr(v))
            }
            if (args.isNotEmpty()) bytesText(args[0])?.let { return@type parseInt(it, 10, PyRt.repr(args[0])) }
            if (args.isEmpty()) 0L else when (val v = intArg(args[0])) {
                is Long, is BigInteger -> v
                is PyBool -> if (v.value) 1L else 0L
                is Double -> {
                    if (v.isNaN()) throw PyException("ValueError", "cannot convert float NaN to integer")
                    if (v.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
                    PyRt.norm(java.math.BigDecimal(v).toBigInteger())
                }
                is PyStr -> parseInt(v, 10)
                else -> throw PyRt.typeError("int() argument must be a string, a bytes-like object or a real number, not '${PyTypes.name(v)}'")
            }
        }
        type(PyTypes.FLOAT) { _, args, kw ->
            noKw("float", kw)
            argc("float", args, 0, 1)
            if (args.isEmpty()) 0.0 else when (val v = args[0]) {
                is PyStr -> when (val t = v.s.trim().lowercase()) {
                    "inf", "+inf", "infinity" -> Double.POSITIVE_INFINITY
                    "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                    "nan", "+nan", "-nan" -> Double.NaN
                    else -> t.toDoubleOrNull() ?: throw PyException("ValueError", "could not convert string to float: ${PyRt.repr(v)}")
                }
                is Long, is BigInteger, is PyBool, is Double, is PyNumSub -> PyRt.toDouble(v)
                else -> {
                    // (a class's __float__, else its __index__)
                    val t = PyTypes.typeOf(v)
                    val f = if (PyRt.special(v)) PyDunder.userMethod(t, "__float__") else null
                    if (f != null) when (val r = PyCalls.callWith(PyContext.current(), f, arrayOf(v), arrayOfNulls(0), arrayOfNulls(0))) {
                        is Double -> r
                        is PyNumSub -> if (r.num is Double) r.num else throw PyRt.typeError("${t.name}.__float__ returned non-float (type ${PyTypes.name(r)})")
                        else -> throw PyRt.typeError("${t.name}.__float__ returned non-float (type ${PyTypes.name(r)})")
                    } else PyRt.toDouble(PyRt.indexValue(v) ?: throw PyRt.typeError("float() argument must be a string or a real number, not '${t.name}'"))
                }
            }
        }
        type(PyTypes.BOOL) { _, args, kw ->
            noKw("bool", kw)
            argc("bool", args, 0, 1)
            PyBool.of(args.isNotEmpty() && PyRt.truthy(args[0]))
        }
        def("abs") { _, args ->
            argc("abs", args, 1)
            when (val v = args[0]) {
                is Double -> Math.abs(v)
                is PyComplex -> Math.hypot(v.real, v.imag)
                else -> if (PyRt.isInt(v)) (if (PyRt.order(0, v, 0L)) PyRt.neg(v) else PyRt.pos(v)) else PyDunder.abs(v)
            }
        }
        def("min") { _, args -> extreme("min", args, 0) }
        def("max") { _, args -> extreme("max", args, 2) }
        def("sum") { _, args ->
            argc("sum", args, 1, 2)
            var acc: Any? = if (args.size > 1) args[1] else 0L
            val it = PyRt.iter(args[0])
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                acc = PyRt.add(acc, x)
            }
            acc
        }
        type(PyTypes.LIST) { _, args, kw ->
            noKw("list", kw)
            argc("list", args, 0, 1)
            PyList(if (args.isEmpty()) ArrayList() else toList(args[0]))
        }
        type(PyTypes.TUPLE) { _, args, kw ->
            noKw("tuple", kw)
            argc("tuple", args, 0, 1)
            if (args.isEmpty()) PyTuple.EMPTY else PyTuple(toList(args[0]).toTypedArray())
        }
        type(PyTypes.DICT) { _, args, kw -> dictFill(PyDict(), args, kw) }
        // (their __new__ and __init__, for subclasses; their special methods, for super() and explicit calls)
        defineValueMethods()
        type(PyTypes.OBJECT) { _, args, kw ->
            if (args.isNotEmpty() || kw != null && kw.size > 0) throw PyRt.typeError("object() takes no arguments")
            PyObject(PyTypes.OBJECT)
        }
        type(PyTypes.TYPE) { _, args, kw ->
            if (args.size == 1 && (kw == null || kw.size == 0)) PyTypes.typeOf(args[0])
            else throw PyRt.typeError("type() takes 1 or 3 arguments")
        }
        def("isinstance") { _, args ->
            argc("isinstance", args, 2)
            PyBool.of(PyTypes.isInstance(args[0], args[1]))
        }
        def("issubclass") { _, args ->
            argc("issubclass", args, 2)
            PyBool.of(PyTypes.isSubclass(args[0], args[1]))
        }
        // classes (M2)
        PyClasses.ensure()
        d[PyStr("__build_class__")] = PyBuiltin("__build_class__", { ctx, args -> PyClasses.buildClass(ctx, args, null) },
            { ctx, args, kw -> PyClasses.buildClass(ctx, args, kw) })
        type(PyTypes.SUPER) { _, args, kw ->
            noKw("super", kw)
            PyClasses.newSuper(args)
        }
        type(PyTypes.STATICMETHOD) { _, args, kw ->
            noKw("staticmethod", kw)
            one("staticmethod", args)
            PyStaticMethod(args[0])
        }
        type(PyTypes.CLASSMETHOD) { _, args, kw ->
            noKw("classmethod", kw)
            one("classmethod", args)
            PyClassMethod(args[0])
        }
        fun propertyInit(p: PyProperty, args: Array<Any?>, kw: PyDict?) {
            if (args.size > 4) throw PyRt.typeError("property() takes at most 4 arguments (${args.size} given)")
            fun arg(i: Int, n: String): Any? = (kw?.get(PyStr(n)) ?: args.getOrNull(i)).let { if (it === PyNone) null else it }
            p.fget = arg(0, "fget")
            p.fset = arg(1, "fset")
            p.fdel = arg(2, "fdel")
            p.doc = arg(3, "doc")
            // (a subclass's instance: its doc, else the getter's, in its dict, which its class's __doc__ would hide otherwise)
            val s = p.sub
            if (s != null) {
                val doc = p.doc ?: p.fget?.let { PyClasses.attrOrNull(it, "__doc__") } ?: PyNone
                (s.dict ?: PyDict().also { d -> s.dict = d })[PyStr("__doc__")] = doc
            }
        }
        type(PyTypes.PROPERTY) { _, args, kw -> PyProperty(null, null, null, null).also { propertyInit(it, args, kw) } }
        // (staticmethod, classmethod and property as bases: __new__ makes one of the subclass, __init__ gives it its
        // functions; abc's abstractclassmethod, a property whose __get__ is its own)
        for (t in listOf(PyTypes.STATICMETHOD, PyTypes.CLASSMETHOD, PyTypes.PROPERTY)) {
            fun new(a: Array<Any?>): Any? {
                val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("${t.name}.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
                if (!cls.isSubtype(t)) throw PyRt.typeError("${t.name}.__new__(${cls.name}): ${cls.name} is not a subtype of ${t.name}")
                val v: PyValue = when (t) {
                    PyTypes.STATICMETHOD -> PyStaticMethod(null)
                    PyTypes.CLASSMETHOD -> PyClassMethod(null)
                    else -> PyProperty(null, null, null, null)
                }
                if (cls !== t) v.sub = PySub(cls)
                return v
            }
            t.def("__new__", PyBuiltin("__new__", { _, a -> new(a) }, { _, a, _ -> new(a) }))
            fun init(a: Array<Any?>, kw: PyDict?): Any? {
                val rest = a.copyOfRange(1, a.size)
                when (val self = a.getOrNull(0)) {
                    is PyProperty -> propertyInit(self, rest, kw)
                    is PyStaticMethod -> {
                        noKw("staticmethod", kw)
                        one("staticmethod", rest)
                        self.fn = rest[0]
                    }
                    is PyClassMethod -> {
                        noKw("classmethod", kw)
                        one("classmethod", rest)
                        self.fn = rest[0]
                    }
                    else -> throw PyRt.typeError("descriptor '__init__' requires a '${t.name}' object but received a '${PyTypes.name(self)}'")
                }
                return PyNone
            }
            t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null) }, { _, a, kw -> init(a, kw) }))
            t.def("__get__", PyBuiltin("__get__") { ctx, a ->
                if (a.size < 2 || a.size > 3) throw PyRt.typeError("expected 1 or 2 arguments, got ${a.size - 1}")
                val obj = a[1].takeIf { it !== PyNone }
                val type = a.getOrNull(2)?.takeIf { it !== PyNone } as? PyType ?: PyTypes.typeOf(a[1])
                PyClasses.builtinGet(ctx, a[0], obj, type)
            })
        }
        PyTypes.PROPERTY.def("__set__", PyBuiltin("__set__") { ctx, a ->
            val p = a[0] as PyProperty
            val s = p.fset ?: throw PyException("AttributeError", "property ${p.nameFor()}of '${PyTypes.name(a[1])}' object has no setter")
            PyCalls.callWith(ctx, s, arrayOf(a[1], a[2]), NO_ARGS, NO_ARGS)
            PyNone
        })
        PyTypes.PROPERTY.def("__delete__", PyBuiltin("__delete__") { ctx, a ->
            val p = a[0] as PyProperty
            val del = p.fdel ?: throw PyException("AttributeError", "property ${p.nameFor()}of '${PyTypes.name(a[1])}' object has no deleter")
            PyCalls.callWith(ctx, del, arrayOf(a[1]), NO_ARGS, NO_ARGS)
            PyNone
        })
        // (p.getter(f) and the others: a copy of its kind, type(p)(fget, fset, fdel, doc) for a subclass's)
        fun copy(ctx: PyContext, p: PyProperty, get: Any?, set: Any?, del: Any?): Any? {
            val doc = p.doc
            if (p.sub == null) return PyProperty(get, set, del, doc).also { it.name = p.name }
            val r = PyCalls.callWith(ctx, PyTypes.typeOf(p), arrayOf(get ?: PyNone, set ?: PyNone, del ?: PyNone, doc ?: PyNone), NO_ARGS, NO_ARGS)
            if (r is PyProperty) r.name = p.name
            return r
        }
        PyTypes.PROPERTY.def("getter", PyBuiltin("getter") { ctx, a -> val p = a[0] as PyProperty; copy(ctx, p, a[1], p.fset, p.fdel) })
        PyTypes.PROPERTY.def("setter", PyBuiltin("setter") { ctx, a -> val p = a[0] as PyProperty; copy(ctx, p, p.fget, a[1], p.fdel) })
        PyTypes.PROPERTY.def("deleter", PyBuiltin("deleter") { ctx, a -> val p = a[0] as PyProperty; copy(ctx, p, p.fget, p.fset, a[1]) })
        PyTypes.PROPERTY.def("__set_name__", PyBuiltin("__set_name__") { _, a -> (a[0] as PyProperty).name = (a[2] as PyStr).s; PyNone })
        def("getattr") { _, args ->
            argc("getattr", args, 2, 3)
            val n = args[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(args[1])}'")
            if (args.size == 2) PyRt.getAttr(args[0], n.s) else try {
                PyRt.getAttr(args[0], n.s)
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) args[2] else throw e
            }
        }
        def("setattr") { _, args ->
            argc("setattr", args, 3)
            val n = args[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(args[1])}'")
            PyClasses.setAttr(args[0], n.s, args[2])
            PyNone
        }
        def("delattr") { _, args ->
            argc("delattr", args, 2)
            val n = args[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(args[1])}'")
            PyClasses.delAttr(args[0], n.s)
            PyNone
        }
        def("hasattr") { _, args ->
            argc("hasattr", args, 2)
            val n = args[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(args[1])}'")
            try {
                PyRt.getAttr(args[0], n.s)
                PyBool.TRUE
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) PyBool.FALSE else throw e
            }
        }
        def("callable") { _, args ->
            argc("callable", args, 1)
            PyBool.of(PyCalls.isCallable(args[0]))
        }
        def("hash") { _, args ->
            argc("hash", args, 1)
            PyHash.hash(args[0])
        }
        // sets and slices; complex numbers, bytes; the everyday builtins (their min, max and sum over these)
        PySets.define(d)
        PyLiterals.define(d)
        PyByteSeqs.define(d)
        PyLibrary.define(d)
        PyTyping.define()
        PyEval.define(d)
        PyStrings.define()
        PyTypes.SLICE.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("slice() takes no keyword arguments")
            when (a.size) {
                1 -> PySlice(PyNone, a[0], PyNone)
                2 -> PySlice(a[0], a[1], PyNone)
                3 -> PySlice(a[0], a[1], a[2])
                0 -> throw PyRt.typeError("slice expected at least 1 argument, got 0")
                else -> throw PyRt.typeError("slice expected at most 3 arguments, got ${a.size}")
            }
        }
        d[PyStr("slice")] = PyTypes.SLICE
        PyTypes.SLICE.def("indices", PyBuiltin("indices") { _, a ->
            argc("indices", a, 2)
            val n = a[1] as? Long ?: throw PyRt.typeError("'${PyTypes.name(a[1])}' object cannot be interpreted as an integer")
            if (n < 0) throw PyException("ValueError", "length should not be negative")
            val (st, sp, step, _) = (a[0] as PySlice).indices(n)
            PyTuple(arrayOf(st, sp, step))
        })
        def("iter") { ctx, args ->
            if (args.isEmpty()) throw PyRt.typeError("iter expected at least 1 argument, got 0")
            if (args.size > 2) throw PyRt.typeError("iter expected at most 2 arguments, got ${args.size}")
            if (args.size == 1) PyDunder.iterObject(args[0]) else {
                // iter(callable, sentinel): its results until one equals sentinel
                val fn = args[0]
                val sentinel = args[1]
                if (!(fn is PyFunction || fn is PyBuiltin || fn is PyBoundMethod || fn is PyMethod || fn is PyType ||
                        fn is PyObject && fn.type.lookup("__call__") != null)) throw PyRt.typeError("iter(v, w): v must be callable")
                var done = false
                PyIterator {
                    if (done) PyIterator.STOP else {
                        val v = PyCalls.callWith(ctx, fn, arrayOfNulls(0), arrayOfNulls(0), arrayOfNulls(0))
                        if (v === sentinel || PyRt.eq(v, sentinel)) {
                            done = true
                            PyIterator.STOP
                        } else v
                    }
                }.also { it.type = PyLibrary.type("callable_iterator") }
            }
        }
        def("next") { ctx, args ->
            if (args.isEmpty()) throw PyRt.typeError("next expected at least 1 argument, got 0")
            if (args.size > 2) throw PyRt.typeError("next expected at most 2 arguments, got ${args.size}")
            val g = args[0]
            if (g is PyAwaitIter) {
                // (an awaitable's iterator ends with its value: the StopIteration carries it)
                val r = g.send(ctx, PyNone)
                if (r !is PyGenerators.Done) r else if (args.size == 2) args[1] else throw PyGenerators.stopOf(r)
            } else {
                val v = PyDunder.next(g)
                if (v !== PyIterator.STOP) v else if (args.size == 2) args[1]
                else throw if (g is PyGenerator) PyGenerators.stop(g.returned) else PyException("StopIteration", "")
            }
        }
        // the exception classes, their behaviour; generators'
        PyExceptions.define()
        PyGenerators.define(d)
        for ((n, t) in PyTypes.EXCEPTIONS) d[PyStr(n)] = t
        d[PyStr("EnvironmentError")] = PyExceptions.type("OSError")
        d[PyStr("IOError")] = PyExceptions.type("OSError")
        d[PyStr("NotImplemented")] = PyNotImplemented
        d[PyStr("Ellipsis")] = PyEllipsis
        // the builtin types' methods
        PyTypes.LIST.def("append", LIST_APPEND)
        PyTypes.LIST.def("pop", LIST_POP)
        PyTypes.DICT.def("get", DICT_GET)
        PyTypes.DICT.def("keys", DICT_KEYS)
        PyTypes.DICT.def("values", DICT_VALUES)
        PyTypes.DICT.def("items", DICT_ITEMS)
        PyTypes.STR.def("join", STR_JOIN)
        // formatting: format(), ascii(), str.format, the types' __format__
        def("format") { _, args ->
            if (args.isEmpty() || args.size > 2) throw PyRt.typeError("format expected at most 2 arguments, got ${args.size}")
            val spec = if (args.size > 1) (args[1] as? PyStr ?: throw PyRt.typeError("format() argument 2 must be str, not ${PyTypes.name(args[1])}")).s else ""
            PyStr(PyFormat.format(args[0], spec))
        }
        def("ascii") { _, args ->
            argc("ascii", args, 1)
            PyStr(PyFormat.ascii(args[0]))
        }
        PyTypes.STR.def("format", PyBuiltin("format", { _, a -> PyStr(PyFormat.strFormat((a[0] as PyStr).s, a.copyOfRange(1, a.size), null)) },
            { _, a, kw -> PyStr(PyFormat.strFormat((a[0] as PyStr).s, a.copyOfRange(1, a.size), kw)) }))
        PyTypes.STR.def("format_map", PyBuiltin("format_map") { _, a ->
            argc("format_map", a, 2)
            PyStr(PyFormat.strFormat((a[0] as PyStr).s, arrayOfNulls(0), a[1]))
        })
        for (t in arrayOf(PyTypes.OBJECT, PyTypes.INT, PyTypes.FLOAT, PyTypes.STR, PyTypes.BOOL)) {
            t.def("__format__", PyBuiltin("__format__") { _, a ->
                argc("__format__", a, 2)
                val spec = a[1] as? PyStr ?: throw PyRt.typeError("__format__() argument must be str, not ${PyTypes.name(a[1])}")
                PyStr(PyFormat.builtinFormat(a[0], spec.s))
            })
        }
        PyTypes.STR.def("upper", STR_UPPER)
        PyTypes.STR.def("lower", STR_LOWER)
        // lists', tuples' and dicts' methods, dicts' views (after these: their keys() are views)
        PyContainers.define()
        PyStrings.define()
        PyImport.define(d)
        PyFrames.define()
        PyWeakrefs.define()
        // (an int's and a float's methods take a subclass's instance as its number)
        for (t in arrayOf(PyTypes.INT, PyTypes.BOOL, PyTypes.FLOAT, PyTypes.COMPLEX, PyTypes.BYTES)) for (v in t.dict.map.values) when (v) {
            is PyBuiltin -> if (v.name != "__new__") v.numSelf = true
            is PyProperty -> (v.fget as? PyBuiltin)?.numSelf = true
        }
        return d
    }

    /**
     * input(prompt=''): the prompt written to sys.stdout, a line read from sys.stdin without its newline; its end an
     * EOFError (the context's stdin has nothing to read).
     */
    private fun input(ctx: PyContext, args: Array<Any?>): Any? {
        if (args.size > 1) throw PyRt.typeError("input expected at most 1 argument, got ${args.size}")
        val sys = ctx.sysModule.dict
        val stdin = sys[PyStr("stdin")] ?: throw PyException("RuntimeError", "input(): lost sys.stdin")
        val stdout = sys[PyStr("stdout")] ?: throw PyException("RuntimeError", "input(): lost sys.stdout")
        if (stdin === PyNone) throw PyException("RuntimeError", "input(): lost sys.stdin")
        if (stdout === PyNone) throw PyException("RuntimeError", "input(): lost sys.stdout")
        val none = arrayOfNulls<Any?>(0)
        sys[PyStr("stderr")]?.takeIf { it !== PyNone }?.let { e -> runCatching { PyCalls.callWith(ctx, PyRt.getAttr(e, "flush"), none, none, none) } }
        if (args.isNotEmpty()) PyCalls.callWith(ctx, PyRt.getAttr(stdout, "write"), arrayOf(PyStr(PyRt.str(args[0]))), none, none)
        PyCalls.callWith(ctx, PyRt.getAttr(stdout, "flush"), none, none, none)
        val line = PyCalls.callWith(ctx, PyRt.getAttr(stdin, "readline"), none, none, none)
        val s = line as? PyStr ?: throw PyRt.typeError("object.readline() returned non-string")
        if (s.s.isEmpty()) throw PyException("EOFError", "EOF when reading a line")
        return if (s.s.endsWith("\n")) PyStr(s.s.dropLast(1)) else s
    }

    /** dict(src, **kw) and dict.__init__: the pairs of a mapping or an iterable of pairs, then the keywords. */
    @JvmStatic
    fun dictFill(out: PyDict, args: Array<Any?>, kw: PyDict?): PyDict {
        if (args.size > 1) throw PyRt.typeError("dict expected at most 1 argument, got ${args.size}")
        if (args.isNotEmpty()) {
            val src = args[0]
            // (a mapping, one with keys(), a mappingproxy too, its keys' items; else the pairs: dict_update_arg)
            if (src is PyDict) for ((k, v) in src.map) out.map[k] = v else PyContainers.update(out, src)
        }
        if (kw != null) for ((k, v) in kw.map) out.map[k] = v
        return out
    }

    /**
     * str, list, tuple and dict as bases (docs/PYTHON_PLATFORM.md §11.2): their __new__ makes a value of the subclass
     * (its [PySub]), list's and dict's __init__ fill it; their special methods as builtins, which dispatch skips (they
     * are what the runtime's paths do) but super().__getitem__(k), list.__init__(self, xs) and the like call.
     */
    private fun defineValueMethods() {
        val noArgs = arrayOfNulls<Any?>(0)
        fun new(t: PyType, make: (Array<Any?>, PyDict?) -> PyValue) {
            fun impl(a: Array<Any?>, kw: PyDict?): Any? {
                val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("${t.name}.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
                if (!cls.isSubtype(t)) throw PyRt.typeError("${t.name}.__new__(${cls.name}): ${cls.name} is not a subtype of ${t.name}")
                val v = make(if (a.size > 1) a.copyOfRange(1, a.size) else noArgs, kw?.takeIf { it.size > 0 })
                if (cls !== t) v.sub = PySub(cls)
                return v
            }
            t.def("__new__", PyBuiltin("__new__", { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        }
        fun init(t: PyType, fill: (PyValue, Array<Any?>, PyDict?) -> Unit) {
            fun impl(a: Array<Any?>, kw: PyDict?): Any? {
                val self = a.getOrNull(0) as? PyValue ?: throw PyRt.typeError("descriptor '__init__' requires a '${t.name}' object")
                fill(self, if (a.size > 1) a.copyOfRange(1, a.size) else noArgs, kw?.takeIf { it.size > 0 })
                return PyNone
            }
            t.def("__init__", PyBuiltin("__init__", { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        }
        fun noKw(name: String, kw: PyDict?) {
            if (kw != null) throw PyRt.typeError("$name() takes no keyword arguments")
        }
        fun atMostOne(name: String, args: Array<Any?>) {
            if (args.size > 1) throw PyRt.typeError("$name expected at most 1 argument, got ${args.size}")
        }
        // int and float as bases: __new__ makes the number, held with the subclass (PyNumSub); their special methods
        // (super().__add__(x), int.__eq__(a, b)): dispatch skips them, the runtime's paths being what they do
        fun numNew(t: PyType) {
            fun impl(ctx: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
                val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("${t.name}.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
                if (!cls.isSubtype(t)) throw PyRt.typeError("${t.name}.__new__(${cls.name}): ${cls.name} is not a subtype of ${t.name}")
                val v = t.newImpl!!(ctx, t, if (a.size > 1) a.copyOfRange(1, a.size) else noArgs, kw?.takeIf { it.size > 0 })
                return if (cls === t) v else PyNumSub(v!!).also { it.sub = PySub(cls) }
            }
            t.def("__new__", PyBuiltin("__new__", { ctx, a -> impl(ctx, a, null) }, { ctx, a, kw -> impl(ctx, a, kw) }))
        }
        numNew(PyTypes.INT)
        numNew(PyTypes.FLOAT)
        // (complex and bytes as bases: their values held with the subclass too)
        numNew(PyTypes.COMPLEX)
        numNew(PyTypes.BYTES)
        fun plainInt(v: Any?): Any? = when (v) {
            is Long, is BigInteger -> v
            is PyBool -> if (v.value) 1L else 0L
            is PyNumSub -> v.num.takeIf { it !is Double }
            else -> null
        }
        fun plainReal(v: Any?): Any? = plainInt(v) ?: (v as? Double) ?: ((v as? PyNumSub)?.num as? Double)
        val arith = listOf("add" to PyOp.ADD, "sub" to PyOp.SUB, "mul" to PyOp.MUL, "truediv" to PyOp.TRUEDIV,
            "floordiv" to PyOp.FLOORDIV, "mod" to PyOp.MOD, "pow" to PyOp.POW)
        val bits = listOf("lshift" to PyOp.LSHIFT, "rshift" to PyOp.RSHIFT, "and" to PyOp.BITAND, "or" to PyOp.BITOR, "xor" to PyOp.BITXOR)
        val cmps = listOf("lt" to PyOp.LT, "le" to PyOp.LE, "gt" to PyOp.GT, "ge" to PyOp.GE, "eq" to PyOp.EQ, "ne" to PyOp.NE)
        for (t in arrayOf(PyTypes.INT, PyTypes.FLOAT)) {
            val plain: (Any?) -> Any? = if (t === PyTypes.INT) ::plainInt else ::plainReal
            fun def(n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
            for ((n, op) in if (t === PyTypes.INT) arith + bits else arith) {
                def("__${n}__") { a -> val x = plain(a[0]); val y = plain(a.getOrNull(1)); if (x == null || y == null) PyNotImplemented else PyDunder.byCode(op, x, y) }
                def("__r${n}__") { a -> val x = plain(a[0]); val y = plain(a.getOrNull(1)); if (x == null || y == null) PyNotImplemented else PyDunder.byCode(op, y, x) }
            }
            for ((n, op) in cmps) def("__${n}__") { a ->
                val x = plain(a[0])
                val y = plain(a.getOrNull(1))
                if (x == null || y == null) PyNotImplemented else PyBool.of(PyRt.compare(op, x, y))
            }
            def("__neg__") { a -> PyRt.neg(plain(a[0])) }
            def("__pos__") { a -> PyRt.pos(plain(a[0])) }
            def("__abs__") { a -> val x = plain(a[0]); if (x is Double) Math.abs(x) else if (PyRt.order(0, x, 0L)) PyRt.neg(x) else x }
            def("__bool__") { a -> PyBool.of(PyRt.truthy(plain(a[0]))) }
            def("__float__") { a -> PyRt.toDouble(plain(a[0])) }
            def("__hash__") { a -> PyHash.hash(plain(a[0])) }
            def("__repr__") { a -> PyStr(PyRt.reprValues(plain(a[0]))) }
            def("__format__") { a -> PyStr(PyFormat.builtinFormat(plain(a[0]), (a.getOrNull(1) as? PyStr)?.s ?: "")) }
        }
        PyTypes.INT.def("__invert__", PyBuiltin("__invert__") { _, a -> PyRt.invert(plainInt(a[0])) })
        for (t in arrayOf(PyTypes.INT, PyTypes.FLOAT, PyTypes.COMPLEX)) {
            t.def("__getnewargs__", PyBuiltin("__getnewargs__") { _, a -> PyTuple(arrayOf(plainReal(a[0]) ?: PyRt.num(a[0]))) })
        }
        PyTypes.STR.def("__getnewargs__", PyBuiltin("__getnewargs__") { _, a -> PyTuple(arrayOf(PyStr((a[0] as PyStr).s))) })
        PyTypes.TUPLE.def("__getnewargs__", PyBuiltin("__getnewargs__") { _, a -> PyTuple(arrayOf(PyTuple((a[0] as PyTuple).items))) })
        // (their own __reduce__, as CPython's: what copy and pickle make them again from)
        fun state(v: Any?): Any? = PyClasses.instanceDict(v)?.takeIf { it.size > 0 } ?: PyNone
        for (t in arrayOf(PyTypes.SET, PyTypes.FROZENSET)) t.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            PyTuple(arrayOf(PyTypes.typeOf(a[0]), PyTuple(arrayOf(PyList(toList(a[0])))), state(a[0])))
        })
        PyTypes.RANGE.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            val r = a[0] as PyRange
            PyTuple(arrayOf(PyTypes.RANGE, PyTuple(arrayOf(PyRanges.start(r), PyRanges.stop(r), PyRanges.step(r)))))
        })
        PyTypes.SLICE.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            val s = a[0] as PySlice
            PyTuple(arrayOf(PyTypes.SLICE, PyTuple(arrayOf(s.start, s.stop, s.step))))
        })
        PyTypes.BYTEARRAY.def("__reduce_ex__", PyBuiltin("__reduce_ex__") { _, a ->
            PyTuple(arrayOf(PyTypes.typeOf(a[0]), PyTuple(arrayOf(PyBytes((a[0] as PyByteArray).bytes()))), state(a[0])))
        })
        PyTypes.BYTEARRAY.def("__reduce__", PyTypes.BYTEARRAY.dict[PyStr("__reduce_ex__")])
        PyTypes.INT.def("__index__", PyBuiltin("__index__") { _, a -> plainInt(a[0]) })
        PyTypes.INT.def("__int__", PyBuiltin("__int__") { _, a -> plainInt(a[0]) })
        fun floatInt(x: Double): Any {
            if (x.isNaN()) throw PyException("ValueError", "cannot convert float NaN to integer")
            if (x.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
            return PyRt.norm(java.math.BigDecimal(x).toBigInteger())
        }
        PyTypes.FLOAT.def("__int__", PyBuiltin("__int__") { _, a -> floatInt(plainReal(a[0]) as Double) })
        // (int's and float's __floor__, __ceil__ and __trunc__: what math.floor, ceil and trunc call, super() reaches)
        for (n in listOf("__floor__", "__ceil__", "__trunc__")) PyTypes.INT.def(n, PyBuiltin(n) { _, a -> plainInt(a[0]) })
        PyTypes.FLOAT.def("__floor__", PyBuiltin("__floor__") { _, a -> floatInt(Math.floor(PyRt.toDouble(plainReal(a[0])))) })
        PyTypes.FLOAT.def("__ceil__", PyBuiltin("__ceil__") { _, a -> floatInt(Math.ceil(PyRt.toDouble(plainReal(a[0])))) })
        PyTypes.FLOAT.def("__trunc__", PyBuiltin("__trunc__") { _, a -> floatInt(PyRt.toDouble(plainReal(a[0]))) })
        new(PyTypes.LIST) { _, _ -> PyList() }
        init(PyTypes.LIST) { self, args, kw ->
            noKw("list", kw)
            atMostOne("list", args)
            val items = if (args.isEmpty()) ArrayList() else toList(args[0])
            (self as PyList).items.clear()
            self.items.addAll(items)
        }
        new(PyTypes.DICT) { _, _ -> PyDict() }
        init(PyTypes.DICT) { self, args, kw -> dictFill(self as PyDict, args, kw) }
        new(PyTypes.TUPLE) { args, kw ->
            noKw("tuple", kw)
            atMostOne("tuple", args)
            PyTuple(if (args.isEmpty()) arrayOfNulls(0) else toList(args[0]).toTypedArray())
        }
        new(PyTypes.STR) { args, kw -> newStr(args, kw, true) }
        fun dunder(t: PyType, name: String, n: Int, impl: (Array<Any?>) -> Any?) {
            t.def(name, PyBuiltin(name) { _, a ->
                if (a.size != n + 1) throw PyRt.typeError("expected $n argument${if (n == 1) "" else "s"}, got ${a.size - 1}")
                impl(a)
            })
        }
        fun sameKind(a: Any?, b: Any?) = a is PyStr && b is PyStr || a is PyList && b is PyList || a is PyTuple && b is PyTuple || a is PyDict && b is PyDict
        for (t in arrayOf(PyTypes.STR, PyTypes.LIST, PyTypes.TUPLE, PyTypes.DICT)) {
            dunder(t, "__getitem__", 1) { a ->
                // (a dict's subclass's __missing__ for a key it has not: dict.__getitem__'s, through super() too)
                val o = a[0]
                val missing = if (o is PyDict && o.sub != null && !o.containsKey(a[1])) PyDunder.userMethod(o.sub!!.cls, "__missing__") else null
                if (missing != null) PyCalls.callWith(PyContext.current(), missing, arrayOf(o, a[1]), NO_ARGS, NO_ARGS)
                else PyRt.getItemValues(o, a[1])
            }
            dunder(t, "__len__", 0) { a -> PyRt.lenValues(a[0]) }
            dunder(t, "__contains__", 1) { a -> PyBool.of(PyRt.containsValues(a[0], a[1])) }
            dunder(t, "__iter__", 0) { a -> PyRt.iterValues(a[0]) }
            dunder(t, "__repr__", 0) { a -> PyStr(PyRt.reprValues(a[0])) }
            dunder(t, "__eq__", 1) { a -> if (sameKind(a[0], a[1])) PyBool.of(PyRt.eqValues(a[0], a[1])) else PyNotImplemented }
        }
        for (t in arrayOf(PyTypes.LIST, PyTypes.DICT)) {
            dunder(t, "__setitem__", 2) { a -> PyRt.setItemValues(a[0], a[1], a[2]); PyNone }
            dunder(t, "__delitem__", 1) { a -> PyCalls.deleteValues(a[0], a[1]); PyNone }
            // (unhashable: a subclass's instances too)
            t.def("__hash__", PyNone)
        }
        for (t in arrayOf(PyTypes.STR, PyTypes.LIST, PyTypes.TUPLE)) {
            dunder(t, "__add__", 1) { a -> PyRt.addValues(a[0], a[1]) ?: PyNotImplemented }
            dunder(t, "__mul__", 1) { a -> if (PyRt.isInt(a[1])) PyRt.mulValues(a[0], a[1]) else PyNotImplemented }
            dunder(t, "__rmul__", 1) { a -> if (PyRt.isInt(a[1])) PyRt.mulValues(a[0], a[1]) else PyNotImplemented }
        }
        // (what hash() gives: CPython's for a tuple, its items' combined)
        for (t in arrayOf(PyTypes.STR, PyTypes.TUPLE)) dunder(t, "__hash__", 0) { a -> PyHash.valueHash(a[0]) }
        dunder(PyTypes.STR, "__str__", 0) { a -> PyStr((a[0] as PyStr).s) }
    }

    /** str(object='', encoding, errors): the object's str, or (an encoding or errors given) a bytes-like object decoded. */
    private fun newStr(args: Array<Any?>, kw: PyDict?, sub: Boolean): PyStr {
        // (CPython's words for a subclass's, str.__new__'s, and for str()'s own)
        if (args.size > 3) throw PyRt.typeError(if (sub) "str() takes at most 3 arguments (${args.size} given)" else "str expected at most 3 arguments, got ${args.size}")
        val x = arrayOfNulls<Any?>(3)
        args.copyInto(x)
        kw?.map?.forEach { (k, v) ->
            val i = listOf("object", "encoding", "errors").indexOf((k.v as PyStr).s)
            if (i < 0) throw PyRt.typeError("str() got an unexpected keyword argument '${(k.v as PyStr).s}'")
            x[i] = v
        }
        val o = x[0] ?: return PyStr.EMPTY
        if (x[1] == null && x[2] == null) return PyStr(PyRt.str(o))
        if (o is PyStr) throw PyRt.typeError("decoding str is not supported")
        val b = PyByteSeqs.content(o) ?: throw PyRt.typeError("decoding to str: need a bytes-like object, ${PyTypes.name(o)} found")
        val r = PyCodecs.decodeText(PyContext.current(), b, x[1]?.let { PyCodecs.cstr(it, "str() argument 'encoding'", false) }, x[2]?.let { PyCodecs.cstr(it, "str() argument 'errors'", false) })
        return if (r.sub == null) r else PyStr(r.s)
    }

    /** The text of bytes or a bytearray as int() reads it (Latin-1: its ASCII digits), or null for anything else. */
    private fun bytesText(v: Any?): PyStr? =
        if (v is PyBytes || v is PyByteArray || v is PyNumSub && v.num is PyBytes) PyStr(String(PyByteSeqs.content(v)!!, Charsets.ISO_8859_1)) else null

    /** int(s, base): Python's int literal syntax (sign, underscores between digits, a prefix where base is 0 or matches). */
    @JvmStatic fun parseInt(v: PyStr, base: Int, shown: String? = null): Any {
        fun bad(): Nothing = throw PyException("ValueError", "invalid literal for int() with base $base: ${shown ?: PyRt.repr(v)}")
        var s = v.s.trim()
        var neg = false
        if (s.startsWith("+") || s.startsWith("-")) { neg = s[0] == '-'; s = s.substring(1) }
        var b = base
        val lower = s.lowercase()
        val prefixed = when {
            lower.startsWith("0x") -> 16
            lower.startsWith("0o") -> 8
            lower.startsWith("0b") -> 2
            else -> 0
        }
        if (prefixed != 0 && (b == 0 || b == prefixed)) {
            b = prefixed
            s = s.substring(2).removePrefix("_")
        } else if (b == 0) {
            b = 10
            if (s.length > 1 && s[0] == '0' && s.any { it != '0' && it != '_' }) bad()
        }
        if (s.isEmpty() || s.startsWith("_") || s.endsWith("_") || "__" in s) bad()
        val digits = s.replace("_", "")
        if (digits.any { Character.digit(it, b) < 0 }) bad()
        // (a base not a power of 2: no more digits than sys.get_int_max_str_digits())
        if (digits.length > PySys.STR_DIGITS_CHECK_THRESHOLD && b and (b - 1) != 0) {
            val max = PyContext.current().maxStrDigits
            if (max > 0 && digits.length > max) throw PyException("ValueError",
                "Exceeds the limit ($max digits) for integer string conversion: value has ${digits.length} digits; use sys.set_int_max_str_digits() to increase the limit")
        }
        val r = BigInteger(digits, b)
        return PyRt.norm(if (neg) r.negate() else r)
    }

    @JvmStatic fun toList(v: Any?): ArrayList<Any?> {
        val out = ArrayList<Any?>()
        val it = PyRt.iter(v)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) return out
            out.add(x)
        }
    }

    /** min (op 0: the least by <) or max (op 2: the greatest by >) of an iterable or of the arguments. */
    private fun extreme(name: String, args: Array<Any?>, op: Int): Any? {
        if (args.isEmpty()) throw PyRt.typeError("$name expected at least 1 argument, got 0")
        val items = if (args.size == 1) toList(args[0]) else args.asList()
        if (items.isEmpty()) throw PyException("ValueError", "$name() iterable argument is empty")
        var best = items[0]
        for (k in 1 until items.size) if (PyRt.order(op, items[k], best)) best = items[k]
        return best
    }

    // (the bound object comes first in args)
    private val LIST_APPEND = PyBuiltin("append") { _, a ->
        argc("append", a, 2)
        (a[0] as PyList).items.add(a[1])
        PyNone
    }
    private val LIST_POP = PyBuiltin("pop") { _, a ->
        argc("pop", a, 1, 2)
        val l = (a[0] as PyList).items
        if (l.isEmpty()) throw PyException("IndexError", "pop from empty list")
        val k = if (a.size > 1) longOf(a[1], "pop").let { if (it < 0) it + l.size else it } else l.size - 1L
        if (k < 0 || k >= l.size) throw PyException("IndexError", "pop index out of range")
        l.removeAt(k.toInt())
    }
    private val DICT_GET = PyBuiltin("get") { _, a ->
        argc("get", a, 2, 3)
        val d = a[0] as PyDict
        if (d.containsKey(a[1])) d[a[1]] else if (a.size > 2) a[2] else PyNone
    }
    private val DICT_KEYS = PyBuiltin("keys") { _, a -> PyList(ArrayList((a[0] as PyDict).map.keys.map { it.v })) }
    private val DICT_VALUES = PyBuiltin("values") { _, a -> PyList(ArrayList((a[0] as PyDict).map.values)) }
    private val DICT_ITEMS = PyBuiltin("items") { _, a ->
        PyList(ArrayList((a[0] as PyDict).map.entries.map { PyTuple(arrayOf(it.key.v, it.value)) }))
    }
    private val STR_JOIN = PyBuiltin("join") { _, a ->
        argc("join", a, 2)
        PyStr(toList(a[1]).withIndex().joinToString((a[0] as PyStr).s) { (i, v) ->
            if (v is PyStr) v.s else throw PyRt.typeError("sequence item $i: expected str instance, ${PyTypes.name(v)} found")
        })
    }
    private val STR_UPPER = PyBuiltin("upper") { _, a -> PyStr((a[0] as PyStr).s.uppercase()) }
    private val STR_LOWER = PyBuiltin("lower") { _, a -> PyStr((a[0] as PyStr).s.lowercase()) }
}
