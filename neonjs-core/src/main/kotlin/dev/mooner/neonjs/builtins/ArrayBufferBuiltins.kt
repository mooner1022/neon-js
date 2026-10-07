package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.math.BigInteger
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.floor

/**
 * Shared Data Block of a SharedArrayBuffer. One block may be referenced by SharedArrayBuffer objects living in
 * different agents (threads); all of them see the same [data]. Growable blocks are allocated at their maximum size up
 * front so [data] never changes; only [byteLength] grows.
 */
class SharedDataBlock internal constructor(
    @JvmField val data: ByteArray,
    @Volatile @JvmField var byteLength: Int,
    @JvmField val maxByteLength: Int,
) {
    /** Critical section for atomic operations, the waiter lists (Atomics.wait / Atomics.notify) and grow. */
    @JvmField val lock = ReentrantLock()

    /** WaiterList per byte index; accessed only while holding [lock]. */
    @JvmField internal val waiters = HashMap<Int, ArrayDeque<AtomicsBuiltins.Waiter>>()
}

/** ArrayBuffer or SharedArrayBuffer object. */
class JSArrayBuffer internal constructor(
    proto: JSObject?,
    /** Backing bytes (capacity may exceed the byte length; never use `data.size` as the length). */
    @JvmField var data: ByteArray,
    byteLength: Int,
    /** `[[ArrayBufferMaxByteLength]]`, or -1 for fixed-length buffers. */
    @JvmField val maxByteLength: Int,
    /** Non-null for SharedArrayBuffers. */
    @JvmField val block: SharedDataBlock?,
) : JSObject(proto) {
    private var ownByteLength = byteLength
    @JvmField var detached = false
    /**
     * `[[ArrayBufferIsImmutable]]` (proposal-immutable-arraybuffer): set only on buffers created by
     * AllocateImmutableArrayBuffer, which are fixed-length and can be neither detached, resized nor written.
     */
    @JvmField var immutable = false

    val isShared: Boolean get() = block != null
    val isFixedLength: Boolean get() = maxByteLength < 0

    /** ArrayBufferByteLength (0 when detached). */
    fun byteLength(): Int {
        val b = block
        return b?.byteLength ?: ownByteLength
    }

    internal fun detach() {
        data = BufferOps.EMPTY
        ownByteLength = 0
        detached = true
    }

    /** Resizes a resizable (non-shared) buffer; newly exposed bytes are zero. */
    internal fun resize(newLen: Int) {
        val old = ownByteLength
        if (newLen > data.size) {
            val cap = minOf(maxOf(newLen.toLong(), data.size.toLong() + (data.size shr 1)), maxByteLength.toLong()).toInt()
            val nd = BufferOps.allocate(cap.toLong())
            System.arraycopy(data, 0, nd, 0, old)
            data = nd
        } else if (newLen < old) {
            java.util.Arrays.fill(data, newLen, old, 0)
        }
        ownByteLength = newLen
    }

    companion object {
        /** Creates a SharedArrayBuffer object in [realm] viewing [block] (used to share memory between agents). */
        @JvmStatic
        fun wrapShared(realm: Realm, block: SharedDataBlock): JSArrayBuffer =
            JSArrayBuffer(realm.intrinsic("%SharedArrayBuffer.prototype%"), block.data, block.byteLength, block.maxByteLength, block)

        /** The Shared Data Block of a SharedArrayBuffer value, or null. */
        @JvmStatic
        fun sharedBlockOf(v: Any?): SharedDataBlock? = (v as? JSArrayBuffer)?.block
    }
}

/** Element types of typed arrays / DataView accessors (ECMA-262 Table 71). */
enum class ElementType(val jsName: String, @JvmField val size: Int, @JvmField val isBigInt: Boolean) {
    INT8("Int8", 1, false), UINT8("Uint8", 1, false), UINT8C("Uint8Clamped", 1, false),
    INT16("Int16", 2, false), UINT16("Uint16", 2, false), INT32("Int32", 4, false), UINT32("Uint32", 4, false),
    FLOAT16("Float16", 2, false), FLOAT32("Float32", 4, false), FLOAT64("Float64", 8, false),
    BIGINT64("BigInt64", 8, true), BIGUINT64("BigUint64", 8, true);

    @JvmField val shift = Integer.numberOfTrailingZeros(size)
    val ctorName: String get() = jsName + "Array"

    /** ToNumber / ToBigInt as required before storing into this element type. */
    fun coerce(v: Any?): Any = if (isBigInt) Ops.toBigInt(v) else v as? Double ?: Ops.toNumber(v)
}

/**
 * Byte-array view VarHandles, kept apart from [BufferOps] so that their class (and the polymorphic-signature calls)
 * is only loaded where VarHandle exists; see [BufferOps.USE_VAR_HANDLES].
 */
internal object ByteViews {
    @JvmField val I16_LE: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
    @JvmField val I16_BE: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.BIG_ENDIAN)
    @JvmField val I32_LE: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    @JvmField val I32_BE: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.BIG_ENDIAN)
    @JvmField val I64_LE: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
    @JvmField val I64_BE: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)

    fun getI16(a: ByteArray, i: Int, le: Boolean): Short = if (le) I16_LE.get(a, i) as Short else I16_BE.get(a, i) as Short
    fun getI32(a: ByteArray, i: Int, le: Boolean): Int = if (le) I32_LE.get(a, i) as Int else I32_BE.get(a, i) as Int
    fun getI64(a: ByteArray, i: Int, le: Boolean): Long = if (le) I64_LE.get(a, i) as Long else I64_BE.get(a, i) as Long
    fun setI16(a: ByteArray, i: Int, v: Short, le: Boolean) = if (le) I16_LE.set(a, i, v) else I16_BE.set(a, i, v)
    fun setI32(a: ByteArray, i: Int, v: Int, le: Boolean) = if (le) I32_LE.set(a, i, v) else I32_BE.set(a, i, v)
    fun setI64(a: ByteArray, i: Int, v: Long, le: Boolean) = if (le) I64_LE.set(a, i, v) else I64_BE.set(a, i, v)
}

/** Raw byte-level access to buffer data (GetValueFromBuffer / SetValueInBuffer / atomics). */
internal object BufferOps {
    @JvmField val EMPTY = ByteArray(0)

    /** Largest supported buffer byte length. */
    const val MAX_BYTE_LENGTH = Int.MAX_VALUE - 8

    @JvmField val TWO_64: BigInteger = BigInteger.ONE.shiftLeft(64)

    /**
     * Whether multi-byte loads and stores go through byte-array view VarHandles ([ByteViews]): standard JVMs and
     * Android API 33+. Older Android has no VarHandle, so values are assembled from bytes instead
     * (`-Dneonjs.noVarHandle=true` forces that path, to test it on a JVM).
     */
    @JvmField val USE_VAR_HANDLES: Boolean = !java.lang.Boolean.getBoolean("neonjs.noVarHandle") && try {
        ByteViews.I16_LE
        true
    } catch (_: Throwable) {
        false
    }

    /** A Uint8Array over an immutable ArrayBuffer holding [data] (bytes modules, `with { type: "bytes" }`). */
    fun immutableBytes(realm: Realm, data: ByteArray): JSObject {
        if (data.size > MAX_BYTE_LENGTH) rangeErr("Array buffer allocation failed")
        val buf = JSArrayBuffer(realm.intrinsics["%ArrayBuffer.prototype%"], data, data.size, -1, null)
        buf.immutable = true
        return JSTypedArray(realm.intrinsics["%Uint8Array.prototype%"], ElementType.UINT8, buf, 0, data.size)
    }

    /** CreateByteDataBlock: zeroed bytes, RangeError instead of OutOfMemoryError. */
    fun allocate(len: Long): ByteArray {
        if (len < 0 || len > MAX_BYTE_LENGTH) rangeErr("Array buffer allocation failed")
        if (len == 0L) return EMPTY
        Agent.current.get()?.reserveAllocation(len)
        try {
            return ByteArray(len.toInt())
        } catch (_: OutOfMemoryError) {
            rangeErr("Array buffer allocation failed")
        }
    }

    // ------------------------------------------------------------------ raw loads / stores

    fun getI16(a: ByteArray, i: Int, le: Boolean): Short {
        if (USE_VAR_HANDLES) return ByteViews.getI16(a, i, le)
        val b0 = a[i].toInt() and 0xFF
        val b1 = a[i + 1].toInt() and 0xFF
        return (if (le) b0 or (b1 shl 8) else (b0 shl 8) or b1).toShort()
    }

    fun getI32(a: ByteArray, i: Int, le: Boolean): Int {
        if (USE_VAR_HANDLES) return ByteViews.getI32(a, i, le)
        var r = 0
        for (k in 0 until 4) r = r or ((a[i + (if (le) k else 3 - k)].toInt() and 0xFF) shl (8 * k))
        return r
    }

    fun getI64(a: ByteArray, i: Int, le: Boolean): Long {
        if (USE_VAR_HANDLES) return ByteViews.getI64(a, i, le)
        var r = 0L
        for (k in 0 until 8) r = r or ((a[i + (if (le) k else 7 - k)].toLong() and 0xFF) shl (8 * k))
        return r
    }

    fun setI16(a: ByteArray, i: Int, v: Short, le: Boolean) {
        if (USE_VAR_HANDLES) return ByteViews.setI16(a, i, v, le)
        val x = v.toInt()
        a[i + (if (le) 0 else 1)] = x.toByte()
        a[i + (if (le) 1 else 0)] = (x shr 8).toByte()
    }

    fun setI32(a: ByteArray, i: Int, v: Int, le: Boolean) {
        if (USE_VAR_HANDLES) return ByteViews.setI32(a, i, v, le)
        for (k in 0 until 4) a[i + (if (le) k else 3 - k)] = (v shr (8 * k)).toByte()
    }

    fun setI64(a: ByteArray, i: Int, v: Long, le: Boolean) {
        if (USE_VAR_HANDLES) return ByteViews.setI64(a, i, v, le)
        for (k in 0 until 8) a[i + (if (le) k else 7 - k)] = (v shr (8 * k)).toByte()
    }

    /** RawBytesToNumeric for a 64-bit pattern. */
    fun bigFromBits(bits: Long, unsigned: Boolean): BigInteger {
        val b = BigInteger.valueOf(bits)
        return if (unsigned && bits < 0) b.add(TWO_64) else b
    }

    /** GetValueFromBuffer: returns a Double or (for BigInt types) a BigInteger. */
    fun load(a: ByteArray, i: Int, t: ElementType, le: Boolean = true): Any = when (t) {
        ElementType.INT8 -> a[i].toDouble()
        ElementType.UINT8, ElementType.UINT8C -> (a[i].toInt() and 0xFF).toDouble()
        ElementType.INT16 -> getI16(a, i, le).toDouble()
        ElementType.UINT16 -> (getI16(a, i, le).toInt() and 0xFFFF).toDouble()
        ElementType.INT32 -> getI32(a, i, le).toDouble()
        ElementType.UINT32 -> (getI32(a, i, le).toLong() and 0xFFFFFFFFL).toDouble()
        ElementType.FLOAT16 -> Float16.toDouble(getI16(a, i, le))
        ElementType.FLOAT32 -> java.lang.Float.intBitsToFloat(getI32(a, i, le)).toDouble()
        ElementType.FLOAT64 -> java.lang.Double.longBitsToDouble(getI64(a, i, le))
        ElementType.BIGINT64 -> BigInteger.valueOf(getI64(a, i, le))
        ElementType.BIGUINT64 -> bigFromBits(getI64(a, i, le), true)
    }

    /** SetValueInBuffer: [v] must already be a Double (Number types) or BigInteger (BigInt types). */
    fun store(a: ByteArray, i: Int, t: ElementType, v: Any, le: Boolean = true) {
        when (t) {
            ElementType.INT8, ElementType.UINT8 -> a[i] = Ops.toInt32(v as Double).toByte()
            ElementType.UINT8C -> a[i] = clamp(v as Double).toByte()
            ElementType.INT16, ElementType.UINT16 -> setI16(a, i, Ops.toInt32(v as Double).toShort(), le)
            ElementType.INT32, ElementType.UINT32 -> setI32(a, i, Ops.toInt32(v as Double), le)
            ElementType.FLOAT16 -> setI16(a, i, Float16.fromDouble(v as Double), le)
            ElementType.FLOAT32 -> setI32(a, i, java.lang.Float.floatToRawIntBits((v as Double).toFloat()), le)
            ElementType.FLOAT64 -> setI64(a, i, java.lang.Double.doubleToRawLongBits(v as Double), le)
            ElementType.BIGINT64, ElementType.BIGUINT64 -> setI64(a, i, (v as BigInteger).toLong(), le)
        }
    }

    /** ToUint8Clamp on a Number. */
    fun clamp(d: Double): Int {
        if (!(d > 0)) return 0
        if (d >= 255) return 255
        val f = floor(d)
        val diff = d - f
        if (diff < 0.5) return f.toInt()
        if (diff > 0.5) return f.toInt() + 1
        val fi = f.toInt()
        return if (fi and 1 == 0) fi else fi + 1
    }
}

internal object ArrayBufferBuiltins {
    fun install(realm: Realm) {
        installArrayBuffer(realm)
        installSharedArrayBuffer(realm)
        realm.intrinsics["%DetachArrayBuffer%"] = NativeFunction(realm, "DetachArrayBuffer", 1, { _, _, args, _ ->
            val b = args.arg(0) as? JSArrayBuffer
            if (b == null || b.isShared) typeErr("DetachArrayBuffer: argument is not an ArrayBuffer")
            if (b.immutable) typeErr("Cannot detach an immutable ArrayBuffer")
            b.detach()
            Null
        })
    }

    /** GetArrayBufferMaxByteLengthOption: -1 when absent. */
    private fun maxByteLengthOption(options: Any?): Long {
        if (options !is JSObject) return -1
        val m = options.get("maxByteLength", options)
        if (m === Undefined) return -1
        return Ops.toIndex(m)
    }

    /** AllocateArrayBuffer(constructor, byteLength [, maxByteLength]); [maxByteLength] < 0 means fixed length. */
    fun allocate(realm: Realm, ctor: JSObject?, byteLength: Long, maxByteLength: Long = -1): JSArrayBuffer {
        if (maxByteLength >= 0 && byteLength > maxByteLength) rangeErr("byteLength exceeds maxByteLength")
        val proto = if (ctor == null) realm.intrinsic("%ArrayBuffer.prototype%")
        else Ops.getPrototypeFromConstructor(ctor) { it.intrinsic("%ArrayBuffer.prototype%") }
        val data = BufferOps.allocate(byteLength)
        if (maxByteLength > BufferOps.MAX_BYTE_LENGTH) rangeErr("Invalid array buffer max length")
        return JSArrayBuffer(proto, data, byteLength.toInt(), maxByteLength.toInt(), null)
    }

    /** AllocateImmutableArrayBuffer(%ArrayBuffer%, byteLength, fromBlock, fromIndex, count) */
    private fun allocateImmutable(realm: Realm, byteLength: Long, from: ByteArray, fromIndex: Int, count: Int): JSArrayBuffer {
        val b = allocate(realm, null, byteLength)
        System.arraycopy(from, fromIndex, b.data, 0, count)
        b.immutable = true
        return b
    }

    private fun thisBuffer(t: Any?, method: String, shared: Boolean): JSArrayBuffer {
        val b = t as? JSArrayBuffer
        if (b == null || b.isShared != shared) {
            typeErr("Method ${if (shared) "SharedArrayBuffer" else "ArrayBuffer"}.prototype.$method called on incompatible receiver ${Ops.describe(t)}")
        }
        return b
    }

    private fun installArrayBuffer(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%ArrayBuffer.prototype%"] = proto
        val ctor = makeCtor(realm, "ArrayBuffer", 1, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor ArrayBuffer requires 'new'")
            val byteLength = Ops.toIndex(args.arg(0))
            val max = maxByteLengthOption(args.arg(1))
            allocate(f.realm, nt, byteLength, max)
        }
        realm.intrinsics["%ArrayBuffer%"] = ctor
        realm.global("ArrayBuffer", ctor)
        ctor.method(realm, "isView", 1) { _, _, args, _ ->
            val v = args.arg(0)
            v is JSTypedArray || v is JSDataView
        }
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }

        proto.getter(realm, "byteLength") { _, t, _, _ -> thisBuffer(t, "byteLength", false).byteLength().toDouble() }
        proto.getter(realm, "detached") { _, t, _, _ -> thisBuffer(t, "detached", false).detached }
        proto.getter(realm, "immutable") { _, t, _, _ -> thisBuffer(t, "immutable", false).immutable }
        proto.getter(realm, "maxByteLength") { _, t, _, _ ->
            val b = thisBuffer(t, "maxByteLength", false)
            (if (b.detached) 0 else if (b.isFixedLength) b.byteLength() else b.maxByteLength).toDouble()
        }
        proto.getter(realm, "resizable") { _, t, _, _ -> !thisBuffer(t, "resizable", false).isFixedLength }
        proto.method(realm, "resize", 1) { _, t, args, _ ->
            val b = thisBuffer(t, "resize", false)
            if (b.isFixedLength) typeErr("Method ArrayBuffer.prototype.resize called on a non-resizable ArrayBuffer")
            val newLen = Ops.toIndex(args.arg(0))
            if (b.detached) typeErr("Cannot resize a detached ArrayBuffer")
            if (newLen > b.maxByteLength) rangeErr("ArrayBuffer.prototype.resize: Invalid length parameter")
            b.resize(newLen.toInt())
            Undefined
        }
        proto.method(realm, "slice", 2) { f, t, args, _ ->
            val o = thisBuffer(t, "slice", false)
            if (o.detached) typeErr("Cannot perform ArrayBuffer.prototype.slice on a detached ArrayBuffer")
            val len = o.byteLength().toLong()
            val first = relIndex(args.arg(0), len, 0)
            val fin = relIndex(args.arg(1), len, len)
            val newLen = maxOf(fin - first, 0)
            val c = Ops.speciesConstructor(o, f.realm.intrinsic("%ArrayBuffer%"))
            val n = Ops.construct(c, arrayOf(newLen.toDouble()))
            if (n !is JSArrayBuffer || n.isShared) typeErr("ArrayBuffer subclass returned this from species constructor")
            if (n.detached) typeErr("Species constructor returned a detached ArrayBuffer")
            if (n.immutable) typeErr("Species constructor returned an immutable ArrayBuffer")
            if (n === o) typeErr("ArrayBuffer subclass returned this from species constructor")
            if (n.byteLength() < newLen) typeErr("Species constructor returned a too small ArrayBuffer")
            if (o.detached) typeErr("Cannot perform ArrayBuffer.prototype.slice on a detached ArrayBuffer")
            val cur = o.byteLength()
            if (first < cur) {
                val count = minOf(newLen, cur - first).toInt()
                System.arraycopy(o.data, first.toInt(), n.data, 0, count)
            }
            n
        }
        proto.method(realm, "transfer", 0) { f, t, args, _ -> copyAndDetach(f.realm, t, args.arg(0), true, "transfer") }
        proto.method(realm, "transferToFixedLength", 0) { f, t, args, _ -> copyAndDetach(f.realm, t, args.arg(0), false, "transferToFixedLength") }
        proto.method(realm, "transferToImmutable", 0) { f, t, args, _ ->
            copyAndDetach(f.realm, t, args.arg(0), false, "transferToImmutable", immutable = true)
        }
        proto.method(realm, "sliceToImmutable", 2) { f, t, args, _ -> sliceToImmutable(f.realm, t, args.arg(0), args.arg(1)) }
        proto.value(JSSymbol.toStringTag, "ArrayBuffer", Attr.CONFIGURABLE)
    }

    /** ArrayBufferCopyAndDetach; [immutable] selects the ~immutable~ mode (transferToImmutable). */
    private fun copyAndDetach(realm: Realm, t: Any?, newLength: Any?, preserveResizability: Boolean, method: String, immutable: Boolean = false): JSArrayBuffer {
        val o = thisBuffer(t, method, false)
        val newByteLength = if (newLength === Undefined) o.byteLength().toLong() else Ops.toIndex(newLength)
        if (o.detached) typeErr("Cannot perform ArrayBuffer.prototype.$method on a detached ArrayBuffer")
        if (o.immutable) typeErr("Cannot perform ArrayBuffer.prototype.$method on an immutable ArrayBuffer")
        val newMax = if (preserveResizability && !o.isFixedLength) o.maxByteLength.toLong() else -1L
        val oldLength = o.byteLength()
        val nb = if (newByteLength == oldLength.toLong()) {
            // same length: move the data block instead of copying it
            if (newMax >= 0 && newByteLength > newMax) rangeErr("byteLength exceeds maxByteLength")
            JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), o.data, oldLength, newMax.toInt(), null)
        } else {
            val b = allocate(realm, null, newByteLength, newMax)
            System.arraycopy(o.data, 0, b.data, 0, minOf(newByteLength, oldLength.toLong()).toInt())
            b
        }
        nb.immutable = immutable
        o.detach()
        return nb
    }

    /** ArrayBuffer.prototype.sliceToImmutable(start, end) */
    private fun sliceToImmutable(realm: Realm, t: Any?, start: Any?, end: Any?): JSArrayBuffer {
        val o = thisBuffer(t, "sliceToImmutable", false)
        if (o.detached) typeErr("Cannot perform ArrayBuffer.prototype.sliceToImmutable on a detached ArrayBuffer")
        val len = o.byteLength().toLong()
        val first = relIndex(start, len, 0)
        val fin = relIndex(end, len, len)
        val newLen = maxOf(fin - first, 0)
        if (o.detached) typeErr("Cannot perform ArrayBuffer.prototype.sliceToImmutable on a detached ArrayBuffer")
        if (o.byteLength() < fin) rangeErr("ArrayBuffer.prototype.sliceToImmutable: the buffer was shrunk below the end of the slice")
        return allocateImmutable(realm, newLen, o.data, first.toInt(), newLen.toInt())
    }

    private fun installSharedArrayBuffer(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%SharedArrayBuffer.prototype%"] = proto
        val ctor = makeCtor(realm, "SharedArrayBuffer", 1, proto) { _, _, args, nt ->
            if (nt == null) typeErr("Constructor SharedArrayBuffer requires 'new'")
            val byteLength = Ops.toIndex(args.arg(0))
            val max = maxByteLengthOption(args.arg(1))
            allocateShared(nt, byteLength, max)
        }
        realm.intrinsics["%SharedArrayBuffer%"] = ctor
        realm.global("SharedArrayBuffer", ctor)
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }

        proto.getter(realm, "byteLength") { _, t, _, _ -> thisBuffer(t, "byteLength", true).byteLength().toDouble() }
        proto.getter(realm, "growable") { _, t, _, _ -> !thisBuffer(t, "growable", true).isFixedLength }
        proto.getter(realm, "maxByteLength") { _, t, _, _ ->
            val b = thisBuffer(t, "maxByteLength", true)
            (if (b.isFixedLength) b.byteLength() else b.maxByteLength).toDouble()
        }
        proto.method(realm, "grow", 1) { _, t, args, _ ->
            val b = thisBuffer(t, "grow", true)
            if (b.isFixedLength) typeErr("Method SharedArrayBuffer.prototype.grow called on a non-growable SharedArrayBuffer")
            val newLen = Ops.toIndex(args.arg(0))
            val block = b.block!!
            block.lock.lock()
            try {
                val cur = block.byteLength
                if (newLen != cur.toLong()) {
                    if (newLen < cur || newLen > b.maxByteLength) rangeErr("SharedArrayBuffer.prototype.grow: Invalid length parameter")
                    block.byteLength = newLen.toInt()
                }
            } finally {
                block.lock.unlock()
            }
            Undefined
        }
        proto.method(realm, "slice", 2) { f, t, args, _ ->
            val o = thisBuffer(t, "slice", true)
            val len = o.byteLength().toLong()
            val first = relIndex(args.arg(0), len, 0)
            val fin = relIndex(args.arg(1), len, len)
            val newLen = maxOf(fin - first, 0)
            val c = Ops.speciesConstructor(o, f.realm.intrinsic("%SharedArrayBuffer%"))
            val n = Ops.construct(c, arrayOf(newLen.toDouble()))
            if (n !is JSArrayBuffer || !n.isShared) typeErr("Species constructor did not return a SharedArrayBuffer")
            if (n.block === o.block) typeErr("SharedArrayBuffer subclass returned this from species constructor")
            if (n.byteLength() < newLen) typeErr("Species constructor returned a too small SharedArrayBuffer")
            System.arraycopy(o.data, first.toInt(), n.data, 0, newLen.toInt())
            n
        }
        proto.value(JSSymbol.toStringTag, "SharedArrayBuffer", Attr.CONFIGURABLE)
    }

    /** AllocateSharedArrayBuffer(constructor, byteLength [, maxByteLength]) */
    fun allocateShared(ctor: JSObject, byteLength: Long, maxByteLength: Long): JSArrayBuffer {
        if (maxByteLength >= 0 && byteLength > maxByteLength) rangeErr("byteLength exceeds maxByteLength")
        val proto = Ops.getPrototypeFromConstructor(ctor) { it.intrinsic("%SharedArrayBuffer.prototype%") }
        val data = BufferOps.allocate(if (maxByteLength >= 0) maxByteLength else byteLength)
        val block = SharedDataBlock(data, byteLength.toInt(), maxByteLength.toInt())
        return JSArrayBuffer(proto, data, byteLength.toInt(), block.maxByteLength, block)
    }
}
