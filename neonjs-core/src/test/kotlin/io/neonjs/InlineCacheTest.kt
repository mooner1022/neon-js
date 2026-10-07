package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Property inline caches must never observe stale structure. Every scenario runs in both execution modes. */
class InlineCacheTest {
    private fun check(src: String) {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            NeonEngine.builder().executionMode(mode).console(null).build().newContext().use { c ->
                val r = c.eval(HARNESS + src)
                assertEquals("ok", r.asString(), "mode $mode")
            }
        }
    }

    private val HARNESS = """
        function eq(a, b, m) { if (a !== b && !(a !== a && b !== b)) throw new Error((m || '') + ': expected ' + String(b) + ' got ' + String(a)); }
        function throws(f, m) { try { f() } catch (e) { return e } throw new Error((m || '') + ': no exception'); }
    """

    @Test
    fun deleteAndRedefine() = check("""
        function getX(o) { return o.x }
        var o = { x: 1, y: 2 };
        for (var i = 0; i < 50; i++) eq(getX(o), 1);
        delete o.x;
        eq(getX(o), undefined, 'after delete');
        Object.defineProperty(o, 'x', { get() { return 42 }, configurable: true });
        eq(getX(o), 42, 'getter');
        Object.defineProperty(o, 'x', { value: 7 });
        eq(getX(o), 7, 'back to data');
        'ok'
    """)

    @Test
    fun readOnlyAfterCachedStore() = check("""
        function setX(o, v) { 'use strict'; o.x = v }
        var o = { x: 0 };
        for (var i = 0; i < 50; i++) setX(o, i);
        eq(o.x, 49);
        Object.defineProperty(o, 'x', { writable: false });
        throws(function () { setX(o, 100) }, 'non-writable');
        eq(o.x, 49);
        var f = Object.freeze({ x: 1 });
        throws(function () { setX(f, 2) }, 'frozen');
        'ok'
    """)

    @Test
    fun addTransitionRespectsExtensibilityAndSetters() = check("""
        function init(o) { 'use strict'; o.a = 1; o.b = 2; return o }
        for (var i = 0; i < 50; i++) init({});
        var n = Object.preventExtensions({});
        throws(function () { init(n) }, 'non-extensible');
        eq(Object.keys(n).length, 0);
        var log = [];
        Object.defineProperty(Object.prototype, 'b', { set(v) { log.push(v) }, configurable: true });
        var o = init({});
        eq(o.hasOwnProperty('b'), false, 'setter on proto intercepts');
        eq(log.join(), '2');
        Object.defineProperty(Object.prototype, 'b', { set: undefined, configurable: true });
        throws(function () { init({}) }, 'setter removed');
        delete Object.prototype.b;
        eq(init({}).b, 2);
        Object.defineProperty(Object.prototype, 'a', { value: 0, writable: false, configurable: true });
        throws(function () { init({}) }, 'read-only on proto');
        delete Object.prototype.a;
        'ok'
    """)

    @Test
    fun prototypeChanges() = check("""
        class A { m() { return 'A' } }
        class B extends A {}
        function call(o) { return o.m() }
        var b = new B();
        for (var i = 0; i < 50; i++) eq(call(b), 'A');
        B.prototype.m = function () { return 'B' };
        eq(call(b), 'B', 'shadowed on intermediate proto');
        delete B.prototype.m;
        eq(call(b), 'A');
        A.prototype.m = function () { return 'A2' };
        eq(call(b), 'A2', 'replaced value');
        Object.setPrototypeOf(b, { m() { return 'C' } });
        eq(call(b), 'C', 'setPrototypeOf');
        var empty = new B();
        Object.setPrototypeOf(B.prototype, { m() { return 'D' } });
        eq(call(empty), 'D', 'setPrototypeOf on intermediate');
        b.m = function () { return 'own' };
        eq(call(b), 'own');
        var bare = Object.create(null);
        function getQ(o) { return o.q }
        for (var i = 0; i < 20; i++) eq(getQ(bare), undefined);
        bare.q = 5;
        eq(getQ(bare), 5, 'null-proto object gained property');
        'ok'
    """)

    @Test
    fun absentPropertyBecomesPresent() = check("""
        function get(o) { return o.zz }
        var o = { a: 1 };
        for (var i = 0; i < 50; i++) eq(get(o), undefined);
        Object.prototype.zz = 'proto';
        eq(get(o), 'proto');
        o.zz = 'own';
        eq(get(o), 'own');
        delete Object.prototype.zz;
        delete o.zz;
        eq(get(o), undefined);
        'ok'
    """)

    @Test
    fun polymorphicAndExotic() = check("""
        function len(o) { return o.length }
        var objs = [[1, 2, 3], 'abcd', { length: 9 }, new String('xy'), function (a, b) {}, { length: 1, x: 0 }];
        for (var k = 0; k < 20; k++) {
            eq(len(objs[0]), 3); eq(len(objs[1]), 4); eq(len(objs[2]), 9); eq(len(objs[3]), 2); eq(len(objs[4]), 2); eq(len(objs[5]), 1);
        }
        var arr = [1, 2];
        function push(a, v) { return a.push(v) }
        for (var i = 0; i < 50; i++) push(arr, i);
        eq(arr.length, 52);
        arr.push = function () { return 'own push' };
        eq(push(arr, 1), 'own push');
        function setLen(a) { a.length = 1 }
        var a2 = [1, 2, 3];
        for (var i = 0; i < 20; i++) setLen([1, 2, 3]);
        setLen(a2);
        eq(a2.length, 1); eq(a2[1], undefined);
        var p = new Proxy({}, { get(t, k) { return 'trap:' + String(k) } });
        function getFoo(o) { return o.foo }
        for (var i = 0; i < 20; i++) getFoo({ foo: 1 });
        eq(getFoo(p), 'trap:foo');
        'ok'
    """)

    @Test
    fun gettersAndSettersOnClasses() = check("""
        class P { constructor() { this._v = 0 } get v() { return this._v } set v(x) { this._v = x * 2 } }
        function rw(o, x) { o.v = x; return o.v }
        var p = new P();
        for (var i = 0; i < 50; i++) eq(rw(p, i), i * 2);
        Object.defineProperty(P.prototype, 'v', { get() { return 'new' }, set(x) { this._v = -x }, configurable: true });
        eq(rw(p, 3), 'new');
        eq(p._v, -3);
        'ok'
    """)

    @Test
    fun globalVariables() = check("""
        globalThis.a = 1; let b = 2; const c = 3;
        function rd() { return a + b + c + (typeof zz) }
        for (var i = 0; i < 20; i++) eq(rd(), '6undefined');
        globalThis.a = 10; b = 20;
        eq(rd(), '33undefined');
        delete globalThis.a;
        eq(typeof a, 'undefined');
        throws(function () { rd() }, 'deleted global');
        Object.defineProperty(globalThis, 'a', { get() { return 'g' }, configurable: true });
        eq(rd(), 'g203undefined');
        globalThis.zz = 1;
        eq(rd(), 'g203number');
        throws(function () { c = 5 }, 'const');
        function wr(v) { 'use strict'; ro = v }
        Object.defineProperty(globalThis, 'ro', { value: 1, writable: true, configurable: true });
        for (var i = 0; i < 20; i++) wr(i);
        Object.defineProperty(globalThis, 'ro', { writable: false });
        throws(function () { wr(100) }, 'read-only global');
        eq(ro, 19);
        'ok'
    """)

    @Test
    fun globalLexicalShadowingAcrossScripts() {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            NeonEngine.builder().executionMode(mode).console(null).build().newContext().use { c ->
                c.eval("globalThis.x = 'prop'; function rx() { return x } for (var i = 0; i < 20; i++) rx()")
                assertEquals("prop", c.eval("rx()").asString())
                c.eval("let x = 'lexical'")
                assertEquals("lexical", c.eval("rx()").asString(), "mode $mode")
            }
        }
    }

    @Test
    fun globalsInSharedScript() {
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).console(null).build()
        val script = engine.compile("var t = 0; for (var i = 0; i < 50; i++) t += g; t")
        val a = engine.newContext()
        val b = engine.newContext()
        a.eval("let g = 1")
        b.eval("var g = 2")
        assertEquals(50, a.eval(script).asInt())
        assertEquals(100, b.eval(script).asInt())
        assertEquals(50, a.eval(script).asInt())
    }

    @Test
    fun scriptSharedAcrossContexts() {
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).console(null).build()
        val script = engine.compile("function get(o) { return o.k } var s = 0; for (var i = 0; i < 100; i++) s += get(obj); s")
        val a = engine.newContext()
        val b = engine.newContext()
        a.eval("var obj = { k: 1 }")
        b.eval("var obj = Object.create({ k: 2 })")
        assertEquals(100, a.eval(script).asInt())
        assertEquals(200, b.eval(script).asInt())
        b.eval("Object.getPrototypeOf(obj).k = 3")
        assertEquals(300, b.eval(script).asInt())
        assertEquals(100, a.eval(script).asInt())
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun concurrentContextsSharingScript() {
        val engine = NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(10).console(null).build()
        val script = engine.compile("""
            function mk(i) { var o = {}; if (i % 3 == 0) o.a = i; o.b = i; if (i % 5 == 0) o.c = i; o.d = i; return o }
            function sum(o) { return (o.a || 0) + o.b + (o.c || 0) + o.d }
            var t = 0; for (var i = 0; i < 20000; i++) t += sum(mk(i)); t
        """)
        val expected = run {
            var t = 0L
            for (i in 0 until 20000) t += (if (i % 3 == 0) i else 0) + i + (if (i % 5 == 0) i else 0) + i
            t
        }
        val pool = Executors.newFixedThreadPool(8)
        val futures = (0 until 16).map {
            pool.submit<Long> { engine.newContext().use { c -> c.eval(script).asLong() } }
        }
        for (f in futures) assertEquals(expected, f.get())
        pool.shutdown()
    }
}
