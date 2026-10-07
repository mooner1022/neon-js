package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.JSMapObject
import dev.mooner.neonjs.interop.HostClassObject
import dev.mooner.neonjs.interop.HostObject
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.JSClosure
import dev.mooner.neonjs.vm.JSPromise
import java.math.BigInteger

/**
 * Node-like value formatting for console output and the REPL. Never invokes user code (getters, toString,
 * proxies are not triggered), so it is safe to use on untrusted values.
 */
object Inspector {
    fun inspect(v: Any?, depth: Int = 2): String {
        val sb = StringBuilder()
        format(v, sb, depth, HashSet())
        return sb.toString()
    }

    /** Formatting for console.log arguments: strings are printed raw at top level. */
    fun display(v: Any?): String = if (v is CharSequence) v.toString() else inspect(v)

    private fun quote(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\'' -> sb.append("\\'")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> if (c < ' ') sb.append(String.format("\\x%02x", c.code)) else sb.append(c)
            }
        }
        return sb.append('\'').toString()
    }

    private fun keyText(k: Any): String = when (k) {
        is JSSymbol -> "[${k}]"
        is Int -> k.toString()
        else -> {
            val s = k.toString()
            if (s.isNotEmpty() && (s[0].isLetter() || s[0] == '_' || s[0] == '$') && s.all { it.isLetterOrDigit() || it == '_' || it == '$' }) s else quote(s)
        }
    }

    private fun ownData(o: JSObject, key: Any): Any? {
        val pm = o.props ?: return NotFound
        val i = pm.find(key)
        if (i < 0) return NotFound
        // an accessor's record itself (never called): see [accessorText]
        return pm.values[i]
    }

    private fun accessorText(a: Accessor): String {
        val g = a.getter !== Undefined && a.getter != null
        val s = a.setter !== Undefined && a.setter != null
        return if (g && s) "[Getter/Setter]" else if (g) "[Getter]" else "[Setter]"
    }

    private fun format(v: Any?, sb: StringBuilder, depth: Int, seen: MutableSet<Any>) {
        when (v) {
            Undefined, null -> sb.append("undefined")
            Null -> sb.append("null")
            is Accessor -> sb.append(accessorText(v))
            is Boolean -> sb.append(v)
            is Double -> sb.append(if (v == 0.0 && 1.0 / v < 0) "-0" else NumberConv.toString(v))
            is CharSequence -> sb.append(quote(v.toString()))
            is BigInteger -> sb.append(v).append('n')
            is JSSymbol -> sb.append(v.toString())
            is HostObject -> sb.append("[Host ").append(v.target.javaClass.simpleName).append(": ").append(safeToString(v.target)).append(']')
            is HostClassObject -> sb.append("[HostClass ").append(v.cls.name).append(']')
            is ProxyObject -> sb.append("Proxy {}")
            is JSObject -> formatObject(v, sb, depth, seen)
            else -> sb.append(v.toString())
        }
    }

    private fun safeToString(o: Any): String = try { o.toString().take(200) } catch (_: Exception) { "?" }

    private fun formatObject(o: JSObject, sb: StringBuilder, depth: Int, seen: MutableSet<Any>) {
        if (!seen.add(o)) {
            sb.append("[Circular]")
            return
        }
        try {
            if (o.isCallable) {
                val name = (o as? JSFunction)?.debugName() ?: ""
                val isClass = o is JSClosure && o.code.isClassConstructor
                sb.append(if (isClass) "[class " else "[Function")
                if (isClass) sb.append(name.ifEmpty { "(anonymous)" }) else if (name.isNotEmpty()) sb.append(": ").append(name) else sb.append(" (anonymous)")
                sb.append(']')
                return
            }
            if (o is JSErrorObject) {
                val stack = dev.mooner.neonjs.builtins.ErrorBuiltins.stackString(o)
                sb.append(stack)
                return
            }
            if (o is JSPromise) {
                sb.append("Promise { ")
                when (o.state) {
                    JSPromise.PENDING -> sb.append("<pending>")
                    JSPromise.FULFILLED -> format(o.result, sb, depth - 1, seen)
                    else -> { sb.append("<rejected> "); format(o.result, sb, depth - 1, seen) }
                }
                sb.append(" }")
                return
            }
            if (o is JSPrimitiveWrapper) {
                sb.append('[').append(if (o.primitive is Double) "Number" else if (o.primitive is Boolean) "Boolean" else "Object").append(": ")
                format(o.primitive, sb, depth, seen)
                sb.append(']')
                return
            }
            if (o is JSStringObject) {
                sb.append("[String: ").append(quote(o.value)).append(']')
                return
            }
            if (o is JSMapObject) {
                sb.append(if (o.isSet) "Set(" else "Map(").append(o.table.size).append(") {")
                if (depth < 0) { sb.append(" ... }"); return }
                var first = true
                var n = 0
                o.table.forEachLive { k, v ->
                    if (n++ >= 100) return@forEachLive
                    sb.append(if (first) " " else ", ")
                    first = false
                    format(k, sb, depth - 1, seen)
                    if (!o.isSet) {
                        sb.append(" => ")
                        format(v, sb, depth - 1, seen)
                    }
                }
                sb.append(if (first) "}" else " }")
                return
            }
            if (o is JSArray) {
                if (depth < 0) { sb.append("[Array]"); return }
                sb.append('[')
                val n = o.length
                var holes = 0
                var shown = 0
                for (i in 0 until minOf(n, 100)) {
                    val v = ownData(o, i.toInt()).let { if (it === NotFound) o.getOwnValue(i.toInt(), o) else it }
                    if (v === NotFound) { holes++; continue }
                    if (shown++ > 0 || holes > 0) sb.append(", ") else sb.append(' ')
                    if (holes > 0) {
                        sb.append("<").append(holes).append(" empty item").append(if (holes > 1) "s" else "").append(">, ")
                        holes = 0
                    }
                    format(v, sb, depth - 1, seen)
                }
                if (holes > 0) sb.append(if (shown > 0) ", " else " ").append("<").append(holes).append(" empty item").append(if (holes > 1) "s" else "").append(">")
                if (n > 100) sb.append(", ... ").append(n - 100).append(" more items")
                sb.append(if (n == 0L) "]" else " ]")
                return
            }
            if (depth < 0) { sb.append("[Object]"); return }
            val tag = (ownData(o, JSSymbol.toStringTag).takeIf { it is CharSequence })?.toString()
            if (o.proto == null) sb.append("[Object: null prototype] ")
            else if (tag != null) sb.append("Object [").append(tag).append("] ")
            sb.append('{')
            var first = true
            var count = 0
            val keys = try { o.ownPropertyKeys() } catch (_: JSException) { emptyList() }
            for (k in keys) {
                // module namespaces throw for bindings still in TDZ
                val d = try { o.getOwnProperty(k) } catch (_: JSException) { null } ?: continue
                if (!d.enumerable) continue
                if (count++ >= 100) { sb.append(", ..."); break }
                sb.append(if (first) " " else ", ")
                first = false
                sb.append(keyText(k)).append(": ")
                if (d.isAccessor) sb.append(if (d.getter !== Undefined && d.setter !== Undefined) "[Getter/Setter]" else if (d.getter !== Undefined) "[Getter]" else "[Setter]")
                else format(d.value, sb, depth - 1, seen)
            }
            sb.append(if (first) "}" else " }")
        } finally {
            seen.remove(o)
        }
    }
}
