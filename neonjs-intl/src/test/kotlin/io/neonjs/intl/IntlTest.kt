package io.neonjs.intl

import io.neonjs.NeonEngine
import io.neonjs.NeonException
import io.neonjs.SandboxPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntlTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.builder().defaultLocale("en-US").timeZone("UTC").build()) =
        NeonEngine.builder().sandbox(policy).build().newContext()

    private fun eval(code: String, policy: SandboxPolicy? = null): String =
        (if (policy == null) ctx() else ctx(policy)).use { it.eval(code).asString() }

    @Test
    fun intlIsInstalled() {
        assertEquals("1,234.5", eval("new Intl.NumberFormat('en-US').format(1234.5)"))
        assertEquals("object", eval("typeof Intl"))
        assertEquals("[object Intl]", eval("Object.prototype.toString.call(Intl)"))
    }

    @Test
    fun temporalToLocaleString() {
        assertEquals("1/2/2026", eval("Temporal.PlainDate.from('2026-01-02').toLocaleString('en-US')"))
        assertEquals("2026. 1. 2.", eval("Temporal.PlainDate.from('2026-01-02').toLocaleString('ko-KR')"))
        assertEquals("true", eval("String(Temporal.ZonedDateTime.from('2026-01-02T03:04:05[Asia/Seoul]').toLocaleString('en-US').includes('GMT+9'))"))
        assertEquals("TypeError", eval("try { Temporal.Now.zonedDateTimeISO().toLocaleString('en', { timeZone: 'UTC' }) } catch (e) { e.name }"))
        assertEquals("1 hr, 30 min", eval("Temporal.Duration.from({ hours: 1, minutes: 30 }).toLocaleString('en')"))
        // non-ISO calendars come from neonjs-intl
        assertEquals("M01,1", eval("var d = Temporal.PlainDate.from('2026-02-17').withCalendar('chinese'); [d.monthCode, d.day].join()"), "Chinese New Year 2026")
    }

    @Test
    fun intlCanBeDisabledPerEngine() {
        NeonEngine.builder().intl(false).console(null).build().newContext().use { c ->
            assertEquals("undefined", c.eval("typeof Intl").asString())
            assertEquals("1234.5", c.eval("(1234.5).toLocaleString()").asString())
        }
    }

    @Test
    fun configuredDefaultLocaleIsUsed() {
        val de = SandboxPolicy.builder().defaultLocale("de-DE").build()
        assertEquals("de-DE", eval("new Intl.NumberFormat().resolvedOptions().locale", de))
        assertEquals("1.234,5", eval("(1234.5).toLocaleString()", de))
        // an unavailable configured locale falls back to its best available prefix, never to the host's locale
        val unavailable = SandboxPolicy.builder().defaultLocale("de-XX").build()
        assertEquals("de", eval("new Intl.NumberFormat().resolvedOptions().locale", unavailable))
    }

    @Test
    fun deterministicSandboxHidesHostLocale() {
        val det = SandboxPolicy.builder().deterministic(1, 0).build()
        assertEquals("en-US", eval("new Intl.NumberFormat().resolvedOptions().locale", det))
    }

    @Test
    fun overlongTagsAreRejected() {
        val e = assertThrows<NeonException> { eval("Intl.getCanonicalLocales('en-' + 'abcdefgh-'.repeat(200) + 'x')") }
        assertTrue(e.message!!.contains("RangeError"), e.message)
    }

    @Test
    fun requestedLocaleListsAreBounded() {
        val e = assertThrows<NeonException> {
            eval("var a = []; for (var i = 0; i < 2000; i++) a.push('en-x-' + i.toString(36).padStart(2, '0')); Intl.getCanonicalLocales(a).length + ''")
        }
        assertTrue(e.message!!.contains("RangeError"), e.message)
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun hugeArrayLikeLocaleListIsInterruptible() {
        val p = SandboxPolicy.builder().defaultLocale("en-US").maxExecutionTime(300).build()
        val start = System.nanoTime()
        assertThrows<NeonException> { eval("Intl.getCanonicalLocales({ length: 2 ** 40 }).length + ''", p) }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20))
    }

    @Test
    fun icuErrorsDoNotLeakHostClasses() {
        ctx().use { c ->
            val msg = c.eval(
                """
                var out = [];
                for (var f of [
                    () => new Intl.NumberFormat('en', { style: 'unit', unit: 'furlong' }),
                    () => new Intl.Locale('x-private'),
                    () => Intl.getCanonicalLocales('en-u-ca-'),
                    () => new Intl.NumberFormat('en', { style: 'currency', currency: 'XX' }),
                ]) { try { f(); } catch (e) { out.push(String(e)); } }
                out.join('\n')
                """.trimIndent(),
            ).asString()
            assertFalse(msg.contains("com.ibm") || msg.contains("java."), msg)
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun contextsOnManyThreadsFormatIndependently() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 8).map { i ->
                Callable {
                    ctx().use { c ->
                        c.eval(
                            """
                            var nf = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
                            var s = '';
                            for (var k = 0; k < 2000; k++) s = nf.format(k + $i + 0.125) + '|' + (k * 1.5).toLocaleString('de-DE');
                            s
                            """.trimIndent(),
                        ).asString()
                    }
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            for ((i, r) in results.withIndex()) assertEquals("${"%,.2f".format(java.util.Locale.US, 1999 + i + 0.125)}|2.998,5", r)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Same limit as neonjs-core's MethodSizeTest: HotSpot never JIT-compiles methods over 8000 bytes. */
    @Test
    fun noHugeMethods() {
        val root = File(IcuIntlProvider::class.java.protectionDomain.codeSource.location.toURI())
        val offenders = ArrayList<String>()
        File(root, "io/neonjs/intl").walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            for ((name, size) in codeSizes(f.readBytes())) if (size > 8000) offenders.add("${f.name}#$name ($size bytes)")
        }
        assertTrue(offenders.isEmpty(), "methods too large for the JVM JIT: $offenders")
    }

    private fun codeSizes(bytes: ByteArray): List<Pair<String, Int>> {
        val inp = DataInputStream(bytes.inputStream())
        inp.readInt(); inp.readUnsignedShort(); inp.readUnsignedShort()
        val cpCount = inp.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(cpCount)
        var i = 1
        while (i < cpCount) {
            when (val tag = inp.readUnsignedByte()) {
                1 -> utf8[i] = inp.readUTF()
                3, 4 -> inp.readInt()
                5, 6 -> { inp.readLong(); i++ }
                7, 8, 16, 19, 20 -> inp.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> inp.readInt()
                15 -> { inp.readUnsignedByte(); inp.readUnsignedShort() }
                else -> error("bad constant pool tag $tag")
            }
            i++
        }
        inp.readUnsignedShort(); inp.readUnsignedShort(); inp.readUnsignedShort()
        repeat(inp.readUnsignedShort()) { inp.readUnsignedShort() }
        repeat(inp.readUnsignedShort()) {
            inp.readUnsignedShort(); inp.readUnsignedShort(); inp.readUnsignedShort()
            repeat(inp.readUnsignedShort()) { inp.readUnsignedShort(); inp.skipBytes(inp.readInt()) }
        }
        val out = ArrayList<Pair<String, Int>>()
        repeat(inp.readUnsignedShort()) {
            inp.readUnsignedShort()
            val name = utf8[inp.readUnsignedShort()] ?: "?"
            inp.readUnsignedShort()
            repeat(inp.readUnsignedShort()) {
                val attrName = utf8[inp.readUnsignedShort()]
                val len = inp.readInt()
                if (attrName == "Code") {
                    inp.readUnsignedShort(); inp.readUnsignedShort()
                    val codeLen = inp.readInt()
                    out.add(name to codeLen)
                    inp.skipBytes(len - 8)
                } else inp.skipBytes(len)
            }
        }
        return out
    }
}
