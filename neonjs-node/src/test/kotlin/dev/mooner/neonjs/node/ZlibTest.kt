package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** node:zlib's one-shot functions and streams, against what Node's zlib writes and throws. */
class ZlibTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).sandbox(policy).extension(NodeExtension()).build().newContext()

    private fun NeonContext.str(code: String): String = eval(code).asString()

    @Test
    fun theBytesNodeWrites() {
        ctx().use { c ->
            // as Node 26's zlib writes them (gzip's OS byte is Unix's, as on Linux)
            assertEquals(
                "1f8b0800000000000003cb48cdc9c9070086a6103605000000|789ccb48cdc9c90700062c0215|cb48cdc9c90700|" +
                    "1f8b0800000000000203cb48cdc9c9070086a6103605000000|78bb062c0215cb001100062c0215|907060870|222957957",
                c.str("""
                    const z = require('zlib');
                    [z.gzipSync('hello').toString('hex'), z.deflateSync('hello').toString('hex'), z.deflateRawSync('hello').toString('hex'),
                     z.gzipSync('hello', { level: 9 }).toString('hex'), z.deflateSync('hello', { dictionary: Buffer.from('hello') }).toString('hex'),
                     z.crc32('hello'), z.crc32('world', z.crc32('hello '))].join('|')
                """))
        }
    }

    @Test
    fun roundTripsAndErrors() {
        ctx().use { c ->
            assertEquals("ok,ok,ok,ok,abcd,hello", c.str("""
                const z = require('node:zlib');
                const data = Buffer.from('x'.repeat(5000) + 'y'.repeat(3000));
                const r = [[z.deflateSync, z.inflateSync], [z.gzipSync, z.gunzipSync], [z.deflateRawSync, z.inflateRawSync], [z.gzipSync, z.unzipSync]]
                    .map(([pack, unpack]) => (unpack(pack(data)).equals(data) ? 'ok' : 'differs'));
                // every member of a gzip, and a dictionary
                r.push(z.gunzipSync(Buffer.concat([z.gzipSync('ab'), z.gzipSync('cd')])).toString());
                const dict = Buffer.from('hello');
                r.push(z.inflateSync(z.deflateSync('hello', { dictionary: dict }), { dictionary: dict }).toString());
                r.join()
            """))
            assertEquals(
                "Z_BUF_ERROR:-5:unexpected end of file|Z_DATA_ERROR:-3:incorrect header check|" +
                    "ERR_BUFFER_TOO_LARGE:RangeError:Cannot create a Buffer larger than 100 bytes|Z_NEED_DICT:2:Missing dictionary",
                c.str("""
                    const zz = require('zlib'), errs = [];
                    try { zz.inflateSync(zz.deflateSync('hello').subarray(0, 5)) } catch (e) { errs.push(e.code + ':' + e.errno + ':' + e.message) }
                    try { zz.inflateSync(Buffer.from('nonsense')) } catch (e) { errs.push(e.code + ':' + e.errno + ':' + e.message) }
                    try { zz.inflateSync(zz.deflateSync(Buffer.alloc(1000)), { maxOutputLength: 100 }) } catch (e) { errs.push(e.code + ':' + e.name + ':' + e.message) }
                    try { zz.inflateSync(zz.deflateSync('hello', { dictionary: Buffer.from('hello') })) } catch (e) { errs.push(e.code + ':' + e.errno + ':' + e.message) }
                    errs.join('|')
                """))
            // the callback form, and promisify over it
            val r = c.eval("""
                (async () => {
                    const z = require('zlib'), { promisify } = require('util');
                    const packed = await promisify(z.gzip)('async');
                    return new Promise((resolve) => z.gunzip(packed, (err, out) => resolve(String(err) + ':' + out)));
                })()
            """).await(5_000).asString()
            assertEquals("null:async", r)
        }
    }

    @Test
    fun aBombStopsAtTheLimits() {
        // 64 MB of zeros packs into some 64 KB (made where nothing limits it); unpacked against a 16 MB allocation
        // limit, it stops there
        val packed = ctx().use { c -> c.str("require('zlib').deflateSync(Buffer.alloc(64 * 1024 * 1024)).toString('base64')") }
        assertTrue(packed.length < 150_000, "${packed.length}")
        ctx(SandboxPolicy.builder().maxAllocatedBytes(16L shl 20).build()).use { c ->
            c.eval("var packed = Buffer.from('$packed', 'base64')")
            assertThrows(dev.mooner.neonjs.NeonResourceLimitException::class.java) { c.eval("require('zlib').inflateSync(packed)") }
        }
        ctx().use { c ->
            assertEquals("ERR_BUFFER_TOO_LARGE", c.str("""
                const z = require('zlib');
                try { z.inflateSync(z.deflateSync(Buffer.alloc(1 << 20)), { maxOutputLength: 1 << 16 }) } catch (e) { e.code }
            """))
        }
    }

    @Test
    fun streams() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const zlib = require('zlib');
                    const { pipeline, Readable } = require('stream');
                    const { buffer } = require('stream/consumers');
                    const text = 'stream me '.repeat(5000);
                    const out = [];
                    // gzip piece by piece, then gunzip a byte at a time (headers and trailers across writes)
                    const gz = await buffer(Readable.from([text.slice(0, 7), text.slice(7, 30000), text.slice(30000)]).pipe(zlib.createGzip()));
                    out.push(gz[0] === 0x1f && gz[1] === 0x8b, zlib.gunzipSync(gz).toString() === text);
                    const bytes = [...gz].map((b) => Buffer.from([b]));
                    const back = await buffer(Readable.from(bytes).pipe(zlib.createGunzip()));
                    out.push(back.toString() === text);
                    // two members, and unzip telling gzip from zlib
                    const two = Buffer.concat([zlib.gzipSync('one,'), zlib.gzipSync('two')]);
                    out.push(zlib.gunzipSync(two).toString(), zlib.unzipSync(zlib.deflateSync('zlib')).toString());
                    // a flush gives what was written so far
                    const d = zlib.createDeflateRaw();
                    const got = [];
                    d.on('data', (chunk) => got.push(chunk));
                    d.write('flushed');
                    await new Promise((resolve) => d.flush(zlib.constants.Z_SYNC_FLUSH, resolve));
                    out.push(zlib.inflateRawSync(Buffer.concat(got), { finishFlush: zlib.constants.Z_SYNC_FLUSH }).toString());
                    // the callback form goes through a stream; errors come as Node's
                    out.push(await new Promise((resolve) => zlib.inflate(Buffer.from('not zlib'), (e) => resolve(e.code + ':' + e.errno))));
                    out.push(await new Promise((resolve) => zlib.gunzip(gz.subarray(0, 40), (e) => resolve(e.message))));
                    out.push(typeof zlib.createBrotliCompress, (() => { try { zlib.createBrotliCompress() } catch (e) { return e.code } })());
                    return out.join();
                })()
            """).await(20_000).asString()
            assertEquals("true,true,true,one,two,zlib,flushed,Z_DATA_ERROR:-3,unexpected end of file,function,ERR_UNAVAILABLE", r)
        }
    }
}
