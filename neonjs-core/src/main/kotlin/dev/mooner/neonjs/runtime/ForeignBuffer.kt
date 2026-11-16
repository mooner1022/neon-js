package dev.mooner.neonjs.runtime

/**
 * An object that may be a host `java.nio.ByteBuffer` the policy shows as a buffer (`HostAccess.allowBufferAccess`):
 * what a TypedArray or DataView can be made over, as GraalJS's foreign buffers are (`new Uint8Array(byteBuffer)`).
 */
interface ForeignBuffer {
    /**
     * An ArrayBuffer over this buffer's bytes 0 until its limit, or null when this is no buffer (or the policy hides
     * it). It shares the bytes where it can, so writes on either side show on the other; otherwise it is a copy.
     */
    fun foreignArrayBuffer(): JSObject?
}
