package io.neonjs.android

import io.neonjs.jit.CodeDefiner
import io.neonjs.jit.IsolatedCodeDefiner
import java.util.concurrent.atomic.AtomicLong

/**
 * A verification tool for standard JVMs: translates every generated class to dex with the converter
 * [DexCodeDefiner] would use (so anything it rejects fails here), then runs the JVM class in a loader of its own.
 * Select it with `-Dneonjs.codeDefiner=io.neonjs.android.DexCheckingDefiner` (and `-Dneonjs.dexConverter=d8` for D8)
 * to run a test suite under Android's code generation conditions without a device.
 */
class DexCheckingDefiner @JvmOverloads constructor(val converter: DexConverter = DexConverters.default) : CodeDefiner {
    private val isolated = IsolatedCodeDefiner()

    override val classFileVersion: Int get() = converter.classFileVersion

    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
        val dex = converter.toDex(name, bytes, 26)
        check(DexConverters.isDex(dex)) { "${converter.id} produced no dex for $name" }
        converted.incrementAndGet()
        dexBytes.addAndGet(dex.size.toLong())
        return isolated.define(name, bytes, parent)
    }

    companion object {
        @JvmField val converted = AtomicLong()
        @JvmField val dexBytes = AtomicLong()
    }
}
