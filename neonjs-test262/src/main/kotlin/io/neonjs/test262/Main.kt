package io.neonjs.test262

import io.neonjs.parser.JSSyntaxError
import io.neonjs.parser.ParseOptions
import io.neonjs.parser.Parser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Result(val path: String, val passed: Boolean, val skipped: Boolean, val message: String?)

fun main(args: Array<String>) {
    var root = File("third_party/test262")
    val filters = ArrayList<String>()
    var parseOnly = false
    var threads = Runtime.getRuntime().availableProcessors()
    var outFile: String? = null
    var verbose = false
    var timeoutMs = 10000L
    var mode = 0
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--root" -> root = File(args[++i])
            "--parse-only" -> parseOnly = true
            "--threads" -> threads = args[++i].toInt()
            "--out" -> outFile = args[++i]
            "-v" -> verbose = true
            "--timeout" -> timeoutMs = args[++i].toLong()
            "--mode" -> mode = when (args[++i]) { "compiled" -> 1; "adaptive" -> 2; else -> 0 }
            "--staging" -> includeStaging = true
            "--intl" -> includeIntl = true
            else -> filters.add(a)
        }
        i++
    }
    val testDir = File(root, "test")
    val files = ArrayList<File>()
    val roots = if (filters.isEmpty()) listOf(testDir) else filters.map { f -> File(testDir, f.removePrefix("test/")) }
    for (r in roots) {
        if (r.isFile) files.add(r) else r.walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.forEach { files.add(it) }
    }
    files.sortBy { it.path }
    System.err.println("Running ${files.size} tests with $threads threads")
    val exec = Exec(root, timeoutMs, mode)
    val results = ConcurrentHashMap<String, Result>()
    val done = AtomicInteger()
    val pool = Executors.newFixedThreadPool(threads) { r -> Thread(null, r, "t262", 512L * 1024 * 1024).also { it.isDaemon = true } }
    val start = System.currentTimeMillis()
    for (f in files) {
        pool.submit {
            val rel = f.relativeTo(testDir).path.replace('\\', '/')
            val res = try {
                if (parseOnly) runParseOnly(f, rel) else runExec(exec, f, rel)
            } catch (t: Throwable) {
                Result(rel, false, false, "runner crash: $t")
            }
            results[rel] = res
            val n = done.incrementAndGet()
            if (n % 5000 == 0) System.err.println("  $n / ${files.size}")
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.DAYS)
    val elapsed = System.currentTimeMillis() - start
    report(results.values.toList(), outFile, verbose, elapsed)
    if (mode != 0) {
        System.err.println("JIT compiled=${io.neonjs.jit.JvmCompiler.compiledCount} shared=${io.neonjs.jit.JvmCompiler.sharedCount} failed=${io.neonjs.jit.JvmCompiler.failedCount}" +
            " background batches=${io.neonjs.jit.JitQueue.batches} blocks=${io.neonjs.jit.JitQueue.batchedBlocks} takenOver=${io.neonjs.jit.JitQueue.takenOver}")
        io.neonjs.jit.JvmCompiler.failureReasons.entries.sortedByDescending { it.value.get() }.take(15).forEach { System.err.println("  ${it.value} ${it.key}") }
    }
}

/** intl402/ (ECMA-402) and staging/ run only on request (--intl, --staging). */
var includeIntl = false
var includeStaging = false

val skippedFeatures = setOf(
    "export-defer",
)

fun runParseOnly(f: File, rel: String): Result {
    val src = f.readText()
    val meta = TestMeta.parse(src)
    meta.features.firstOrNull { it in skippedFeatures }?.let { return Result(rel, false, true, "feature $it") }
    if ((!includeIntl && rel.startsWith("intl402/")) || (!includeStaging && rel.startsWith("staging/"))) return Result(rel, false, true, "intl/staging")
    val expectParseError = meta.negativePhase == "parse"
    val modes = when {
        meta.isModule -> listOf("module")
        meta.onlyStrict -> listOf("strict")
        meta.noStrict || meta.isRaw -> listOf("sloppy")
        else -> listOf("sloppy", "strict")
    }
    for (m in modes) {
        val code = if (m == "strict") "\"use strict\";\n$src" else src
        val err: Throwable? = try {
            Parser.parse(code, ParseOptions(isModule = m == "module", sourceName = rel))
            null
        } catch (e: JSSyntaxError) {
            e
        } catch (e: StackOverflowError) {
            RuntimeException("stack overflow")
        } catch (e: Exception) {
            e
        }
        if (expectParseError) {
            if (err == null) return Result(rel, false, false, "[$m] expected SyntaxError, parsed OK")
            if (err !is JSSyntaxError) return Result(rel, false, false, "[$m] expected SyntaxError, got $err")
        } else if (err != null) {
            return Result(rel, false, false, "[$m] ${err.javaClass.simpleName}: ${err.message}")
        }
    }
    return Result(rel, true, false, null)
}

fun runExec(exec: Exec, f: File, rel: String): Result {
    val src = f.readText()
    val meta = TestMeta.parse(src)
    meta.features.firstOrNull { it in skippedFeatures }?.let { return Result(rel, false, true, "feature $it") }
    // every agent of this host can block (Atomics.wait), so [[CanBlock]] is true
    if ("CanBlockIsFalse" in meta.flags) return Result(rel, false, true, "flag CanBlockIsFalse")
    if ((!includeIntl && rel.startsWith("intl402/")) || (!includeStaging && rel.startsWith("staging/"))) return Result(rel, false, true, "intl/staging")
    val modes = when {
        meta.isModule -> listOf(false)
        meta.onlyStrict -> listOf(true)
        meta.noStrict || meta.isRaw -> listOf(false)
        else -> listOf(false, true)
    }
    for (strict in modes) {
        val o = try {
            exec.run(meta, src, rel, strict)
        } catch (t: Throwable) {
            Exec.Outcome(false, "runner exception: ${Exec.describe(t)}")
        }
        if (!o.ok) return Result(rel, false, false, "[${if (strict) "strict" else "sloppy"}] ${o.message}")
    }
    return Result(rel, true, false, null)
}

fun report(results: List<Result>, outFile: String?, verbose: Boolean, elapsed: Long) {
    val byDir = java.util.TreeMap<String, IntArray>()
    for (r in results) {
        val parts = r.path.split('/')
        val key = parts.take(minOf(3, parts.size - 1)).joinToString("/")
        val arr = byDir.getOrPut(key) { IntArray(3) }
        if (r.skipped) arr[2]++ else if (r.passed) arr[0]++ else arr[1]++
    }
    val pass = results.count { it.passed }
    val fail = results.count { !it.passed && !it.skipped }
    val skip = results.count { it.skipped }
    val sb = StringBuilder()
    for ((k, v) in byDir) {
        if (v[1] > 0 || verbose) sb.append(String.format("%-70s pass %6d  fail %6d  skip %6d%n", k, v[0], v[1], v[2]))
    }
    sb.append(String.format("TOTAL pass %d fail %d skip %d  (%.2f%% of run)  in %.1fs%n", pass, fail, skip,
        if (pass + fail == 0) 0.0 else 100.0 * pass / (pass + fail), elapsed / 1000.0))
    print(sb)
    if (outFile != null) {
        File(outFile).printWriter().use { w ->
            for (r in results.sortedBy { it.path }) {
                if (!r.passed && !r.skipped) w.println("FAIL ${r.path} :: ${r.message?.replace('\n', ' ')}")
            }
        }
    }
}
