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
 * Usage: `ClassDiffKt BEFORE AFTER [--show N]`; exit status 0 when equal, 1 otherwise, with the first N differences
 * printed (a class only on one side, or the first lines where its code differs).
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: ClassDiffKt BEFORE AFTER [--show N]")
        exitProcess(2)
    }
    val show = args.indexOf("--show").let { if (it >= 0) args[it + 1].toInt() else 5 }
    val before = ClassDiff.load(File(args[0]))
    val after = ClassDiff.load(File(args[1]))
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

    /** The classes of [dir], by name without counter: each class's entry (several where the counter told them apart). */
    fun load(dir: File): Map<String, List<Entry>> {
        val out = HashMap<String, ArrayList<Entry>>()
        val files = dir.listFiles { f -> f.name.endsWith(".class") } ?: throw IllegalArgumentException("not a directory: $dir")
        for (f in files) {
            val sw = StringWriter()
            ClassReader(f.readBytes()).accept(TraceClassVisitor(null, Textifier(), PrintWriter(sw)), ClassReader.SKIP_DEBUG)
            val text = normalize(sw.toString())
            out.getOrPut(normalize(f.name.removeSuffix(".class"))) { ArrayList() }.add(Entry(text))
        }
        for (l in out.values) l.sortBy { it.digest }
        return out
    }

    fun normalize(s: String): String = counter.replace(s) { it.groupValues[1] }

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
