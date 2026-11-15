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

/**
 * Host objects as GraalJS shows them to scripts, where NeonJS fell short: JS Maps and Sets
 * as Java ones. Expectations are GraalJS 25.5's answers to the same scripts, except
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
}
