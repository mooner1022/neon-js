package dev.mooner.neonjs.jit.opt

/**
 * JavaScript's frame of a call compiled inlined ([InlineSite.frame]): the closure called, `this` as the call binds it
 * (undefined where the callee does not read it), the arguments, the environment; and whether the call is a construction
 * (`new`: `this` is the object made for it, Ir.NEW_THIS, and the result that unless the body returns an object).
 */
internal const val SITE_CONSTRUCT = 1

internal fun jsFrame(fn: Value, thisValue: Value, args: Array<Value>, env: Value): Array<Value> = arrayOf(fn, thisValue, *args, env)

internal val InlineSite.fn: Value get() = frame[0]
internal val InlineSite.thisValue: Value get() = frame[1]
internal val InlineSite.args: Array<Value> get() = frame.copyOfRange(2, frame.size - 1)
internal val InlineSite.env: Value get() = frame[frame.size - 1]
internal val InlineSite.construct: Boolean get() = flags and SITE_CONSTRUCT != 0
