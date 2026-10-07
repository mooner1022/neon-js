package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Named IANA time zones in Temporal (core only: no calendar / link provider on this class path). */
class TemporalTimeZoneTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.STRICT) = NeonEngine.builder().sandbox(policy).build().newContext()

    private fun NeonContext.str(src: String) = eval(src).asString()

    @Test
    fun namedZonesAreCaseInsensitiveAndKeepTheirSpelling() {
        ctx().use { c ->
            assertEquals("Europe/Paris", c.str("new Temporal.ZonedDateTime(0n, 'europe/PARIS').timeZoneId"))
            assertEquals("Asia/Calcutta", c.str("new Temporal.ZonedDateTime(0n, 'Asia/Calcutta').timeZoneId"))
            assertEquals("EST", c.str("new Temporal.ZonedDateTime(0n, 'est').timeZoneId"))
            assertEquals("true", c.str("String(Temporal.ZonedDateTime.from('2020-01-01T00:00Z[Etc/GMT]').equals('2020-01-01T00:00Z[UTC]'))"))
            for (bad in listOf("PST", "SystemV/EST5", "Mars/Olympus_Mons")) {
                assertEquals("RangeError", c.str("try { new Temporal.ZonedDateTime(0n, '$bad'); 'none' } catch (e) { e.name }"))
            }
        }
    }

    @Test
    fun offsetsAndDisambiguation() {
        ctx().use { c ->
            // sub-minute LMT offset
            assertEquals("-00:44:30", c.str("Temporal.ZonedDateTime.from('1960-01-01T00:00[Africa/Monrovia]').offset"))
            // gap: 2017-03-12T02:30 does not exist in New York
            val gap = "Temporal.PlainDateTime.from('2017-03-12T02:30').toZonedDateTime('America/New_York'"
            assertEquals("2017-03-12T03:30:00-04:00[America/New_York]", c.str("$gap).toString()"))
            assertEquals("2017-03-12T01:30:00-05:00[America/New_York]", c.str("$gap, { disambiguation: 'earlier' }).toString()"))
            assertEquals("RangeError", c.str("try { $gap, { disambiguation: 'reject' }); 'none' } catch (e) { e.name }"))
            // overlap: 2017-11-05T01:30 happens twice
            val overlap = "Temporal.PlainDateTime.from('2017-11-05T01:30').toZonedDateTime('America/New_York'"
            assertEquals("-04:00", c.str("$overlap).offset"))
            assertEquals("-05:00", c.str("$overlap, { disambiguation: 'later' }).offset"))
            // Pacific/Apia skipped 2011-12-30 entirely
            assertEquals("2011-12-31T00:00:00+14:00[Pacific/Apia]", c.str("Temporal.PlainDate.from('2011-12-30').toZonedDateTime('Pacific/Apia').toString()"))
        }
    }

    @Test
    fun transitions() {
        ctx().use { c ->
            val z = "Temporal.ZonedDateTime.from('2020-01-01T00:00[Europe/London]')"
            assertEquals("2020-03-29T02:00:00+01:00[Europe/London]", c.str("$z.getTimeZoneTransition('next').toString()"))
            assertEquals("2019-10-27T01:00:00+00:00[Europe/London]", c.str("$z.getTimeZoneTransition('previous').toString()"))
            assertEquals("null", c.str("String(new Temporal.ZonedDateTime(0n, '+05:30').getTimeZoneTransition('next'))"))
            assertEquals("null", c.str("String(new Temporal.ZonedDateTime(8640000000000000000000n, 'Europe/London').getTimeZoneTransition('next'))"))
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun sandboxTimeZoneDrivesNow() {
        ctx(SandboxPolicy.builder().timeZone("America/New_York").build()).use { c ->
            assertEquals("America/New_York", c.str("Temporal.Now.timeZoneId()"))
            assertEquals("America/New_York", c.str("Temporal.Now.zonedDateTimeISO().timeZoneId"))
        }
        ctx(SandboxPolicy.builder().timeZone("+05:30").build()).use { c ->
            assertEquals("+05:30", c.str("Temporal.Now.timeZoneId()"))
        }
        ctx(SandboxPolicy.builder().timeZone("Etc/UTC").build()).use { c ->
            assertEquals("UTC", c.str("Temporal.Now.timeZoneId()"))
        }
        ctx(SandboxPolicy.builder().deterministic(1, 0).build()).use { c ->
            assertEquals("UTC", c.str("Temporal.Now.timeZoneId()"))
        }
    }

    @Test
    fun onlyIsoCalendarWithoutProvider() {
        ctx().use { c ->
            assertEquals("RangeError", c.str("try { new Temporal.PlainDate(2024, 1, 1, 'gregory'); 'none' } catch (e) { e.name }"))
            assertEquals("iso8601", c.str("new Temporal.PlainDate(2024, 1, 1, 'ISO8601').calendarId"))
        }
    }
}
