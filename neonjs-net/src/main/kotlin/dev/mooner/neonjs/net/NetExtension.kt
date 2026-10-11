package dev.mooner.neonjs.net

import dev.mooner.neonjs.NeonExtension
import dev.mooner.neonjs.ext.WebNetwork
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Modules
import dev.mooner.neonjs.vm.Promises

/**
 * `fetch` and `WebSocket` for the contexts of an engine, over OkHttp, within [options]' network policy:
 * `NeonEngine.builder().webGlobals(true).extension(NetExtension(NetOptions.builder().allowHosts("api.example.com").build()))`.
 * Also the modules `undici` and `ws` (importable, and requirable with neonjs-node), which give libraries written for
 * those packages the same `fetch` and `WebSocket`. Needs the web globals. The engine's contexts share one client
 * (its connection pool); each context has its own limits of fetches and sockets.
 */
class NetExtension @JvmOverloads constructor(val options: NetOptions = NetOptions.DEFAULT) : NeonExtension {
    private val clients by lazy { NetClients(options) }

    override fun install(realm: Realm) {
        val usage = NetUsage()
        WebNetwork.install(realm, OkHttpFetch(clients, usage), OkHttpWebSockets(clients, usage))
        Modules.defineBuiltin(realm, Modules.Builtin("undici") { undici(realm) })
        Modules.defineBuiltin(realm, Modules.Builtin("ws") { ws(realm) })
    }

    private fun global(realm: Realm, name: String): Any? = realm.globalObject.let { it.get(name, it) }

    /** An Error with a `code`, as Node's are. */
    private fun unavailable(realm: Realm, what: String): JSException {
        val e = realm.newError(ErrorKind.ERROR, "$what is not available in NeonJS")
        e.defineOwn("code", "ERR_UNAVAILABLE", Attr.WC)
        return JSException(e)
    }

    /**
     * undici: its web classes are the globals; dispatchers (`Agent`...) can be made and set but change nothing (the
     * policy's client does the connecting), and `request` / `stream` / `connect` are unavailable (rejected promises).
     */
    private fun undici(realm: Realm): JSObject {
        val o = JSObject(realm.objectPrototype)
        for (n in listOf("fetch", "Request", "Response", "Headers", "FormData", "File", "WebSocket", "MessageEvent", "CloseEvent", "ErrorEvent")) {
            o.createDataProperty(n, global(realm, n))
        }
        val dispatcherProto = JSObject(realm.objectPrototype)
        for (m in listOf("close", "destroy")) {
            dispatcherProto.defineOwn(m, NativeFunction(realm, m, 0, { f, _, _, _ ->
                Promises.promiseResolve(f.realm, f.realm.promiseConstructor, Undefined)
            }), Attr.WC)
        }
        var globalDispatcher: Any? = null
        for (n in listOf("Dispatcher", "Agent", "Pool", "Client", "ProxyAgent", "EnvHttpProxyAgent", "RetryAgent")) {
            val proto = JSObject(dispatcherProto)
            val ctor = NativeFunction(realm, n, 0, { f, _, _, nt ->
                if (nt == null) throw JSException(f.realm.newError(ErrorKind.TYPE, "Class constructor $n cannot be invoked without 'new'"))
                JSObject(Ops.getPrototypeFromConstructor(nt) { proto })
            }, isConstructor = true)
            ctor.defineOwn("prototype", proto, Attr.NONE)
            proto.defineOwn("constructor", ctor, Attr.WC)
            o.createDataProperty(n, ctor)
            if (n == "Agent") globalDispatcher = JSObject(proto)
        }
        o.createDataProperty("setGlobalDispatcher", NativeFunction(realm, "setGlobalDispatcher", 1, { _, _, a, _ ->
            globalDispatcher = a.arg(0)
            Undefined
        }))
        o.createDataProperty("getGlobalDispatcher", NativeFunction(realm, "getGlobalDispatcher", 0, { _, _, _, _ -> globalDispatcher }))
        for (n in listOf("request", "stream", "pipeline", "connect", "upgrade")) {
            o.createDataProperty(n, NativeFunction(realm, n, 2, { f, _, _, _ ->
                val p = Promises.newPromise(f.realm)
                Promises.rejectPromise(f.realm, p, unavailable(f.realm, "undici.$n (use fetch)").value)
                p
            }))
        }
        return o
    }

    /**
     * ws: `WebSocket` is the global one (made through a constructor of ws' name, with the same prototype and ready
     * state constants), so a library choosing between ws and the global one gets the same class; no server.
     */
    private fun ws(realm: Realm): JSObject {
        val webSocket = global(realm, "WebSocket") as JSObject
        val proto = webSocket.get("prototype", webSocket)
        val ctor = NativeFunction(realm, "WebSocket", 2, { f, _, a, nt ->
            if (nt == null) throw JSException(f.realm.newError(ErrorKind.TYPE, "Class constructor WebSocket cannot be invoked without 'new'"))
            // the global constructor's arguments: the URL and the protocols (ws' options are ignored)
            Ops.construct(webSocket, if (a.size > 2) a.copyOf(2) else a)
        }, isConstructor = true)
        ctor.defineOwn("prototype", proto, Attr.NONE)
        for ((i, n) in listOf("CONNECTING", "OPEN", "CLOSING", "CLOSED").withIndex()) ctor.defineOwn(n, i.toDouble(), Attr.ENUMERABLE)
        ctor.defineOwn("WebSocket", ctor, Attr.ALL)
        val server = NativeFunction(realm, "WebSocketServer", 1, { f, _, _, _ -> throw unavailable(f.realm, "ws.WebSocketServer") }, isConstructor = true)
        ctor.defineOwn("WebSocketServer", server, Attr.ALL)
        ctor.defineOwn("Server", server, Attr.ALL)
        ctor.defineOwn("createWebSocketStream", NativeFunction(realm, "createWebSocketStream", 1, { f, _, _, _ -> throw unavailable(f.realm, "ws.createWebSocketStream") }), Attr.ALL)
        return ctor
    }
}
