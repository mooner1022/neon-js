package dev.mooner.neonjs.net

import dev.mooner.neonjs.ext.WebNetwork
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The OkHttp clients of an extension: the host's (or a new one) with the policy applied, shared by its contexts. */
internal class NetClients(val options: NetOptions) {
    val client: OkHttpClient = (options.client ?: newClient(options)).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .proxy(Proxy.NO_PROXY)
        .socketFactory(PolicySocketFactory(options))
        .connectTimeout(options.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(options.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(options.writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(options.callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    private companion object {
        /** A client of our own: its dispatcher runs a context's fetches to one host at once, up to the limit. */
        fun newClient(options: NetOptions): OkHttpClient {
            val d = okhttp3.Dispatcher()
            d.maxRequestsPerHost = maxOf(d.maxRequestsPerHost, options.maxConcurrentRequests)
            d.maxRequests = maxOf(d.maxRequests, options.maxConcurrentRequests)
            return OkHttpClient.Builder().dispatcher(d).build()
        }
    }

    /** For WebSocket handshakes: an HTTP/1.1 upgrade (no HTTP/2), no time limit for the whole call. */
    val upgradeClient: OkHttpClient = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

/** What one context uses of its limits. */
internal class NetUsage {
    val requests = AtomicInteger()
    val sockets = AtomicInteger()

    /** Takes one of [limit] slots of [counter]; false when none is left. */
    fun acquire(counter: AtomicInteger, limit: Int): Boolean {
        while (true) {
            val n = counter.get()
            if (n >= limit) return false
            if (counter.compareAndSet(n, n + 1)) return true
        }
    }
}

/** What a failed call says: the policy's refusal when that was the cause (OkHttp wraps it), else the exception's message. */
internal fun failureMessage(e: Throwable): String {
    var t: Throwable? = e
    while (t != null) {
        if (t is PolicyException) return t.message!!
        for (s in t.suppressed) if (s is PolicyException) return s.message!!
        t = t.cause
    }
    return e.message ?: e.javaClass.simpleName
}

/** Why [url] may not be reached, or null when it may (the address rules come at connect time). */
internal fun refusal(options: NetOptions, url: HttpUrl, what: String): String? {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    if (!options.allowsHost(host, url.port)) return "$what to $host is not allowed by the network policy"
    if (!url.isHttps && !options.allowInsecure) return "$what to ${url.scheme}: URLs is not allowed by the network policy (cleartext)"
    return null
}

/** fetch's exchanges over OkHttp, within the options' policy and one context's limits. */
internal class OkHttpFetch(private val clients: NetClients, private val usage: NetUsage) : WebNetwork.FetchTransport {
    private val options = clients.options

    override fun send(request: WebNetwork.Request, callback: WebNetwork.FetchCallback): WebNetwork.Cancellable {
        val url = request.url.toHttpUrlOrNull()
        if (url == null) {
            callback.onFailure("invalid URL ${request.url}")
            return NOTHING
        }
        refusal(options, url, "fetch")?.let {
            callback.onFailure(it)
            return NOTHING
        }
        if (!usage.acquire(usage.requests, options.maxConcurrentRequests)) {
            callback.onFailure("too many fetches in flight (limit ${options.maxConcurrentRequests})")
            return NOTHING
        }
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) usage.requests.decrementAndGet()
        }
        val call = try {
            clients.client.newCall(build(request, url))
        } catch (e: IllegalArgumentException) {
            release()
            callback.onFailure(e.message ?: "invalid request")
            return NOTHING
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                release()
                if (!call.isCanceled()) callback.onFailure(failureMessage(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { readBody(it) }
                    release()
                    callback.onResponse(WebNetwork.Response(response.code, response.message, response.headers.toList(), body))
                } catch (e: IOException) {
                    release()
                    call.cancel()
                    if (e is LimitException || !call.isCanceled()) callback.onFailure(failureMessage(e))
                }
            }
        })
        return WebNetwork.Cancellable {
            call.cancel()
            release()
        }
    }

    private fun build(request: WebNetwork.Request, url: HttpUrl): Request {
        val headers = Headers.Builder()
        var userAgent = false
        for ((n, v) in request.headers) {
            // values are ByteStrings: code units up to 0xFF, which OkHttp's own check would refuse above 0x7E
            headers.addUnsafeNonAscii(n, v)
            if (n.equals("User-Agent", ignoreCase = true)) userAgent = true
        }
        if (!userAgent) headers.add("User-Agent", options.userAgent)
        val method = request.method
        val body = request.body?.toRequestBody() ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody() else null
        return Request.Builder().url(url).headers(headers.build()).method(method, body).build()
    }

    /** The decoded body, at most [NetOptions.maxResponseBytes] of it, or a [LimitException]. */
    private fun readBody(response: Response): ByteArray {
        val max = options.maxResponseBytes
        val body = response.body
        val declared = body.contentLength()
        if (declared > max) throw LimitException("the response body (${declared} bytes) exceeds the limit of $max bytes")
        val source = body.source()
        val out = okio.Buffer()
        while (true) {
            val n = source.read(out, 8192)
            if (n < 0) break
            if (out.size > max) throw LimitException("the response body exceeds the limit of $max bytes")
        }
        return out.readByteArray()
    }

    private class LimitException(message: String) : IOException(message)

    companion object {
        val NOTHING = WebNetwork.Cancellable { }

        /** The methods OkHttp sends only with a body (an empty one when fetch has none). */
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}
