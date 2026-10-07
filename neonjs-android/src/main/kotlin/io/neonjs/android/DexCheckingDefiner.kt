package io.neonjs.android

import io.neonjs.jit.CodeDefiner
import io.neonjs.jit.GeneratedClass
import io.neonjs.jit.IsolatedCodeDefiner
import java.util.concurrent.atomic.AtomicLong

/**
 * A verification tool for standard JVMs: translates generated classes to dex with the converter [DexCodeDefiner]
 * would use, a batch per dex file as it does (so anything the converter rejects fails here), then runs the JVM classes,
 * each in a loader of its own. Select it with `-Dneonjs.codeDefiner=io.neonjs.android.DexCheckingDefiner` (and
 * `-Dneonjs.dexConverter=d8` for D8) to run a test suite under Android's code generation conditions without a device.
 */
class DexCheckingDefiner @JvmOverloads constructor(val converter: DexConverter = DexConverters.default) : CodeDefiner {
    private val isolated = IsolatedCodeDefiner()

    override val classFileVersion: Int get() = converter.classFileVersion

    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> =
        defineAll(listOf(GeneratedClass(name, bytes)), parent)[0]

    override fun defineAll(classes: List<GeneratedClass>, parent: ClassLoader): List<Class<*>> {
        val dex = converter.toDex(classes, 26)
        check(DexConverters.isDex(dex)) { "${converter.id} produced no dex for ${classes.map { it.name }}" }
        check(DexConverters.classCount(dex) == classes.size) { "${converter.id} put ${DexConverters.classCount(dex)} of ${classes.size} classes in the dex file" }
        converted.addAndGet(classes.size.toLong())
        batches.incrementAndGet()
        dexBytes.addAndGet(dex.size.toLong())
        return classes.map { isolated.define(it.name, it.bytes, parent) }
    }

    companion object {
        /** Classes translated, dex files produced (one per batch) and their total size, over all instances. */
        @JvmField val converted = AtomicLong()
        @JvmField val batches = AtomicLong()
        @JvmField val dexBytes = AtomicLong()
    }
}
