package dev.mooner.neonjs.test262

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy

/** The host object the [InteropBench] cases call. */
class BenchHolder {
    @JvmField var count = 7
    private val big = ArrayList<Int>().apply { for (i in 0 until 1000) add(i) }

    fun getName() = "n"
    /** A new object every time. */
    fun make() = BenchItem()
    fun add(a: Int, b: Int) = a + b
    /** The same 1000-element list every time. */
    fun big(): List<Int> = big
    fun over(s: String) = 1
    fun over(s: CharSequence) = 2
    fun over(o: Any?) = 3
    fun over(i: Int) = 4
    fun over(l: Long) = 5
    fun over(d: Double) = 6
}

/** What [BenchHolder.make] returns. */
class BenchItem {
    fun getName() = "n"
}

private val BENCH_JS = """
    var SB = Java.type('java.lang.StringBuilder');
    var Collections = Java.type('java.util.Collections');
    var ArrayList = Java.type('java.util.ArrayList');
    function jsOnly(n) { var t = 0; for (var i = 0; i < n; i++) t += i & 1; return t; }
    function field(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.count; return t; }
    function fieldSet(n) { for (var i = 0; i < n; i++) holder.count = i & 7; return holder.count; }
    function getter(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.getName().length; return t; }
    function size(n) { var t = 0; for (var i = 0; i < n; i++) t += list.size(); return t; }
    function get(n) { var t = 0; for (var i = 0; i < n; i++) t += list.get(i % 1000); return t; }
    function add(n) { var t = 0; for (var i = 0; i < n; i++) t = holder.add(t & 1023, 1); return t; }
    function overStr(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.over('x'); return t; }
    function overInt(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.over(i); return t; }
    function overDbl(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.over(i + 0.5); return t; }
    function mathMax(n) { var M = Java.type('java.lang.Math'); var t = 0; for (var i = 0; i < n; i++) t += M.max(i, 1); return t; }
    function mapGet(n) { var t = 0; for (var i = 0; i < n; i++) t += map.get('k') ? 1 : 0; return t; }
    function wrapNew(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.make() ? 1 : 0; return t; }
    function fresh(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.make().getName().length; return t; }
    function bigList(n) { var t = 0; for (var i = 0; i < n; i++) t += holder.big().size(); return t; }
    function javaType(n) { var t = 0; for (var i = 0; i < n; i++) t += Java.type('java.lang.StringBuilder') ? 1 : 0; return t; }
    function sortCb(n) {
        var t = 0;
        for (var r = 0; r < n / 10000; r++) {
            var a = new ArrayList();
            for (var i = 0; i < 1000; i++) a.add((i * 7919) % 1000);
            Collections.sort(a, (x, y) => x - y);
            t += a.get(0);
        }
        return t;
    }
    function append(n) { var sb = new SB(); for (var i = 0; i < n; i++) { sb.append('x'); if (i % 1000 == 999) sb.setLength(0); } return sb.length(); }
    function appendInt(n) { var sb = new SB(); for (var i = 0; i < n; i++) { sb.append(i); if (i % 1000 == 999) sb.setLength(0); } return sb.length(); }
    function newSB(n) { var t = 0; for (var i = 0; i < n; i++) t += new SB().length(); return t; }
""".trimIndent()

private val BENCH_CASES = listOf(
    "jsOnly", "field", "fieldSet", "getter", "size", "get", "add", "overStr", "overInt", "overDbl", "mathMax", "mapGet",
    "wrapNew", "fresh", "bigList", "javaType", "sortCb", "append", "appendInt", "newSB",
)

/**
 * Host interop micro-benchmarks: ns per operation (the median of the rounds) for field reads and writes, calls with
 * and without overload choice, wrapping returned objects, `Java.type`, and host code calling a JS function. Arguments:
 * `[-n OPS] [-rounds R] [NAME...]` (names select cases by substring). Runs on the JVM and on Android (see the README).
 */
object InteropBench {
    @JvmStatic
    fun main(args: Array<String>) {
        var n = 200_000
        var rounds = 7
        val only = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-n" -> n = args[++i].toInt()
                "-rounds" -> rounds = args[++i].toInt()
                else -> only.add(args[i])
            }
            i++
        }
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        NeonEngine.builder().hostAccess(access).console(null)
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build().newContext().use { c ->
                c["list"] = ArrayList<Int>().apply { for (k in 0 until 1000) add(k) }
                c["map"] = hashMapOf<String, Any?>("k" to arrayListOf(1, 2, 3))
                c["holder"] = BenchHolder()
                c.eval(BENCH_JS, "<bench>")
                for (name in BENCH_CASES) {
                    if (only.isNotEmpty() && only.none { name.contains(it) }) continue
                    val f = c[name]
                    try {
                        repeat(10) { f.call(n.toDouble()) }
                    } catch (e: Exception) {
                        println("%-10s FAILS %s".format(name, e.message))
                        continue
                    }
                    val t = LongArray(rounds) {
                        val s = System.nanoTime()
                        f.call(n.toDouble())
                        System.nanoTime() - s
                    }
                    t.sort()
                    println("%-10s %9.1f ns/op".format(name, t[rounds / 2] / n.toDouble()))
                }
            }
    }
}
