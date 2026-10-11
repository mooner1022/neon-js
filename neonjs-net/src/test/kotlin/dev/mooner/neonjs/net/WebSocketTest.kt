package dev.mooner.neonjs.net

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** WebSocket connections over OkHttp against a local server (OkHttp's own WebSocket on the other side). */
class WebSocketTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun local(): NetOptions.Builder = NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").allowInsecure(true)

    private fun ctx(options: NetOptions): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).extension(NetExtension(options)).build().newContext()

    private fun wsUrl() = "ws://${server.hostName}:${server.port}/gateway"

    /** A server side that echoes messages and answers a Close frame; [closed] gets the client's close code. */
    private fun echo(closed: CompletableFuture<Int> = CompletableFuture()) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send("echo:$text")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(code)
            webSocket.close(code, reason)
        }
    }

    @Test
    fun aConversation() {
        val closed = CompletableFuture<Int>()
        server.enqueue(MockResponse.Builder().addHeader("Sec-WebSocket-Protocol", "json").webSocketUpgrade(echo(closed)).build())
        ctx(local().build()).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('${wsUrl()}', ['etf', 'json']);
                    ws.binaryType = 'arraybuffer';
                    ws.onopen = () => { log.push('open:' + ws.protocol + ':' + ws.extensions); ws.send('hello'); ws.send(new Uint8Array([1, 2, 255])) };
                    ws.onmessage = (e) => {
                        log.push(typeof e.data === 'string' ? e.data : new Uint8Array(e.data).join('-'));
                        if (log.length === 3) ws.close(1000, 'done');
                    };
                    ws.onerror = (e) => log.push('error:' + e.message);
                    ws.onclose = (e) => { log.push('close:' + e.code + ':' + e.reason + ':' + e.wasClean); resolve(log.join()) };
                })
            """).await(10_000).asString()
            assertEquals("open:json:,echo:hello,1-2-255,close:1000:done:true", r)
        }
        assertEquals(1000, closed.get(5, TimeUnit.SECONDS))
        val handshake = server.takeRequest()
        assertEquals("etf, json", handshake.headers["Sec-WebSocket-Protocol"])
        assertNull(handshake.headers["Sec-WebSocket-Extensions"])
    }

    @Test
    fun theServerCloses() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("bye soon")
                webSocket.close(4004, "authentication failed")
            }
        }).build())
        ctx(local().build()).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('${wsUrl()}');
                    ws.onmessage = (e) => log.push(e.data);
                    ws.onclose = (e) => { log.push(e.code, e.reason, e.wasClean, ws.readyState); resolve(log.join()) };
                })
            """).await(10_000).asString()
            assertEquals("bye soon,4004,authentication failed,true,3", r)
        }
    }

    @Test
    fun aMessageOverTheLimitFailsTheConnection() {
        val closed = CompletableFuture<Int>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("small")
                webSocket.send("x".repeat(1000))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(code)
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                closed.complete(-1)
            }
        }).build())
        ctx(local().maxMessageBytes(100).build()).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('${wsUrl()}');
                    ws.onmessage = (e) => log.push(e.data);
                    ws.onerror = (e) => log.push(e.message);
                    ws.onclose = (e) => { log.push(e.code, e.wasClean); resolve(log.join()) };
                })
            """).await(10_000).asString()
            assertEquals("small,the server sent a message larger than the limit of 100 bytes,1006,false", r)
        }
        assertEquals(1009, closed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun anOpenSocketHasNoReadTimeout() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Thread {
                    Thread.sleep(600)
                    webSocket.send("after a pause")
                    webSocket.close(1000, "")
                }.start()
            }
        }).build())
        ctx(local().readTimeout(200).build()).use { c ->
            c.eval("var log = []; const ws = new WebSocket('${wsUrl()}'); ws.onmessage = (e) => log.push(e.data); ws.onerror = (e) => log.push('error:' + e.message); ws.onclose = (e) => log.push(e.code)")
            // the open socket keeps the loop waiting until it closes
            assertTrue(c.runEventLoop(10_000))
            assertEquals("after a pause,1000", c.eval("log.join()").asString())
        }
    }

    @Test
    fun thePolicyRefuses() {
        fun outcome(options: NetOptions, url: String = wsUrl()): String = ctx(options).use { c ->
            c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('$url');
                    ws.onerror = (e) => log.push(e.message);
                    ws.onclose = (e) => { log.push(e.code); resolve(log.join()) };
                })
            """).await(10_000).asString()
        }
        assertEquals("WebSocket to ${server.hostName} is not allowed by the network policy,1006", outcome(NetOptions.DEFAULT))
        assertEquals("WebSocket to ws: URLs is not allowed by the network policy (cleartext),1006",
            outcome(NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").build()))
        val loopback = outcome(NetOptions.builder().allowAllHosts().allowInsecure(true).build())
        assertTrue(loopback.contains("not a public address"), loopback)
        assertEquals("too many WebSockets open (limit 0),1006", outcome(local().maxWebSockets(0).build()))
        assertEquals(0, server.requestCount)
    }
}
