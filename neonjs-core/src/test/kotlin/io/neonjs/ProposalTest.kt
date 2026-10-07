package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Behaviour of recent and staged features that Test262 covers only partly or not at all (decorators, ShadowRealm,
 * deferred imports), locked in for both execution modes.
 */
class ProposalTest {
    private val modes = listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)

    private fun engine(mode: ExecutionMode, sandbox: SandboxPolicy = SandboxPolicy.UNRESTRICTED, access: HostAccess = HostAccess.ALL) =
        NeonEngine.builder().executionMode(mode).sandbox(sandbox).hostAccess(access).console(null).build()

    private fun eval(mode: ExecutionMode, code: String): String = engine(mode).newContext().use { it.eval(code).asString() }

    @Test
    fun decoratorsTransformElements() {
        val code = """
            const double = (value, ctx) => function (...a) { return 2 * value.apply(this, a) };
            const plus10 = (value, ctx) => v => v + 10;
            function logged(value, { kind, name }) {
                if (kind === 'accessor') return {
                    get() { return value.get.call(this) },
                    set(v) { value.set.call(this, v * 2) },
                    init(v) { return v * 100 },
                };
            }
            const register = [];
            function reg(cls, ctx) { register.push(ctx.kind + ':' + ctx.name) }
            function wrapClass(cls, ctx) { return class extends cls { wrapped() { return true } } }
            @reg class C {
                @double num() { return 21 }
                @plus10 @plus10 x = 1;
                @logged accessor acc = 3;
                accessor plain = 'p';
                static accessor sacc = 9;
                #p = 5;
                get p() { return this.#p }
            }
            const c = new C();
            const r1 = [c.num(), c.x, c.acc, c.plain, C.sacc, register.join()].join();
            c.acc = 4; c.plain = 'q';
            const d = Object.getOwnPropertyDescriptor(C.prototype, 'plain');
            @wrapClass class W { static s = 1 }
            [r1, c.acc, c.plain, typeof d.get, typeof d.set, d.enumerable, d.configurable, new W().wrapped(), W.s].join('|')
        """
        for (m in modes) assertEquals("42,21,300,p,9,class:C|8|q|function|function|false|true|true|1", eval(m, code), "mode $m")
    }

    @Test
    fun decoratorContextAndInitializers() {
        val code = """
            const log = [];
            const t = tag => (value, ctx) => {
                log.push('call ' + tag + ':' + ctx.kind + ':' + String(ctx.name) + ':' + ctx.static + ':' + ctx.private);
                ctx.addInitializer(function () { log.push('init ' + tag + ' ' + typeof this) });
            };
            function cls(value, ctx) { ctx.addInitializer(function () { log.push('class init ' + this.name) }) }
            @cls class C {
                @t('m') method() {}
                @t('sm') static sm() {}
                @t('f') #f = 1;
                @t('pm') #pm() {}
                static { log.push('static block') }
            }
            log.push('---');
            new C();
            log.join('\n')
        """
        val expected = listOf(
            // static methods and accessors are decorated first, then instance ones, then static and instance fields
            "call sm:method:sm:true:false", "call m:method:method:false:false", "call pm:method:#pm:false:true",
            "call f:field:#f:false:true",
            // static method initializers run before static fields/blocks, class decorator initializers after them
            "init sm function", "static block", "class init C", "---",
            // instance method initializers run before fields, field initializers right after their field
            "init m object", "init pm object", "init f object",
        ).joinToString("\n")
        for (m in modes) assertEquals(expected, eval(m, code), "mode $m")
    }

    @Test
    fun decoratorOrderAndClassReplacement() {
        val code = """
            const log = [];
            const t = tag => (v, ctx) => { log.push(tag) };
            class O { @t('if') a = 1; @t('im') b() {}; @t('sf') static c = 1; @t('sm') static d() {}; @t('ia') accessor e; @t('sa') static accessor g }
            const alog = [];
            const ai = (v, ctx) => { ctx.addInitializer(function () { alog.push('acc-init') }) };
            const mi = (v, ctx) => { ctx.addInitializer(function () { alog.push('m-init') }) };
            class AI { x = alog.push('x'); @ai accessor y = alog.push('y'); z = alog.push('z'); @mi m() {} }
            new AI();
            let inner;
            const seen = [];
            const repl = (cls, ctx) => { ctx.addInitializer(function () { seen.push(['cls', this]) }); return class R2 extends cls {} };
            const sdec = (v, ctx) => { ctx.addInitializer(function () { seen.push(['sm', this]) }) };
            @repl class R { static f = this; @sdec static m() {} static { inner = R; seen.push(['blk', this]) } }
            // static private methods are installed on the original class (before class decorators run), static
            // fields on the replacement: here the static field initializer cannot call #pm on `this`
            let orig;
            const keep = (cls, ctx) => { orig = cls; return class extends cls {} };
            @keep class K { static #pm() { return 'pm' } static callOn(o) { return o.#pm() } static r = (() => { try { return K.#pm() } catch (e) { return e.name } })() }
            [log.join(), alog.join(), R.name, Object.hasOwn(R, 'f'), R.f === R, inner === R, seen.map(([k, v]) => k + ':' + (v === R)).join(),
             K.r, K.callOn(orig)].join('|')
        """
        for (m in modes) assertEquals("sm,sa,im,ia,sf,if|m-init,x,y,acc-init,z|R2|true|true|true|sm:true,blk:true,cls:true|TypeError|pm", eval(m, code), "mode $m")
    }

    @Test
    fun decoratorMetadata() {
        val code = """
            const meta = (k, v) => (_, ctx) => { ctx.metadata[k] = v };
            @meta('a', 'x') class C { @meta('m', 1) m() {} }
            class D extends C { @meta('b', 'z') m() {} }
            class E extends C {}
            class N extends null { @meta('n', 1) static s() {} }
            let bad; function P() {} P[Symbol.metadata] = 5;
            try { class Q extends P { @meta('q', 1) m() {} } } catch (e) { bad = e.name }
            let reads = 0; const S = new Proxy(class {}, { get(t, k, r) { if (k === Symbol.metadata) reads++; return Reflect.get(t, k, r) } });
            class U extends S { accessor plain = 1 }
            const cm = C[Symbol.metadata], dm = D[Symbol.metadata];
            const pd = Object.getOwnPropertyDescriptor(C, Symbol.metadata);
            const ps = Object.getOwnPropertyDescriptor(Symbol, 'metadata');
            [typeof Symbol.metadata, cm.a, cm.m, dm.a, dm.b, Object.getPrototypeOf(dm) === cm, Object.getPrototypeOf(cm),
             Object.hasOwn(E, Symbol.metadata), Object.getPrototypeOf(N[Symbol.metadata]),
             pd.writable && pd.enumerable && pd.configurable, ps.writable || ps.enumerable || ps.configurable, bad, reads].join()
        """
        // a primitive parent metadata is an error; undecorated classes neither read nor define Symbol.metadata
        for (m in modes) assertEquals("symbol,x,1,x,z,true,,false,,true,false,TypeError,0", eval(m, code), "mode $m")
    }

    @Test
    fun decoratorErrorsAndAccess() {
        val code = """
            const out = [];
            try { (class { @(42) m() {} }) } catch (e) { out.push(e.constructor.name) }
            try { (class { @((v, ctx) => 5) m() {} }) } catch (e) { out.push(e.constructor.name) }
            let saved;
            try { (class { @((v, ctx) => { saved = ctx }) m() {} }); saved.addInitializer(() => {}) } catch (e) { out.push(e.constructor.name) }
            const access = [];
            class A { @((v, ctx) => { access.push(ctx.access) }) #f = 7; @((v, ctx) => { access.push(ctx.access) }) pub = 8 }
            const a = new A();
            out.push(access[0].get(a), access[0].has(a), access[0].has({}), access[1].get(a));
            access[0].set(a, 70);
            out.push(access[0].get(a));
            try { access[0].get({}) } catch (e) { out.push(e.constructor.name) }
            out.join()
        """
        for (m in modes) assertEquals("TypeError,TypeError,TypeError,7,true,false,8,70,TypeError", eval(m, code), "mode $m")
    }

    @Test
    fun shadowRealmIsIsolated() {
        val policy = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("java.util.") }.build()
        for (m in modes) engine(m, policy, access).newContext().use { ctx ->
            ctx["host"] = Point(1, 2)
            assertEquals("object", ctx.eval("typeof Java").asString())
            assertEquals("undefined,undefined", ctx.eval("var r = new ShadowRealm(); r.evaluate('[typeof Java, typeof host].join()')").asString(),
                "host globals are not visible inside a ShadowRealm")
            assertEquals("TypeError", ctx.eval("try { r.evaluate('({})') } catch (e) { e.constructor.name }").asString())
            assertEquals("TypeError", ctx.eval("try { r.evaluate('x => x')({}) } catch (e) { e.constructor.name }").asString())
            assertEquals("TypeError", ctx.eval("try { r.evaluate('x => x')(host) } catch (e) { e.constructor.name }").asString(),
                "host objects cannot cross the boundary")
            assertEquals("TypeError", ctx.eval("try { r.evaluate('Object.prototype') } catch (e) { e.constructor.name }").asString())
            // callables are wrapped both ways; primitives cross
            assertEquals("3,ok", ctx.eval("var add = r.evaluate('(a, f) => a + f()'); [add(1, () => 2), r.evaluate('\"ok\"')].join()").asString())
            assertEquals("undefined", ctx.eval("globalThis.shared = 1; r.evaluate('typeof shared')").asString(), "separate global object")
            assertEquals("1,2", ctx.eval("r.evaluate('globalThis.n = 1'); r.evaluate('n++'); [r.evaluate('n - 1'), r.evaluate('n')].join()").asString(),
                "state persists across evaluate calls")
            // errors thrown inside become TypeErrors of the caller's realm
            assertEquals("true", ctx.eval("try { r.evaluate('throw new RangeError(\"x\")') } catch (e) { String(e instanceof TypeError) }").asString())
        }
    }

    @Test
    fun shadowRealmModules() {
        for (m in modes) engine(m).newContext().use { ctx ->
            ctx.setModuleLoader(MapModuleLoader(mapOf("util.js" to "export const twice = x => 2 * x; export const obj = {}")))
            ctx.defineModule("host:secret", mapOf("value" to Point(1, 2), "fn" to HostFunction { "called" }))
            assertEquals("object", ctx.evalModule("import { value } from 'host:secret'; export const t = typeof value", "a.js").getMember("t").asString())
            ctx.eval("var r = new ShadowRealm(), out = []")
            ctx.eval("r.importValue('util.js', 'twice').then(f => out.push(f(21)))")
            ctx.eval("r.importValue('util.js', 'obj').catch(e => out.push(e.constructor.name))")
            ctx.eval("r.importValue('host:secret', 'fn').catch(e => out.push(e.constructor.name))")
            ctx.runJobs()
            assertEquals("42,TypeError,TypeError", ctx.eval("out.sort().join()").asString(), "mode $m")
            // nor through import() inside the realm
            ctx.eval("r.evaluate(`import('host:secret').then(() => globalThis.res = 'loaded', e => globalThis.res = e.constructor.name); 0`)")
            ctx.runJobs()
            assertEquals("TypeError", ctx.eval("r.evaluate('res')").asString(), "mode $m")
        }
    }

    @Test
    fun shadowRealmHonoursSandbox() {
        val noEval = SandboxPolicy.builder().allowEval(false).build()
        for (m in modes) engine(m, noEval).newContext().use { ctx ->
            assertEquals("EvalError", ctx.eval("try { new ShadowRealm().evaluate('1') } catch (e) { e.constructor.name }").asString())
        }
        val limited = SandboxPolicy.builder().maxStatements(100_000).build()
        for (m in modes) engine(m, limited).newContext().use { ctx ->
            assertThrows<NeonException> { ctx.eval("new ShadowRealm().evaluate('for (;;) {}')") }
        }
        val timed = SandboxPolicy.builder().maxExecutionTime(300).build()
        for (m in modes) engine(m, timed).newContext().use { ctx ->
            assertThrows<NeonException> { ctx.eval("var f = new ShadowRealm().evaluate('() => { while (true) {} }'); f()") }
        }
    }

    @Test
    fun deferredImportsEvaluateOnFirstAccess() {
        val modules = mapOf(
            "lib.js" to "globalThis.log.push('lib'); export const x = 1; export function f() { return 'f' }",
            "main.js" to """
                import defer * as ns from './lib.js';
                globalThis.log.push('main');
                globalThis.log.push(Object.prototype.toString.call(ns));
                globalThis.log.push('x=' + ns.x);
                globalThis.log.push(ns.f());
            """,
            // c defers d; d imports c eagerly and touches the deferred namespace while it is itself evaluating
            "c.js" to "import defer * as d from './d.js'; export function peek() { return d.v }",
            "d.js" to "import { peek } from './c.js'; export let v = 1; try { peek(); globalThis.log.push('no error') } catch (e) { globalThis.log.push(e.constructor.name) }",
        )
        for (m in modes) engine(m).newContext().use { ctx ->
            ctx.setModuleLoader(MapModuleLoader(modules))
            ctx.eval("globalThis.log = []")
            ctx.evalModule("import './main.js'", "entry.js")
            assertEquals("main,[object Deferred Module],lib,x=1,f", ctx.eval("log.join()").asString(), "mode $m")
            ctx.eval("log.length = 0")
            ctx.evalModule("import './d.js'", "entry2.js")
            assertEquals("TypeError", ctx.eval("log.join()").asString(), "mode $m")
        }
    }

    @Test
    fun dynamicDeferredImport() {
        val modules = mapOf("lib.js" to "globalThis.log.push('lib'); export const x = 2")
        for (m in modes) engine(m).newContext().use { ctx ->
            ctx.setModuleLoader(MapModuleLoader(modules))
            ctx.eval("globalThis.log = []; import.defer('lib.js').then(ns => { log.push('loaded'); log.push(ns.x) })")
            ctx.runJobs()
            assertEquals("loaded,lib,2", ctx.eval("log.join()").asString(), "mode $m")
        }
    }
}
