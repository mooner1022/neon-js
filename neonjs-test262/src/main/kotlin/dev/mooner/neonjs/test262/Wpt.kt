package dev.mooner.neonjs.test262

import dev.mooner.neonjs.ExecutionMode
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.NeonException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs the `.any.js` tests of web-platform-tests (testharness.js) against the web globals of the engine, the way
 * WPT's server runs them in a dedicated worker: `self` and `GLOBAL` are defined, testharness.js and the `META: script`
 * files are evaluated, then the test, then `done()`; the event loop runs until the harness reports completion.
 *
 * Usage: `WptKt [--root third_party/wpt] [--mode interpreter|compiled|adaptive] [--threads N] [--timeout ms]
 * [--known file] [--write-known file] [-v] [dir-or-file...]`. Results are per subtest; the known-failures file lists
 * `path[?variant] :: subtest name` lines, or a bare `path[?variant]` for all of a file's subtests.
 */
fun main(args: Array<String>) {
    var root = File("third_party/wpt")
    val filters = ArrayList<String>()
    var threads = Runtime.getRuntime().availableProcessors()
    var timeoutMs = 10_000L
    var mode = ExecutionMode.INTERPRETER
    var known = emptySet<String>()
    var writeKnown: File? = null
    var verbose = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--root" -> root = File(args[++i])
            "--threads" -> threads = args[++i].toInt()
            "--timeout" -> timeoutMs = args[++i].toLong()
            "--mode" -> mode = when (args[++i]) { "compiled" -> ExecutionMode.COMPILED; "adaptive" -> ExecutionMode.ADAPTIVE; else -> ExecutionMode.INTERPRETER }
            // whole lines only: subtest names may contain '#'
            "--known" -> known = File(args[++i]).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            "--write-known" -> writeKnown = File(args[++i])
            "-v" -> verbose = true
            else -> filters.add(a)
        }
        i++
    }
    val runner = Wpt(root, timeoutMs, mode)
    val tests = runner.collect(filters)
    System.err.println("Running ${tests.size} WPT files (with variants) with $threads threads")
    val results = ConcurrentHashMap<String, Wpt.FileResult>()
    val pool = Executors.newFixedThreadPool(threads) { r -> Thread(null, r, "wpt", 512L * 1024 * 1024).also { it.isDaemon = true } }
    for (t in tests) pool.submit {
        results[t.key] = try {
            runner.run(t)
        } catch (e: Throwable) {
            Wpt.FileResult(t.key, emptyList(), "runner crash: $e")
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.DAYS)

    val failures = ArrayList<Pair<String, String>>()
    val byDir = sortedMapOf<String, IntArray>()
    var skipped = 0
    for (r in results.values.sortedBy { it.key }) {
        if (r.skipped) {
            skipped++
            continue
        }
        val dir = r.key.substringBeforeLast('/')
        val counts = byDir.getOrPut(dir) { IntArray(2) }
        r.harnessError?.let { failures.add("${r.key} :: <harness>" to it); counts[1]++ }
        for (s in r.subtests) {
            if (s.passed) counts[0]++ else {
                counts[1]++
                failures.add("${r.key} :: ${s.name}" to s.message)
            }
        }
    }
    for ((dir, c) in byDir) System.out.printf("%-60s pass %5d  fail %5d%n", dir, c[0], c[1])
    val pass = byDir.values.sumOf { it[0] }
    val fail = byDir.values.sumOf { it[1] }
    println("TOTAL pass $pass fail $fail (skipped files: $skipped)")
    // a file none of whose subtests pass is listed whole, the others subtest by subtest
    writeKnown?.writeText(results.values.sortedBy { it.key }.filter { !it.skipped }.joinToString("") { r ->
        val failed = r.subtests.filter { !it.passed }
        when {
            r.subtests.none { it.passed } && (failed.isNotEmpty() || r.harnessError != null) -> r.key + "\n"
            else -> (if (r.harnessError != null) listOf("<harness>") else emptyList()).plus(failed.map { it.name })
                .joinToString("") { "${r.key} :: $it\n" }
        }
    })
    val unexpected = failures.filter { (key, _) -> key !in known && key.substringBefore(" :: ") !in known }
    if (known.isNotEmpty()) println("known failures: ${failures.size - unexpected.size}; unexpected: ${unexpected.size}")
    for ((key, msg) in if (verbose) unexpected else unexpected.take(40)) println("UNEXPECTED $key :: ${msg.replace('\n', ' ').take(240)}")
    System.out.flush()
    exitProcess(if (unexpected.isEmpty()) 0 else 1)
}

class Wpt(val root: File, val timeoutMs: Long, val mode: ExecutionMode) {
    /** One run of a test file: [variant] is its `META: variant` (the query string), or "". */
    class TestFile(val file: File, val rel: String, val variant: String, val meta: List<Pair<String, String>>) {
        val key: String get() = rel + variant
    }

    class Subtest(val name: String, val passed: Boolean, val message: String)

    class FileResult(val key: String, val subtests: List<Subtest>, val harnessError: String?, val skipped: Boolean = false)

    private val sources = ConcurrentHashMap<File, String>()

    private fun source(f: File): String = sources.getOrPut(f.canonicalFile) { f.readText() }

    /** The `.any.js` files under [filters] (paths relative to the root; all of them without filters), one per variant. */
    fun collect(filters: List<String>): List<TestFile> {
        val roots = if (filters.isEmpty()) listOf(root) else filters.map { File(root, it) }
        val files = roots.flatMap { r -> if (r.isFile) listOf(r) else r.walkTopDown().filter { it.isFile && it.name.endsWith(".any.js") }.toList() }
            .filter { !it.path.replace('\\', '/').contains("/resources/") }.distinct().sortedBy { it.path }
        return files.flatMap { f ->
            val meta = metaOf(source(f))
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val variants = meta.filter { it.first == "variant" }.map { it.second }.ifEmpty { listOf("") }
            variants.map { TestFile(f, rel, it, meta) }
        }
    }

    /** The `// META: key=value` lines at the top of a test. */
    private fun metaOf(src: String): List<Pair<String, String>> = src.lineSequence()
        .takeWhile { it.isBlank() || it.startsWith("//") }
        .mapNotNull { Regex("""^//\s*META:\s*(\w+)=(.*)$""").find(it.trim()) }
        .map { it.groupValues[1] to it.groupValues[2].trim() }
        .toList()

    /** Whether the test is meant for a scope other than a window (a worker, a shell, a ShadowRealm). */
    private fun runsOutsideWindows(t: TestFile): Boolean {
        val globals = t.meta.filter { it.first == "global" }.flatMap { it.second.split(',') }.map { it.trim() }
            .ifEmpty { listOf("window", "dedicatedworker") }
        return globals.any { g -> g != "window" && !g.startsWith("window-") }
    }

    fun run(t: TestFile): FileResult {
        if (!runsOutsideWindows(t)) return FileResult(t.key, emptyList(), null, skipped = true)
        val engine = NeonEngine.builder().webGlobals(true).console(null).executionMode(mode).build()
        engine.use { return runIn(t, it) }
    }

    private fun runIn(t: TestFile, engine: NeonEngine): FileResult {
        val subtests = ArrayList<Subtest>()
        var harness: String? = null
        var completed = false
        engine.newContext().use { c ->
            val errors = ArrayList<String>()
            c.setUncaughtErrorHandler { e, rejection -> errors.add((if (rejection) "unhandled rejection: " else "uncaught: ") + e.message) }
            c.setFunction("__wptResult") { a ->
                val passed = a[1].asInt() == 0
                subtests.add(Subtest(a[0].asString().replace("\n", "\\n"), passed, if (a[2].isNullish) "" else a[2].asString()))
                null
            }
            c.setFunction("__wptComplete") { a ->
                if (a[0].asInt() != 0) harness = "harness status ${a[0].asInt()}: ${if (a[1].isNullish) "" else a[1].asString()}"
                completed = true
                null
            }
            c.setFunction("__wptRead") { a -> readFixture(t, a[0].asString()) }
            c.eval(prelude(t), "wpt-prelude.js")
            try {
                c.eval(source(File(root, "resources/testharness.js")), "/resources/testharness.js")
                c.eval(REPORTER, "wpt-reporter.js")
                for ((k, v) in t.meta) if (k == "script") c.eval(source(resolve(t, v)), v)
                c.eval(source(t.file), "/" + t.rel)
                c.eval("done()", "wpt-done.js")
                c.runEventLoop(if (t.meta.any { it.first == "timeout" && it.second == "long" }) timeoutMs * 3 else timeoutMs)
            } catch (e: NeonException) {
                errors.add("uncaught: ${e.message}")
            }
            if (!completed) harness = (harness ?: "") + "harness did not complete (timeout)"
            if (errors.isNotEmpty()) harness = listOfNotNull(harness, errors.joinToString("; ")).joinToString("; ")
        }
        return FileResult(t.key, subtests, harness)
    }

    /** A path of a `META: script` or a fixture: absolute from the WPT root, or relative to the test's directory. */
    private fun resolve(t: TestFile, path: String): File {
        val p = ALIASES[path.substringBefore('?')] ?: path.substringBefore('?')
        return if (p.startsWith("/")) File(root, p.removePrefix("/")) else File(t.file.parentFile, p)
    }

    private fun readFixture(t: TestFile, url: String): String {
        val f = resolve(t, url).canonicalFile
        require(f.path.startsWith(root.canonicalPath) && f.isFile) { "no such fixture: $url" }
        return source(f)
    }

    /** What WPT's worker wrapper defines, plus `location` (for variants) and a fetch of local fixtures only. */
    private fun prelude(t: TestFile): String = """
        globalThis.self = globalThis;
        self.GLOBAL = { isWindow() { return false }, isWorker() { return false }, isShadowRealm() { return false } };
        self.location = { search: ${jsString(t.variant)}, href: ${jsString("http://web-platform.test/" + t.rel + t.variant)},
                          origin: "http://web-platform.test", protocol: "http:", host: "web-platform.test", hostname: "web-platform.test",
                          port: "", pathname: ${jsString("/" + t.rel)}, hash: "" };
        self.fetch = function (url) {
            return new Promise((resolve, reject) => {
                let text;
                try { text = __wptRead(String(url)) } catch (e) { reject(new TypeError("fetch failed: " + url)); return }
                resolve({ ok: true, status: 200, text: async () => text, json: async () => JSON.parse(text) });
            });
        };
    """.trimIndent()

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Paths WPT's server maps to other files. */
        private val ALIASES = mapOf("/resources/WebIDLParser.js" to "/resources/webidl2/lib/webidl2.js")

        private val REPORTER = """
            add_result_callback(t => __wptResult(t.name, t.status, t.message));
            add_completion_callback((tests, status) => __wptComplete(status.status, status.message));
        """.trimIndent()
    }
}
