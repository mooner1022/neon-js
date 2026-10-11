package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The native side of node:crypto's hashes (`binding.crypto`): message digests and HMACs of the platform's providers
 * (java.security), which node:crypto's Hash and Hmac feed and read synchronously, as Node's do. The bytes go in and
 * out through views the JS side made.
 */
internal object CryptoBinding {
    /** Node's (OpenSSL's) digest names, lower-cased, to the platform's. */
    private val DIGESTS = linkedMapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha224" to "SHA-224",
        "sha256" to "SHA-256",
        "sha384" to "SHA-384",
        "sha512" to "SHA-512",
        "sha512-224" to "SHA-512/224",
        "sha512-256" to "SHA-512/256",
        "sha3-224" to "SHA3-224",
        "sha3-256" to "SHA3-256",
        "sha3-384" to "SHA3-384",
        "sha3-512" to "SHA3-512",
    )

    /** OpenSSL's other names of these. */
    private val ALIASES = mapOf(
        "sha-1" to "sha1", "sha-224" to "sha224", "sha-256" to "sha256", "sha-384" to "sha384", "sha-512" to "sha512",
        "sha2-224" to "sha224", "sha2-256" to "sha256", "sha2-384" to "sha384", "sha2-512" to "sha512",
        "sha-512/224" to "sha512-224", "sha-512/256" to "sha512-256", "sha2-512/224" to "sha512-224",
        "sha2-512/256" to "sha512-256", "rsa-md5" to "md5", "rsa-sha1" to "sha1", "rsa-sha224" to "sha224",
        "rsa-sha256" to "sha256", "rsa-sha384" to "sha384", "rsa-sha512" to "sha512", "rsa-sha512/224" to "sha512-224",
        "rsa-sha512/256" to "sha512-256", "rsa-sha3-224" to "sha3-224", "rsa-sha3-256" to "sha3-256",
        "rsa-sha3-384" to "sha3-384", "rsa-sha3-512" to "sha3-512",
    )

    /** A digest or HMAC in progress. */
    class HashHandle(@JvmField val md: MessageDigest?, @JvmField val mac: Mac?) : JSObject(null) {
        val length: Int get() = md?.digestLength ?: mac!!.macLength
    }

    private fun canonical(name: String): String? {
        val n = name.lowercase()
        val c = ALIASES[n] ?: n
        return if (DIGESTS.containsKey(c)) c else null
    }

    private fun digest(name: String): MessageDigest? = canonical(name)?.let {
        try {
            MessageDigest.getInstance(DIGESTS[it])
        } catch (_: Exception) {
            null
        }
    }

    private fun macName(canonical: String): String = "Hmac" + DIGESTS[canonical]!!.replace("-", "").replace("SHA3", "SHA3-")

    private fun mac(name: String, key: ByteArray): Mac? {
        val c = canonical(name) ?: return null
        return try {
            val algorithm = macName(c)
            // an empty key is a key of no bytes, which SecretKeySpec refuses
            Mac.getInstance(algorithm).apply { init(if (key.isEmpty()) EmptyKey(algorithm) else SecretKeySpec(key, algorithm)) }
        } catch (_: Exception) {
            null
        }
    }

    /** A key of no bytes, which HMAC allows and SecretKeySpec does not. */
    private class EmptyKey(private val algorithm: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = algorithm
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun bytes(v: Any?): Triple<ByteArray, Int, Int> = when (v) {
        is JSTypedArray -> Triple(v.buffer.data, v.byteOffset, v.byteLengthOrZero())
        is JSDataView -> Triple(v.buffer.data, v.byteOffset, maxOf(v.byteLengthOrOOB(), 0))
        else -> throw JSException.typeError("an ArrayBufferView was expected")
    }

    fun create(realm: Realm): JSObject {
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        // the digests this platform has, by Node's names
        fn("hashes", 0) { f, _, _, _ ->
            val names = (DIGESTS.keys + ALIASES.keys).filter { digest(it) != null }.sorted()
            JSArray.of(f.realm.arrayPrototype, names.map { it as Any? }.toTypedArray())
        }
        fn("hashNew", 1) { _, _, a, _ -> digest(Ops.toString(a.arg(0)))?.let { HashHandle(it, null) } ?: Null }
        fn("hmacNew", 2) { _, _, a, _ ->
            val (data, off, len) = bytes(a.arg(1))
            mac(Ops.toString(a.arg(0)), data.copyOfRange(off, off + len))?.let { HashHandle(null, it) } ?: Null
        }
        fn("update", 2) { _, _, a, _ ->
            val h = a.arg(0) as HashHandle
            val (data, off, len) = bytes(a.arg(1))
            h.md?.update(data, off, len) ?: h.mac!!.update(data, off, len)
            Undefined
        }
        fn("length", 1) { _, _, a, _ -> (a.arg(0) as HashHandle).length.toDouble() }
        // writes the result into the view (of the handle's length) and ends the handle
        fn("digest", 2) { _, _, a, _ ->
            val h = a.arg(0) as HashHandle
            val out = h.md?.digest() ?: h.mac!!.doFinal()
            val t = a.arg(1) as JSTypedArray
            System.arraycopy(out, 0, t.buffer.data, t.byteOffset, minOf(out.size, t.byteLengthOrZero()))
            Undefined
        }
        fn("copy", 1) { _, _, a, _ ->
            val h = a.arg(0) as HashHandle
            HashHandle(h.md?.clone() as MessageDigest?, h.mac?.clone() as Mac?)
        }
        return b
    }
}
