package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSDate
import dev.mooner.neonjs.builtins.JSMapObject
import dev.mooner.neonjs.builtins.JSRegExp
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.TypedArrayBuiltins
import dev.mooner.neonjs.runtime.*
import java.util.IdentityHashMap

/**
 * `structuredClone(value, { transfer })` (HTML StructuredSerializeWithTransfer + StructuredDeserialize within one
 * realm). Clones primitives (not symbols), plain objects and arrays (own enumerable string-keyed properties, read
 * with `[[Get]]`), Boolean / Number / BigInt / String wrappers, Date, RegExp, Map, Set, ArrayBuffer (transferable),
 * SharedArrayBuffer (shared), typed arrays, DataView, Error objects and DOMExceptions, preserving shared references
 * and cycles. Anything else (functions, symbols, proxies, promises, weak collections, host objects…) is a
 * DataCloneError.
 */
internal class StructuredClone(private val realm: Realm) {
    private val memory = IdentityHashMap<Any, Any>()
    private var depth = 0
    private var work = 0

    fun run(value: Any?, transfer: List<Any?>): Any? {
        val transferred = ArrayList<JSArrayBuffer>()
        for (t in transfer) {
            val b = t as? JSArrayBuffer
            if (b == null || b.isShared) throw dataCloneError("Value at index ${transferred.size} of the transfer list is not transferable")
            if (transferred.any { it === b }) throw dataCloneError("ArrayBuffer at index ${transferred.size} is a duplicate in the transfer list")
            if (b.detached) throw dataCloneError("An ArrayBuffer in the transfer list is detached")
            if (b.immutable) throw dataCloneError("An immutable ArrayBuffer cannot be transferred")
            transferred.add(b)
        }
        // transferred buffers are re-created over the same bytes; the originals are detached once cloning succeeded
        for (b in transferred) {
            val copy = if (b.isFixedLength) JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), b.data, b.byteLength(), -1, null)
            else JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), b.data, b.byteLength(), b.maxByteLength, null)
            memory[b] = copy
        }
        val result = clone(value)
        for (b in transferred) b.detach()
        return result
    }

    private fun dataCloneError(message: String): JSException = WebGlobals.domException(realm, message, "DataCloneError")

    private fun tick() {
        if (++work and 1023 == 0) realm.agent.checkInterrupt()
    }

    private fun clone(v: Any?): Any? {
        when (v) {
            null, Undefined, Null, is Boolean, is Double, is CharSequence, is java.math.BigInteger -> return v
            is JSSymbol -> throw dataCloneError("Symbol() could not be cloned")
            !is JSObject -> throw dataCloneError("${Ops.typeOf(v)} could not be cloned")
        }
        memory[v]?.let { return it }
        if (++depth > 5000) throw JSException.rangeError("Maximum call stack size exceeded")
        try {
            tick()
            return cloneObject(v)
        } finally {
            depth--
        }
    }

    private fun proto(name: String) = realm.intrinsic(name)

    private fun cloneObject(v: JSObject): Any? {
        when (v) {
            is JSPrimitiveWrapper -> {
                val p = v.primitive
                if (p is JSSymbol) throw dataCloneError("Symbol object could not be cloned")
                val r = Ops.toObject(realm, p)
                memory[v] = r
                return r
            }
            is JSStringObject -> return Ops.toObject(realm, v.value).also { memory[v] = it }
            is JSDate -> return JSDate(proto("%Date.prototype%"), v.timeValue).also { memory[v] = it }
            is JSRegExp -> return Ops.construct(realm.intrinsic("%RegExp%"), arrayOf(v.originalSource, v.originalFlags)).also { memory[v] = it }
            is JSArrayBuffer -> return cloneBuffer(v)
            is JSTypedArray -> {
                if (v.isOutOfBounds()) throw dataCloneError("An out-of-bounds typed array could not be cloned")
                val buf = clone(v.buffer) as JSArrayBuffer
                val r = JSTypedArray(TypedArrayBuiltins.protoOf(realm, v.type), v.type, buf, v.byteOffset, v.fixedLength)
                memory[v] = r
                return r
            }
            is JSDataView -> {
                if (v.byteLengthOrOOB() < 0) throw dataCloneError("An out-of-bounds DataView could not be cloned")
                val buf = clone(v.buffer) as JSArrayBuffer
                val r = JSDataView(proto("%DataView.prototype%"), buf, v.byteOffset, v.fixedByteLength)
                memory[v] = r
                return r
            }
            is JSMapObject -> {
                val r = JSMapObject(proto(if (v.isSet) "%Set.prototype%" else "%Map.prototype%"), v.isSet)
                memory[v] = r
                // the entries are copied first: serializing them may run getters that change the original
                val keys = ArrayList<Any?>()
                val values = ArrayList<Any?>()
                v.table.forEachLive { k, x -> keys.add(k); values.add(x) }
                for (i in keys.indices) {
                    val k = clone(keys[i])
                    r.table.set(k, if (v.isSet) k else clone(values[i]))
                }
                return r
            }
            is WebGlobals.JSDOMException -> {
                val r = WebGlobals.newDOMException(realm, v.excMessage, v.excName)
                memory[v] = r
                return r
            }
            is JSErrorObject -> return cloneError(v)
            is JSArray -> {
                val r = JSArray(realm.arrayPrototype)
                r.setOrThrow("length", v.length.toDouble())
                memory[v] = r
                copyProperties(v, r)
                return r
            }
        }
        // ordinary objects only: no exotic behaviour, no internal slots beyond [[Prototype]] / [[Extensible]]
        if (v.javaClass != JSObject::class.java) throw dataCloneError("${Ops.describe(v)} could not be cloned")
        val r = JSObject(realm.objectPrototype)
        memory[v] = r
        copyProperties(v, r)
        return r
    }

    private fun copyProperties(from: JSObject, to: JSObject) {
        for (k in from.ownPropertyKeys()) {
            if (k is JSSymbol) continue
            val d = from.getOwnProperty(k) ?: continue
            if (!d.enumerable) continue
            val x = clone(from.get(k, from))
            to.createDataProperty(k, x)
        }
    }

    private fun cloneBuffer(b: JSArrayBuffer): JSArrayBuffer {
        val r = when {
            b.isShared -> JSArrayBuffer.wrapShared(realm, b.block!!)
            b.detached -> throw dataCloneError("A detached ArrayBuffer could not be cloned")
            else -> {
                val len = b.byteLength()
                val copy = ArrayBufferBuiltins.allocate(realm, null, len.toLong(), if (b.isFixedLength) -1L else b.maxByteLength.toLong())
                System.arraycopy(b.data, 0, copy.data, 0, len)
                copy.immutable = b.immutable
                copy
            }
        }
        memory[b] = r
        return r
    }

    private fun cloneError(e: JSErrorObject): JSObject {
        val nameValue = e.get("name", e)
        val name = if (nameValue === Undefined) "Error" else Ops.toString(nameValue)
        val kind = ErrorKind.entries.firstOrNull { it.jsName == name && it != ErrorKind.AGGREGATE && it != ErrorKind.SUPPRESSED } ?: ErrorKind.ERROR
        val hasMessage = e.getOwnProperty("message")?.let { !it.isAccessor } == true
        val message = if (hasMessage) Ops.toString(e.get("message", e)) else ""
        val r = realm.newError(kind, message)
        if (!hasMessage) r.delete("message")
        r.stackTrace = e.stackTrace
        memory[e] = r
        return r
    }

    companion object {
        /** Reads `options.transfer` (an iterable) for structuredClone. */
        fun transferList(realm: Realm, options: Any?): List<Any?> {
            if (options === Undefined || options === Null) return emptyList()
            val o = options as? JSObject ?: throw JSException.typeError("structuredClone options must be an object")
            val t = o.get("transfer", o)
            if (t === Undefined) return emptyList()
            val out = ArrayList<Any?>()
            val rec = dev.mooner.neonjs.vm.Iteration.getIterator(realm, t, false)
            while (true) {
                val next = dev.mooner.neonjs.vm.Iteration.stepValue(rec)
                if (next === NotFound) break
                out.add(next)
                if (out.size > 100_000) throw JSException.rangeError("structuredClone: transfer list too long")
            }
            return out
        }
    }
}
