package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** node:util: inspect and format as Node lays them out, and the helpers libraries use. */
class UtilTest {
    private fun ctx(options: NodeOptions = NodeOptions.DEFAULT): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension(options)).build().newContext()

    private fun NeonContext.str(code: String): String = eval(code).asString()

    @Test
    fun inspectLayout() {
        ctx().use { c ->
            c.eval("var { inspect } = require('util')")
            assertEquals("{ a: 1, b: 'two', c: [ 1, 2, 3 ], d: { e: { f: [Object] } } }", c.str("inspect({ a: 1, b: 'two', c: [1, 2, 3], d: { e: { f: { g: 1 } } } })"))
            assertEquals("<ref *1> { name: 'x', self: [Circular *1] }", c.str("const o = { name: 'x' }; o.self = o; inspect(o)"))
            assertEquals("Point { x: 1, y: 2 }", c.str("class Point { constructor() { this.x = 1; this.y = 2 } }; inspect(new Point())"))
            assertEquals("Map(2) { 'a' => 1, { k: true } => [ 'v' ] }", c.str("inspect(new Map([['a', 1], [{ k: true }, ['v']]]))"))
            assertEquals("Set(0) {},[ <2 empty items>, 3 ],[Object: null prototype] { a: 1 }", c.str("[inspect(new Set()), inspect([, , 3]), inspect(Object.assign(Object.create(null), { a: 1 }))].join()"))
            assertEquals("[Function: f],[class A],[class B extends A],[AsyncFunction: g],[Function (anonymous)]", c.str("""
                function f() {}
                class A {}
                class B extends A {}
                async function g() {}
                [inspect(f), inspect(A), inspect(B), inspect(g), inspect(() => {})].join()
            """))
            assertEquals("Promise { 1 },Promise { <pending> },Promise { <rejected> 'no' }", c.str("""
                const rej = Promise.reject('no'); rej.catch(() => {});
                [inspect(Promise.resolve(1)), inspect(new Promise(() => {})), inspect(rej)].join()
            """))
            assertEquals("'a\\nb',\"it's\",`a'b\"c`,-0,10n,Symbol(s),[Number: 3],1970-01-01T00:00:00.000Z,/x/gi", c.str("""
                [inspect('a\nb'), inspect("it's"), inspect('a\'b"c'), inspect(-0), inspect(10n), inspect(Symbol('s')), inspect(new Number(3)),
                 inspect(new Date(0)), inspect(/x/gi)].join()
            """))
            // long arrays are grouped in columns, over 100 items cut
            assertEquals(
                "[\n   0,  1,  2,  3,  4,  5,  6,  7,  8,\n   9, 10, 11, 12, 13, 14, 15, 16, 17,\n  18, 19, 20, 21, 22, 23, 24, 25, 26,\n  27, 28, 29\n]",
                c.str("inspect(Array.from({ length: 30 }, (_, i) => i))"))
            // as node lays them out: arguments by key, a subclass of Map with its tag, prototypes without a constructor,
            // long strings a line each, depth
            assertEquals("[Arguments] { '0': 1, '1': 2 }|Foo(0) [Map] {}|Array {}|Object <[Object: null prototype] {}> { a: 1 }", c.str("""
                [(function () { return inspect(arguments) })(1, 2), inspect(new (class Foo extends Map {})()), inspect(Object.create(Array.prototype)),
                 inspect(Object.setPrototypeOf({ a: 1 }, Object.create(null)))].join('|')
            """))
            assertEquals("'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\\n' +\n  'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyy\\n' +\n  'z'",
                c.str("inspect('x'.repeat(30) + '\\n' + 'y'.repeat(30) + '\\n' + 'z', { breakLength: 40 })"))
            assertEquals("[ [ 1, [ 2, [Array] ] ], { a: { b: [Object] } } ]", c.str("inspect([[1, [2, [3, [4]]]], { a: { b: { c: {} } } }])"))
            assertTrue(c.str("inspect(Array.from({ length: 120 }, (_, i) => i))").endsWith("... 20 more items\n]"))
            // an object too long for one line goes one property a line
            assertEquals("{\n  alpha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',\n  beta: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',\n  gamma: 'cccccccccccccccccccccccccccccccccccccccc'\n}",
                c.str("inspect({ alpha: 'a'.repeat(40), beta: 'b'.repeat(40), gamma: 'c'.repeat(40) })"))
            assertEquals("Uint8Array(3) [ 1, 2, 3 ],ArrayBuffer { [Uint8Contents]: <01 02>, byteLength: 2 }", c.str(
                "[inspect(new Uint8Array([1, 2, 3])), inspect(new Uint8Array([1, 2]).buffer)].join()"))
            assertEquals("{ a: [Getter], b: [Getter/Setter], [Symbol(k)]: 1 }", c.str("inspect({ get a() { return 1 }, get b() { return 1 }, set b(v) {}, [Symbol('k')]: 1 })"))
            assertEquals("\u001b[33m1\u001b[39m", c.str("inspect(1, { colors: true })"))
        }
    }

    @Test
    fun inspectErrorsProxiesAndCustom() {
        ctx().use { c ->
            c.eval("var { inspect } = require('util')")
            val err = c.str("const e = new TypeError('bad'); e.code = 'E_BAD'; inspect(e)")
            assertTrue(err.startsWith("TypeError: bad\n    at "), err)
            assertTrue(err.endsWith("{\n  code: 'E_BAD'\n}"), err)
            assertEquals("[Error: no frames]", c.str("const nf = new Error('no frames'); nf.stack = 'Error: no frames'; inspect(nf)"))
            // a proxy shows its target; its traps are never run
            assertEquals("{ t: 1 },0", c.str("""
                var trapped = 0;
                const handler = { get() { trapped++ }, ownKeys() { trapped++; return [] }, getOwnPropertyDescriptor() { trapped++ } };
                [inspect(new Proxy({ t: 1 }, handler)), trapped].join()
            """))
            assertEquals("Proxy [ { t: 1 }, {} ]", c.str("inspect(new Proxy({ t: 1 }, {}), { showProxy: true })"))
            // custom inspection gets the depth, the options with stylize, and inspect
            assertEquals("Thing<2,function,true> { inner: Thing<1,function,true> }", c.str("""
                class Thing {
                    constructor(inner) { this.inner = inner }
                    [inspect.custom](depth, options, insp) {
                        const head = 'Thing<' + depth + ',' + typeof options.stylize + ',' + (insp === inspect) + '>';
                        return this.inner ? head + ' { inner: ' + insp(this.inner, { ...options, depth: options.depth - 1 }) + ' }' : head;
                    }
                }
                inspect(new Thing(new Thing()))
            """))
        }
    }

    @Test
    fun formatting() {
        ctx().use { c ->
            c.eval("var util = require('node:util')")
            assertEquals("a 1 {\"b\":[1]} 42 3.5 %x", c.str("util.format('%s %d %j %i %f %x', 'a', '1', { b: [1] }, '42.9', '3.5')"))
            assertEquals("{ a: 1 } [Circular] 100% extra { x: 'y' }", c.str("const circ = {}; circ.c = circ; util.format('%O %j 100%%', { a: 1 }, circ, 'extra', { x: 'y' })"))
            assertEquals("Error: e,[ 1, 2 ],1 2", c.str("[util.format('%s', new Error('e')).split('\\n')[0], util.format([1, 2]), util.format(1, 2)].join()"))
            assertEquals("plain", c.str("util.styleText('red', 'plain')"))
            assertEquals("\u001b[31mred\u001b[39m", c.str("util.styleText('red', 'red', { validateStream: false })"))
            assertEquals("abc", c.str("util.stripVTControlCharacters('\\u001b[31mabc\\u001b[39m')"))
        }
    }

    @Test
    fun helpers() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const util = require('util');
                    const out = [];
                    // promisify, with promisify.custom (setTimeout has one)
                    const legacy = (a, b, cb) => setTimeout(() => cb(a < 0 ? new Error('neg') : null, a + b), 1);
                    out.push(await util.promisify(legacy)(1, 2));
                    out.push(await util.promisify(legacy)(-1, 0).catch((e) => e.message));
                    out.push(await util.promisify(setTimeout)(1, 'custom'));
                    // callbackify, a falsy rejection wrapped
                    const cbf = util.callbackify(async (x) => { if (x) return x * 2; throw null });
                    out.push(await new Promise((res) => cbf(21, (err, v) => res(v))));
                    out.push(await new Promise((res) => cbf(0, (err) => res(err.code + ':' + err.reason))));
                    // deprecate warns once per code
                    const warnings = [];
                    process.on('warning', (w) => warnings.push(w.code));
                    const old = util.deprecate(() => 'old', 'old() is gone', 'DEP_X');
                    out.push(old() + old());
                    util.deprecate(() => {}, 'again', 'DEP_X')();
                    await new Promise((res) => setTimeout(res, 5));
                    out.push(warnings.join('+'));
                    return out.join();
                })()
            """.replace("process.on", "require('process').on")).await(5_000).asString()
            assertEquals("3,neg,custom,42,ERR_FALSY_VALUE_REJECTION:null,oldold,DEP_X", r)

            assertEquals("true,true,false,false,true,true", c.str("""
                const u = require('util');
                [u.isDeepStrictEqual({ a: [1, { b: 2 }], m: new Map([[1, new Set([2])]]) }, { a: [1, { b: 2 }], m: new Map([[1, new Set([2])]]) }),
                 u.isDeepStrictEqual(Buffer.from('ab'), Buffer.from('ab')),
                 u.isDeepStrictEqual({ a: 1 }, { a: '1' }),
                 u.isDeepStrictEqual([1, , 3], [1, undefined, 3]),
                 u.isDeepStrictEqual(NaN, NaN),
                 (() => { const x = { v: 1 }; x.s = x; const y = { v: 1 }; y.s = y; return u.isDeepStrictEqual(x, y) })()].join()
            """))
            assertEquals("true,true,true,true,false,true", c.str("""
                const { types } = require('node:util');
                [types.isPromise(Promise.resolve()), types.isProxy(new Proxy({}, {})), types.isUint8Array(Buffer.alloc(1)),
                 types.isAsyncFunction(async () => {}), types.isGeneratorFunction(() => {}), require('util/types') === types].join()
            """))
            assertEquals("true,Sup", c.str("""
                function Sup() {}
                Sup.prototype.hello = function () { return 'Sup' };
                function Sub() { Sup.call(this) }
                require('util').inherits(Sub, Sup);
                [Sub.super_ === Sup, new Sub().hello()].join()
            """))
            assertEquals("true,true", c.str("const ut = require('util'); [ut.TextEncoder === TextEncoder, ut.TextDecoder === TextDecoder].join()"))
        }
        // debuglog writes when NODE_DEBUG names the section
        ctx(NodeOptions.builder().env(mapOf("NODE_DEBUG" to "net,ht*")).build()).use { c ->
            assertEquals("true,true,false", c.str("""
                const { debuglog } = require('util');
                [debuglog('net').enabled, debuglog('http').enabled, debuglog('tls').enabled].join()
            """))
        }
    }

    @Test
    fun codedErrors() {
        ctx().use { c ->
            assertEquals(
                "TypeError|ERR_INVALID_ARG_TYPE|The \"original\" argument must be of type function. Received type number (1)|" +
                    "TypeError [ERR_INVALID_ARG_TYPE]: The \"original\" argument must be of type function. Received type number (1)|true",
                c.str("""
                    let e;
                    try { require('util').promisify(1) } catch (err) { e = err }
                    [e.name, e.code, e.message, e.toString(), e.stack.startsWith('TypeError [ERR_INVALID_ARG_TYPE]: ')].join('|')
                """))
            assertEquals("The \"list[1]\" argument must be an instance of Buffer or Uint8Array. Received type string ('x')", c.str("""
                try { Buffer.concat([Buffer.alloc(1), 'x']) } catch (err) { err.message }
            """))
        }
    }
}
