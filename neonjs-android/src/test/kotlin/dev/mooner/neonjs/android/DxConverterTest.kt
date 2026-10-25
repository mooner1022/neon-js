package dev.mooner.neonjs.android

import com.android.dx.cf.direct.DirectClassFile
import com.android.dx.cf.direct.StdAttributeFactory
import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Compiler
import dev.mooner.neonjs.compiler.Source
import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.GeneratedClass
import dev.mooner.neonjs.jit.IsolatedCodeDefiner
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.jit.opt.T2
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.Parser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** dx's translation of large compiled functions, measured in the dex it writes. */
class DxConverterTest {
    /** Keeps the classes it defines (as class files for dx: version 52). */
    private class Capturing : CodeDefiner {
        val classes = ArrayList<GeneratedClass>()
        private val isolated = IsolatedCodeDefiner()
        override val classFileVersion: Int get() = 52
        override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
            classes.add(GeneratedClass(name, bytes))
            return isolated.define(name, bytes, parent)
        }
    }

    /** The class of function [name] of [src], compiled by the baseline compiler, or the optimizing tier's with [t2]. */
    private fun compiled(src: String, name: String, t2: Boolean = false): GeneratedClass {
        val script = Compiler.compileScript(Parser.parse(src, ParseOptions()), Source("t.js", src))
        val fn = script.constants.filterIsInstance<CodeBlock>().first { it.name == name }
        val definer = Capturing()
        if (t2) T2.compile(fn, definer)!! else JvmCompiler.compile(fn, definer)!!
        return definer.classes.last()
    }

    @Test
    fun largeFunctionsStayUnderArtsCompileLimit() {
        // the optimizing tier gives every value a JVM local, which unoptimized dx makes a register of its own: a
        // function of 50 statements has code under the tier's limit (T2.MAX_CODE) but past ART's unoptimized
        val body = (0 until 50).joinToString("\n") { k -> "  s = s * 1.000001 + a[(i + $k) % 7] - (s > 1e6 ? $k : 1);" }
        val cls = compiled("function big(a, i) { var s = 0;\n$body\n  return s; }", "big", t2 = true)
        val plain = largestCodeItem(DxConverter.toDex(listOf(cls), 26, Int.MAX_VALUE))
        val optimized = largestCodeItem(DxConverter.toDex(listOf(cls), 26, 9000))
        assertTrue(plain > ART_HUGE_METHOD, "unoptimized: $plain code units, optimized $optimized")
        assertTrue(optimized < ART_HUGE_METHOD && optimized * 2 < plain, "optimized: $optimized code units, unoptimized $plain")
    }

    @Test
    fun smallFunctionsAreNotOptimized() {
        val cls = compiled("function small(a, b) { return a * b + 1 }", "small")
        val dex = DxConverter.toDex(listOf(cls), 26, 9000)
        assertTrue(dex.contentEquals(DxConverter.toDex(listOf(cls), 26, Int.MAX_VALUE)))
    }

    @Test
    fun theSizeEstimateIsNoLessThanWhatDxMakes() {
        // the estimate decides which classes are optimized: one under what dx makes could leave a method past ART's
        // limit; checked over code of both tiers, with few locals and with hundreds
        val shapes = listOf(
            "  s = s * 1.000001 + a[(i + K) % 7] - (s > 1e6 ? K : 1);",
            "  o.x = o.x + a[K % 7] * s; s = s + o.y;",
            "  if (a[K % 7] > s) s = s - K; else s = s + a.length;",
        )
        for (shape in shapes) for (n in listOf(10, 25, 40, 80)) for (t2 in listOf(false, true)) {
            val body = (0 until n).joinToString("\n") { k -> shape.replace("K", k.toString()) }
            val cls = try {
                compiled("function big(a, i, o) { var s = 0;\n$body\n  return s; }", "big", t2)
            } catch (_: NullPointerException) {
                continue // past the optimizing tier's limit
            }
            val cf = DirectClassFile(cls.bytes, cls.name.replace('.', '/') + ".class", true)
            cf.setAttributeFactory(StdAttributeFactory.THE_ONE)
            val plain = largestCodeItem(DxConverter.toDex(listOf(cls), 26, Int.MAX_VALUE))
            assertTrue(DxConverter.unoptimizedSize(cf) >= plain, "$n of '$shape' (t2 $t2): estimated ${DxConverter.unoptimizedSize(cf)}, made $plain")
        }
    }

    private companion object {
        /** ART does not compile a method of more code units than this (CompilerOptions' huge method threshold). */
        const val ART_HUGE_METHOD = 10_000

        /** The `insns_size` of the largest code item in dex file [dex]. */
        fun largestCodeItem(dex: ByteArray): Int {
            val r = Reader(dex)
            r.pos = r.int(0x34) // map_list
            val n = r.u4()
            var codeOff = -1
            var codeCount = 0
            repeat(n) {
                val type = r.u2()
                r.u2()
                val size = r.u4()
                val off = r.u4()
                if (type == 0x2001) { codeOff = off; codeCount = size }
            }
            check(codeOff >= 0) { "no code items" }
            r.pos = codeOff
            var max = 0
            repeat(codeCount) {
                r.pos = (r.pos + 3) and 3.inv()
                r.u2(); r.u2(); r.u2()
                val tries = r.u2()
                r.u4()
                val insns = r.u4()
                max = maxOf(max, insns)
                r.pos += insns * 2
                if (tries > 0) {
                    if (insns % 2 == 1) r.pos += 2
                    r.pos += tries * 8
                    repeat(r.uleb()) {
                        val handlers = r.sleb()
                        repeat(Math.abs(handlers)) { r.uleb(); r.uleb() }
                        if (handlers <= 0) r.uleb()
                    }
                }
            }
            return max
        }
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun int(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
        fun u2() = ((b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8)).also { pos += 2 }
        fun u4() = int(pos).also { pos += 4 }
        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val v = b[pos++].toInt() and 0xff
                result = result or ((v and 0x7f) shl shift)
                if (v < 0x80) return result
                shift += 7
            }
        }
        fun sleb(): Int {
            var result = 0
            var shift = 0
            var v: Int
            do {
                v = b[pos++].toInt() and 0xff
                result = result or ((v and 0x7f) shl shift)
                shift += 7
            } while (v >= 0x80)
            return if (shift < 32 && (v and 0x40) != 0) result or (-1 shl shift) else result
        }
    }
}
