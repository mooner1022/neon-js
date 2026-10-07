package dev.mooner.neonjs.jit

import dev.mooner.neonjs.vm.Frame
import java.util.concurrent.ConcurrentHashMap

/**
 * Works around class hierarchy analysis (CHA) in the ART of Android 8.0 and 8.1 (API 26-27). It records the only
 * implementation of an interface method and reads that record again when the next implementing class is linked, even
 * after the class loader that defined the implementation has been unloaded: the read then marks a dead class during a
 * garbage collection, which aborts the runtime (`Check failed: self == thread_running_gc_`) or reports heap corruption.
 * Android 9 clears the record when a class loader is unloaded.
 *
 * Generated classes are unloaded with their class loaders, so none may be the only implementation of an interface
 * method there: [CompiledCode] gets two permanent implementations before any generated class is linked, and adapter
 * classes of `Java.extend` that implement interfaces stay loaded on those versions.
 */
internal object ArtCha {
    /** Whether this runtime keeps records of unloaded implementations (ART of Android 8.0 and 8.1). */
    val keepsUnloadedImplementations: Boolean by lazy {
        try {
            Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null) in 26..27
        } catch (_: Throwable) {
            false
        }
    }

    private object NotCompiled : CompiledCode {
        override fun run(f: Frame): Any? = null
    }

    private object AlsoNotCompiled : CompiledCode {
        override fun run(f: Frame): Any? = null
    }

    // linked with this object: from then on CompiledCode.run has several implementations, which CHA no longer tracks
    private val compiledCodeImplementations = arrayOf<CompiledCode>(NotCompiled, AlsoNotCompiled)

    /** Links two implementations of [CompiledCode] from the engine's own class loader, once (harmless elsewhere). */
    fun linkCompiledCodeImplementations() = compiledCodeImplementations.size

    private val pinned: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    /** Keeps the generated class [cls], and so its class loader, for the life of the process on Android 8.0 and 8.1. */
    fun pinIfNeeded(cls: Class<*>) {
        if (keepsUnloadedImplementations) pinned.add(cls)
    }
}
