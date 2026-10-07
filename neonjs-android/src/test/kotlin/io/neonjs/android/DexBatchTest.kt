package io.neonjs.android

import io.neonjs.ExecutionMode
import io.neonjs.NeonEngine
import io.neonjs.compiler.CodeBlock
import io.neonjs.compiler.Compiler
import io.neonjs.compiler.Source
import io.neonjs.jit.GeneratedClass
import io.neonjs.jit.JvmCompiler
import io.neonjs.parser.ParseOptions
import io.neonjs.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Stands in for a dex converter on a JVM: the "dex file" is the dex magic followed by the class files. */
private class FakeConverter : DexConverter {
    val calls = AtomicInteger()
    val classes = AtomicInteger()
    override val id: String get() = "fake"
    override val classFileVersion: Int get() = 52

    override fun toDex(classes: List<GeneratedClass>, minSdk: Int): ByteArray {
        calls.incrementAndGet()
        this.classes.addAndGet(classes.size)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.write("dex\n035\u0000".toByteArray())
            o.writeInt(classes.size)
            for (c in classes) {
                o.writeUTF(c.name)
                o.writeInt(c.bytes.size)
                o.write(c.bytes)
            }
        }
        return out.toByteArray()
    }
}

/** Stands in for InMemoryDexClassLoader: defines the class files of a [FakeConverter] "dex file". */
private class FakeLoaders : DexLoaders {
    val created = AtomicInteger()
    override val hasInMemory get() = true
    override val hasFile get() = false

    override fun inMemory(dex: ByteArray, parent: ClassLoader): ClassLoader {
        created.incrementAndGet()
        return object : ClassLoader(parent) {
            private val classes = HashMap<String, ByteArray>()

            init {
                val inp = DataInputStream(dex.inputStream())
                inp.skipBytes(8)
                repeat(inp.readInt()) {
                    val name = inp.readUTF()
                    val bytes = ByteArray(inp.readInt())
                    inp.readFully(bytes)
                    classes[name] = bytes
                }
            }

            override fun findClass(name: String): Class<*> {
                val b = classes[name] ?: throw ClassNotFoundException(name)
                return defineClass(name, b, 0, b.size)
            }
        }
    }

    override fun fromFile(file: File, dir: File, parent: ClassLoader): ClassLoader? = null
}

/** DexCodeDefiner's batching and cache directory, on a JVM (with stand-ins for dx and the dalvik class loaders). */
class DexBatchTest {
    private fun function(src: String, name: String): CodeBlock {
        val script = Compiler.compileScript(Parser.parse(src, ParseOptions()), Source("t.js", src))
        return script.constants.filterIsInstance<CodeBlock>().first { it.name == name }
    }

    private fun blocks() = listOf(
        function("function a(x) { return x + 1 }", "a"),
        function("function b(x) { return x * 2 }", "b"),
        function("function c(x) { return x - 3 }", "c"),
    )

    @Test
    fun aBatchSharesOneDexFileAndLoader() {
        val conv = FakeConverter()
        val loaders = FakeLoaders()
        val definer = DexCodeDefiner(null, 26, conv, loaders)
        val r = JvmCompiler.compileAll(blocks(), definer)
        assertTrue(r.all { it != null })
        assertEquals(2, conv.calls.get(), "the probe class, then the batch")
        assertEquals(2, loaders.created.get())
    }

    @Test
    fun theRealConverterPutsABatchInOneDexFile() {
        val before = DexCheckingDefiner.batches.get()
        val converted = DexCheckingDefiner.converted.get()
        val r = JvmCompiler.compileAll(blocks(), DexCheckingDefiner(DxConverter))
        assertTrue(r.all { it != null })
        assertEquals(2, DexCheckingDefiner.batches.get() - before, "the probe class, then the batch")
        assertEquals(4, DexCheckingDefiner.converted.get() - converted)
    }

    @Test
    fun theCacheDirectoryKeepsConvertedCodeAcrossLaunches(@TempDir dir: File) {
        val conv = FakeConverter()
        val first = JvmCompiler.compileAll(blocks(), DexCodeDefiner(dir, 26, conv, FakeLoaders()))
        assertTrue(first.all { it != null })
        assertEquals(2, conv.calls.get())
        val dexFiles = dir.listFiles { f -> f.name.endsWith(".dex") }!!
        assertEquals(2, dexFiles.size, "the probe's dex file and the batch's")
        assertTrue(dexFiles.none { it.canWrite() }, "dex files are read-only")
        assertEquals(4, dir.listFiles { f -> f.name.endsWith(".idx") }!!.size, "one index entry per class")

        // a new definer, as after a restart: everything comes from the directory
        val loaders = FakeLoaders()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(DexCodeDefiner(dir, 26, conv, loaders)).console(null).build()
        val again = JvmCompiler.compileAll(blocks(), engine.codeDefiner!!)
        assertTrue(again.all { it != null })
        assertEquals(2, conv.calls.get(), "nothing converted again")
        assertEquals(2, loaders.created.get(), "one loader per dex file")
        engine.newContext().use { assertEquals(8, it.eval("function b(x) { return x * 2 } b(4)").asInt()) }
    }

    @Test
    fun aMissingDexFileIsConvertedAgain(@TempDir dir: File) {
        val conv = FakeConverter()
        JvmCompiler.compileAll(blocks(), DexCodeDefiner(dir, 26, conv, FakeLoaders()))
        for (f in dir.listFiles { f -> f.name.endsWith(".dex") }!!) {
            f.setWritable(true)
            assertTrue(f.delete())
        }
        val r = JvmCompiler.compileAll(blocks(), DexCodeDefiner(dir, 26, conv, FakeLoaders()))
        assertTrue(r.all { it != null })
        assertEquals(4, conv.calls.get())
        assertEquals(8, conv.classes.get())
    }
}
