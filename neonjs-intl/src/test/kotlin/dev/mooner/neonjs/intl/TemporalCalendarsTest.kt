package dev.mooner.neonjs.intl

import com.ibm.icu.util.Calendar
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TemporalCalendarsTest {
    private val provider = IcuTemporalCalendarProvider()

    private fun source(id: String): TemporalCalendarSource = provider.calendarSource(id)!!

    private fun yearOf(s: TemporalCalendarSource, day: Long): Int {
        var y = s.estimateYear(day)
        repeat(50) {
            val inf = s.yearInfo(y)
            if (day < inf.start) y-- else if (day >= inf.end) y++ else return y
        }
        fail<Unit>("estimateYear too far off for day $day")
        return y
    }

    /** The hand-rolled arithmetic calendars agree with ICU (Hebrew only for years >= 1: ICU's cache misbehaves below). */
    @Test
    fun arithmeticCalendarsMatchIcu() {
        val r = Random(7)
        for (id in listOf("coptic", "ethiopic", "indian", "islamic-civil", "islamic-tbla", "hebrew", "persian", "islamic-umalqura")) {
            val s = source(id)
            val icu = Calendar.getInstance(TimeZone.GMT_ZONE, ULocale("en@calendar=$id"))
            repeat(3000) { i ->
                var day = if (i % 2 == 0) (r.nextDouble() * 2e8 - 1e8).toLong() else (r.nextDouble() * 2e5 - 1e5).toLong()
                if (id == "hebrew" && day < -2_000_000) day = -day % 3_000_000
                icu.clear()
                icu.set(Calendar.JULIAN_DAY, (day + Civil.JD_EPOCH).toInt())
                val y = yearOf(s, day)
                val inf = s.yearInfo(y)
                var m = 1
                while (m < inf.monthCount && day >= inf.monthStarts[m]) m++
                assertEquals(icu.get(Calendar.EXTENDED_YEAR), y, "$id year of $day")
                assertEquals(icu.get(Calendar.ORDINAL_MONTH) + 1, m, "$id month of $day")
                assertEquals(icu.get(Calendar.DAY_OF_MONTH).toLong(), day - inf.monthStarts[m - 1] + 1, "$id day of $day")
                val code = inf.monthCodes[m - 1]
                assertEquals(icu.temporalMonthCode, "M%02d".format(code shr 1) + if (code and 1 != 0) "L" else "", "$id code of $day")
            }
        }
    }

    /** Year boundaries and month counts are consistent in every calendar, across the ICU / table / model seams. */
    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun yearStructureIsConsistent() {
        for (id in provider.calendarIds()) {
            val s = source(id)
            val years = (-272_000..283_000 step 997).toMutableList()
            years += listOf(1290..1310, 1590..1610, 1690..1710, 1895..1905, 2095..2105, 2295..2305, -3..3).flatten()
            for (y in years) {
                val a: CalendarYearInfo = s.yearInfo(y)
                val b: CalendarYearInfo = s.yearInfo(y + 1)
                assertEquals(a.end, b.start, "$id: year $y must end where ${y + 1} starts")
                assertEquals(a.monthCount.toLong(), s.monthsBeforeYear(y + 1) - s.monthsBeforeYear(y), "$id: month count of $y")
                for (m in 1..a.monthCount) {
                    val len = a.monthStarts[m] - a.monthStarts[m - 1]
                    assertTrue(len in 5..31, "$id: month $m of $y has $len days")
                    if (m > 1) assertTrue(a.monthCodes[m - 1] > a.monthCodes[m - 2], "$id: month codes of $y are ordered")
                }
                assertEquals(2, a.monthCodes[0], "$id: $y starts with M01")
                assertTrue(abs(yearOf(s, a.start) - y) == 0, "$id: estimate for $y")
            }
        }
    }

    @Test
    fun timeZoneLinksResolveToPrimaryIdentifiers() {
        assertEquals("Asia/Kolkata", provider.primaryTimeZoneId("Asia/Calcutta"))
        assertEquals("Europe/Kyiv", provider.primaryTimeZoneId("Europe/Kiev"))
        assertEquals("Europe/Bratislava", provider.primaryTimeZoneId("Europe/Bratislava"))
        assertNull(provider.primaryTimeZoneId("Not/AZone"))
    }

    private fun eval(src: String): String =
        NeonEngine.builder().sandbox(SandboxPolicy.STRICT).build().newContext().use { it.eval(src).asString() }

    @Test
    fun engineUsesTheProvider() {
        assertEquals("5784 M06 5 am", eval($$"const d = Temporal.PlainDate.from('2024-03-15').withCalendar('hebrew'); `${d.year} ${d.monthCode} ${d.day} ${d.era}`"))
        assertEquals("2033 13 M11L", eval($$"const d = Temporal.PlainDate.from({ calendar: 'chinese', year: 2033, month: 12, day: 1 }); `${d.year} ${d.monthsInYear} ${d.monthCode}`"))
        assertEquals("true", eval("String(Temporal.ZonedDateTime.from('2020-01-01T00:00[Asia/Calcutta]').equals('2020-01-01T00:00[Asia/Kolkata]'))"))
        assertEquals("false", eval("String(Temporal.ZonedDateTime.from('2020-01-01T00:00[Europe/Prague]').equals('2020-01-01T00:00[Europe/Bratislava]'))"))
        // SystemTimeZoneIdentifier reports the primary identifier of the sandbox's zone
        val policy = SandboxPolicy.builder().timeZone("Asia/Calcutta").build()
        NeonEngine.builder().sandbox(policy).build().newContext().use { c ->
            assertEquals("Asia/Kolkata", c.eval("Temporal.Now.timeZoneId()").asString())
        }
    }

    /** Calendar sources are shared JVM-wide; hammer them from several threads at once. */
    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun concurrentUse() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 12).map { t ->
                pool.submit<String> {
                    eval(
                        """
                        let s = 0;
                        for (const cal of ['chinese', 'dangi', 'persian', 'islamic-umalqura', 'hebrew']) {
                          for (let y = 1700 + $t; y < 2300; y += 7) {
                            const d = Temporal.PlainDate.from({ calendar: cal, year: y, month: 1, day: 1 });
                            s += d.daysInYear + d.add({ months: 13 }).month;
                          }
                        }
                        String(s)
                        """.trimIndent(),
                    )
                }
            }
            val results = tasks.map { it.get() }
            // the same years in a different order give the same structure
            val single = (0 until 12).map { t ->
                eval(
                    """
                    let s = 0;
                    for (const cal of ['chinese', 'dangi', 'persian', 'islamic-umalqura', 'hebrew']) {
                      for (let y = 1700 + $t; y < 2300; y += 7) {
                        const d = Temporal.PlainDate.from({ calendar: cal, year: y, month: 1, day: 1 });
                        s += d.daysInYear + d.add({ months: 13 }).month;
                      }
                    }
                    String(s)
                    """.trimIndent(),
                )
            }
            assertEquals(single, results)
        } finally {
            pool.shutdownNow()
        }
    }
}
