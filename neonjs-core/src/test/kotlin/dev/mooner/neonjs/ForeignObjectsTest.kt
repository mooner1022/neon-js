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
