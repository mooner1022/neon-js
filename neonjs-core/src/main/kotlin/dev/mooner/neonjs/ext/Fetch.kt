package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins
import dev.mooner.neonjs.builtins.ElementType
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSONBuiltins
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.TypedArrayBuiltins
import dev.mooner.neonjs.builtins.rangeErr
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.JSPromise
import dev.mooner.neonjs.vm.Promises

/**
 * `Request`, `Response` and `fetch` (Fetch Standard), outside a browser: there is no origin, CORS, cache, cookie
 * store or referrer, URLs are absolute, and bodies are whole byte sequences rather than streams (there is no
 * ReadableStream, so no `body` attribute: the body methods read them). `fetch` exists once an engine module gives
 * the realm a transport ([WebNetwork.install]); the redirects (at most 20), the header guard and aborts are done here.
 */
internal object Fetch {
    /** A Request or a Response: the Body mixin's state. A body's bytes never change once made. */
    abstract class JSBody(proto: JSObject?) : JSObject(proto) {
        @JvmField var body: ByteArray? = null
        @JvmField var bodyUsed = false
        @JvmField var headers: Headers.JSHeaders? = null
    }

    class JSRequest(proto: JSObject?) : JSBody(proto) {
        override val className: String get() = "Request"
        @JvmField var method = "GET"
        @JvmField var url = Url.Record()
        @JvmField var redirect = "follow"
        @JvmField var mode = "cors"
        @JvmField var credentials = "same-origin"
        @JvmField var cache = "default"
        /** "about:client", "" for no referrer, or a URL. */
        @JvmField var referrer = "about:client"
        @JvmField var referrerPolicy = ""
        @JvmField var integrity = ""
        @JvmField var keepalive = false
        @JvmField var signal: Abort.JSAbortSignal? = null
    }

    class JSResponse(proto: JSObject?) : JSBody(proto) {
        override val className: String get() = "Response"
        /** "basic", "default" or "error". */
        @JvmField var type = "default"
        /** The last URL of the response's URL list (serialized without fragment), or null for none. */
        @JvmField var url: String? = null
        @JvmField var redirected = false
        @JvmField var status = 200
        @JvmField var statusText = ""
    }

    private val NULL_BODY_STATUSES = setOf(101, 103, 204, 205, 304)
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private val FORBIDDEN_METHODS = setOf("CONNECT", "TRACE", "TRACK")
    private val NORMALIZED_METHODS = setOf("DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT")
    private val REQUEST_BODY_HEADERS = setOf("content-encoding", "content-language", "content-location", "content-type")

    private fun enumValue(v: Any?, allowed: Set<String>, what: String): String? {
        if (v === Undefined) return null
        val s = Idl.domString(v)
        if (s !in allowed) typeErr("$what: '$s' is not a valid value")
        return s
    }

    /**
     * "extract a body" of a `BodyInit`: the bytes and the Content-Type it implies (null for none). Blob, BufferSource,
     * FormData, URLSearchParams, or a string (any other value converted to one); a ReadableStream is no option here.
     */
    fun extract(realm: Realm, v: Any?): Pair<ByteArray, String?> = when (v) {
        is Blob.JSBlob -> v.bytes() to v.type.ifEmpty { null }
        is JSArrayBuffer, is JSTypedArray, is JSDataView -> Idl.bytes(v, "body", allowShared = false) to null
        is FormData.JSFormData -> FormData.multipart(realm, v)
        is Url.JSURLSearchParams -> WebGlobals.utf8(Url.serializeForm(v.list)) to "application/x-www-form-urlencoded;charset=UTF-8"
        else -> WebGlobals.utf8(Idl.usvString(v)) to "text/plain;charset=UTF-8"
    }

    private fun newHeaders(realm: Realm): Headers.JSHeaders = Headers.JSHeaders(realm.intrinsic("%Headers.prototype%"))

    // ------------------------------------------------------------------ Request

    /** The Request constructor's steps (`new Request(input, init)`, and fetch's first step). */
    fun newRequest(realm: Realm, proto: JSObject, input: Any?, initArg: Any?): JSRequest {
        val init = Idl.dictionary(initArg, "RequestInit")
        val r = JSRequest(proto)
        val from = input as? JSRequest
        if (from == null) {
            val s = Idl.usvString(input)
            val url = Url.parse(s, null) ?: typeErr("Failed to parse URL from $s")
            if (url.includesCredentials) typeErr("Request cannot be constructed from a URL that includes credentials: $s")
            r.url = url
        } else {
            r.url = from.url.copy()
            r.method = from.method
            r.redirect = from.redirect
            r.mode = from.mode
            r.credentials = from.credentials
            r.cache = from.cache
            r.referrer = from.referrer
            r.referrerPolicy = from.referrerPolicy
            r.integrity = from.integrity
            r.keepalive = from.keepalive
        }
        // RequestInit's members, converted in lexicographic order
        val bodyInit = Idl.member(init, "body")
        enumValue(Idl.member(init, "cache"), setOf("default", "no-store", "reload", "no-cache", "force-cache", "only-if-cached"), "RequestInit cache")?.let { r.cache = it }
        enumValue(Idl.member(init, "credentials"), setOf("omit", "same-origin", "include"), "RequestInit credentials")?.let { r.credentials = it }
        enumValue(Idl.member(init, "duplex"), setOf("half"), "RequestInit duplex")
        val headersInit = Idl.member(init, "headers")
        Idl.member(init, "integrity").let { if (it !== Undefined) r.integrity = Idl.domString(it) }
        Idl.member(init, "keepalive").let { if (it !== Undefined) r.keepalive = Ops.toBoolean(it) }
        val methodInit = Idl.member(init, "method").let { if (it === Undefined) null else Idl.byteString(it, "RequestInit method") }
        val mode = enumValue(Idl.member(init, "mode"), setOf("navigate", "same-origin", "no-cors", "cors"), "RequestInit mode")
        enumValue(Idl.member(init, "priority"), setOf("high", "low", "auto"), "RequestInit priority")
        enumValue(Idl.member(init, "redirect"), setOf("follow", "error", "manual"), "RequestInit redirect")?.let { r.redirect = it }
        val referrer = Idl.member(init, "referrer").let { if (it === Undefined) null else Idl.usvString(it) }
        enumValue(
            Idl.member(init, "referrerPolicy"),
            setOf("", "no-referrer", "no-referrer-when-downgrade", "same-origin", "origin", "strict-origin", "origin-when-cross-origin", "strict-origin-when-cross-origin", "unsafe-url"),
            "RequestInit referrerPolicy",
        )?.let { r.referrerPolicy = it }
        val signalInit = Idl.member(init, "signal")
        val signal = if (signalInit === Undefined || signalInit === Null) null else Abort.signalOf(signalInit)
        Idl.member(init, "window").let { if (it !== Undefined && it !== Null) typeErr("RequestInit window can only be null") }

        if (mode == "navigate") typeErr("Request mode 'navigate' is not allowed")
        if (mode != null) r.mode = mode
        if (r.cache == "only-if-cached" && r.mode != "same-origin") typeErr("Request cache 'only-if-cached' needs mode 'same-origin'")
        if (referrer != null) {
            r.referrer = if (referrer.isEmpty()) "" else {
                val u = Url.parse(referrer, null) ?: typeErr("Failed to parse referrer URL from $referrer")
                if (u.scheme == "about" && u.pathname() == "client") "about:client" else u.serialize()
            }
        }
        if (methodInit != null) {
            if (!Headers.isToken(methodInit)) typeErr("'$methodInit' is not a valid HTTP method")
            val upper = methodInit.uppercase(java.util.Locale.ROOT)
            if (upper in FORBIDDEN_METHODS) typeErr("'$methodInit' HTTP method is unsupported")
            r.method = if (upper in NORMALIZED_METHODS) upper else methodInit
        }

        // the new request's signal follows the given one (init's, else the input request's)
        val s = Abort.newSignal(realm)
        (signal ?: from?.signal)?.let { src ->
            if (src.aborted) {
                s.aborted = true
                s.reason = src.reason
            } else {
                src.addAlgorithm { s.abort(realm, src.reason) }
            }
        }
        r.signal = s

        val h = newHeaders(realm)
        h.requestGuard = true
        r.headers = h
        if (headersInit !== Undefined) Headers.fill(realm, h, headersInit, "Request headers")
        else from?.headers?.let { for ((n, v) in it.list) h.append(n, v) }
        if (r.mode == "no-cors") {
            if (r.method != "GET" && r.method != "HEAD" && r.method != "POST") typeErr("'${r.method}' is unsupported in no-cors mode")
        }

        val inputBody = from?.body
        val hasInitBody = bodyInit !== Undefined && bodyInit !== Null
        if ((hasInitBody || inputBody != null) && (r.method == "GET" || r.method == "HEAD")) typeErr("Request with GET/HEAD method cannot have body.")
        if (hasInitBody) {
            val (bytes, type) = extract(realm, bodyInit)
            r.body = bytes
            if (type != null && !h.contains("Content-Type")) h.append("Content-Type", type)
        } else if (inputBody != null) {
            if (from.bodyUsed) typeErr("Cannot construct a Request with a Request object that has already been used.")
            r.body = inputBody
            // the input's body moves to the new request
            from.bodyUsed = true
        }
        return r
    }

    private fun cloneRequest(realm: Realm, r: JSRequest): JSRequest {
        if (r.bodyUsed) typeErr("Request.clone: the body has already been used")
        val c = JSRequest(realm.intrinsic("%Request.prototype%"))
        c.method = r.method; c.url = r.url.copy(); c.redirect = r.redirect; c.mode = r.mode; c.credentials = r.credentials
        c.cache = r.cache; c.referrer = r.referrer; c.referrerPolicy = r.referrerPolicy; c.integrity = r.integrity
        c.keepalive = r.keepalive; c.body = r.body
        val h = newHeaders(realm)
        h.requestGuard = true
        h.list.addAll(r.headers!!.list)
        c.headers = h
        val s = Abort.newSignal(realm)
        r.signal?.let { src -> if (src.aborted) { s.aborted = true; s.reason = src.reason } else src.addAlgorithm { s.abort(realm, src.reason) } }
        c.signal = s
        return c
    }

    // ------------------------------------------------------------------ Response

    private fun isReasonPhrase(s: String) = s.all { it == '\t' || it.code in 0x20..0x7E || it.code in 0x80..0xFF }

    fun newResponse(realm: Realm, proto: JSObject? = null): JSResponse {
        val r = JSResponse(proto ?: realm.intrinsic("%Response.prototype%"))
        r.headers = newHeaders(realm)
        return r
    }

    /** "initialize a response" with a ResponseInit and an extracted body. */
    private fun initialize(realm: Realm, r: JSResponse, init: JSObject?, body: Pair<ByteArray, String?>?) {
        // ResponseInit in lexicographic order: headers, status, statusText
        val headersInit = Idl.member(init, "headers")
        val status = Idl.member(init, "status").let { if (it === Undefined) 200 else Idl.integer(it, 16, signed = false, what = "ResponseInit status").toInt() }
        val statusText = Idl.member(init, "statusText").let { if (it === Undefined) "" else Idl.byteString(it, "ResponseInit statusText") }
        if (status < 200 || status > 599) throw JSException.rangeError("The status provided ($status) is outside the range [200, 599].")
        if (!isReasonPhrase(statusText)) typeErr("Invalid statusText")
        r.status = status
        r.statusText = statusText
        if (headersInit !== Undefined) Headers.fill(realm, r.headers!!, headersInit, "Response headers")
        if (body != null) {
            if (status in NULL_BODY_STATUSES) typeErr("Response constructor: a ${status} response cannot have a body")
            r.body = body.first
            if (body.second != null && !r.headers!!.contains("Content-Type")) r.headers!!.append("Content-Type", body.second!!)
        }
    }

    private fun cloneResponse(realm: Realm, r: JSResponse): JSResponse {
        if (r.bodyUsed) typeErr("Response.clone: the body has already been used")
        val c = newResponse(realm)
        c.type = r.type; c.url = r.url; c.redirected = r.redirected; c.status = r.status; c.statusText = r.statusText
        c.body = r.body
        c.headers!!.list.addAll(r.headers!!.list)
        c.headers!!.immutable = r.headers!!.immutable
        return c
    }

    // ------------------------------------------------------------------ the Body mixin

    /** "consume body": the bytes of [b]'s body (none: empty), which is used from then on; a TypeError once it was. */
    private fun consume(b: JSBody, what: String): ByteArray {
        val body = b.body ?: return ByteArray(0)
        if (b.bodyUsed) typeErr("$what: the body has already been used")
        b.bodyUsed = true
        return body
    }

    /** The essence of the Content-Type of [b]'s headers ("type/subtype" in lower case), or null. */
    private fun mimeEssence(b: JSBody): String? {
        val ct = b.headers?.get("Content-Type") ?: return null
        val essence = ct.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return essence.ifEmpty { null }
    }

    private fun installBody(i: WebInterface, iface: String, cls: Class<out JSBody>) {
        fun body(t: Any?, m: String): JSBody = if (cls.isInstance(t)) t as JSBody else typeErr("$iface.$m called on ${Ops.describe(t)}, which is not a $iface")
        i.attribute("bodyUsed", { _, t, _, _ -> body(t, "bodyUsed").let { it.body != null && it.bodyUsed } })
        i.promiseOperation("arrayBuffer", 0) { f, t, _, _ ->
            val bytes = consume(body(t, "arrayBuffer"), "$iface.arrayBuffer")
            val buf = ArrayBufferBuiltins.allocate(f.realm, null, bytes.size.toLong(), -1L)
            System.arraycopy(bytes, 0, buf.data, 0, bytes.size)
            buf
        }
        i.promiseOperation("blob", 0) { f, t, _, _ ->
            val b = body(t, "blob")
            val type = b.headers?.get("Content-Type")?.let { ct -> if (ct.all { it.code in 0x20..0x7E }) ct.lowercase(java.util.Locale.ROOT) else "" } ?: ""
            Blob.newBlob(f.realm, consume(b, "$iface.blob"), type)
        }
        i.promiseOperation("bytes", 0) { f, t, _, _ ->
            val bytes = consume(body(t, "bytes"), "$iface.bytes")
            val ta = TypedArrayBuiltins.allocate(f.realm, ElementType.UINT8, TypedArrayBuiltins.protoOf(f.realm, ElementType.UINT8), bytes.size.toLong())
            System.arraycopy(bytes, 0, ta.buffer.data, 0, bytes.size)
            ta
        }
        i.promiseOperation("formData", 0) { f, t, _, _ ->
            val b = body(t, "formData")
            val essence = mimeEssence(b)
            val ct = b.headers?.get("Content-Type") ?: ""
            val bytes = consume(b, "$iface.formData")
            when (essence) {
                "multipart/form-data" -> {
                    val boundary = FormData.headerParam(ct, "boundary") ?: typeErr("$iface.formData: the multipart/form-data body has no boundary")
                    FormData.parseMultipart(f.realm, bytes, boundary)
                }
                "application/x-www-form-urlencoded" -> {
                    val fd = FormData.newFormData(f.realm)
                    for (p in Url.parseForm(WebGlobals.utf8Decode(f.realm, bytes))) fd.entries.add(p)
                    fd
                }
                else -> typeErr("$iface.formData: the body is neither multipart/form-data nor application/x-www-form-urlencoded")
            }
        }
        i.promiseOperation("json", 0) { f, t, _, _ ->
            val text = WebGlobals.utf8Decode(f.realm, consume(body(t, "json"), "$iface.json"))
            JSONBuiltins.JsonParser(f.realm, text).parseRoot().value
        }
        i.promiseOperation("text", 0) { f, t, _, _ -> WebGlobals.utf8Decode(f.realm, consume(body(t, "text"), "$iface.text")) }
    }

    // ------------------------------------------------------------------ the interfaces

    fun install(realm: Realm) {
        val rq = WebInterface.define(realm, "Request", 1) { a, proto ->
            Idl.required(a, 1, "Request constructor")
            newRequest(realm, proto, a[0], a.arg(1))
        }
        fun req(t: Any?, m: String) = Idl.self<JSRequest>(t, "Request", m)
        rq.attribute("method", { _, t, _, _ -> req(t, "method").method })
        rq.attribute("url", { _, t, _, _ -> req(t, "url").url.serialize() })
        rq.attribute("headers", { _, t, _, _ -> req(t, "headers").headers })
        rq.attribute("destination", { _, t, _, _ -> req(t, "destination"); "" })
        rq.attribute("referrer", { _, t, _, _ -> req(t, "referrer").referrer })
        rq.attribute("referrerPolicy", { _, t, _, _ -> req(t, "referrerPolicy").referrerPolicy })
        rq.attribute("mode", { _, t, _, _ -> req(t, "mode").mode })
        rq.attribute("credentials", { _, t, _, _ -> req(t, "credentials").credentials })
        rq.attribute("cache", { _, t, _, _ -> req(t, "cache").cache })
        rq.attribute("redirect", { _, t, _, _ -> req(t, "redirect").redirect })
        rq.attribute("integrity", { _, t, _, _ -> req(t, "integrity").integrity })
        rq.attribute("keepalive", { _, t, _, _ -> req(t, "keepalive").keepalive })
        rq.attribute("isReloadNavigation", { _, t, _, _ -> req(t, "isReloadNavigation"); false })
        rq.attribute("isHistoryNavigation", { _, t, _, _ -> req(t, "isHistoryNavigation"); false })
        rq.attribute("signal", { _, t, _, _ -> req(t, "signal").signal })
        rq.attribute("duplex", { _, t, _, _ -> req(t, "duplex"); "half" })
        rq.operation("clone", 0) { f, t, _, _ -> cloneRequest(f.realm, req(t, "clone")) }
        installBody(rq, "Request", JSRequest::class.java)

        val rs = WebInterface.define(realm, "Response", 0) { a, proto ->
            val body = a.arg(0).let { if (it === Undefined || it === Null) null else extract(realm, it) }
            val init = Idl.dictionary(a.arg(1), "ResponseInit")
            val r = newResponse(realm, proto)
            initialize(realm, r, init, body)
            r
        }
        rs.staticOperation("error", 0) { f, _, _, _ ->
            val r = newResponse(f.realm)
            r.type = "error"
            r.status = 0
            r.headers!!.immutable = true
            r
        }
        rs.staticOperation("redirect", 1) { f, _, a, _ ->
            Idl.required(a, 1, "Response.redirect")
            val s = Idl.usvString(a[0])
            val url = Url.parse(s, null) ?: typeErr("Failed to parse URL from $s")
            val status = a.arg(1).let { if (it === Undefined) 302 else Idl.integer(it, 16, signed = false, what = "Response.redirect status").toInt() }
            if (status !in REDIRECT_STATUSES) rangeErr("Invalid status code $status")
            val r = newResponse(f.realm)
            r.status = status
            r.headers!!.append("Location", url.serialize())
            r.headers!!.immutable = true
            r
        }
        rs.staticOperation("json", 1) { f, _, a, _ ->
            val text = JSONBuiltins.stringify(f.realm, a.arg(0), Undefined, Undefined)
            if (text === Undefined) typeErr("Response.json: the data is not JSON-serializable")
            val init = Idl.dictionary(a.arg(1), "ResponseInit")
            val r = newResponse(f.realm)
            initialize(f.realm, r, init, WebGlobals.utf8(Ops.toString(text)) to "application/json")
            r
        }
        fun res(t: Any?, m: String) = Idl.self<JSResponse>(t, "Response", m)
        rs.attribute("type", { _, t, _, _ -> res(t, "type").type })
        rs.attribute("url", { _, t, _, _ -> res(t, "url").url ?: "" })
        rs.attribute("redirected", { _, t, _, _ -> res(t, "redirected").redirected })
        rs.attribute("status", { _, t, _, _ -> res(t, "status").status.toDouble() })
        rs.attribute("ok", { _, t, _, _ -> res(t, "ok").status in 200..299 })
        rs.attribute("statusText", { _, t, _, _ -> res(t, "statusText").statusText })
        rs.attribute("headers", { _, t, _, _ -> res(t, "headers").headers })
        rs.operation("clone", 0) { f, t, _, _ -> cloneResponse(f.realm, res(t, "clone")) }
        installBody(rs, "Response", JSResponse::class.java)
    }

    // ------------------------------------------------------------------ fetch

    fun installFetch(realm: Realm, transport: WebNetwork.FetchTransport) {
        WebInterface.globalOperation(realm, "fetch", 1) { f, _, a, _ ->
            val p = Promises.newPromise(f.realm)
            val request = try {
                Idl.required(a, 1, "fetch")
                newRequest(f.realm, f.realm.intrinsic("%Request.prototype%"), a[0], a.arg(1))
            } catch (e: JSException) {
                Promises.rejectPromise(f.realm, p, e.value)
                return@globalOperation p
            }
            FetchRun(f.realm, transport, request, p).start()
            p
        }
    }

    /** One fetch in flight: its hops through the transport, until it settles [promise]. */
    private class FetchRun(
        val realm: Realm, val transport: WebNetwork.FetchTransport, val request: JSRequest, val promise: JSPromise,
    ) : Agent.ExternalSource {
        /** The exchange in flight; read by [cancel] from any thread. */
        @Volatile var current: WebNetwork.Cancellable? = null
        @Volatile var cancelled = false
        var settled = false
        var method = request.method
        var url: Url.Record = request.url
        var headers = ArrayList(request.headers!!.list)
        var body = request.body
        var redirects = 0

        override fun cancel() {
            cancelled = true
            current?.cancel()
        }

        fun start() {
            val signal = request.signal!!
            if (signal.aborted) {
                Promises.rejectPromise(realm, promise, signal.reason)
                return
            }
            if (!realm.agent.addExternalSource(this)) {
                fail("the context is closing")
                return
            }
            signal.addAlgorithm { abort(signal.reason) }
            if (headers.none { it.first.equals("Accept", ignoreCase = true) }) headers.add("Accept" to "*/*")
            send()
        }

        fun send() {
            if (url.scheme != "http" && url.scheme != "https") {
                fail("fetch supports http: and https: URLs only, not ${url.scheme}:")
                return
            }
            val agent = realm.agent
            val r = WebNetwork.Request(method, url.serialize(excludeFragment = true), headers.toList(), body)
            val callback = object : WebNetwork.FetchCallback {
                override fun onResponse(response: WebNetwork.Response) = agent.postExternalJob { if (!settled) received(response) }
                override fun onFailure(message: String) = agent.postExternalJob { if (!settled) fail(message) }
            }
            try {
                current = transport.send(r, callback)
                if (cancelled) current?.cancel()
            } catch (e: RuntimeException) {
                fail(e.message ?: e.toString())
            }
        }

        fun received(response: WebNetwork.Response) {
            current = null
            val location = response.headers.firstOrNull { it.first.equals("Location", ignoreCase = true) }?.second
            if (response.status in REDIRECT_STATUSES && location != null && request.redirect != "manual") {
                if (request.redirect == "error") {
                    fail("unexpected redirect")
                    return
                }
                redirect(response.status, location)
                return
            }
            settled = true
            realm.agent.removeExternalSource(this)
            val bytes = response.body
            if (bytes != null) realm.agent.reserveAllocation(bytes.size.toLong())
            val r = newResponse(realm)
            r.type = "basic"
            r.url = url.serialize(excludeFragment = true)
            r.redirected = redirects > 0
            r.status = response.status
            r.statusText = response.statusText
            r.headers!!.list.addAll(response.headers)
            r.headers!!.immutable = true
            r.body = if (method == "HEAD" || response.status in NULL_BODY_STATUSES) null else bytes ?: ByteArray(0)
            Promises.resolvePromise(realm, promise, r)
        }

        /** "HTTP-redirect fetch" with redirect mode "follow". */
        fun redirect(status: Int, location: String) {
            val next = Url.parse(location, url)
            if (next == null) {
                fail("invalid redirect location: $location")
                return
            }
            if (next.scheme != "http" && next.scheme != "https") {
                fail("redirect to a ${next.scheme}: URL")
                return
            }
            if (redirects == 20) {
                fail("redirect count exceeded")
                return
            }
            redirects++
            if ((status == 301 || status == 302) && method == "POST" || status == 303 && method != "GET" && method != "HEAD") {
                method = "GET"
                body = null
                headers.removeIf { it.first.lowercase(java.util.Locale.ROOT) in REQUEST_BODY_HEADERS }
            }
            // credentials do not go to another origin (as undici: the Fetch Standard names Authorization)
            if (next.origin() != url.origin()) {
                headers.removeIf { it.first.equals("Authorization", ignoreCase = true) || it.first.equals("Cookie", ignoreCase = true) || it.first.equals("Proxy-Authorization", ignoreCase = true) }
            }
            // a URL's fragment carries over a redirect that has none
            if (next.fragment == null) next.fragment = url.fragment
            url = next
            send()
        }

        fun abort(reason: Any?) {
            if (settled) return
            settled = true
            realm.agent.removeExternalSource(this)
            current?.cancel()
            Promises.rejectPromise(realm, promise, reason)
        }

        /** Rejects with fetch's network error: `TypeError: fetch failed` with the reason as its `cause`. */
        fun fail(message: String) {
            if (settled) return
            settled = true
            realm.agent.removeExternalSource(this)
            current?.cancel()
            val e = realm.newError(ErrorKind.TYPE, "fetch failed")
            e.defineOwn("cause", realm.newError(ErrorKind.ERROR, message), Attr.WC)
            Promises.rejectPromise(realm, promise, e)
        }
    }
}
