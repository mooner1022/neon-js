package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ElementType
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `crypto.getRandomValues` and `crypto.randomUUID` (Web Cryptography). The bytes come from a [java.security.SecureRandom],
 * or from the sandbox's seeded random source when the policy is deterministic. No `crypto.subtle` yet.
 */
internal object Crypto {
    class JSCrypto(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "Crypto"
    }

    private val secure by lazy { java.security.SecureRandom() }

    private fun randomBytes(realm: Realm, out: ByteArray) {
        val r = realm.agent.randomSource
        if (r != null) r.nextBytes(out) else secure.nextBytes(out)
    }

    private val INTEGER_TYPES = setOf(
        ElementType.INT8, ElementType.UINT8, ElementType.UINT8C, ElementType.INT16, ElementType.UINT16, ElementType.INT32,
        ElementType.UINT32, ElementType.BIGINT64, ElementType.BIGUINT64,
    )

    fun install(realm: Realm) {
        val i = WebInterface.define(realm, "Crypto", 0)
        fun crypto(t: Any?, m: String) = Idl.self<JSCrypto>(t, "Crypto", m)
        i.operation("getRandomValues", 1) { f, t, a, _ ->
            crypto(t, "getRandomValues")
            Idl.required(a, 1, "Crypto.getRandomValues")
            val array = a[0]
            if (array is JSDataView) throw WebGlobals.domException(f.realm, "getRandomValues takes an integer typed array", "TypeMismatchError")
            if (array !is JSTypedArray) typeErr("Crypto.getRandomValues: the argument is not an ArrayBufferView")
            if (array.type !in INTEGER_TYPES) throw WebGlobals.domException(f.realm, "getRandomValues takes an integer typed array", "TypeMismatchError")
            val n = maxOf(array.lengthOrOOB(), 0) * array.type.size
            if (n > 65536) throw JSException(WebGlobals.newQuotaExceededError(f.realm, "getRandomValues takes at most 65536 bytes"))
            val bytes = ByteArray(n)
            randomBytes(f.realm, bytes)
            System.arraycopy(bytes, 0, array.buffer.data, array.byteOffset, n)
            array
        }
        i.operation("randomUUID", 0) { f, t, _, _ ->
            crypto(t, "randomUUID")
            val b = ByteArray(16)
            randomBytes(f.realm, b)
            b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = StringBuilder(36)
            for (k in 0 until 16) {
                if (k == 4 || k == 6 || k == 8 || k == 10) hex.append('-')
                hex.append(Character.forDigit(b[k].toInt() shr 4 and 0xF, 16)).append(Character.forDigit(b[k].toInt() and 0xF, 16))
            }
            hex.toString()
        }
        val crypto = JSCrypto(i.proto)
        // [SameObject] readonly attribute of the global object
        val get = NativeFunction(realm, "crypto", 0, { _, _, _, _ -> crypto }, namePrefix = "get")
        realm.globalObject.defineAccessor("crypto", get, Undefined, Attr.ENUMERABLE or Attr.CONFIGURABLE)
    }
}
