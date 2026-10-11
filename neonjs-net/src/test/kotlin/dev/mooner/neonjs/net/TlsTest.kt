package dev.mooner.neonjs.net

import dev.mooner.neonjs.NeonEngine
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * https and wss, as the services scripts reach are: TLS over the policy's sockets, HTTP/2 for fetch and HTTP/1.1 for
 * the WebSocket upgrade to the same server, and the TLS settings of a client the host gives.
 */
class TlsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun start() {
        server = MockWebServer()
        // the names the server may go by (its host name is only known once it is started)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
        // the host's client trusts the test certificate; nothing else of it is set
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun httpsAndWss() {
        server.enqueue(MockResponse.Builder().body("over h2").build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("echo:$text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // a Close frame without a code (1005) is answered with 1000, which OkHttp can send
                webSocket.close(if (code == 1005) 1000 else code, reason)
            }
        }).build())
        // no allowInsecure: TLS is enough
        val options = NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").client(client).build()
        NeonEngine.builder().console(null).webGlobals(true).extension(NetExtension(options)).build().newContext().use { c ->
            val r = c.eval("""
                (async () => {
                    const res = await fetch('https://${server.hostName}:${server.port}/');
                    // HTTP/2 has no reason phrase
                    const out = [res.status, JSON.stringify(res.statusText), await res.text()];
                    out.push(await new Promise((resolve) => {
                        const ws = new WebSocket('wss://${server.hostName}:${server.port}/ws');
                        ws.onopen = () => ws.send('hi');
                        ws.onmessage = (e) => { out.push(e.data); ws.close() };
                        ws.onerror = (e) => out.push('error:' + e.message);
                        ws.onclose = (e) => resolve(e.code + ':' + e.wasClean);
                    }));
                    return out.join();
                })()
            """).await(10_000).asString()
            assertEquals("200,\"\",over h2,echo:hi,1000:true", r)
        }
        assertEquals("HTTP/2", server.takeRequest().version.uppercase().let { if (it.startsWith("H2")) "HTTP/2" else it })
        assertEquals("HTTP/1.1", server.takeRequest().version.uppercase())
    }

    @Test
    fun anUntrustedCertificateFails() {
        // the default client does not trust the test certificate
        val options = NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").build()
        NeonEngine.builder().console(null).webGlobals(true).extension(NetExtension(options)).build().newContext().use { c ->
            val r = c.eval("fetch('https://${server.hostName}:${server.port}/').then(() => 'reached', (e) => e.message)").await(10_000).asString()
            assertEquals("fetch failed", r)
        }
        assertEquals(0, server.requestCount)
    }
}
