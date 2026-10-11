package dev.mooner.neonjs.net

import dev.mooner.neonjs.ext.WebNetwork
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket connections (RFC 6455) over OkHttp: OkHttp makes the connection (DNS, TLS, the address rules of the
 * policy's socket factory) and the HTTP/1.1 upgrade, and the frames are read and written here, where the limits are:
 * a received message larger than [NetOptions.maxMessageBytes] fails the connection before it is read, and no
 * extension (permessage-deflate) is offered, so nothing is inflated. Each connection has a reader thread and writes
 * its queue on a pooled thread; an open connection has no read timeout (the protocols above have their heartbeats).
 */
internal class OkHttpWebSockets(private val clients: NetClients, private val usage: NetUsage) : WebNetwork.WebSocketTransport {
    private val options = clients.options

    override fun connect(url: String, protocols: List<String>, events: WebNetwork.WebSocketEvents): WebNetwork.WebSocketConnection {
        val httpUrl = when {
            url.startsWith("wss:") -> "https:" + url.substring(4)
            url.startsWith("ws:") -> "http:" + url.substring(3)
            else -> url
        }.toHttpUrlOrNull()
        if (httpUrl == null) return refused(events, "invalid URL $url")
        refusal(options, httpUrl, "WebSocket")?.let { return refused(events, it.replace(" to https:", " to wss:").replace(" to http:", " to ws:")) }
        if (!usage.acquire(usage.sockets, options.maxWebSockets)) return refused(events, "too many WebSockets open (limit ${options.maxWebSockets})")
        val c = Connection(events)
        c.start(httpUrl, protocols)
        return c
    }

    private fun refused(events: WebNetwork.WebSocketEvents, message: String): WebNetwork.WebSocketConnection {
        events.onError(message)
        events.onClose(1006, "", false)
        return CLOSED
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    /** A received frame that breaks the protocol (or a limit): the connection fails with this close code. */
    private class Failure(val code: Int, message: String) : IOException(message)

    private inner class Connection(private val events: WebNetwork.WebSocketEvents) : WebNetwork.WebSocketConnection {
        private val key = Base64.getEncoder().encodeToString(ByteArray(16).also { RANDOM.nextBytes(it) })
        @Volatile private var call: Call? = null
        @Volatile private var socket: okio.Socket? = null
        private var sink: BufferedSink? = null
        /** The last report (onClose) was made, or the connection was cancelled: nothing more is reported. */
        private val finished = AtomicBoolean()
        private val released = AtomicBoolean()
        private val lock = Any()
        private val queue = ArrayDeque<Frame>()
        private val queued = AtomicLong()
        private var writing = false
        @Volatile private var closeSent = false
        @Volatile private var closeReceived = false
        private var receivedCode = 1005
        private var receivedReason = ""

        fun start(url: okhttp3.HttpUrl, protocols: List<String>) {
            val b = Request.Builder().url(url)
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Key", key)
                .header("Sec-WebSocket-Version", "13")
                .header("User-Agent", options.userAgent)
            if (protocols.isNotEmpty()) b.header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
            val c = clients.upgradeClient.newCall(b.build())
            call = c
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    finish(1006, "", false, failureMessage(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        opened(response, protocols)
                    } catch (e: IOException) {
                        response.close()
                        response.socket?.cancel()
                        finish(1006, "", false, e.message ?: e.javaClass.simpleName)
                    }
                }
            })
        }

        /** Checks the server's handshake response, then starts reading. */
        private fun opened(response: Response, protocols: List<String>) {
            if (response.code != 101) throw ProtocolException("the server answered the WebSocket handshake with ${response.code} ${response.message}")
            val s = response.socket ?: throw ProtocolException("the server did not upgrade the connection")
            if (!"websocket".equals(response.header("Upgrade"), ignoreCase = true)) throw ProtocolException("the server's Upgrade header is not 'websocket'")
            val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
            if (response.header("Sec-WebSocket-Accept") != expected) throw ProtocolException("the server's Sec-WebSocket-Accept header is wrong")
            val protocol = response.header("Sec-WebSocket-Protocol") ?: ""
            if (protocol.isNotEmpty() && protocol !in protocols) throw ProtocolException("the server chose the subprotocol '$protocol', which was not offered")
            if (response.header("Sec-WebSocket-Extensions") != null) throw ProtocolException("the server chose extensions, which were not offered")
            // no read timeout once open; writes keep theirs
            s.source.timeout().timeout(0, TimeUnit.MILLISECONDS)
            socket = s
            synchronized(lock) { sink = s.sink.buffer() }
            if (finished.get()) {
                s.cancel()
                return
            }
            events.onOpen(protocol, "")
            EXECUTOR.execute { read(s) }
        }

        private fun read(s: okio.Socket) {
            val source = s.source.buffer()
            val message = Buffer()
            var messageOpcode = -1
            val max = options.maxMessageBytes
            try {
                while (true) {
                    val b0 = source.readByte().toInt() and 0xFF
                    val b1 = source.readByte().toInt() and 0xFF
                    val fin = b0 and 0x80 != 0
                    if (b0 and 0x70 != 0) throw Failure(1002, "the server sent a frame with reserved bits set")
                    val opcode = b0 and 0x0F
                    if (b1 and 0x80 != 0) throw Failure(1002, "the server sent a masked frame")
                    var length = (b1 and 0x7F).toLong()
                    if (length == 126L) length = source.readShort().toLong() and 0xFFFF
                    else if (length == 127L) length = source.readLong()
                    if (length < 0) throw Failure(1002, "the server sent a frame of negative length")
                    when (opcode) {
                        OP_CLOSE, OP_PING, OP_PONG -> {
                            if (!fin || length > 125) throw Failure(1002, "the server sent a fragmented or too long control frame")
                            val payload = source.readByteArray(length)
                            if (opcode == OP_PING) {
                                enqueue(OP_PONG, payload)
                            } else if (opcode == OP_CLOSE) {
                                receivedClose(payload)
                                return
                            }
                        }
                        OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                            if (opcode == OP_CONTINUATION) {
                                if (messageOpcode == -1) throw Failure(1002, "the server sent a continuation frame without a message")
                            } else {
                                if (messageOpcode != -1) throw Failure(1002, "the server began a message inside another")
                                messageOpcode = opcode
                            }
                            if (message.size + length > max) throw Failure(1009, "the server sent a message larger than the limit of $max bytes")
                            source.readFully(message, length)
                            if (fin) {
                                val bytes = message.readByteArray()
                                if (messageOpcode == OP_TEXT) events.onText(utf8(bytes)) else events.onBinary(bytes)
                                messageOpcode = -1
                            }
                        }
                        else -> throw Failure(1002, "the server sent a frame with the unknown opcode $opcode")
                    }
                }
            } catch (e: Failure) {
                fail(e.code, e.message!!)
            } catch (e: IOException) {
                // the connection ended without a closing handshake (or was cancelled: nothing is reported then)
                s.cancel()
                finish(1006, "", false, null)
            }
        }

        private fun utf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw Failure(1007, "the server sent text that is not UTF-8")
        }

        private fun receivedClose(payload: ByteArray) {
            if (payload.size == 1) throw Failure(1002, "the server sent a Close frame of one byte")
            var code = 1005
            var reason = ""
            if (payload.size >= 2) {
                code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                if (!(code in 1000..1003 || code in 1007..1014 || code in 3000..4999)) throw Failure(1002, "the server sent the close code $code")
                reason = utf8(payload.copyOfRange(2, payload.size))
            }
            receivedCode = code
            receivedReason = reason
            closeReceived = true
            if (closeSent) {
                // the answer to ours: the closing handshake is done
                socket?.cancel()
                finish(code, reason, true, null)
            } else {
                // answer with the same code; the connection is closed once that is written
                enqueue(OP_CLOSE, if (payload.size >= 2) payload.copyOfRange(0, 2) else ByteArray(0), close = true)
            }
        }

        /** Fails the connection: a Close frame with [code] if it can still be sent, then the end, reported with [message]. */
        private fun fail(code: Int, message: String) {
            finish(1006, "", false, message)
            enqueue(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()), close = true)
            SCHEDULER.schedule({ socket?.cancel() }, 1, TimeUnit.SECONDS)
        }

        private fun finish(code: Int, reason: String, wasClean: Boolean, error: String?) {
            if (released.compareAndSet(false, true)) usage.sockets.decrementAndGet()
            if (!finished.compareAndSet(false, true)) return
            if (error != null) events.onError(error)
            events.onClose(code, reason, wasClean)
        }

        private fun enqueue(opcode: Int, payload: ByteArray, close: Boolean = false) {
            synchronized(lock) {
                if (closeSent) return
                if (close) closeSent = true
                if (!close && queued.get() + payload.size > MAX_QUEUE) {
                    // as OkHttp's: a peer that stops reading does not make the host buffer without end
                    closeSent = true
                    EXECUTOR.execute { fail(1009, "the WebSocket send buffer exceeds ${MAX_QUEUE shr 20} MiB") }
                    return
                }
                queue.add(Frame(opcode, payload))
                queued.addAndGet(payload.size.toLong())
                if (writing) return
                writing = true
            }
            EXECUTOR.execute { drain() }
        }

        private fun drain() {
            while (true) {
                val frame: Frame
                val out: BufferedSink
                synchronized(lock) {
                    val f = queue.poll()
                    val s = sink
                    if (f == null || s == null) {
                        writing = false
                        return
                    }
                    frame = f
                    out = s
                }
                try {
                    write(out, frame)
                } catch (_: IOException) {
                    socket?.cancel()
                    finish(1006, "", false, null)
                    synchronized(lock) {
                        queue.clear()
                        queued.set(0)
                        writing = false
                    }
                    return
                }
                queued.addAndGet(-frame.payload.size.toLong())
                if (frame.opcode == OP_CLOSE) {
                    if (closeReceived) {
                        socket?.cancel()
                        finish(receivedCode, receivedReason, true, null)
                    } else {
                        // the server has this long to answer before the connection is dropped
                        SCHEDULER.schedule({
                            socket?.cancel()
                            finish(1006, "", false, null)
                        }, CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
        }

        private fun write(out: BufferedSink, frame: Frame) {
            val p = frame.payload
            out.writeByte(0x80 or frame.opcode)
            when {
                p.size <= 125 -> out.writeByte(0x80 or p.size)
                p.size <= 0xFFFF -> {
                    out.writeByte(0x80 or 126)
                    out.writeShort(p.size)
                }
                else -> {
                    out.writeByte(0x80 or 127)
                    out.writeLong(p.size.toLong())
                }
            }
            val mask = ByteArray(4)
            RANDOM.nextBytes(mask)
            out.write(mask)
            val masked = ByteArray(p.size)
            for (i in p.indices) masked[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
            out.write(masked)
            out.flush()
        }

        override fun sendText(text: String) = enqueue(OP_TEXT, text.toByteArray(Charsets.UTF_8))

        override fun sendBinary(bytes: ByteArray) = enqueue(OP_BINARY, bytes)

        override fun close(code: Int, reason: String) {
            val payload = if (code < 0) ByteArray(0) else byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray(Charsets.UTF_8)
            enqueue(OP_CLOSE, payload, close = true)
        }

        override fun bufferedAmount(): Long = queued.get()

        override fun cancel() {
            finished.set(true)
            if (released.compareAndSet(false, true)) usage.sockets.decrementAndGet()
            call?.cancel()
            socket?.cancel()
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_QUEUE = 16L shl 20
        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private val RANDOM = SecureRandom()

        private val CLOSED = object : WebNetwork.WebSocketConnection {
            override fun sendText(text: String) {}
            override fun sendBinary(bytes: ByteArray) {}
            override fun close(code: Int, reason: String) {}
            override fun bufferedAmount(): Long = 0
            override fun cancel() {}
        }

        /** The readers and writers of all connections (daemon threads). */
        private val EXECUTOR: ExecutorService by lazy {
            Executors.newCachedThreadPool { r -> Thread(r, "neonjs-websocket").also { it.isDaemon = true } }
        }

        private val SCHEDULER: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "neonjs-websocket-timeouts").also { it.isDaemon = true } }
        }
    }
}
