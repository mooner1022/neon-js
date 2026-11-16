package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CollectionSink {
    var kept: Any? = null
    fun putInto(m: MutableMap<String, Any?>): Int { m["z"] = 26; m.remove("a"); return m.size }
    fun read(m: Map<Any?, Any?>): String = "${m["k"]},${m[1]},${m.containsKey("k")},${m.size},${m.keys.joinToString("|")}"
    fun keep(m: Map<String, Any?>): Map<String, Any?> { kept = m; return m }
    fun addTo(s: MutableSet<Any?>): Int { s.add("q"); s.remove(1); return s.size }
    fun keepSet(s: Set<Any?>): Set<Any?> { kept = s; return s }
    fun count(c: Collection<Any?>): Int = c.size
    fun joinAll(i: Iterable<Any?>): String = i.joinToString(",")
    fun props(m: Map<String, Any?>): String = m.toString()
}

class Thrower {
    fun io() { throw java.io.IOException("boom", IllegalStateException("inner")) }
    fun hidden() { throw HiddenFailure() }
}

/** A non-public exception: JS sees it as its public superclass. */
private class HiddenFailure : IllegalStateException("hidden")

fun twice(x: Int) = x * 2

class LongSink {
    fun take(x: Long) = x.toString()
    fun pick(x: Long) = "long $x"
    fun pick(x: String) = "string $x"
    fun fn(): java.util.function.Function<Int, Int> = java.util.function.Function { it + 1 }
}

class ObjectSink {
    var kept: Any? = null
    fun describe(o: Any?): String = when (o) {
        null -> "null"
        is java.util.function.Function<*, *> -> "Function"
        is Map<*, *> -> "Map" + o.entries.joinToString(",", "{", "}") { "${it.key}=${describe(it.value)}" }
        is Set<*> -> "Set" + o.joinToString(",", "[", "]") { describe(it) }
        is List<*> -> "List" + o.joinToString(",", "[", "]") { describe(it) }
        is NeonValue -> "NeonValue"
        else -> o.javaClass.simpleName + ":" + o
    }
    fun keep(o: Any?): Any? { kept = o; return o }
    @Suppress("UNCHECKED_CAST")
    fun apply(f: Any?, x: Any?): Any? = (f as java.util.function.Function<Any?, Any?>).apply(x)
    @Suppress("UNCHECKED_CAST")
    fun write(o: Any?) { (o as MutableMap<String, Any?>)["fromJava"] = 1 }
}

class OverloadedSink {
    fun take(s: Set<Any?>) = "Set"
    fun take(o: Any?) = "Object"
    fun takeMap(m: Map<Any?, Any?>) = "Map"
    fun takeMap(o: Any?) = "Object"
}

/**
 * Host objects as GraalJS shows them to scripts, where NeonJS fell short: JS Maps and Sets
 * as Java ones, host date-times for `new Date`. Expectations are GraalJS 25.5's answers to the same scripts, except
 * where noted.
 */
class ForeignObjectsTest {
    private fun ctx(access: HostAccess = HostAccess.ALL, sandbox: SandboxPolicy = SandboxPolicy.builder().build()) =
        NeonEngine.builder().hostAccess(access).sandbox(sandbox).console(null).build().newContext()

    private fun NeonContext.str(code: String) = eval(code).asString()

    @Test
    fun aJsMapIsAJavaMapOfItsEntries() {
        // it was a view of the Map object's own properties: an empty Map
        ctx().use { c ->
            val h = CollectionSink()
            c["h"] = h
            assertEquals("""2:[["b",2],["z",26]]""", c.str("var jm = new Map([['a', 1], ['b', 2]]); var n = h.putInto(jm); n + ':' + JSON.stringify([...jm])"),
                "a live view: what Java puts and removes, the Map has")
            assertEquals("v,one,true,2,k|1", c.str("h.read(new Map([['k', 'v'], [1, 'one']]))"), "keys as JS values: Integer 1 finds the key 1")
            assertTrue(c.eval("var m2 = new Map([['x', 1]]); h.keep(m2) === m2").asBoolean(), "it comes back as the same Map")
            // a plain object is still a view of its properties
            assertEquals("{p=1}", c.str("h.props({ p: 1 })"))
        }
    }

    @Test
    fun aJsSetIsAJavaSetOfItsValues() {
        ctx().use { c ->
            c["h"] = CollectionSink()
            assertEquals("2:2,q", c.str("var s = new Set([1, 2]); var n = h.addTo(s); n + ':' + [...s].join()"))
            assertEquals("true,3,a,b", c.str("var s2 = new Set(['a', 'b']); [h.keepSet(s2) === s2, h.count(new Set([1, 2, 3])), h.joinAll(s2)].join()"))
        }
    }

    @Test
    fun aJavaMapIsAJsMap() {
        ctx().use { c ->
            val m = linkedMapOf<String, Any?>("a" to 1, "b" to 2)
            c["m"] = m
            assertEquals("true,true,[object Map]", c.str("[Object.getPrototypeOf(m) === Map.prototype, m instanceof Map, Object.prototype.toString.call(m)].join()"))
            assertEquals("""a=1|b=2 [["a",1],["b",2]] 2 [["a",1],["b",2]] {"a":1,"b":2} a,b true false""", c.str("""[
                (() => { const r = []; for (const [k, v] of m) r.push(k + '=' + v); return r.join('|') })(),
                JSON.stringify([...m]), new Map(m).get('b'), JSON.stringify(Array.from(m)), JSON.stringify(Object.fromEntries(m)),
                [...m.keys()].join(), m.has('a'), m.has('z')
            ].join(' ')"""))
            // Map.prototype's methods change the host's Map
            assertTrue(c.eval("m.set('c', 3) === m").asBoolean())
            assertEquals(3, m["c"])
            assertEquals("true,false,3", c.str("[m.delete('c'), m.delete('c'), Map.prototype.get.call(m, 'c') === undefined ? 3 : 0].join()"))
            assertEquals(listOf("a", "b"), m.keys.toList())
            assertEquals("2|a=1,b=2|1|v", c.str("""[
                Object.getOwnPropertyDescriptor(Map.prototype, 'size').get.call(m),
                (() => { const r = []; Map.prototype.forEach.call(m, (v, k, mm) => r.push(k + '=' + v + (mm === m ? '' : '!'))); return r.join() })(),
                m.getOrInsert('a', 9), m.getOrInsertComputed('v', k => k)
            ].join('|')"""))
            assertEquals("v", m["v"])
            // a key the JS value converts back to: a number key is an Integer
            c.eval("m.set(1, 'one')")
            assertEquals("one", m[1 as Any?])
            assertEquals("one,true,false", c.str("[m.get(1), m.has(1), m.has('1')].join()"))
            // changing the Map during a for-of never throws (GraalJS throws ConcurrentModificationException): a removed
            // key is skipped, an added one is not visited
            c["x"] = linkedMapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3)
            assertEquals("a,c", c.str("var r = []; for (const [k] of x) { r.push(k); if (k === 'a') { x.delete('b'); x.set('n', 9) } } r.join()"))
            // Java's members come first (as in GraalJS): size() and forEach((k, v)) are Java's, and an entry named like a
            // Map method shadows it, as entries are properties
            assertEquals("function,4,k=a", c.str("[typeof m.size, m.size(), (() => { let s; m.forEach((k, v) => { if (!s) s = 'k=' + k }); return s })()].join()"))
            c["sh"] = linkedMapOf<String, Any?>("has" to 5, "a" to 1)
            assertEquals("5,true", c.str("[sh.has, Map.prototype.has.call(sh, 'a')].join()"))
            // a Map that cannot change
            c["fixed"] = java.util.Map.of("k", 1)
            assertEquals("TypeError,1", c.str("[(() => { try { fixed.set('j', 2) } catch (e) { return e.name } })(), fixed.get('k')].join()"))
            // JSON and keys stay the entries with string keys (those are the properties), as before
            assertEquals("""{"a":1,"b":2,"v":"v"}""", c.str("JSON.stringify(m)"))
        }
    }

    @Test
    fun aRememberedOverloadChoiceDoesNotMixObjectsWithMapsAndSets() {
        // overload choices are remembered by the kinds of the arguments: a plain object and a JS Map or Set convert
        // differently, in either order
        for (first in listOf("{}", "new Set()")) {
            ctx().use { c ->
                c["h"] = OverloadedSink()
                val second = if (first == "{}") "new Set()" else "{}"
                val r = c.str("[h.take($first), h.take($second), h.take($first)].join()")
                assertEquals(if (first == "{}") "Object,Set,Object" else "Set,Object,Set", r)
                assertEquals("Object,Map,Object", c.str("[h.takeMap(1), h.takeMap(new Map()), h.takeMap(2)].join()"))
            }
        }
    }

    @Test
    fun aKeyAMapRefusesIsNoEntry() {
        // a TreeMap of Integer keys refuses String lookups (ClassCastException), Map.of refuses null: that is no entry,
        // so Map.prototype's methods are not shadowed by a failed lookup (GraalJS throws on `tm.has`)
        ctx().use { c ->
            c["tm"] = java.util.TreeMap(mapOf(1 to "a"))
            c["stm"] = java.util.TreeMap(mapOf("k" to 1))
            c["mof"] = java.util.Map.of("k", 1)
            assertEquals("function,true,,[[1,\"a\"]],false,,false,false", c.str("""[typeof tm.has, tm.has(1), tm.foo,
                JSON.stringify([...tm]), stm.has(1), Map.prototype.get.call(stm, 1), mof.has(undefined), mof.delete(null)].join()"""))
        }
    }

    @Test
    fun aSiteSeesJsMapsThenHostMaps() {
        for (mode in ExecutionMode.entries) {
            NeonEngine.builder().hostAccess(HostAccess.ALL).executionMode(mode).console(null).build().newContext().use { c ->
                c["hm"] = linkedMapOf<String, Any?>("a" to 1, "b" to 2)
                c.eval("""
                    function f(m) {
                        let t = 0;
                        for (const [k, v] of m) t += v;
                        return t + Map.prototype.get.call(m, 'a') + (m.has('b') ? 10 : 0) + [...m.keys()].length + (m instanceof Map ? 100 : 0);
                    }
                    var js = new Map([['a', 1], ['b', 2]]), sum = 0;
                    for (var i = 0; i < 3000; i++) sum += f(js);
                    for (var i = 0; i < 3000; i++) sum += f(hm);
                    for (var i = 0; i < 3000; i++) sum += f(i % 2 ? js : hm);
                """)
                // f gives 3 + 1 + 10 + 2 + 100 = 116 for both
                assertEquals(116 * 9000, c.eval("sum").asInt(), mode.name)
            }
        }
    }

    @Test
    fun aJavaMapIsAJsMapWhereThePolicyShowsItsEntries() {
        ctx(HostAccess.EXPLICIT).use { c ->
            c["m"] = linkedMapOf("a" to 1)
            assertEquals("false,true,undefined", c.str("[m instanceof Map, Object.getPrototypeOf(m) === Object.prototype, typeof m.has].join()"))
        }
        ctx(HostAccess.builder(HostAccess.Level.EXPLICIT).allowMapAccess(true).build()).use { c ->
            c["m"] = linkedMapOf("a" to 1)
            assertEquals("true,true,1", c.str("[m instanceof Map, m.has('a'), m.get('a')].join()"), "Map.prototype's get: Java's is hidden here")
        }
    }

    @Test
    fun aHostExceptionIsNamedAfterItsClass() {
        val lookup = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        ctx(lookup, SandboxPolicy.builder().exposeJavaGlobal(true).build()).use { c ->
            c["t"] = Thrower()
            assertEquals("java.io.IOException|java.io.IOException: boom|boom|true,true,true,false,false|undefined", c.str("""
                try { t.io() } catch (e) {
                    [e.name, String(e), e.message,
                     [e instanceof Error, e instanceof Java.type('java.io.IOException'), e instanceof Java.type('java.lang.Exception'),
                      e instanceof Java.type('java.lang.IllegalStateException'), ({}) instanceof Java.type('java.lang.Exception')].join(),
                     typeof e.getMessage].join('|')
                }"""), "its methods only where the policy allows them")
        }
        // a class the policy filters out, or one that is not public, is named after its nearest superclass JS may use
        val filtered = HostAccess.builder(HostAccess.Level.ALL).filterClasses { it != java.io.IOException::class.java }.build()
        ctx(filtered).use { c ->
            c["t"] = Thrower()
            assertEquals("java.lang.Exception,java.lang.IllegalStateException", c.str("""[(() => { try { t.io() } catch (e) { return e.name } })(),
                (() => { try { t.hidden() } catch (e) { return e.name } })()].join()"""))
        }
        // a policy that shows nothing of the host: no class name either (a failed future's error, as a host call fails
        // nowhere under NONE)
        NeonEngine.builder().hostAccess(HostAccess.NONE).console(null).build().newContext().use { c ->
            c["f"] = java.util.concurrent.CompletableFuture.failedFuture<Any>(java.io.IOException("x"))
            c.eval("var out; f.catch(e => { out = String(e) })")
            c.runEventLoop(5_000)
            assertEquals("HostError: x", c.str("out"))
        }
    }

    @Test
    fun aHostExceptionsMethodsWhereThePolicyAllowsThem() {
        val all = HostAccess.builder(HostAccess.Level.ALL).allowExceptionAccess(true).build()
        ctx(all).use { c ->
            c["t"] = Thrower()
            assertEquals("boom|inner|java.io.IOException: boom|true", c.str("""
                try { t.io() } catch (e) { [e.getMessage(), e.getCause().getMessage(), e.toString(), e.getStackTrace().length > 0].join('|') }"""),
                "the error's own toString stays Error.prototype's")
        }
        // under EXPLICIT, the exported ones only (none of IOException's)
        val explicit = HostAccess.builder(HostAccess.Level.EXPLICIT).allowExceptionAccess(true).build()
        ctx(explicit).use { c ->
            c["f"] = java.util.concurrent.CompletableFuture.failedFuture<Any>(java.io.IOException("x"))
            c.eval("var out; f.catch(e => { out = [e.name, typeof e.getMessage].join() })")
            c.runEventLoop(5_000)
            assertEquals("java.io.IOException,undefined", c.str("out"))
        }
    }

    @Test
    fun javaPackagesAsGlobals() {
        // off by default
        ctx().use { c -> assertEquals("undefined,undefined", c.str("[typeof Packages, typeof java].join()")) }
        val lookup = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        val packages = SandboxPolicy.builder().exposeJavaGlobal(true).exposeJavaPackages(true).build()
        ctx(lookup, packages).use { c ->
            assertEquals("object,object,object,object,object,object,object", c.str("[Packages, java, javax, com, org, edu, android].map(x => typeof x).join()"),
                "android besides GraalJS's globals, for Android hosts")
            assertEquals("0,true,true,true", c.str("""[new java.util.ArrayList().size(), java.util.ArrayList === Java.type('java.util.ArrayList'),
                Packages.java.util.HashMap === java.util.HashMap, java.util === java.util].join()"""))
            assertEquals("[JavaPackage java.util]|object|{}|0|true|function", c.str("""[String(java.util), typeof java.no.such.Thing, JSON.stringify(java.util),
                Object.keys(java.util).length, 'util' in java, typeof java.util.Map.Entry].join('|')"""))
            assertEquals("true,object", c.str("java.util.ArrayList2 = 1; java.lang = 2; [typeof java.lang === 'object', typeof java.util.ArrayList2].join()"),
                "a package takes no writes")
            // the policy first: a class denied by name (Runtime), or one the lookup filter refuses, is just a name
            assertEquals("object,object", c.str("[typeof java.lang.Runtime, typeof java.lang.Runtime.getRuntime].join()"))
            // a script's own globals of those names still work
            assertEquals(5, c.eval("var com = 5; com").asInt())
        }
        // no lookup allowed: every name is a package
        ctx(HostAccess.ALL, SandboxPolicy.builder().exposeJavaPackages(true).build()).use { c ->
            assertEquals("object", c.str("typeof java.util.ArrayList"))
        }
        val narrow = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it == "java.util.ArrayList" }.build()
        ctx(narrow, SandboxPolicy.builder().exposeJavaPackages(true).build()).use { c ->
            assertEquals("function,object", c.str("[typeof java.util.ArrayList, typeof java.util.HashMap].join()"))
        }
    }

    @Test
    fun hostFunctionsHaveFunctionPrototype() {
        ctx().use { c ->
            c["f"] = LongSink().fn()
            assertEquals("true,true,2,3,4,0,", c.str("""[f instanceof Function, Object.getPrototypeOf(f) === Function.prototype, f.call(null, 1),
                f.bind(null, 2)(), Function.prototype.apply.call(f, null, [3]), f.length, f.name].join()"""),
                "f.apply is Java's Function.apply, as in GraalJS")
            assertEquals("function", c.str("typeof f.apply"))
        }
    }

    @Test
    fun hostDateTimesThatNameAnInstantAreDates() {
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        val values = linkedMapOf<String, Any>(
            "instant" to java.time.Instant.ofEpochMilli(5),
            "zoned" to java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, seoul),
            "offset" to java.time.OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, java.time.ZoneOffset.ofHours(9)),
            "legacy" to java.util.Date(86_400_000L),
            "local" to java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5),
            "day" to java.time.LocalDate.of(2026, 1, 2),
        )
        ctx().use { c ->
            for ((k, v) in values) c[k] = v
            assertEquals("true,true,true,true,false,false", c.str("[instant, zoned, offset, legacy, local, day].map(d => d instanceof Date).join()"),
                "a LocalDateTime or LocalDate names no instant (as in GraalJS)")
            assertEquals("5|1970-01-01T00:00:00.005Z|1970|\"1970-01-01T00:00:00.005Z\"|5|true|[object Date]|object", c.str("""[instant.getTime(), instant.toISOString(),
                instant.getUTCFullYear(), JSON.stringify(instant), +instant, instant < new Date(10), Object.prototype.toString.call(instant), typeof instant].join('|')"""))
            assertEquals("1767290645000,1767290645000,86400000", c.str("[zoned.getTime(), offset.getTime(), legacy.getTime()].join()"))
            assertEquals("\"2026-01-01T18:04:05.000Z\"", c.str("JSON.stringify(zoned)"))
            // Java's members come first: String(instant) is Instant.toString(); Date.prototype's setters are a TypeError
            assertEquals("1970-01-01T00:00:00.005Z|TypeError|TypeError", c.str("""[String(instant),
                (() => { try { instant.setTime(0) } catch (e) { return e.name } })(),
                (() => { try { zoned.setFullYear(2000) } catch (e) { return e.name } })()].join('|')"""))
            // ...but a java.util.Date's own setTime is Java's, and changes it; a ZonedDateTime's getMonth() is Java's
            assertEquals("7,JANUARY,2026", c.str("[(legacy.setTime(7), legacy.getTime()), zoned.getMonth(), zoned.getYear()].join()"))
            assertEquals(7L, (values["legacy"] as java.util.Date).time)
            // Date.prototype.toTemporalInstant reads it too
            assertEquals("1970-01-01T00:00:00.005Z", c.str("String(instant.toTemporalInstant())"))
        }
        // under EXPLICIT (members hidden) a Date still, as in GraalJS; under NONE nothing
        ctx(HostAccess.EXPLICIT).use { c ->
            c["instant"] = java.time.Instant.ofEpochMilli(5)
            assertEquals("true,5,true", c.str("[instant instanceof Date, instant.getTime(), String(instant).startsWith('Thu Jan 01 1970')].join()"))
        }
        ctx(HostAccess.NONE).use { c ->
            c["instant"] = java.time.Instant.ofEpochMilli(5)
            assertEquals("false,undefined", c.str("[instant instanceof Date, typeof instant.getTime].join()"))
        }
    }

    @Test
    fun aSiteSeesJsFunctionsAndDatesThenHostOnes() {
        // one function's sites (call, bind, apply.call; getTime, toISOString, +d) warmed by JS values, then given host ones
        for (mode in ExecutionMode.entries) {
            NeonEngine.builder().hostAccess(HostAccess.ALL).executionMode(mode).console(null).build().newContext().use { c ->
                c["hf"] = LongSink().fn()
                c["hd"] = java.time.Instant.ofEpochMilli(5)
                c.eval("""
                    function f(fn, d) {
                        return fn.call(null, 1) + fn.bind(null, 2)() + Function.prototype.apply.call(fn, null, [3])
                            + d.getTime() + d.toISOString().length + (+d) + (d instanceof Date ? 100 : 0);
                    }
                    var jf = x => x + 1, jd = new Date(5), sum = 0;
                    for (var i = 0; i < 3000; i++) sum += f(jf, jd);
                    for (var i = 0; i < 3000; i++) sum += f(hf, hd);
                    for (var i = 0; i < 3000; i++) sum += f(i % 2 ? jf : hf, i % 3 ? jd : hd);
                """)
                // f gives 2 + 3 + 4 + 5 + 24 + 5 + 100 = 143 for both
                assertEquals(143 * 9000, c.eval("sum").asInt(), mode.name)
            }
        }
    }

    @Test
    fun aKotlinFunctionReferenceIsAFunction() {
        ctx().use { c ->
            c["kf"] = ::twice
            c["out"] = null
            assertEquals("function,6", c.str("[typeof kf, kf(3)].join()"))
            // its call is Function.prototype.call (KCallable's reflective call is no member of the reference)
            assertEquals("8,8", c.str("[kf.call(null, 4), Function.prototype.call.call(kf, null, 4)].join()"))
        }
    }

    @Test
    fun jsObjectsAsJavaViewsByPermission() {
        val script = "[h.describe({ a: 1 }), h.describe([1, 'x']), h.describe(new Map([['k', [2]]])), h.describe(new Set([3])), " +
            "h.describe(x => x * 2), h.describe(new Date(5)), h.describe(1), h.describe('s')].join(' | ')"
        // off by default: a NeonValue, as before
        ctx().use { c ->
            c["h"] = ObjectSink()
            assertEquals("NeonValue | NeonValue | NeonValue | NeonValue | NeonValue | NeonValue | Integer:1 | String:s", c.str(script))
        }
        ctx(HostAccess.builder(HostAccess.Level.ALL).allowObjectViews(true).build()).use { c ->
            val h = ObjectSink()
            c["h"] = h
            assertEquals("Map{a=Integer:1} | List[Integer:1,String:x] | Map{k=List[Integer:2]} | Set[Integer:3] | Function | " +
                "Instant:1970-01-01T00:00:00.005Z | Integer:1 | String:s", c.str(script))
            // nested views, a Function that calls back into JS, live writes, and the same object back
            assertEquals("Map{list=List[Integer:1,Map{x=Integer:2}]}", c.str("h.describe({ list: [1, { x: 2 }] })"))
            assertEquals(10, c.eval("h.apply(x => x * 2, 5)").asInt())
            assertEquals("1,true,true", c.str("var o = {}; h.write(o); var f = () => 1; [o.fromJava, h.keep(o) === o, h.keep(f) === f].join()"))
            // a Java collection given a JS object holds the view
            c["jl"] = java.util.ArrayList<Any?>()
            c.eval("jl.add({ y: 3 })")
            assertEquals("Map{y=Integer:3}", h.describe((c.eval("jl").asHostObject<List<Any?>>())[0]))
        }
    }

    @Test
    fun javaTypeNameAndPrint() {
        val lookup = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        val out = ArrayList<String>()
        NeonEngine.builder().hostAccess(lookup).console { level, m -> out.add("$level $m") }
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).exposePrint(true).build()).build().newContext().use { c ->
                c["l"] = arrayListOf(1, 2)
                assertEquals("java.lang.String,int[],undefined,undefined", c.str("""[Java.typeName(Java.type('java.lang.String')), Java.typeName(Java.type('int[]')),
                    Java.typeName(l), Java.typeName('x')].map(String).join()"""))
                c.eval("print('a', 1, [1, 2], { k: 1 }, l, null, undefined); printErr('e', 2)")
            }
        assertEquals(listOf("LOG a 1 1,2 [object Object] [1, 2] null undefined", "ERROR e 2"), out, "as GraalJS's print: String(x), spaces")
        // off by default
        ctx().use { c -> assertEquals("undefined,undefined", c.str("[typeof print, typeof printErr].join()")) }
    }

    @Test
    fun aLongParameterTakesAnyWholeNumberItHolds() {
        // as in GraalJS: 2 ** 60 and -(2 ** 63) are whole numbers a long holds; 2 ** 63 is not
        ctx().use { c ->
            c["h"] = LongSink()
            assertEquals("1152921504606846976,-9223372036854775808,9007199254740994", c.str("[h.take(2 ** 60), h.take(-(2 ** 63)), h.take(2 ** 53 + 2)].join()"))
            assertEquals("TypeError,TypeError", c.str("[2 ** 63, 1.5].map(x => { try { h.take(x); return 'no' } catch (e) { return e.name } }).join()"))
            // NeonValue.asLong: the same whole numbers (2 ** 63 converted to Long.MAX_VALUE before)
            assertEquals(1L shl 60, c.eval("2 ** 60").asLong())
            org.junit.jupiter.api.assertThrows<ArithmeticException> { c.eval("2 ** 63").asLong() }
            // a choice made for one number beyond 2^53 is not remembered for another: 2 ** 64 fits no long
            assertEquals("long 1152921504606846976,TypeError", c.str("[h.pick(2 ** 60), (() => { try { return h.pick(2 ** 64) } catch (e) { return e.name } })()].join()"))
        }
    }

    @Test
    fun aHostFunctionsCheckedExceptionIsAHostError() {
        // a Kotlin or Java lambda may throw a checked exception: it was an InternalError
        NeonEngine.builder().hostAccess(HostAccess.ALL).console(null).build().newContext().use { c ->
            c.setFunction("read") { throw java.io.IOException("no file") }
            assertEquals("java.io.IOException: no file", c.str("try { read() } catch (e) { String(e) }"))
        }
    }

    @Test
    fun newDateOfAHostDateTimeTakesItsInstant() {
        // a ZonedDateTime's text does not parse ("...+09:00[Asia/Seoul]"): it was an Invalid Date
        ctx(sandbox = SandboxPolicy.builder().timeZone("Asia/Seoul").build()).use { c ->
            val seoul = java.time.ZoneId.of("Asia/Seoul")
            c["zoned"] = java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 6_000_000, seoul)
            c["offset"] = java.time.OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, java.time.ZoneOffset.ofHours(9))
            c["instant"] = java.time.Instant.ofEpochMilli(86_400_123L)
            c["local"] = java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5)
            c["day"] = java.time.LocalDate.of(2026, 1, 2)
            c["legacy"] = java.util.Date(172_800_000L)
            c["time"] = java.time.LocalTime.of(3, 4)
            assertEquals("2026-01-01T18:04:05.006Z|2026-01-01T18:04:05.000Z|1970-01-02T00:00:00.123Z|2026-01-01T18:04:05.000Z|2026-01-02T00:00:00.000Z|1970-01-03T00:00:00.000Z",
                c.str("[zoned, offset, instant, local, day, legacy].map(x => new Date(x).toISOString()).join('|')"),
                "a LocalDateTime in the context's time zone, a LocalDate at its UTC midnight (as GraalJS)")
            assertEquals("NaN", c.str("String(new Date(time).getTime())"), "a time of day is no date")
        }
        // the policy first: of an object it shows nothing of, nothing is read
        ctx(HostAccess.NONE).use { c ->
            c["instant"] = java.time.Instant.ofEpochMilli(0)
            assertEquals("NaN", c.str("String(new Date(instant).getTime())"))
        }
    }
}
