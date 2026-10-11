package dev.mooner.neonjs.net

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** fetch over OkHttp against a local server, within the policy. */
class FetchTest {
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

    /** The local server allowed: its host, loopback addresses and cleartext. */
    private fun local(): NetOptions.Builder = NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").allowInsecure(true)

    private fun ctx(options: NetOptions, policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).sandbox(policy).extension(NetExtension(options)).build().newContext()

    private fun url(path: String = "/") = server.url(path).toString()

    @Test
    fun anExchange() {
        server.enqueue(MockResponse.Builder().code(201).addHeader("Content-Type", "application/json").addHeader("X-Rate", "5").body("{\"id\":\"1\"}").build())
        ctx(local().build()).use { c ->
            val r = c.eval("""
                (async () => {
                    const res = await fetch('${url("/api/x?y=1")}', { method: 'POST', body: JSON.stringify({ a: 1 }),
                        headers: { 'Content-Type': 'application/json', Authorization: 'Bot t', Host: 'evil.example', 'Content-Length': '1' } });
                    return [res.status, res.statusText, res.headers.get('x-rate'), (await res.json()).id, res.url].join();
                })()
            """).await(10_000).asString()
            assertEquals("201,OK,5,1,${url("/api/x?y=1")}", r)
        }
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/x?y=1", req.target)
        assertEquals("{\"a\":1}", req.body!!.utf8())
        assertEquals("Bot t", req.headers["Authorization"])
        assertEquals("*/*", req.headers["Accept"])
        // the transport's own: Host and Content-Length follow the request, not the script
        assertEquals("${server.hostName}:${server.port}", req.headers["Host"])
        assertEquals("7", req.headers["Content-Length"])
        assertTrue(req.headers["User-Agent"]!!.startsWith("NeonJS/"), req.headers["User-Agent"])
    }

    @Test
    fun decodedBodiesAndRedirects() {
        val gz = Buffer()
        GzipSink(gz).buffer().use { it.writeUtf8("unzipped") }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(gz).build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/next").build())
        server.enqueue(MockResponse.Builder().body("landed").build())
        ctx(local().build()).use { c ->
            val r = c.eval("""
                (async () => {
                    const a = await fetch('${url("/gz")}');
                    const b = await fetch('${url("/start")}', { method: 'POST', body: 'x' });
                    return [await a.text(), b.redirected, b.url.endsWith('/next'), await b.text()].join();
                })()
            """).await(10_000).asString()
            assertEquals("unzipped,true,true,landed", r)
        }
        assertEquals("gzip", server.takeRequest().headers["Accept-Encoding"])
        assertEquals("POST", server.takeRequest().method)
        val next = server.takeRequest()
        assertEquals("GET /next", "${next.method} ${next.target}")
    }

    @Test
    fun thePolicyRefuses() {
        fun cause(options: NetOptions, target: String = url()): String = ctx(options).use { c ->
            c.eval("fetch('$target').then(() => 'reached', (e) => e.message + ': ' + e.cause.message)").await(10_000).asString()
        }
        // nothing is allowed by default
        assertTrue(cause(NetOptions.DEFAULT).endsWith("is not allowed by the network policy"))
        // an allowed host whose address is loopback: refused before connecting
        val loopback = cause(NetOptions.builder().allowHosts(server.hostName).allowInsecure(true).build())
        assertTrue(loopback.contains("not a public address"), loopback)
        assertTrue(cause(NetOptions.builder().allowAllHosts().allowInsecure(true).build(), "http://127.0.0.1:${server.port}/").contains("not a public address"))
        assertTrue(cause(NetOptions.builder().allowAllHosts().allowInsecure(true).build(), "http://[::1]:${server.port}/").contains("not a public address"))
        // cleartext needs allowInsecure
        assertTrue(cause(NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").build()).contains("(cleartext)"))
        assertEquals(0, server.requestCount)
        // a redirect to a host that is not allowed is refused too
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "http://not-allowed.example/").build())
        assertTrue(cause(local().build()).endsWith("fetch to not-allowed.example is not allowed by the network policy"))
    }

    @Test
    fun limits() {
        server.enqueue(MockResponse.Builder().body("x".repeat(100)).build())
        ctx(local().maxResponseBytes(10).build()).use { c ->
            val r = c.eval("fetch('${url()}').then(() => 'read', (e) => e.cause.message)").await(10_000).asString()
            assertEquals("the response body (100 bytes) exceeds the limit of 10 bytes", r)
        }
        server.enqueue(MockResponse.Builder().headersDelay(500, TimeUnit.MILLISECONDS).body("slow").build())
        server.enqueue(MockResponse.Builder().body("next").build())
        ctx(local().maxConcurrentRequests(1).build()).use { c ->
            val r = c.eval("""
                (async () => {
                    const slow = fetch('${url()}').then((r) => r.text());
                    const second = await fetch('${url()}').then(() => 'reached', (e) => e.cause.message);
                    // the slot is free again once the first is done
                    const after = [await slow, await fetch('${url()}').then((r) => r.text())];
                    return [second, ...after].join();
                })()
            """).await(10_000).asString()
            assertEquals("too many fetches in flight (limit 1),slow,next", r)
        }
    }

    @Test
    fun anAbortCancelsTheCall() {
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("late").build())
        ctx(local().build()).use { c ->
            val start = System.nanoTime()
            val r = c.eval("fetch('${url()}', { signal: AbortSignal.timeout(100) }).then(() => 'read', (e) => e.name)").await(10_000).asString()
            assertEquals("TimeoutError", r)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun addressRules() {
        fun public(a: String) = AddressRules.isPublic(java.net.InetAddress.getByName(a))
        for (a in listOf("8.8.8.8", "1.1.1.1", "2606:4700::1111", "64:ff9b::808:808", "2002:808:808::1")) assertTrue(public(a), a)
        for (a in listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1",
            "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "::ffff:10.0.0.1", "64:ff9b::a00:1",
            "2002:a00:1::1", "2001:db8::1",
        )) assertFalse(public(a), a)
        val o = NetOptions.builder().allowAddresses("10.0.0.0/8").build()
        assertTrue(AddressRules.allows(o, java.net.InetAddress.getByName("10.9.9.9")))
        assertFalse(AddressRules.allows(o, java.net.InetAddress.getByName("192.168.0.1")))
    }

    @Test
    fun hostPatterns() {
        val p = HostPattern.parse("*.discord.gg")
        assertTrue(p.matches("gateway.discord.gg", 443))
        assertTrue(p.matches("a.b.discord.gg", 443))
        assertFalse(p.matches("discord.gg", 443))
        assertFalse(p.matches("evildiscord.gg", 443))
        assertTrue(HostPattern.parse("Discord.com").matches("discord.com", 443))
        assertFalse(HostPattern.parse("discord.com:8443").matches("discord.com", 443))
        assertTrue(HostPattern.parse("[::1]:80").matches("[::1]", 80))
        assertTrue(HostPattern.parse("0:0::1").matches("[::1]", 1))
        assertTrue(HostPattern.parse("127.0.0.1").matches("127.0.0.1", 9))
        assertTrue(HostPattern.parse("bücher.example").matches("xn--bcher-kva.example", 443))
    }
}
