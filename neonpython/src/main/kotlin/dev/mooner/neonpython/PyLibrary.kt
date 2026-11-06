package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * The builtins every program uses (docs/PYTHON_PLATFORM.md §11: the builtins a language needs): sorted, reversed,
 * enumerate, zip, map, filter, any, all, min and max, round, divmod, pow, chr, ord, hex, oct, bin, id, vars, globals,
 * locals, sum; the iterators' types; ints' and floats' methods. As CPython 3.14 has them, its messages too.
 */
object PyLibrary {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    private fun ctx() = PyContext.current()

    private fun call(f: Any?, vararg args: Any?): Any? = PyCalls.callWith(ctx(), f, arrayOf(*args), NO_ARGS, NO_ARGS)

    /** An iterator of type [t] (map, zip, list_iterator...), stepping [next]. */
    @JvmStatic fun iterator(t: PyType, next: () -> Any?): PyIterator = PyIterator(next).also { it.type = t }

    private fun kwArg(kw: PyDict?, name: String, allowed: Set<String>, fn: String): Any? {
        if (kw == null) return null
        for (k in kw.map.keys) {
            val n = (k.v as PyStr).s
            if (n !in allowed) throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
        }
        return kw[PyStr(name)]
    }

    /** A stable merge sort of [items] by [lt] (CPython's sort is stable and asks only `<`). */
    @JvmStatic
    fun sort(items: ArrayList<Any?>, key: Any?, reverse: Boolean) {
        if (items.size < 2) return
        val keys: ArrayList<Any?> = if (key == null || key === PyNone) items else ArrayList<Any?>(items.size).also { k -> for (x in items) k.add(call(key, x)) }
        // (reverse: reversed, sorted, reversed again, so that equal items keep their order, as CPython does)
        val idx = IntArray(items.size) { it }
        if (reverse) idx.reverse()
        val tmp = IntArray(idx.size)
        fun lt(i: Int, j: Int) = PyRt.order(0, keys[i], keys[j])
        fun merge(lo: Int, mid: Int, hi: Int) {
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) tmp[k++] = if (lt(idx[j], idx[i])) idx[j++] else idx[i++]
            while (i < mid) tmp[k++] = idx[i++]
            while (j < hi) tmp[k++] = idx[j++]
            for (x in lo until hi) idx[x] = tmp[x]
        }
        var width = 1
        // (runs of 8 by insertion first)
        val run = 8
        var s = 0
        while (s < idx.size) {
            val e = minOf(s + run, idx.size)
            for (i in s + 1 until e) {
                val v = idx[i]
                var j = i - 1
                while (j >= s && lt(v, idx[j])) {
                    idx[j + 1] = idx[j]
                    j--
                }
                idx[j + 1] = v
            }
            s = e
        }
        width = run
        while (width < idx.size) {
            var lo = 0
            while (lo < idx.size - width) {
                merge(lo, lo + width, minOf(lo + 2 * width, idx.size))
                lo += 2 * width
            }
            width *= 2
        }
        if (reverse) idx.reverse()
        val sorted = ArrayList<Any?>(items.size)
        for (i in idx) sorted.add(items[i])
        items.clear()
        items.addAll(sorted)
    }

    /** float.hex(): CPython's form, 13 hex digits of the fraction and the exponent's sign (`0x1.8000000000000p+0`). */
    private fun floatHex(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val sign = if (bits < 0) "-" else ""
        val e = ((bits ushr 52) and 0x7ff).toInt()
        val frac = bits and 0xfffffffffffffL
        if (e == 0 && frac == 0L) return sign + "0x0.0p+0"
        val digits = java.lang.Long.toHexString(frac).padStart(13, '0')
        if (e == 0) return sign + "0x0." + digits + "p-1022"
        val exp = e - 1023
        return sign + "0x1." + digits + "p" + (if (exp >= 0) "+" else "") + exp
    }

    /** float.fromhex(s): [sign] [0x] digits [. digits] [p [sign] digits], or inf and nan; CPython's errors. */
    private fun fromHex(s0: String): Double {
        val s = s0.trim().lowercase()
        fun bad(): Nothing = throw PyException("ValueError", "invalid hexadecimal floating-point string")
        var i = 0
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) { neg = s[i] == '-'; i++ }
        val rest = s.substring(i)
        if (rest == "inf" || rest == "infinity") return if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (rest == "nan") return Double.NaN
        if (s.startsWith("0x", i)) i += 2
        var mant = BigInteger.ZERO
        var digits = 0
        var fracDigits = 0
        while (i < s.length && Character.digit(s[i], 16) >= 0) { mant = mant.shiftLeft(4).add(BigInteger.valueOf(Character.digit(s[i], 16).toLong())); i++; digits++ }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && Character.digit(s[i], 16) >= 0) { mant = mant.shiftLeft(4).add(BigInteger.valueOf(Character.digit(s[i], 16).toLong())); i++; digits++; fracDigits++ }
        }
        if (digits == 0) bad()
        var exp = 0L
        if (i < s.length && s[i] == 'p') {
            i++
            var eneg = false
            if (i < s.length && (s[i] == '+' || s[i] == '-')) { eneg = s[i] == '-'; i++ }
            val st = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == st) bad()
            exp = s.substring(st, i).toBigInteger().min(BigInteger.valueOf(100000)).toLong().let { if (eneg) -it else it }
        }
        if (i != s.length) bad()
        // the value mant * 2**(exp - 4 * fracDigits), exactly rounded (BigDecimal of the power of two is exact)
        val e2 = exp - 4L * fracDigits
        if (mant.signum() == 0) return if (neg) -0.0 else 0.0
        if (mant.bitLength() + e2 > 1025) throw PyException("OverflowError", "hexadecimal value too large to represent as a float")
        val v = if (e2 >= 0) BigDecimal(mant.shiftLeft(e2.toInt())) else BigDecimal(mant).divide(BigDecimal(BigInteger.ONE.shiftLeft((-e2).toInt().coerceAtMost(2000))))
        val d = v.toDouble()
        if (d.isInfinite()) throw PyException("OverflowError", "hexadecimal value too large to represent as a float")
        return if (neg) -d else d
    }

    private fun index(v0: Any?, what: String = ""): Long = when (val v = PyRt.indexValue(v0) ?: v0) {
        is Long -> v
        is PyBool -> if (v.value) 1L else 0L
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer$what")
    }

    private fun big(v: Any?): BigInteger = when (v) {
        is Long -> BigInteger.valueOf(v)
        is BigInteger -> v
        is PyBool -> if (v.value) BigInteger.ONE else BigInteger.ZERO
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** round(x, n): ints and floats half to even (a float's exact value), a class's __round__. */
    @JvmStatic
    fun round(x: Any?, nd: Any?): Any? {
        if (PyRt.special(x)) {
            val f = PyDunder.userMethod(PyTypes.typeOf(x), "__round__")
            // (an int's or a float's subclass: its number rounded)
            if (f == null && x is PyNumSub) return round(x.num, nd)
            f ?: throw PyRt.typeError("type ${PyTypes.name(x)} doesn't define __round__ method")
            return if (nd == null) call(PyClasses.descrGet(null, f, x, PyTypes.typeOf(x))) else call(PyClasses.descrGet(null, f, x, PyTypes.typeOf(x)), nd)
        }
        when (x) {
            is Long, is BigInteger, is PyBool -> {
                if (nd == null || nd === PyNone) return if (x is PyBool) (if (x.value) 1L else 0L) else x
                val n = index(nd)
                if (n >= 0) return if (x is PyBool) (if (x.value) 1L else 0L) else x
                return PyRt.norm(BigDecimal(big(x)).setScale(n.toInt(), RoundingMode.HALF_EVEN).toBigInteger())
            }
            is Double -> {
                if (nd == null || nd === PyNone) {
                    if (x.isNaN()) throw PyException("ValueError", "cannot convert float NaN to integer")
                    if (x.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
                    return PyRt.norm(BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toBigInteger())
                }
                val n = index(nd)
                if (x.isNaN() || x.isInfinite() || x == 0.0) return x
                if (n > 400) return x
                if (n < -400) return 0.0 * Math.signum(x)
                val r = BigDecimal(x).setScale(n.toInt(), RoundingMode.HALF_EVEN).toDouble()
                return if (r == 0.0 && x < 0) -0.0 else r
            }
            else -> throw PyRt.typeError("type ${PyTypes.name(x)} doesn't define __round__ method")
        }
    }

    /** pow(b, e, mod): ints only with a modulus (a negative exponent its inverse). */
    @JvmStatic
    fun pow3(b: Any?, e: Any?, m: Any?): Any? {
        if (!PyRt.isInt(b) || !PyRt.isInt(e) || !PyRt.isInt(m)) throw PyRt.typeError("pow() 3rd argument not allowed unless all arguments are integers")
        val mod = big(m)
        if (mod.signum() == 0) throw PyException("ValueError", "pow() 3rd argument cannot be 0")
        var base = big(b)
        var exp = big(e)
        val am = mod.abs()
        if (exp.signum() < 0) {
            base = try {
                base.modInverse(am)
            } catch (x: ArithmeticException) {
                throw PyException("ValueError", "base is not invertible for the given modulus")
            }
            exp = exp.negate()
        }
        var r = base.modPow(exp, am)
        // (the result has the modulus's sign, as % gives it)
        if (mod.signum() < 0 && r.signum() != 0) r = r.add(mod)
        return PyRt.norm(r)
    }

    /** sum(iterable, start): ints as ints, floats with CPython 3.12's compensated (Neumaier) sum, the rest by +. */
    @JvmStatic
    fun sum(iterable: Any?, start: Any?): Any? {
        if (start is PyStr) throw PyRt.typeError("sum() can't sum strings [use ''.join(seq) instead]")
        if (start is PyBytes) throw PyRt.typeError("sum() can't sum bytes [use b''.join(seq) instead]")
        val it = PyRt.iter(iterable)
        var result: Any? = start
        var pending: Any? = PyIterator.STOP
        if (result is Long || result is PyBool) {
            // (ints, while they are ints that fit)
            var acc = (result as? Long) ?: if ((result as PyBool).value) 1L else 0L
            var big: BigInteger? = null
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) return big?.add(BigInteger.valueOf(acc))?.let(PyRt::norm) ?: acc
                if (x is Long) {
                    val r = acc + x
                    if (((acc xor r) and (x xor r)) < 0) {
                        big = (big ?: BigInteger.ZERO).add(BigInteger.valueOf(acc)).add(BigInteger.valueOf(x))
                        acc = 0
                    } else acc = r
                    continue
                }
                result = big?.add(BigInteger.valueOf(acc))?.let(PyRt::norm) ?: acc
                result = PyRt.add(result, x)
                break
            }
        }
        if (result is Double) {
            var f: Double = result
            var c = 0.0
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) return if (c != 0.0 && !c.isInfinite() && !c.isNaN()) f + c else f
                if (x is Double) {
                    val t = f + x
                    c += if (Math.abs(f) >= Math.abs(x)) (f - t) + x else (x - t) + f
                    f = t
                    continue
                }
                if (x is Long) {
                    f += x.toDouble()
                    continue
                }
                result = if (c != 0.0 && !c.isInfinite() && !c.isNaN()) f + c else f
                pending = x
                break
            }
        }
        if (pending !== PyIterator.STOP) result = PyRt.add(result, pending)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) return result
            result = PyRt.add(result, x)
        }
    }

    /** min (op 0) or max (op 2): of an iterable or of the arguments, by key, with a default for an empty iterable. */
    private fun extreme(name: String, op: Int, args: Array<Any?>, kw: PyDict?): Any? {
        val key = kwArg(kw, "key", setOf("key", "default"), name).let { if (it === PyNone) null else it }
        val dflt = kw?.let { if (it.containsKey(PyStr("default"))) it[PyStr("default")] else PyIterator.STOP } ?: PyIterator.STOP
        if (args.isEmpty()) throw PyRt.typeError("$name expected at least 1 argument, got 0")
        if (args.size > 1 && dflt !== PyIterator.STOP) throw PyRt.typeError("Cannot specify a default for $name() with multiple positional arguments")
        val it = if (args.size == 1) PyRt.iter(args[0]) else PyRt.iter(PyTuple(args))
        var best: Any? = PyIterator.STOP
        var bestKey: Any? = null
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            val k = if (key == null) x else call(key, x)
            if (best === PyIterator.STOP || PyRt.order(op, k, bestKey)) {
                best = x
                bestKey = k
            }
        }
        if (best === PyIterator.STOP) {
            if (dflt !== PyIterator.STOP) return dflt
            throw PyException("ValueError", "$name() iterable argument is empty")
        }
        return best
    }

    /** reversed(seq): its __reversed__, a list's, else by length and index. */
    private fun reversed(v: Any?): Any? {
        if (PyRt.special(v)) PyDunder.userMethod(PyTypes.typeOf(v), "__reversed__")?.let { return call(PyClasses.descrGet(null, it, v, PyTypes.typeOf(v))) }
        when (v) {
            is PyList -> {
                var k = v.items.size - 1
                return iterator(type("list_reverseiterator")) { if (k >= 0 && k < v.items.size) v.items[k--] else PyIterator.STOP }
            }
            is PyRange -> {
                val n = v.length
                val r = PyRange(v.start + (n - 1) * v.step, v.start - v.step, -v.step)
                return PyRt.iterValues(r)
            }
            is PyDictView -> {
                val items = ArrayList<Any?>()
                val it = PyContainers.iter(v.dict, v.kind)
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    items.add(x)
                }
                var k = items.size - 1
                return iterator(type(arrayOf("dict_reversekeyiterator", "dict_reversevalueiterator", "dict_reverseitemiterator")[v.kind])) {
                    if (k >= 0) items[k--] else PyIterator.STOP
                }
            }
            is PyDict -> {
                val keys = ArrayList(v.map.keys).asReversed()
                var k = 0
                return iterator(type("dict_reversekeyiterator")) { if (k < keys.size) keys[k++].v else PyIterator.STOP }
            }
        }
        val t = PyTypes.typeOf(v)
        if (v is PySet || v is PyDict || t.lookup("__getitem__") == null || t.lookup("__len__") == null && v !is PyValue) {
            throw PyRt.typeError("'${t.name}' object is not reversible")
        }
        var k = PyRt.len(v) - 1
        return iterator(type("reversed")) { if (k >= 0) PyRt.getItem(v, k--) else PyIterator.STOP }
    }

    private val iterTypes = HashMap<String, PyType>()

    @JvmField val LIST_ITERATOR = type("list_iterator")
    @JvmField val TUPLE_ITERATOR = type("tuple_iterator")
    @JvmField val STR_ASCII_ITERATOR = type("str_ascii_iterator")
    @JvmField val STR_ITERATOR = type("str_iterator")
    @JvmField val DICT_KEYITERATOR = type("dict_keyiterator")
    @JvmField val RANGE_ITERATOR = type("range_iterator")
    @JvmField val SET_ITERATOR = type("set_iterator")
    @JvmField val BYTES_ITERATOR = type("bytes_iterator")

    /** The iterator type [n] (each with __next__ and __iter__). */
    @JvmStatic
    @Synchronized
    fun type(n: String): PyType = iterTypes.getOrPut(n) {
        val t = PyTypes.newBuiltin(n, subclassable = false)
        t.def("__next__", PyBuiltin("__next__") { _, a ->
            val v = (a[0] as PyIterator).next()
            if (v === PyIterator.STOP) throw PyException("StopIteration", "") else v
        })
        t.def("__iter__", PyBuiltin("__iter__") { _, a -> a[0] })
        t
    }

    // ------------------------------------------------------------------ locals() in a function

    /** LOAD_LOCALS: a function's locals, those with a value (a cell's contents for its cells), as a dict. */
    @JvmStatic
    fun loadLocals(values: Any?, names: Any?): Any? {
        val d = PyDict()
        val vs = (values as PyTuple).items
        val ns = (names as PyTuple).items
        for (i in vs.indices) {
            val pair = (ns[i] as PyTuple).items
            var v = vs[i]
            if (v != null && pair[1] === PyBool.TRUE) v = (v as PyCell).value
            if (v != null) d[pair[0]] = v
        }
        return d
    }

    // ------------------------------------------------------------------ the builtins

    @JvmStatic
    fun define(d: PyDict) {
        fun def(name: String, impl: (Array<Any?>, PyDict?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) })
        }
        fun noKw(name: String, kw: PyDict?) {
            if (kw != null && kw.size > 0) throw PyRt.typeError("$name() takes no keyword arguments")
        }
        fun argc(name: String, a: Array<Any?>, min: Int, max: Int = min) {
            if (a.size < min) throw PyRt.typeError("$name expected at least $min argument${if (min == 1) "" else "s"}, got ${a.size}")
            if (a.size > max) throw PyRt.typeError("$name expected at most $max argument${if (max == 1) "" else "s"}, got ${a.size}")
        }
        def("sorted") { a, kw ->
            if (a.size != 1) throw PyRt.typeError("sorted expected 1 argument, got ${a.size}")
            val items = PyBuiltins.toList(a[0])
            val key = kwArg(kw, "key", setOf("key", "reverse"), "sort")
            sort(items, key, PyRt.truthy(kw?.get(PyStr("reverse")) ?: PyBool.FALSE))
            PyList(items)
        }
        def("min") { a, kw -> extreme("min", 0, a, kw) }
        def("max") { a, kw -> extreme("max", 2, a, kw) }
        def("any") { a, kw ->
            noKw("any", kw)
            argc("any", a, 1)
            val it = PyRt.iter(a[0])
            var r = false
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                if (PyRt.truthy(x)) { r = true; break }
            }
            PyBool.of(r)
        }
        def("all") { a, kw ->
            noKw("all", kw)
            argc("all", a, 1)
            val it = PyRt.iter(a[0])
            var r = true
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                if (!PyRt.truthy(x)) { r = false; break }
            }
            PyBool.of(r)
        }
        def("round") { a, kw ->
            val nd = kwArg(kw, "ndigits", setOf("number", "ndigits"), "round") ?: a.getOrNull(1)
            val x = a.getOrNull(0) ?: kw?.get(PyStr("number")) ?: throw PyRt.typeError("round() missing required argument 'number' (pos 1)")
            round(x, nd)
        }
        def("divmod") { a, kw ->
            noKw("divmod", kw)
            argc("divmod", a, 2)
            PyTuple(arrayOf(PyRt.floordiv(a[0], a[1]), PyRt.mod(a[0], a[1])))
        }
        def("pow") { a, kw ->
            val mod = kwArg(kw, "mod", setOf("base", "exp", "mod"), "pow") ?: a.getOrNull(2)
            if (a.size < 2) throw PyRt.typeError("pow() missing required argument 'exp' (pos 2)")
            if (mod == null || mod === PyNone) PyRt.pow(a[0], a[1]) else pow3(a[0], a[1], mod)
        }
        def("chr") { a, kw ->
            noKw("chr", kw)
            argc("chr", a, 1)
            val c = index(a[0])
            if (c < 0 || c > 0x10ffff) throw PyException("ValueError", "chr() arg not in range(0x110000)")
            PyStr(String(Character.toChars(c.toInt())))
        }
        def("ord") { a, kw ->
            noKw("ord", kw)
            argc("ord", a, 1)
            when (val v = a[0]) {
                is PyStr -> {
                    if (v.length != 1) throw PyRt.typeError("ord() expected a character, but string of length ${v.length} found")
                    v.s.codePointAt(0).toLong()
                }
                is PyBytes -> {
                    if (v.b.size != 1) throw PyRt.typeError("ord() expected a character, but string of length ${v.b.size} found")
                    (v.b[0].toInt() and 0xFF).toLong()
                }
                else -> throw PyRt.typeError("ord() expected string of length 1, but ${PyTypes.name(v)} found")
            }
        }
        fun radix(name: String, r: Int, prefix: String) = def(name) { a, kw ->
            noKw(name, kw)
            argc("$name()", a, 1)
            val n = big(PyRt.indexValue(a[0]) ?: throw PyRt.typeError("'${PyTypes.name(a[0])}' object cannot be interpreted as an integer"))
            PyStr((if (n.signum() < 0) "-" else "") + prefix + n.abs().toString(r))
        }
        radix("hex", 16, "0x")
        radix("oct", 8, "0o")
        radix("bin", 2, "0b")
        def("id") { a, kw ->
            noKw("id", kw)
            argc("id", a, 1)
            System.identityHashCode(a[0]).toLong()
        }
        def("vars") { a, kw ->
            noKw("vars", kw)
            if (a.size > 1) throw PyRt.typeError("vars expected at most 1 argument, got ${a.size}")
            if (a.isEmpty()) throw PyRt.typeError("vars() without an argument is not supported here")
            try {
                PyRt.getAttr(a[0], "__dict__")
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) throw PyRt.typeError("vars() argument must have __dict__ attribute") else throw e
            }
        }
        // globals() and locals(): the caller's frame's (locals() in a function is the compiler's: LOAD_LOCALS)
        d[PyStr("globals")] = PyBuiltin("globals") { _, _ -> throw PyRt.typeError("globals() needs its caller's frame") }.also { b ->
            b.frameImpl = { f, a ->
                if (a.isNotEmpty()) throw PyRt.typeError("globals() takes no arguments (${a.size} given)")
                (f as PyFrame).globals
            }
        }
        d[PyStr("locals")] = PyBuiltin("locals") { _, _ -> throw PyRt.typeError("locals() needs its caller's frame") }.also { b ->
            b.frameImpl = { f, a ->
                if (a.isNotEmpty()) throw PyRt.typeError("locals() takes no arguments (${a.size} given)")
                (f as PyFrame).locals ?: f.globals
            }
        }
        def("sum") { a, kw ->
            val start = kwArg(kw, "start", setOf("start"), "sum") ?: a.getOrNull(1) ?: 0L
            if (a.isEmpty() || a.size > 2) throw PyRt.typeError("sum() takes at least 1 positional argument (${a.size} given)")
            sum(a[0], start)
        }
        // the iterators' types: map, filter, zip, enumerate, reversed
        fun iterType(name: String, make: (Array<Any?>, PyDict?) -> Any?) {
            val t = type(name)
            t.newImpl = { _, _, a, kw -> make(a, kw) }
            d[PyStr(name)] = t
        }
        iterType("map") { a, kw ->
            noKw("map", kw)
            if (a.size < 2) throw PyRt.typeError("map() must have at least two arguments.")
            val f = a[0]
            val its = Array(a.size - 1) { PyRt.iter(a[it + 1]) }
            iterator(type("map")) {
                val xs = arrayOfNulls<Any?>(its.size)
                var done = false
                for (i in its.indices) {
                    val x = its[i].next()
                    if (x === PyIterator.STOP) { done = true; break }
                    xs[i] = x
                }
                if (done) PyIterator.STOP else PyCalls.callWith(ctx(), f, xs, NO_ARGS, NO_ARGS)
            }
        }
        iterType("filter") { a, kw ->
            noKw("filter", kw)
            if (a.size != 2) throw PyRt.typeError("filter expected 2 arguments, got ${a.size}")
            val f = a[0]
            val it = PyRt.iter(a[1])
            iterator(type("filter")) {
                var r: Any? = PyIterator.STOP
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    if (PyRt.truthy(if (f === PyNone) x else call(f, x))) { r = x; break }
                }
                r
            }
        }
        iterType("zip") { a, kw ->
            val strict = PyRt.truthy(kwArg(kw, "strict", setOf("strict"), "zip") ?: PyBool.FALSE)
            val its = Array(a.size) { PyRt.iter(a[it]) }
            var over = false
            iterator(type("zip")) {
                if (over || its.isEmpty()) PyIterator.STOP else {
                    val xs = arrayOfNulls<Any?>(its.size)
                    var stop = -1
                    for (i in its.indices) {
                        val x = its[i].next()
                        if (x === PyIterator.STOP) { stop = i; break }
                        xs[i] = x
                    }
                    if (stop < 0) PyTuple(xs) else {
                        over = true
                        if (strict) {
                            if (stop > 0) throw PyException("ValueError", "zip() argument ${stop + 1} is shorter than argument${if (stop > 1) "s 1-$stop" else " 1"}")
                            for (i in 1 until its.size) if (its[i].next() !== PyIterator.STOP) {
                                throw PyException("ValueError", "zip() argument ${i + 1} is longer than argument${if (i > 1) "s 1-$i" else " 1"}")
                            }
                        }
                        PyIterator.STOP
                    }
                }
            }
        }
        iterType("enumerate") { a, kw ->
            val start = kwArg(kw, "start", setOf("iterable", "start"), "enumerate") ?: a.getOrNull(1) ?: 0L
            if (a.isEmpty() || a.size > 2) throw PyRt.typeError("enumerate() missing required argument 'iterable'")
            val it = PyRt.iter(a[0])
            var n: Any? = start
            iterator(type("enumerate")) {
                val x = it.next()
                if (x === PyIterator.STOP) PyIterator.STOP else PyTuple(arrayOf(n, x)).also { n = PyRt.add(n, 1L) }
            }
        }
        iterType("reversed") { a, kw ->
            noKw("reversed", kw)
            if (a.size != 1) throw PyRt.typeError("reversed expected 1 argument, got ${a.size}")
            reversed(a[0])
        }
        // ints' and floats' methods
        fun m(t: PyType, n: String, impl: (Array<Any?>, PyDict?) -> Any?) =
            t.def(n, PyBuiltin(n, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        for (t in arrayOf(PyTypes.INT, PyTypes.BOOL)) {
            m(t, "bit_length") { a, _ -> big(a[0]).abs().bitLength().toLong() }
            m(t, "bit_count") { a, _ -> big(a[0]).abs().bitCount().toLong() }
            m(t, "is_integer") { _, _ -> PyBool.TRUE }
            m(t, "as_integer_ratio") { a, _ -> PyTuple(arrayOf(PyRt.norm(big(a[0])), 1L)) }
            m(t, "to_bytes") { a, kw ->
                val n = big(a[0])
                val length = index(kw?.get(PyStr("length")) ?: a.getOrNull(1) ?: 1L).toInt()
                val order = ((kw?.get(PyStr("byteorder")) ?: a.getOrNull(2) ?: PyStr("big")) as PyStr).s
                val signed = PyRt.truthy(kw?.get(PyStr("signed")) ?: PyBool.FALSE)
                if (!signed && n.signum() < 0) throw PyException("OverflowError", "can't convert negative int to unsigned")
                val raw = n.toByteArray()
                val bytes = if (!signed && raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
                if (bytes.size > length && !(n.signum() == 0 && length == 0)) throw PyException("OverflowError", "int too big to convert")
                val out = ByteArray(length) { if (n.signum() < 0) -1 else 0 }
                bytes.copyInto(out, length - bytes.size)
                if (order == "little") out.reverse() else if (order != "big") throw PyException("ValueError", "byteorder must be either 'little' or 'big'")
                PyBytes(out)
            }
        }
        PyTypes.INT.def("from_bytes", PyClassMethod(PyBuiltin("from_bytes", { _, a -> fromBytes(a, null) }, { _, a, kw -> fromBytes(a, kw) })))
        m(PyTypes.FLOAT, "is_integer") { a, _ -> val x = a[0] as Double; PyBool.of(!x.isInfinite() && x == Math.floor(x)) }
        m(PyTypes.FLOAT, "hex") { a, _ -> PyStr(floatHex(a[0] as Double)) }
        PyTypes.FLOAT.def("fromhex", PyClassMethod(PyBuiltin("fromhex") { ctx, a ->
            val v = fromHex((a.getOrNull(1) as? PyStr ?: throw PyRt.typeError("fromhex() argument must be str, not ${PyTypes.name(a.getOrNull(1))}")).s)
            val cls = a[0] as PyType
            if (cls === PyTypes.FLOAT) v else PyCalls.callWith(ctx, cls, arrayOf(v), arrayOfNulls(0), arrayOfNulls(0))
        }))
        m(PyTypes.FLOAT, "as_integer_ratio") { a, _ ->
            val x = a[0] as Double
            if (x.isInfinite()) throw PyException("OverflowError", "cannot convert Infinity to integer ratio")
            if (x.isNaN()) throw PyException("ValueError", "cannot convert NaN to integer ratio")
            val bd = BigDecimal(x)
            val num = bd.unscaledValue()
            val den = BigInteger.TEN.pow(maxOf(bd.scale(), 0))
            val n2 = if (bd.scale() < 0) num.multiply(BigInteger.TEN.pow(-bd.scale())) else num
            val g = n2.gcd(den)
            PyTuple(arrayOf(PyRt.norm(n2.divide(g)), PyRt.norm(den.divide(g))))
        }
    }

    private fun fromBytes(a: Array<Any?>, kw: PyDict?): Any? {
        val b = (kw?.get(PyStr("bytes")) ?: a.getOrNull(1)).let { v -> v as? PyBytes ?: PyLiterals.newBytes(arrayOf(v)) }.b
        val order = ((kw?.get(PyStr("byteorder")) ?: a.getOrNull(2) ?: PyStr("big")) as PyStr).s
        val signed = PyRt.truthy(kw?.get(PyStr("signed")) ?: PyBool.FALSE)
        val be = if (order == "little") b.reversedArray() else b
        if (be.isEmpty()) return 0L
        return PyRt.norm(if (signed) BigInteger(be) else BigInteger(1, be))
    }
}
