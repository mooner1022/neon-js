package io.neonjs.cli

import io.neonjs.NeonConsole
import io.neonjs.builtins.Builtins
import io.neonjs.ext.ConsoleBuiltins
import io.neonjs.ext.Inspector
import io.neonjs.ext.WebGlobals
import io.neonjs.runtime.*
import io.neonjs.vm.Evaluator
import io.neonjs.vm.ModuleSource
import io.neonjs.vm.Modules
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: neonjs [options] [file.js | file.mjs ...]
  -f, --file PATH run the local file PATH (repeatable; also --file=PATH)
  --interpreter   bytecode interpreter only
  --compiled      compile every function to JVM bytecode
  --adaptive      interpret, then compile hot functions (default)
  --module, -m    treat files as ES modules (default for .mjs)
  --dis           print bytecode before running
  -e CODE         evaluate CODE
  -h, --help      print this help
Files given with -f and as plain arguments run in the order given, after any -e code.
With no files and no -e, starts an interactive REPL."""

fun main(args: Array<String>) {
    // Deep JS recursion needs more than the JVM's default 1 MB main-thread stack to reach the engine's call-depth
    // limit. The launch script passes -Xss16m, but `java -jar` does not, so run on a thread with a 16 MB stack.
    var failure: Throwable? = null
    val t = Thread(null, {
        try {
            cliMain(args)
        } catch (e: Throwable) {
            failure = e
        }
    }, "neonjs-main", 16L shl 20)
    t.start()
    t.join()
    failure?.let { throw it }
}

private fun cliMain(args: Array<String>) {
    var dis = false
    var mode = 2
    var forceModule = false
    val files = ArrayList<String>()
    val snippets = ArrayList<String>()
    fun usageError(message: String): Nothing {
        System.err.println("neonjs: $message")
        System.err.println(USAGE)
        exitProcess(2)
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--dis" -> dis = true
            a == "--interpreter" -> mode = 0
            a == "--compiled" -> mode = 1
            a == "--adaptive" -> mode = 2
            a == "--module" || a == "-m" -> forceModule = true
            a == "-e" -> snippets.add(args.getOrNull(++i) ?: usageError("option -e requires code to evaluate"))
            a == "-f" || a == "--file" -> files.add(args.getOrNull(++i) ?: usageError("option $a requires a file path"))
            a.startsWith("--file=") -> files.add(a.substring("--file=".length).ifEmpty { usageError("option --file requires a file path") })
            a == "-h" || a == "--help" -> { println(USAGE); return }
            // a file whose name starts with '-' can still be run with -f
            a.startsWith("-") && a != "-" -> usageError("unknown option '$a'")
            else -> files.add(a)
        }
        i++
    }
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))
    val agent = Agent()
    agent.config.executionMode = mode
    agent.config.propagateInternalErrors = System.getProperty("neonjs.debug") != null
    var failed = false
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
            Modules.setLoader(realm, FileModuleLoader())

            fun report(e: Throwable) {
                failed = true
                when (e) {
                    is JSException -> {
                        System.err.println("Uncaught " + Inspector.inspect(e.value).let { s -> if (e.value is JSErrorObject) s else "$s" })
                        if (e.value !is JSErrorObject) e.jsStack?.let { System.err.println(it) }
                    }
                    is StackOverflowError -> System.err.println("Uncaught RangeError: Maximum call stack size exceeded")
                    else -> throw e
                }
            }

            /** Runs jobs and waits for timers / host events until nothing is pending (errors are reported). */
            fun eventLoop() {
                while (true) {
                    try {
                        agent.runJobs()
                    } catch (e: Throwable) {
                        report(e)
                        continue
                    }
                    if (!agent.hasPendingExternal()) return
                    agent.awaitExternal(Long.MAX_VALUE)
                }
            }

            for (s in snippets) {
                try {
                    val r = Evaluator.evaluateScript(realm, s, "<eval>")
                    agent.runJobs()
                    if (r !== Undefined) println(Inspector.inspect(r))
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
                        if (p.state == io.neonjs.vm.JSPromise.REJECTED) report(JSException(p.result))
                        continue
                    }
                    if (dis) {
                        val cb = Evaluator.compileScript(realm, src, f)
                        println(cb.disassemble())
                        for (c in cb.constants) if (c is io.neonjs.compiler.CodeBlock) println(c.disassemble())
                    }
                    Evaluator.evaluateScript(realm, src, f)
                    agent.runJobs()
                } catch (e: Throwable) { report(e) }
                eventLoop()
            }
            if (files.isEmpty() && snippets.isEmpty()) repl(realm, agent)
        }
    }
    if (failed) exitProcess(1)
}

private fun repl(realm: Realm, agent: Agent) {
    println("NeonJS REPL - type .exit or press Ctrl+D to quit")
    val reader = System.`in`.bufferedReader()
    val buf = StringBuilder()
    while (true) {
        // run timers / host events that became due while the user was typing
        try {
            agent.awaitExternal(0)
            agent.runJobs()
        } catch (e: JSException) {
            System.err.println("Uncaught " + Inspector.inspect(e.value))
        }
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
            if (isIncomplete(src, e)) continue
            buf.setLength(0)
            System.err.println("Uncaught " + Inspector.inspect(e.value))
        } catch (e: StackOverflowError) {
            buf.setLength(0)
            System.err.println("Uncaught RangeError: Maximum call stack size exceeded")
        }
    }
}

/** A syntax error at end of input means the user is still typing a multi-line construct. */
private fun isIncomplete(src: String, e: JSException): Boolean {
    val v = e.value as? JSErrorObject ?: return false
    val msg = Ops.toString(v.get("message", v))
    if (Ops.toString(v.get("name", v)) != "SyntaxError") return false
    return msg.contains("end of input", ignoreCase = true) || msg.contains("Unexpected EOF", ignoreCase = true) ||
        msg.contains("Unterminated template", ignoreCase = true) || msg.contains("Unterminated comment", ignoreCase = true)
}

/** Resolves relative specifiers against the importing module's file URL. */
private class FileModuleLoader : io.neonjs.vm.ModuleLoader {
    override fun resolve(specifier: String, referrerKey: String?): String {
        val base = referrerKey?.let { java.net.URI(it) } ?: File(".").absoluteFile.toURI()
        return base.resolve(specifier).normalize().toString()
    }

    override fun load(key: String, request: io.neonjs.compiler.ModuleRequest): ModuleSource {
        val f = File(java.net.URI(key))
        if (!f.isFile) throw JSException.typeError("Cannot find module '${request.specifier}'")
        return ModuleSource(key, f.readText())
    }

    override fun importMetaProperties(key: String): Map<String, Any?> = mapOf("url" to key)
}
