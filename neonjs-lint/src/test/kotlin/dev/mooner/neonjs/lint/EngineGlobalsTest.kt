package dev.mooner.neonjs.lint

import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.net.NetExtension
import dev.mooner.neonjs.node.NodeExtension
import dev.mooner.neonjs.HostAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** EngineGlobals' names are those the engine's realms have, for each option. */
class EngineGlobalsTest {
    private fun names(b: NeonEngine.Builder): Set<String> =
        b.build().newContext().eval("Object.getOwnPropertyNames(globalThis).join(',')").asString()!!.split(',').toSortedSet()

    @Test
    fun theListsAreTheRealms() {
        val core = names(NeonEngine.builder().console(null).webGlobals(false))
        assertEquals(core, EngineGlobals.ECMASCRIPT.toSortedSet())
        assertEquals(names(NeonEngine.builder().webGlobals(false)) - core, EngineGlobals.CONSOLE)
        val web = names(NeonEngine.builder().console(null).webGlobals(true)) - core
        assertEquals(web, EngineGlobals.WEB.toSortedSet())
        val policy = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        assertEquals(names(NeonEngine.builder().console(null).webGlobals(false).sandbox(policy)) - core, EngineGlobals.JAVA)
        assertEquals(names(NeonEngine.builder().console(null).webGlobals(true).extension(NetExtension())) - core - web, EngineGlobals.NET)
        assertEquals(names(NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension())) - core - web, EngineGlobals.NODE.toSortedSet())
    }

    @Test
    fun ofCombinesThem() {
        val g = EngineGlobals.of(console = true, net = true)
        for (name in listOf("Promise", "console", "setTimeout", "fetch")) assertEquals(GlobalKind.READ_ONLY, g.kind(name), name)
        for (name in listOf("require", "Java", "Intl", "Chat")) assertEquals(null, g.kind(name), name)
        val withApp = g + Globals.of(listOf("Chat"), GlobalKind.WRITABLE)
        assertEquals(GlobalKind.WRITABLE, withApp.kind("Chat"))
    }

    @Test
    fun theModuleIsDeniedToScripts() {
        val location = java.io.File(NeonLint::class.java.protectionDomain.codeSource.location.toURI())
        val paths = if (location.isDirectory) location.walk().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        else java.util.jar.JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
        val names = paths.filter { it.startsWith("dev/mooner/neonjs/lint/") && it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }
        assertTrue(names.size >= 3, "$names")
        for (n in names) assertTrue(HostAccess.ALL.isClassDenied(Class.forName(n, false, javaClass.classLoader)), n)
    }
}
