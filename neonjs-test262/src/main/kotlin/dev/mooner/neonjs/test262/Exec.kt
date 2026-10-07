package dev.mooner.neonjs.test262

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.SharedDataBlock
import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.parser.JSSyntaxError
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Evaluator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Executes test262 tests on the engine. */
class Exec(val root: File, val timeoutMillis: Long, val mode: Int = 0) {
    private val harnessDir = File(root, "harness")
    private val harnessSrc = ConcurrentHashMap<String, String>()
    private val clockOrigin = System.nanoTime()

    private fun harness(name: String): String = harnessSrc.getOrPut(name) { File(harnessDir, name).readText() }

    class Outcome(val ok: Boolean, val message: String?)

    private inner class Host(val agent: Agent) {
        val printed = StringBuilder()
        var asyncDone = false
        var asyncFailure: String? = null
        private var group: AgentGroup? = null

        /** The agents started by this test (created on first use of $262.agent). */
        fun agents(): AgentGroup = group ?: AgentGroup().also { group = it }

        fun shutdownAgents() {
            group?.shutdown()
        }
    }

    /** Applies the per-test engine configuration to [agent]. */
    private fun configure(agent: Agent) {
        agent.config.maxExecutionMillis = timeoutMillis
        // deterministic Intl default locale (otherwise the host's locale leaks into intl402 results)
        agent.config.defaultLocale = "en-US"
        agent.config.executionMode = mode
        agent.config.jitThreshold = 2
        agent.maxDepth = 2500
    }

    /**
     * Multi-agent support for $262.agent (INTERPRETING.md): each started agent runs on its own thread with its own
     * Agent and Realm; a broadcast shares the SharedArrayBuffer's data block with every agent.
     */
    private inner class AgentGroup {
        private val reports = ConcurrentLinkedQueue<String>()
        private val workers = CopyOnWriteArrayList<Worker>()

        private inner class Worker {
            val mailbox = LinkedBlockingQueue<Message>()
            @Volatile var agent: Agent? = null
            @Volatile var thread: Thread? = null
            @Volatile var done = false
            /** Set by receiveBroadcast; only accessed on the worker thread. */
            var callback: JSObject? = null
        }

        private inner class Message(val block: SharedDataBlock, val num: Any?) {
            val receivedBy: MutableSet<Worker> = ConcurrentHashMap.newKeySet()
        }

        fun getReport(): Any? = reports.poll() ?: Null

        /** $262.agent.start: runs [src] in a new agent; returns once that agent is running. */
        fun start(src: String) {
            val w = Worker()
            val running = CountDownLatch(1)
            val t = Thread(null, { runWorker(w, src, running) }, "t262-agent", 64L * 1024 * 1024)
            t.isDaemon = true
            w.thread = t
            workers.add(w)
            t.start()
            running.await(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        private fun runWorker(w: Worker, src: String, running: CountDownLatch) {
            val agent = Agent()
            configure(agent)
            w.agent = agent
            try {
                agent.enter {
                    val realm = Realm(agent)
                    Builtins.install(realm)
                    installWorkerHost(realm, w)
                    realm.enter {
                        agent.startLimits()
                        running.countDown()
                        Evaluator.evaluateScript(realm, src, "agent.js")
                        agent.runJobs()
                        val cb = w.callback
                        if (cb != null) {
                            var msg: Message? = null
                            while (msg == null) {
                                msg = w.mailbox.poll(20, TimeUnit.MILLISECONDS)
                                agent.checkInterrupt()
                            }
                            val sab = JSArrayBuffer.wrapShared(realm, msg.block)
                            msg.receivedBy.add(w)
                            cb.call(Undefined, arrayOf(sab, msg.num))
                            agent.runJobs()
                        }
                        runEventLoop(agent, null)
                    }
                }
            } catch (_: TerminationException) {
                // stopped at the end of the test, or timed out
            } catch (_: InterruptedException) {
                // stopped at the end of the test
            } catch (e: Throwable) {
                reports.add("Test262:AgentError: ${describe(e)}")
            } finally {
                agent.closeExternal()
                running.countDown()
                w.done = true
            }
        }

        private fun installWorkerHost(realm: Realm, w: Worker) {
            val g = realm.globalObject
            val d = JSObject(realm.objectPrototype)
            val a = JSObject(realm.objectPrototype)
            a.defineOwn("receiveBroadcast", NativeFunction(realm, "receiveBroadcast", 1, { _, _, args, _ ->
                val cb = args.arg(0)
                if (!Ops.isCallable(cb)) throw JSException.typeError("receiveBroadcast requires a function")
                w.callback = cb as JSObject
                Undefined
            }), Attr.WC)
            a.defineOwn("report", NativeFunction(realm, "report", 1, { _, _, args, _ ->
                reports.add(Ops.toString(args.arg(0)))
                Undefined
            }), Attr.WC)
            a.defineOwn("leaving", NativeFunction(realm, "leaving", 0, { _, _, _, _ -> Undefined }), Attr.WC)
            installCommonAgentFunctions(realm, a)
            d.defineOwn("agent", a, Attr.WC)
            d.defineOwn("global", g, Attr.WC)
            g.defineOwn("$262", d, Attr.WC)
        }

        /** $262.agent.broadcast: hands the shared block to all running agents; blocks until each retrieved it. */
        fun broadcast(sab: Any?, num: Any?) {
            val block = JSArrayBuffer.sharedBlockOf(sab) ?: throw JSException.typeError("broadcast requires a SharedArrayBuffer")
            val msg = Message(block, if (num is JSObject) Ops.toNumber(num) else num)
            val targets = workers.filter { !it.done }
            for (w in targets) w.mailbox.add(msg)
            val agent = Agent.current()
            while (!targets.all { it.done || it in msg.receivedBy }) {
                sleepMillis(1)
                agent.checkInterrupt()
            }
        }

        /** Stops all agents of the test (interrupting waits and sleeps) and gives them a moment to finish. */
        fun shutdown() {
            for (w in workers) {
                w.agent?.interruptRequested = true
                w.thread?.interrupt()
            }
            for (w in workers) w.thread?.join(1000)
        }
    }

    /**
     * Keeps an agent's job loop alive while it expects external jobs (Atomics.waitAsync resolutions posted by other
     * agents or by timeouts): waits for them and runs the resulting jobs until [host] reports $DONE (main agent) or
     * nothing is pending. The wait honors interrupts and the execution deadline (Agent.awaitExternal).
     */
    private fun runEventLoop(agent: Agent, host: Host?) {
        while ((host == null || !host.asyncDone) && agent.hasPendingExternal()) {
            agent.awaitExternal(timeoutMillis)
            agent.runJobs()
        }
    }

    private fun sleepMillis(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedExecutionException("Execution interrupted")
        }
    }

    /** $262.agent.sleep and monotonicNow, available to the main agent and to started agents. */
    private fun installCommonAgentFunctions(realm: Realm, a: JSObject) {
        a.defineOwn("sleep", NativeFunction(realm, "sleep", 1, { _, _, args, _ ->
            val ms = Ops.toNumber(args.arg(0))
            if (ms > 0) sleepMillis(minOf(ms, timeoutMillis.toDouble()).toLong())
            Undefined
        }), Attr.WC)
        a.defineOwn("monotonicNow", NativeFunction(realm, "monotonicNow", 0, { _, _, _, _ ->
            (System.nanoTime() - clockOrigin) / 1e6
        }), Attr.WC)
    }

    private fun newRealm(agent: Agent, host: Host): Realm {
        val realm = Realm(agent)
        Builtins.install(realm)
        installHost(realm, host)
        return realm
    }

    private fun installHost(realm: Realm, host: Host) {
        realm.enter {
            val g = realm.globalObject
            val print = NativeFunction(realm, "print", 1, { _, _, args, _ ->
                val s = if (args.isEmpty()) "" else Ops.toString(args[0])
                host.printed.append(s).append('\n')
                if (s == "Test262:AsyncTestComplete") host.asyncDone = true
                if (s.startsWith("Test262:AsyncTestFailure")) {
                    host.asyncDone = true
                    host.asyncFailure = s
                }
                Undefined
            })
            g.defineOwn("print", print, Attr.WC)
            val d = JSObject(realm.objectPrototype)
            d.defineOwn("global", g, Attr.WC)
            d.defineOwn("createRealm", NativeFunction(realm, "createRealm", 0, { _, _, _, _ ->
                val r2 = newRealm(host.agent, host)
                r2.globalObject.get("$262")
            }), Attr.WC)
            d.defineOwn("evalScript", NativeFunction(realm, "evalScript", 1, { f, _, args, _ ->
                Evaluator.evaluateScript(f.realm, Ops.toString(args.arg(0)), "evalScript")
            }), Attr.WC)
            d.defineOwn("gc", NativeFunction(realm, "gc", 0, { _, _, _, _ -> System.gc(); Undefined }), Attr.WC)
            realm.intrinsics["%AbstractModuleSource%"]?.let { d.defineOwn("AbstractModuleSource", it, Attr.WC) }
            d.defineOwn("detachArrayBuffer", NativeFunction(realm, "detachArrayBuffer", 1, { f, _, args, _ ->
                val det = f.realm.intrinsics["%DetachArrayBuffer%"] ?: throw JSException.typeError("detachArrayBuffer unsupported")
                det.call(Undefined, arrayOf(args.arg(0)))
            }), Attr.WC)
            val dda = NativeFunction(realm, "IsHTMLDDA", 0, { _, _, _, _ -> Null })
            dda.special = dda.special or JSObject.HTMLDDA
            d.defineOwn("IsHTMLDDA", dda, Attr.WC)
            val agentObj = JSObject(realm.objectPrototype)
            agentObj.defineOwn("start", NativeFunction(realm, "start", 1, { _, _, args, _ ->
                host.agents().start(Ops.toString(args.arg(0)))
                Undefined
            }), Attr.WC)
            agentObj.defineOwn("broadcast", NativeFunction(realm, "broadcast", 2, { _, _, args, _ ->
                host.agents().broadcast(args.arg(0), args.arg(1))
                Undefined
            }), Attr.WC)
            agentObj.defineOwn("getReport", NativeFunction(realm, "getReport", 0, { _, _, _, _ -> host.agents().getReport() }), Attr.WC)
            installCommonAgentFunctions(realm, agentObj)
            d.defineOwn("agent", agentObj, Attr.WC)
            g.defineOwn("$262", d, Attr.WC)
        }
    }

    private class FileLoader(val baseDir: File) : dev.mooner.neonjs.vm.ModuleLoader {
        companion object {
            const val MODULE_SOURCE = "<module source>"
        }

        override fun resolve(specifier: String, referrerKey: String?): String {
            if (specifier == MODULE_SOURCE) return MODULE_SOURCE
            val dir = if (referrerKey != null && File(referrerKey).isAbsolute) File(referrerKey).parentFile else baseDir
            return File(dir, specifier).canonicalPath
        }

        override fun load(key: String, request: dev.mooner.neonjs.compiler.ModuleRequest): dev.mooner.neonjs.vm.ModuleSource {
            if (key == MODULE_SOURCE) {
                // INTERPRETING.md: a module with a valid module source (like a WebAssembly module)
                return dev.mooner.neonjs.vm.ModuleSource(key, "").apply {
                    hostExports = emptyMap()
                    sourceClassName = "Test262ModuleSource"
                }
            }
            val f = File(key)
            if (!f.isFile) throw JSException.typeError("Cannot find module '${request.specifier}'")
            if (request.type == "bytes") return dev.mooner.neonjs.vm.ModuleSource(key, "").also { it.bytes = f.readBytes() }
            return dev.mooner.neonjs.vm.ModuleSource(key, f.readText())
        }
    }

    fun run(meta: TestMeta, src: String, rel: String, strict: Boolean): Outcome {
        val agent = Agent()
        configure(agent)
        val host = Host(agent)
        try {
            return runIn(agent, host, meta, src, rel, strict)
        } finally {
            host.shutdownAgents()
            agent.closeExternal()
        }
    }

    private fun runIn(agent: Agent, host: Host, meta: TestMeta, src: String, rel: String, strict: Boolean): Outcome {
        return agent.enter {
            val realm = newRealm(agent, host)
            val testFile = File(File(root, "test"), rel)
            dev.mooner.neonjs.vm.Modules.setLoader(realm, FileLoader(testFile.parentFile))
            realm.enter {
                agent.startLimits()
                try {
                    if (!meta.isRaw) {
                        Evaluator.evaluateScript(realm, harness("assert.js"), "assert.js")
                        Evaluator.evaluateScript(realm, harness("sta.js"), "sta.js")
                        if (meta.isAsync) Evaluator.evaluateScript(realm, harness("doneprintHandle.js"), "doneprintHandle.js")
                        for (inc in meta.includes) Evaluator.evaluateScript(realm, harness(inc), inc)
                    }
                } catch (e: Throwable) {
                    return@enter Outcome(false, "harness error: ${describe(e)}")
                }
                if (meta.isModule) return@enter runModule(agent, realm, meta, src, testFile, host)
                val code = if (strict) "\"use strict\";\n$src" else src
                var thrown: Throwable? = null
                try {
                    val cb: CodeBlock = try {
                        Evaluator.compileScript(realm, code, rel)
                    } catch (e: JSException) {
                        // early (parse) error
                        if (meta.negativePhase == "parse") {
                            return@enter checkErrorType(e, meta.negativeType)
                        }
                        return@enter Outcome(false, "unexpected parse error: ${describe(e)}")
                    }
                    if (meta.negativePhase == "parse") return@enter Outcome(false, "expected parse-time ${meta.negativeType}")
                    Evaluator.runScript(realm, cb)
                    agent.runJobs()
                    if (meta.isAsync) runEventLoop(agent, host)
                } catch (e: Throwable) {
                    thrown = e
                }
                if (thrown != null) {
                    if (meta.negativePhase == "runtime" || meta.negativePhase == "resolution") return@enter checkErrorType(thrown, meta.negativeType)
                    return@enter Outcome(false, describe(thrown))
                }
                if (meta.negativePhase != null) return@enter Outcome(false, "expected ${meta.negativePhase} ${meta.negativeType} but completed")
                if (meta.isAsync) {
                    if (!host.asyncDone) return@enter Outcome(false, "async test did not complete: ${host.printed.toString().trim()}")
                    if (host.asyncFailure != null) return@enter Outcome(false, host.asyncFailure)
                }
                Outcome(true, null)
            }
        }
    }

    private fun runModule(agent: Agent, realm: Realm, meta: TestMeta, src: String, file: File, host: Host): Outcome {
        val key = file.canonicalPath
        val rec = try {
            dev.mooner.neonjs.vm.Modules.parseModule(realm, dev.mooner.neonjs.vm.ModuleSource(key, src))
        } catch (e: JSException) {
            if (meta.negativePhase == "parse") return checkErrorType(e, meta.negativeType)
            return Outcome(false, "unexpected parse error: ${describe(e)}")
        }
        if (meta.negativePhase == "parse") return Outcome(false, "expected parse-time ${meta.negativeType}")
        dev.mooner.neonjs.vm.Modules.moduleMap(realm)[key] = rec
        try {
            dev.mooner.neonjs.vm.Modules.loadGraph(realm, rec)
            dev.mooner.neonjs.vm.Modules.link(rec)
        } catch (e: Throwable) {
            if (meta.negativePhase == "resolution") return checkErrorType(e, meta.negativeType)
            return Outcome(false, "link error: ${describe(e)}")
        }
        if (meta.negativePhase == "resolution") return Outcome(false, "expected resolution ${meta.negativeType}")
        val p: dev.mooner.neonjs.vm.JSPromise
        try {
            p = dev.mooner.neonjs.vm.Modules.evaluate(rec)
            agent.runJobs()
            if (meta.isAsync) runEventLoop(agent, host)
        } catch (e: Throwable) {
            if (meta.negativePhase == "runtime") return checkErrorType(e, meta.negativeType)
            return Outcome(false, describe(e))
        }
        if (p.state == dev.mooner.neonjs.vm.JSPromise.REJECTED) {
            val e = JSException(p.result)
            if (meta.negativePhase == "runtime") return checkErrorType(e, meta.negativeType)
            return Outcome(false, describe(e))
        }
        if (meta.negativePhase != null) return Outcome(false, "expected ${meta.negativePhase} ${meta.negativeType} but completed")
        if (meta.isAsync) {
            if (!host.asyncDone) return Outcome(false, "async test did not complete: ${host.printed.toString().trim()}")
            if (host.asyncFailure != null) return Outcome(false, host.asyncFailure)
        } else if (p.state == dev.mooner.neonjs.vm.JSPromise.PENDING) return Outcome(false, "module evaluation did not complete")
        return Outcome(true, null)
    }

    private fun checkErrorType(e: Throwable, type: String?): Outcome {
        if (e !is JSException) return Outcome(false, "expected $type, got ${describe(e)}")
        val v = e.value
        if (v is JSObject) {
            val c = try { v.get("constructor", v) } catch (_: Throwable) { null }
            val name = if (c is JSObject) try { Ops.toDisplayString(c.get("name", c)) } catch (_: Throwable) { "?" } else "?"
            if (name == type) return Outcome(true, null)
            return Outcome(false, "expected $type, got $name: ${describe(e)}")
        }
        return Outcome(false, "expected $type, got non-object ${describe(e)}")
    }

    companion object {
        fun describe(e: Throwable): String = when (e) {
            is JSException -> {
                val s = e.describe()
                val st = e.jsStack
                if (st != null) "$s | ${st.lines().take(3).joinToString(" ; ") { it.trim() }}" else s
            }
            is JSSyntaxError -> "SyntaxError(parse): ${e.message}"
            is StackOverflowError -> "StackOverflowError"
            else -> {
                val st = e.stackTrace.take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                "${e.javaClass.simpleName}: ${e.message} @ $st"
            }
        }
    }
}
