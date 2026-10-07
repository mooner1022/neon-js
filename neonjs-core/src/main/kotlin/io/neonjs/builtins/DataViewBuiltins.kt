package io.neonjs.builtins

import io.neonjs.runtime.*

/** DataView instance. */
class JSDataView internal constructor(
    proto: JSObject?,
    @JvmField val buffer: JSArrayBuffer,
    @JvmField val byteOffset: Int,
    /** [[ByteLength]], or -1 (auto) for length-tracking views of resizable buffers. */
    @JvmField val fixedByteLength: Int,
) : JSObject(proto) {
    /** GetViewByteLength, or -1 when IsViewOutOfBounds (which includes a detached buffer). */
    fun byteLengthOrOOB(): Int {
        val b = buffer
        if (b.detached) return -1
        val bufLen = b.byteLength()
        if (byteOffset > bufLen) return -1
        val n = fixedByteLength
        if (n < 0) return bufLen - byteOffset
        return if (byteOffset.toLong() + n > bufLen) -1 else n
    }
}

internal object DataViewBuiltins {
    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%DataView.prototype%"] = proto
        val ctor = makeCtor(realm, "DataView", 1, proto) { _, _, args, nt ->
            if (nt == null) typeErr("Constructor DataView requires 'new'")
            val buffer = args.arg(0) as? JSArrayBuffer ?: typeErr("First argument to DataView constructor must be an ArrayBuffer")
            val offset = Ops.toIndex(args.arg(1))
            if (buffer.detached) typeErr("Cannot construct a DataView on a detached ArrayBuffer")
            var bufLen = buffer.byteLength().toLong()
            if (offset > bufLen) rangeErr("Start offset $offset is outside the bounds of the buffer")
            val lengthArg = args.arg(2)
            val viewByteLength: Long = if (lengthArg === Undefined) {
                if (buffer.isFixedLength) bufLen - offset else -1L
            } else {
                val l = Ops.toIndex(lengthArg)
                if (offset + l > bufLen) rangeErr("Invalid DataView length $l")
                l
            }
            val p = Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%DataView.prototype%") }
            if (buffer.detached) typeErr("Cannot construct a DataView on a detached ArrayBuffer")
            bufLen = buffer.byteLength().toLong()
            if (offset > bufLen) rangeErr("Start offset $offset is outside the bounds of the buffer")
            if (lengthArg !== Undefined && offset + viewByteLength > bufLen) rangeErr("Invalid DataView length $viewByteLength")
            JSDataView(p, buffer, offset.toInt(), viewByteLength.toInt())
        }
        realm.intrinsics["%DataView%"] = ctor
        realm.global("DataView", ctor)

        fun thisView(t: Any?, name: String): JSDataView =
            t as? JSDataView ?: typeErr("Method DataView.prototype.$name called on incompatible receiver ${Ops.describe(t)}")

        proto.getter(realm, "buffer") { _, t, _, _ -> thisView(t, "buffer").buffer }
        proto.getter(realm, "byteLength") { _, t, _, _ ->
            val n = thisView(t, "byteLength").byteLengthOrOOB()
            if (n < 0) typeErr("DataView is detached or out of bounds")
            n.toDouble()
        }
        proto.getter(realm, "byteOffset") { _, t, _, _ ->
            val v = thisView(t, "byteOffset")
            if (v.byteLengthOrOOB() < 0) typeErr("DataView is detached or out of bounds")
            v.byteOffset.toDouble()
        }

        for (type in ElementType.entries) {
            if (type == ElementType.UINT8C) continue
            val name = type.jsName
            proto.method(realm, "get$name", 1) { _, t, args, _ ->
                val view = thisView(t, "get$name")
                val index = Ops.toIndex(args.arg(0))
                val le = Ops.toBoolean(args.arg(1))
                val at = checkAccess(view, index, type)
                BufferOps.load(view.buffer.data, at, type, le)
            }
            proto.method(realm, "set$name", 2) { _, t, args, _ ->
                val view = thisView(t, "set$name")
                if (view.buffer.immutable) typeErr("DataView.prototype.set$name: the DataView is backed by an immutable ArrayBuffer")
                val index = Ops.toIndex(args.arg(0))
                val value = type.coerce(args.arg(1))
                val le = Ops.toBoolean(args.arg(2))
                val at = checkAccess(view, index, type)
                BufferOps.store(view.buffer.data, at, type, value, le)
                Undefined
            }
        }
        proto.value(JSSymbol.toStringTag, "DataView", Attr.CONFIGURABLE)
    }

    /** Bounds checks of GetViewValue / SetViewValue; returns the buffer byte index. */
    private fun checkAccess(view: JSDataView, index: Long, type: ElementType): Int {
        val viewSize = view.byteLengthOrOOB()
        if (viewSize < 0) typeErr("DataView is detached or out of bounds")
        if (index + type.size > viewSize) rangeErr("Offset is outside the bounds of the DataView")
        return (index + view.byteOffset).toInt()
    }
}
