package dev.mooner.neonpython

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * weakref.ref (and its subclasses, weakref.py's KeyedRef and WeakMethod): an object holding [referent] weakly, and
 * [callback], called with it once the JVM's collector found the referent unreachable.
 */
class PyWeakRef(type: PyType, referent: Any, @JvmField val callback: Any?, queue: ReferenceQueue<Any>) : PyObject(type) {
    @JvmField val ref = PyWeakrefs.Ref(this, referent, queue)
    /** The referent's hash, once taken (a dead reference's stays). */
    @JvmField var hash: Long? = null
}

/** weakref.proxy: the referent's operations and attributes, while it lives; ReferenceError after. */
class PyWeakProxy(type: PyType, referent: Any, @JvmField val callback: Any?, queue: ReferenceQueue<Any>) : PyObject(type) {
    @JvmField val ref = PyWeakrefs.Ref(this, referent, queue)

    fun get(): Any = ref.get() ?: throw PyException("ReferenceError", "weakly-referenced object no longer exists")
}

/**
 * Weak references and `__del__` (docs/PYTHON_PLATFORM.md §12.2, L4). The JVM counts no references: a referent goes when
 * its collector finds it unreachable, and the callbacks and `__del__` methods run after, on the interpreter's thread,
 * where [drain] is called (every so many calls, gc.collect()). A `__del__` gets an object of the class with the
 * instance's dict (the instance itself is gone); one whose dict holds itself is never collected.
 */
object PyWeakrefs {
    /** The JVM's weak reference of a weakref.ref or proxy ([owner]), which the context's queue gets. */
    class Ref(@JvmField val owner: PyObject, referent: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(referent, queue)

    /** An instance of a class with `__del__`, followed: its class and dict, for the call after it is gone. */
    class Del(o: PyObject, queue: ReferenceQueue<Any>) : WeakReference<Any>(o, queue) {
        @JvmField val type: PyType = o.type
        @JvmField val dict: PyDict = o.dict ?: PyDict().also { o.dict = it }
    }

    /** gc.collect()'s mark: once the queue has it, the collection it ran is in the queue. */
    private class Mark(o: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(o, queue)

    @JvmField val REF = PyTypes.newBuiltin("ReferenceType").also { it.module = "weakref"; it.dispatchBuiltins = true }
    @JvmField val PROXY = PyTypes.newBuiltin("ProxyType", subclassable = false).also { it.module = "weakref"; it.dispatchBuiltins = true }
    @JvmField val CALLABLE_PROXY = PyTypes.newBuiltin("CallableProxyType", subclassable = false).also { it.module = "weakref"; it.dispatchBuiltins = true }

    /** Whether CPython takes a weak reference to [v]: an instance of a class (not of object, not a builtin exception's), a function, a class, a set, a generator, a module. */
    @JvmStatic
    fun weakrefable(v: Any?): Boolean = when (v) {
        is PyBaseException -> v.type.heap && v.type.hasWeakref
        is PyObject -> v.type !== PyTypes.OBJECT && v.type.hasWeakref
        // (a class over a value of items, int's, bytes's, tuple's, takes none: CPython's layout has no room for them)
        is PyValue -> v.sub?.cls?.let { PySlots.weakrefable(it) } ?: (v is PySet)
        is PyFunction, is PyBuiltin, is PyType, is PyMethod, is PyGenBase, is PyModule, is PyCode -> true
        else -> false
    }

    private fun check(v: Any?) {
        if (!weakrefable(v)) throw PyRt.typeError("cannot create weak reference to '${PyTypes.name(v)}' object")
    }

    /** [v]'s first weak reference still alive (its `__weakref__`), or null. */
    @JvmStatic
    fun firstRef(v: Any?): Any? = refsOf(PyContext.current(), v).firstOrNull()

    /** The context's weak references to [v] still alive: their objects (getweakrefs). */
    private fun refsOf(ctx: PyContext, v: Any?): List<PyObject> =
        ctx.weakrefs[IdKey(v)]?.mapNotNull { it.get() }?.filter { (it as? PyWeakRef)?.ref?.get() === v || (it as? PyWeakProxy)?.ref?.get() === v } ?: emptyList()

    /** An object as a key by its identity, held weakly (the registry of weak references keeps no referent alive). */
    class IdKey(v: Any?) {
        private val ref = WeakReference(v)
        private val h = System.identityHashCode(v)
        val dead: Boolean get() = ref.get() == null
        override fun hashCode() = h
        override fun equals(other: Any?) = other is IdKey && (other === this || ref.get().let { it != null && it === other.ref.get() })
    }

    private fun note(ctx: PyContext, v: Any, r: PyObject) {
        val k = IdKey(v)
        val list = ctx.weakrefs.getOrPut(k) { ArrayList() }
        list.removeAll { it.get() == null }
        list.add(WeakReference(r))
        // (the entries of referents gone, now and then)
        if (++ctx.weakrefNotes % 1024 == 0) ctx.weakrefs.keys.removeAll { it.dead }
    }

    /** ref(ob, callback=None) of class [cls]; without a callback, the plain ref there is one of ([cls] ref itself). */
    @JvmStatic
    fun newRef(ctx: PyContext, cls: PyType, ob: Any?, callback: Any?): PyWeakRef {
        check(ob)
        val cb = callback?.takeIf { it !== PyNone }
        if (cb == null && cls === REF) refsOf(ctx, ob).firstOrNull { it is PyWeakRef && it.type === REF && it.callback == null }?.let { return it as PyWeakRef }
        return PyWeakRef(cls, ob!!, cb, ctx.refQueue).also { note(ctx, ob, it) }
    }

    @JvmStatic
    fun newProxy(ctx: PyContext, ob: Any?, callback: Any?): PyWeakProxy {
        check(ob)
        val cb = callback?.takeIf { it !== PyNone }
        val t = if (PyCalls.isCallable(ob)) CALLABLE_PROXY else PROXY
        if (cb == null) refsOf(ctx, ob).firstOrNull { it is PyWeakProxy && it.callback == null }?.let { return it as PyWeakProxy }
        return PyWeakProxy(t, ob!!, cb, ctx.refQueue).also { note(ctx, ob, it) }
    }

    /** An instance of [cls] just made, followed for its `__del__` where its class has one. */
    @JvmStatic
    fun made(o: PyObject) {
        if (PyDunder.userMethod(o.type, "__del__") == null) return
        val ctx = PyContext.current()
        ctx.finalizers.add(Del(o, ctx.refQueue))
    }

    /** The callbacks and `__del__` methods of what the collector found gone: run now (not while they already run). */
    @JvmStatic
    fun drain(ctx: PyContext): Int {
        if (ctx.draining) return 0
        ctx.draining = true
        var n = 0
        try {
            while (true) {
                val r = ctx.refQueue.poll() ?: break
                n += run(ctx, r)
            }
        } finally {
            ctx.draining = false
        }
        return n
    }

    private fun run(ctx: PyContext, r: java.lang.ref.Reference<*>): Int {
        when (r) {
            is Ref -> {
                val cb = (r.owner as? PyWeakRef)?.callback ?: (r.owner as? PyWeakProxy)?.callback ?: return 1
                unraisable(ctx, cb) { PyCalls.callWith(ctx, cb, arrayOf(r.owner), arrayOfNulls(0), arrayOfNulls(0)) }
            }
            is Del -> {
                ctx.finalizers.remove(r)
                val o = PyObject(r.type).also { it.dict = r.dict }
                val f = PyDunder.userMethod(r.type, "__del__") ?: return 1
                unraisable(ctx, f) { PyCalls.callWith(ctx, f, arrayOf(o), arrayOfNulls(0), arrayOfNulls(0)) }
            }
            else -> {}
        }
        return 1
    }

    /** [body], its exception printed as CPython's unraisable hook prints it (to stderr), not raised. */
    private fun unraisable(ctx: PyContext, what: Any?, body: () -> Unit) {
        try {
            body()
        } catch (e: PyException) {
            ctx.err.append("Exception ignored while calling deallocator ").append(PyRt.repr(what)).append(":\n").append(ctx.formatException(e))
        }
    }

    /** gc.collect(): the JVM asked to collect, then what it found gone run ([drain]); how many. */
    @JvmStatic
    fun collect(ctx: PyContext): Long {
        var n = 0
        repeat(2) {
            val mark = Mark(Any(), ctx.refQueue)
            System.gc()
            val deadline = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < deadline) {
                val r = ctx.refQueue.remove(20) ?: continue
                if (r === mark) break
                if (r !is Mark) n += run(ctx, r)
            }
            n += drain(ctx)
        }
        return n.toLong()
    }

    // ------------------------------------------------------------------ the module

    private fun referent(v: Any?): Any? = when (v) {
        is PyWeakRef -> v.ref.get() ?: PyNone
        else -> PyNone
    }

    @JvmStatic
    fun define() {
        val noArgs = arrayOfNulls<Any?>(0)
        REF.def("__new__", PyBuiltin("__new__", { ctx, a ->
            newRef(ctx, a[0] as PyType, a.getOrNull(1) ?: throw PyRt.typeError("__new__ expected at least 1 argument, got 0"), a.getOrNull(2))
        }, { ctx, a, kw -> newRef(ctx, a[0] as PyType, a.getOrNull(1), a.getOrNull(2) ?: kw[PyStr("callback")]) }))
        REF.def("__init__", PyBuiltin("__init__", { _, _ -> PyNone }, { _, _, _ -> PyNone }))
        REF.def("__call__", PyBuiltin("__call__") { _, a -> referent(a[0]) })
        REF.def("__callback__", PyProperty(PyBuiltin("__callback__") { _, a -> (a[0] as PyWeakRef).callback ?: PyNone }, null, null, null).also { it.name = "__callback__" })
        REF.def("__hash__", PyBuiltin("__hash__") { _, a ->
            val r = a[0] as PyWeakRef
            r.hash ?: run {
                val v = r.ref.get() ?: throw PyRt.typeError("weak object has gone away")
                PyDunder.hashValue(v).also { r.hash = it }
            }
        })
        REF.def("__eq__", PyBuiltin("__eq__") { _, a ->
            val x = a[0] as PyWeakRef
            val y = a[1] as? PyWeakRef ?: return@PyBuiltin PyNotImplemented
            val vx = x.ref.get()
            val vy = y.ref.get()
            PyBool.of(if (vx == null || vy == null) x === y else PyRt.eq(vx, vy))
        })
        REF.def("__ne__", PyBuiltin("__ne__") { _, a ->
            val x = a[0] as PyWeakRef
            val y = a[1] as? PyWeakRef ?: return@PyBuiltin PyNotImplemented
            val vx = x.ref.get()
            val vy = y.ref.get()
            PyBool.of(if (vx == null || vy == null) x !== y else !PyRt.eq(vx, vy))
        })
        REF.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val r = a[0] as PyWeakRef
            val v = r.ref.get()
            val at = "<weakref at 0x${Integer.toHexString(System.identityHashCode(r))}; "
            PyStr(if (v == null) at + "dead>" else at + "to '${PyTypes.fullName(PyTypes.typeOf(v))}' at 0x${Integer.toHexString(System.identityHashCode(v))}>")
        })
        REF.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
        // a proxy: its referent's attributes (PyClasses) and operations (these, which dispatch takes: dispatchBuiltins)
        for (t in arrayOf(PROXY, CALLABLE_PROXY)) {
            fun def(n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
            fun v(x: Any?): Any? = if (x is PyWeakProxy) x.get() else x
            for (op in listOf(PyOp.ADD, PyOp.SUB, PyOp.MUL, PyOp.TRUEDIV, PyOp.FLOORDIV, PyOp.MOD, PyOp.POW, PyOp.LSHIFT, PyOp.RSHIFT,
                    PyOp.BITAND, PyOp.BITOR, PyOp.BITXOR, PyOp.MATMUL)) {
                def(PyDunder.opName(op)) { a -> PyDunder.byCode(op, v(a[0]), v(a[1])) }
                def(PyDunder.reflectedName(op)) { a -> PyDunder.byCode(op, v(a[1]), v(a[0])) }
            }
            for (op in PyOp.LT..PyOp.NE) def(PyDunder.compareName(op)) { a -> PyRt.compareValue(op, v(a[0]), v(a[1])) }
            def("__neg__") { a -> PyRt.neg(v(a[0])) }
            def("__pos__") { a -> PyRt.pos(v(a[0])) }
            def("__invert__") { a -> PyRt.invert(v(a[0])) }
            def("__abs__") { a -> PyCalls.callWith(PyContext.current(), PyContext.current().builtins[PyStr("abs")], arrayOf(v(a[0])), noArgs, noArgs) }
            def("__bool__") { a -> PyBool.of(PyRt.truthy(v(a[0]))) }
            def("__len__") { a -> PyRt.len(v(a[0])) }
            def("__iter__") { a -> PyRt.iter(v(a[0])) }
            def("__next__") { a -> PyCalls.callWith(PyContext.current(), PyRt.getAttr(v(a[0]), "__next__"), noArgs, noArgs, noArgs) }
            def("__getitem__") { a -> PyRt.getItem(v(a[0]), a[1]) }
            def("__setitem__") { a -> PyRt.setItem(v(a[0]), a[1], a[2]); PyNone }
            def("__delitem__") { a -> PyCalls.deleteSubscr(v(a[0]), a[1]); PyNone }
            def("__contains__") { a -> PyBool.of(PyRt.contains(v(a[0]), a[1])) }
            def("__str__") { a -> PyStr(PyRt.str(v(a[0]))) }
            def("__repr__") { a ->
                val p = a[0] as PyWeakProxy
                val r = p.ref.get()
                PyStr("<weakproxy at 0x${Integer.toHexString(System.identityHashCode(p))}; " +
                    if (r == null) "dead>" else "to '${PyTypes.fullName(PyTypes.typeOf(r))}' at 0x${Integer.toHexString(System.identityHashCode(r))}>")
            }
            t.def("__hash__", PyNone)
        }
        CALLABLE_PROXY.def("__call__", PyBuiltin("__call__", { ctx, a -> PyCalls.callWith(ctx, (a[0] as PyWeakProxy).get(), a.copyOfRange(1, a.size), noArgs, noArgs) },
            { ctx, a, kw -> PyCalls.callWith(ctx, (a[0] as PyWeakProxy).get(), a.copyOfRange(1, a.size), kw.map.keys.map { it.v }.toTypedArray(), kw.map.values.toTypedArray()) }))
    }

    /** `_weakref`: ref, proxy, getweakrefcount, getweakrefs, the types. */
    @JvmStatic
    fun fillWeakref(ctx: PyContext, d: PyDict) {
        d[PyStr("ref")] = REF
        d[PyStr("ReferenceType")] = REF
        d[PyStr("ProxyType")] = PROXY
        d[PyStr("CallableProxyType")] = CALLABLE_PROXY
        d[PyStr("proxy")] = PyBuiltin("proxy") { c, a -> newProxy(c, a.getOrNull(0), a.getOrNull(1)) }
        d[PyStr("getweakrefcount")] = PyBuiltin("getweakrefcount") { c, a -> refsOf(c, a.getOrNull(0)).size.toLong() }
        d[PyStr("getweakrefs")] = PyBuiltin("getweakrefs") { c, a -> PyList(ArrayList<Any?>(refsOf(c, a.getOrNull(0)))) }
        d[PyStr("_remove_dead_weakref")] = PyBuiltin("_remove_dead_weakref") { _, a ->
            val dict = a[0] as PyDict
            val v = dict[a[1]]
            if (v is PyWeakRef && v.ref.get() == null) dict.map.remove(PyDict.Key(a[1]))
            PyNone
        }
    }

    /** `gc`: collect() as [collect]; the rest as CPython's names, for code that asks (the JVM's collector is always on). */
    @JvmStatic
    fun fillGc(ctx: PyContext, d: PyDict) {
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n) { _, a -> impl(a) }
        }
        def("collect") { _ -> collect(ctx) }
        def("enable") { _ -> ctx.gcEnabled = true; PyNone }
        def("disable") { _ -> ctx.gcEnabled = false; PyNone }
        def("isenabled") { _ -> PyBool.of(ctx.gcEnabled) }
        def("get_count") { _ -> PyTuple(arrayOf(0L, 0L, 0L)) }
        def("get_threshold") { _ -> PyTuple(arrayOf(2000L, 10L, 0L)) }
        def("set_threshold") { _ -> PyNone }
        def("get_objects") { _ -> PyList() }
        def("get_referrers") { _ -> PyList() }
        def("get_referents") { _ -> PyList() }
        def("is_tracked") { a -> PyBool.of(a.getOrNull(0) is PyObject || a.getOrNull(0) is PyValue) }
        def("is_finalized") { _ -> PyBool.FALSE }
        def("freeze") { _ -> PyNone }
        def("unfreeze") { _ -> PyNone }
        def("get_freeze_count") { _ -> 0L }
        d[PyStr("garbage")] = PyList()
        d[PyStr("callbacks")] = PyList()
        d[PyStr("DEBUG_STATS")] = 1L
        d[PyStr("DEBUG_COLLECTABLE")] = 2L
        d[PyStr("DEBUG_UNCOLLECTABLE")] = 4L
        d[PyStr("DEBUG_SAVEALL")] = 32L
        d[PyStr("DEBUG_LEAK")] = 38L
    }
}
