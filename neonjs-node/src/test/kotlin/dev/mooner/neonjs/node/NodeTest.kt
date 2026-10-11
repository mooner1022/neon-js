package dev.mooner.neonjs.node

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NodeTest {
    private fun ctx(options: NodeOptions = NodeOptions.DEFAULT): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension(options)).build().newContext()

    @Test
    fun needsTheWebGlobals() {
        val e = assertThrows<IllegalStateException> { NeonEngine.builder().console(null).extension(NodeExtension()).build().newContext() }
        assertTrue(e.message!!.contains("webGlobals"), e.message)
    }

    @Test
    fun requireLoadsBuiltInAndHostModules() {
        ctx().use { c ->
            assertEquals("true,true,true,true", c.eval("""
                const m = require('node:module');
                [m === require('module'), m.builtinModules.includes('module'), m.isBuiltin('node:module'), m.createRequire('/x.js') === require].join()
            """).asString())
            assertEquals("MODULE_NOT_FOUND,ERR_INVALID_ARG_TYPE", c.eval("""
                const codes = [];
                try { require('left-pad') } catch (e) { codes.push(e.code) }
                try { require(1) } catch (e) { codes.push(e.code) }
                codes.join()
            """).asString())
            // ES modules see the same exports
            val ns = c.evalModule("import m, { isBuiltin } from 'node:module'; export const r = [m === require('module'), isBuiltin('fs/nope')]", "main.mjs")
            assertEquals("true,false", ns.getMember("r").toString())
        }
        // a host module comes first, for require as for import
        ctx().use { c ->
            c.defineModule("node:module", mapOf("replaced" to true))
            assertEquals(true, c.eval("require('node:module').replaced").asBoolean())
        }
    }

    @Test
    fun captureStackTraceLeavesOutTheFramesAboveTheConstructor() {
        ctx().use { c ->
            val stack = c.eval("""
                class AppError extends Error {
                    constructor(message) { super(message); this.name = 'AppError'; Error.captureStackTrace(this, AppError) }
                }
                function fail() { return new AppError('boom') }
                fail().stack
            """).asString()
            assertTrue(stack.startsWith("AppError: boom\n    at fail"), stack)
            assertFalse(stack.contains("AppError (") || stack.contains("at new AppError"), stack)
            // any object, Error.prototype.toString for the header, a limit
            assertEquals("Error|true", c.eval("var o = {}; Error.captureStackTrace(o); [o.stack.split('\\n')[0], Object.getOwnPropertyDescriptor(o, 'stack').enumerable === false].join('|')").asString())
            assertEquals("x", c.eval("Error.stackTraceLimit = 0; var p = { name: 'x', message: '' }; Error.captureStackTrace(p); p.stack").asString())
        }
    }

    @Test
    fun globalsFollowOptionA() {
        ctx().use { c ->
            // global and require; no process global (scripts import node:process)
            assertEquals("true,function,undefined", c.eval("[global === globalThis, typeof require, typeof process].join()").asString())
            assertEquals("undefined,undefined", c.eval("new ShadowRealm().evaluate('[typeof require, typeof global].join()')").asString())
        }
    }

    @Test
    fun theModuleIsDeniedToScripts() {
        // every compiled class of the module: scripts that get hold of one see nothing of it
        val location = java.io.File(NodeExtension::class.java.protectionDomain.codeSource.location.toURI())
        val paths = if (location.isDirectory) location.walk().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        else java.util.jar.JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
        val names = paths.filter { it.startsWith("dev/mooner/neonjs/node/") && it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }
        assertTrue(names.size >= 3, "$names")
        for (n in names) assertTrue(HostAccess.ALL.isClassDenied(Class.forName(n, false, javaClass.classLoader)), n)
    }
}
