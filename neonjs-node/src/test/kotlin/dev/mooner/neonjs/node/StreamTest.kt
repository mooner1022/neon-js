package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** node:stream (Node's own sources), stream/promises and stream/consumers. */
class StreamTest {
    private fun ctx(): NeonContext = NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension()).build().newContext()

    @Test
    fun readableTransformWritable() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const { Readable, Writable, Transform, PassThrough, pipeline } = require('stream');
                    const out = [];
                    const upper = new Transform({ transform(chunk, enc, cb) { cb(null, chunk.toString().toUpperCase()) } });
                    const sink = new Writable({ write(chunk, enc, cb) { out.push(chunk.toString()); cb() } });
                    await new Promise((resolve, reject) => pipeline(Readable.from(['a', 'b', 'c']), upper, new PassThrough(), sink, (e) => e ? reject(e) : resolve()));
                    // async iteration, objectMode
                    const objs = [];
                    for await (const o of Readable.from([{ n: 1 }, { n: 2 }])) objs.push(o.n);
                    out.push(objs.join('+'));
                    // Buffer chunks and encodings
                    const r = new Readable({ read() {} });
                    r.push(Buffer.from('héllo'));
                    r.push(null);
                    r.setEncoding('utf8');
                    let s = '';
                    for await (const chunk of r) s += chunk;
                    out.push(s);
                    return out.join();
                })()
            """).await(10_000).asString()
            assertEquals("A,B,C,1+2,héllo", r)
        }
    }

    @Test
    fun promisesAndConsumers() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const { pipeline, finished } = require('stream/promises');
                    const { Readable, Writable } = require('stream');
                    const consumers = require('stream/consumers');
                    const out = [];
                    out.push(await consumers.text(Readable.from(['x', 'y'])));
                    out.push(JSON.stringify(await consumers.json(Readable.from(['{"a":', '1}']))));
                    out.push((await consumers.buffer(Readable.from([Buffer.from([1, 2])]))).toString('hex'));
                    const chunks = [];
                    await pipeline(Readable.from(['p', 'q']), async function* (source) { for await (const x of source) yield x + '!' },
                        new Writable({ write(chunk, enc, cb) { chunks.push(String(chunk)); cb() } }));
                    out.push(chunks.join(''));
                    const w = new Writable({ write(chunk, enc, cb) { cb() } });
                    w.end('z');
                    await finished(w);
                    out.push(w.writableFinished);
                    // an error ends the pipeline
                    const failing = new Readable({ read() { this.destroy(new Error('broken')) } });
                    out.push(await pipeline(failing, new Writable({ write(c, e, cb) { cb() } })).catch((e) => e.message));
                    // operators
                    out.push((await Readable.from([1, 2, 3, 4]).filter((x) => x % 2 === 0).map((x) => x * 10).toArray()).join('|'));
                    return out.join();
                })()
            """).await(10_000).asString()
            assertEquals("xy,{\"a\":1},0102,p!q!,true,broken,20|40", r)
        }
    }

    @Test
    fun backpressureAndLegacyStreams() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const stream = require('stream');
                    const { Writable, Duplex } = stream;
                    const out = [];
                    let pending;
                    const w = new Writable({ highWaterMark: 4, write(chunk, enc, cb) { pending = cb } });
                    out.push(w.write('abcd'), w.writableLength, w.writableNeedDrain);
                    const drained = new Promise((resolve) => w.once('drain', resolve));
                    pending();
                    await drained;
                    out.push('drained');
                    // the old-style Stream, util.inherits subclasses
                    function Old() { stream.Stream.call(this) }
                    require('util').inherits(Old, stream.Stream);
                    out.push(new Old() instanceof require('events'), typeof stream.Stream.prototype.pipe);
                    // Duplex.from a pair
                    const d = Duplex.from({ readable: stream.Readable.from(['in']), writable: new Writable({ write(c, e, cb) { cb() } }) });
                    out.push(d instanceof Duplex, stream.isReadable(d), typeof stream.promises.pipeline, require('stream').promises === require('stream/promises'));
                    return out.join();
                })()
            """).await(10_000).asString()
            assertEquals("false,4,true,drained,true,function,true,true,function,true", r)
        }
    }

    @Test
    fun webStreamsAreNotThereYet() {
        ctx().use { c ->
            assertEquals("ERR_UNAVAILABLE,ERR_UNAVAILABLE", c.eval("""
                const out = [];
                try { new (require('stream/web').ReadableStream)() } catch (e) { out.push(e.code) }
                try { require('stream').Readable.toWeb(require('stream').Readable.from([])) } catch (e) { out.push(e.code) }
                out.join()
            """).asString())
        }
    }
}
