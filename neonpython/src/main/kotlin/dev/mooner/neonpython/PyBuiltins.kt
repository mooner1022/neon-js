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

    private fun longOf(v: Any?, what: String): Long = when (v) {
        is Long -> v
        is PyBool -> if (v.value) 1 else 0
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C long")
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer ($what)")
    }

    /** A dict of the builtins. */
    fun module(): PyDict {
        val d = PyDict()
        fun def(name: String, impl: (PyContext, Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, impl)
        }
        // print(*objects, sep=' ', end='\n', file=None, flush=False)
        fun print(ctx: PyContext, args: Array<Any?>, kw: PyDict?): Any? {
            var sep = " "
            var end = "\n"
            if (kw != null) for ((k, v) in kw.map) {
                when ((k.v as PyStr).s) {
                    "sep" -> sep = if (v === PyNone) " " else (v as? PyStr)?.s ?: throw PyRt.typeError("sep must be None or a string, not ${PyTypes.name(v)}")
                    "end" -> end = if (v === PyNone) "\n" else (v as? PyStr)?.s ?: throw PyRt.typeError("end must be None or a string, not ${PyTypes.name(v)}")
                    "flush" -> {}
                    "file" -> if (v !== PyNone) throw PyRt.typeError("print(file=...) is not supported yet")
                    else -> throw PyRt.typeError("print() got an unexpected keyword argument '${k.v}'")
                }
            }
            ctx.out.append(args.joinToString(sep) { PyRt.str(it) }).append(end)
            return PyNone
        }
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
            val (a, b, s) = when (args.size) {
                1 -> Triple(0L, longOf(args[0], "range"), 1L)
                2 -> Triple(longOf(args[0], "range"), longOf(args[1], "range"), 1L)
                else -> Triple(longOf(args[0], "range"), longOf(args[1], "range"), longOf(args[2], "range"))
            }
            if (s == 0L) throw PyException("ValueError", "range() arg 3 must not be zero")
            PyRange(a, b, s)
        }
        type(PyTypes.STR) { _, args, kw ->
            noKw("str", kw)
            argc("str", args, 0, 1)
            if (args.isEmpty()) PyStr.EMPTY else PyStr(PyRt.str(args[0]))
        }
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
                if (v !is PyStr) throw PyRt.typeError("int() can't convert non-string with explicit base")
                if (b != 0L && (b < 2 || b > 36)) throw PyException("ValueError", "int() base must be >= 2 and <= 36, or 0")
                return@type parseInt(v, b.toInt())
            }
            if (args.isEmpty()) 0L else when (val v = args[0]) {
                is Long, is BigInteger -> v
                is PyBool -> if (v.value) 1L else 0L
                is Double -> {
                    if (v.isNaN()) throw PyException("ValueError", "cannot convert float NaN to integer")
                    if (v.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
                    PyRt.norm(java.math.BigDecimal(v).toBigInteger())
                }
                is PyStr -> parseInt(v, 10)
                else -> throw PyRt.typeError("int() argument must be a string or a real number, not '${PyTypes.name(v)}'")
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
                else -> PyRt.toDouble(v)
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
            argc("staticmethod", args, 1)
            PyStaticMethod(args[0])
        }
        type(PyTypes.CLASSMETHOD) { _, args, kw ->
            noKw("classmethod", kw)
            argc("classmethod", args, 1)
            PyClassMethod(args[0])
        }
        type(PyTypes.PROPERTY) { _, args, kw ->
            if (args.size > 4) throw PyRt.typeError("property() takes at most 4 arguments (${args.size} given)")
            fun arg(i: Int, n: String): Any? = (kw?.get(PyStr(n)) ?: args.getOrNull(i)).let { if (it === PyNone) null else it }
            PyProperty(arg(0, "fget"), arg(1, "fset"), arg(2, "fdel"), arg(3, "doc"))
        }
        PyTypes.PROPERTY.def("getter", PyBuiltin("getter") { _, a -> val p = a[0] as PyProperty; PyProperty(a[1], p.fset, p.fdel, p.doc) })
        PyTypes.PROPERTY.def("setter", PyBuiltin("setter") { _, a -> val p = a[0] as PyProperty; PyProperty(p.fget, a[1], p.fdel, p.doc) })
        PyTypes.PROPERTY.def("deleter", PyBuiltin("deleter") { _, a -> val p = a[0] as PyProperty; PyProperty(p.fget, p.fset, a[1], p.doc) })
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
            val v = args[0]
            PyBool.of(v is PyFunction || v is PyBuiltin || v is PyBoundMethod || v is PyMethod || v is PyType || v is PyStaticMethod ||
                PyClasses.classOf(v)?.lookup("__call__") != null)
        }
        def("hash") { _, args ->
            argc("hash", args, 1)
            PyHash.hash(args[0])
        }
        // sets and slices; complex numbers, bytes; the everyday builtins (their min, max and sum over these)
        PySets.define(d)
        PyLiterals.define(d)
        PyLibrary.define(d)
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
                }
            }
        }
        def("next") { _, args ->
            if (args.isEmpty()) throw PyRt.typeError("next expected at least 1 argument, got 0")
            if (args.size > 2) throw PyRt.typeError("next expected at most 2 arguments, got ${args.size}")
            val v = PyDunder.next(args[0])
            val g = args[0]
            if (v !== PyIterator.STOP) v else if (args.size == 2) args[1]
            else throw if (g is PyGenerator) PyGenerators.stop(g.returned) else PyException("StopIteration", "")
        }
        // the exception classes, their behaviour; generators'
        PyExceptions.define()
        PyGenerators.define()
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
        return d
    }

    /** dict(src, **kw) and dict.__init__: the pairs of a mapping or an iterable of pairs, then the keywords. */
    private fun dictFill(out: PyDict, args: Array<Any?>, kw: PyDict?): PyDict {
        if (args.size > 1) throw PyRt.typeError("dict expected at most 1 argument, got ${args.size}")
        if (args.isNotEmpty()) {
            val src = args[0]
            if (src is PyDict) for ((k, v) in src.map) out.map[k] = v
            else for ((i, item) in toList(src).withIndex()) {
                val pair = try {
                    toList(item)
                } catch (e: PyException) {
                    throw PyRt.typeError("object is not iterable")
                }
                if (pair.size != 2) throw PyException("ValueError", "dictionary update sequence element #$i has length ${pair.size}; 2 is required")
                out[pair[0]] = pair[1]
            }
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
        new(PyTypes.STR) { args, kw ->
            if (kw != null) throw PyRt.typeError("str() got an unexpected keyword argument '${kw.map.keys.first().v}'")
            if (args.size > 3) throw PyRt.typeError("str() takes at most 3 arguments (${args.size} given)")
            if (args.size > 1) throw PyRt.typeError("decoding str is not supported")
            PyStr(if (args.isEmpty()) "" else PyRt.str(args[0]))
        }
        fun dunder(t: PyType, name: String, n: Int, impl: (Array<Any?>) -> Any?) {
            t.def(name, PyBuiltin(name) { _, a ->
                if (a.size != n + 1) throw PyRt.typeError("expected $n argument${if (n == 1) "" else "s"}, got ${a.size - 1}")
                impl(a)
            })
        }
        fun sameKind(a: Any?, b: Any?) = a is PyStr && b is PyStr || a is PyList && b is PyList || a is PyTuple && b is PyTuple || a is PyDict && b is PyDict
        for (t in arrayOf(PyTypes.STR, PyTypes.LIST, PyTypes.TUPLE, PyTypes.DICT)) {
            dunder(t, "__getitem__", 1) { a -> PyRt.getItemValues(a[0], a[1]) }
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
        for (t in arrayOf(PyTypes.STR, PyTypes.TUPLE)) dunder(t, "__hash__", 0) { a -> PyRt.hashValues(a[0]).toLong() }
        dunder(PyTypes.STR, "__str__", 0) { a -> PyStr((a[0] as PyStr).s) }
    }

    /** int(s, base): Python's int literal syntax (sign, underscores between digits, a prefix where base is 0 or matches). */
    @JvmStatic fun parseInt(v: PyStr, base: Int): Any {
        fun bad(): Nothing = throw PyException("ValueError", "invalid literal for int() with base $base: ${PyRt.repr(v)}")
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
