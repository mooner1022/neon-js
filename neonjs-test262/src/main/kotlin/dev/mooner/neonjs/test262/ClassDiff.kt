package dev.mooner.neonjs.test262

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Compares two directories of classes the JIT dumped (`-Dneonjs.jit.dump=DIR`), for changes that should not change the
 * code generated (docs/TESTING.md, "Generated code unchanged"): the same classes, as many of each, with the same code.
 *
 * Names of the optimizing tier's classes end with a counter (`T2$name$17`), which depends on the order blocks were
 * compiled in: it is removed from file names and from the code (where the class names itself) before comparing. The
 * baseline's names end with a hash of their input and are compared as they are.
 *
 * Usage: `ClassDiffKt BEFORE AFTER [--show N] [--subset] [--equiv FILE]`; exit status 0 when equal, 1 otherwise, with
 * the first N differences printed (a class only on one side, or the first lines where its code differs). With
 * `--subset`, for a change that compiles more: every class BEFORE must be in AFTER, unchanged (AFTER may have more).
 *
 * With `--equiv FILE` (tools/codegen-equiv.txt), the classes are compared modulo the renames FILE lists, applied to
 * both sides alike, and the summary without them is printed too: `type NEW = OLD` (internal names) counts class NEW as
 * OLD; `entry-cast CLASS` drops a `run` method's first instructions where they are `ALOAD 1; CHECKCAST CLASS;
 * ASTORE 1` (the cast of its frame); `expand-frames` prints the stack map frames uncompressed (as the verifier sees
 * them: a cast changes how the next frame is encoded, not what it says); `baseline-names` drops the hash a baseline
 * class's name ends with (`T2$base$<hash>`: a hash of its code, which renames change).
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: ClassDiffKt BEFORE AFTER [--show N]")
        exitProcess(2)
    }
    val show = args.indexOf("--show").let { if (it >= 0) args[it + 1].toInt() else 5 }
    val equiv = args.indexOf("--equiv").let { if (it >= 0) ClassDiff.Equivalences.read(File(args[it + 1])) else null }
    if (equiv != null) {
        // what differs as generated, before the renames are applied
        val raw = ClassDiff.compare(ClassDiff.load(File(args[0])), ClassDiff.load(File(args[1])))
        println("as generated: ${raw.size} names differ; modulo ${equiv.size} renames (${args[args.indexOf("--equiv") + 1]}):")
    }
    val before = ClassDiff.load(File(args[0]), equiv)
    val after = ClassDiff.load(File(args[1]), equiv)
    if ("--subset" in args) {
        // a change that compiles more (new coverage): every class before must be there after, unchanged
        val missing = ClassDiff.missing(before, after)
        println("classes: ${before.values.sumOf { it.size }} before, ${after.values.sumOf { it.size }} after; " +
            "${missing.sumOf { it.second }} of those before not there after")
        for ((name, n) in missing.take(show)) println("missing: $name ($n)")
        exitProcess(if (missing.isEmpty()) 0 else 1)
    }
    val diffs = ClassDiff.compare(before, after)
    println("classes: ${before.values.sumOf { it.size }} before, ${after.values.sumOf { it.size }} after; " +
        "${before.size} and ${after.size} distinct names; ${diffs.size} names differ")
    for (d in diffs.take(show)) println(d)
    exitProcess(if (diffs.isEmpty()) 0 else 1)
}

object ClassDiff {
    private val counter = Regex("""(T2\$[^/;.\s]*?)\$\d+\b""")

    /** A class's text with the optimizing tier's counters removed. */
    class Entry(val text: String) {
        val digest: String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Renames that count as no change (`--equiv`): classes counted as others, and a `run` method's cast of its frame. */
    class Equivalences(
        private val types: List<Pair<Regex, String>>,
        private val casts: Set<String>,
        val expandFrames: Boolean,
        private val baselineNames: Boolean,
    ) {
        val size: Int get() = types.size + casts.size + (if (expandFrames) 1 else 0) + (if (baselineNames) 1 else 0)

        fun name(n: String): String = if (baselineNames) baselineHash.replace(n) { it.groupValues[1] } else n

        fun apply(text: String): String {
            var t = if (baselineNames) baselineHash.replace(text) { it.groupValues[1] } else text
            for ((from, to) in types) t = from.replace(t, to)
            if (casts.isEmpty()) return t
            val lines = t.lines().toMutableList()
            var k = 0
            while (k < lines.size) {
                if (Regex("""\s(public |)final run\(L[^;]+;\)Ljava/lang/Object;""").containsMatchIn(lines[k])) {
                    var j = k + 1
                    while (j < lines.size && (lines[j].trim().startsWith("TRYCATCHBLOCK") || lines[j].trim().matches(Regex("L\\d+")))) j++
                    if (j + 2 < lines.size && lines[j].trim() == "ALOAD 1" && lines[j + 2].trim() == "ASTORE 1" &&
                        lines[j + 1].trim().removePrefix("CHECKCAST ") in casts) {
                        repeat(3) { lines.removeAt(j) }
                    }
                }
                k++
            }
            return lines.joinToString("\n")
        }

        companion object {
            private val baselineHash = Regex("(T2\\\$base)\\\$[0-9a-f]{16}\\b")

            fun read(f: File): Equivalences {
                val types = ArrayList<Pair<Regex, String>>()
                val casts = HashSet<String>()
                var expand = false
                var names = false
                for (raw in f.readLines()) {
                    val l = raw.substringBefore('#').trim()
                    if (l.isEmpty()) continue
                    when {
                        l.startsWith("type ") -> {
                            val (from, to) = l.removePrefix("type ").split("=").map { it.trim() }
                            // (a name is fully qualified: it ends at a character no name has, and may follow a descriptor's L)
                            types.add(Regex(Regex.escape(from) + """(?![\w$])""") to to)
                        }
                        l.startsWith("entry-cast ") -> casts.add(l.removePrefix("entry-cast ").trim())
                        l == "expand-frames" -> expand = true
                        l == "baseline-names" -> names = true
                        else -> throw IllegalArgumentException("$f: not a rule: $raw")
                    }
                }
                return Equivalences(types, casts, expand, names)
            }
        }
    }

    /** The classes of [dir], by name without counter: each class's entry (several where the counter told them apart). */
    fun load(dir: File, equiv: Equivalences? = null): Map<String, List<Entry>> {
        val out = HashMap<String, ArrayList<Entry>>()
        val files = dir.listFiles { f -> f.name.endsWith(".class") } ?: throw IllegalArgumentException("not a directory: $dir")
        for (f in files) {
            val sw = StringWriter()
            val flags = ClassReader.SKIP_DEBUG or (if (equiv?.expandFrames == true) ClassReader.EXPAND_FRAMES else 0)
            ClassReader(f.readBytes()).accept(TraceClassVisitor(null, Textifier(), PrintWriter(sw)), flags)
            val text = normalize(sw.toString()).let { equiv?.apply(it) ?: it }
            val name = normalize(f.name.removeSuffix(".class")).let { equiv?.name(it) ?: it }
            out.getOrPut(name) { ArrayList() }.add(Entry(text))
        }
        for (l in out.values) l.sortBy { it.digest }
        return out
    }

    fun normalize(s: String): String = counter.replace(s) { it.groupValues[1] }

    /** Names with classes before that are not after (as many of each), and how many. */
    fun missing(before: Map<String, List<Entry>>, after: Map<String, List<Entry>>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((name, a) in before.toSortedMap()) {
            val left = after[name].orEmpty().map { it.digest }.toMutableList()
            val n = a.count { e -> !left.remove(e.digest) }
            if (n > 0) out.add(name to n)
        }
        return out
    }

    /** The differences, one line (or a few) per name. */
    fun compare(before: Map<String, List<Entry>>, after: Map<String, List<Entry>>): List<String> {
        val out = ArrayList<String>()
        for (name in (before.keys + after.keys).sorted()) {
            val a = before[name].orEmpty()
            val b = after[name].orEmpty()
            if (a.map { it.digest } == b.map { it.digest }) continue
            when {
                a.isEmpty() -> out.add("only after: $name (${b.size})")
                b.isEmpty() -> out.add("only before: $name (${a.size})")
                else -> {
                    val onlyA = a.filter { x -> b.none { it.digest == x.digest } }
                    val onlyB = b.filter { x -> a.none { it.digest == x.digest } }
                    // the closest pair shows what changed (entries of one name are in no particular order)
                    val pair = onlyA.take(50).flatMap { x -> onlyB.take(50).map { y -> x to y } }
                        .maxByOrNull { (x, y) -> sameLines(x.text, y.text) }
                    out.add("differs: $name (${a.size} before, ${b.size} after)" +
                        (if (pair != null) "\n" + firstDifference(pair.first.text, pair.second.text) else ""))
                }
            }
        }
        return out
    }

    /** How many lines [a] and [b] have in common, as multisets. */
    private fun sameLines(a: String, b: String): Int {
        val counts = HashMap<String, Int>()
        for (l in a.lines()) counts.merge(l, 1, Int::plus)
        var n = 0
        for (l in b.lines()) {
            val c = counts[l] ?: 0
            if (c > 0) {
                counts[l] = c - 1
                n++
            }
        }
        return n
    }

    /** The first lines where [a] and [b] differ, with two lines of context. */
    private fun firstDifference(a: String, b: String): String {
        val la = a.lines()
        val lb = b.lines()
        var k = 0
        while (k < la.size && k < lb.size && la[k] == lb[k]) k++
        val from = maxOf(0, k - 2)
        val sb = StringBuilder()
        for (j in from until minOf(la.size, k + 3)) sb.append("    - ").append(la[j].trim()).append('\n')
        for (j in from until minOf(lb.size, k + 3)) sb.append("    + ").append(lb[j].trim()).append('\n')
        return sb.toString().trimEnd()
    }
}
