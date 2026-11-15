package dev.mooner.neonwasm

import dev.mooner.neonjs.runtime.VmAgent
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * A linear memory (§4.2.8): a byte array of whole pages. A JVM array holds at most 2 GiB, so a memory grows to
 * [MAX_PAGES] (32,767 pages) here, not wasm32's 65,536: past that `memory.grow` gives -1, as wasm allows.
 */
class WasmMemory(type: MemType) {
    @JvmField val max: Long = type.limits.max
    @JvmField var bytes: ByteArray

    init {
        if (type.limits.min > MAX_PAGES) throw WasmException("out of memory: ${type.limits.min} pages")
        bytes = ByteArray((type.limits.min * PAGE).toInt())
    }

    val pages: Int get() = bytes.size / PAGE

    /** memory.grow: the old size in pages, or -1 where the maximum, this runtime's limit or the agent's refuses. */
    fun grow(delta: Long, agent: VmAgent?): Int {
        val old = pages
        if (delta == 0L) return old
        val n = old + delta
        if (n > MAX_PAGES || max >= 0 && n > max) return -1
        val size = n * PAGE
        try {
            agent?.reserveAllocation(size)
        } catch (_: dev.mooner.neonjs.runtime.ResourceLimitException) {
            return -1
        }
        bytes = try {
            bytes.copyOf(size.toInt())
        } catch (_: OutOfMemoryError) {
            return -1
        }
        return old
    }

    companion object {
        const val PAGE = 65536
        const val MAX_PAGES = (Int.MAX_VALUE - 8) / PAGE
    }
}

/** A table (§4.2.7): references, functions ([WasmFunction]) or host objects, null for none. */
class WasmTable(@JvmField val elemType: Int, initial: Int, @JvmField val max: Long) {
    @JvmField var elems: Array<Any?> = arrayOfNulls(initial)

    constructor(type: TableType) : this(type.elemType, checkSize(type.limits.min), type.limits.max)

    val size: Int get() = elems.size

    /** table.grow: the old size, or -1 past the maximum or this runtime's limit. */
    fun grow(delta: Long, init: Any?): Int {
        val old = elems.size
        val n = old + delta
        if (n > MAX_SIZE || max >= 0 && n > max) return -1
        val a = elems.copyOf(n.toInt())
        java.util.Arrays.fill(a, old, n.toInt(), init)
        elems = a
        return old
    }

    companion object {
        /** This runtime's limit on a table's elements. */
        const val MAX_SIZE = 10_000_000L

        private fun checkSize(min: Long): Int {
            if (min > MAX_SIZE) throw WasmException("out of memory: a table of $min elements")
            return min.toInt()
        }
    }
}

/** A global (§4.2.9): a number's bits in [bits], or a reference in [ref]. */
class WasmGlobal(@JvmField val type: GlobalType) {
    @JvmField var bits = 0L
    @JvmField var ref: Any? = null

    /** The value as [WasmFunction.call] takes and gives values. */
    var value: Any?
        get() = Values.fromRaw(type.valType, bits, ref)
        set(v) {
            if (ValType.isRef(type.valType)) ref = v else bits = Values.toRaw(type.valType, v)
        }

    constructor(type: GlobalType, value: Any?) : this(type) {
        this.value = value
    }
}

/**
 * A function (§4.2.6): defined by a module ([ModuleFunction]) or by the host ([HostFunction]). Values cross as Int
 * (i32), Long (i64), Float (f32), Double (f64), and references as they are (a [WasmFunction] for funcref, any
 * object for externref; null for none).
 */
abstract class WasmFunction internal constructor(@JvmField val type: FuncType) {
    /** Calls the function; a trap is a [WasmTrap]. */
    fun call(vararg args: Any?): Array<Any?> {
        if (args.size != type.params.size) throw IllegalArgumentException("$type takes ${type.params.size} arguments, not ${args.size}")
        val raw = arrayOfNulls<Any>(args.size)
        for (i in args.indices) raw[i] = Values.toRawValue(type.params[i], args[i])
        val res = invokeRaw(raw)
        for (i in res.indices) res[i] = Values.fromRawValue(type.results[i], res[i])
        return res
    }

    /** Calls with raw values: numbers as Longs holding their bits (i32 and f32 in the low 32), references as they are. */
    internal abstract fun invokeRaw(args: Array<Any?>): Array<Any?>
}

/** A function of the host's, which a module may import. */
class HostFunction(type: FuncType, private val impl: (Array<Any?>) -> Array<Any?>?) : WasmFunction(type) {
    override fun invokeRaw(args: Array<Any?>): Array<Any?> {
        val typed = arrayOfNulls<Any>(args.size)
        for (i in args.indices) typed[i] = Values.fromRawValue(type.params[i], args[i])
        val res = impl(typed) ?: arrayOfNulls(0)
        if (res.size != type.results.size) throw WasmException("host function returned ${res.size} values, not ${type.results.size}")
        val raw = arrayOfNulls<Any>(res.size)
        for (i in res.indices) raw[i] = Values.toRawValue(type.results[i], res[i])
        return raw
    }
}

/** A function a module defines: its instance and its code. */
internal class ModuleFunction(type: FuncType, @JvmField val instance: WasmInstance, @JvmField val code: Code) : WasmFunction(type) {
    override fun invokeRaw(args: Array<Any?>): Array<Any?> = instance.context.invoke(this, args)

    override fun toString() = "wasm function ${code.index}"
}

/** Conversions between [WasmFunction.call]'s values and the interpreter's raw ones. */
internal object Values {
    @JvmStatic fun toRaw(t: Int, v: Any?): Long = when (t) {
        ValType.I32 -> (v as Number).toInt().toLong()
        ValType.I64 -> (v as Number).toLong()
        ValType.F32 -> java.lang.Float.floatToRawIntBits((v as Number).toFloat()).toLong()
        ValType.F64 -> java.lang.Double.doubleToRawLongBits((v as Number).toDouble())
        else -> throw IllegalArgumentException(ValType.name(t))
    }

    @JvmStatic fun fromRaw(t: Int, bits: Long, ref: Any?): Any? = when (t) {
        ValType.I32 -> bits.toInt()
        ValType.I64 -> bits
        ValType.F32 -> java.lang.Float.intBitsToFloat(bits.toInt())
        ValType.F64 -> java.lang.Double.longBitsToDouble(bits)
        else -> ref
    }

    @JvmStatic fun toRawValue(t: Int, v: Any?): Any? = if (ValType.isRef(t)) {
        if (t == ValType.FUNCREF && v != null && v !is WasmFunction) throw IllegalArgumentException("not a function: $v")
        v
    } else toRaw(t, v)

    @JvmStatic fun fromRawValue(t: Int, v: Any?): Any? = if (ValType.isRef(t)) v else fromRaw(t, v as Long, null)
}

/**
 * Little-endian loads and stores on a memory's bytes. Byte-array view VarHandles on standard JVMs; on ART, bytes
 * assembled: its view accesses are fast only at aligned addresses, and a byte array's data is not 8-byte aligned
 * (NeonJS's typed arrays do the same: `BufferOps.USE_VAR_HANDLES` in neonjs-core).
 */
internal object LE {
    @JvmField val USE_VAR_HANDLES: Boolean = System.getProperty("java.vm.name") != "Dalvik" && try {
        LEViews.I16
        true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic fun i16(a: ByteArray, i: Int): Int =
        if (USE_VAR_HANDLES) (LEViews.I16.get(a, i) as Short).toInt()
        else ((a[i].toInt() and 0xFF) or (a[i + 1].toInt() shl 8)).toShort().toInt()

    @JvmStatic fun i32(a: ByteArray, i: Int): Int =
        if (USE_VAR_HANDLES) LEViews.I32.get(a, i) as Int
        else (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8) or ((a[i + 2].toInt() and 0xFF) shl 16) or (a[i + 3].toInt() shl 24)

    @JvmStatic fun i64(a: ByteArray, i: Int): Long =
        if (USE_VAR_HANDLES) LEViews.I64.get(a, i) as Long
        else (i32(a, i).toLong() and 0xFFFFFFFFL) or (i32(a, i + 4).toLong() shl 32)

    @JvmStatic fun set16(a: ByteArray, i: Int, v: Int) {
        if (USE_VAR_HANDLES) LEViews.I16.set(a, i, v.toShort())
        else { a[i] = v.toByte(); a[i + 1] = (v shr 8).toByte() }
    }

    @JvmStatic fun set32(a: ByteArray, i: Int, v: Int) {
        if (USE_VAR_HANDLES) LEViews.I32.set(a, i, v)
        else { a[i] = v.toByte(); a[i + 1] = (v shr 8).toByte(); a[i + 2] = (v shr 16).toByte(); a[i + 3] = (v shr 24).toByte() }
    }

    @JvmStatic fun set64(a: ByteArray, i: Int, v: Long) {
        if (USE_VAR_HANDLES) LEViews.I64.set(a, i, v)
        else { set32(a, i, v.toInt()); set32(a, i + 4, (v ushr 32).toInt()) }
    }
}

/** The VarHandles of [LE], in a class of their own: loaded only where VarHandle exists. */
internal object LEViews {
    @JvmField val I16: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
    @JvmField val I32: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    @JvmField val I64: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
}
