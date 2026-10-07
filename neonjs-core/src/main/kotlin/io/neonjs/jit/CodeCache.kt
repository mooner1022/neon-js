package io.neonjs.jit

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiled code shared by every code block with the same [JitInput.identity]: generated classes keep no state (they
 * read constants and registers from the frame), so one instance serves them all, across contexts and engines. Code
 * blocks that differ only in constants or source position therefore compile once, as does library code run in many
 * contexts. One cache per [CodeDefiner], since definers produce different classes.
 *
 * Entries are weak: code stays while some code block uses it, then its class can be unloaded (hidden classes on the
 * JVM, the class loader on Android) as before.
 */
class CodeCache private constructor() {
    private val map = ConcurrentHashMap<String, Ref>()
    private val queue = ReferenceQueue<CompiledCode>()

    private class Ref(val key: String, code: CompiledCode, q: ReferenceQueue<CompiledCode>) : WeakReference<CompiledCode>(code, q)

    /** The code compiled for [identity], if some code block still uses it. */
    operator fun get(identity: String): CompiledCode? {
        expunge()
        return map[identity]?.get()
    }

    /** Records [code] for [identity]; returns the code to use (an equal one recorded first by another thread wins). */
    fun putIfAbsent(identity: String, code: CompiledCode): CompiledCode {
        expunge()
        while (true) {
            val prev = map.putIfAbsent(identity, Ref(identity, code, queue)) ?: return code
            prev.get()?.let { return it }
            if (map.replace(identity, prev, Ref(identity, code, queue))) return code
        }
    }

    val size: Int get() = map.size

    private fun expunge() {
        while (true) {
            val r = queue.poll() as Ref? ?: return
            map.remove(r.key, r)
        }
    }

    companion object {
        private val caches = WeakHashMap<CodeDefiner, CodeCache>()

        /** The cache for code defined by [definer] (kept as long as the definer is). */
        @JvmStatic
        fun of(definer: CodeDefiner): CodeCache = synchronized(caches) { caches.getOrPut(definer) { CodeCache() } }
    }
}
