package dev.mooner.neonjs.cli

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** The CLI as a process: its options, output and exit status (the code it passes to exitProcess). */
class CliTest {
    @TempDir
    lateinit var dir: File

    private class Run(val status: Int, val out: String, val err: String)

    private fun cli(vararg args: String, env: Map<String, String> = emptyMap()): Run {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val out = File(dir, "out-${System.nanoTime()}.txt")
        val err = File(dir, "err-${System.nanoTime()}.txt")
        val pb = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), "dev.mooner.neonjs.cli.MainKt") + args)
            .redirectOutput(out).redirectError(err)
        pb.environment().putAll(env)
        val p = pb.start()
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail<Unit>("the CLI did not exit: ${out.readText()} ${err.readText()}")
        }
        return Run(p.exitValue(), out.readText().replace("\r\n", "\n").trimEnd(), err.readText().replace("\r\n", "\n").trimEnd())
    }

    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }

    @Test
    fun nodeApisOnlyWithNode() {
        val code = "print(typeof require, typeof Buffer, typeof fetch, typeof WebSocket)"
        assertEquals("undefined undefined function function", cli("-e", code).out)
        assertEquals("function function function function", cli("--node", "-e", code).out)
    }

    @Test
    fun envAndArgv() {
        val env = file("test.env", "# a comment\nexport C='single'\nD=\"two\\nlines\"\nE=plain # a comment\n")
        val script = file("args.js", """
            const process = require('node:process');
            print(JSON.stringify(process.env));
            print(JSON.stringify(process.argv.slice(1)));
        """.trimIndent())
        val r = cli("--node", "--env", "A=1=2", "--env", "B", "--env", "MISSING_FROM_THE_HOST", "--env-file", env.path, script.path, "--", "x", "-y",
            env = mapOf("B" to "from the host"))
        assertEquals(0, r.status, r.err)
        assertEquals(
            """{"C":"single","D":"two\nlines","E":"plain","A":"1=2","B":"from the host"}""" + "\n" + """["${script.absolutePath.replace("\\", "\\\\")}","x","-y"]""",
            r.out,
        )
    }

    @Test
    fun envFilesAsNode() {
        val a = file("a.env", "A=from a\nB=from a\nC=from a\n")
        val b = file("b.env", "B=from b\nC=from b\n")
        val code = "const e = require('node:process').env; print(e.A, '/', e.B, '/', e.C)"
        // a later file wins, and --env over the files
        val r = cli("--node", "--env-file=${a.path}", "--env-file", b.path, "--env", "C=given", "-e", code)
        assertEquals(0, r.status, r.err)
        assertEquals("from a / from b / given", r.out)
        val missing = File(dir, "missing.env").path
        val optional = cli("--node", "--env-file-if-exists", missing, "--env-file-if-exists=${a.path}", "-e", code)
        assertEquals("from a / from a / from a", optional.out)
        assertEquals("$missing not found. Continuing without it.", optional.err)
        // a missing --env-file ends the run before any script, with Node's status
        val required = cli("--node", "--env-file", missing, "-e", "print('ran')")
        assertEquals(9, required.status)
        assertEquals("", required.out)
        assertEquals("neonjs: $missing: not found", required.err)
    }

    @Test
    fun exitStatus() {
        val exit = cli("--node", "-e", "require('node:process').exit(3); print('after')", "-e", "print('next')")
        assertEquals(3, exit.status)
        assertEquals("", exit.out)
        // process.exitCode and the 'exit' event once nothing is left to do, as in Node
        val code = cli("--node", "-e", "const p = require('node:process'); p.on('exit', (c) => print('exit', c)); setTimeout(() => { p.exitCode = 4 }, 5); undefined")
        assertEquals(4, code.status)
        assertEquals("exit 4", code.out)
        assertEquals(0, cli("-e", "1").status)
    }

    @Test
    fun uncaughtErrorsAreReported() {
        val rejection = cli("-e", "Promise.reject(new Error('boom')); undefined")
        assertEquals(1, rejection.status)
        assertTrue(rejection.err.startsWith("Uncaught (in promise) Error: boom"), rejection.err)
        // the loop goes on after an exception in a task
        val task = cli("-e", "setTimeout(() => { throw new Error('late') }, 1); setTimeout(() => print('still'), 20); undefined")
        assertEquals(1, task.status)
        assertEquals("still", task.out)
        assertTrue(task.err.startsWith("Uncaught Error: late"), task.err)
        // node:process' listeners come first
        val handled = cli("--node", "-e", "require('node:process').on('unhandledRejection', (e) => print('handled', e.message)); Promise.reject(new Error('boom')); undefined")
        assertEquals(0, handled.status, handled.err)
        assertEquals("handled boom", handled.out)
    }

    @Test
    fun usageErrors() {
        for (args in listOf(
            arrayOf("--env", "A=1", "-e", "1"),
            arrayOf("-e", "1", "--", "x"),
            arrayOf("--allow-host", "a b", "-e", "1"),
            arrayOf("--allow-address", "not-a-range", "-e", "1"),
            arrayOf("--env"),
            arrayOf("--env-file-if-exists", "x.env", "-e", "1"),
        )) {
            assertEquals(2, cli(*args).status, args.joinToString(" "))
        }
    }

    @Test
    fun theNetworkPolicyRefusesWhatIsNotAllowed() {
        val code = "fetch('https://example.com/').catch((e) => print(e.message, '/', e.cause.message)); undefined"
        val expected = "fetch failed / fetch to example.com is not allowed by the network policy"
        assertEquals(expected, cli("-e", code).out)
        assertEquals(expected, cli("--allow-host", "other.example,*.other.example", "-e", code).out)
    }

    @Test
    fun fetchAndWebSocketThenExit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("hello").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("echo:$text")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }).build())
            server.start()
            val script = file("net.js", """
                const res = await fetch('http://${server.hostName}:${server.port}/');
                print(await res.text());
                const ws = new WebSocket('ws://${server.hostName}:${server.port}/ws');
                ws.onopen = () => ws.send('hi');
                ws.onmessage = (e) => { print(e.data); ws.close(1000) };
                ws.onclose = (e) => print('closed', e.code);
            """.trimIndent())
            // a loopback server needs its address range besides its name, and cleartext; the process ends once the
            // socket is closed
            val r = cli("--allow-host", server.hostName, "--allow-address", "127.0.0.0/8,::1/128", "--allow-insecure", "--module", script.path)
            assertEquals(0, r.status, r.err)
            assertEquals("hello\necho:hi\nclosed 1000", r.out)
        }
    }

    @Test
    fun modulesImportBuiltinsAndFiles() {
        file("dep.mjs", "export const answer = 42;")
        val main = file("main.mjs", """
            import { EventEmitter } from 'node:events';
            import WebSocket from 'ws';
            import { answer } from './dep.mjs';
            print(typeof EventEmitter, WebSocket.OPEN, answer);
        """.trimIndent())
        val r = cli("--node", main.path)
        assertEquals(0, r.status, r.err)
        assertEquals("function 1 42", r.out)
    }

    @Test
    fun nodeOutputAndTimers() {
        val r = cli("--node", "-e", "const p = require('node:process'); p.stdout.write('a'); p.stdout.write('b\\n'); console.log({ n: [1] })",
            "-e", "setInterval(() => {}, 1000).unref(); setTimeout(() => print('done'), 10); undefined")
        assertEquals(0, r.status, r.err)
        assertEquals("ab\n{ n: [ 1 ] }\ndone", r.out)
    }
}
