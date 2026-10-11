package dev.mooner.neonjs.ext

import dev.mooner.neonjs.runtime.Realm

/**
 * The network under `fetch` and `WebSocket`, for engine modules: neonjs-net's `NetExtension` gives a realm its
 * transports (with its policy) through [install]; without them the realm has neither global. The web globals do the
 * standards' part on the context's thread (requests and responses, redirects, header guards, aborts, ready states and
 * events); a transport moves bytes, decides what may be reached, and calls back from any thread.
 */
object WebNetwork {
    /** One HTTP exchange of a fetch: the transport makes exactly this request and follows no redirect. */
    class Request(
        @JvmField val method: String,
        /** An absolute `http:` or `https:` URL, without fragment. */
        @JvmField val url: String,
        /** The headers in order, none of those the request guard drops (`Host`, `Content-Length`, `Connection`...). */
        @JvmField val headers: List<Pair<String, String>>,
        @JvmField val body: ByteArray?,
    )

    class Response(
        @JvmField val status: Int,
        @JvmField val statusText: String,
        @JvmField val headers: List<Pair<String, String>>,
        /** The whole body with its content coding removed, or null for none. */
        @JvmField val body: ByteArray?,
    )

    /** An exchange or connection a transport started. */
    fun interface Cancellable {
        /** Ends it at once; it reports nothing more (any thread, any number of times). */
        fun cancel()
    }

    interface FetchTransport {
        /**
         * Makes the exchange [request] and reports its outcome once to [callback], from any thread (this one
         * included). A refusal by policy, a network error or a limit reached is a failure.
         */
        fun send(request: Request, callback: FetchCallback): Cancellable
    }

    interface FetchCallback {
        fun onResponse(response: Response)

        /** [message] says why: the `cause` of the TypeError that fetch rejects with. */
        fun onFailure(message: String)
    }

    interface WebSocketTransport {
        /**
         * Opens a connection to [url] (`ws:` or `wss:`, without fragment) offering [protocols], reporting to [events]
         * from any thread (this one included). A refusal by policy is a failure reported to [events].
         */
        fun connect(url: String, protocols: List<String>, events: WebSocketEvents): WebSocketConnection
    }

    /** An open (or opening) connection; called on the context's thread only. */
    interface WebSocketConnection : Cancellable {
        fun sendText(text: String)

        fun sendBinary(bytes: ByteArray)

        /** Starts the closing handshake; [code] is -1 for a Close frame without a status code. */
        fun close(code: Int, reason: String)

        /** Bytes of messages queued and not yet sent. */
        fun bufferedAmount(): Long
    }

    /** What a connection reports, in this order: [onOpen] (unless it fails first), messages, [onError]?, [onClose]. */
    interface WebSocketEvents {
        fun onOpen(protocol: String, extensions: String)

        fun onText(text: String)

        fun onBinary(bytes: ByteArray)

        /** The connection failed (refused, handshake or protocol error, a network error, a limit); [onClose] follows. */
        fun onError(message: String)

        /** The connection is closed: after the closing handshake ([wasClean]) or not (code 1006). The last report. */
        fun onClose(code: Int, reason: String, wasClean: Boolean)
    }

    /**
     * Gives [realm] `fetch` over [fetch] and `WebSocket` over [webSocket] (either may be null). Needs the web globals
     * (`NeonEngine.builder().webGlobals(true)`).
     */
    @JvmStatic
    fun install(realm: Realm, fetch: FetchTransport?, webSocket: WebSocketTransport?) {
        check(WebGlobals.timersOf(realm) != null) { "fetch and WebSocket need the web globals: NeonEngine.builder().webGlobals(true)" }
        if (fetch != null) Fetch.installFetch(realm, fetch)
        if (webSocket != null) WebSockets.install(realm, webSocket)
    }
}
