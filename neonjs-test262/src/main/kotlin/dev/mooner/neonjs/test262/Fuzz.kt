package dev.mooner.neonjs.test262

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.ext.Inspector
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Evaluator
import dev.mooner.neonjs.vm.ModuleSource
import dev.mooner.neonjs.vm.Modules
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Mutation fuzzer over the Test262 corpus. Each input is a Test262 test with a few random mutations (span deletion,
 * duplication, splicing from another test, token insertion), run in the interpreter and in compiled mode under tight
 * limits. Reported problems:
 *  - crash: an exception other than a JS exception or a termination (an engine-internal error),
 *  - hang: no completion although the time limit should have stopped it,
 *  - mismatch: interpreter and compiled code complete differently (normal result vs. thrown error type).
 *
 * usage: FuzzKt --root third_party/test262 [--iterations N] [--seed S] [--threads T] [--out DIR]
 */
fun main(args: Array<String>) {
    var root = File("third_party/test262")
    var iterations = 2000
    var seed = System.nanoTime()
    var threads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
    var out = File("fuzz-out")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--root" -> root = File(args[++i])
            "--iterations" -> iterations = args[++i].toInt()
            "--seed" -> seed = args[++i].toLong()
            "--threads" -> threads = args[++i].toInt()
            "--out" -> out = File(args[++i])
        }
        i++
    }
    Fuzzer(root, out, seed).run(iterations, threads)
}

private class Fuzzer(val root: File, val out: File, val seed: Long) {
    private val corpus: List<File> = listOf("language", "built-ins", "annexB", "intl402").flatMap { d ->
        File(root, "test/$d").walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.toList()
    }.sortedBy { it.path }
    private val harness = listOf("assert.js", "sta.js", "compareArray.js", "propertyHelper.js").joinToString("\n") { File(root, "harness/$it").readText() }
    private val signatures = ConcurrentHashMap.newKeySet<String>()
    private val done = AtomicInteger()
    private val crashes = AtomicInteger()
    private val hangs = AtomicInteger()
    private val mismatches = AtomicInteger()
    private val completed = AtomicLong()
    private val outcomes = ConcurrentHashMap<String, AtomicInteger>()

    fun run(iterations: Int, threads: Int) {
        out.mkdirs()
        println("corpus ${corpus.size} files, seed $seed, $iterations iterations on $threads threads")
        val per = (iterations + threads - 1) / threads
        val workers = (0 until threads).map { t ->
            Thread(null, {
                val rnd = Random(seed + t)
                repeat(per) { iteration(rnd) }
            }, "fuzz-$t", 64L shl 20)
        }
        val start = System.nanoTime()
        workers.forEach { it.isDaemon = true; it.start() }
        while (workers.any { it.isAlive }) {
            workers.forEach { it.join(10_000) }
            println("  ${done.get()} inputs, ${completed.get()} ran to completion, ${crashes.get()} crashes, ${hangs.get()} hangs, ${mismatches.get()} mismatches")
        }
        println("most frequent interpreter outcomes:")
        for ((k, v) in outcomes.entries.sortedByDescending { it.value.get() }.take(12)) println("  ${v.get()}  $k")
        println("done in ${(System.nanoTime() - start) / 1_000_000_000}s: ${crashes.get()} crashes, ${hangs.get()} hangs, ${mismatches.get()} mismatches (unique signatures: ${signatures.size}) -> $out")
    }

    // ------------------------------------------------------------------ mutation

    private val tokens = listOf(
        "(", ")", "{", "}", "[", "]", ";", ",", "=>", "...", "?.", "??", "=", "+=", "**=", "&&=", "`", $$"${", "'", "\"",
        "async ", "await ", "yield ", "yield* ", "class ", "#x", "super", "super()", "super.x", "new.target", "import(", "import.meta",
        "let ", "const ", "var ", "using ", "function ", "function* ", "static ", "get ", "set ", "accessor ", "@dec ",
        "/", "/a/g", "/(?<n>a)|\\k<n>/v", "\\u{61}", "\\u0061", "0n", "1e400", "-0", "NaN", "null", "undefined", "this",
        "arguments", "eval", "delete ", "typeof ", "void ", "in ", "instanceof ", "of ", "with (Math) ", "label: ", "break ",
        "continue ", "return ", "throw ", "try {} finally {} ", "debugger;", "new ", "?", ":", "!", "~", "++", "--", "<<", ">>>",
        "Proxy", "Reflect", "Symbol.iterator", "Object.prototype", "Array(1e6)", "'x'.repeat(1e5)", "new Proxy({}, {})",
        "Object.freeze", "__proto__", "{ __proto__: null }", "constructor", "prototype", "0x7fffffff", "2**53",
    )

    private fun body(f: File): String {
        val s = f.readText()
        val end = s.indexOf("---*/")
        return if (end >= 0) s.substring(end + 5) else s
    }

    private fun span(s: String, rnd: Random): IntRange {
        if (s.isEmpty()) return IntRange.EMPTY
        val a = rnd.nextInt(s.length)
        val len = rnd.nextInt(1, minOf(80, s.length - a) + 1)
        return a until a + len
    }

    private val numbers = listOf("0", "-0", "1", "-1", "0.5", "2", "31", "32", "53", "255", "256", "65535", "65536",
        "2147483647", "2147483648", "4294967295", "4294967296", "9007199254740991", "9007199254740992", "1e21", "1e-7",
        "Infinity", "-Infinity", "NaN", "0x10", "1n", "-1n", "2n ** 64n", "Number.MAX_VALUE", "Number.MIN_VALUE")
    private val names = listOf("Object", "Array", "Proxy", "Reflect", "Symbol", "Map", "Set", "WeakMap", "Promise", "RegExp",
        "Date", "Math", "JSON", "BigInt", "String", "Number", "Function", "Error", "TypeError", "ArrayBuffer", "SharedArrayBuffer",
        "Uint8Array", "Float64Array", "BigInt64Array", "DataView", "Atomics", "Iterator", "Temporal", "globalThis", "undefined",
        "null", "this", "arguments", "eval", "x", "length", "prototype", "constructor", "__proto__", "valueOf", "toString",
        "Symbol.toPrimitive", "Symbol.species", "Symbol.iterator", "then", "next", "return", "get", "set", "value", "done")
    private val identRe = Regex("""\b[A-Za-z_$][\w$]*\b""")
    private val numberRe = Regex("""\b\d+(\.\d+)?\b""")

    /** Replaces one random match of [re] in [s] by [repl]. */
    private fun replaceOne(s: String, re: Regex, rnd: Random, repl: (String) -> String): String {
        val ms = re.findAll(s).toList()
        if (ms.isEmpty()) return s
        val m = ms[rnd.nextInt(ms.size)]
        return s.replaceRange(m.range, repl(m.value))
    }

    private fun mutate(src: String, rnd: Random): String {
        var s = src
        repeat(rnd.nextInt(1, 4)) {
            val lines = s.split('\n').toMutableList()
            when (rnd.nextInt(10)) {
                // statement-level: mostly keeps the program syntactically valid
                0 -> if (lines.size > 1) lines.removeAt(rnd.nextInt(lines.size))
                1 -> lines.add(rnd.nextInt(lines.size + 1), lines[rnd.nextInt(lines.size)])
                2 -> {
                    val other = body(corpus[rnd.nextInt(corpus.size)]).split('\n')
                    lines.add(rnd.nextInt(lines.size + 1), other[rnd.nextInt(other.size)])
                }
                3 -> if (lines.size > 1) {
                    val i = rnd.nextInt(lines.size)
                    val j = rnd.nextInt(lines.size)
                    val t = lines[i]; lines[i] = lines[j]; lines[j] = t
                }
                // value-level
                4, 5 -> { s = replaceOne(s, numberRe, rnd) { numbers[rnd.nextInt(numbers.size)] }; return@repeat }
                6, 7 -> {
                    val own = identRe.findAll(s).map { it.value }.toList()
                    s = replaceOne(s, identRe, rnd) { if (own.isNotEmpty() && rnd.nextBoolean()) own[rnd.nextInt(own.size)] else names[rnd.nextInt(names.size)] }
                    return@repeat
                }
                // character-level: stresses the parser and its early errors
                8 -> { val r = span(s, rnd); s = s.removeRange(r); return@repeat }
                else -> { val p = rnd.nextInt(s.length + 1); s = s.substring(0, p) + tokens[rnd.nextInt(tokens.size)] + s.substring(p); return@repeat }
            }
            s = lines.joinToString("\n")
        }
        return s
    }

    // ------------------------------------------------------------------ execution

    private class Result(val kind: String, val detail: String, val error: Throwable?)

    private fun iteration(rnd: Random) {
        val file = corpus[rnd.nextInt(corpus.size)]
        val raw = file.readText()
        val isModule = raw.contains("flags: [module]") || raw.contains("flags: [module,") || rnd.nextInt(10) == 0
        val strict = !isModule && rnd.nextInt(3) == 0
        var src = mutate(body(file), rnd)
        if (strict) src = "'use strict';\n$src"
        val interp = execute(src, isModule, 0)
        val compiled = execute(src, isModule, 1)
        done.incrementAndGet()
        if (interp.kind == "ok") completed.incrementAndGet()
        outcomes.computeIfAbsent(interp.kind + " " + interp.detail.take(60)) { AtomicInteger() }.incrementAndGet()
        for ((mode, r) in listOf("interpreter" to interp, "compiled" to compiled)) {
            if (r.kind == "crash" || r.kind == "hang") {
                val top = r.error?.stackTrace?.take(4)?.joinToString("|") { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: ""
                val sig = "${r.kind}:${r.error?.javaClass?.name}:$top"
                if (r.kind == "crash") crashes.incrementAndGet() else hangs.incrementAndGet()
                if (signatures.add(sig)) save(r.kind, src, isModule, "$mode ${file.relativeTo(root)}\n${r.detail}\n${r.error?.stackTraceToString() ?: ""}")
            }
        }
        val comparable = setOf("ok", "throw")
        if (interp.kind in comparable && compiled.kind in comparable && (interp.kind != compiled.kind || interp.detail != compiled.detail)) {
            mismatches.incrementAndGet()
            val sig = "mismatch:${interp.kind}:${interp.detail.take(40)}:${compiled.kind}:${compiled.detail.take(40)}"
            if (signatures.add(sig)) save("mismatch", src, isModule, "${file.relativeTo(root)}\ninterpreter: ${interp.kind} ${interp.detail}\ncompiled:    ${compiled.kind} ${compiled.detail}")
        }
    }

    private val counter = AtomicInteger()

    private fun save(kind: String, src: String, isModule: Boolean, info: String) {
        val n = counter.incrementAndGet()
        val ext = if (isModule) "mjs" else "js"
        File(out, "$kind-$n.$ext").writeText(src)
        File(out, "$kind-$n.txt").writeText(info)
    }

    /** Runs [src] on a fresh agent in a separate thread; a run that outlives its time limit by far is a hang. */
    private fun execute(src: String, isModule: Boolean, mode: Int): Result {
        var result: Result? = null
        val agent = Agent()
        agent.config.maxExecutionMillis = 1500
        agent.config.maxStatements = 20_000_000
        agent.config.maxStringLength = 1 shl 24
        agent.config.maxAllocatedBytes = 256L shl 20
        agent.config.propagateInternalErrors = true
        agent.config.executionMode = mode
        agent.config.jitThreshold = 2
        agent.maxDepth = 400
        agent.randomSource = java.util.Random(42)
        agent.clock = { 1_700_000_000_000.0 }
        val t = Thread(null, { result = runIn(agent, src, isModule) }, "fuzz-exec", 32L shl 20)
        t.isDaemon = true
        t.start()
        t.join(10_000)
        if (t.isAlive) {
            agent.interruptRequested = true
            t.join(5_000)
            // a thread that ignores the interrupt is abandoned (daemon); its agent is not shared
            val stack = t.stackTrace
            val e = RuntimeException("hang").also { it.stackTrace = stack }
            return Result("hang", "no completion after 10 s", e)
        }
        return result ?: Result("crash", "no result", null)
    }

    private fun runIn(agent: Agent, src: String, isModule: Boolean): Result = try {
        agent.enter {
            val realm = Realm(agent)
            Builtins.install(realm)
            realm.enter {
                agent.startLimits()
                installHost(realm)
                Evaluator.evaluateScript(realm, harness, "harness.js")
                val r: Any? = if (isModule) {
                    val (_, p) = Modules.runModule(realm, ModuleSource("fuzz.mjs", src))
                    agent.runJobs()
                    if (p.state == dev.mooner.neonjs.vm.JSPromise.REJECTED) throw JSException(p.result)
                    Undefined
                } else {
                    Evaluator.evaluateScript(realm, src, "fuzz.js").also { agent.runJobs() }
                }
                Result("ok", Inspector.inspect(r).take(300), null)
            }
        }
    } catch (e: JSException) {
        Result("throw", errorName(e.value), null)
    } catch (e: TerminationException) {
        Result("limit", e.javaClass.simpleName, null)
    } catch (_: StackOverflowError) {
        Result("limit", "StackOverflowError", null)
    } catch (e: Throwable) {
        Result("crash", e.toString(), e)
    } finally {
        agent.closeExternal()
    }

    private fun errorName(v: Any?): String {
        if (v !is JSObject) return "non-object " + Ops.typeOf(v)
        var p: JSObject? = v
        while (p != null) {
            val d = p.getOwnProperty("name")
            if (d != null && !d.isAccessor) return Ops.toDisplayString(d.value)
            if (d != null) return "?"
            p = p.proto
        }
        return "?"
    }

    private fun installHost(realm: Realm) {
        val g = realm.globalObject
        g.defineOwn("print", NativeFunction(realm, "print", 1, { _, _, _, _ -> Undefined }), Attr.WC)
        val d = JSObject(realm.objectPrototype)
        d.defineOwn("global", g, Attr.WC)
        d.defineOwn("evalScript", NativeFunction(realm, "evalScript", 1, { f, _, a, _ -> Evaluator.evaluateScript(f.realm, Ops.toString(a.arg(0)), "evalScript") }), Attr.WC)
        d.defineOwn("detachArrayBuffer", NativeFunction(realm, "detachArrayBuffer", 1, { f, _, a, _ ->
            val det = f.realm.intrinsics["%DetachArrayBuffer%"] ?: throw JSException.typeError("detachArrayBuffer unsupported")
            det.call(Undefined, arrayOf(a.arg(0)))
        }), Attr.WC)
        d.defineOwn("createRealm", NativeFunction(realm, "createRealm", 0, { f, _, _, _ ->
            val r2 = Realm(f.realm.agent)
            Builtins.install(r2)
            r2.enter { installHost(r2) }
            r2.globalObject.get("$262")
        }), Attr.WC)
        d.defineOwn("gc", NativeFunction(realm, "gc", 0, { _, _, _, _ -> Undefined }), Attr.WC)
        g.defineOwn("$262", d, Attr.WC)
    }
}
