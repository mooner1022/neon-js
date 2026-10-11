package dev.mooner.neonjs.node

import dev.mooner.neonjs.runtime.*

/**
 * V8's `Error.captureStackTrace(target, constructorOpt)` and `Error.stackTraceLimit`, which Node.js libraries use
 * (discord.js's errors call the former in their constructor). The stack is the target's `Error.prototype.toString` and
 * the frames below the call of `constructorOpt` (none when it is not on the stack), at most `Error.stackTraceLimit`.
 */
internal object ErrorStack {
    /** Frames recorded for a limit above this (`Error.stackTraceLimit = Infinity`). */
    private const val MAX_FRAMES = 200

    fun install(realm: Realm) {
        val error = realm.globalObject.get("Error", realm.globalObject) as JSObject
        error.defineOwn("stackTraceLimit", 10.0, Attr.ALL)
        error.defineOwn("captureStackTrace", NativeFunction(realm, "captureStackTrace", 2, { f, _, a, _ ->
            val target = a.arg(0) as? JSObject ?: throw JSException.typeError("Error.captureStackTrace: the target is not an object")
            val ctorOpt = (a.arg(1) as? JSObject)?.takeIf { it.isCallable }
            val limitValue = error.get("stackTraceLimit", error)
            val limit = if (limitValue is Double && !limitValue.isNaN()) limitValue.coerceIn(0.0, MAX_FRAMES.toDouble()).toInt() else 0
            val frames = f.realm.agent.captureStack(ctorOpt, limit)
            val header = header(target)
            if (target is JSErrorObject) target.stackTrace = frames
            target.definePropertyOrThrow("stack", PropertyDescriptor.data(if (frames.isEmpty()) header else "$header\n$frames", Attr.WC))
            Undefined
        }), Attr.WC)
    }

    /** Error.prototype.toString of [o]. */
    private fun header(o: JSObject): String {
        val n = o.get("name", o)
        val m = o.get("message", o)
        val name = if (n === Undefined) "Error" else Ops.toString(n)
        val msg = if (m === Undefined) "" else Ops.toString(m)
        return if (name.isEmpty()) msg else if (msg.isEmpty()) name else "$name: $msg"
    }
}
