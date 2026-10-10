package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ElementType
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.TypedArrayBuiltins
import dev.mooner.neonjs.builtins.getter
import dev.mooner.neonjs.builtins.global
import dev.mooner.neonjs.builtins.makeCtor
import dev.mooner.neonjs.builtins.method
import dev.mooner.neonjs.builtins.rangeErr
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in web platform globals that embedded scripts commonly expect (not part of ECMAScript): `queueMicrotask`,
 * `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval`, `structuredClone`, `DOMException`, `atob` /
 * `btoa`, UTF-8 `TextEncoder` / `TextDecoder`, `Event` / `CustomEvent` / `EventTarget` ([Events]) and
 * `AbortController` / `AbortSignal` ([Abort]), `performance` ([Performance]) and `crypto.getRandomValues` /
 * `randomUUID` ([Crypto]), `Blob` / `File` ([Blob]).
 */
object WebGlobals {
    fun install(realm: Realm, maxTimers: Int) {
        Performance.startClock(realm)
        WebInterface.globalOperation(realm, "queueMicrotask", 1) { _, _, a, _ ->
            val cb = a.arg(0)
            if (!Ops.isCallable(cb)) typeErr("queueMicrotask requires a function")
            realm.agent.enqueueJob { Ops.call(cb, Undefined, EMPTY_ARGS) }
            Undefined
        }
        installDOMException(realm)
        WebInterface.globalOperation(realm, "structuredClone", 1) { f, _, a, _ ->
            if (a.isEmpty()) typeErr("structuredClone requires 1 argument")
            StructuredClone(f.realm).run(a[0], StructuredClone.transferList(f.realm, a.arg(1)))
        }
        installTimers(realm, maxTimers)
        Events.install(realm)
        Abort.install(realm)
        Performance.install(realm)
        Crypto.install(realm)
        Blob.install(realm)
        installBase64(realm)
        installTextEncoder(realm)
        installTextDecoder(realm)
    }

    // ------------------------------------------------------------------ DOMException

    /** A DOMException: an error object (so `Error.isError` holds) whose name and message are internal slots. */
    internal open class JSDOMException(proto: JSObject?, @JvmField val excName: String, @JvmField val excMessage: String) : JSErrorObject(proto) {
        override val className: String get() = "DOMException"
        override val slotName: String get() = excName
        override val slotMessage: String get() = excMessage
    }

    /** The legacy `code` of each DOMException name (WebIDL). */
    private val LEGACY_CODES = linkedMapOf(
        "IndexSizeError" to ("INDEX_SIZE_ERR" to 1), "HierarchyRequestError" to ("HIERARCHY_REQUEST_ERR" to 3),
        "WrongDocumentError" to ("WRONG_DOCUMENT_ERR" to 4), "InvalidCharacterError" to ("INVALID_CHARACTER_ERR" to 5),
        "NoModificationAllowedError" to ("NO_MODIFICATION_ALLOWED_ERR" to 7), "NotFoundError" to ("NOT_FOUND_ERR" to 8),
        "NotSupportedError" to ("NOT_SUPPORTED_ERR" to 9), "InUseAttributeError" to ("INUSE_ATTRIBUTE_ERR" to 10),
        "InvalidStateError" to ("INVALID_STATE_ERR" to 11), "SyntaxError" to ("SYNTAX_ERR" to 12),
        "InvalidModificationError" to ("INVALID_MODIFICATION_ERR" to 13), "NamespaceError" to ("NAMESPACE_ERR" to 14),
        "InvalidAccessError" to ("INVALID_ACCESS_ERR" to 15), "TypeMismatchError" to ("TYPE_MISMATCH_ERR" to 17),
        "SecurityError" to ("SECURITY_ERR" to 18), "NetworkError" to ("NETWORK_ERR" to 19), "AbortError" to ("ABORT_ERR" to 20),
        "URLMismatchError" to ("URL_MISMATCH_ERR" to 21), "QuotaExceededError" to ("QUOTA_EXCEEDED_ERR" to 22),
        "TimeoutError" to ("TIMEOUT_ERR" to 23), "InvalidNodeTypeError" to ("INVALID_NODE_TYPE_ERR" to 24),
        "DataCloneError" to ("DATA_CLONE_ERR" to 25),
    )
    private val EXTRA_CONSTANTS = listOf("DOMSTRING_SIZE_ERR" to 2, "NO_DATA_ALLOWED_ERR" to 6, "VALIDATION_ERR" to 16)

    internal fun newDOMException(realm: Realm, message: String, name: String): JSDOMException {
        val e = JSDOMException(realm.intrinsic("%DOMException.prototype%"), name, message)
        if (realm.agent.topFrame != null) e.stackTrace = realm.agent.captureStack()
        return e
    }

    /** A QuotaExceededError (WebIDL): a DOMException with the quota and the amount requested, when known. */
    internal class JSQuotaExceededError(proto: JSObject?, message: String, @JvmField val quota: Double?, @JvmField val requested: Double?) :
        JSDOMException(proto, "QuotaExceededError", message) {
        override val className: String get() = "QuotaExceededError"
    }

    internal fun newQuotaExceededError(realm: Realm, message: String): JSQuotaExceededError {
        val e = JSQuotaExceededError(realm.intrinsic("%QuotaExceededError.prototype%"), message, null, null)
        if (realm.agent.topFrame != null) e.stackTrace = realm.agent.captureStack()
        return e
    }

    /** A throwable DOMException of [realm] (e.g. "DataCloneError", "InvalidCharacterError"). */
    internal fun domException(realm: Realm, message: String, name: String): JSException = JSException(newDOMException(realm, message, name))

    private fun installDOMException(realm: Realm) {
        val i = WebInterface.define(realm, "DOMException", 0, protoParent = realm.errorPrototype) { a, proto ->
            val message = if (a.arg(0) === Undefined) "" else Idl.domString(a.arg(0))
            val name = if (a.arg(1) === Undefined) "Error" else Idl.domString(a.arg(1))
            val e = JSDOMException(proto, name, message)
            if (realm.agent.topFrame != null) e.stackTrace = realm.agent.captureStack()
            e
        }
        i.attribute("name", { _, t, _, _ -> Idl.self<JSDOMException>(t, "DOMException", "name").excName })
        i.attribute("message", { _, t, _, _ -> Idl.self<JSDOMException>(t, "DOMException", "message").excMessage })
        i.attribute("code", { _, t, _, _ -> (LEGACY_CODES[Idl.self<JSDOMException>(t, "DOMException", "code").excName]?.second ?: 0).toDouble() })
        for ((n, c) in (LEGACY_CODES.values.toList() + EXTRA_CONSTANTS).sortedBy { it.second }) i.constant(n, c.toDouble())

        val q = WebInterface.define(realm, "QuotaExceededError", 0, parent = "DOMException") { a, proto ->
            val message = if (a.arg(0) === Undefined) "" else Idl.domString(a.arg(0))
            val o = Idl.dictionary(a.arg(1), "QuotaExceededErrorOptions")
            val quota = Idl.member(o, "quota").let { if (it === Undefined) null else Idl.double(it, "quota") }
            val requested = Idl.member(o, "requested").let { if (it === Undefined) null else Idl.double(it, "requested") }
            if (quota != null && quota < 0) rangeErr("QuotaExceededError: quota is negative")
            if (requested != null && requested < 0) rangeErr("QuotaExceededError: requested is negative")
            if (quota != null && requested != null && requested < quota) rangeErr("QuotaExceededError: requested is less than quota")
            val e = JSQuotaExceededError(proto, message, quota, requested)
            if (realm.agent.topFrame != null) e.stackTrace = realm.agent.captureStack()
            e
        }
        q.attribute("quota", { _, t, _, _ -> Idl.self<JSQuotaExceededError>(t, "QuotaExceededError", "quota").quota ?: Null })
        q.attribute("requested", { _, t, _, _ -> Idl.self<JSQuotaExceededError>(t, "QuotaExceededError", "requested").requested ?: Null })
    }

    // ------------------------------------------------------------------ timers

    /** One daemon thread for all contexts; it only posts jobs, guest code always runs on the context's thread. */
    private val scheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "neonjs-timers").also { it.isDaemon = true } }.also {
            it.removeOnCancelPolicy = true
            it.executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    /** Number of timer tasks queued in the shared scheduler (for tests). */
    internal val scheduledTaskCount: Int get() = scheduler.queue.size

    /** A task scheduled with [Timers.task]; cancelling it (or closing the context) withdraws it. */
    internal class ScheduledTask : Agent.ExternalSource {
        @Volatile @JvmField var future: ScheduledFuture<*>? = null
        @Volatile @JvmField var cancelled = false

        override fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }

    /** The timers of [realm] (installed with the web globals). */
    internal fun timersOf(realm: Realm): Timers = realm.intrinsicsAny["%Timers%"] as Timers

    /** A pending timer; registered with the agent as an external source until it fires for the last time. */
    internal class Timer(@JvmField val id: Int, @JvmField val fn: Any?, @JvmField val args: Array<Any?>, @JvmField val interval: Long) :
        Agent.ExternalSource {
        @Volatile @JvmField var future: ScheduledFuture<*>? = null
        @Volatile @JvmField var cancelled = false

        override fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }

    /**
     * The timers of a realm: `setTimeout` / `setInterval`, and the tasks other APIs schedule ([task]), within one limit
     * of pending timers ([SandboxPolicy.maxTimers][dev.mooner.neonjs.SandboxPolicy.maxTimers]).
     */
    internal class Timers(val realm: Realm, val max: Int) {
        val active = HashMap<Int, Timer>()
        var nextId = 1
        /** Pending tasks of [task]. */
        private var tasks = 0

        /**
         * Runs [job] as a task of the realm's agent after [ms] milliseconds. With [keepsAlive] false the pending task
         * does not keep an event loop waiting (as Node's unref'd timers). Null after the agent closed.
         */
        fun task(ms: Long, keepsAlive: Boolean, job: Runnable): ScheduledTask? {
            if (active.size + tasks >= max) rangeErr("Too many active timers (limit $max)")
            val agent = realm.agent
            val t = ScheduledTask()
            if (!agent.addExternalSource(t, keepsAlive)) return null
            tasks++
            t.future = scheduler.schedule({
                agent.postExternalJob {
                    tasks--
                    agent.removeExternalSource(t)
                    if (!t.cancelled) job.run()
                }
            }, ms, TimeUnit.MILLISECONDS)
            if (t.cancelled) t.future?.cancel(false)
            return t
        }

        fun start(name: String, fn: Any?, delay: Any?, args: Array<Any?>, repeat: Boolean): Any? {
            if (!Ops.isCallable(fn)) typeErr("$name requires a function (string callbacks are not supported)")
            val ms = delayOf(delay)
            if (active.size + tasks >= max) rangeErr("Too many active timers (limit $max)")
            var id = nextId
            while (active.containsKey(id)) id = if (id == Int.MAX_VALUE) 1 else id + 1
            nextId = if (id == Int.MAX_VALUE) 1 else id + 1
            val t = Timer(id, fn, args, if (repeat) ms else -1L)
            if (!realm.agent.addExternalSource(t)) return id.toDouble()
            active[id] = t
            schedule(t, ms)
            return id.toDouble()
        }

        private fun schedule(t: Timer, ms: Long) {
            val agent = realm.agent
            t.future = scheduler.schedule({ agent.postExternalJob { fire(t) } }, ms, TimeUnit.MILLISECONDS)
            if (t.cancelled) t.future?.cancel(false)
        }

        private fun stop(t: Timer) {
            t.cancel()
            if (active[t.id] === t) active.remove(t.id)
            realm.agent.removeExternalSource(t)
        }

        /** Runs on the context's thread, as a job. */
        private fun fire(t: Timer) {
            if (t.cancelled) return
            if (t.interval < 0) {
                stop(t)
                Ops.call(t.fn, Undefined, t.args)
                return
            }
            try {
                Ops.call(t.fn, Undefined, t.args)
            } catch (e: JSException) {
                // like the web platform: an exception does not end the interval
                if (!t.cancelled) schedule(t, t.interval)
                throw e
            } catch (e: Throwable) {
                stop(t)
                throw e
            }
            if (!t.cancelled) schedule(t, t.interval)
        }

        fun clear(handle: Any?) {
            if (handle === Undefined || handle === Null) return
            val d = Ops.toNumber(handle)
            if (d.isNaN() || d < 1 || d > Int.MAX_VALUE) return
            val t = active[d.toInt()] ?: return
            stop(t)
        }

        private fun delayOf(v: Any?): Long {
            val d = if (v === Undefined) 0.0 else Ops.toNumber(v)
            return if (d.isNaN() || d < 1) 0L else minOf(d, Int.MAX_VALUE.toDouble()).toLong()
        }
    }

    private fun installTimers(realm: Realm, maxTimers: Int) {
        val timers = Timers(realm, maxTimers)
        realm.intrinsicsAny["%Timers%"] = timers
        fun starter(name: String, repeat: Boolean) = NativeFunction(realm, name, 1, { _, _, a, _ ->
            timers.start(name, a.arg(0), a.arg(1), if (a.size > 2) a.copyOfRange(2, a.size) else EMPTY_ARGS, repeat)
        })
        fun clearer(name: String) = NativeFunction(realm, name, 0, { _, _, a, _ ->
            timers.clear(a.arg(0))
            Undefined
        })
        for (f in listOf(starter("setTimeout", false), starter("setInterval", true), clearer("clearTimeout"), clearer("clearInterval")))
            realm.global(f.debugName(), f, Attr.ALL)
    }

    // ------------------------------------------------------------------ atob / btoa

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun installBase64(realm: Realm) {
        WebInterface.globalOperation(realm, "btoa", 1) { f, _, a, _ ->
            Idl.required(a, 1, "btoa")
            val s = Idl.domString(a[0])
            val bytes = ByteArray(s.length)
            for (i in s.indices) {
                val c = s[i]
                if (c.code > 0xFF) throw domException(f.realm, "The string to be encoded contains characters outside of the Latin1 range.", "InvalidCharacterError")
                bytes[i] = c.code.toByte()
            }
            f.realm.agent.checkStringLength((s.length + 2L) / 3 * 4)
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
        WebInterface.globalOperation(realm, "atob", 1) { f, _, a, _ ->
            Idl.required(a, 1, "atob")
            forgivingBase64Decode(Idl.domString(a[0]))
                ?: throw domException(f.realm, "The string to be decoded is not correctly encoded.", "InvalidCharacterError")
        }
    }

    /** The forgiving-base64 decode of the Infra standard, to a binary string; null on failure. */
    internal fun forgivingBase64Decode(input: String): String? {
        val sb = StringBuilder(input.length)
        for (c in input) if (c != ' ' && c != '\t' && c != '\n' && c != '\u000C' && c != '\r') sb.append(c)
        var len = sb.length
        if (len % 4 == 0) {
            if (len > 0 && sb[len - 1] == '=') len--
            if (len > 0 && sb[len - 1] == '=') len--
        }
        if (len % 4 == 1) return null
        val out = StringBuilder(len * 3 / 4)
        var buffer = 0
        var bits = 0
        for (i in 0 until len) {
            val v = B64.indexOf(sb[i])
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ TextEncoder

    private class JSTextEncoder(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "TextEncoder"
    }

    /** UTF-8 encoding of [s], unpaired surrogates encoded as U+FFFD. */
    internal fun utf8(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length + 16)
        var i = 0
        while (i < s.length) {
            val cp = codePointOrReplacement(s, i)
            i += if (cp >= 0x10000) 2 else 1
            writeUtf8(out, cp)
        }
        return out.toByteArray()
    }

    private fun codePointOrReplacement(s: String, i: Int): Int {
        val c = s[i]
        if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) return Character.toCodePoint(c, s[i + 1])
        return if (Character.isSurrogate(c)) 0xFFFD else c.code
    }

    private fun utf8Length(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun writeUtf8(out: java.io.ByteArrayOutputStream, cp: Int) {
        when (utf8Length(cp)) {
            1 -> out.write(cp)
            2 -> { out.write(0xC0 or (cp shr 6)); out.write(0x80 or (cp and 0x3F)) }
            3 -> { out.write(0xE0 or (cp shr 12)); out.write(0x80 or ((cp shr 6) and 0x3F)); out.write(0x80 or (cp and 0x3F)) }
            else -> {
                out.write(0xF0 or (cp shr 18)); out.write(0x80 or ((cp shr 12) and 0x3F))
                out.write(0x80 or ((cp shr 6) and 0x3F)); out.write(0x80 or (cp and 0x3F))
            }
        }
    }

    private fun installTextEncoder(realm: Realm) {
        val i = WebInterface.define(realm, "TextEncoder", 0) { _, proto -> JSTextEncoder(proto) }
        fun check(t: Any?, m: String) = Idl.self<JSTextEncoder>(t, "TextEncoder", m)
        i.operation("encode", 0) { f, t, a, _ ->
            check(t, "encode")
            val input = a.arg(0)
            val bytes = utf8(if (input === Undefined) "" else Idl.usvString(input))
            val ta = TypedArrayBuiltins.allocate(f.realm, ElementType.UINT8, TypedArrayBuiltins.protoOf(f.realm, ElementType.UINT8), bytes.size.toLong())
            System.arraycopy(bytes, 0, ta.buffer.data, 0, bytes.size)
            ta
        }
        i.operation("encodeInto", 2) { f, t, a, _ ->
            check(t, "encodeInto")
            Idl.required(a, 2, "TextEncoder.encodeInto")
            val s = Idl.usvString(a[0])
            val dest = a.arg(1) as? JSTypedArray
            if (dest == null || dest.type != ElementType.UINT8) typeErr("TextEncoder.prototype.encodeInto requires a Uint8Array destination")
            val cap = maxOf(dest.lengthOrOOB(), 0)
            val data = dest.buffer.data
            var read = 0
            var written = 0
            while (read < s.length) {
                val cp = codePointOrReplacement(s, read)
                val n = utf8Length(cp)
                if (written + n > cap) break
                val tmp = java.io.ByteArrayOutputStream(4)
                writeUtf8(tmp, cp)
                System.arraycopy(tmp.toByteArray(), 0, data, dest.byteOffset + written, n)
                written += n
                read += if (cp >= 0x10000) 2 else 1
            }
            val r = JSObject(f.realm.objectPrototype)
            r.createDataProperty("read", read.toDouble())
            r.createDataProperty("written", written.toDouble())
            r
        }
        i.attribute("encoding", { _, t, _, _ -> check(t, "encoding"); "utf-8" })
    }

    // ------------------------------------------------------------------ TextDecoder

    /** WHATWG UTF-8 decoder state, kept across `decode(..., { stream: true })` calls. */
    private class JSTextDecoder(proto: JSObject?, @JvmField val fatal: Boolean, @JvmField val ignoreBOM: Boolean) : JSObject(proto) {
        override val className: String get() = "TextDecoder"
        @JvmField var codePoint = 0
        @JvmField var bytesSeen = 0
        @JvmField var bytesNeeded = 0
        @JvmField var lower = 0x80
        @JvmField var upper = 0xBF
        @JvmField var bomHandled = false

        fun reset() {
            codePoint = 0; bytesSeen = 0; bytesNeeded = 0; lower = 0x80; upper = 0xBF; bomHandled = false
        }
    }

    private val UTF8_LABELS = setOf("unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "utf-8", "utf8", "x-unicode20utf8")

    private fun installTextDecoder(realm: Realm) {
        val i = WebInterface.define(realm, "TextDecoder", 0) { a, proto ->
            val label = if (a.arg(0) === Undefined) "utf-8" else Idl.domString(a.arg(0)).trim { it == ' ' || it == '\t' || it == '\n' || it == '\u000C' || it == '\r' }.lowercase()
            val o = Idl.dictionary(a.arg(1), "TextDecoder options")
            val fatal = Ops.toBoolean(Idl.member(o, "fatal"))
            val ignoreBOM = Ops.toBoolean(Idl.member(o, "ignoreBOM"))
            if (label !in UTF8_LABELS) rangeErr("TextDecoder: unsupported encoding '$label' (only UTF-8 is available)")
            JSTextDecoder(proto, fatal, ignoreBOM)
        }
        fun check(t: Any?, m: String) = Idl.self<JSTextDecoder>(t, "TextDecoder", m)
        i.attribute("encoding", { _, t, _, _ -> check(t, "encoding"); "utf-8" })
        i.attribute("fatal", { _, t, _, _ -> check(t, "fatal").fatal })
        i.attribute("ignoreBOM", { _, t, _, _ -> check(t, "ignoreBOM").ignoreBOM })
        i.operation("decode", 0) { f, t, a, _ ->
            val d = check(t, "decode")
            val bytes = if (a.arg(0) === Undefined) ByteArray(0) else Idl.bytes(a.arg(0), "TextDecoder.decode input", allowShared = true)
            val stream = Ops.toBoolean(Idl.member(Idl.dictionary(a.arg(1), "TextDecoder.decode options"), "stream"))
            decode(f.realm, d, bytes, stream)
        }
    }

    /** UTF-8 decode (Encoding Standard): a leading BOM dropped, invalid sequences replaced by U+FFFD. */
    internal fun utf8Decode(realm: Realm, bytes: ByteArray): String = decode(realm, JSTextDecoder(null, fatal = false, ignoreBOM = false), bytes, stream = false)

    private fun decode(realm: Realm, d: JSTextDecoder, bytes: ByteArray, stream: Boolean): String {
        val out = StringBuilder(bytes.size)
        fun emit(cp: Int) {
            if (!d.bomHandled) {
                d.bomHandled = true
                if (cp == 0xFEFF && !d.ignoreBOM) return
            }
            out.appendCodePoint(cp)
        }
        fun error() {
            if (d.fatal) {
                d.reset()
                typeErr("TextDecoder: the encoded data is not valid UTF-8")
            }
            emit(0xFFFD)
        }
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (d.bytesNeeded == 0) {
                when (b) {
                    in 0x00..0x7F -> emit(b)
                    in 0xC2..0xDF -> { d.bytesNeeded = 1; d.codePoint = b and 0x1F }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) d.lower = 0xA0
                        if (b == 0xED) d.upper = 0x9F
                        d.bytesNeeded = 2; d.codePoint = b and 0xF
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) d.lower = 0x90
                        if (b == 0xF4) d.upper = 0x8F
                        d.bytesNeeded = 3; d.codePoint = b and 0x7
                    }
                    else -> error()
                }
                i++
                continue
            }
            if (b !in d.lower..d.upper) {
                // the byte is not consumed: it starts the next sequence
                d.codePoint = 0; d.bytesNeeded = 0; d.bytesSeen = 0; d.lower = 0x80; d.upper = 0xBF
                error()
                continue
            }
            d.lower = 0x80; d.upper = 0xBF
            d.codePoint = (d.codePoint shl 6) or (b and 0x3F)
            d.bytesSeen++
            i++
            if (d.bytesSeen == d.bytesNeeded) {
                val cp = d.codePoint
                d.codePoint = 0; d.bytesNeeded = 0; d.bytesSeen = 0
                emit(cp)
            }
            if (out.length > 65536) realm.agent.checkStringLength(out.length.toLong())
        }
        if (!stream) {
            if (d.bytesNeeded != 0) {
                d.codePoint = 0; d.bytesNeeded = 0; d.bytesSeen = 0; d.lower = 0x80; d.upper = 0xBF
                error()
            }
            d.reset()
        }
        realm.agent.checkStringLength(out.length.toLong())
        return out.toString()
    }
}
