package dev.mooner.neonjs

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.interop.ContextGate
import dev.mooner.neonjs.interop.HostBridge
import dev.mooner.neonjs.interop.HostClassObject
import dev.mooner.neonjs.interop.HostObject
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Evaluator
import dev.mooner.neonjs.vm.HostException
import dev.mooner.neonjs.vm.Interpreter
import dev.mooner.neonjs.vm.Frame
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** A host function callable from JS (Java-friendly SAM). */
fun interface HostFunction {
    fun call(args: Array<NeonValue>): Any?
}

/**
 * An isolated JS execution context: one realm (global object + intrinsics), one job queue and its own sandbox
 * limits. Contexts are single-threaded: concurrent use from several threads is serialized by an internal lock.
 */
class NeonContext internal constructor(val engine: NeonEngine) : AutoCloseable, ContextGate {
    internal val agent: Agent
    internal val realm: Realm
    internal val bridge: HostBridge
    private val lock = ReentrantLock()
    private var depth = 0
    @Volatile private var closed = false

    init {
        val p = engine.sandbox
        val cfg = RuntimeConfig()
        cfg.maxCallDepth = p.maxCallDepth
        cfg.maxExecutionMillis = p.maxExecutionMillis
        cfg.maxStatements = p.maxStatements
        cfg.maxStringLength = p.maxStringLength
        cfg.maxAllocatedBytes = p.maxAllocatedBytes
        cfg.limitsPerTask = p.limitsPerTask
        cfg.allowCodeGeneration = p.allowEval
        cfg.timeZone = p.timeZone
        cfg.defaultLocale = p.defaultLocale
        cfg.intl = engine.intl
        cfg.codeDefiner = engine.codeDefiner
        cfg.executionMode = engine.executionMode.ordinal
        cfg.jitThreshold = engine.jitThreshold
        cfg.backgroundJit = engine.backgroundCompilation
        agent = Agent(cfg)
        p.randomSeed?.let { agent.randomSource = java.util.Random(it) }
        p.fixedTimeMillis?.let { t -> agent.clock = { t.toDouble() } }
        var r: Realm? = null
        var br: HostBridge? = null
        agent.enter {
            val realm = Realm(agent)
            Builtins.install(realm)
            r = realm
            br = HostBridge(realm, engine.hostAccess, this)
            realm.hostData = this
            agent.hostExceptionHandler = { e, rr -> hostErrorToJS(e, rr) }
            if (p.exposeJavaGlobal) realm.enter { installJavaGlobal(realm, br) }
            engine.console?.let { c -> realm.enter { dev.mooner.neonjs.ext.ConsoleBuiltins.install(realm, c) } }
            if (engine.webGlobals) realm.enter { dev.mooner.neonjs.ext.WebGlobals.install(realm, p.maxTimers) }
        }
        realm = r!!
        bridge = br!!
    }

    // ------------------------------------------------------------------ entering

    override fun <R> enter(block: () -> R): R {
        check(!closed) { "context is closed" }
        acquire()
        try {
            return agent.enter {
                realm.enter {
                    val top = depth == 0
                    if (top) agent.startLimits()
                    depth++
                    try {
                        val r = block()
                        if (top && engine.autoRunJobs) agent.runJobs()
                        r
                    } finally {
                        depth--
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Takes the context lock. A thread waits at most the sandbox time limit for another thread's use of the context,
     * so threads waiting on each other (JS blocking on host work that needs the same context) end with a
     * [NeonTimeoutException] instead of a deadlock. Without a time limit the wait is unbounded.
     */
    private fun acquire() {
        val limit = engine.sandbox.maxExecutionMillis
        if (limit <= 0 || lock.isHeldByCurrentThread) {
            lock.lock()
            return
        }
        val acquired = try {
            lock.tryLock(limit, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NeonInterruptedException("interrupted while waiting for the context")
        }
        if (!acquired) throw NeonTimeoutException("context busy on another thread for longer than the time limit ($limit ms)")
    }

    /** Runs [block] translating engine exceptions into [NeonException]s. */
    private inline fun <R> guarded(crossinline block: () -> R): R {
        try {
            return enter { block() }
        } catch (e: JSException) {
            throw toHostException(e)
        } catch (e: ExecutionTimeoutException) {
            throw NeonTimeoutException(e.message ?: "timeout")
        } catch (e: ResourceLimitException) {
            throw NeonResourceLimitException(e.message ?: "resource limit")
        } catch (e: InterruptedExecutionException) {
            throw NeonInterruptedException(e.message ?: "interrupted")
        } catch (e: HostException) {
            throw NeonException("Host exception: ${e.hostCause}", e.hostCause)
        } catch (e: StackOverflowError) {
            throw NeonException("Maximum call stack size exceeded", e)
        }
    }

    internal fun toHostException(e: JSException): NeonException {
        val v = e.value
        val hostCause = (v as? JSObject)?.let { hostCauseOf(it) }
        val ex = if (v is JSErrorObject && isSyntaxError(v)) NeonSyntaxException(e.describe(), 0, 0, null)
        else NeonException(e.describe(), hostCause)
        ex.guestValue = NeonValue(this, v)
        ex.jsStack = e.jsStack
        return ex
    }

    private fun isSyntaxError(v: JSErrorObject): Boolean = v.proto === realm.errorPrototypes[ErrorKind.SYNTAX]

    private val hostCauseKey = JSSymbol("hostCause")

    private fun hostCauseOf(o: JSObject): Throwable? {
        val pm = o.props ?: return null
        val i = pm.find(hostCauseKey)
        if (i < 0) return null
        return ((pm.values[i] as? HostObject)?.target as? Throwable)
    }

    private fun hostErrorToJS(e: HostException, r: Realm): Any? {
        val cause = e.hostCause
        val err = r.newError(ErrorKind.ERROR, cause.message ?: cause.javaClass.simpleName)
        err.defineOwn("name", "HostError", Attr.WC)
        err.defineOwn(hostCauseKey, HostObject(bridge, cause, dev.mooner.neonjs.interop.HostClassInfo.of(Any::class.java, HostAccess.NONE)), Attr.NONE)
        return err
    }

    // ------------------------------------------------------------------ evaluation

    /** Evaluates script source code in the global scope. */
    fun eval(code: String, name: String = "<eval>"): NeonValue = guarded {
        NeonValue(this, Evaluator.evaluateScript(realm, code, name))
    }

    /** Runs a pre-compiled script. */
    fun eval(script: NeonScript): NeonValue {
        require(script.engine === engine) { "script was compiled by another engine" }
        return guarded { NeonValue(this, Evaluator.runScript(realm, script.code)) }
    }

    /** The global object. */
    val global: NeonValue get() = NeonValue(this, realm.globalObject)

    private var userLoader: NeonModuleLoader? = null
    private val hostModules = HashMap<String, Map<String, Any?>>()

    /** Installs the loader used for `import` declarations and `import()`. Without one, only host modules resolve. */
    fun setModuleLoader(loader: NeonModuleLoader) {
        userLoader = loader
        installLoader()
    }

    /**
     * Defines an ES module that scripts can import by [specifier] (e.g. `"host:fs"`), with the given named exports
     * (converted to JS; use the key `"default"` for a default export). Host modules take precedence over the loader.
     */
    fun defineModule(specifier: String, exports: Map<String, Any?>) = guarded {
        hostModules[specifier] = exports
        installLoader()
    }

    private fun installLoader() {
        dev.mooner.neonjs.vm.Modules.setLoader(realm, object : dev.mooner.neonjs.vm.ModuleLoader {
            override fun resolve(specifier: String, referrerKey: String?): String {
                if (specifier in hostModules) return specifier
                val l = userLoader ?: throw JSException.typeError("Cannot find module '$specifier'")
                return loaderCall(specifier) { l.resolve(specifier, referrerKey) }
            }
            override fun load(key: String, request: dev.mooner.neonjs.compiler.ModuleRequest): dev.mooner.neonjs.vm.ModuleSource {
                hostModules[key]?.let { ex ->
                    val src = dev.mooner.neonjs.vm.ModuleSource(key, "")
                    src.hostExports = ex.mapValues { bridge.toJS(it.value) }
                    src.hostRealm = realm
                    return src
                }
                if (request.type == "bytes") {
                    val bytes = loaderCall(request.specifier) { userLoader?.loadBytes(key) } ?: throw JSException.typeError("Cannot find module '${request.specifier}'")
                    return dev.mooner.neonjs.vm.ModuleSource(key, "").also { it.bytes = bytes }
                }
                val text = loaderCall(request.specifier) { userLoader?.load(key) } ?: throw JSException.typeError("Cannot find module '${request.specifier}'")
                return dev.mooner.neonjs.vm.ModuleSource(key, text)
            }
            override fun importMetaProperties(key: String): Map<String, Any?> = mapOf("url" to key)
        })
    }

    /** Host loader failures become JS TypeErrors (so `import()` rejects instead of a host exception escaping). */
    private inline fun <T> loaderCall(specifier: String, block: () -> T): T = try {
        block()
    } catch (e: JSException) {
        throw e
    } catch (e: TerminationException) {
        throw e
    } catch (e: RuntimeException) {
        throw JSException.typeError("Cannot load module '$specifier': ${e.message}")
    } catch (e: java.io.IOException) {
        throw JSException.typeError("Cannot load module '$specifier': ${e.message}")
    }

    /**
     * Evaluates [code] as an ES module named [name] (its key for resolving relative imports). Returns the module
     * namespace object once evaluation (including top-level await) has completed. Top-level await on host futures
     * waits for them up to [timeoutMillis] (and the sandbox's time limit); a module still pending after that, or
     * waiting on nothing that can settle it, is an error.
     */
    @JvmOverloads
    fun evalModule(code: String, name: String, timeoutMillis: Long = Long.MAX_VALUE): NeonValue = guarded {
        val (rec, p) = dev.mooner.neonjs.vm.Modules.runModule(realm, dev.mooner.neonjs.vm.ModuleSource(name, code))
        pumpUntil(timeoutMillis) { p.state != dev.mooner.neonjs.vm.JSPromise.PENDING }
        when (p.state) {
            dev.mooner.neonjs.vm.JSPromise.REJECTED -> throw JSException(p.result)
            dev.mooner.neonjs.vm.JSPromise.PENDING -> throw NeonException("module evaluation did not complete (pending top-level await)")
            else -> NeonValue(this, dev.mooner.neonjs.vm.Modules.namespaceOf(rec))
        }
    }

    /** Defines (or replaces) a global binding with a host value (converted to JS). */
    operator fun set(name: String, value: Any?) = guarded {
        realm.globalObject.defineOwnProperty(name, PropertyDescriptor.data(bridge.toJS(value), Attr.ALL))
        Unit
    }

    operator fun get(name: String): NeonValue = guarded {
        val g = realm.globalObject
        NeonValue(this, g.get(name, g))
    }

    /** Defines a global function implemented by the host. */
    fun setFunction(name: String, fn: HostFunction) {
        set(name, createFunction(name, fn))
    }

    /** Creates a JS function object backed by a host function (not bound to a global name). */
    fun createFunction(name: String, fn: HostFunction): NeonValue = guarded {
        val f = NativeFunction(realm, name, 0, { _, _, args, _ ->
            val wrapped = Array(args.size) { NeonValue(this, args[it]) }
            try {
                bridge.toJS(fn.call(wrapped))
            } catch (e: NeonException) {
                val gv = e.guestValue
                if (gv != null && gv.context === this) throw JSException(gv.raw) else throw HostException(e)
            } catch (e: JSException) {
                throw e
            } catch (e: TerminationException) {
                throw e
            } catch (e: RuntimeException) {
                throw HostException(e)
            }
        })
        NeonValue(this, f)
    }

    /** Exposes a host class as a global constructor / static namespace (subject to [HostAccess]). */
    fun exposeClass(name: String, cls: Class<*>) = guarded {
        if (!engine.hostAccess.isClassAccessible(cls)) throw NeonException("Host access policy does not allow class ${cls.name}")
        realm.globalObject.defineOwn(name, bridge.classObject(cls), Attr.WC)
    }

    /** Converts a host value into a JS value of this context. */
    fun asValue(host: Any?): NeonValue = guarded { NeonValue(this, bridge.toJS(host)) }

    /** Runs pending Promise jobs (microtasks). */
    fun runJobs() = guarded { agent.runJobs() }

    /**
     * Runs pending jobs and waits for external events — completions of host futures handed to JS (they appear as
     * promises), Atomics.waitAsync wake-ups — until nothing is pending. Returns false if work was still pending after
     * [timeoutMillis]. Waiting counts against the sandbox's execution-time limit (unless [SandboxPolicy.limitsPerTask])
     * and can be stopped with [interrupt]. While it waits, other threads can use the context.
     */
    fun runEventLoop(timeoutMillis: Long = Long.MAX_VALUE): Boolean = guarded { pumpUntil(timeoutMillis, null) }

    /**
     * Runs jobs, waiting for external events while any are pending, until [done] holds (true), nothing more is
     * pending (true without [done], else false) or [timeoutMillis] elapses (false). Call inside [enter].
     */
    internal fun pumpUntil(timeoutMillis: Long, done: (() -> Boolean)?): Boolean {
        val start = System.nanoTime()
        while (true) {
            agent.runJobs()
            if (done != null && done()) return true
            if (!agent.hasPendingExternal()) return done == null
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed >= timeoutMillis) return false
            // a wait inside JS (a host function awaiting a promise) keeps the context: its frames are live
            if (depth == 1 && lock.holdCount == 1 && agent.topFrame == null) awaitReleased(timeoutMillis - elapsed)
            else agent.awaitExternal(timeoutMillis - elapsed)
        }
    }

    /**
     * [Agent.awaitExternal] without holding the context, so other threads can evaluate code and call into it
     * meanwhile (and run the tasks that arrive, as their calls run jobs). Their evaluations start their own limits;
     * this thread's are restored afterwards, and an interrupt requested meanwhile ends the wait even if their start
     * cleared it. Called at the top of [enter] only.
     */
    private fun awaitReleased(timeoutMillis: Long) {
        if (agent.interruptRequested) throw InterruptedExecutionException("Execution interrupted")
        val limits = agent.saveLimits()
        // other threads' enter / exit restore the current realm in their own order: an exit may clear it under this one
        // (JS calls set it again, but host code between tasks reads it)
        val realm = agent.currentRealm
        val interrupts = agent.interruptCount
        val deadline = if (agent.config.limitsPerTask) 0L else agent.deadlineNanos
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        depth = 0
        lock.unlock()
        try {
            // another thread may run the last tasks meanwhile: then nothing will be posted
            while (!closed && agent.interruptCount == interrupts && agent.hasPendingExternal()) {
                // differences, not comparisons: end overflows for an unbounded timeout
                val now = System.nanoTime()
                var wait = end - now
                if (deadline != 0L) wait = minOf(wait, deadline - now)
                if (wait <= 0) break
                if (agent.waitForExternal(minOf(wait, Agent.WAIT_SLICE_NANOS))) break
            }
        } finally {
            // not acquire(): its time limit is for threads that may wait on each other, and this one holds nothing
            lock.lock()
            depth = 1
            agent.currentRealm = realm
            agent.restoreLimits(limits)
        }
        if (agent.interruptCount != interrupts) throw InterruptedExecutionException("Execution interrupted")
        if (deadline != 0L) agent.checkWaitLimits()
    }

    /**
     * Requests termination of the currently running evaluation and of an event loop waiting in the context (callable
     * from any thread).
     */
    fun interrupt() {
        agent.requestInterrupt()
    }

    override fun close() {
        closed = true
        // withdraw pending Atomics.waitAsync waiters (unlink them, cancel their timers) so nothing outlives the context
        agent.closeExternal()
        // and code still waiting for the background compiler
        dev.mooner.neonjs.jit.JitQueue.cancel(agent)
    }

    // ------------------------------------------------------------------ ContextGate

    override fun wrapValue(v: Any?): Any = NeonValue(this, v)
    override fun unwrapValue(v: Any?): Any? = if (v is NeonValue && v.context === this) v.raw else NotFound
    override val valueClass: Class<*> get() = NeonValue::class.java
    override fun rejectionToHost(reason: Any?): Throwable = toHostException(JSException(reason))

    internal fun <R> call(block: () -> R): R = guarded { block() }

    // ------------------------------------------------------------------ Java global

    private fun installJavaGlobal(realm: Realm, bridge: HostBridge) {
        val javaObj = JSObject(realm.objectPrototype)
        javaObj.defineOwn("type", NativeFunction(realm, "type", 1, { _, _, args, _ ->
            val n = Ops.toString(args.arg(0))
            val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val cached = typeCache[n]
            if (cached != null && cached.first === loader) cached.second
            else bridge.classObject(lookupClass(n, loader)).also { typeCache[n] = loader to it }
        }), Attr.WC)
        javaObj.defineOwn("from", NativeFunction(realm, "from", 1, { f, _, args, _ ->
            val v = args.arg(0) as? HostObject ?: throw JSException.typeError("Java.from requires a Java array or collection")
            val t = v.target
            val out = ArrayList<Any?>()
            fun add(x: Any?) {
                if (out.size >= HostBridge.MAX_ARRAY_LENGTH) throw JSException.rangeError("Java.from: collection too large")
                out.add(bridge.toJS(x))
                if (out.size and 1023 == 0) f.realm.agent.checkInterrupt()
            }
            when {
                t is Iterable<*> -> for (x in t) add(x)
                t.javaClass.isArray -> for (i in 0 until java.lang.reflect.Array.getLength(t)) add(java.lang.reflect.Array.get(t, i))
                else -> throw JSException.typeError("Java.from requires a Java array or collection")
            }
            JSArray.of(f.realm, out)
        }), Attr.WC)
        javaObj.defineOwn("to", NativeFunction(realm, "to", 2, { _, _, args, _ ->
            // Java.to(jsArray, "int[]" | Java.type(...)) -> Java array or List
            val t: Class<*> = when (val ta = args.arg(1)) {
                is HostClassObject -> ta.cls
                Undefined -> Array<Any>::class.java
                else -> lookupClass(Ops.toString(ta))
            }
            bridge.toJS(bridge.convertTo(args.arg(0), t))
        }), Attr.WC)
        javaObj.defineOwn("extend", NativeFunction(realm, "extend", 1, { _, _, args, _ ->
            // Java.extend(Type..., [impl]): a subclass / implementation whose methods are JS functions
            if (!engine.hostAccess.allowImplementations) throw JSException.typeError("Host access policy does not allow Java.extend")
            val last = args.lastOrNull()
            val classImpl = if (last is JSObject && last !is HostClassObject) last else null
            val typeArgs = if (classImpl != null) args.copyOfRange(0, args.size - 1) else args
            if (typeArgs.isEmpty()) throw JSException.typeError("Java.extend requires at least one Java type")
            val types = typeArgs.map {
                val c = (it as? HostClassObject)?.cls ?: throw JSException.typeError("Java.extend: ${Ops.describe(it)} is not a Java type")
                if (!engine.hostAccess.isClassAccessible(c) || c.isArray || c.isPrimitive) throw JSException.typeError("Java.extend: ${c.name} cannot be extended")
                c
            }
            val adapter = adapterCache.getOrPut(types) { dev.mooner.neonjs.interop.Adapters.generate(types, agent.config.codeDefiner ?: dev.mooner.neonjs.jit.CodeDefiners.default) }
            dev.mooner.neonjs.interop.AdapterClassObject(bridge, adapter, classImpl, types.joinToString("+") { it.simpleName })
        }), Attr.WC)
        javaObj.defineOwn("super", NativeFunction(realm, "super", 1, { _, _, args, _ ->
            val o = args.arg(0) as? HostObject ?: throw JSException.typeError("Java.super requires a Java adapter instance")
            val entry = adapterCache.values.firstOrNull { it.cls.isInstance(o.target) }
                ?: throw JSException.typeError("Java.super requires an instance of a Java.extend adapter")
            dev.mooner.neonjs.interop.AdapterClassObject(bridge, entry, null, entry.cls.simpleName).superOf(o)
        }), Attr.WC)
        javaObj.defineOwn("isJavaObject", NativeFunction(realm, "isJavaObject", 1, { _, _, args, _ -> args.arg(0) is HostObject }), Attr.WC)
        javaObj.defineOwn("isType", NativeFunction(realm, "isType", 1, { _, _, args, _ -> args.arg(0) is HostClassObject }), Attr.WC)
        realm.globalObject.defineOwn("Java", javaObj, Attr.WC)
    }

    /**
     * Java.type results by class name, with the class loader that resolved them: names resolve through the calling
     * thread's context class loader, which can differ between the threads using the context.
     */
    private val typeCache = HashMap<String, Pair<ClassLoader?, HostClassObject>>()

    /** Adapter classes generated by Java.extend in this context, by extended types. */
    private val adapterCache = HashMap<List<Class<*>>, dev.mooner.neonjs.interop.AdapterClass>()

    /** Resolves a class name for the Java global, applying the lookup filter and the class access policy. */
    private fun lookupClass(n: String, loader: ClassLoader? = Thread.currentThread().contextClassLoader ?: javaClass.classLoader): Class<*> {
        if (!engine.hostAccess.isLookupAllowed(n)) throw JSException.typeError("Access to host class $n is not allowed")
        val c = try {
            HostBridge.classForName(n, loader)
        } catch (_: ClassNotFoundException) {
            throw JSException.typeError("Unknown host class $n")
        } catch (_: LinkageError) {
            throw JSException.typeError("Cannot load host class $n")
        }
        if (!engine.hostAccess.isClassAccessible(c)) throw JSException.typeError("Access to host class $n is not allowed")
        return c
    }

    companion object {
        @Suppress("unused") private val keepImports = listOf(Interpreter::class, Frame::class)
    }
}
