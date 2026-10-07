package dev.mooner.neonjs.ext

import dev.mooner.neonjs.NeonConsole
import dev.mooner.neonjs.NeonConsole.Level
import dev.mooner.neonjs.runtime.*
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The `console` namespace object. All formatting is done by [Inspector], which never calls back into guest code,
 * so logging an object cannot trigger getters, proxies or toString overrides.
 */
object ConsoleBuiltins {
    /** Upper bound for a single message, so a script cannot make the host buffer unbounded output in one call. */
    const val MAX_MESSAGE = 1 shl 20

    private class State(val sink: NeonConsole) {
        var indent = 0
        val counts = HashMap<String, Long>()
        val timers = HashMap<String, Double>()
    }

    fun install(realm: Realm, sink: NeonConsole) {
        val st = State(sink)
        val console = JSObject(realm.objectPrototype)
        fun emit(level: Level, text: String) {
            val msg = if (text.length > MAX_MESSAGE) text.substring(0, MAX_MESSAGE) + "... <truncated>" else text
            val out = if (st.indent == 0) msg else {
                val pad = " ".repeat(st.indent * 2)
                msg.lineSequence().joinToString("\n") { pad + it }
            }
            st.sink.write(level, out)
        }
        fun def(name: String, length: Int, impl: (Array<Any?>) -> Unit) {
            console.defineOwn(name, NativeFunction(realm, name, length, { _, _, a, _ -> impl(a); Undefined }), Attr.ALL)
        }
        fun label(a: Array<Any?>): String = if (a.isEmpty() || a[0] === Undefined) "default" else Ops.toString(a[0])

        def("log", 0) { emit(Level.LOG, format(it)) }
        def("info", 0) { emit(Level.INFO, format(it)) }
        def("debug", 0) { emit(Level.DEBUG, format(it)) }
        def("warn", 0) { emit(Level.WARN, format(it)) }
        def("error", 0) { emit(Level.ERROR, format(it)) }
        def("trace", 0) {
            val head = if (it.isEmpty()) "Trace" else "Trace: " + format(it)
            emit(Level.ERROR, head + "\n" + realm.agent.captureStack().trimEnd())
        }
        def("dir", 0) { emit(Level.LOG, Inspector.inspect(it.arg(0))) }
        def("assert", 0) {
            if (!Ops.toBoolean(it.arg(0))) {
                val rest = it.copyOfRange(minOf(1, it.size), it.size)
                emit(Level.ERROR, if (rest.isEmpty()) "Assertion failed" else "Assertion failed: " + format(rest))
            }
        }
        def("count", 0) {
            val l = label(it)
            val n = (st.counts[l] ?: 0L) + 1
            st.counts[l] = n
            emit(Level.INFO, "$l: $n")
        }
        def("countReset", 0) { st.counts.remove(label(it)) }
        def("time", 0) {
            val l = label(it)
            if (l in st.timers) emit(Level.WARN, "Warning: Label '$l' already exists for console.time()")
            else st.timers[l] = realm.agent.currentTimeMillis()
        }
        fun elapsed(l: String): String? {
            val t0 = st.timers[l] ?: run { emit(Level.WARN, "Warning: No such label '$l' for console.timeEnd()"); return null }
            return "$l: ${NumberConv.toString(realm.agent.currentTimeMillis() - t0)}ms"
        }
        def("timeEnd", 0) { a -> val l = label(a); elapsed(l)?.let { emit(Level.INFO, it) }; st.timers.remove(l) }
        def("timeLog", 0) { a ->
            elapsed(label(a))?.let { s ->
                val rest = a.copyOfRange(minOf(1, a.size), a.size)
                emit(Level.INFO, if (rest.isEmpty()) s else s + " " + format(rest))
            }
        }
        def("group", 0) { if (it.isNotEmpty()) emit(Level.LOG, format(it)); st.indent++ }
        def("groupCollapsed", 0) { if (it.isNotEmpty()) emit(Level.LOG, format(it)); st.indent++ }
        def("groupEnd", 0) { if (st.indent > 0) st.indent-- }
        def("table", 1) { emit(Level.LOG, table(it.arg(0)) ?: format(it)) }
        console.defineOwn(JSSymbol.toStringTag, "console", Attr.CONFIGURABLE)
        realm.globalObject.defineOwn("console", console, Attr.WC)
    }

    /** Node-style util.format: `%s %d %i %f %j %o %O %c %%` in a leading string, remaining arguments inspected. */
    fun format(args: Array<Any?>): String {
        if (args.isEmpty()) return ""
        val sb = StringBuilder()
        var next: Int
        val first = args[0]
        if (first is CharSequence && args.size > 1) {
            val f = first.toString()
            next = 1
            var i = 0
            while (i < f.length) {
                val c = f[i]
                if (c == '%' && i + 1 < f.length) {
                    val s = f[i + 1]
                    if (s == '%') { sb.append('%'); i += 2; continue }
                    if (s in "sdifjoOc" && next < args.size) {
                        val a = args[next++]
                        when (s) {
                            's' -> sb.append(if (a is JSObject) Inspector.inspect(a, 1) else if (a is BigInteger) "${a}n" else Inspector.display(a))
                            'd', 'i' -> sb.append(numberSpec(a, s == 'i'))
                            'f' -> sb.append(if (a is JSObject || a is JSSymbol) "NaN" else NumberConv.toString(safeNumber(a)))
                            'j' -> sb.append(Inspector.inspect(a, 4))
                            'o' -> sb.append(Inspector.inspect(a, 4))
                            'O' -> sb.append(Inspector.inspect(a))
                            'c' -> {}
                        }
                        i += 2
                        if (sb.length > MAX_MESSAGE) return sb.toString()
                        continue
                    }
                }
                sb.append(c)
                i++
            }
        } else {
            sb.append(Inspector.display(first))
            next = 1
        }
        while (next < args.size) {
            sb.append(' ').append(Inspector.display(args[next++]))
            if (sb.length > MAX_MESSAGE) break
        }
        return sb.toString()
    }

    private fun safeNumber(a: Any?): Double = when (a) {
        is Double -> a
        is Boolean -> if (a) 1.0 else 0.0
        is CharSequence -> NumberConv.stringToNumber(a.toString())
        Null -> 0.0
        is BigInteger -> a.toDouble()
        else -> Double.NaN
    }

    private fun numberSpec(a: Any?, integer: Boolean): String {
        if (a is BigInteger) return "${a}n"
        if (a is JSObject || a is JSSymbol) return "NaN"
        val d = safeNumber(a)
        return NumberConv.toString(if (integer && d.isFinite()) (if (d < 0) ceil(d) else floor(d)) else d)
    }

    /** Renders arrays / objects of rows as a box table using own enumerable data properties only. */
    private fun table(data: Any?): String? {
        if (data !is JSObject || data is ProxyObject || data.isCallable) return null
        val rowKeys = try { data.ownPropertyKeys().filter { it !is JSSymbol && data.getOwnProperty(it)?.enumerable == true } } catch (_: JSException) { return null }
        if (rowKeys.size > 1000) return null
        val cols = LinkedHashSet<String>()
        var hasValues = false
        val rows = rowKeys.map { k ->
            val d = data.getOwnProperty(k)
            val v = if (d == null || d.isAccessor) Undefined else d.value
            val cells = HashMap<String, String>()
            if (v is JSObject && v !is ProxyObject && !v.isCallable) {
                for (ck in v.ownPropertyKeys()) {
                    if (ck is JSSymbol) continue
                    val cd = v.getOwnProperty(ck) ?: continue
                    if (!cd.enumerable) continue
                    val name = PK.toValue(ck).toString()
                    cols.add(name)
                    cells[name] = if (cd.isAccessor) "[Getter]" else Inspector.inspect(cd.value, 0)
                }
            } else {
                hasValues = true
                cells["Values"] = Inspector.inspect(v, 0)
            }
            PK.toValue(k).toString() to cells
        }
        val header = ArrayList<String>()
        header.add("(index)")
        header.addAll(cols)
        if (hasValues) header.add("Values")
        val widths = header.map { it.length + 2 }.toIntArray()
        for ((idx, cells) in rows) {
            widths[0] = maxOf(widths[0], idx.length + 2)
            for (c in 1 until header.size) widths[c] = maxOf(widths[c], (cells[header[c]]?.length ?: 0) + 2)
        }
        val sb = StringBuilder()
        fun line(l: Char, m: Char, r: Char) {
            sb.append(l)
            for (c in header.indices) { repeat(widths[c]) { sb.append('─') }; sb.append(if (c == header.size - 1) r else m) }
            sb.append('\n')
        }
        fun row(cells: List<String>) {
            sb.append('│')
            for (c in header.indices) {
                val t = cells[c]
                val total = widths[c] - t.length
                val left = total / 2
                repeat(left) { sb.append(' ') }
                sb.append(t)
                repeat(total - left) { sb.append(' ') }
                sb.append('│')
            }
            sb.append('\n')
        }
        line('┌', '┬', '┐')
        row(header)
        line('├', '┼', '┤')
        for ((idx, cells) in rows) row(listOf(idx) + header.drop(1).map { cells[it] ?: "" })
        line('└', '┴', '┘')
        return sb.toString().trimEnd('\n')
    }
}
