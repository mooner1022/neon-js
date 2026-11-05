package dev.mooner.neonjs.jit


/** Thrown when a code block cannot be compiled (unsupported instruction, method too large). */
class JitBailout(msg: String) : RuntimeException(msg, null, false, false)
