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
            assertEquals("object,object,object,object,object,object", c.str("[Packages, java, javax, com, org, edu].map(x => typeof x).join()"))
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
