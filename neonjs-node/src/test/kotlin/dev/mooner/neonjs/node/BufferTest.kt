package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.NeonResourceLimitException
import dev.mooner.neonjs.NeonTimeoutException
import dev.mooner.neonjs.SandboxPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** node:buffer and node:string_decoder. */
class BufferTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).sandbox(policy).extension(NodeExtension()).build().newContext()

    private fun NeonContext.str(code: String): String = eval(code).asString()

    @Test
    fun encodings() {
        ctx().use { c ->
            // utf8: a lone surrogate is EF BF BD; no partial character when the room runs out
            assertEquals("efbfbd", c.str("Buffer.from('\\uD800').toString('hex')"))
            assertEquals("0,2", c.str("const b2 = Buffer.alloc(1); [b2.write('é', 0, 1), Buffer.alloc(2).write('é')].join()"))
            assertEquals("f09f9880", c.str("Buffer.from('😀').toString('hex')"))
            assertEquals("�A", c.str("Buffer.from([0xE2, 0x41]).toString()"))
            // hex stops at the first pair that is not hex; an odd last digit is dropped
            assertEquals("0,1,ab", c.str("[Buffer.from('zzab', 'hex').length, Buffer.from('abzz', 'hex').length, Buffer.from('abc', 'hex').toString('hex')].join()"))
            // base64 skips whitespace, reads both alphabets and ends at '='
            assertEquals("hello,hello,hello?", c.str("""
                [Buffer.from('aGVs bG8=', 'base64').toString(), Buffer.from('aGVsbG8', 'base64url').toString(),
                 Buffer.from('aGVsbG8_', 'base64').toString('latin1').replace(/[^a-z]/g, '?')].join()
            """))
            assertEquals("aGk/Pw==,aGk_Pw", c.str("[Buffer.from('hi??').toString('base64'), Buffer.from('hi??').toString('base64url')].join()"))
            // latin1 keeps the low byte, ascii reads 7 bits, utf16le two bytes a unit
            assertEquals("ac,1,61006200", c.str("[Buffer.from('\\u01ac', 'latin1').toString('hex'), Buffer.from([0x81]).toString('ascii').charCodeAt(0), Buffer.from('ab', 'ucs2').toString('hex')].join()"))
            assertEquals("5,2,5,3", c.str("[Buffer.byteLength('hello'), Buffer.byteLength('é'), Buffer.byteLength('aGVsbG8=', 'base64'), Buffer.byteLength('abcdef', 'hex')].join()"))
            assertEquals("true,false,TypeError:ERR_UNKNOWN_ENCODING", c.str("""
                const r = [Buffer.isEncoding('UTF-8'), Buffer.isEncoding('utf9')];
                try { Buffer.from('x', 'utf9') } catch (e) { r.push(e.name + ':' + e.code) }
                r.join()
            """))
        }
    }

    @Test
    fun creatingAndSharing() {
        ctx().use { c ->
            assertEquals("true,true,true,true", c.str("""
                const b = Buffer.from('hello');
                [b instanceof Uint8Array, b instanceof Buffer, Buffer.isBuffer(b), b.constructor === Buffer].join()
            """))
            // slice and subarray share memory, Buffer.from(buffer) copies, Buffer.from(arrayBuffer) shares
            assertEquals("Jello,hello,Hello", c.str("""
                const src = Buffer.from('hello');
                const view = src.slice(0, 5), copy = Buffer.from(src);
                const ab = new ArrayBuffer(5), shared = Buffer.from(ab);
                view[0] = 0x4a; shared.write('Hello');
                [src.toString(), copy.toString(), Buffer.from(ab).toString()].join()
            """))
            assertEquals("abcdef,6,abc\u0000\u0000", c.str("""
                const parts = [Buffer.from('ab'), Buffer.from('cd'), new Uint8Array([0x65, 0x66])];
                [Buffer.concat(parts).toString(), Buffer.concat(parts).length, Buffer.concat([Buffer.from('abc')], 5).toString()].join()
            """))
            assertEquals("ababa,00000000,7a7a7a", c.str("[Buffer.alloc(5, 'ab').toString(), Buffer.allocUnsafe(4).toString('hex'), Buffer.alloc(3).fill(0x7a).toString('hex')].join()"))
            assertEquals("ERR_INVALID_ARG_VALUE,ERR_OUT_OF_RANGE,ERR_INVALID_ARG_TYPE", c.str("""
                const codes = [];
                for (const f of [() => Buffer.alloc(4).fill('zz', 'hex'), () => Buffer.alloc(-1), () => Buffer.from(5)]) {
                    try { f() } catch (e) { codes.push(e.code) }
                }
                codes.join()
            """))
            assertEquals("{\"type\":\"Buffer\",\"data\":[1,2]},3,5", c.str("""
                const j = JSON.stringify(Buffer.from([1, 2]));
                [j, Buffer.from(JSON.parse(j)).length + 1, Buffer.of(1, 2, 3, 4, 5).length].join()
            """))
            assertEquals("<Buffer 68 65 6c 6c 6f>", c.str("require('util').inspect(Buffer.from('hello'))"))
        }
        // allocation is the engine's, within its limits
        ctx().use { c -> assertEquals("RangeError", c.str("try { Buffer.alloc(2 ** 31) } catch (e) { e.name }")) }
        ctx(SandboxPolicy.builder().maxAllocatedBytes(16L shl 20).build()).use { c ->
            assertThrows<NeonResourceLimitException> { c.eval("Buffer.alloc(64 * 1024 * 1024)") }
        }
    }

    @Test
    fun searchingAndComparing() {
        ctx().use { c ->
            assertEquals("2,7,-1,7,2,true,1,4,0", c.str("""
                const b = Buffer.from('abcdeabcde');
                [b.indexOf('c'), b.indexOf('c', 3), b.indexOf('x'), b.lastIndexOf('c'), b.lastIndexOf('c', -4), b.includes(Buffer.from('dea')),
                 b.indexOf(98), b.indexOf('', 4), b.indexOf('')].join()
            """))
            assertEquals("-1,0,1,true,false", c.str("""
                const a = Buffer.from([1, 2, 3]), z = Buffer.from([1, 2, 4]);
                [Buffer.compare(a, z), a.compare(Buffer.from([1, 2, 3])), z.compare(a), a.equals(Buffer.from([1, 2, 3])), a.equals(z)].join()
            """))
            assertEquals("2,abab", c.str("const t = Buffer.from('xxxx'); [Buffer.from('ab').copy(t, 2), (Buffer.from('ab').copy(t), t.toString())].join()"))
            // overlapping copy within one buffer moves as memmove does
            assertEquals("aabcd", c.str("const m = Buffer.from('abcde'); m.copy(m, 1, 0, 4); m.toString()"))
        }
        // a search as slow as haystack times needle still stops at the time limit
        ctx(SandboxPolicy.builder().maxExecutionTime(300).build()).use { c ->
            val start = System.nanoTime()
            assertThrows<NeonTimeoutException> {
                c.eval("const hay = Buffer.alloc(4_000_000, 'a'), needle = Buffer.alloc(200_000, 'a'); needle[199_999] = 0x62; hay.indexOf(needle)")
            }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        }
    }

    @Test
    fun numbers() {
        ctx().use { c ->
            assertEquals("258,513,-2,4294967295,-1", c.str("""
                const b = Buffer.from([1, 2, 0xfe, 0xff, 0xff, 0xff, 0xff]);
                [b.readUInt16BE(0), b.readUInt16LE(0), b.readInt8(2), b.readUInt32LE(3), b.readInt32BE(3)].join()
            """))
            assertEquals("-1,281474976710655,1311768467294899695,-2", c.str("""
                const s = Buffer.alloc(8);
                s.writeIntBE(-1, 0, 6);
                const r = [s.readIntBE(0, 6), s.readUIntLE(0, 6)];
                s.writeBigUInt64LE(0x1234567890abcdefn); r.push(s.readBigUInt64LE());
                s.writeBigInt64BE(-2n); r.push(s.readBigInt64BE());
                r.join()
            """))
            assertEquals("3.140000104904175,-1.5,13,true", c.str("""
                const f = Buffer.alloc(12);
                f.writeFloatLE(3.14, 0);
                const end = f.writeDoubleBE(-1.5, 4);
                [f.readFloatLE(0), f.readDoubleBE(4), end + 1, f.readUint8 === f.readUInt8].join()
            """))
            assertEquals(
                "The value of \"offset\" is out of range. It must be >= 0 and <= 2. Received 3|" +
                    "The value of \"value\" is out of range. It must be >= 0 and <= 255. Received 256",
                c.str("""
                    const errs = [];
                    try { Buffer.alloc(4).readUInt16LE(3) } catch (e) { errs.push(e.message) }
                    try { Buffer.alloc(1).writeUInt8(256) } catch (e) { errs.push(e.message) }
                    errs.join('|')
                """))
            assertEquals("0201,04030201,0807060504030201", c.str("""
                [Buffer.from([1, 2]).swap16().toString('hex'), Buffer.from([1, 2, 3, 4]).swap32().toString('hex'),
                 Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]).swap64().toString('hex')].join()
            """))
        }
    }

    @Test
    fun theGlobalIsLazy() {
        ctx().use { c ->
            assertEquals("function,true,true", c.str("""
                const before = Object.getOwnPropertyDescriptor(globalThis, 'Buffer').get !== undefined;
                [typeof Buffer, before, Buffer === require('node:buffer').Buffer].join()
            """))
            assertEquals("42", c.str("globalThis.Buffer = 42; String(Buffer)"))
        }
    }

    @Test
    fun stringDecoder() {
        ctx().use { c ->
            assertEquals("|€|,|😀|,�", c.str("""
                const { StringDecoder } = require('string_decoder');
                const d = new StringDecoder('utf8'), euro = Buffer.from('€'), smile = Buffer.from('😀');
                const r = ['|' + d.write(euro.subarray(0, 1)) + d.write(euro.subarray(1)) + '|'];
                r.push('|' + d.write(smile.subarray(0, 3)) + d.end(smile.subarray(3)) + '|');
                r.push(new StringDecoder().end(Buffer.from([0xE2, 0x82])));
                r.join()
            """))
            assertEquals("ab,aGVsbG8=,hel", c.str("""
                const { StringDecoder: SD } = require('node:string_decoder');
                const u = new SD('utf16le'), b = new SD('base64'), l = new SD('latin1');
                const u16 = Buffer.from('ab', 'utf16le');
                [u.write(u16.subarray(0, 3)) + u.end(u16.subarray(3)), b.write(Buffer.from('hel')) + b.end(Buffer.from('lo')),
                 l.write(new Uint8Array([104, 101, 108]))].join()
            """))
        }
    }
}
