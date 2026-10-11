package dev.mooner.neonjs.net

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.node.NodeExtension
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The undici and ws modules, with and without neonjs-node, and what scripts see of the module's classes. */
class ModulesTest {
    private fun ctx(node: Boolean): NeonContext {
        val b = NeonEngine.builder().console(null).webGlobals(true).extension(NetExtension())
        if (node) b.extension(NodeExtension())
        return b.build().newContext()
    }

    @Test
    fun undiciIsTheGlobals() {
        ctx(node = true).use { c ->
            val r = c.eval("""
                (async () => {
                    const undici = require('undici');
                    const out = [undici.fetch === fetch, undici.Headers === Headers, undici.FormData === FormData, undici.WebSocket === WebSocket];
                    const agent = new undici.Agent({ connect: { timeout: 1000 } });
                    undici.setGlobalDispatcher(agent);
                    out.push(undici.getGlobalDispatcher() === agent, typeof agent.close);
                    await agent.close();
                    out.push(await undici.request('https://example.com/').catch((e) => e.code));
                    // Node's own list does not name it
                    out.push(require('module').builtinModules.includes('undici'), require('module').isBuiltin('undici'));
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("true,true,true,true,true,function,ERR_UNAVAILABLE,false,false", r)
        }
    }

    @Test
    fun wsIsTheGlobalWebSocket() {
        ctx(node = true).use { c ->
            assertEquals("1,1,true,true,true,ERR_UNAVAILABLE", c.eval("""
                const WS = require('ws');
                const ws = new WS('wss://not-allowed.example/', [], { handshakeTimeout: 1000 });
                const out = [WS.OPEN, WS.WebSocket.OPEN, ws instanceof WebSocket, ws instanceof WS, WS.WebSocket === WS];
                try { new WS.WebSocketServer({ port: 1 }) } catch (e) { out.push(e.code) }
                out.join()
            """).asString())
        }
        // as ES modules, without neonjs-node
        ctx(node = false).use { c ->
            val ns = c.evalModule("""
                import WebSocket, { WebSocket as Named } from 'ws';
                import { fetch as f, Agent } from 'undici';
                export const r = [WebSocket === Named, f === fetch, typeof Agent, typeof require].join();
            """, "m.mjs")
            assertEquals("true,true,function,undefined", ns.getMember("r").toString())
        }
    }

    @Test
    fun theModuleIsDeniedToScripts() {
        val location = java.io.File(NetExtension::class.java.protectionDomain.codeSource.location.toURI())
        val paths = if (location.isDirectory) location.walk().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        else java.util.jar.JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
        val names = paths.filter { it.startsWith("dev/mooner/neonjs/net/") && it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }
        assertTrue(names.size >= 3, "$names")
        for (n in names) assertTrue(HostAccess.ALL.isClassDenied(Class.forName(n, false, javaClass.classLoader)), n)
        // OkHttp and Okio too: a script holding a host's client could otherwise use it outside the policy
        for (c in listOf(okhttp3.OkHttpClient::class.java, okhttp3.Request::class.java, okio.Buffer::class.java)) assertTrue(HostAccess.ALL.isClassDenied(c), c.name)
    }
}
