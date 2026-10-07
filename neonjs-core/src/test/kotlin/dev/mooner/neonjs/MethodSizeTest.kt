package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File

/**
 * HotSpot never JIT-compiles methods whose bytecode exceeds 8000 bytes (-XX:+DontCompileHugeMethods), so such a
 * method in the engine silently runs in the JVM's bytecode interpreter. This guards the interpreter loop and the
 * runtime helpers against growing past that limit.
 */
class MethodSizeTest {
    private val limit = 8000

    @Test
    fun noHugeMethodsInEngineRuntime() {
        val root = File(dev.mooner.neonjs.vm.Interpreter::class.java.protectionDomain.codeSource.location.toURI())
        val packages = listOf("vm", "runtime", "jit", "builtins", "interop", "ext")
        val offenders = ArrayList<String>()
        for (pkg in packages) {
            val dir = File(root, "dev/mooner/neonjs/$pkg")
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                for ((name, size) in codeSizes(f.readBytes())) {
                    if (size > limit) offenders.add("${f.relativeTo(root).path}#$name ($size bytes)")
                }
            }
        }
        assertTrue(offenders.isEmpty(), "methods too large for the JVM JIT: $offenders")
    }

    /** Minimal class-file reader: returns (method name, code length) for every method with a Code attribute. */
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
        repeat(inp.readUnsignedShort()) { // fields
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
                    val codeLen = inp.readInt()
                    out.add(name to codeLen)
                    inp.skipBytes(len - 8)
                } else inp.skipBytes(len)
            }
        }
        return out
    }
}
