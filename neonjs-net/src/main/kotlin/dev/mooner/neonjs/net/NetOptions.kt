package dev.mooner.neonjs.net

import okhttp3.OkHttpClient

/**
 * What the `fetch` and `WebSocket` of a context may reach, and how much of the host they may use. Nothing is
 * reachable by default: a host names the hosts its scripts need ([Builder.allowHosts]). Whatever the hosts, a
 * connection to a loopback, private, link-local or otherwise non-public address is refused unless its range is
 * allowed ([Builder.allowAddresses]), and so is cleartext (`http:`, `ws:`) unless allowed ([Builder.allowInsecure]).
 */
class NetOptions private constructor(b: Builder) {
    /** The hosts that may be reached (patterns: `example.com`, `*.example.com`, `example.com:8443`). */
    val allowedHosts: List<HostPattern> = b.hosts.toList()
    /** Whether any host may be reached (the address rules still apply). */
    val allowAllHosts: Boolean = b.allowAllHosts
    /** The address ranges (CIDR) exempted from the rule against non-public addresses. */
    val allowedAddresses: List<AddressRange> = b.addresses.toList()
    /** Whether `http:` and `ws:` are allowed besides `https:` and `wss:` (default: false). */
    val allowInsecure: Boolean = b.allowInsecure
    /** The largest response body a fetch reads, decoded (default: 32 MiB); a larger one fails the fetch. */
    val maxResponseBytes: Long = b.maxResponseBytes
    /** The largest message a WebSocket receives (default: 32 MiB); a larger one fails the connection. */
    val maxMessageBytes: Long = b.maxMessageBytes
    /** The fetches a context may have in flight at once (default: 16). */
    val maxConcurrentRequests: Int = b.maxConcurrentRequests
    /** The WebSockets a context may have open or opening at once (default: 8). */
    val maxWebSockets: Int = b.maxWebSockets
    val connectTimeoutMillis: Long = b.connectTimeoutMillis
    /** The longest wait for the next bytes of a response (default: 30 s); an open WebSocket has none. */
    val readTimeoutMillis: Long = b.readTimeoutMillis
    val writeTimeoutMillis: Long = b.writeTimeoutMillis
    /** The longest a whole fetch may take, redirects and body included (default: 0, none). */
    val callTimeoutMillis: Long = b.callTimeoutMillis
    /** The `User-Agent` of requests that do not set one (default: `NeonJS/<version>`). */
    val userAgent: String = b.userAgent ?: "NeonJS/" + (dev.mooner.neonjs.NeonEngine::class.java.`package`?.implementationVersion ?: "dev")
    /**
     * The host's client, whose connection pool, dispatcher, DNS, TLS settings and interceptors are used (default: a
     * new one). The policy is applied on a client derived from it: redirects, caches, cookies and proxies are not
     * used, and connections go through NeonJS's socket factory, which enforces the address rules. Its dispatcher's
     * limits apply too (OkHttp's default runs 5 requests per host at once, and queues the others).
     */
    val client: OkHttpClient? = b.client

    /** Whether [host] (an ASCII host as URLs serialize it) and [port] may be reached by name. */
    fun allowsHost(host: String, port: Int): Boolean = allowAllHosts || allowedHosts.any { it.matches(host, port) }

    class Builder {
        internal val hosts = ArrayList<HostPattern>()
        internal var allowAllHosts = false
        internal val addresses = ArrayList<AddressRange>()
        internal var allowInsecure = false
        internal var maxResponseBytes = 32L shl 20
        internal var maxMessageBytes = 32L shl 20
        internal var maxConcurrentRequests = 16
        internal var maxWebSockets = 8
        internal var connectTimeoutMillis = 10_000L
        internal var readTimeoutMillis = 30_000L
        internal var writeTimeoutMillis = 30_000L
        internal var callTimeoutMillis = 0L
        internal var userAgent: String? = null
        internal var client: OkHttpClient? = null

        /** Allows the hosts matching these patterns: `example.com`, `*.example.com` (its subdomains), `host:port`. */
        fun allowHosts(vararg patterns: String) = apply { for (p in patterns) hosts.add(HostPattern.parse(p)) }

        /** Allows every host; the address rules and [allowInsecure] still apply. */
        fun allowAllHosts() = apply { allowAllHosts = true }

        /** Exempts these ranges (`127.0.0.1/32`, `10.0.0.0/8`, `::1/128`) from the rule against non-public addresses. */
        fun allowAddresses(vararg cidrs: String) = apply { for (c in cidrs) addresses.add(AddressRange.parse(c)) }

        fun allowInsecure(allow: Boolean) = apply { allowInsecure = allow }
        fun maxResponseBytes(bytes: Long) = apply { require(bytes in 0..Int.MAX_VALUE - 8); maxResponseBytes = bytes }
        fun maxMessageBytes(bytes: Long) = apply { require(bytes in 0..Int.MAX_VALUE - 8); maxMessageBytes = bytes }
        fun maxConcurrentRequests(n: Int) = apply { require(n >= 0); maxConcurrentRequests = n }
        fun maxWebSockets(n: Int) = apply { require(n >= 0); maxWebSockets = n }
        fun connectTimeout(millis: Long) = apply { require(millis >= 0); connectTimeoutMillis = millis }
        fun readTimeout(millis: Long) = apply { require(millis >= 0); readTimeoutMillis = millis }
        fun writeTimeout(millis: Long) = apply { require(millis >= 0); writeTimeoutMillis = millis }
        fun callTimeout(millis: Long) = apply { require(millis >= 0); callTimeoutMillis = millis }
        fun userAgent(value: String) = apply { userAgent = value }
        fun client(client: OkHttpClient) = apply { this.client = client }
        fun build() = NetOptions(this)
    }

    companion object {
        /** No host allowed: fetch and WebSocket exist but reach nothing. */
        @JvmField val DEFAULT: NetOptions = Builder().build()

        @JvmStatic fun builder() = Builder()
    }
}
