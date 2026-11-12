package dev.mooner.neonjs.test262

import dev.mooner.neonjs.parser.JSSyntaxError
import dev.mooner.neonjs.parser.Node
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.Parser
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.system.exitProcess

/*
 * The parser's own checks (docs/LINT.md): what Parser.parse makes of every Test262 file, and how fast it reads them.
 * Both use the public parser API only, so that tools/check.sh-style comparisons can build an older engine with them.
 *
 *   ParseBaseline --root DIR --out FILE            every file's result: accepted (and its tree's digest) or the error
 *   ParseBaseline --root DIR --compare FILE      the same, compared with FILE: exit status 1 when anything differs
 *   ParseBench --root DIR [--rounds 14] [--drop 4] [--tolerant]   Parser.parse over the files it accepts, the median
 *                                                  round; --tolerant: each round reads the files expected to fail with
 *                                                  the recovery mode first (untimed), so that both parsers share the JVM
 *   ParseBench --once FILE [--module]              one parse of one file in this JVM (a new JVM each time: cold)
 */

/** A Test262 file, in one of the modes it is parsed in, with the parse options its metadata asks for. */
class ParseCase(val rel: String, val mode: String, val src: String, val options: ParseOptions, val negativeParse: Boolean) {
    val key get() = "$rel [$mode]"
}

/**
 * Every Test262 file but the fixtures. With [runnerModes], in each mode the runner parses it in (--parse-only: a
 * module; strict, with "use strict" before it; sloppy; or both); otherwise once, strict when only strict.
 */
fun parseCases(root: File, runnerModes: Boolean): List<ParseCase> {
    val testDir = File(root, "test")
    require(testDir.isDirectory) { "no Test262 at $root" }
    val out = ArrayList<ParseCase>()
    testDir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.forEach { f ->
        val src = f.readText()
        val meta = TestMeta.parse(src)
        val rel = f.relativeTo(testDir).path.replace('\\', '/')
        val neg = meta.negativePhase == "parse"
        if (!runnerModes) {
            out.add(ParseCase(rel, "file", src, ParseOptions(isModule = meta.isModule, strict = meta.onlyStrict), neg))
            return@forEach
        }
        val modes = when {
            meta.isModule -> listOf("module")
            meta.onlyStrict -> listOf("strict")
            meta.noStrict || meta.isRaw -> listOf("sloppy")
            else -> listOf("sloppy", "strict")
        }
        for (m in modes) {
            val code = if (m == "strict") "\"use strict\";\n$src" else src
            out.add(ParseCase(rel, m, code, ParseOptions(isModule = m == "module"), neg))
        }
    }
    out.sortBy { it.key }
    return out
}

/** A digest of a tree: every field of every node (but the compiler's annotations), in a fixed order. */
class TreeDigest {
    private val md = MessageDigest.getInstance("SHA-256")
    private val seen = IdentityHashMap<Any, Int>()

    fun of(n: Node): String {
        md.reset()
        seen.clear()
        walk(n)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun put(s: String) {
        md.update(s.toByteArray())
        md.update(0)
    }

    private fun walk(v: Any?) {
        when (v) {
            null -> put("null")
            is Node -> {
                val id = seen[v]
                if (id != null) { put("@$id"); return }
                seen[v] = seen.size
                put(v.javaClass.name)
                for (f in fieldsOf(v.javaClass)) {
                    put(f.name)
                    walk(f.get(v))
                }
            }
            is List<*> -> { put("[${v.size}"); v.forEach { walk(it) }; put("]") }
            is Double -> put("d" + java.lang.Double.doubleToRawLongBits(v))
            is String -> put("s$v")
            else -> put(v.javaClass.simpleName + ":" + v)
        }
    }

    companion object {
        private val fields = HashMap<Class<*>, List<Field>>()

        @Synchronized
        fun fieldsOf(c: Class<*>): List<Field> = fields.getOrPut(c) {
            val out = ArrayList<Field>()
            var k: Class<*>? = c
            while (k != null && k != Any::class.java) {
                for (f in k.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.name == "scope" || f.name == "ref") continue
                    f.isAccessible = true
                    out.add(f)
                }
                k = k.superclass
            }
            out.sortBy { it.declaringClass.name + "." + it.name }
            out
        }
    }
}

/**
 * One line per file and mode: "path [mode]\tneg|pos\tOK\tdigest" or "path [mode]\tneg|pos\tERR\tpos\tmessage"
 * (neg: the metadata expects a parse error).
 */
fun baselineLines(cases: List<ParseCase>): List<String> {
    val digest = TreeDigest()
    return cases.map { c ->
        val head = "${c.key}\t${if (c.negativeParse) "neg" else "pos"}"
        try {
            val prog = Parser.parse(c.src, c.options)
            "$head\tOK\t${digest.of(prog)}"
        } catch (e: JSSyntaxError) {
            "$head\tERR\t${e.pos}\t${e.rawMessage.replace('\n', ' ').replace('\t', ' ')}"
        } catch (e: StackOverflowError) {
            "$head\tSOE"
        }
    }
}

object ParseBaseline {
    @JvmStatic
    fun main(args: Array<String>) {
        var root = File("third_party/test262")
        var out: File? = null
        var compare: File? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--root" -> root = File(args[++i])
                "--out" -> out = File(args[++i])
                "--compare" -> compare = File(args[++i])
                else -> { System.err.println("unknown argument ${args[i]}"); exitProcess(2) }
            }
            i++
        }
        val cases = parseCases(root, runnerModes = true)
        val t0 = System.nanoTime()
        val lines = baselineLines(cases)
        System.err.println("${cases.size} files in ${(System.nanoTime() - t0) / 1_000_000} ms: " +
            "${lines.count { it.contains("\tOK\t") }} accepted, ${lines.count { it.contains("\tERR\t") }} rejected, " +
            "${lines.count { it.endsWith("\tSOE") }} stack overflows; " +
            "expected to fail but accepted: ${lines.count { it.contains("\tneg\tOK\t") }}, " +
            "rejected though not expected to: ${lines.count { it.contains("\tpos\tERR\t") }}")
        out?.writeText(lines.joinToString("\n", postfix = "\n"))
        if (compare != null) {
            val before = compare.readLines().filter { it.isNotEmpty() }.associateBy { it.substringBefore('\t') }
            val after = lines.associateBy { it.substringBefore('\t') }
            val keys = (before.keys + after.keys).sorted()
            var diffs = 0
            for (k in keys) {
                if (before[k] != after[k]) {
                    if (diffs < 20) println("DIFF $k\n  before: ${before[k]}\n  after:  ${after[k]}")
                    diffs++
                }
            }
            println("${keys.size} files compared, $diffs differ")
            exitProcess(if (diffs == 0) 0 else 1)
        }
    }
}

object ParseBench {
    @JvmStatic
    fun main(args: Array<String>) {
        var root = File("third_party/test262")
        var rounds = 14
        var drop = 4
        var once: File? = null
        var module = false
        var tolerant = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--root" -> root = File(args[++i])
                "--rounds" -> rounds = args[++i].toInt()
                "--drop" -> drop = args[++i].toInt()
                "--once" -> once = File(args[++i])
                "--module" -> module = true
                "--tolerant" -> tolerant = true
                else -> { System.err.println("unknown argument ${args[i]}"); exitProcess(2) }
            }
            i++
        }
        if (once != null) {
            // cold: the parser's classes load and run once, as an app's first script does
            val src = once.readText()
            val t0 = System.nanoTime()
            Parser.parse(src, ParseOptions(isModule = module))
            println("%.3f".format((System.nanoTime() - t0) / 1e6))
            return
        }
        val cases = parseCases(root, runnerModes = false)
        val valid = cases.filter { c ->
            !c.negativeParse && try { Parser.parse(c.src, c.options); true } catch (e: JSSyntaxError) { false } catch (e: StackOverflowError) { false }
        }
        val chars = valid.sumOf { it.src.length.toLong() }
        System.err.println("${valid.size} programs, $chars chars")
        // (found by reflection: an engine before the recovery mode builds this tool too)
        val tolerantParse = if (!tolerant) null else Parser::class.java.methods.firstOrNull {
            it.name == "parseTolerant" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
        } ?: run { System.err.println("--tolerant: this engine has no Parser.parseTolerant"); exitProcess(2) }
        val invalid = cases.filter { it.negativeParse }
        val times = ArrayList<Long>()
        var sink = 0
        for (r in 0 until rounds) {
            if (tolerantParse != null) for (c in invalid) sink += tolerantParse.invoke(null, c.src, c.options).hashCode() and 1
            val t0 = System.nanoTime()
            for (c in valid) sink += Parser.parse(c.src, c.options).body.size
            val ms = (System.nanoTime() - t0) / 1_000_000
            times.add(ms)
            System.err.println("round ${r + 1}: $ms ms")
        }
        val kept = times.drop(drop).sorted()
        val median = if (kept.size % 2 == 1) kept[kept.size / 2].toDouble() else (kept[kept.size / 2 - 1] + kept[kept.size / 2]) / 2.0
        println("median of rounds ${drop + 1}..$rounds: $median ms (min ${kept.first()}, max ${kept.last()}) [$sink]")
    }
}
