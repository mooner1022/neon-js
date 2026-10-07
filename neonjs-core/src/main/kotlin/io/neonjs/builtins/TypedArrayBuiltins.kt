package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.Iteration
import java.math.BigInteger

/** TypedArray instance: an integer-indexed exotic object viewing an [JSArrayBuffer]. */
class JSTypedArray internal constructor(
    proto: JSObject?,
    @JvmField val type: ElementType,
    @JvmField val buffer: JSArrayBuffer,
    @JvmField val byteOffset: Int,
    /** [[ArrayLength]], or -1 (auto) for length-tracking views of resizable buffers. */
    @JvmField val fixedLength: Int,
) : JSObject(proto), TypedArrayLike {
    init {
        special = special or SPECIAL_ALL
    }

    override val className: String get() = type.ctorName

    /** TypedArrayLength, or -1 when IsTypedArrayOutOfBounds (which includes a detached buffer). */
    fun lengthOrOOB(): Int {
        val b = buffer
        if (b.detached) return -1
        val bufLen = b.byteLength()
        val off = byteOffset
        if (off > bufLen) return -1
        val n = fixedLength
        if (n < 0) return (bufLen - off) shr type.shift
        return if (off.toLong() + (n.toLong() shl type.shift) > bufLen) -1 else n
    }

    override fun typedLength(): Long = maxOf(lengthOrOOB(), 0).toLong()
    override fun isOutOfBounds(): Boolean = lengthOrOOB() < 0

    /** TypedArrayByteLength (0 when out of bounds). */
    fun byteLengthOrZero(): Int = maxOf(lengthOrOOB(), 0) shl type.shift

    /** IsTypedArrayFixedLength */
    fun isFixedLength(): Boolean = fixedLength >= 0 && (buffer.isFixedLength || buffer.isShared)

    fun isValidIndex(i: Int): Boolean = i >= 0 && i < lengthOrOOB()

    /** TypedArrayGetElement: Undefined for invalid indices. */
    fun getIndex(i: Int): Any {
        if (i < 0 || i >= lengthOrOOB()) return Undefined
        return BufferOps.load(buffer.data, byteOffset + (i shl type.shift), type)
    }

    /** TypedArraySetElement: converts [v] first (which may run user code), then stores if the index is valid. */
    fun setIndex(i: Int, v: Any?) {
        val num = type.coerce(v)
        if (i >= 0 && i < lengthOrOOB()) BufferOps.store(buffer.data, byteOffset + (i shl type.shift), type, num)
    }

    /** Stores an already converted value (Double / BigInteger) if the index is valid. */
    internal fun storeIndex(i: Int, num: Any) {
        if (i >= 0 && i < lengthOrOOB()) BufferOps.store(buffer.data, byteOffset + (i shl type.shift), type, num)
    }

    // ------------------------------------------------------------------ internal methods

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) return ordinaryGetOwnProperty(key)
        val v = getIndex(i)
        if (v === Undefined) return null
        // elements of an immutable buffer are non-writable and non-configurable
        return PropertyDescriptor.data(v, if (buffer.immutable) Attr.ENUMERABLE else Attr.ALL)
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) return super.getOwnValue(key, receiver)
        val v = getIndex(i)
        return if (v === Undefined) NotFound else v
    }

    override fun hasOwnProperty(key: Any): Boolean {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) {
            val p = props ?: return false
            return p.find(key) >= 0
        }
        return isValidIndex(i)
    }

    override fun hasProperty(key: Any): Boolean {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) return super.hasProperty(key)
        return isValidIndex(i)
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) return ordinaryDefineOwnProperty(key, desc)
        if (!isValidIndex(i)) return false
        if (buffer.immutable) {
            // redefining an immutable element only succeeds for a compatible descriptor with the SameValue value
            return validateAndApply(key, false, desc, PropertyDescriptor.data(getIndex(i), Attr.ENUMERABLE))
        }
        if (desc.hasConfigurable && !desc.configurable) return false
        if (desc.hasEnumerable && !desc.enumerable) return false
        if (desc.isAccessor) return false
        if (desc.hasWritable && !desc.writable) return false
        if (desc.hasValue) setIndex(i, desc.value)
        return true
    }

    override fun get(key: Any, receiver: Any?): Any? {
        if (key is Int) return getIndex(key)
        if (key is String) {
            val i = stringIndex(key)
            if (i != NOT_NUMERIC) return getIndex(i)
        }
        return super.get(key, receiver)
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        val i = numericKey(key)
        if (i != NOT_NUMERIC) {
            // assignments to canonical numeric keys of an immutable buffer fail without converting the value
            if (buffer.immutable) return false
            if (receiver === this) {
                setIndex(i, value)
                return true
            }
            if (!isValidIndex(i)) return true
        }
        return ordinarySet(key, value, receiver)
    }

    override fun delete(key: Any): Boolean {
        val i = numericKey(key)
        if (i == NOT_NUMERIC) return super.delete(key)
        return !isValidIndex(i)
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val n = lengthOrOOB()
        return ordinaryOwnKeys(if (n > 0) List<Any>(n) { it } else null)
    }

    override fun preventExtensions(): Boolean {
        if (!isFixedLength()) return false
        return super.preventExtensions()
    }

    companion object {
        /** [numericKey] result for keys that are not canonical numeric strings. */
        const val NOT_NUMERIC = Int.MIN_VALUE

        /** Index for Int keys / canonical numeric strings (-1 when never a valid integer index), else [NOT_NUMERIC]. */
        @JvmStatic
        fun numericKey(key: Any): Int = when (key) {
            is Int -> key
            is String -> stringIndex(key)
            else -> NOT_NUMERIC
        }

        @JvmStatic
        fun stringIndex(s: String): Int {
            if (s.isEmpty()) return NOT_NUMERIC
            val c = s[0]
            // ToString(Number) always starts with a digit, '-', "Infinity" or "NaN"
            if (!(c in '0'..'9' || c == '-' || c == 'I' || c == 'N')) return NOT_NUMERIC
            val n = Ops.canonicalNumericIndexString(s) ?: return NOT_NUMERIC
            if (n >= 0 && n <= Int.MAX_VALUE && n == Math.floor(n) && !(n == 0.0 && 1.0 / n < 0)) return n.toInt()
            return -1
        }
    }
}

internal object TypedArrayBuiltins {
    private val ctorKeys = ElementType.entries.map { "%${it.ctorName}%" }.toTypedArray()
    private val protoKeys = ElementType.entries.map { "%${it.ctorName}.prototype%" }.toTypedArray()

    fun ctorOf(realm: Realm, t: ElementType): JSObject = realm.intrinsic(ctorKeys[t.ordinal])
    fun protoOf(realm: Realm, t: ElementType): JSObject = realm.intrinsic(protoKeys[t.ordinal])

    /**
     * ValidateTypedArray: the receiver must be a typed array that is not out of bounds; with [write] (accessMode
     * ~write~) its buffer must not be immutable.
     */
    fun validate(t: Any?, method: String, write: Boolean = false): JSTypedArray {
        val ta = t as? JSTypedArray ?: typeErr("$method: receiver is not a typed array")
        if (write && ta.buffer.immutable) typeErr("$method: typed array is backed by an immutable ArrayBuffer")
        if (ta.lengthOrOOB() < 0) typeErr("$method: typed array is detached or out of bounds")
        return ta
    }

    private fun thisTA(t: Any?, method: String): JSTypedArray =
        t as? JSTypedArray ?: typeErr("Method %TypedArray%.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    /** AllocateTypedArray with a length, using an already resolved prototype. */
    fun allocate(realm: Realm, t: ElementType, proto: JSObject, length: Long): JSTypedArray {
        if (length > (BufferOps.MAX_BYTE_LENGTH shr t.shift)) rangeErr("Invalid typed array length: $length")
        val buf = ArrayBufferBuiltins.allocate(realm, null, length shl t.shift)
        return JSTypedArray(proto, t, buf, 0, length.toInt())
    }

    /** TypedArrayCreateFromConstructor; [write] = accessMode ~write~ (the result will be written). */
    fun createFromConstructor(c: JSObject, args: Array<Any?>, write: Boolean = false): JSTypedArray {
        val ta = validate(Ops.construct(c, args), "TypedArray constructor", write)
        if (args.size == 1) {
            val n = args[0]
            if (n is Double && ta.lengthOrOOB() < n) typeErr("TypedArray constructor created an array which is too small")
        }
        return ta
    }

    /** TypedArraySpeciesCreate */
    fun speciesCreate(realm: Realm, exemplar: JSTypedArray, args: Array<Any?>, write: Boolean = false): JSTypedArray {
        val c = Ops.speciesConstructor(exemplar, ctorOf(realm, exemplar.type))
        val r = createFromConstructor(c, args, write)
        if (r.type.isBigInt != exemplar.type.isBigInt) typeErr("TypedArray species constructor returned an array with an incompatible content type")
        return r
    }

    /** TypedArrayCreateSameType (the intrinsic constructor is unobservable, so allocate directly). */
    fun createSameType(realm: Realm, exemplar: JSTypedArray, length: Int): JSTypedArray =
        allocate(realm, exemplar.type, protoOf(realm, exemplar.type), length.toLong())

    /** IteratorToList(GetIteratorFromMethod(obj, method)) with a fast path for pristine arrays. */
    fun iterableToList(realm: Realm, obj: Any?, method: Any?): List<Any?> {
        if (obj is JSArray && !obj.sparse && method === realm.arrayProtoValues && Iteration.isPristineArrayIteration(realm, obj)) {
            return obj.toList()
        }
        val out = ArrayList<Any?>()
        val rec = Iteration.fromMethod(obj, method)
        while (true) {
            val v = Iteration.stepValue(rec)
            if (v === NotFound) return out
            out.add(v)
        }
    }

    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%TypedArray.prototype%"] = proto
        val ctor = makeCtor(realm, "TypedArray", 0, proto) { _, _, _, _ -> typeErr("Abstract class TypedArray not directly constructable") }
        realm.intrinsics["%TypedArray%"] = ctor
        installStatics(realm, ctor)
        installPrototype(realm, proto)
        for (t in ElementType.entries) installConcrete(realm, t, ctor, proto)
        Uint8ArrayCodec.install(realm, ctorOf(realm, ElementType.UINT8), protoOf(realm, ElementType.UINT8))
    }

    // ================================================================== constructors

    private fun installConcrete(realm: Realm, t: ElementType, taCtor: JSObject, taProto: JSObject) {
        val proto = JSObject(taProto)
        val ctor = makeCtor(realm, t.ctorName, 3, proto, taCtor) { f, _, args, nt -> construct(f.realm, t, args, nt) }
        ctor.defineOwn("BYTES_PER_ELEMENT", t.size.toDouble(), Attr.NONE)
        proto.defineOwn("BYTES_PER_ELEMENT", t.size.toDouble(), Attr.NONE)
        realm.intrinsics[ctorKeys[t.ordinal]] = ctor
        realm.intrinsics[protoKeys[t.ordinal]] = proto
        realm.global(t.ctorName, ctor)
    }

    private fun construct(realm: Realm, t: ElementType, args: Array<Any?>, nt: JSObject?): JSTypedArray {
        if (nt == null) typeErr("Constructor ${t.ctorName} requires 'new'")
        val first = args.arg(0)
        if (first !is JSObject) {
            val len = Ops.toIndex(first)
            val proto = Ops.getPrototypeFromConstructor(nt) { it.intrinsic(protoKeys[t.ordinal]) }
            return allocate(realm, t, proto, len)
        }
        val proto = Ops.getPrototypeFromConstructor(nt) { it.intrinsic(protoKeys[t.ordinal]) }
        return when (first) {
            is JSTypedArray -> fromTypedArray(realm, t, proto, first)
            is JSArrayBuffer -> fromArrayBuffer(t, proto, first, args.arg(1), args.arg(2))
            else -> {
                val usingIterator = Ops.getMethod(first, JSSymbol.iterator)
                if (usingIterator !== Undefined) {
                    val values = iterableToList(realm, first, usingIterator)
                    val ta = allocate(realm, t, proto, values.size.toLong())
                    for (k in values.indices) ta.setIndex(k, values[k])
                    ta
                } else {
                    val len = Ops.lengthOfArrayLike(first)
                    val ta = allocate(realm, t, proto, len)
                    for (k in 0 until len.toInt()) ta.setIndex(k, first.get(k, first))
                    ta
                }
            }
        }
    }

    /** InitializeTypedArrayFromTypedArray */
    private fun fromTypedArray(realm: Realm, t: ElementType, proto: JSObject, src: JSTypedArray): JSTypedArray {
        val len = src.lengthOrOOB()
        if (len < 0) typeErr("Cannot construct a typed array from a detached or out of bounds typed array")
        val ta = allocate(realm, t, proto, len.toLong())
        if (src.type.isBigInt != t.isBigInt) typeErr("Cannot mix BigInt and other types, use explicit conversions")
        copyElements(src.buffer.data, src.byteOffset, src.type, ta.buffer.data, 0, t, len)
        return ta
    }

    /** Copies [count] elements, converting between element types when they differ (content types must match). */
    fun copyElements(src: ByteArray, srcIndex: Int, srcType: ElementType, dst: ByteArray, dstIndex: Int, dstType: ElementType, count: Int) {
        if (srcType == dstType) {
            System.arraycopy(src, srcIndex, dst, dstIndex, count shl srcType.shift)
            return
        }
        var si = srcIndex
        var di = dstIndex
        for (k in 0 until count) {
            BufferOps.store(dst, di, dstType, BufferOps.load(src, si, srcType))
            si += srcType.size
            di += dstType.size
        }
    }

    /** InitializeTypedArrayFromArrayBuffer */
    private fun fromArrayBuffer(t: ElementType, proto: JSObject, buffer: JSArrayBuffer, byteOffset: Any?, length: Any?): JSTypedArray {
        val size = t.size
        val offset = Ops.toIndex(byteOffset)
        if (offset % size != 0L) rangeErr("Start offset of ${t.ctorName} should be a multiple of $size")
        val newLength = if (length !== Undefined) Ops.toIndex(length) else -1L
        if (buffer.detached) typeErr("Cannot construct ${t.ctorName} on a detached ArrayBuffer")
        val bufLen = buffer.byteLength().toLong()
        if (length === Undefined && !buffer.isFixedLength) {
            if (offset > bufLen) rangeErr("Start offset $offset is outside the bounds of the buffer")
            return JSTypedArray(proto, t, buffer, offset.toInt(), -1)
        }
        val newByteLength: Long
        if (length === Undefined) {
            if (bufLen % size != 0L) rangeErr("Byte length of ${t.ctorName} should be a multiple of $size")
            newByteLength = bufLen - offset
            if (newByteLength < 0) rangeErr("Start offset $offset is outside the bounds of the buffer")
        } else {
            newByteLength = newLength * size
            if (offset + newByteLength > bufLen) rangeErr("Invalid typed array length: $newLength")
        }
        return JSTypedArray(proto, t, buffer, offset.toInt(), (newByteLength / size).toInt())
    }

    // ================================================================== %TypedArray% statics

    private fun installStatics(realm: Realm, ctor: JSObject) {
        ctor.method(realm, "from", 1) { f, t, args, _ ->
            if (!Ops.isConstructor(t)) typeErr("${Ops.describe(t)} is not a constructor")
            val c = t as JSObject
            val mapfn = args.arg(1)
            val thisArg = args.arg(2)
            val mapping = mapfn !== Undefined
            if (mapping && !Ops.isCallable(mapfn)) typeErr("${Ops.describe(mapfn)} is not a function")
            val source = args.arg(0)
            val usingIterator = Ops.getMethod(source, JSSymbol.iterator)
            if (usingIterator !== Undefined) {
                val values = iterableToList(f.realm, source, usingIterator)
                val target = createFromConstructor(c, arrayOf(values.size.toDouble()), true)
                for (k in values.indices) {
                    val v = values[k]
                    target.setIndex(k, if (mapping) (mapfn as JSObject).call(thisArg, arrayOf(v, k.toDouble())) else v)
                }
                target
            } else {
                val arrayLike = Ops.toObject(source)
                val len = Ops.lengthOfArrayLike(arrayLike)
                val target = createFromConstructor(c, arrayOf(len.toDouble()), true)
                for (k in 0 until len.toInt()) {
                    val v = arrayLike.get(k, arrayLike)
                    target.setIndex(k, if (mapping) (mapfn as JSObject).call(thisArg, arrayOf(v, k.toDouble())) else v)
                }
                target
            }
        }
        ctor.method(realm, "of", 0) { _, t, args, _ ->
            if (!Ops.isConstructor(t)) typeErr("${Ops.describe(t)} is not a constructor")
            val target = createFromConstructor(t as JSObject, arrayOf(args.size.toDouble()), true)
            for (k in args.indices) target.setIndex(k, args[k])
            target
        }
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }
    }

    // ================================================================== %TypedArray%.prototype

    private fun relative(v: Any?, len: Int): Int = relIndex(v, len.toLong(), 0).toInt()
    private fun relativeEnd(v: Any?, len: Int): Int = relIndex(v, len.toLong(), len.toLong()).toInt()

    private fun installPrototype(realm: Realm, proto: JSObject) {
        proto.getter(realm, "buffer") { _, t, _, _ -> thisTA(t, "buffer").buffer }
        proto.getter(realm, "byteLength") { _, t, _, _ -> thisTA(t, "byteLength").byteLengthOrZero().toDouble() }
        proto.getter(realm, "byteOffset") { _, t, _, _ ->
            val ta = thisTA(t, "byteOffset")
            if (ta.lengthOrOOB() < 0) 0.0 else ta.byteOffset.toDouble()
        }
        proto.getter(realm, "length") { _, t, _, _ -> maxOf(thisTA(t, "length").lengthOrOOB(), 0).toDouble() }
        proto.getter(realm, JSSymbol.toStringTag) { _, t, _, _ -> if (t is JSTypedArray) t.type.ctorName else Undefined }

        proto.method(realm, "at", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.at")
            val len = ta.lengthOrOOB()
            val rel = Ops.toIntegerOrInfinity(args.arg(0))
            val k = if (rel >= 0) rel else len + rel
            if (k < 0 || k >= len) Undefined else ta.getIndex(k.toInt())
        }
        proto.method(realm, "copyWithin", 2) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.copyWithin", true)
            var len = ta.lengthOrOOB()
            val to = relative(args.arg(0), len)
            val from = relative(args.arg(1), len)
            val fin = relativeEnd(args.arg(2), len)
            var count = minOf(fin - from, len - to)
            if (count > 0) {
                len = ta.lengthOrOOB()
                if (len < 0) typeErr("%TypedArray%.prototype.copyWithin: typed array is detached or out of bounds")
                // side effects may have shrunk the array: copy the longest still-applicable prefix
                count = minOf(count, len - from, len - to)
                if (count > 0) {
                    val shift = ta.type.shift
                    val data = ta.buffer.data
                    System.arraycopy(data, ta.byteOffset + (from shl shift), data, ta.byteOffset + (to shl shift), count shl shift)
                }
            }
            ta
        }
        proto.method(realm, "entries", 0) { f, t, _, _ ->
            ArrayIteratorObject(f.realm.arrayIteratorPrototype, validate(t, "%TypedArray%.prototype.entries"), 2)
        }
        proto.method(realm, "keys", 0) { f, t, _, _ ->
            ArrayIteratorObject(f.realm.arrayIteratorPrototype, validate(t, "%TypedArray%.prototype.keys"), 0)
        }
        val values = proto.method(realm, "values", 0) { f, t, _, _ ->
            ArrayIteratorObject(f.realm.arrayIteratorPrototype, validate(t, "%TypedArray%.prototype.values"), 1)
        }
        proto.defineOwn(JSSymbol.iterator, values, Attr.WC)
        proto.method(realm, "every", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.every")
            val len = ta.lengthOrOOB()
            val cb = callable(args.arg(0), "every")
            var r = true
            for (k in 0 until len) {
                if (!Ops.toBoolean(cb.call(args.arg(1), arrayOf(ta.getIndex(k), k.toDouble(), ta)))) { r = false; break }
            }
            r
        }
        proto.method(realm, "some", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.some")
            val len = ta.lengthOrOOB()
            val cb = callable(args.arg(0), "some")
            var r = false
            for (k in 0 until len) {
                if (Ops.toBoolean(cb.call(args.arg(1), arrayOf(ta.getIndex(k), k.toDouble(), ta)))) { r = true; break }
            }
            r
        }
        proto.method(realm, "forEach", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.forEach")
            val len = ta.lengthOrOOB()
            val cb = callable(args.arg(0), "forEach")
            for (k in 0 until len) cb.call(args.arg(1), arrayOf(ta.getIndex(k), k.toDouble(), ta))
            Undefined
        }
        proto.method(realm, "fill", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.fill", true)
            var len = ta.lengthOrOOB()
            val value = ta.type.coerce(args.arg(0))
            val start = relative(args.arg(1), len)
            var end = relativeEnd(args.arg(2), len)
            len = ta.lengthOrOOB()
            if (len < 0) typeErr("%TypedArray%.prototype.fill: typed array is detached or out of bounds")
            end = minOf(end, len)
            if (start < end) {
                val data = ta.buffer.data
                val type = ta.type
                val first = ta.byteOffset + (start shl type.shift)
                BufferOps.store(data, first, type, value)
                if (type.size == 1) java.util.Arrays.fill(data, first + 1, ta.byteOffset + end, data[first])
                else {
                    // replicate the encoded element by doubling copies
                    val total = (end - start) shl type.shift
                    var filled = type.size
                    while (filled < total) {
                        val n = minOf(filled, total - filled)
                        System.arraycopy(data, first, data, first + filled, n)
                        filled += n
                    }
                }
            }
            ta
        }
        proto.method(realm, "filter", 1) { f, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.filter")
            val len = ta.lengthOrOOB()
            val cb = callable(args.arg(0), "filter")
            val kept = ArrayList<Any>()
            for (k in 0 until len) {
                val v = ta.getIndex(k)
                if (Ops.toBoolean(cb.call(args.arg(1), arrayOf(v, k.toDouble(), ta)))) kept.add(v)
            }
            val a = speciesCreate(f.realm, ta, arrayOf(kept.size.toDouble()), true)
            for (n in kept.indices) a.setIndex(n, kept[n])
            a
        }
        for ((name, fromEnd, wantIndex) in listOf(
            Triple("find", false, false), Triple("findIndex", false, true),
            Triple("findLast", true, false), Triple("findLastIndex", true, true),
        )) {
            proto.method(realm, name, 1) { _, t, args, _ ->
                val ta = validate(t, "%TypedArray%.prototype.$name")
                val len = ta.lengthOrOOB()
                val cb = callable(args.arg(0), name)
                var result: Any? = if (wantIndex) -1.0 else Undefined
                var k = if (fromEnd) len - 1 else 0
                while (if (fromEnd) k >= 0 else k < len) {
                    val v = ta.getIndex(k)
                    if (Ops.toBoolean(cb.call(args.arg(1), arrayOf(v, k.toDouble(), ta)))) {
                        result = if (wantIndex) k.toDouble() else v
                        break
                    }
                    if (fromEnd) k-- else k++
                }
                result
            }
        }
        proto.method(realm, "includes", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.includes")
            val len = ta.lengthOrOOB()
            if (len == 0) return@method false
            val n = Ops.toIntegerOrInfinity(args.arg(1))
            if (n == Double.POSITIVE_INFINITY) return@method false
            var k = if (n >= 0) n.toLong() else maxOf(len + n, 0.0).toLong()
            val search = args.arg(0)
            while (k < len) {
                if (Ops.sameValueZero(search, ta.getIndex(k.toInt()))) return@method true
                k++
            }
            false
        }
        proto.method(realm, "indexOf", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.indexOf")
            val len = ta.lengthOrOOB()
            if (len == 0) return@method -1.0
            val n = Ops.toIntegerOrInfinity(args.arg(1))
            if (n == Double.POSITIVE_INFINITY) return@method -1.0
            var k = if (n >= 0) n.toLong() else maxOf(len + n, 0.0).toLong()
            val search = args.arg(0)
            if (search !is Double && search !is BigInteger) return@method -1.0
            while (k < len) {
                val v = ta.getIndex(k.toInt())
                if (v !== Undefined && Ops.strictEquals(search, v)) return@method k.toDouble()
                k++
            }
            -1.0
        }
        proto.method(realm, "lastIndexOf", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.lastIndexOf")
            val len = ta.lengthOrOOB()
            if (len == 0) return@method -1.0
            val n = if (args.size > 1) Ops.toIntegerOrInfinity(args[1]) else (len - 1).toDouble()
            if (n == Double.NEGATIVE_INFINITY) return@method -1.0
            var k = if (n >= 0) minOf(n, (len - 1).toDouble()).toLong() else (len + n).toLong()
            val search = args.arg(0)
            if (search !is Double && search !is BigInteger) return@method -1.0
            while (k >= 0) {
                val v = ta.getIndex(k.toInt())
                if (v !== Undefined && Ops.strictEquals(search, v)) return@method k.toDouble()
                k--
            }
            -1.0
        }
        proto.method(realm, "join", 1) { _, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.join")
            val len = ta.lengthOrOOB()
            val sep = if (args.arg(0) === Undefined) "," else Ops.toString(args.arg(0))
            val sb = StringBuilder()
            for (k in 0 until len) {
                if (k > 0) sb.append(sep)
                val e = ta.getIndex(k)
                if (e !== Undefined) sb.append(Ops.toString(e))
                if (sb.length > Rope.MAX_LENGTH) rangeErr("Invalid string length")
            }
            sb.toString()
        }
        proto.method(realm, "map", 1) { f, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.map")
            val len = ta.lengthOrOOB()
            val cb = callable(args.arg(0), "map")
            val a = speciesCreate(f.realm, ta, arrayOf(len.toDouble()), true)
            for (k in 0 until len) a.setIndex(k, cb.call(args.arg(1), arrayOf(ta.getIndex(k), k.toDouble(), ta)))
            a
        }
        for ((name, right) in listOf("reduce" to false, "reduceRight" to true)) {
            proto.method(realm, name, 1) { _, t, args, _ ->
                val ta = validate(t, "%TypedArray%.prototype.$name")
                val len = ta.lengthOrOOB()
                val cb = callable(args.arg(0), name)
                if (len == 0 && args.size < 2) typeErr("Reduce of empty array with no initial value")
                var k = if (right) len - 1 else 0
                var acc: Any?
                if (args.size >= 2) acc = args[1]
                else {
                    acc = ta.getIndex(k)
                    if (right) k-- else k++
                }
                while (if (right) k >= 0 else k < len) {
                    acc = cb.call(Undefined, arrayOf(acc, ta.getIndex(k), k.toDouble(), ta))
                    if (right) k-- else k++
                }
                acc
            }
        }
        proto.method(realm, "reverse", 0) { _, t, _, _ ->
            val ta = validate(t, "%TypedArray%.prototype.reverse", true)
            val len = ta.lengthOrOOB()
            val size = ta.type.size
            val data = ta.buffer.data
            var lo = ta.byteOffset
            var hi = ta.byteOffset + ((len - 1) shl ta.type.shift)
            while (lo < hi) {
                for (b in 0 until size) {
                    val tmp = data[lo + b]
                    data[lo + b] = data[hi + b]
                    data[hi + b] = tmp
                }
                lo += size
                hi -= size
            }
            ta
        }
        proto.method(realm, "set", 1) { _, t, args, _ ->
            val target = thisTA(t, "set")
            if (target.buffer.immutable) typeErr("%TypedArray%.prototype.set: typed array is backed by an immutable ArrayBuffer")
            val targetOffset = Ops.toIntegerOrInfinity(args.arg(1))
            if (targetOffset < 0) rangeErr("offset is out of bounds")
            val source = args.arg(0)
            if (source is JSTypedArray) setFromTypedArray(target, targetOffset, source)
            else setFromArrayLike(target, targetOffset, source)
            Undefined
        }
        proto.method(realm, "slice", 2) { f, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.slice")
            val srcLen = ta.lengthOrOOB()
            val start = relative(args.arg(0), srcLen)
            var end = relativeEnd(args.arg(1), srcLen)
            var count = maxOf(end - start, 0)
            val a = speciesCreate(f.realm, ta, arrayOf(count.toDouble()), true)
            if (count > 0) {
                val len = ta.lengthOrOOB()
                if (len < 0) typeErr("%TypedArray%.prototype.slice: typed array is detached or out of bounds")
                end = minOf(end, len)
                count = maxOf(end - start, 0)
                if (ta.type == a.type) {
                    val shift = ta.type.shift
                    val src = ta.buffer.data
                    val dst = a.buffer.data
                    var si = (start shl shift) + ta.byteOffset
                    var di = a.byteOffset
                    val limit = di + (count shl shift)
                    if (src === dst && si < di && di < si + (count shl shift)) {
                        // byte-wise forward copy semantics on overlapping views of one buffer
                        while (di < limit) dst[di++] = src[si++]
                    } else if (limit > di) {
                        System.arraycopy(src, si, dst, di, limit - di)
                    }
                } else {
                    var n = 0
                    for (k in start until end) a.setIndex(n++, ta.getIndex(k))
                }
            }
            a
        }
        proto.method(realm, "sort", 1) { _, t, args, _ ->
            val cmp = args.arg(0)
            if (cmp !== Undefined && !Ops.isCallable(cmp)) typeErr("The comparison function must be either a function or undefined")
            val ta = validate(t, "%TypedArray%.prototype.sort", true)
            val len = ta.lengthOrOOB()
            val sorted = sortedValues(ta, len, cmp)
            for (j in 0 until len) ta.storeIndex(j, sorted[j]!!)
            ta
        }
        proto.method(realm, "subarray", 2) { f, t, args, _ ->
            val ta = thisTA(t, "subarray")
            val srcLen = maxOf(ta.lengthOrOOB(), 0)
            val start = relative(args.arg(0), srcLen)
            val end = args.arg(1)
            val beginByteOffset = ta.byteOffset.toDouble() + (start.toLong() shl ta.type.shift)
            val argList: Array<Any?> = if (ta.fixedLength < 0 && end === Undefined) arrayOf(ta.buffer, beginByteOffset)
            else {
                val e = relativeEnd(end, srcLen)
                arrayOf(ta.buffer, beginByteOffset, maxOf(e - start, 0).toDouble())
            }
            speciesCreate(f.realm, ta, argList)
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val ta = validate(t, "%TypedArray%.prototype.toLocaleString")
            val len = ta.lengthOrOOB()
            val sb = StringBuilder()
            for (k in 0 until len) {
                if (k > 0) sb.append(',')
                val e = ta.getIndex(k)
                if (e !== Undefined) sb.append(Ops.toString(Ops.invoke(e, "toLocaleString", EMPTY_ARGS)))
            }
            sb.toString()
        }
        proto.method(realm, "toReversed", 0) { f, t, _, _ ->
            val ta = validate(t, "%TypedArray%.prototype.toReversed")
            val len = ta.lengthOrOOB()
            val a = createSameType(f.realm, ta, len)
            for (k in 0 until len) a.storeIndex(k, ta.getIndex(len - k - 1))
            a
        }
        proto.method(realm, "toSorted", 1) { f, t, args, _ ->
            val cmp = args.arg(0)
            if (cmp !== Undefined && !Ops.isCallable(cmp)) typeErr("The comparison function must be either a function or undefined")
            val ta = validate(t, "%TypedArray%.prototype.toSorted")
            val len = ta.lengthOrOOB()
            val a = createSameType(f.realm, ta, len)
            val sorted = sortedValues(ta, len, cmp)
            for (j in 0 until len) a.storeIndex(j, sorted[j]!!)
            a
        }
        proto.method(realm, "with", 2) { f, t, args, _ ->
            val ta = validate(t, "%TypedArray%.prototype.with")
            val len = ta.lengthOrOOB()
            val rel = Ops.toIntegerOrInfinity(args.arg(0))
            val actual = if (rel >= 0) rel else len + rel
            val value = ta.type.coerce(args.arg(1))
            if (!(actual >= 0 && actual < ta.lengthOrOOB())) rangeErr("Invalid typed array index")
            val a = createSameType(f.realm, ta, len)
            val ai = actual.toInt()
            // elements beyond a shrunk length read as undefined and are converted again
            for (k in 0 until len) if (k == ai) a.storeIndex(k, value) else a.setIndex(k, ta.getIndex(k))
            a
        }
        proto.defineOwn("toString", realm.arrayPrototype.get("toString"), Attr.WC)
    }

    /** SortIndexedProperties with CompareTypedArrayElements; reads through [[Get]]. */
    private fun sortedValues(ta: JSTypedArray, len: Int, cmp: Any?): Array<Any?> {
        if (cmp === Undefined) {
            // no user code can run: sort the raw numeric values directly
            if (ta.type.isBigInt) {
                val b = Array(len) { ta.getIndex(it) as BigInteger }
                b.sort()
                return arrayOf(*b)
            }
            val d = DoubleArray(len) { ta.getIndex(it) as Double }
            java.util.Arrays.sort(d) // total order: -0 < +0, NaN last
            return Array(len) { d[it] }
        }
        val items = Array<Any?>(len) { ta.getIndex(it) }
        val fn = cmp as JSObject
        return mergeSort(items) { x, y ->
            val v = Ops.toNumber(fn.call(Undefined, arrayOf(x, y)))
            if (v < 0) -1 else if (v > 0) 1 else 0
        }
    }

    /** Stable bottom-up merge sort tolerant of inconsistent comparators. */
    private inline fun mergeSort(items: Array<Any?>, cmp: (Any?, Any?) -> Int): Array<Any?> {
        val n = items.size
        var src = items
        var dst = arrayOfNulls<Any?>(n)
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                var a = lo
                var b = mid
                var o = lo
                while (a < mid && b < hi) {
                    if (cmp(src[b], src[a]) < 0) dst[o++] = src[b++] else dst[o++] = src[a++]
                }
                while (a < mid) dst[o++] = src[a++]
                while (b < hi) dst[o++] = src[b++]
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        return src
    }

    /** SetTypedArrayFromTypedArray */
    private fun setFromTypedArray(target: JSTypedArray, targetOffset: Double, source: JSTypedArray) {
        val targetLength = target.lengthOrOOB()
        if (targetLength < 0) typeErr("%TypedArray%.prototype.set: target is detached or out of bounds")
        val srcLength = source.lengthOrOOB()
        if (srcLength < 0) typeErr("%TypedArray%.prototype.set: source is detached or out of bounds")
        if (targetOffset == Double.POSITIVE_INFINITY || srcLength + targetOffset > targetLength) rangeErr("offset is out of bounds")
        if (target.type.isBigInt != source.type.isBigInt) typeErr("Cannot mix BigInt and other types, use explicit conversions")
        var srcData = source.buffer.data
        var srcIndex = source.byteOffset
        val sameData = source.buffer === target.buffer || (source.buffer.isShared && source.buffer.block === target.buffer.block)
        if (sameData && source.type != target.type) {
            val byteLen = srcLength shl source.type.shift
            srcData = srcData.copyOfRange(srcIndex, srcIndex + byteLen)
            srcIndex = 0
        }
        val targetIndex = (targetOffset.toInt() shl target.type.shift) + target.byteOffset
        copyElements(srcData, srcIndex, source.type, target.buffer.data, targetIndex, target.type, srcLength)
    }

    /** SetTypedArrayFromArrayLike */
    private fun setFromArrayLike(target: JSTypedArray, targetOffset: Double, source: Any?) {
        val targetLength = target.lengthOrOOB()
        if (targetLength < 0) typeErr("%TypedArray%.prototype.set: target is detached or out of bounds")
        val src = Ops.toObject(source)
        val srcLength = Ops.lengthOfArrayLike(src)
        if (targetOffset == Double.POSITIVE_INFINITY || srcLength + targetOffset > targetLength) rangeErr("offset is out of bounds")
        val off = targetOffset.toInt()
        for (k in 0 until srcLength.toInt()) target.setIndex(off + k, src.get(k, src))
    }
}
