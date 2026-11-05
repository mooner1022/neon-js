package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File

/**
 * HotSpot does not compile methods of more than 8,000 bytes of bytecode, which then run in the JVM's own interpreter:
 * NeonPy's dispatch loop (PyInterpreter.run) and its runtime grow with the language, and must stay under that.
 */
class PyMethodSizeTest {
    private val limit = 8000

    @Test
    fun noMethodIsTooLargeForTheJit() {
        val root = File(PyInterpreter::class.java.protectionDomain.codeSource.location.toURI())
        val offenders = ArrayList<String>()
        File(root, "dev/mooner/neonpython").walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            for ((name, size) in codeSizes(f.readBytes())) if (size > limit) offenders.add("${f.name}#$name ($size bytes)")
        }
        assertTrue(offenders.isEmpty(), "methods too large for the JIT: $offenders")
    }

    /** (method name, code length) for every method with a Code attribute: a minimal class-file reader. */
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
                val attr = utf8[inp.readUnsignedShort()]
                val len = inp.readInt()
                if (attr == "Code") {
                    inp.readUnsignedShort(); inp.readUnsignedShort()
                    out.add(name to inp.readInt())
                    inp.skipBytes(len - 8)
                } else inp.skipBytes(len)
            }
        }
        return out
    }
}
