package dev.mooner.neonjs

import dev.mooner.neonjs.unicode.Idna
import dev.mooner.neonjs.unicode.Punycode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** UTS #46 ToASCII as the URL Standard runs it (WPT's IdnaTestV2 covers it at length through `new URL`). */
class IdnaTest {
    @Test
    fun mapsNormalizesAndEncodes() {
        assertEquals("xn--bcher-kva.de", Idna.toAscii("Bücher.DE"))
        // nontransitional: ß stays (a deviation), and so does ς
        assertEquals("xn--fa-hia.de", Idna.toAscii("faß.de"))
        assertEquals("xn--ls8h.la", Idna.toAscii("💩.la"))
        // full stops of other scripts separate labels
        assertEquals("xn--fiqs8s.xn--fiqz9s", Idna.toAscii("中国。中國"))
        // an ASCII domain is only lower-cased, even with a label that is no valid IDNA
        assertEquals("a.b.xn--pokxncvks", Idna.domainToAscii("A.b.XN--pokxncvks"))
        assertNull(Idna.domainToAscii(""))
    }

    @Test
    fun rejectsWhatTheValidityCriteriaReject() {
        assertNull(Idna.toAscii("́a.com"), "a label beginning with a combining mark")
        assertNull(Idna.toAscii("a‌b.com"), "ZWNJ outside the ContextJ rules")
        assertNotNull(Idna.toAscii("क्‌ष.com"), "ZWNJ after a virama")
        assertNull(Idna.toAscii("אa.com"), "a right-to-left label with a left-to-right letter")
        assertNull(Idna.toAscii("xn--a.ä"), "invalid Punycode")
        assertNull(Idna.toAscii("xn--abc-.ä"), "a label that decodes to ASCII only")
    }

    @Test
    fun punycodeRoundTrips() {
        for (s in listOf("bücher", "ü", "中国", "💩", "ab-ç-d")) assertEquals(s, Punycode.decode(Punycode.encode(s)!!))
        assertNull(Punycode.decode("ü"))
        assertNull(Punycode.decode("99999999999"))
    }
}
