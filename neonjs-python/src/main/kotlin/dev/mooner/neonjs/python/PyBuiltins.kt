package dev.mooner.neonjs.python

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
        def("print") { ctx, args ->
            ctx.out.append(args.joinToString(" ") { PyRt.str(it) }).append('\n')
            PyNone
        }
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
        def("range") { _, args ->
            argc("range", args, 1, 3)
            val (a, b, s) = when (args.size) {
                1 -> Triple(0L, longOf(args[0], "range"), 1L)
                2 -> Triple(longOf(args[0], "range"), longOf(args[1], "range"), 1L)
                else -> Triple(longOf(args[0], "range"), longOf(args[1], "range"), longOf(args[2], "range"))
            }
            if (s == 0L) throw PyException("ValueError", "range() arg 3 must not be zero")
            PyRange(a, b, s)
        }
        def("str") { _, args ->
            argc("str", args, 0, 1)
            if (args.isEmpty()) PyStr.EMPTY else PyStr(PyRt.str(args[0]))
        }
        def("repr") { _, args ->
            argc("repr", args, 1)
            PyStr(PyRt.repr(args[0]))
        }
        def("int") { _, args ->
            argc("int", args, 0, 1)
            if (args.isEmpty()) 0L else when (val v = args[0]) {
                is Long, is BigInteger -> v
                is PyBool -> if (v.value) 1L else 0L
                is Double -> {
                    if (v.isNaN()) throw PyException("ValueError", "cannot convert float NaN to integer")
                    if (v.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
                    PyRt.norm(java.math.BigDecimal(v).toBigInteger())
                }
                is PyStr -> try {
                    PyRt.norm(BigInteger(v.s.trim().replace("_", "")))
                } catch (_: NumberFormatException) {
                    throw PyException("ValueError", "invalid literal for int() with base 10: ${PyRt.repr(v)}")
                }
                else -> throw PyRt.typeError("int() argument must be a string or a real number, not '${PyTypes.name(v)}'")
            }
        }
        def("float") { _, args ->
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
        def("bool") { _, args ->
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
        def("list") { _, args ->
            argc("list", args, 0, 1)
            PyList(if (args.isEmpty()) ArrayList() else toList(args[0]))
        }
        def("tuple") { _, args ->
            argc("tuple", args, 0, 1)
            if (args.isEmpty()) PyTuple.EMPTY else PyTuple(toList(args[0]).toTypedArray())
        }
        return d
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

    /** Method [name] of [obj]'s type, or null. */
    @JvmStatic fun method(obj: Any?, name: String): PyBuiltin? = when (obj) {
        is PyList -> when (name) {
            "append" -> LIST_APPEND
            "pop" -> LIST_POP
            else -> null
        }
        is PyDict -> when (name) {
            "get" -> DICT_GET
            "keys" -> DICT_KEYS
            "values" -> DICT_VALUES
            "items" -> DICT_ITEMS
            else -> null
        }
        is PyStr -> when (name) {
            "join" -> STR_JOIN
            "upper" -> STR_UPPER
            "lower" -> STR_LOWER
            else -> null
        }
        else -> null
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
