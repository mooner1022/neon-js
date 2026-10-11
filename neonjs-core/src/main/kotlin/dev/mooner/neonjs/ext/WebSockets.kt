package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*

/**
 * `WebSocket` (WebSockets Standard) over a transport ([WebNetwork.install]): the ready states, the events (`open`,
 * `message`, an `ErrorEvent` for `error` as in Node and Deno, `close`) each fired as a task, and `binaryType`. An
 * open socket keeps the event loop waiting until it is closed.
 */
internal object WebSockets {
    const val CONNECTING = 0
    const val OPEN = 1
    const val CLOSING = 2
    const val CLOSED = 3

    class JSWebSocket(proto: JSObject?) : Events.JSEventTarget(proto), Agent.ExternalSource {
        override val className: String get() = "WebSocket"
        @JvmField var url = ""
        @JvmField var origin = ""
        @JvmField var protocol = ""
        @JvmField var extensions = ""
        @JvmField var readyState = CONNECTING
        @JvmField var binaryType = "blob"
        @Volatile @JvmField var connection: WebNetwork.WebSocketConnection? = null
        @Volatile @JvmField var cancelled = false
        /** Bytes of what `send` got once the socket was closing: counted in `bufferedAmount`, never sent. */
        @JvmField var bufferedAfterClose = 0L
        /** The socket failed or closed on this side (`close()` while connecting): later reports are ignored. */
        @JvmField var ended = false

        /** Withdrawal by closing the context (any thread). */
        override fun cancel() {
            cancelled = true
            connection?.cancel()
        }
    }

    private fun syntaxError(realm: Realm, message: String) = WebGlobals.domException(realm, message, "SyntaxError")

    fun install(realm: Realm, transport: WebNetwork.WebSocketTransport) {
        val i = WebInterface.define(realm, "WebSocket", 1, parent = "EventTarget") { a, proto ->
            Idl.required(a, 1, "WebSocket constructor")
            val urlString = Idl.usvString(a[0])
            // the protocols: one string, or a sequence of them (a third argument, ws' options, is ignored)
            val protocols: List<String> = when (val p = a.arg(1)) {
                Undefined -> emptyList()
                is JSObject -> if (Ops.getMethod(p, JSSymbol.iterator) !== Undefined) Idl.sequence(realm, p, "WebSocket protocols") { Idl.domString(it) } else listOf(Idl.domString(p))
                else -> listOf(Idl.domString(p))
            }
            val url = Url.parse(urlString, null) ?: throw syntaxError(realm, "Failed to construct 'WebSocket': the URL '$urlString' is invalid")
            when (url.scheme) {
                "http" -> url.scheme = "ws"
                "https" -> url.scheme = "wss"
                "ws", "wss" -> {}
                else -> throw syntaxError(realm, "Failed to construct 'WebSocket': the URL's scheme must be 'ws' or 'wss', not '${url.scheme}'")
            }
            if (url.fragment != null) throw syntaxError(realm, "Failed to construct 'WebSocket': the URL contains a fragment identifier")
            val seen = HashSet<String>()
            for (p in protocols) {
                if (!Headers.isToken(p)) throw syntaxError(realm, "Failed to construct 'WebSocket': '$p' is not a valid subprotocol")
                if (!seen.add(p)) throw syntaxError(realm, "Failed to construct 'WebSocket': the subprotocol '$p' is given twice")
            }
            val ws = JSWebSocket(proto)
            ws.url = url.serialize()
            ws.origin = url.origin()
            connect(realm, ws, transport, protocols)
            ws
        }
        i.constant("CONNECTING", CONNECTING.toDouble())
        i.constant("OPEN", OPEN.toDouble())
        i.constant("CLOSING", CLOSING.toDouble())
        i.constant("CLOSED", CLOSED.toDouble())
        fun sock(t: Any?, m: String) = Idl.self<JSWebSocket>(t, "WebSocket", m)
        i.attribute("url", { _, t, _, _ -> sock(t, "url").url })
        i.attribute("readyState", { _, t, _, _ -> sock(t, "readyState").readyState.toDouble() })
        i.attribute("bufferedAmount", { _, t, _, _ ->
            val ws = sock(t, "bufferedAmount")
            ((if (ws.readyState == OPEN) ws.connection?.bufferedAmount() ?: 0L else 0L) + ws.bufferedAfterClose).toDouble()
        })
        Events.eventHandler(i, "onopen", "open")
        Events.eventHandler(i, "onerror", "error")
        Events.eventHandler(i, "onclose", "close")
        i.attribute("extensions", { _, t, _, _ -> sock(t, "extensions").extensions })
        i.attribute("protocol", { _, t, _, _ -> sock(t, "protocol").protocol })
        i.operation("close", 0) { f, t, a, _ ->
            val ws = sock(t, "close")
            val code = a.arg(0).let { if (it === Undefined) -1 else Idl.integer(it, 16, signed = false, what = "WebSocket.close code", clamp = true).toInt() }
            if (code != -1 && code != 1000 && code !in 3000..4999) {
                throw WebGlobals.domException(f.realm, "WebSocket.close: the code must be 1000 or between 3000 and 4999, not $code", "InvalidAccessError")
            }
            val reason = a.arg(1).let { if (it === Undefined) "" else Idl.usvString(it) }
            if (WebGlobals.utf8(reason).size > 123) throw syntaxError(f.realm, "WebSocket.close: the reason is longer than 123 bytes")
            when (ws.readyState) {
                CLOSING, CLOSED -> {}
                CONNECTING -> {
                    // "fail the WebSocket connection"
                    ws.readyState = CLOSING
                    ws.connection?.cancel()
                    failLocally(f.realm, ws, "WebSocket was closed before the connection was established")
                }
                else -> {
                    ws.readyState = CLOSING
                    ws.connection?.close(code, reason)
                }
            }
            Undefined
        }
        Events.eventHandler(i, "onmessage", "message")
        i.attribute("binaryType", { _, t, _, _ -> sock(t, "binaryType").binaryType }, { _, t, a, _ ->
            val ws = sock(t, "binaryType")
            // an enumeration attribute ignores values outside the enumeration
            val v = Idl.domString(a.arg(0))
            if (v == "blob" || v == "arraybuffer") ws.binaryType = v
            Undefined
        })
        i.operation("send", 1) { f, t, a, _ ->
            val ws = sock(t, "send")
            Idl.required(a, 1, "WebSocket.send")
            if (ws.readyState == CONNECTING) throw WebGlobals.domException(f.realm, "WebSocket.send: the connection is not open yet", "InvalidStateError")
            when (val d = a[0]) {
                is JSArrayBuffer, is JSTypedArray, is JSDataView -> sendBinary(ws, Idl.bytes(d, "WebSocket.send data", allowShared = true))
                is Blob.JSBlob -> sendBinary(ws, d.bytes())
                else -> {
                    val text = Idl.usvString(d)
                    if (ws.readyState == OPEN) ws.connection?.sendText(text) else ws.bufferedAfterClose += WebGlobals.utf8(text).size
                }
            }
            Undefined
        }
    }

    private fun sendBinary(ws: JSWebSocket, bytes: ByteArray) {
        if (ws.readyState == OPEN) ws.connection?.sendBinary(bytes) else ws.bufferedAfterClose += bytes.size
    }

    private fun connect(realm: Realm, ws: JSWebSocket, transport: WebNetwork.WebSocketTransport, protocols: List<String>) {
        val agent = realm.agent
        if (!agent.addExternalSource(ws)) {
            ws.readyState = CLOSED
            ws.ended = true
            return
        }
        val events = object : WebNetwork.WebSocketEvents {
            override fun onOpen(protocol: String, extensions: String) = agent.postExternalJob {
                if (ws.ended || ws.readyState != CONNECTING) return@postExternalJob
                ws.readyState = OPEN
                ws.protocol = protocol
                ws.extensions = extensions
                Events.dispatch(realm, ws, Events.engineEvent(realm, "open"))
            }

            override fun onText(text: String) = agent.postExternalJob {
                if (!ws.ended && ws.readyState == OPEN) message(realm, ws, text)
            }

            override fun onBinary(bytes: ByteArray) = agent.postExternalJob {
                if (ws.ended || ws.readyState != OPEN) return@postExternalJob
                agent.reserveAllocation(bytes.size.toLong())
                val data: Any = if (ws.binaryType == "arraybuffer") {
                    ArrayBufferBuiltins.allocate(realm, null, bytes.size.toLong(), -1L).also { System.arraycopy(bytes, 0, it.data, 0, bytes.size) }
                } else {
                    Blob.newBlob(realm, bytes, "")
                }
                message(realm, ws, data)
            }

            override fun onError(message: String) = agent.postExternalJob {
                if (!ws.ended) fireError(realm, ws, message)
            }

            override fun onClose(code: Int, reason: String, wasClean: Boolean) = agent.postExternalJob {
                if (!ws.ended) closed(realm, ws, code, reason, wasClean)
            }
        }
        try {
            ws.connection = transport.connect(ws.url, protocols, events)
            if (ws.cancelled) ws.connection?.cancel()
        } catch (e: RuntimeException) {
            ws.readyState = CLOSING
            failLocally(realm, ws, e.message ?: e.toString())
        }
    }

    private fun message(realm: Realm, ws: JSWebSocket, data: Any) {
        val e = Events.engineEvent(realm, Events.JSMessageEvent(realm.intrinsic("%MessageEvent.prototype%")), "message")
        e.data = data
        e.origin = ws.origin
        Events.dispatch(realm, ws, e)
    }

    private fun fireError(realm: Realm, ws: JSWebSocket, message: String) {
        val e = Events.engineEvent(realm, Events.JSErrorEvent(realm.intrinsic("%ErrorEvent.prototype%")), "error")
        e.message = message
        e.error = realm.newError(ErrorKind.TYPE, message)
        Events.dispatch(realm, ws, e)
    }

    private fun closed(realm: Realm, ws: JSWebSocket, code: Int, reason: String, wasClean: Boolean) {
        ws.ended = true
        ws.readyState = CLOSED
        realm.agent.removeExternalSource(ws)
        val e = Events.engineEvent(realm, Events.JSCloseEvent(realm.intrinsic("%CloseEvent.prototype%")), "close")
        e.code = code
        e.reason = reason
        e.wasClean = wasClean
        Events.dispatch(realm, ws, e)
    }

    /** Fails the connection on this side: `error`, then `close` (1006), as tasks; the transport's reports are ignored. */
    private fun failLocally(realm: Realm, ws: JSWebSocket, message: String) {
        ws.ended = true
        realm.agent.postExternalJob {
            fireError(realm, ws, message)
            ws.readyState = CLOSED
            realm.agent.removeExternalSource(ws)
            val e = Events.engineEvent(realm, Events.JSCloseEvent(realm.intrinsic("%CloseEvent.prototype%")), "close")
            e.code = 1006
            Events.dispatch(realm, ws, e)
        }
    }
}
