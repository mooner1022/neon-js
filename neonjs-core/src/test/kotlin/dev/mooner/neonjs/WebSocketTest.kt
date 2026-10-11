package dev.mooner.neonjs

import dev.mooner.neonjs.ext.WebNetwork
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The WebSocket interface over a fake transport: ready states, events, sends, the closing handshake and failures. */
class WebSocketTest {
    /** A connection the test drives: [events] is what the transport reports, [log] what the script asked of it. */
    private class FakeConnection(val url: String, val protocols: List<String>, val events: WebNetwork.WebSocketEvents) : WebNetwork.WebSocketConnection {
        val log = CopyOnWriteArrayList<String>()
        @Volatile var queued = 0L

        override fun sendText(text: String) {
            log.add("text:$text")
        }

        override fun sendBinary(bytes: ByteArray) {
            log.add("binary:" + bytes.joinToString("-"))
        }

        override fun close(code: Int, reason: String) {
            log.add("close:$code:$reason")
            // the server answers the Close frame
            events.onClose(if (code == -1) 1005 else code, reason, true)
        }

        override fun bufferedAmount(): Long = queued

        override fun cancel() {
            log.add("cancel")
        }
    }

    private class FakeTransport(val onConnect: (FakeConnection) -> Unit) : WebNetwork.WebSocketTransport {
        val connections = CopyOnWriteArrayList<FakeConnection>()

        override fun connect(url: String, protocols: List<String>, events: WebNetwork.WebSocketEvents): WebNetwork.WebSocketConnection {
            val c = FakeConnection(url, protocols, events)
            connections.add(c)
            onConnect(c)
            return c
        }
    }

    private fun ctx(t: WebNetwork.WebSocketTransport): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).extension { realm -> WebNetwork.install(realm, null, t) }.build().newContext()

    @Test
    fun aConversation() {
        val t = FakeTransport { c ->
            Thread {
                c.events.onOpen("chat", "")
                c.events.onText("hello")
                c.events.onBinary(byteArrayOf(1, 2))
                c.events.onBinary(byteArrayOf(3))
            }.start()
        }
        ctx(t).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('http://gateway.example/?v=10', ['chat', 'json'], { handshakeTimeout: 1000 });
                    log.push(ws.url, ws.readyState, ws.binaryType, WebSocket.OPEN, ws.CLOSED);
                    try { ws.send('early') } catch (e) { log.push(e.name) }
                    ws.onopen = (e) => { log.push('open:' + ws.protocol + ':' + ws.readyState + ':' + e.isTrusted); ws.send('hi'); ws.send(new Uint8Array([7, 8])) };
                    let n = 0;
                    ws.addEventListener('message', async (e) => {
                        n++;
                        if (n === 1) { log.push(e.data, e.origin, e instanceof MessageEvent); return }
                        if (n === 2) { log.push(e.data instanceof Blob, (await e.data.bytes()).join('-')); ws.binaryType = 'arraybuffer'; ws.binaryType = 'nope'; return }
                        log.push(e.data instanceof ArrayBuffer, new Uint8Array(e.data)[0], ws.binaryType);
                        ws.close(4000, 'bye');
                        log.push(ws.readyState);
                        ws.send('after');
                        log.push(ws.bufferedAmount);
                    });
                    ws.onclose = (e) => { log.push('close:' + e.code + ':' + e.reason + ':' + e.wasClean + ':' + ws.readyState); resolve(log.join()) };
                })
            """).await(5_000).asString()
            assertEquals("ws://gateway.example/?v=10,0,blob,1,3,InvalidStateError,open:chat:1:true,hello,ws://gateway.example,true,true,1-2,true,3,arraybuffer,2,5,close:4000:bye:true:3", r)
        }
        val conn = t.connections.single()
        assertEquals("ws://gateway.example/?v=10", conn.url)
        assertEquals(listOf("chat", "json"), conn.protocols)
        assertEquals(listOf("text:hi", "binary:7-8", "close:4000:bye"), conn.log)
    }

    @Test
    fun failures() {
        // a refused connection: an ErrorEvent, then close 1006
        val refused = FakeTransport { c -> Thread { c.events.onError("connection refused"); c.events.onClose(1006, "", false) }.start() }
        ctx(refused).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('wss://down.example/');
                    ws.onerror = (e) => log.push(e.constructor.name, e.message, e.error instanceof TypeError);
                    ws.onclose = (e) => { log.push(e.code, e.wasClean, ws.readyState); resolve(log.join()) };
                })
            """).await(5_000).asString()
            assertEquals("ErrorEvent,connection refused,true,1006,false,3", r)
        }
        // close() while connecting fails the connection on this side
        val silent = FakeTransport { }
        ctx(silent).use { c ->
            val r = c.eval("""
                new Promise((resolve) => {
                    const log = [];
                    const ws = new WebSocket('wss://slow.example/');
                    ws.onerror = () => log.push('error');
                    ws.onclose = (e) => { log.push(e.code, ws.readyState); resolve(log.join()) };
                    ws.close();
                    log.push(ws.readyState);
                })
            """).await(5_000).asString()
            assertEquals("2,error,1006,3", r)
            assertEquals(listOf("cancel"), silent.connections.single().log)
        }
        // what the constructor and close() refuse
        ctx(silent).use { c ->
            assertEquals("SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,InvalidAccessError,SyntaxError", c.eval("""
                const out = [];
                for (const f of [() => new WebSocket('ftp://a.b/'), () => new WebSocket('wss://a.b/#x'), () => new WebSocket('/relative'),
                                 () => new WebSocket('wss://a.b/', ['a', 'a']), () => new WebSocket('wss://a.b/', 'not a token')]) {
                    try { f() } catch (e) { out.push(e.name) }
                }
                const ws = new WebSocket('wss://a.b/');
                for (const f of [() => ws.close(1001), () => ws.close(1000, 'x'.repeat(124))]) {
                    try { f() } catch (e) { out.push(e.name) }
                }
                out.join()
            """).asString())
        }
    }

    @Test
    fun anOpenSocketKeepsTheLoopWaiting() {
        val t = FakeTransport { c ->
            Thread {
                c.events.onOpen("", "")
                Thread.sleep(100)
                c.events.onText("late")
                Thread.sleep(50)
                c.events.onClose(1000, "", true)
            }.start()
        }
        ctx(t).use { c ->
            c.eval("var got = []; const ws = new WebSocket('wss://a.example/'); ws.onmessage = (e) => got.push(e.data); ws.onclose = (e) => got.push(e.code)")
            assertTrue(c.runEventLoop(5_000))
            assertEquals("late,1000", c.eval("got.join()").asString())
        }
    }
}
