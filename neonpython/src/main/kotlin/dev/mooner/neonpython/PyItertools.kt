package dev.mooner.neonpython

/**
 * `itertools` (docs/PYTHON_PLATFORM.md §12, S2), as CPython 3.14 has it: each of its types makes an iterator of the
 * runtime's ([PyIterator], its type the itertools type), as lazy as CPython's (what CPython takes at once, a pool,
 * a product's tuples, is taken at once). Its types are not subclassable here (CPython's are).
 */
/** An itertools iterator: its repr where CPython gives one of its own, its length hint (repeat's), its copy (_tee's). */
class PyIterTool(next: () -> Any?) : PyIterator(next) {
    @JvmField var repr: (() -> String)? = null
    @JvmField var lengthHint: (() -> Any?)? = null
    @JvmField var tee: (() -> PyIterTool)? = null
}

object PyItertools {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private val STOP = PyIterator.STOP
    private val UNSET = Any()

    private fun ctx() = PyContext.current()

    private fun call(f: Any?, vararg a: Any?): Any? = PyCalls.callWith(ctx(), f, arrayOf(*a), NO_ARGS, NO_ARGS)

    private val types = LinkedHashMap<String, PyType>()

    private fun type(name: String): PyType = types.getOrPut(name) {
        PyTypes.newBuiltin(name, subclassable = false).also { it.module = "itertools"; it.tpName = "itertools.$name" }
    }

    private fun iterator(name: String, next: () -> Any?): PyIterTool = PyIterTool(next).also { it.type = type(name) }

    /**
     * Arguments as Argument Clinic takes them: [names] by position or keyword (the first [posOnly] by position
     * only), the first [required] required; CPython's messages.
     */
    private fun clinic(fn: String, a: Array<Any?>, kw: PyDict?, names: List<String>, required: Int, posOnly: Int = 0, kwOnly: List<String> = emptyList()): Array<Any?> {
        val out = arrayOfNulls<Any?>(names.size + kwOnly.size)
        if (a.size > names.size) {
            val most = names.size
            throw PyRt.typeError(if (required == most) "$fn() takes exactly $most argument${if (most == 1) "" else "s"} (${a.size} given)"
                else "$fn() takes at most $most argument${if (most == 1) "" else "s"} (${a.size} given)")
        }
        a.copyInto(out)
        if (kw != null) for ((k, v) in kw.map) {
            val n = (k.v as PyStr).s
            val i = names.indexOf(n).takeIf { it >= posOnly } ?: kwOnly.indexOf(n).takeIf { it >= 0 }?.plus(names.size)
                ?: throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
            if (i < a.size) throw PyRt.typeError("argument for $fn() given by name ('$n') and position (${i + 1})")
            out[i] = v
        }
        for (i in 0 until required) if (out[i] == null) throw PyRt.typeError("$fn() missing required argument '${names[i]}' (pos ${i + 1})")
        return out
    }

    /** Arguments by position only: "cycle expected 1 argument, got 0". */
    private fun positional(fn: String, a: Array<Any?>, kw: PyDict?, min: Int, max: Int = min) {
        if (kw != null && kw.size > 0) throw PyRt.typeError("$fn() takes no keyword arguments")
        if (a.size < min || a.size > max) {
            val n = if (a.size < min) min else max
            val what = if (min == max) "" else if (a.size < min) "at least " else "at most "
            throw PyRt.typeError("$fn expected $what$n argument${if (n == 1) "" else "s"}, got ${a.size}")
        }
    }

    private fun isNumber(v: Any?): Boolean = when (v) {
        is Long, is java.math.BigInteger, is Double, is PyBool, is PyComplex, is PyNumSub -> true
        else -> PyClasses.classOf(v)?.let { c -> listOf("__index__", "__int__", "__float__").any { c.lookup(it) != null } } == true
    }

    /** CPython's PyNumber_AsSsize_t, -1 where it fails (islice's arguments). */
    private fun ssize(v: Any?): Long = when (val i = PyRt.indexValue(v)) {
        is Long -> i
        else -> -1L
    }

    private fun count(r: Any?, what: String): Int {
        val n = PyRt.indexValue(r) as? Long ?: throw PyRt.typeError("'${PyTypes.name(r)}' object cannot be interpreted as an integer")
        if (n < 0) throw PyException("ValueError", what)
        return minOf(n, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun tupleOf(v: Any?): Array<Any?> = PyBuiltins.toList(v).toTypedArray()

    private fun pick(pool: Array<Any?>, idx: IntArray, r: Int): PyTuple = PyTuple(Array(r) { pool[idx[it]] })

    // ------------------------------------------------------------------ tee

    /** A value tee's iterators share (each holds the next it reads; those all passed go). */
    private class TeeNode {
        var value: Any? = null
        var filled = false
        var next: TeeNode? = null
    }

    private class TeeSource(val it: PyIterator) {
        var running = false
    }

    private fun tee(src: TeeSource, start: TeeNode): PyIterTool {
        var node = start
        lateinit var self: PyIterTool
        self = iterator("_tee") {
            if (!node.filled) {
                if (src.running) throw PyException("RuntimeError", "cannot re-enter the tee iterator")
                src.running = true
                val v = try { src.it.next() } finally { src.running = false }
                if (v === STOP) return@iterator STOP
                node.value = v
                node.filled = true
                node.next = TeeNode()
            }
            val v = node.value
            node = node.next!!
            v
        }
        self.tee = { tee(src, node) }
        return self
    }

    private fun teeOf(iterable: Any?): PyIterTool {
        val it = PyRt.iter(iterable)
        (it as? PyIterTool)?.tee?.let { return it() }
        return tee(TeeSource(it), TeeNode())
    }

    // ------------------------------------------------------------------ groupby

    private class GroupBy(val it: PyIterator, val key: Any?) {
        var tgtkey: Any? = UNSET
        var currkey: Any? = UNSET
        var currvalue: Any? = UNSET
        var currgrouper: Any? = null

        fun step(): Boolean {
            val v = it.next()
            if (v === STOP) return false
            val k = if (key == null || key === PyNone) v else call(key, v)
            currvalue = v
            currkey = k
            return true
        }
    }

    private fun grouper(g: GroupBy, tgtkey: Any?): PyIterTool {
        lateinit var self: PyIterTool
        self = iterator("_grouper") {
            if (g.currgrouper !== self) return@iterator STOP
            if (g.currvalue === UNSET && !g.step()) return@iterator STOP
            if (!PyRt.eq(tgtkey, g.currkey)) return@iterator STOP
            val r = g.currvalue
            g.currvalue = UNSET
            r
        }
        return self
    }

    // ------------------------------------------------------------------ the module

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        fun t(name: String, make: (Array<Any?>, PyDict?) -> Any?) {
            val ty = type(name)
            ty.newImpl = { _, _, a, kw -> make(a, kw?.takeIf { it.size > 0 }) }
            d[PyStr(name)] = ty
        }
        t("count") { a, kw ->
            val x = clinic("count", a, kw, listOf("start", "step"), 0)
            var n: Any? = x[0] ?: 0L
            val step: Any? = x[1] ?: 1L
            if (!isNumber(n) || !isNumber(step)) throw PyRt.typeError("a number is required")
            iterator("count") { n.also { n = PyRt.add(n, step) } }.also { self ->
                self.repr = {
                    val one = (step is Long && step == 1L) || (step is PyBool && step.value)
                    if (one) "count(${PyRt.repr(n)})" else "count(${PyRt.repr(n)}, ${PyRt.repr(step)})"
                }
            }
        }
        t("cycle") { a, kw ->
            positional("cycle", a, kw, 1)
            val it = PyRt.iter(a[0])
            val saved = ArrayList<Any?>()
            var first = true
            var i = 0
            iterator("cycle") {
                if (first) {
                    val v = it.next()
                    if (v !== STOP) { saved.add(v); return@iterator v }
                    first = false
                }
                if (saved.isEmpty()) STOP else saved[i].also { i = (i + 1) % saved.size }
            }
        }
        t("repeat") { a, kw ->
            val x = clinic("repeat", a, kw, listOf("object", "times"), 1)
            val v = x[0]
            var n = if (x[1] == null) -1L else maxOf(0L, PyRt.indexValue(x[1]) as? Long
                ?: throw PyRt.typeError("'${PyTypes.name(x[1])}' object cannot be interpreted as an integer"))
            iterator("repeat") { if (n == 0L) STOP else { if (n > 0) n--; v } }.also { self ->
                self.repr = { if (n < 0) "repeat(${PyRt.repr(v)})" else "repeat(${PyRt.repr(v)}, $n)" }
                self.lengthHint = { if (n < 0) throw PyRt.typeError("len() of unsized object") else n }
            }
        }
        t("accumulate") { a, kw ->
            val x = clinic("accumulate", a, kw, listOf("iterable", "func"), 1, kwOnly = listOf("initial"))
            val it = PyRt.iter(x[0])
            val f = x[1]?.takeIf { it !== PyNone }
            var total: Any? = UNSET
            var initial: Any? = x[2]?.takeIf { it !== PyNone } ?: UNSET
            iterator("accumulate") {
                if (initial !== UNSET) {
                    total = initial
                    initial = UNSET
                    return@iterator total
                }
                val v = it.next()
                if (v === STOP) return@iterator STOP
                total = if (total === UNSET) v else if (f == null) PyRt.add(total, v) else call(f, total, v)
                total
            }
        }
        t("batched") { a, kw ->
            val x = clinic("batched", a, kw, listOf("iterable", "n"), 2, kwOnly = listOf("strict"))
            val n = PyRt.indexValue(x[1]) as? Long ?: throw PyRt.typeError("'${PyTypes.name(x[1])}' object cannot be interpreted as an integer")
            if (n < 1) throw PyException("ValueError", "n must be at least one")
            val strict = x[2]?.let { PyRt.truthy(it) } ?: false
            var it: PyIterator? = PyRt.iter(x[0])
            iterator("batched") {
                val src = it ?: return@iterator STOP
                val out = ArrayList<Any?>()
                while (out.size < n) {
                    val v = src.next()
                    if (v === STOP) { it = null; break }
                    out.add(v)
                }
                when {
                    out.isEmpty() -> STOP
                    strict && out.size < n -> throw PyException("ValueError", "batched(): incomplete batch")
                    else -> PyTuple(out.toTypedArray())
                }
            }
        }
        fun chain(src: PyIterator): PyIterator {
            var cur: PyIterator? = null
            return iterator("chain") {
                while (true) {
                    val c = cur ?: run {
                        val next = src.next()
                        if (next === STOP) return@iterator STOP
                        PyRt.iter(next).also { cur = it }
                    }
                    val v = c.next()
                    if (v !== STOP) return@iterator v
                    cur = null
                }
                @Suppress("UNREACHABLE_CODE") STOP
            }
        }
        t("chain") { a, kw ->
            if (kw != null) throw PyRt.typeError("chain() takes no keyword arguments")
            chain(PyRt.iter(PyTuple(a)))
        }
        type("chain").def("from_iterable", PyClassMethod(PyBuiltin("from_iterable") { _, a ->
            if (a.size != 2) throw PyRt.typeError("chain.from_iterable() takes exactly one argument (${a.size - 1} given)")
            chain(PyRt.iter(a[1]))
        }))
        type("chain").def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
        t("compress") { a, kw ->
            val x = clinic("compress", a, kw, listOf("data", "selectors"), 2)
            val data = PyRt.iter(x[0])
            val sel = PyRt.iter(x[1])
            iterator("compress") {
                while (true) {
                    val v = data.next()
                    if (v === STOP) return@iterator STOP
                    val s = sel.next()
                    if (s === STOP) return@iterator STOP
                    if (PyRt.truthy(s)) return@iterator v
                }
                @Suppress("UNREACHABLE_CODE") STOP
            }
        }
        t("dropwhile") { a, kw ->
            positional("dropwhile", a, kw, 2)
            val f = a[0]
            val it = PyRt.iter(a[1])
            var dropping = true
            iterator("dropwhile") {
                while (true) {
                    val v = it.next()
                    if (v === STOP) return@iterator STOP
                    if (!dropping || !PyRt.truthy(call(f, v))) {
                        dropping = false
                        return@iterator v
                    }
                }
                @Suppress("UNREACHABLE_CODE") STOP
            }
        }
        t("takewhile") { a, kw ->
            positional("takewhile", a, kw, 2)
            val f = a[0]
            val it = PyRt.iter(a[1])
            var done = false
            iterator("takewhile") {
                if (done) return@iterator STOP
                val v = it.next()
                if (v === STOP) return@iterator STOP
                if (PyRt.truthy(call(f, v))) v else { done = true; STOP }
            }
        }
        t("filterfalse") { a, kw ->
            positional("filterfalse", a, kw, 2)
            val f = a[0]
            val it = PyRt.iter(a[1])
            iterator("filterfalse") {
                while (true) {
                    val v = it.next()
                    if (v === STOP) return@iterator STOP
                    if (!PyRt.truthy(if (f === PyNone || f == null) v else call(f, v))) return@iterator v
                }
                @Suppress("UNREACHABLE_CODE") STOP
            }
        }
        t("groupby") { a, kw ->
            val x = clinic("groupby", a, kw, listOf("iterable", "key"), 1)
            val g = GroupBy(PyRt.iter(x[0]), x[1])
            iterator("groupby") {
                g.currgrouper = null
                while (true) {
                    if (g.currkey === UNSET) {
                        // (none read yet)
                    } else if (g.tgtkey === UNSET) break
                    else if (!PyRt.eq(g.tgtkey, g.currkey)) break
                    if (!g.step()) return@iterator STOP
                }
                g.tgtkey = g.currkey
                val gr = grouper(g, g.tgtkey)
                g.currgrouper = gr
                PyTuple(arrayOf(g.currkey, gr))
            }
        }
        d[PyStr("_grouper")] = type("_grouper")
        t("islice") { a, kw ->
            if (kw != null) throw PyRt.typeError("islice() takes no keyword arguments")
            if (a.size < 2) throw PyRt.typeError("islice expected at least 2 arguments, got ${a.size}")
            if (a.size > 4) throw PyRt.typeError("islice expected at most 4 arguments, got ${a.size}")
            val stopMsg = "Stop argument for islice() must be None or an integer: 0 <= x <= sys.maxsize."
            var start = 0L
            var stop = -1L
            var step = 1L
            if (a.size == 2) {
                if (a[1] !== PyNone) {
                    stop = ssize(a[1])
                    if (stop == -1L) throw PyException("ValueError", stopMsg)
                }
            } else {
                if (a[1] !== PyNone) start = ssize(a[1])
                if (a[2] !== PyNone) {
                    stop = ssize(a[2])
                    if (stop == -1L) throw PyException("ValueError", stopMsg)
                }
            }
            if (start < 0 || stop < -1) throw PyException("ValueError", "Indices for islice() must be None or an integer: 0 <= x <= sys.maxsize.")
            if (a.size == 4 && a[3] !== PyNone) step = ssize(a[3])
            if (step < 1) throw PyException("ValueError", "Step for islice() must be a positive integer or None.")
            var it: PyIterator? = PyRt.iter(a[0])
            var cnt = 0L
            var next = start
            iterator("islice") {
                val src = it ?: return@iterator STOP
                while (cnt < next) {
                    val v = src.next()
                    if (v === STOP) { it = null; return@iterator STOP }
                    cnt++
                }
                if (stop != -1L && cnt >= stop) { it = null; return@iterator STOP }
                val v = src.next()
                if (v === STOP) { it = null; return@iterator STOP }
                cnt++
                val old = next
                next += step
                if (next < old || (stop != -1L && next > stop)) next = stop
                v
            }
        }
        t("pairwise") { a, kw ->
            positional("pairwise", a, kw, 1)
            var it: PyIterator? = PyRt.iter(a[0])
            var old: Any? = UNSET
            iterator("pairwise") {
                val src = it ?: return@iterator STOP
                if (old === UNSET) {
                    old = src.next()
                    if (old === STOP) { it = null; return@iterator STOP }
                }
                val v = src.next()
                if (v === STOP) { it = null; return@iterator STOP }
                PyTuple(arrayOf(old, v)).also { old = v }
            }
        }
        t("permutations") { a, kw ->
            val x = clinic("permutations", a, kw, listOf("iterable", "r"), 1)
            val pool = tupleOf(x[0])
            val n = pool.size
            val r = if (x[1] == null || x[1] === PyNone) n else count(x[1], "r must be non-negative")
            val idx = IntArray(n) { it }
            val cycles = IntArray(r) { n - it }
            var first = true
            var done = r > n
            iterator("permutations") {
                if (done) return@iterator STOP
                if (first) {
                    first = false
                    return@iterator pick(pool, idx, r)
                }
                var i = r - 1
                while (i >= 0) {
                    cycles[i]--
                    if (cycles[i] == 0) {
                        val v = idx[i]
                        for (j in i until n - 1) idx[j] = idx[j + 1]
                        idx[n - 1] = v
                        cycles[i] = n - i
                    } else {
                        val j = cycles[i]
                        val t = idx[i]; idx[i] = idx[n - j]; idx[n - j] = t
                        return@iterator pick(pool, idx, r)
                    }
                    i--
                }
                done = true
                STOP
            }
        }
        t("combinations") { a, kw ->
            val x = clinic("combinations", a, kw, listOf("iterable", "r"), 2)
            val pool = tupleOf(x[0])
            val r = count(x[1], "r must be non-negative")
            val n = pool.size
            val idx = IntArray(r) { it }
            var first = true
            var done = r > n
            iterator("combinations") {
                if (done) return@iterator STOP
                if (first) {
                    first = false
                    return@iterator pick(pool, idx, r)
                }
                var i = r - 1
                while (i >= 0 && idx[i] == i + n - r) i--
                if (i < 0) { done = true; return@iterator STOP }
                idx[i]++
                for (j in i + 1 until r) idx[j] = idx[j - 1] + 1
                pick(pool, idx, r)
            }
        }
        t("combinations_with_replacement") { a, kw ->
            val x = clinic("combinations_with_replacement", a, kw, listOf("iterable", "r"), 2)
            val pool = tupleOf(x[0])
            val r = count(x[1], "r must be non-negative")
            val n = pool.size
            val idx = IntArray(r)
            var first = true
            var done = n == 0 && r > 0
            iterator("combinations_with_replacement") {
                if (done) return@iterator STOP
                if (first) {
                    first = false
                    return@iterator pick(pool, idx, r)
                }
                var i = r - 1
                while (i >= 0 && idx[i] == n - 1) i--
                if (i < 0) { done = true; return@iterator STOP }
                val v = idx[i] + 1
                for (j in i until r) idx[j] = v
                pick(pool, idx, r)
            }
        }
        t("product") { a, kw ->
            val rep = kw?.let { k ->
                for (key in k.map.keys) if ((key.v as PyStr).s != "repeat") throw PyRt.typeError("product() got an unexpected keyword argument '${(key.v as PyStr).s}'")
                k[PyStr("repeat")]
            }
            val times = if (rep == null) 1 else {
                val n = PyRt.indexValue(rep) as? Long ?: throw PyRt.typeError("'${PyTypes.name(rep)}' object cannot be interpreted as an integer")
                if (n < 0) throw PyException("ValueError", "repeat argument cannot be negative")
                n.toInt()
            }
            val one = a.map { tupleOf(it) }
            val pools = ArrayList<Array<Any?>>()
            repeat(times) { pools.addAll(one) }
            val m = pools.size
            val idx = IntArray(m)
            var first = true
            var done = pools.any { it.isEmpty() }
            iterator("product") {
                if (done) return@iterator STOP
                if (!first) {
                    var i = m - 1
                    while (i >= 0) {
                        idx[i]++
                        if (idx[i] < pools[i].size) break
                        idx[i] = 0
                        i--
                    }
                    if (i < 0) { done = true; return@iterator STOP }
                }
                first = false
                if (m == 0) done = true
                PyTuple(Array(m) { pools[it][idx[it]] })
            }
        }
        t("starmap") { a, kw ->
            positional("starmap", a, kw, 2)
            val f = a[0]
            val it = PyRt.iter(a[1])
            iterator("starmap") {
                val v = it.next()
                if (v === STOP) STOP else PyCalls.callWith(ctx(), f, if (v is PyTuple && v.sub == null) v.items else tupleOf(v), NO_ARGS, NO_ARGS)
            }
        }
        t("zip_longest") { a, kw ->
            var fill: Any? = PyNone
            kw?.let { k ->
                for (key in k.map.keys) if ((key.v as PyStr).s != "fillvalue") throw PyRt.typeError("zip_longest() got an unexpected keyword argument '${(key.v as PyStr).s}'")
                fill = k[PyStr("fillvalue")]
            }
            val its = arrayOfNulls<PyIterator>(a.size)
            for (i in a.indices) its[i] = PyRt.iter(a[i])
            var active = a.size
            iterator("zip_longest") {
                if (active == 0) return@iterator STOP
                val out = arrayOfNulls<Any?>(its.size)
                for (i in its.indices) {
                    val src = its[i]
                    if (src == null) {
                        out[i] = fill
                        continue
                    }
                    val v = src.next()
                    if (v === STOP) {
                        its[i] = null
                        active--
                        if (active == 0) return@iterator STOP
                        out[i] = fill
                    } else out[i] = v
                }
                PyTuple(out)
            }
        }
        // tee(iterable, n=2): n iterators over one, each its own copy (a _tee's __copy__)
        type("_tee").newImpl = { _, _, a, _ ->
            if (a.size != 1) throw PyRt.typeError("_tee expected 1 argument, got ${a.size}")
            teeOf(a[0])
        }
        d[PyStr("_tee")] = type("_tee")
        d[PyStr("tee")] = PyBuiltin("tee") { _, a ->
            if (a.isEmpty() || a.size > 2) throw PyRt.typeError("tee expected at most 2 arguments, got ${a.size}")
            val n = if (a.size > 1) (PyRt.indexValue(a[1]) as? Long ?: throw PyRt.typeError("'${PyTypes.name(a[1])}' object cannot be interpreted as an integer")) else 2L
            if (n < 0) throw PyException("ValueError", "n must be >= 0")
            if (n == 0L) return@PyBuiltin PyTuple.EMPTY
            val base = teeOf(a[0])
            PyTuple(Array(n.toInt()) { base.tee!!() })
        }
        type("_tee").def("__copy__", PyBuiltin("__copy__") { _, a -> (a[0] as PyIterTool).tee!!() })
        type("repeat").def("__length_hint__", PyBuiltin("__length_hint__") { _, a -> (a[0] as PyIterTool).lengthHint!!() })
    }
}
