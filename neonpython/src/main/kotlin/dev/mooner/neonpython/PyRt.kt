package dev.mooner.neonpython

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Python's semantics of its operations, shared by the interpreter ([PyInterpreter]) and the code the optimizing tier
 * generates ([PyCodegen]): what an instruction means lives here once. Ints are Longs, BigIntegers past 64 bits
 * ([norm] brings a BigInteger that fits back to a Long), bools ints of 0 and 1.
 */
object PyRt {
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

    @JvmStatic fun typeError(msg: String) = PyException("TypeError", msg)

    private val NO_KW = arrayOfNulls<Any?>(0)

    private fun t(v: Any?) = PyTypes.name(v)

    // ------------------------------------------------------------------ numbers

    @JvmStatic fun isInt(v: Any?) = v is Long || v is BigInteger || v is PyBool
    @JvmStatic fun isNumber(v: Any?) = isInt(v) || v is Double

    /** A BigInteger as an int: a Long where it fits. */
    @JvmStatic fun norm(b: BigInteger): Any = if (b.bitLength() < 64) b.toLong() else b

    private fun big(v: Any?): BigInteger = when (v) {
        is Long -> BigInteger.valueOf(v)
        is BigInteger -> v
        is PyBool -> if (v.value) BigInteger.ONE else BigInteger.ZERO
        else -> throw IllegalStateException("not an int: $v")
    }

    /** An int as a Long, where it is one (a bool's 0 or 1). */
    private fun small(v: Any?): Long? = when (v) {
        is Long -> v
        is PyBool -> if (v.value) 1L else 0L
        else -> null
    }

    @JvmStatic fun toDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is PyBool -> if (v.value) 1.0 else 0.0
        is BigInteger -> v.toDouble().also { if (it.isInfinite()) throw PyException("OverflowError", "int too large to convert to float") }
        else -> throw typeError("must be real number, not ${t(v)}")
    }

    private fun unsupported(op: String, a: Any?, b: Any?) = typeError("unsupported operand type(s) for $op: '${t(a)}' and '${t(b)}'")

    /**
     * Whether [v] is an instance of a class: an object, or a builtin value of a subclass. Its class's special methods
     * come first (PyDunder), then, for a value, its base type's paths (the `...Values` functions).
     */
    @JvmStatic fun special(v: Any?): Boolean = v is PyObject || v is PyValue && v.sub != null

    /** a op b where no builtin path took them: an instance's special methods (PyDunder), else the TypeError. */
    private fun other(op: Int, sym: String, a: Any?, b: Any?): Any? =
        if (special(a) || special(b)) PyDunder.binary(a, b, op) else throw unsupported(sym, a, b)

    /** a op b of builtin values for [PyDunder.binary]: concatenation and repetition; null where none applies. */
    @JvmStatic fun values(op: Int, a: Any?, b: Any?): Any? = when (op) {
        PyOp.ADD -> addValues(a, b)
        PyOp.MUL -> mulValues(a, b)
        else -> null
    }

    private fun concat(kind: String, b: Any?) = typeError("can only concatenate $kind (not \"${t(b)}\") to $kind")

    @JvmStatic fun add(a: Any?, b: Any?): Any? {
        val x = small(a)
        val y = small(b)
        if (x != null && y != null) {
            val r = x + y
            return if (((x xor r) and (y xor r)) < 0) BigInteger.valueOf(x).add(BigInteger.valueOf(y)) else r
        }
        if (isNumber(a) && isNumber(b)) {
            if (a is Double || b is Double) return toDouble(a) + toDouble(b)
            return norm(big(a).add(big(b)))
        }
        if (special(a) || special(b)) return PyDunder.binary(a, b, PyOp.ADD)
        return addValues(a, b) ?: throw unsupported("+", a, b)
    }

    /** a + b of a str, list or tuple: null where a is none of them, CPython's TypeError where b is not of a's kind. */
    @JvmStatic fun addValues(a: Any?, b: Any?): Any? {
        if (a is PyStr) return if (b is PyStr) PyStr(a.s + b.s) else throw concat("str", b)
        if (a is PyList) {
            if (b !is PyList) throw concat("list", b)
            return PyList(ArrayList<Any?>(a.items.size + b.items.size).apply { addAll(a.items); addAll(b.items) })
        }
        if (a is PyTuple) {
            if (b !is PyTuple) throw concat("tuple", b)
            return PyTuple(a.items.copyOf(a.items.size + b.items.size).also { b.items.copyInto(it, a.items.size) })
        }
        return null
    }

    @JvmStatic fun sub(a: Any?, b: Any?): Any? {
        val x = small(a)
        val y = small(b)
        if (x != null && y != null) {
            val r = x - y
            return if (((x xor y) and (x xor r)) < 0) BigInteger.valueOf(x).subtract(BigInteger.valueOf(y)) else r
        }
        if (isNumber(a) && isNumber(b)) {
            if (a is Double || b is Double) return toDouble(a) - toDouble(b)
            return norm(big(a).subtract(big(b)))
        }
        return other(PyOp.SUB, "-", a, b)
    }

    @JvmStatic fun mul(a: Any?, b: Any?): Any? {
        val x = small(a)
        val y = small(b)
        if (x != null && y != null) {
            val r = x * y
            return if (Math.multiplyHigh(x, y) != (r shr 63)) BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)) else r
        }
        if (isNumber(a) && isNumber(b)) {
            if (a is Double || b is Double) return toDouble(a) * toDouble(b)
            return norm(big(a).multiply(big(b)))
        }
        if (special(a) || special(b)) return PyDunder.binary(a, b, PyOp.MUL)
        return mulValues(a, b) ?: throw unsupported("*", a, b)
    }

    /** A str, list or tuple repeated (either operand the count): null where neither is one, the TypeError where the count is no int. */
    @JvmStatic fun mulValues(a: Any?, b: Any?): Any? {
        val seq: Any?
        val n: Any?
        if (a is PyStr || a is PyList || a is PyTuple) { seq = a; n = b } else if (b is PyStr || b is PyList || b is PyTuple) { seq = b; n = a } else return null
        if (!isInt(n)) throw typeError("can't multiply sequence by non-int of type '${t(n)}'")
        return when (seq) {
            is PyStr -> PyStr(seq.s.repeat(count(n)))
            is PyList -> PyList(ArrayList<Any?>().apply { repeat(count(n)) { addAll(seq.items) } })
            else -> PyTuple(ArrayList<Any?>().apply { repeat(count(n)) { addAll((seq as PyTuple).items) } }.toTypedArray())
        }
    }

    /** A repetition count: negative counts as none. */
    private fun count(n: Any?): Int {
        val v = small(n) ?: throw PyException("OverflowError", "cannot fit 'int' into an index-sized integer")
        if (v > Int.MAX_VALUE) throw PyException("MemoryError", "")
        return if (v < 0) 0 else v.toInt()
    }

    @JvmStatic fun truediv(a: Any?, b: Any?): Any? {
        if (!isNumber(a) || !isNumber(b)) return other(PyOp.TRUEDIV, "/", a, b)
        val d = toDouble(b)
        if (d == 0.0) throw PyException("ZeroDivisionError", "division by zero")
        return toDouble(a) / d
    }

    @JvmStatic fun floordiv(a: Any?, b: Any?): Any? {
        if (!isNumber(a) || !isNumber(b)) return other(PyOp.FLOORDIV, "//", a, b)
        if (a is Double || b is Double) {
            val y = toDouble(b)
            if (y == 0.0) throw PyException("ZeroDivisionError", "division by zero")
            return Math.floor(toDouble(a) / y)
        }
        val x = small(a)
        val y = small(b)
        if (y == 0L || (y == null && big(b).signum() == 0)) throw PyException("ZeroDivisionError", "division by zero")
        if (x != null && y != null && !(x == Long.MIN_VALUE && y == -1L)) return Math.floorDiv(x, y)
        val (q, r) = big(a).divideAndRemainder(big(b)).let { it[0] to it[1] }
        return norm(if (r.signum() != 0 && (r.signum() != big(b).signum())) q.subtract(BigInteger.ONE) else q)
    }

    @JvmStatic fun mod(a: Any?, b: Any?): Any? {
        if (!isNumber(a) || !isNumber(b)) {
            if (a is PyStr && a.sub == null) throw typeError("str formatting with % is not supported yet")
            return other(PyOp.MOD, "%", a, b)
        }
        if (a is Double || b is Double) {
            val y = toDouble(b)
            if (y == 0.0) throw PyException("ZeroDivisionError", "division by zero")
            var r = toDouble(a) % y
            if (r != 0.0 && (r < 0) != (y < 0)) r += y
            return r
        }
        val x = small(a)
        val y = small(b)
        if (y == 0L || (y == null && big(b).signum() == 0)) throw PyException("ZeroDivisionError", "division by zero")
        if (x != null && y != null) return Math.floorMod(x, y)
        val by = big(b)
        var r = big(a).mod(by.abs())
        if (by.signum() < 0 && r.signum() != 0) r = r.add(by)
        return norm(r)
    }

    @JvmStatic fun pow(a: Any?, b: Any?): Any? {
        if (!isNumber(a) || !isNumber(b)) return other(PyOp.POW, "** or pow()", a, b)
        if (isInt(a) && isInt(b)) {
            val e = big(b)
            if (e.signum() >= 0) {
                if (e.bitLength() > 31) throw PyException("MemoryError", "")
                return norm(big(a).pow(e.toInt()))
            }
            val base = toDouble(a)
            if (base == 0.0) throw PyException("ZeroDivisionError", "zero to a negative power")
            return Math.pow(base, toDouble(b))
        }
        val x = toDouble(a)
        val y = toDouble(b)
        if (x == 0.0 && y < 0) throw PyException("ZeroDivisionError", "zero to a negative power")
        if (x < 0 && y != Math.floor(y)) throw PyException("ValueError", "complex results are not supported yet")
        return Math.pow(x, y)
    }

    /**
     * `a op= b` (INPLACE, binary opcode [op]): two ints, a float's arithmetic and strs joined as `a op b`, small enough
     * to be inlined where [op] is a constant; instances, lists and the errors' messages in [PyDunder.inplace].
     */
    @JvmStatic fun inplace(a: Any?, b: Any?, op: Int): Any? {
        if (a is Long && b is Long || op <= PyOp.POW && (a is Double || b is Double) && isNumber(a) && isNumber(b)) return when (op) {
            PyOp.ADD -> add(a, b)
            PyOp.SUB -> sub(a, b)
            PyOp.MUL -> mul(a, b)
            PyOp.TRUEDIV -> truediv(a, b)
            PyOp.FLOORDIV -> floordiv(a, b)
            PyOp.MOD -> mod(a, b)
            PyOp.POW -> pow(a, b)
            PyOp.LSHIFT -> lshift(a, b)
            PyOp.RSHIFT -> rshift(a, b)
            PyOp.BITAND -> bitAnd(a, b)
            PyOp.BITOR -> bitOr(a, b)
            PyOp.BITXOR -> bitXor(a, b)
            else -> PyDunder.inplace(a, b, op)
        }
        if (op == PyOp.ADD && a is PyStr && b is PyStr) return PyStr(a.s + b.s)
        return PyDunder.inplace(a, b, op)
    }

    private fun bitwise(op: String, code: Int, a: Any?, b: Any?, f: (Long, Long) -> Long, g: (BigInteger, BigInteger) -> BigInteger): Any? {
        if (a is PyBool && b is PyBool) {
            val r = f(if (a.value) 1 else 0, if (b.value) 1 else 0)
            return PyBool.of(r != 0L)
        }
        if (!isInt(a) || !isInt(b)) return other(code, op, a, b)
        val x = small(a)
        val y = small(b)
        if (x != null && y != null) return f(x, y)
        return norm(g(big(a), big(b)))
    }

    @JvmStatic fun bitAnd(a: Any?, b: Any?) = bitwise("&", PyOp.BITAND, a, b, { x, y -> x and y }, BigInteger::and)
    @JvmStatic fun bitOr(a: Any?, b: Any?) = bitwise("|", PyOp.BITOR, a, b, { x, y -> x or y }, BigInteger::or)
    @JvmStatic fun bitXor(a: Any?, b: Any?) = bitwise("^", PyOp.BITXOR, a, b, { x, y -> x xor y }, BigInteger::xor)

    @JvmStatic fun lshift(a: Any?, b: Any?): Any? {
        if (!isInt(a) || !isInt(b)) return other(PyOp.LSHIFT, "<<", a, b)
        val n = big(b)
        if (n.signum() < 0) throw PyException("ValueError", "negative shift count")
        if (n.bitLength() > 31) throw PyException("OverflowError", "too many digits in integer")
        return norm(big(a).shiftLeft(n.toInt()))
    }

    @JvmStatic fun rshift(a: Any?, b: Any?): Any? {
        if (!isInt(a) || !isInt(b)) return other(PyOp.RSHIFT, ">>", a, b)
        val n = big(b)
        if (n.signum() < 0) throw PyException("ValueError", "negative shift count")
        if (n.bitLength() > 31) return if (big(a).signum() < 0) -1L else 0L
        return norm(big(a).shiftRight(n.toInt()))
    }

    @JvmStatic fun neg(a: Any?): Any? = when {
        a is Long -> if (a == Long.MIN_VALUE) BigInteger.valueOf(a).negate() else -a
        a is Double -> -a
        isInt(a) -> norm(big(a).negate())
        else -> PyDunder.unary(a, "__neg__", "-")
    }

    @JvmStatic fun pos(a: Any?): Any? = when {
        a is PyBool -> small(a)
        isNumber(a) -> a
        else -> PyDunder.unary(a, "__pos__", "+")
    }

    @JvmStatic fun invert(a: Any?): Any? = when {
        a is Long -> a.inv()
        isInt(a) -> norm(big(a).not())
        else -> PyDunder.unary(a, "__invert__", "~")
    }

    // ------------------------------------------------------------------ comparisons, truth, hashing

    @JvmStatic fun truthy(v: Any?): Boolean = when (v) {
        null, PyNone -> false
        is PyBool -> v.value
        is Long -> v != 0L
        is Double -> v != 0.0
        is BigInteger -> v.signum() != 0
        is PyValue -> if (v.sub == null) truthValues(v) else PyDunder.truthy(v)
        is PyRange -> v.length > 0
        is PyObject -> PyDunder.truthy(v)
        else -> true
    }

    /** A str's, list's, tuple's or dict's truth: not empty. */
    @JvmStatic fun truthValues(v: Any?): Boolean = when (v) {
        is PyStr -> v.s.isNotEmpty()
        is PyList -> v.items.isNotEmpty()
        is PyTuple -> v.items.isNotEmpty()
        is PyDict -> v.size > 0
        else -> true
    }

    /** len(v): a builtin value's length, or its class's __len__. */
    @JvmStatic fun len(v: Any?): Long = if (special(v)) PyDunder.len(v) else lenValues(v)

    @JvmStatic fun lenValues(v: Any?): Long = when (v) {
        is PyList -> v.items.size.toLong()
        is PyTuple -> v.items.size.toLong()
        is PyStr -> v.length.toLong()
        is PyDict -> v.size.toLong()
        is PyRange -> v.length
        else -> throw typeError("object of type '${t(v)}' has no len()")
    }

    @JvmStatic fun not(v: Any?): PyBool = PyBool.of(!truthy(v))

    /** Two numbers compared exactly (an int with a float by their values, not the int rounded). */
    private fun numCompare(a: Any?, b: Any?): Int? {
        val x = small(a)
        val y = small(b)
        if (x != null && y != null) return x.compareTo(y)
        if (a is Double && b is Double) return if (a.isNaN() || b.isNaN()) null else a.compareTo(b).let { if (a == b) 0 else it }
        if (a is Double && a.isNaN() || b is Double && b.isNaN()) return null
        fun dec(v: Any?): BigDecimal = when (v) {
            is Double -> if (v.isInfinite()) BigDecimal(if (v > 0) "1e400" else "-1e400") else BigDecimal(v)
            else -> BigDecimal(big(v))
        }
        return dec(a).compareTo(dec(b))
    }

    @JvmStatic fun eq(a: Any?, b: Any?): Boolean {
        if (a === b) return if (special(a)) PyDunder.eq(a, b) else a !is Double || !a.isNaN()
        if (isNumber(a) && isNumber(b)) return numCompare(a, b) == 0
        if (special(a) || special(b)) return PyDunder.eq(a, b)
        return eqValues(a, b)
    }

    /** a == b of builtin values (a subclass's instance as its base type's value); false for other kinds. */
    @JvmStatic fun eqValues(a: Any?, b: Any?): Boolean {
        if (isNumber(a) && isNumber(b)) return numCompare(a, b) == 0
        return when (a) {
            is PyStr -> b is PyStr && a.s == b.s
            is PyList -> b is PyList && seqEquals(a.items, b.items)
            is PyTuple -> b is PyTuple && seqEquals(a.items.asList(), b.items.asList())
            is PyDict -> b is PyDict && a.size == b.size && a.map.all { (k, v) -> b.map.containsKey(k) && eq(v, b.map[k]) }
            PyNone, null -> b === PyNone || b == null
            else -> false
        }
    }

    /** Two sequences' items equal pairwise (an item is its own equal: containers compare by identity first). */
    @JvmStatic fun seqEquals(a: List<Any?>, b: List<Any?>): Boolean {
        if (a.size != b.size) return false
        for (k in a.indices) if (a[k] !== b[k] && !eq(a[k], b[k])) return false
        return true
    }

    /** a < b (op 0), <= (1), > (2), >= (3), as Python orders numbers, strs, lists and tuples. */
    @JvmStatic fun order(op: Int, a: Any?, b: Any?): Boolean {
        if (special(a) || special(b)) return PyDunder.order(op, a, b)
        return orderValues(op, a, b) ?: throw typeError("'${arrayOf("<", "<=", ">", ">=")[op]}' not supported between instances of '${t(a)}' and '${t(b)}'")
    }

    /** a < b (op 0)... of builtin values (a subclass's instance as its base type's value); null where they have no order. */
    @JvmStatic fun orderValues(op: Int, a: Any?, b: Any?): Boolean? {
        val c: Int = when {
            isNumber(a) && isNumber(b) -> numCompare(a, b) ?: return false
            a is PyStr && b is PyStr -> a.compareTo(b)
            a is PyList && b is PyList -> seqCompare(op, a.items, b.items) ?: return false
            a is PyTuple && b is PyTuple -> seqCompare(op, a.items.asList(), b.items.asList()) ?: return false
            else -> return null
        }
        return when (op) {
            0 -> c < 0
            1 -> c <= 0
            2 -> c > 0
            else -> c >= 0
        }
    }

    private fun seqCompare(op: Int, a: List<Any?>, b: List<Any?>): Int? {
        val n = minOf(a.size, b.size)
        for (k in 0 until n) if (a[k] !== b[k] && !eq(a[k], b[k])) return if (order(0, a[k], b[k])) -1 else 1
        return a.size.compareTo(b.size)
    }

    @JvmStatic fun hash(v: Any?): Int = when (v) {
        null, PyNone -> 0x5f3759
        is PyBool -> if (v.value) 1 else 0
        is Long -> java.lang.Long.hashCode(v)
        is BigInteger -> v.hashCode()
        is Double -> if (v == Math.rint(v) && !v.isInfinite()) {
            if (v >= -9.223372036854776E18 && v < 9.223372036854776E18) java.lang.Long.hashCode(v.toLong())
            else BigDecimal(v).toBigInteger().hashCode()
        } else v.hashCode()
        is PyStr -> if (v.sub == null) v.s.hashCode() else PyDunder.hash(v)
        is PyTuple -> if (v.sub == null) v.hashCode() else PyDunder.hash(v)
        is PyList, is PyDict -> if (special(v)) PyDunder.hash(v) else throw typeError("unhashable type: '${t(v)}'")
        is PyObject -> PyDunder.hash(v)
        else -> System.identityHashCode(v)
    }

    /** A str's or tuple's hash (a subclass's instance as its base type's value); lists and dicts have none. */
    @JvmStatic fun hashValues(v: Any?): Int = when (v) {
        is PyStr -> v.s.hashCode()
        is PyTuple -> v.hashCode()
        is PyList, is PyDict -> throw typeError("unhashable type: '${t(v)}'")
        else -> System.identityHashCode(v)
    }

    /** A dict key's hash: a TypeError hashing it says what it was for (CPython 3.14's message). */
    @JvmStatic fun keyHash(v: Any?): Int = try {
        hash(v)
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("TypeError"))) throw typeError("cannot use '${t(v)}' as a dict key (${e.message})") else throw e
    }

    @JvmStatic fun contains(container: Any?, v: Any?): Boolean =
        if (special(container)) PyDunder.contains(container!!, v) else containsValues(container, v)

    /** `v in container` of builtin values (a subclass's instance as its base type's value), else by iteration. */
    @JvmStatic fun containsValues(container: Any?, v: Any?): Boolean = when (container) {
        is PyList -> container.items.any { it === v || eq(it, v) }
        is PyTuple -> container.items.any { it === v || eq(it, v) }
        is PyDict -> container.containsKey(v)
        is PyStr -> if (v is PyStr) container.s.contains(v.s) else throw typeError("'in <string>' requires string as left operand, not ${t(v)}")
        is PyRange -> v is Long && (if (container.step > 0) v >= container.start && v < container.stop else v <= container.start && v > container.stop) &&
            (v - container.start) % container.step == 0L
        else -> {
            val it = iter(container)
            var found = false
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                if (x === v || eq(x, v)) { found = true; break }
            }
            found
        }
    }

    // ------------------------------------------------------------------ str and repr

    @JvmStatic fun str(v: Any?): String = if (v is PyStr && v.sub == null) v.s else if (special(v)) PyDunder.str(v!!) else repr(v)

    @JvmStatic fun repr(v: Any?): String = if (v is PyValue && v.sub != null) PyDunder.repr(v) else reprValues(v)

    /** repr of a builtin value (a subclass's instance as its base type's value), an object's through its class. */
    @JvmStatic fun reprValues(v: Any?): String = when (v) {
        null, PyNone -> "None"
        is PyBool -> v.toString()
        is Long, is BigInteger -> v.toString()
        is Double -> floatRepr(v)
        is PyStr -> strRepr(v.s)
        is PyList -> v.items.joinToString(", ", "[", "]") { repr(it) }
        is PyTuple -> if (v.items.size == 1) "(" + repr(v.items[0]) + ",)" else v.items.joinToString(", ", "(", ")") { repr(it) }
        is PyDict -> v.map.entries.joinToString(", ", "{", "}") { repr(it.key.v) + ": " + repr(it.value) }
        is PyRange -> if (v.step == 1L) "range(${v.start}, ${v.stop})" else "range(${v.start}, ${v.stop}, ${v.step})"
        is PyType -> v.repr()
        is PyObject -> PyDunder.repr(v)
        else -> v.toString()
    }

    /** A float as Python writes it: the shortest digits that read back the same, fixed from 1e-4 to 1e16, else 1e+XX. */
    @JvmStatic fun floatRepr(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val j = java.lang.Double.toString(Math.abs(d))
        var digits: String
        var exp: Int
        val e = j.indexOf('E')
        if (e >= 0) {
            val m = j.substring(0, e)
            exp = j.substring(e + 1).toInt()
            digits = m.replace(".", "")
        } else {
            val dot = j.indexOf('.')
            val ip = j.substring(0, dot)
            val fp = j.substring(dot + 1)
            if (ip != "0") {
                exp = ip.length - 1
                digits = ip + fp
            } else {
                val lead = fp.indexOfFirst { it != '0' }
                exp = -(lead + 1)
                digits = fp.substring(lead)
            }
        }
        digits = digits.trimStart('0').trimEnd('0').ifEmpty { "0" }
        // Java writes two digits at least (4.9E-324) where one reads back the same (Python's 5e-324)
        if (digits.length == 2) {
            val one = java.math.BigDecimal(Math.abs(d)).round(java.math.MathContext(1, java.math.RoundingMode.HALF_EVEN))
            if (one.toDouble() == Math.abs(d)) {
                digits = one.unscaledValue().toString()
                exp = one.precision() - one.scale() - 1
            }
        }
        val sign = if (d < 0) "-" else ""
        if (exp < -4 || exp >= 16) {
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val es = if (exp < 0) "-" else "+"
            return sign + mant + "e" + es + Math.abs(exp).toString().padStart(2, '0')
        }
        if (exp >= 0) {
            val ip = if (digits.length > exp + 1) digits.substring(0, exp + 1) else digits.padEnd(exp + 1, '0')
            val fp = if (digits.length > exp + 1) digits.substring(exp + 1) else "0"
            return "$sign$ip.$fp"
        }
        return sign + "0." + "0".repeat(-exp - 1) + digits
    }

    /** A str as repr writes it: quoted, what is not printable (str.isprintable) escaped as \xhh, \uhhhh or \Uhhhhhhhh. */
    @JvmStatic fun strRepr(s: String): String {
        val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
        val sb = StringBuilder().append(q)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                c == q.code || c == '\\'.code -> sb.append('\\').appendCodePoint(c)
                c == '\n'.code -> sb.append("\\n")
                c == '\r'.code -> sb.append("\\r")
                c == '\t'.code -> sb.append("\\t")
                printable(c) -> sb.appendCodePoint(c)
                c < 0x100 -> sb.append("\\x").append(Integer.toHexString(c).padStart(2, '0'))
                c < 0x10000 -> sb.append("\\u").append(Integer.toHexString(c).padStart(4, '0'))
                else -> sb.append("\\U").append(Integer.toHexString(c).padStart(8, '0'))
            }
        }
        return sb.append(q).toString()
    }

    /** str.isprintable of one code point: not a control, format, surrogate, private, unassigned or separator (but ' '). */
    @JvmStatic fun printable(c: Int): Boolean {
        if (c == ' '.code) return true
        return when (Character.getType(c).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false
            else -> true
        }
    }

    // ------------------------------------------------------------------ items, attributes, iteration

    private fun index(i: Any?, size: Int, what: String): Int {
        val n = small(i) ?: if (i is BigInteger) throw PyException("IndexError", "cannot fit 'int' into an index-sized integer")
            else throw typeError("$what indices must be integers, not ${t(i)}")
        val k = if (n < 0) n + size else n
        if (k < 0 || k >= size) throw PyException("IndexError", "$what index out of range")
        return k.toInt()
    }

    @JvmStatic fun getItem(obj: Any?, key: Any?): Any? =
        if (obj is PyValue && obj.sub != null) PyDunder.getItem(obj, key) else getItemValues(obj, key)

    /** obj[key] of builtin values (a subclass's instance as its base type's value), an object's through its class. */
    @JvmStatic fun getItemValues(obj: Any?, key: Any?): Any? = when (obj) {
        is PyList -> obj.items[index(key, obj.items.size, "list")]
        is PyTuple -> obj.items[index(key, obj.items.size, "tuple")]
        is PyStr -> obj.at(index(key, obj.length, "string"))
        is PyDict -> if (obj.containsKey(key)) obj[key] else throw PyException.keyError(key)
        is PyRange -> {
            val n = obj.length
            if (n > Int.MAX_VALUE) throw PyException("OverflowError", "range too large")
            obj.start + obj.step * index(key, n.toInt(), "range")
        }
        is PyObject -> PyDunder.getItem(obj, key)
        else -> throw typeError("'${t(obj)}' object is not subscriptable")
    }

    @JvmStatic fun setItem(obj: Any?, key: Any?, v: Any?) {
        if (obj is PyValue && obj.sub != null) PyDunder.setItem(obj, key, v) else setItemValues(obj, key, v)
    }

    /** obj[key] = v of builtin values (a subclass's instance as its base type's value), an object's through its class. */
    @JvmStatic fun setItemValues(obj: Any?, key: Any?, v: Any?) {
        when (obj) {
            is PyList -> obj.items[index(key, obj.items.size, "list assignment")] = v
            is PyDict -> obj[key] = v
            is PyObject -> PyDunder.setItem(obj, key, v)
            else -> throw typeError("'${t(obj)}' object does not support item assignment")
        }
    }

    /** obj.name (PyClasses: types, super, instances, the attributes of every object). */
    @JvmStatic fun getAttr(obj: Any?, name: String): Any? = when (obj) {
        is PyType -> PyClasses.typeAttr(obj, name)
        is PySuper -> PyClasses.superAttr(obj, name)
        else -> PyClasses.getAttr(obj, name)
    }

    @JvmStatic fun iter(v: Any?): PyIterator = if (v is PyValue && v.sub != null) PyDunder.iter(v) else iterValues(v)

    /** iter(v) of builtin values (a subclass's instance as its base type's value), an object's through its class. */
    @JvmStatic fun iterValues(v: Any?): PyIterator = when (v) {
        is PyIterator -> v
        is PyRange -> {
            var cur = v.start
            val step = v.step
            val stop = v.stop
            PyIterator { if (if (step > 0) cur < stop else cur > stop) cur.also { cur += step } else PyIterator.STOP }
        }
        is PyList -> {
            var k = 0
            PyIterator { if (k < v.items.size) v.items[k++] else PyIterator.STOP }
        }
        is PyTuple -> {
            var k = 0
            PyIterator { if (k < v.items.size) v.items[k++] else PyIterator.STOP }
        }
        is PyStr -> {
            var k = 0
            val s = v.s
            PyIterator {
                if (k < s.length) {
                    val cp = s.codePointAt(k)
                    k += Character.charCount(cp)
                    PyStr(String(Character.toChars(cp)))
                } else PyIterator.STOP
            }
        }
        is PyDict -> {
            val it = ArrayList(v.map.keys).iterator()
            PyIterator { if (it.hasNext()) it.next().v else PyIterator.STOP }
        }
        is PyObject -> PyDunder.iter(v)
        else -> throw typeError("'${t(v)}' object is not iterable")
    }

    /** The next value of iterator [it], or [PyIterator.STOP]. */
    @JvmStatic fun next(it: Any?): Any? = (it as PyIterator).next()

    @JvmStatic fun isStop(v: Any?): Boolean = v === PyIterator.STOP

    /** The [n] items of [seq] (an unpacking assignment), or a ValueError. */
    @JvmStatic fun unpack(seq: Any?, n: Int): Array<Any?> {
        // (a subclass's instance through its iteration, which its class may change)
        val exact = seq is PyValue && seq.sub == null
        val items: List<Any?> = when {
            exact && seq is PyTuple -> seq.items.asList()
            exact && seq is PyList -> seq.items
            else -> {
                val out = ArrayList<Any?>()
                val it = iter(seq)
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    out.add(x)
                    if (out.size > n) break
                }
                out
            }
        }
        if (items.size < n) throw PyException("ValueError", "not enough values to unpack (expected $n, got ${items.size})")
        if (items.size > n) {
            // (Python 3.14 counts a list's or a tuple's items; an iterator's it stopped taking)
            val got = if (exact && (seq is PyTuple || seq is PyList)) ", got ${items.size}" else ""
            throw PyException("ValueError", "too many values to unpack (expected $n$got)")
        }
        return items.toTypedArray()
    }

    @JvmStatic fun item(items: Any?, k: Int): Any? = (items as Array<*>)[k]

    @JvmStatic fun loadAttr(obj: Any?, name: Any?): Any? = getAttr(obj, (name as PyStr).s)

    /** [iter] as compiled code calls it (an Object). */
    @JvmStatic fun getIter(v: Any?): Any? = iter(v)

    @JvmStatic fun buildList(items: Array<Any?>): Any? = PyList(ArrayList(items.asList()))
    @JvmStatic fun buildTuple(items: Array<Any?>): Any? = if (items.isEmpty()) PyTuple.EMPTY else PyTuple(items)

    /** A dict of keys and values alternating in [kv]. */
    @JvmStatic fun buildMap(kv: Array<Any?>): Any? {
        val d = PyDict()
        for (k in 0 until kv.size / 2) d[kv[2 * k]] = kv[2 * k + 1]
        return d
    }

    /** [v], local [r] of [f]'s function, or an UnboundLocalError where it has no value (null). */
    @JvmStatic fun checkBound(v: Any?, r: Int, f: dev.mooner.neonjs.vm.VmFrame): Any? {
        if (v != null) return v
        val n = (f.unit.dialectData as PyCodeInfo).localNames[r]
        throw PyException("UnboundLocalError", "cannot access local variable '$n' where it is not associated with a value")
    }

    // ------------------------------------------------------------------ comparisons as booleans

    /** The comparison [op] (PyOp.LT..NOT_IN) of [a] and [b]. */
    @JvmStatic fun compare(op: Int, a: Any?, b: Any?): Boolean = when (op) {
        PyOp.LT -> order(0, a, b)
        PyOp.LE -> order(1, a, b)
        PyOp.GT -> order(2, a, b)
        PyOp.GE -> order(3, a, b)
        PyOp.EQ -> eq(a, b)
        PyOp.NE -> ne(a, b)
        PyOp.IS -> same(a, b)
        PyOp.IS_NOT -> !same(a, b)
        PyOp.IN -> contains(b, a)
        PyOp.NOT_IN -> !contains(b, a)
        else -> throw IllegalStateException("not a comparison: $op")
    }

    @JvmStatic fun lt(a: Any?, b: Any?): Boolean = order(0, a, b)
    @JvmStatic fun le(a: Any?, b: Any?): Boolean = order(1, a, b)
    @JvmStatic fun gt(a: Any?, b: Any?): Boolean = order(2, a, b)
    @JvmStatic fun ge(a: Any?, b: Any?): Boolean = order(3, a, b)
    @JvmStatic fun ne(a: Any?, b: Any?): Boolean = if (a is PyObject || b is PyObject) PyDunder.ne(a, b) else !eq(a, b)
    @JvmStatic fun isIn(a: Any?, b: Any?): Boolean = contains(b, a)
    @JvmStatic fun notIn(a: Any?, b: Any?): Boolean = !contains(b, a)

    /**
     * `a is b`: the same object; ints and floats, which the JVM boxes anew, by value (CPython's small ints are one
     * object each; a program relying on `is` for ints relies on that).
     */
    @JvmStatic fun same(a: Any?, b: Any?): Boolean = a === b || (a is Long && b is Long && a == b) || (a is Double && b is Double && a == b)

    /** A bool of the optimizing tier's (an int) from the PyBool a frame's register holds (on-stack replacement). */
    @JvmStatic fun slotBool(v: Any?): Boolean = v === PyBool.TRUE

    // ------------------------------------------------------------------ the speculating tier's helpers

    /**
     * On-stack replacement into speculating code: whether each register whose letter in [kinds] names a kind holds a
     * value of it ('L' an int that is a Long, 'F' a float).
     */
    @JvmStatic fun osrKinds(slots: Array<Any?>, kinds: String): Boolean {
        for (r in kinds.indices) {
            val ok = when (kinds[r]) {
                'L' -> slots[r] is Long
                'F' -> slots[r] is Double
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    // a register dead at the header takes any value of the kind: 0 for one of another kind
    @JvmStatic fun slotLong(slots: Array<Any?>, r: Int): Long = slots[r] as? Long ?: 0L
    @JvmStatic fun slotDouble(slots: Array<Any?>, r: Int): Double = slots[r] as? Double ?: 0.0

    /** Whether a * b leaves 64 bits (Math.multiplyExact's test; Math.multiplyHigh is not on every Android). */
    @JvmStatic fun mulOverflows(a: Long, b: Long): Boolean {
        val r = a * b
        val ax = Math.abs(a)
        val ay = Math.abs(b)
        if ((ax or ay) ushr 31 != 0L) {
            if ((b != 0L && r / b != a) || (a == Long.MIN_VALUE && b == -1L)) return true
        }
        return false
    }

    /** Whether a // b and a % b are ints of 64 bits (b is not 0, and not MIN // -1). */
    @JvmStatic fun divisible(a: Long, b: Long): Boolean = b != 0L && !(a == Long.MIN_VALUE && b == -1L)

    // ------------------------------------------------------------------ names, functions, calls

    /** The global [name] of [f]'s module, else the builtin, else a NameError. */
    @JvmStatic fun loadGlobal(f: dev.mooner.neonjs.vm.VmFrame, name: Any?): Any? {
        val pf = f as PyFrame
        return pf.globals[name] ?: pf.ctx.builtins[name] ?: throw PyException.nameError(name)
    }

    @JvmStatic fun storeGlobal(f: dev.mooner.neonjs.vm.VmFrame, name: Any?, v: Any?) {
        (f as PyFrame).globals[name] = v
    }

    /** A function of [code] (a code block of [PyCompiler]'s), in [f]'s module. */
    @JvmStatic fun makeFunction(f: dev.mooner.neonjs.vm.VmFrame, code: Any?): Any? {
        val cb = code as dev.mooner.neonjs.compiler.CodeUnit
        return PyFunction(cb, (f as PyFrame).globals, (cb.dialectData as PyCodeInfo).name)
    }

    /** Calls [fn] with [args], from frame [f]. */
    @JvmStatic fun call(f: dev.mooner.neonjs.vm.VmFrame, fn: Any?, args: Array<Any?>): Any? {
        val ctx = (f as PyFrame).ctx
        return when (fn) {
            is PyFunction -> PyInterpreter.call(ctx, fn, args)
            is PyBuiltin -> fn.impl(ctx, args)
            is PyBoundMethod -> {
                val all = arrayOfNulls<Any?>(args.size + 1)
                all[0] = fn.self
                args.copyInto(all, 1)
                fn.fn.impl(ctx, all)
            }
            is PyType -> PyTypes.call(ctx, fn, args, null)
            else -> PyCalls.callWith(ctx, fn, args, NO_KW, NO_KW)
        }
    }
}
