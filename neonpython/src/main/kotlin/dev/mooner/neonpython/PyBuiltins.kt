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
            when (val v = args[0]) {
                is PyList -> v.items.size.toLong()
                is PyTuple -> v.items.size.toLong()
                is PyStr -> v.length.toLong()
                is PyDict -> v.size.toLong()
                is PyRange -> v.length
                else -> throw PyRt.typeError("object of type '${PyTypes.name(v)}' has no len()")
            }
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
                else -> if (PyRt.isInt(v)) (if (PyRt.order(0, v, 0L)) PyRt.neg(v) else PyRt.pos(v)) else throw PyRt.typeError("bad operand type for abs(): '${PyTypes.name(v)}'")
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
        type(PyTypes.DICT) { _, args, kw ->
            argc("dict", args, 0, 1)
            val out = PyDict()
            if (args.isNotEmpty()) {
                val src = args[0]
                if (src is PyDict) for ((k, v) in src.map) out.map[k] = v
                else for ((i, item) in toList(src).withIndex()) {
                    val pair = try {
                        toList(item)
                    } catch (e: PyException) {
                        throw PyRt.typeError("cannot convert dictionary update sequence element #$i to a sequence")
                    }
                    if (pair.size != 2) throw PyException("ValueError", "dictionary update sequence element #$i has length ${pair.size}; 2 is required")
                    out[pair[0]] = pair[1]
                }
            }
            if (kw != null) for ((k, v) in kw.map) out.map[k] = v
            out
        }
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
        def("getattr") { _, args ->
            argc("getattr", args, 2, 3)
            val n = args[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(args[1])}'")
            if (args.size == 2) PyRt.getAttr(args[0], n.s) else try {
                PyRt.getAttr(args[0], n.s)
            } catch (e: PyException) {
                if (e.type == "AttributeError") args[2] else throw e
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
                if (e.type == "AttributeError") PyBool.FALSE else throw e
            }
        }
        def("callable") { _, args ->
            argc("callable", args, 1)
            val v = args[0]
            PyBool.of(v is PyFunction || v is PyBuiltin || v is PyBoundMethod || v is PyMethod || v is PyType || v is PyStaticMethod ||
                v is PyObject && v.type.lookup("__call__") != null)
        }
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
        PyTypes.STR.def("upper", STR_UPPER)
        PyTypes.STR.def("lower", STR_LOWER)
        return d
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
        PyStr(toList(a[1]).joinToString((a[0] as PyStr).s) { v ->
            if (v is PyStr) v.s else throw PyRt.typeError("sequence item: expected str instance, ${PyTypes.name(v)} found")
        })
    }
    private val STR_UPPER = PyBuiltin("upper") { _, a -> PyStr((a[0] as PyStr).s.uppercase()) }
    private val STR_LOWER = PyBuiltin("lower") { _, a -> PyStr((a[0] as PyStr).s.lowercase()) }
}
