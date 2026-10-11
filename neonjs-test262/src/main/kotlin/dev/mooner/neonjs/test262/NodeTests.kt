package dev.mooner.neonjs.test262

import dev.mooner.neonjs.NeonConsole
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.NeonException
import dev.mooner.neonjs.NeonInterruptedException
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.node.NodeExtension
import dev.mooner.neonjs.node.NodeOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs Node.js's own tests (`test/parallel` of a Node checkout, v22.12.0) against neonjs-node, each in an engine of
 * its own: the test runs as a CommonJS module (with `process` in scope, as in Node, although NeonJS has no `process`
 * global) whose `require('../common')` is node-common.js; the event loop runs to its end, then `process` emits
 * 'exit' (common.mustCall's checks run there). A test passes when nothing was left uncaught and the exit code is 0;
 * common.skip() and tests that need `// Flags:` count as skipped.
 *
 * Usage: `NodeTestsKt [--root third_party/node] [--tests neonjs-test262/node-tests.txt] [--threads N] [--timeout ms]
 * [--known file] [--write-known file] [--list] [--output] [-v] [test-name-or-glob...]`; `--output` prints what each test
 * wrote. The tests file and the arguments are
 * file name globs within test/parallel; the known-failures file lists test names (`#` starts a comment).
 */
fun main(args: Array<String>) {
    var root = File("third_party/node")
    var testsFile = File("neonjs-test262/node-tests.txt")
    val patterns = ArrayList<String>()
    var threads = Runtime.getRuntime().availableProcessors()
    var timeoutMs = 20_000L
    var known = emptySet<String>()
    var writeKnown: File? = null
    var verbose = false
    var list = false
    var output = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--root" -> root = File(args[++i])
            "--tests" -> testsFile = File(args[++i])
            "--threads" -> threads = args[++i].toInt()
            "--timeout" -> timeoutMs = args[++i].toLong()
            "--known" -> known = File(args[++i]).readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
            "--write-known" -> writeKnown = File(args[++i])
            "-v" -> verbose = true
            "--list" -> list = true
            "--output" -> output = true
            else -> patterns.add(a)
        }
        i++
    }
    if (patterns.isEmpty()) patterns.addAll(testsFile.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() })
    val runner = NodeTests(root, timeoutMs)
    val tests = runner.collect(patterns)
    System.err.println("Running ${tests.size} Node.js tests with $threads threads")
    val results = ConcurrentHashMap<String, NodeTests.Result>()
    val pool = Executors.newFixedThreadPool(threads) { r -> Thread(null, r, "node-test", 512L * 1024 * 1024).also { it.isDaemon = true } }
    for (t in tests) pool.submit {
        results[t.name] = try {
            runner.run(t)
        } catch (e: Throwable) {
            NodeTests.Result(t.name, NodeTests.Outcome.FAIL, "runner crash: $e", "")
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.DAYS)

    val sorted = results.values.sortedBy { it.name }
    if (output) for (r in sorted) println("== ${r.outcome} ${r.name} ${r.message}\n${r.output}")
    if (list) for (r in sorted) println("${r.outcome} ${r.name}${if (r.outcome != NodeTests.Outcome.PASS) " :: " + r.message.replace('\n', ' ').take(200) else ""}")
    val byPrefix = sortedMapOf<String, IntArray>()
    for (r in sorted) {
        // test-buffer-alloc.js counts under test-buffer
        val prefix = r.name.removeSuffix(".js").split('-').take(2).joinToString("-")
        byPrefix.getOrPut(prefix) { IntArray(3) }[r.outcome.ordinal]++
    }
    for ((p, c) in byPrefix) System.out.printf("%-40s pass %4d  fail %4d  skip %4d%n", p, c[0], c[1], c[2])
    val failed = sorted.filter { it.outcome == NodeTests.Outcome.FAIL }
    println("TOTAL pass ${sorted.count { it.outcome == NodeTests.Outcome.PASS }} fail ${failed.size} skip ${sorted.count { it.outcome == NodeTests.Outcome.SKIP }}")
    writeKnown?.writeText(failed.joinToString("") { "${it.name}  # ${it.message.lineSequence().first().take(140)}\n" })
    val unexpected = failed.filter { it.name !in known }
    val nowPassing = sorted.filter { it.outcome == NodeTests.Outcome.PASS && it.name in known }
    if (known.isNotEmpty()) println("known failures: ${failed.size - unexpected.size}; unexpected: ${unexpected.size}; known but passing: ${nowPassing.size}")
    for (r in nowPassing) println("NOW PASSING ${r.name}")
    for (r in if (verbose) unexpected else unexpected.take(40)) {
        println("UNEXPECTED ${r.name} :: ${r.message.replace('\n', ' ').take(400)}")
        if (verbose && r.output.isNotBlank()) println(r.output.prependIndent("    | "))
    }
    System.out.flush()
    exitProcess(if (unexpected.isEmpty()) 0 else 1)
}

class NodeTests(val root: File, val timeoutMs: Long) {
    enum class Outcome { PASS, FAIL, SKIP }

    class Result(val name: String, val outcome: Outcome, val message: String, val output: String)

    private val parallel = File(root, "test/parallel")

    /** The files of test/parallel whose names match [patterns] (globs: `*` and `?`). */
    fun collect(patterns: List<String>): List<File> {
        val regexes = patterns.map { p -> Regex(p.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }) }
        return (parallel.listFiles() ?: emptyArray()).filter { f -> f.isFile && regexes.any { it.matches(f.name) } }.sortedBy { it.name }
    }

    fun run(test: File): Result {
        val src = test.readText()
        val flags = FLAGS.find(src)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() && it !in HARMLESS_FLAGS }.orEmpty()
        if (flags.isNotEmpty()) return Result(test.name, Outcome.SKIP, "flags: ${flags.joinToString(" ")}", "")

        val out = StringBuilder()
        val console = NeonConsole { _, msg -> synchronized(out) { if (out.length < 64 * 1024) out.append(msg).append('\n') } }
        var exitCode: Int? = null
        var skipped: String? = null
        val options = NodeOptions.builder()
            .argv(listOf("node", "/test/parallel/${test.name}"))
            .cwd("/test/parallel")
            .platform("linux")
            .onExit { exitCode = it }
            .build()
        // limits per task: the main script and each callback of the loop may each run up to the timeout
        // V8's longest string, which tests of buffer.constants.MAX_STRING_LENGTH expect
        val policy = SandboxPolicy.builder().maxExecutionTime(timeoutMs).limitsPerTask(true).maxStringLength(0x1fffffe8).build()
        val errors = ArrayList<String>()
        NeonEngine.builder().console(console).webGlobals(true).sandbox(policy).extension(NodeExtension(options)).build().use { engine ->
            engine.newContext().use { c ->
                c.setUncaughtErrorHandler { e, rejection -> errors.add((if (rejection) "unhandled rejection: " else "uncaught: ") + e.message) }
                c.setFunction("__nodeTestCompile") { a -> compile(c, a[0].asString()) }
                c.setFunction("__nodeTestSkipped") { a -> skipped = a[0].asString(); null }
                try {
                    val runner = c.eval(RUNNER, "node-runner.js")
                    runner.callMember("load", "test/parallel/${test.name}", true)
                    if (!c.runEventLoop(timeoutMs)) {
                        errors.add("timeout: the event loop was still busy after $timeoutMs ms")
                    } else {
                        val code = runner.callMember("exit").asInt()
                        if (exitCode == null) exitCode = code
                    }
                } catch (_: NeonInterruptedException) {
                    // process.exit(): the code came through onExit
                } catch (e: NeonException) {
                    errors.add("uncaught: ${e.message}${where(e)}")
                }
            }
        }
        val output = synchronized(out) { out.toString() }
        skipped?.let { return Result(test.name, Outcome.SKIP, it, output) }
        if (errors.isNotEmpty()) return Result(test.name, Outcome.FAIL, errors.joinToString("; "), output)
        if (exitCode != null && exitCode != 0) {
            return Result(test.name, Outcome.FAIL, "exit code $exitCode: ${output.lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" / ")}", output)
        }
        return Result(test.name, Outcome.PASS, "", output)
    }

    /** Where in the tests an uncaught error was thrown: the first test frames of its stack. */
    private fun where(e: NeonException): String = try {
        val stack = e.guestValue?.takeIf { it.hasMember("stack") }?.getMember("stack")?.toString().orEmpty()
        val frames = stack.lineSequence().filter { it.trimStart().startsWith("at ") && "/test/" in it }.take(2).map { it.trim() }.toList()
        if (frames.isEmpty()) "" else " [" + frames.joinToString(" < ") + "]"
    } catch (_: Exception) {
        ""
    }

    /** A test or test/common file as a module function, or null when there is none (test/common/index.js is ours). */
    private fun compile(c: NeonContext, path: String): Any? {
        val src = if (path == "test/common/index.js") COMMON else {
            val f = File(root, path).canonicalFile
            if (!f.path.startsWith(root.canonicalPath) || !f.isFile) return null
            f.readText()
        }
        val body = if (src.startsWith("#!")) "//" + src else src
        return c.eval("(function (exports, require, module, __filename, __dirname, process) { $body\n})", "/$path")
    }

    companion object {
        private val FLAGS = Regex("""^// Flags:(.*)$""", RegexOption.MULTILINE)

        /** Flags that change nothing the tests check here. */
        private val HARMLESS_FLAGS = setOf("--no-warnings", "--no-deprecation")

        private fun resource(name: String): String =
            NodeTests::class.java.getResourceAsStream(name)!!.use { String(it.readBytes(), Charsets.UTF_8) }

        private val RUNNER = resource("node-runner.js")
        private val COMMON = resource("node-common.js")
    }
}
