package dev.mooner.neonjs

import dev.mooner.neonjs.ext.WebNetwork
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Request, Response and fetch over a fake transport, and the web globals without one. */
class FetchTest {
    /** Answers each exchange with [answer] (null: a failure) on another thread, and keeps what it was sent. */
    private class FakeTransport(val answer: (WebNetwork.Request) -> WebNetwork.Response?) : WebNetwork.FetchTransport {
        val sent = CopyOnWriteArrayList<WebNetwork.Request>()
        val cancelled = AtomicBoolean()
        @Volatile var delayMillis = 0L

        override fun send(request: WebNetwork.Request, callback: WebNetwork.FetchCallback): WebNetwork.Cancellable {
            sent.add(request)
            val t = Thread {
                try {
                    Thread.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (cancelled.get()) return@Thread
                val r = answer(request)
                if (r == null) callback.onFailure("connection refused") else callback.onResponse(r)
            }
            t.isDaemon = true
            t.start()
            return WebNetwork.Cancellable { cancelled.set(true); t.interrupt() }
        }
    }

    private fun ctx(transport: WebNetwork.FetchTransport?): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).extension { realm -> WebNetwork.install(realm, transport, null) }.build().newContext()

    private fun ok(body: String, vararg headers: Pair<String, String>, status: Int = 200) =
        WebNetwork.Response(status, "OK", headers.toList(), body.toByteArray())

    @Test
    fun withoutATransportThereIsNoFetch() {
        NeonEngine.builder().console(null).webGlobals(true).build().newContext().use { c ->
            assertEquals("undefined,undefined,function,function,function,function", c.eval(
                "[typeof fetch, typeof WebSocket, typeof Request, typeof Response, typeof MessageEvent, typeof CloseEvent].join()",
            ).asString())
        }
    }

    @Test
    fun responses() {
        ctx(null).use { c ->
            val r = c.eval("""
                (async () => {
                    const out = [];
                    const r = new Response('hi', { status: 201, statusText: 'Made', headers: { 'X-A': '1' } });
                    out.push(r.status, r.ok, r.statusText, r.headers.get('content-type'), r.type, r.url, r.bodyUsed);
                    const c = r.clone();
                    out.push(await r.text(), r.bodyUsed, await c.text());
                    out.push(await r.text().catch((e) => e.name));
                    out.push(JSON.stringify(await Response.json({ a: [1] }).json()), Response.json(1).headers.get('content-type'));
                    const e = Response.error();
                    out.push(e.type, e.status);
                    try { e.headers.set('a', 'b') } catch (x) { out.push(x.name) }
                    const red = Response.redirect('https://example.com/x', 301);
                    out.push(red.status, red.headers.get('location'));
                    for (const bad of [() => new Response('', { status: 99 }), () => new Response('x', { status: 204 }), () => Response.redirect('https://a.b/', 200)]) {
                        try { bad() } catch (x) { out.push(x.name) }
                    }
                    // a null body is never used
                    const empty = new Response(null);
                    out.push(await empty.text() + '|' + await empty.text(), empty.bodyUsed);
                    out.push(new Uint8Array(await new Response(new Uint8Array([1, 2, 3])).arrayBuffer()).join('-'));
                    out.push((await new Response(new Blob(['abc'], { type: 'Text/Plain' })).blob()).type);
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("201,true,Made,text/plain;charset=UTF-8,default,,false,hi,true,hi,TypeError,{\"a\":[1]},application/json,error,0,TypeError," +
                "301,https://example.com/x,RangeError,TypeError,RangeError,|,false,1-2-3,text/plain", r)
        }
    }

    @Test
    fun requests() {
        ctx(null).use { c ->
            val r = c.eval("""
                (async () => {
                    const out = [];
                    const q = new Request('https://example.com/a?b#frag', { method: 'post', body: 'x',
                        headers: { Host: 'evil', 'Content-Length': '9', Cookie: 'c=1', 'Proxy-Authorization': 'p', 'X-Ok': 'y' } });
                    out.push(q.method, q.url, [...q.headers].map((e) => e.join(':')).join(' '));
                    q.headers.set('Transfer-Encoding', 'chunked');
                    out.push(q.headers.has('transfer-encoding'), q.redirect, q.mode, q.credentials, q.referrer, q.signal instanceof AbortSignal);
                    out.push(new Request('https://a.b/', { method: 'patch' }).method, new Request('https://a.b/', { method: 'delete' }).method);
                    for (const bad of [() => new Request('/relative'), () => new Request('https://u:p@a.b/'), () => new Request('https://a.b/', { method: 'TRACE' }),
                                       () => new Request('https://a.b/', { body: 'x' }), () => new Request('https://a.b/', { redirect: 'nope' })]) {
                        try { bad() } catch (x) { out.push(x.name) }
                    }
                    // a Request made from another takes its body
                    const r2 = new Request(q);
                    out.push(q.bodyUsed, await r2.text());
                    try { new Request(q) } catch (x) { out.push(x.name) }
                    // the signal follows the given one
                    const ac = new AbortController();
                    const s = new Request('https://a.b/', { signal: ac.signal }).signal;
                    ac.abort('why');
                    out.push(s.aborted, s.reason);
                    // FormData and URLSearchParams bodies, and back
                    const fd = new FormData();
                    fd.append('name', 'line1\nline2');
                    fd.append('file', new Blob(['data'], { type: 'text/plain' }), 'a"b.txt');
                    const m = new Request('https://a.b/', { method: 'POST', body: fd });
                    const ct = m.headers.get('content-type');
                    out.push(ct.startsWith('multipart/form-data; boundary='));
                    const back = await m.clone().formData();
                    out.push(back.get('name') === 'line1\r\nline2', back.get('file').name, await back.get('file').text());
                    const raw = await m.text();
                    out.push(raw.includes('filename="a%22b.txt"'));
                    const u = new Request('https://a.b/', { method: 'POST', body: new URLSearchParams({ q: 'a b', r: '&' }) });
                    out.push(u.headers.get('content-type'), [...(await u.formData())].join(' '));
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("POST,https://example.com/a?b#frag,content-type:text/plain;charset=UTF-8 cookie:c=1 x-ok:y,false,follow,cors,same-origin," +
                "about:client,true,patch,DELETE,TypeError,TypeError,TypeError,TypeError,TypeError,true,x,TypeError,true,why,true,true,a%22b.txt,data,true," +
                "application/x-www-form-urlencoded;charset=UTF-8,q,a b r,&", r)
        }
    }

    @Test
    fun fetchMakesTheExchangeAndBuildsTheResponse() {
        val t = FakeTransport { ok("{\"ok\":true}", "Content-Type" to "application/json", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2") }
        ctx(t).use { c ->
            val r = c.eval("""
                (async () => {
                    const res = await fetch('https://api.example.com/v1/x?y=1#f', { method: 'PUT', body: JSON.stringify({ a: 1 }),
                        headers: { Authorization: 'Bot t', 'Content-Type': 'application/json', Host: 'x' }, dispatcher: undefined });
                    const out = [res.status, res.ok, res.url, res.redirected, res.type, res.headers.get('content-type'), res.headers.getSetCookie().join('|')];
                    try { res.headers.set('a', 'b') } catch (e) { out.push(e.name) }
                    out.push(JSON.stringify(await res.json()));
                    const head = await fetch('https://api.example.com/', { method: 'HEAD' });
                    out.push(head.bodyUsed, await head.text() === '');
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("200,true,https://api.example.com/v1/x?y=1,false,basic,application/json,a=1|b=2,TypeError,{\"ok\":true},false,true", r)
        }
        val first = t.sent[0]
        assertEquals("PUT", first.method)
        assertEquals("https://api.example.com/v1/x?y=1", first.url)
        assertEquals(listOf("Authorization" to "Bot t", "Content-Type" to "application/json", "Accept" to "*/*"), first.headers)
        assertEquals("{\"a\":1}", String(first.body!!))
    }

    @Test
    fun redirects() {
        val t = FakeTransport { req ->
            when {
                req.url.endsWith("/post") -> WebNetwork.Response(302, "Found", listOf("Location" to "/landed"), null)
                req.url.endsWith("/keep") -> WebNetwork.Response(307, "Temporary Redirect", listOf("Location" to "https://other.example/landed"), null)
                req.url.endsWith("/loop") -> WebNetwork.Response(301, "Moved", listOf("Location" to "/loop"), null)
                else -> ok(req.method + ' ' + (req.body?.let { String(it) } ?: "-") + ' ' + req.headers.joinToString(";") { it.first.lowercase() })
            }
        }
        ctx(t).use { c ->
            val r = c.eval("""
                (async () => {
                    const out = [];
                    const a = await fetch('https://api.example.com/post', { method: 'POST', body: 'b', headers: { Authorization: 'x' } });
                    out.push(a.url, a.redirected, await a.text());
                    const b = await fetch('https://api.example.com/keep', { method: 'POST', body: 'b', headers: { Authorization: 'x', Cookie: 'c', 'X-K': '1' } });
                    out.push(b.url, await b.text());
                    out.push(await fetch('https://api.example.com/post', { method: 'POST', body: 'b', redirect: 'error' }).catch((e) => e.cause.message));
                    const m = await fetch('https://api.example.com/post', { method: 'POST', body: 'b', redirect: 'manual' });
                    out.push(m.status, m.headers.get('location'));
                    out.push(await fetch('https://api.example.com/loop').catch((e) => e.message + ':' + e.cause.message));
                    return out.join('|');
                })()
            """).await(10_000).asString()
            assertEquals("https://api.example.com/landed|true|GET - authorization;accept|https://other.example/landed|POST b x-k;content-type;accept|" +
                "unexpected redirect|302|/landed|fetch failed:redirect count exceeded", r)
        }
        // the loop: the first request and 20 redirects
        assertEquals(21, t.sent.count { it.url.endsWith("/loop") })
    }

    @Test
    fun failuresAndAborts() {
        val t = FakeTransport { null }
        ctx(t).use { c ->
            val r = c.eval("""
                (async () => {
                    const out = [];
                    await fetch('https://down.example/').catch((e) => out.push(e.name, e.message, e.cause.message));
                    await fetch('ftp://a.b/').catch((e) => out.push(e.cause.message));
                    await fetch('https://a.b/', { signal: AbortSignal.abort() }).catch((e) => out.push(e.name));
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("TypeError,fetch failed,connection refused,fetch supports http: and https: URLs only, not ftp:,AbortError", r)
            assertEquals(1, t.sent.size)
        }
        val slow = FakeTransport { ok("late") }
        slow.delayMillis = 10_000
        ctx(slow).use { c ->
            val r = c.eval("""
                const ac = new AbortController();
                const p = fetch('https://slow.example/', { signal: ac.signal }).catch((e) => e);
                setTimeout(() => ac.abort(new Error('stop')), 20);
                p.then((e) => e.message)
            """).await(5_000).asString()
            assertEquals("stop", r)
            assertTrue(slow.cancelled.get())
        }
    }

    @Test
    fun aPendingFetchKeepsTheLoopWaiting() {
        val t = FakeTransport { ok("done") }
        t.delayMillis = 100
        ctx(t).use { c ->
            c.eval("var got; fetch('https://a.example/').then((r) => r.text()).then((s) => { got = s })")
            assertTrue(c.runEventLoop(5_000))
            assertEquals("done", c.eval("got").asString())
        }
    }
}
