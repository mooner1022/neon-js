package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `Event`, `CustomEvent` and `EventTarget` of the DOM Standard, for targets outside any tree: an event is dispatched to
 * its target alone (capture listeners, then the others), so `bubbles` and `composed` only report what was asked. Also
 * the event classes of other standards that the web globals fire: `MessageEvent` and `ErrorEvent` (HTML) and
 * `CloseEvent` (WebSockets).
 */
internal object Events {
    /** An event: the DOM Standard's internal state and flags. */
    open class JSEvent(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "Event"
        @JvmField var type = ""
        @JvmField var target: JSObject? = null
        @JvmField var currentTarget: JSObject? = null
        @JvmField var eventPhase = NONE
        @JvmField var bubbles = false
        @JvmField var cancelable = false
        @JvmField var composed = false
        @JvmField var isTrusted = false
        @JvmField var timeStamp = 0.0
        @JvmField var stopPropagation = false
        @JvmField var stopImmediatePropagation = false
        @JvmField var canceled = false
        @JvmField var inPassiveListener = false
        @JvmField var initialized = false
        @JvmField var dispatching = false

        fun initialize(type: String, bubbles: Boolean, cancelable: Boolean) {
            initialized = true
            stopPropagation = false
            stopImmediatePropagation = false
            canceled = false
            isTrusted = false
            target = null
            this.type = type
            this.bubbles = bubbles
            this.cancelable = cancelable
        }

        /** "set the canceled flag" */
        fun cancel() {
            if (cancelable && !inPassiveListener) canceled = true
        }
    }

    class JSCustomEvent(proto: JSObject?) : JSEvent(proto) {
        override val className: String get() = "CustomEvent"
        @JvmField var detail: Any? = Null
    }

    class JSMessageEvent(proto: JSObject?) : JSEvent(proto) {
        override val className: String get() = "MessageEvent"
        @JvmField var data: Any? = Null
        @JvmField var origin = ""
        @JvmField var lastEventId = ""
        /** `ports`: a frozen array, the same one at each get. */
        @JvmField var ports: JSObject? = null
    }

    class JSCloseEvent(proto: JSObject?) : JSEvent(proto) {
        override val className: String get() = "CloseEvent"
        @JvmField var wasClean = false
        @JvmField var code = 0
        @JvmField var reason = ""
    }

    class JSErrorEvent(proto: JSObject?) : JSEvent(proto) {
        override val className: String get() = "ErrorEvent"
        @JvmField var message = ""
        @JvmField var filename = ""
        @JvmField var lineno = 0.0
        @JvmField var colno = 0.0
        @JvmField var error: Any? = Undefined
    }

    /** An event listener: [callback] is null for a listener that has been removed. */
    class Listener(
        @JvmField val type: String, @JvmField var callback: JSObject?, @JvmField val capture: Boolean,
        @JvmField val passive: Boolean, @JvmField val once: Boolean,
    ) {
        @JvmField var removed = false
        /** For the listener of an event handler attribute: the target and the attribute's name. */
        @JvmField var handlerOf: Pair<JSEventTarget, String>? = null
    }

    open class JSEventTarget(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "EventTarget"
        @JvmField val listeners = ArrayList<Listener>(2)
        /** The values of event handler attributes (`onabort`...), and their listeners. */
        @JvmField var handlers: HashMap<String, Pair<Any?, Listener>>? = null

        fun addListener(l: Listener) {
            if (listeners.any { !it.removed && it.type == l.type && it.callback === l.callback && it.capture == l.capture && it.handlerOf == null }) return
            listeners.add(l)
            listenerAdded(l)
        }

        /** Called when a listener (or an event handler's listener) is added. */
        open fun listenerAdded(l: Listener) {}

        fun removeListener(l: Listener) {
            l.removed = true
            listeners.remove(l)
        }
    }

    const val NONE = 0
    const val CAPTURING_PHASE = 1
    const val AT_TARGET = 2
    const val BUBBLING_PHASE = 3


    fun install(realm: Realm) {
        installEvent(realm)
        installCustomEvent(realm)
        installEventTarget(realm)
        installMessageEvent(realm)
        installCloseEvent(realm)
        installErrorEvent(realm)
    }

    private fun installEvent(realm: Realm) {
        // [LegacyUnforgeable] isTrusted: an own accessor of each event, the same getter for all of a realm's events
        val isTrusted = NativeFunction(realm, "isTrusted", 0, { _, t, _, _ -> Idl.self<JSEvent>(t, "Event", "isTrusted").isTrusted }, namePrefix = "get")
        realm.intrinsicsAny["%Event.isTrusted%"] = isTrusted
        val i = WebInterface.define(realm, "Event", 1) { a, proto ->
            Idl.required(a, 1, "Event constructor")
            val type = Idl.domString(a[0])
            val init = Idl.dictionary(a.arg(1), "EventInit")
            newEvent(realm, JSEvent(proto), type, init)
        }
        fun ev(t: Any?, m: String) = Idl.self<JSEvent>(t, "Event", m)
        i.attribute("type", { _, t, _, _ -> ev(t, "type").type })
        i.attribute("target", { _, t, _, _ -> ev(t, "target").target ?: Null })
        i.attribute("srcElement", { _, t, _, _ -> ev(t, "srcElement").target ?: Null })
        i.attribute("currentTarget", { _, t, _, _ -> ev(t, "currentTarget").currentTarget ?: Null })
        i.operation("composedPath", 0) { f, t, _, _ ->
            val e = ev(t, "composedPath")
            JSArray.of(f.realm.arrayPrototype, if (e.dispatching && e.currentTarget != null) arrayOf(e.currentTarget) else EMPTY_ARGS)
        }
        i.constant("NONE", NONE.toDouble())
        i.constant("CAPTURING_PHASE", CAPTURING_PHASE.toDouble())
        i.constant("AT_TARGET", AT_TARGET.toDouble())
        i.constant("BUBBLING_PHASE", BUBBLING_PHASE.toDouble())
        i.attribute("eventPhase", { _, t, _, _ -> ev(t, "eventPhase").eventPhase.toDouble() })
        i.operation("stopPropagation", 0) { _, t, _, _ -> ev(t, "stopPropagation").stopPropagation = true; Undefined }
        i.attribute("cancelBubble", { _, t, _, _ -> ev(t, "cancelBubble").stopPropagation }, { _, t, a, _ ->
            val e = ev(t, "cancelBubble")
            if (Ops.toBoolean(a.arg(0))) e.stopPropagation = true
            Undefined
        })
        i.operation("stopImmediatePropagation", 0) { _, t, _, _ ->
            val e = ev(t, "stopImmediatePropagation")
            e.stopPropagation = true
            e.stopImmediatePropagation = true
            Undefined
        }
        i.attribute("bubbles", { _, t, _, _ -> ev(t, "bubbles").bubbles })
        i.attribute("cancelable", { _, t, _, _ -> ev(t, "cancelable").cancelable })
        i.attribute("returnValue", { _, t, _, _ -> !ev(t, "returnValue").canceled }, { _, t, a, _ ->
            val e = ev(t, "returnValue")
            if (!Ops.toBoolean(a.arg(0))) e.cancel()
            Undefined
        })
        i.operation("preventDefault", 0) { _, t, _, _ -> ev(t, "preventDefault").cancel(); Undefined }
        i.attribute("defaultPrevented", { _, t, _, _ -> ev(t, "defaultPrevented").canceled })
        i.attribute("composed", { _, t, _, _ -> ev(t, "composed").composed })
        i.attribute("timeStamp", { _, t, _, _ -> ev(t, "timeStamp").timeStamp })
        i.operation("initEvent", 1) { _, t, a, _ ->
            val e = ev(t, "initEvent")
            Idl.required(a, 1, "Event.initEvent")
            val type = Idl.domString(a[0])
            val bubbles = Ops.toBoolean(a.arg(1))
            val cancelable = Ops.toBoolean(a.arg(2))
            if (!e.dispatching) e.initialize(type, bubbles, cancelable)
            Undefined
        }
    }

    /** The steps of the Event constructor, on an event of the right class and prototype. */
    fun <E : JSEvent> newEvent(realm: Realm, e: E, type: String, init: JSObject?): E {
        // EventInit members in lexicographic order
        e.bubbles = Ops.toBoolean(Idl.member(init, "bubbles"))
        e.cancelable = Ops.toBoolean(Idl.member(init, "cancelable"))
        e.composed = Ops.toBoolean(Idl.member(init, "composed"))
        e.initialized = true
        e.type = type
        e.timeStamp = Performance.now(realm)
        e.defineAccessor("isTrusted", realm.intrinsicsAny["%Event.isTrusted%"], Undefined, Attr.ENUMERABLE)
        return e
    }

    /** A new trusted-looking event of [realm] fired by the engine itself (`abort` on an AbortSignal...). */
    fun engineEvent(realm: Realm, type: String): JSEvent {
        val e = newEvent(realm, JSEvent(realm.intrinsic("%Event.prototype%")), type, null)
        e.isTrusted = true
        return e
    }

    private fun installCustomEvent(realm: Realm) {
        val i = WebInterface.define(realm, "CustomEvent", 1, parent = "Event") { a, proto ->
            Idl.required(a, 1, "CustomEvent constructor")
            val type = Idl.domString(a[0])
            val init = Idl.dictionary(a.arg(1), "CustomEventInit")
            // the inherited EventInit members first, then detail
            val e = newEvent(realm, JSCustomEvent(proto), type, init)
            val d = Idl.member(init, "detail")
            e.detail = if (d === Undefined) Null else d
            e
        }
        i.attribute("detail", { _, t, _, _ -> Idl.self<JSCustomEvent>(t, "CustomEvent", "detail").detail })
        i.operation("initCustomEvent", 1) { _, t, a, _ ->
            val e = Idl.self<JSCustomEvent>(t, "CustomEvent", "initCustomEvent")
            Idl.required(a, 1, "CustomEvent.initCustomEvent")
            val type = Idl.domString(a[0])
            val bubbles = Ops.toBoolean(a.arg(1))
            val cancelable = Ops.toBoolean(a.arg(2))
            if (!e.dispatching) {
                e.initialize(type, bubbles, cancelable)
                e.detail = if (a.size > 3) a[3] else Null
            }
            Undefined
        }
    }

    private fun emptyPorts(realm: Realm): JSObject =
        dev.mooner.neonjs.builtins.Builtins.arrayOf(realm, emptyList()).also { dev.mooner.neonjs.builtins.Builtins.freeze(it) }

    /** MessageEventInit's `ports`: a sequence of MessagePorts, which this realm has none of (so only an empty one). */
    private fun ports(realm: Realm, v: Any?, what: String): JSObject {
        if (v === Undefined) return emptyPorts(realm)
        Idl.sequence(realm, v, "$what ports") { typeErr("$what: ${Ops.describe(it)} is not a MessagePort") }
        return emptyPorts(realm)
    }

    /** MessageEventInit's `source`: a window, MessagePort or service worker, or null; there are none here. */
    private fun checkSource(v: Any?, what: String) {
        if (v !== Undefined && v !== Null) typeErr("$what: ${Ops.describe(v)} is not a MessageEventSource")
    }

    private fun installMessageEvent(realm: Realm) {
        val i = WebInterface.define(realm, "MessageEvent", 1, parent = "Event") { a, proto ->
            Idl.required(a, 1, "MessageEvent constructor")
            val type = Idl.domString(a[0])
            val init = Idl.dictionary(a.arg(1), "MessageEventInit")
            val e = newEvent(realm, JSMessageEvent(proto), type, init)
            // the MessageEventInit members in lexicographic order, after the inherited ones
            Idl.member(init, "data").let { e.data = if (it === Undefined) Null else it }
            Idl.member(init, "lastEventId").let { if (it !== Undefined) e.lastEventId = Idl.domString(it) }
            Idl.member(init, "origin").let { if (it !== Undefined) e.origin = Idl.usvString(it) }
            e.ports = ports(realm, Idl.member(init, "ports"), "MessageEventInit")
            checkSource(Idl.member(init, "source"), "MessageEventInit")
            e
        }
        fun me(t: Any?, m: String) = Idl.self<JSMessageEvent>(t, "MessageEvent", m)
        i.attribute("data", { _, t, _, _ -> me(t, "data").data })
        i.attribute("origin", { _, t, _, _ -> me(t, "origin").origin })
        i.attribute("lastEventId", { _, t, _, _ -> me(t, "lastEventId").lastEventId })
        i.attribute("source", { _, t, _, _ -> me(t, "source"); Null })
        i.attribute("ports", { f, t, _, _ -> me(t, "ports").let { e -> e.ports ?: emptyPorts(f.realm).also { e.ports = it } } })
        i.operation("initMessageEvent", 1) { f, t, a, _ ->
            val e = me(t, "initMessageEvent")
            Idl.required(a, 1, "MessageEvent.initMessageEvent")
            val type = Idl.domString(a[0])
            val bubbles = Ops.toBoolean(a.arg(1))
            val cancelable = Ops.toBoolean(a.arg(2))
            val data = if (a.size > 3) a[3] else Null
            val origin = if (a.size > 4 && a[4] !== Undefined) Idl.usvString(a[4]) else ""
            val lastEventId = if (a.size > 5 && a[5] !== Undefined) Idl.domString(a[5]) else ""
            checkSource(a.arg(6), "MessageEvent.initMessageEvent")
            val ports = ports(f.realm, a.arg(7), "MessageEvent.initMessageEvent")
            if (!e.dispatching) {
                e.initialize(type, bubbles, cancelable)
                e.data = data
                e.origin = origin
                e.lastEventId = lastEventId
                e.ports = ports
            }
            Undefined
        }
    }

    private fun installCloseEvent(realm: Realm) {
        val i = WebInterface.define(realm, "CloseEvent", 1, parent = "Event") { a, proto ->
            Idl.required(a, 1, "CloseEvent constructor")
            val type = Idl.domString(a[0])
            val init = Idl.dictionary(a.arg(1), "CloseEventInit")
            val e = newEvent(realm, JSCloseEvent(proto), type, init)
            Idl.member(init, "code").let { if (it !== Undefined) e.code = Idl.integer(it, 16, signed = false, what = "CloseEventInit code").toInt() }
            Idl.member(init, "reason").let { if (it !== Undefined) e.reason = Idl.usvString(it) }
            e.wasClean = Ops.toBoolean(Idl.member(init, "wasClean"))
            e
        }
        fun ce(t: Any?, m: String) = Idl.self<JSCloseEvent>(t, "CloseEvent", m)
        i.attribute("wasClean", { _, t, _, _ -> ce(t, "wasClean").wasClean })
        i.attribute("code", { _, t, _, _ -> ce(t, "code").code.toDouble() })
        i.attribute("reason", { _, t, _, _ -> ce(t, "reason").reason })
    }

    private fun installErrorEvent(realm: Realm) {
        val i = WebInterface.define(realm, "ErrorEvent", 1, parent = "Event") { a, proto ->
            Idl.required(a, 1, "ErrorEvent constructor")
            val type = Idl.domString(a[0])
            val init = Idl.dictionary(a.arg(1), "ErrorEventInit")
            val e = newEvent(realm, JSErrorEvent(proto), type, init)
            Idl.member(init, "colno").let { if (it !== Undefined) e.colno = Idl.integer(it, 32, signed = false, what = "ErrorEventInit colno") }
            e.error = Idl.member(init, "error")
            Idl.member(init, "filename").let { if (it !== Undefined) e.filename = Idl.usvString(it) }
            Idl.member(init, "lineno").let { if (it !== Undefined) e.lineno = Idl.integer(it, 32, signed = false, what = "ErrorEventInit lineno") }
            Idl.member(init, "message").let { if (it !== Undefined) e.message = Idl.domString(it) }
            e
        }
        fun ee(t: Any?, m: String) = Idl.self<JSErrorEvent>(t, "ErrorEvent", m)
        i.attribute("message", { _, t, _, _ -> ee(t, "message").message })
        i.attribute("filename", { _, t, _, _ -> ee(t, "filename").filename })
        i.attribute("lineno", { _, t, _, _ -> ee(t, "lineno").lineno })
        i.attribute("colno", { _, t, _, _ -> ee(t, "colno").colno })
        i.attribute("error", { _, t, _, _ -> ee(t, "error").error })
    }

    /** [e] (made with its interface's prototype) initialized as a trusted event of [type] that the engine fires. */
    fun <E : JSEvent> engineEvent(realm: Realm, e: E, type: String): E {
        newEvent(realm, e, type, null)
        e.isTrusted = true
        return e
    }

    private fun installEventTarget(realm: Realm) {
        val i = WebInterface.define(realm, "EventTarget", 0) { _, proto -> JSEventTarget(proto) }
        fun target(t: Any?, m: String) = Idl.self<JSEventTarget>(t, "EventTarget", m)
        i.operation("addEventListener", 2) { _, t, a, _ ->
            val et = target(t, "addEventListener")
            Idl.required(a, 2, "EventTarget.addEventListener")
            val type = Idl.domString(a[0])
            val callback = listenerCallback(a[1])
            val o = a.arg(2)
            var capture = false
            var once = false
            var passive = false
            var signal: Any? = Undefined
            if (o is JSObject) {
                // AddEventListenerOptions: the inherited capture, then once, passive, signal
                capture = Ops.toBoolean(o.get("capture", o))
                once = Ops.toBoolean(o.get("once", o))
                val p = o.get("passive", o)
                passive = p !== Undefined && Ops.toBoolean(p)
                signal = o.get("signal", o)
                if (signal !== Undefined) signal = Abort.signalOf(signal)
            } else capture = Ops.toBoolean(o)
            if (callback == null) return@operation Undefined
            val s = signal as? Abort.JSAbortSignal
            if (s != null && s.aborted) return@operation Undefined
            val l = Listener(type, callback, capture, passive, once)
            et.addListener(l)
            s?.addAlgorithm { et.removeListener(l) }
            Undefined
        }
        i.operation("removeEventListener", 2) { _, t, a, _ ->
            val et = target(t, "removeEventListener")
            Idl.required(a, 2, "EventTarget.removeEventListener")
            val type = Idl.domString(a[0])
            val callback = listenerCallback(a[1])
            val o = a.arg(2)
            val capture = if (o is JSObject) Ops.toBoolean(o.get("capture", o)) else Ops.toBoolean(o)
            if (callback != null) {
                val l = et.listeners.firstOrNull { !it.removed && it.handlerOf == null && it.type == type && it.callback === callback && it.capture == capture }
                if (l != null) et.removeListener(l)
            }
            Undefined
        }
        i.operation("dispatchEvent", 1) { f, t, a, _ ->
            val et = target(t, "dispatchEvent")
            Idl.required(a, 1, "EventTarget.dispatchEvent")
            val e = a[0] as? JSEvent ?: typeErr("EventTarget.dispatchEvent: the argument is not an Event")
            if (e.dispatching || !e.initialized) throw WebGlobals.domException(f.realm, "The event is already being dispatched or was not initialized", "InvalidStateError")
            e.isTrusted = false
            dispatch(f.realm, et, e)
        }
    }

    /** An `EventListener?` argument: null, a function or an object (its `handleEvent` is looked up when called). */
    private fun listenerCallback(v: Any?): JSObject? = when (v) {
        Null, Undefined -> null
        is JSObject -> v
        else -> typeErr("EventTarget: the listener is not an object")
    }

    /**
     * Dispatches [e] to [et] and returns whether the default action is allowed. An exception thrown by a listener is
     * reported as the exception of a microtask, so it reaches the context's uncaught error handler.
     */
    fun dispatch(realm: Realm, et: JSEventTarget, e: JSEvent): Boolean {
        e.dispatching = true
        e.target = et
        e.currentTarget = et
        e.eventPhase = AT_TARGET
        try {
            val listeners = et.listeners.toTypedArray()
            if (!invoke(realm, et, e, listeners, capture = true)) invoke(realm, et, e, listeners, capture = false)
        } finally {
            e.eventPhase = NONE
            e.currentTarget = null
            e.dispatching = false
            e.stopPropagation = false
            e.stopImmediatePropagation = false
        }
        return !e.canceled
    }

    /** Runs the listeners of one phase; true if one stopped immediate propagation. */
    private fun invoke(realm: Realm, et: JSEventTarget, e: JSEvent, listeners: Array<Listener>, capture: Boolean): Boolean {
        for (l in listeners) {
            if (l.removed || l.type != e.type || l.capture != capture) continue
            if (l.once) et.removeListener(l)
            if (l.passive) e.inPassiveListener = true
            try {
                callListener(l, e, et)
            } catch (x: JSException) {
                val v = x.value
                realm.agent.enqueueJob { throw JSException(v) }
            } finally {
                e.inPassiveListener = false
            }
            if (e.stopImmediatePropagation) return true
        }
        return false
    }

    private fun callListener(l: Listener, e: JSEvent, self: JSObject) {
        val h = l.handlerOf
        if (h != null) {
            // an event handler: the attribute's current value, if it is callable; returning false cancels the event
            val v = h.first.handlers?.get(h.second)?.first
            if (v is JSObject && v.isCallable && Ops.call(v, self, arrayOf(e)) == false) e.cancel()
            return
        }
        val cb = l.callback ?: return
        if (cb.isCallable) {
            Ops.call(cb, self, arrayOf(e))
            return
        }
        val handleEvent = cb.get("handleEvent", cb)
        if (!Ops.isCallable(handleEvent)) typeErr("The listener has no handleEvent method")
        Ops.call(handleEvent, cb, arrayOf(e))
    }

    /**
     * An event handler IDL attribute (`onabort`) of [i] for events [type]: the value is an object or null, and setting
     * one adds a listener (at that point of the listener list) that calls the current value.
     */
    fun eventHandler(i: WebInterface, name: String, type: String) {
        i.attribute(name, { _, t, _, _ ->
            Idl.self<JSEventTarget>(t, i.name, name).handlers?.get(name)?.first ?: Null
        }, { _, t, a, _ ->
            val et = Idl.self<JSEventTarget>(t, i.name, name)
            // [LegacyTreatNonObjectAsNull]
            val v = a.arg(0) as? JSObject
            val map = et.handlers ?: HashMap<String, Pair<Any?, Listener>>().also { et.handlers = it }
            val old = map[name]
            when {
                v == null -> if (old != null) {
                    et.removeListener(old.second)
                    map.remove(name)
                }
                old != null -> map[name] = v to old.second
                else -> {
                    val l = Listener(type, null, capture = false, passive = false, once = false)
                    l.handlerOf = et to name
                    et.listeners.add(l)
                    map[name] = v to l
                    et.listenerAdded(l)
                }
            }
            Undefined
        })
    }
}
