package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** node:crypto: hashes and HMACs against known vectors, random numbers, timingSafeEqual. */
class CryptoTest {
    private fun ctx(): NeonContext = NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension()).build().newContext()

    private fun NeonContext.str(code: String): String = eval(code).asString()

    @Test
    fun hashes() {
        ctx().use { c ->
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad|d41d8cd98f00b204e9800998ecf8427e|" +
                    "qUqP5cyxm6YcTAhz05Hph5gvu9M=|ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                c.str("""
                    const crypto = require('crypto');
                    [crypto.createHash('sha256').update('a').update(Buffer.from('bc')).digest('hex'),
                     crypto.createHash('MD5').digest('hex'),
                     crypto.createHash('sha1').update('test').digest('base64'),
                     crypto.hash('SHA-256', 'abc')].join('|')
                """))
            // copy carries the state on; a digest ends a hash
            assertEquals("true,ERR_CRYPTO_HASH_FINALIZED,Digest method not supported,true,true", c.str("""
                const c2 = require('node:crypto');
                const h = c2.createHash('sha512').update('x');
                const copy = h.copy().update('y');
                const r = [copy.digest('hex') === c2.createHash('sha512').update('xy').digest('hex')];
                h.digest();
                try { h.update('z') } catch (e) { r.push(e.code) }
                try { c2.createHash('nope') } catch (e) { r.push(e.message) }
                r.push(Buffer.isBuffer(c2.createHash('sha256').digest()), c2.getHashes().includes('sha256'));
                r.join()
            """))
        }
    }

    @Test
    fun hmacs() {
        ctx().use { c ->
            // the classic quick brown fox, an empty key, a finished HMAC (an empty digest, as in Node), an unknown digest
            assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8|" +
                    "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad||ERR_CRYPTO_INVALID_DIGEST",
                c.str("""
                    const crypto = require('crypto');
                    const r = [crypto.createHmac('sha256', 'key').update('The quick brown fox jumps over the lazy dog').digest('hex'),
                               crypto.createHmac('sha256', '').digest('hex')];
                    const done = crypto.createHmac('sha1', Buffer.from('k'));
                    done.digest();
                    r.push(done.digest('hex'));
                    try { crypto.createHmac('nope', 'k') } catch (e) { r.push(e.code) }
                    r.join('|')
                """))
        }
    }

    @Test
    fun randomness() {
        ctx().use { c ->
            assertEquals("32,true,true,true,ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH,true", c.str("""
                const crypto = require('crypto');
                const r = [crypto.randomBytes(32).length];
                let inRange = true;
                for (let i = 0; i < 200; i++) { const n = crypto.randomInt(3, 7); if (n < 3 || n >= 7) inRange = false }
                r.push(inRange, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(crypto.randomUUID()));
                r.push(crypto.timingSafeEqual(Buffer.from('ab'), Buffer.from('ab')) && !crypto.timingSafeEqual(Buffer.from('ab'), Buffer.from('ac')));
                try { crypto.timingSafeEqual(Buffer.from('a'), Buffer.from('ab')) } catch (e) { r.push(e.code) }
                r.push(crypto.randomFillSync(new Uint32Array(4)).length === 4);
                r.join()
            """))
            // what is not here loads, and says so when used
            assertEquals("function,ERR_UNAVAILABLE", c.str("""
                const { createCipheriv } = require('crypto');
                let code; try { createCipheriv('aes-128-cbc', Buffer.alloc(16), Buffer.alloc(16)) } catch (e) { code = e.code }
                [typeof createCipheriv, code].join()
            """))
        }
    }
}
