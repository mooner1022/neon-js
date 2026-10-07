package io.neonjs.jit

import java.lang.invoke.MethodHandles
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the JVM class files the engine generates at run time (JIT-compiled functions, `Java.extend` adapters) into
 * classes.
 *
 * The default ([JvmCodeDefiner]) works on standard JVMs. Platforms that cannot define JVM classes provide another
 * implementation, found with [ServiceLoader] or passed to `NeonEngine.Builder.codeDefiner`: on Android the
 * `neonjs-android` module converts each class to dex and loads it with `InMemoryDexClassLoader`. Generated classes
 * reference only public engine members and are self-contained (no generated class refers to another), so each can
 * live in its own class loader.
 */
interface CodeDefiner {
    /** Highest class file major version the generated code may use (52 = Java 8, what dex converters accept). */
    val classFileVersion: Int get() = 61

    /** Whether this definer works on the running platform (consulted when it is found with [ServiceLoader]). */
    fun isSupported(): Boolean = true

    /** Defines the public class [name] (binary name) from [bytes]; the classes it references resolve through [parent]. */
    fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*>
}

/**
 * The definer for standard JVMs: JIT code becomes hidden classes (unloaded with their code block); other classes get a
 * class loader each.
 */
open class JvmCodeDefiner : CodeDefiner {
    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
        if (name.startsWith(JIT_PACKAGE) && parent === JvmCodeDefiner::class.java.classLoader) {
            val l = if (VISIBLE_CLASSES) MethodHandles.privateLookupIn(LOOKUP.defineClass(bytes), LOOKUP)
            // profilers (JFR) leave hidden classes out of stack traces: -Dneonjs.jit.visibleClasses defines ordinary
            // (never unloaded) classes instead, so compiled JS code shows up in profiles
            else LOOKUP.defineHiddenClass(bytes, true)
            return l.lookupClass()
        }
        return OwnLoader(parent).define(name, bytes)
    }

    /** A class loader holding one generated class. */
    class OwnLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    companion object {
        private const val JIT_PACKAGE = "io.neonjs.jit."
        private val LOOKUP = MethodHandles.lookup()
        internal val VISIBLE_CLASSES = System.getProperty("neonjs.jit.visibleClasses") != null
    }
}

/**
 * Defines every class in a class loader of its own, from Java 8 class files: the conditions of a dex-based platform,
 * reproduced on a standard JVM to test that generated code does not depend on JVM-only features (selected with
 * `-Dneonjs.codeDefiner=isolated`).
 */
class IsolatedCodeDefiner : CodeDefiner {
    override val classFileVersion: Int get() = 52

    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> =
        JvmCodeDefiner.OwnLoader(parent).define(name, bytes)
}

object CodeDefiners {
    /**
     * The definer used when an engine is not given one: `-Dneonjs.codeDefiner=isolated|jvm|<class name>`, else the
     * first supported [ServiceLoader] provider, else [JvmCodeDefiner].
     */
    @JvmStatic
    val default: CodeDefiner by lazy {
        when (val p = System.getProperty("neonjs.codeDefiner")) {
            null, "" -> {}
            "isolated" -> return@lazy IsolatedCodeDefiner()
            "jvm" -> return@lazy JvmCodeDefiner()
            else -> return@lazy Class.forName(p).getDeclaredConstructor().newInstance() as CodeDefiner
        }
        val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, CodeDefiner::class.java.classLoader).distinct()
        for (l in loaders) {
            try {
                for (d in ServiceLoader.load(CodeDefiner::class.java, l)) if (d.isSupported()) return@lazy d
            } catch (e: Throwable) {
                // a broken provider must not prevent the engine from running (it falls back to the JVM definer)
            }
        }
        JvmCodeDefiner()
    }

    private val usable = ConcurrentHashMap<CodeDefiner, Boolean>()

    /**
     * Whether [d] can define classes here, tested once with a trivial class before any real code is generated. On a
     * platform without a working definer (Android without neonjs-android) the engine then stays in the interpreter
     * instead of generating bytecode for every function and failing.
     */
    @JvmStatic
    fun isUsable(d: CodeDefiner): Boolean = usable.computeIfAbsent(d) {
        try {
            val bytes = JvmCompiler.probeClass(d.classFileVersion)
            val c = d.define(JvmCompiler.PROBE_CLASS, bytes, CodeDefiner::class.java.classLoader)
            c.getDeclaredConstructor().newInstance() is CompiledCode
        } catch (e: Throwable) {
            if (System.getProperty("neonjs.jit.debug") != null) e.printStackTrace()
            false
        }
    }
}
