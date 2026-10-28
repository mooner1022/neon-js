package dev.mooner.neonjs.cli

import dev.mooner.neonjs.NeonConsole
import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.ext.ConsoleBuiltins
import dev.mooner.neonjs.ext.Inspector
import dev.mooner.neonjs.ext.WebGlobals
import dev.mooner.neonjs.net.NetExtension
import dev.mooner.neonjs.net.NetOptions
import dev.mooner.neonjs.node.DotEnv
import dev.mooner.neonjs.node.NodeExtension
import dev.mooner.neonjs.node.NodeOptions
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Evaluator
import dev.mooner.neonjs.vm.HostException
import dev.mooner.neonjs.vm.JSPromise
import dev.mooner.neonjs.vm.ModuleSource
import dev.mooner.neonjs.vm.Modules
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: neonjs [options] [file.js | file.mjs ...] [-- args...]
  -f, --file PATH run the local file PATH (repeatable; also --file=PATH)
  --interpreter   bytecode interpreter only
  --compiled      compile every function to JVM bytecode
  --adaptive      interpret, then compile hot functions (default)
  --jit-threshold N
                  adaptive: compile a function on its Nth call (default 1000)
  --sync-jit      compile on the calling thread instead of in the background
  --module, -m    treat files as ES modules (default for .mjs)
  --dis           print bytecode before running
  -e CODE         evaluate CODE
  --node          Node.js APIs: require and the node: modules, Buffer, Node's timers and console
  --env NAME[=VALUE]
                  with --node: sets process.env.NAME (without a value, to the host's NAME); repeatable
  --env-file PATH with --node: sets process.env from the .env file PATH, read as Node reads one
                  (later files win, --env over all of them); also --env-file=PATH
  --env-file-if-exists PATH
                  the same, for a file that may not be there
  --allow-host PATTERNS
                  fetch and WebSocket may reach these hosts (comma-separated: example.com,
                  *.example.com for its subdomains, example.com:8443); repeatable
  --allow-all-hosts
                  fetch and WebSocket may reach any host
  --allow-address CIDRS
                  may also connect to these loopback or private ranges (127.0.0.1/32,
                  10.0.0.0/8, ::1/128; comma-separated); repeatable
  --allow-insecure
                  may also use http: and ws:
  -h, --help      print this help
Files given with -f and as plain arguments run in the order given, after any -e code; the arguments
after -- are the script's (process.argv, with --node). fetch and WebSocket reach only what the --allow
options allow: nothing by default. With no files and no -e, starts an interactive REPL."""

fun main(args: Array<String>) {
    // Deep JS recursion needs more than the JVM's default 1 MB main-thread stack to reach the engine's call-depth
    // limit. The launch script passes -Xss16m, but `java -jar` does not, so run on a thread with a 16 MB stack.
    var status = 0
    var failure: Throwable? = null
    val t = Thread(null, {
        try {
            status = cliMain(args)
        } catch (e: Throwable) {
            failure = e
        }
    }, "neonjs-main", 16L shl 20)
    t.start()
    t.join()
    failure?.let { throw it }
    // the network's threads (OkHttp's) must not keep the process alive once the scripts are done
    exitProcess(status)
}

/** The exit status of a run with [args]. */
private fun cliMain(args: Array<String>): Int {
    var dis = false
    var mode = 2
    var forceModule = false
    var jitThreshold = -1
    var syncJit = false
    val files = ArrayList<String>()
    val snippets = ArrayList<String>()
    var node = false
    val env = LinkedHashMap<String, String>()
    var envGiven = false
    // (path, whether it may be missing), in the order given
    val envFiles = ArrayList<Pair<String, Boolean>>()
    val scriptArgs = ArrayList<String>()
    val net = NetOptions.builder()
    fun usageError(message: String): Nothing {
        System.err.println("neonjs: $message")
        System.err.println(USAGE)
        exitProcess(2)
    }
    fun list(option: String, value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: usageError("option $option requires a value")
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                scriptArgs.addAll(args.copyOfRange(i + 1, args.size))
                break
            }
            a == "--dis" -> dis = true
            a == "--interpreter" -> mode = 0
            a == "--compiled" -> mode = 1
            a == "--adaptive" -> mode = 2
            a == "--jit-threshold" -> jitThreshold = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 } ?: usageError("option --jit-threshold requires a positive number")
            a == "--sync-jit" -> syncJit = true
            a == "--module" || a == "-m" -> forceModule = true
            a == "-e" -> snippets.add(args.getOrNull(++i) ?: usageError("option -e requires code to evaluate"))
            a == "-f" || a == "--file" -> files.add(args.getOrNull(++i) ?: usageError("option $a requires a file path"))
            a.startsWith("--file=") -> files.add(a.substring("--file=".length).ifEmpty { usageError("option --file requires a file path") })
            a == "--node" -> node = true
            a == "--env" -> {
                val v = args.getOrNull(++i)?.takeIf { it.isNotEmpty() && !it.startsWith("=") } ?: usageError("option --env requires NAME or NAME=VALUE")
                val eq = v.indexOf('=')
                if (eq >= 0) env[v.substring(0, eq)] = v.substring(eq + 1) else System.getenv(v)?.let { env[v] = it }
                envGiven = true
            }
            a == "--env-file" || a == "--env-file-if-exists" || a.startsWith("--env-file=") || a.startsWith("--env-file-if-exists=") -> {
                val eq = a.indexOf('=')
                val path = if (eq >= 0) a.substring(eq + 1) else args.getOrNull(++i) ?: usageError("option $a requires a file path")
                envFiles.add(path to a.startsWith("--env-file-if-exists"))
                envGiven = true
            }
            a == "--allow-host" -> for (p in list(a, args.getOrNull(++i))) {
                try {
                    net.allowHosts(p)
                } catch (e: IllegalArgumentException) {
                    usageError(e.message ?: "invalid host pattern: $p")
                }
            }
            a == "--allow-all-hosts" -> net.allowAllHosts()
            a == "--allow-address" -> for (c in list(a, args.getOrNull(++i))) {
                try {
                    net.allowAddresses(c)
                } catch (e: IllegalArgumentException) {
                    usageError(e.message ?: "invalid address range: $c")
                }
            }
            a == "--allow-insecure" -> net.allowInsecure(true)
            a == "-h" || a == "--help" -> { println(USAGE); return 0 }
            // a file whose name starts with '-' can still be run with -f
            a.startsWith("-") && a != "-" -> usageError("unknown option '$a'")
            else -> files.add(a)
        }
        i++
    }
    if (!node && envGiven) usageError("--env and --env-file need --node")
    if (!node && scriptArgs.isNotEmpty()) usageError("the arguments after -- are the script's process.argv: they need --node")
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Terminal.charset("stdout.encoding")))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Terminal.charset("stderr.encoding")))
    // as Node: a later file's variables replace an earlier one's, and the environment given otherwise (--env) wins
    if (envFiles.isNotEmpty()) {
        val fromFiles = LinkedHashMap<String, String>()
        for ((path, optional) in envFiles) {
            val text = try {
                File(path).readText(Charsets.UTF_8)
            } catch (_: java.io.IOException) {
                if (optional) {
                    System.err.println("$path not found. Continuing without it.")
                    continue
                }
                System.err.println("neonjs: $path: not found")
                return 9
            }
            fromFiles.putAll(DotEnv.parse(text))
        }
        fromFiles.putAll(env)
        env.clear()
        env.putAll(fromFiles)
    }
    val agent = Agent()
    agent.config.executionMode = mode
    if (jitThreshold > 0) agent.config.jitThreshold = jitThreshold
    if (syncJit) agent.config.backgroundJit = false
    agent.config.propagateInternalErrors = System.getProperty("neonjs.debug") != null
    var failed = false
    var exitCode: Int? = null
    agent.enter {
        val realm = Realm(agent)
        Builtins.install(realm)
        realm.enter {
            ConsoleBuiltins.install(realm, NeonConsole.STDIO)
            WebGlobals.install(realm, 1_000_000)
            val print = NativeFunction(realm, "print", 1, { _, _, a, _ ->
                println(a.joinToString(" ") { Inspector.display(it) })
                Undefined
            })
            realm.globalObject.defineOwn("print", print, Attr.WC)
            realm.globalObject.defineOwn("java_nanos", NativeFunction(realm, "java_nanos", 0, { _, _, _, _ -> System.nanoTime().toDouble() }), Attr.WC)
            // as an engine installs them: the network (whose policy refuses what no --allow option allows), then the
            // Node.js APIs
            NetExtension(net.build()).install(realm)
            if (node) {
                val argv = listOf("neonjs") + listOfNotNull(files.firstOrNull()?.let { File(it).absolutePath }) + scriptArgs
                NodeExtension(NodeOptions.builder().env(env).argv(argv).onExit { exitCode = it }.build()).install(realm)
            }
            Modules.setLoader(realm, FileModuleLoader(realm))

            fun report(e: Throwable) {
                failed = true
                when (e) {
                    is JSException -> {
                        System.err.println("Uncaught " + Inspector.inspect(e.value))
                        // without a stack: where the parser found a syntax error, or where a value was thrown
                        val err = e.value as? JSErrorObject
                        val at = if (err?.stackTrace.isNullOrEmpty()) err?.location ?: e.location else null
                        at?.let { System.err.println("    at ${it.sourceName ?: "<input>"}:${it.line}:${it.column}") }
                    }
                    is HostException -> System.err.println("Uncaught host exception: ${e.hostCause}")
                    is StackOverflowError -> System.err.println("Uncaught RangeError: Maximum call stack size exceeded")
                    else -> throw e
                }
            }
            // what jobs leave uncaught (after node:process' listeners, with --node) is reported, and the loop goes on
            agent.uncaught = object : Agent.UncaughtSink {
                override fun exception(e: RuntimeException) = report(e)

                override fun rejection(p: JSPromise) {
                    failed = true
                    System.err.println("Uncaught (in promise) " + Inspector.inspect(p.result))
                }
            }

            /** Runs jobs and waits for timers / host events until nothing is pending (errors are reported). */
            fun eventLoop() {
                while (true) {
                    try {
                        agent.runJobs()
                    } catch (e: TerminationException) {
                        throw e
                    } catch (e: Throwable) {
                        report(e)
                        continue
                    }
                    if (!agent.hasPendingExternal()) return
                    agent.awaitExternal(Long.MAX_VALUE)
                }
            }

            try {
                for (s in snippets) {
                    try {
                        val r = Evaluator.evaluateScript(realm, s, "<eval>")
                        agent.runJobs()
                        if (r !== Undefined) println(Inspector.inspect(r))
                    } catch (e: TerminationException) {
                        throw e
                    } catch (e: Throwable) { report(e) }
                    eventLoop()
                }
                for (f in files) {
                    val file = File(f)
                    val src = try {
                        if (!file.exists()) throw java.io.FileNotFoundException("no such file")
                        if (!file.isFile) throw java.io.IOException("not a regular file")
                        file.readText(Charsets.UTF_8)
                    } catch (e: java.io.IOException) {
                        System.err.println("neonjs: cannot read '$f': ${e.message}")
                        failed = true
                        continue
                    }
                    try {
                        if (forceModule || f.endsWith(".mjs")) {
                            val (_, p) = Modules.runModule(realm, ModuleSource(file.absoluteFile.toURI().toString(), src))
                            eventLoop()
                            if (p.state == JSPromise.REJECTED) report(JSException(p.result))
                            continue
                        }
                        if (dis) {
                            val cb = Evaluator.compileScript(realm, src, f)
                            println(cb.disassemble())
                            for (c in cb.constants) if (c is dev.mooner.neonjs.compiler.CodeBlock) println(c.disassemble())
                        }
                        Evaluator.evaluateScript(realm, src, f)
                        agent.runJobs()
                    } catch (e: TerminationException) {
                        throw e
                    } catch (e: Throwable) { report(e) }
                    eventLoop()
                }
                if (files.isEmpty() && snippets.isEmpty()) {
                    repl(realm, agent)
                    // what the session left uncaught was reported as it happened; it is no failure of the run
                    failed = false
                }
                if (node) {
                    exitCode = try {
                        exitEvent(realm, if (failed) 1 else 0)
                    } catch (e: JSException) {
                        report(e)
                        1
                    }
                }
            } catch (e: InterruptedExecutionException) {
                // process.exit(code): the code came through onExit; nothing else runs
                if (exitCode == null) throw e
            }
        }
    }
    return exitCode ?: if (failed) 1 else 0
}

/**
 * As Node's process ends when nothing is left to do: node:process (if a script loaded it) emits 'exit' with
 * `process.exitCode` (or [status]), and its listeners may change that code. Without it, [status].
 */
private fun exitEvent(realm: Realm, status: Int): Int {
    val proc = Modules.builtin(realm, "node:process")?.takeIf { it.isLoaded }?.exports() as? JSObject ?: return status
    fun exitCode(fallback: Int): Int = proc.get("exitCode", proc).let { if (it === Undefined || it === Null) fallback else Ops.toNumber(it).toInt() }
    val code = exitCode(status)
    if (Ops.toBoolean(proc.get("_exiting", proc))) return code
    proc.set("_exiting", true, proc)
    Ops.invoke(proc, "emit", arrayOf("exit", code.toDouble()))
    return exitCode(code)
}

private fun repl(realm: Realm, agent: Agent) {
    println("NeonJS REPL - type .exit or press Ctrl+D to quit")
    val reader = System.`in`.bufferedReader(Terminal.charset("stdin.encoding"))
    val buf = StringBuilder()
    while (true) {
        // run timers / host events that became due while the user was typing (what they leave uncaught is reported)
        agent.awaitExternal(0)
        agent.runJobs()
        print(if (buf.isEmpty()) "> " else "... ")
        System.out.flush()
        val line = reader.readLine() ?: break
        if (buf.isEmpty() && line.trim() == ".exit") break
        buf.append(line).append('\n')
        val src = buf.toString()
        try {
            val r = Evaluator.evaluateScript(realm, src, "<repl>")
            buf.setLength(0)
            agent.runJobs()
            println(Inspector.inspect(r))
        } catch (e: JSException) {
            if (isIncomplete(e)) continue
            buf.setLength(0)
            System.err.println("Uncaught " + Inspector.inspect(e.value))
        } catch (_: StackOverflowError) {
            buf.setLength(0)
            System.err.println("Uncaught RangeError: Maximum call stack size exceeded")
        }
    }
}

/**
 * The encoding of the standard streams: a terminal's own (on Windows its code page, so that a console set to 949
 * shows Hangul rather than UTF-8 bytes), else UTF-8, as Node writes to files and pipes.
 */
private object Terminal {
    private val console: java.io.Console? = System.console()

    /** Whether the streams are a terminal's: [java.io.Console.isTerminal] from Java 22, a console at all before. */
    private val isTerminal: Boolean = console != null && try {
        java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
    } catch (_: NoSuchMethodException) {
        true
    }

    /** The charset of the stream whose encoding the system property [property] names (the console's when unset). */
    fun charset(property: String): java.nio.charset.Charset {
        if (!isTerminal) return Charsets.UTF_8
        return System.getProperty(property)?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: console!!.charset()
    }
}

/** A syntax error at end of input means the user is still typing a multi-line construct. */
private fun isIncomplete(e: JSException): Boolean {
    val v = e.value as? JSErrorObject ?: return false
    val msg = Ops.toString(v.get("message", v))
    return Ops.toString(v.get("name", v)) == "SyntaxError" && (msg.contains("end of input", ignoreCase = true) || msg.contains("Unexpected EOF", ignoreCase = true) ||
            msg.contains("Unterminated template", ignoreCase = true) || msg.contains("Unterminated comment", ignoreCase = true))
}

/** Built-in modules (`node:events` with --node, `ws` with the network) by name; other specifiers relative to the importing file. */
private class FileModuleLoader(private val realm: Realm) : dev.mooner.neonjs.vm.ModuleLoader {
    override fun resolve(specifier: String, referrerKey: String?): String {
        Modules.builtin(realm, specifier)?.let { return it.name }
        val base = referrerKey?.let { java.net.URI(it) } ?: File(".").absoluteFile.toURI()
        return base.resolve(specifier).normalize().toString()
    }

    override fun load(key: String, request: dev.mooner.neonjs.compiler.ModuleRequest): ModuleSource {
        Modules.builtin(realm, key)?.let { return Modules.builtinSource(realm, it) }
        val f = File(java.net.URI(key))
        if (!f.isFile) throw JSException.typeError("Cannot find module '${request.specifier}'")
        return ModuleSource(key, f.readText())
    }

    override fun importMetaProperties(key: String): Map<String, Any?> = mapOf("url" to key)
}
