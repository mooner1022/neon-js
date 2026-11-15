package dev.mooner.neonwasm.wasi

import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonwasm.Json
import dev.mooner.neonwasm.WasmContext
import dev.mooner.neonwasm.WasmModule
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * WebAssembly/wasi-testsuite's preview 1 tests (C, Rust and AssemblyScript programs, prebuilt; tools/wasi-testsuite.sh
 * fetches them): each program run as its JSON specification says (arguments, environment, a directory copied into
 * the in-memory file system and preopened as `/`), its exit code, and its stdout and stderr where the specification
 * gives them, compared as the testsuite's own runner does. A test that fails is an error unless
 * wasi-known-failures.txt lists it (`suite/name`, the suite being c, rust or assemblyscript).
 */
class WasiTestsuiteTest {
    @TestFactory
    fun testsuite(): List<DynamicNode> {
        val root = File(System.getProperty("neonwasm.wasiTestsuite") ?: "third_party/wasi-testsuite", "tests")
        if (!root.isDirectory) {
            return listOf(dynamicTest("WASI testsuite") { assumeTrue(false, "no $root: tools/wasi-testsuite.sh fetches it") })
        }
        val known = HashSet<String>()
        System.getProperty("neonwasm.wasiKnown")?.let { File(it) }?.takeIf { it.isFile }?.forEachLine { l ->
            val t = l.substringBefore('#').trim()
            if (t.isNotEmpty()) known.add(t)
        }
        val suites = root.listFiles()!!.filter { File(it, "testsuite/wasm32-wasip1").isDirectory }.sortedBy { it.name }
        return suites.map { s -> suite(s.name, File(s, "testsuite/wasm32-wasip1"), known) }
    }

    private fun suite(name: String, dir: File, known: Set<String>): DynamicContainer {
        val tests = dir.listFiles { f -> f.extension == "wasm" }!!.sortedBy { it.name }
        return dynamicContainer(name, tests.map { wasm ->
            val test = wasm.nameWithoutExtension
            dynamicTest(test) {
                val key = "$name/$test"
                val error = try {
                    run(wasm)
                } catch (e: Throwable) {
                    "${e.javaClass.simpleName}: ${e.message}"
                }
                when {
                    error == null && key in known -> println("NOW PASSING $key")
                    error != null && key !in known -> fail<Unit>(error)
                    error != null -> println("known failure $key: $error")
                }
            }
        })
    }

    private class Spec(var args: List<String> = emptyList(), var env: Map<String, String> = emptyMap(), var root: String? = null,
                       var exitCode: Int = 0, val reads: MutableList<Pair<String, String>> = ArrayList())

    @Suppress("UNCHECKED_CAST")
    private fun spec(wasm: File): Spec? {
        val f = File(wasm.parentFile, wasm.nameWithoutExtension + ".json")
        val s = Spec()
        if (!f.isFile) return s
        val j = Json.parse(f.readText()) as Map<String, Any?>
        if ((j["proposals"] as List<*>?)?.isNotEmpty() == true) return null
        val ops = j["operations"] as List<Map<String, Any?>>?
        if (ops == null) {
            (j["args"] as List<String>?)?.let { s.args = it }
            (j["env"] as Map<String, String>?)?.let { s.env = it }
            s.root = j["root"] as String?
            (j["exit_code"] as Double?)?.let { s.exitCode = it.toInt() }
            (j["stdout"] as String?)?.let { s.reads.add("stdout" to it) }
            (j["stderr"] as String?)?.let { s.reads.add("stderr" to it) }
        } else for (op in ops) when (op["type"]) {
            "run" -> {
                (op["args"] as List<String>?)?.let { s.args = it }
                (op["env"] as Map<String, String>?)?.let { s.env = it }
                s.root = op["root"] as String?
            }
            "wait" -> (op["exit_code"] as Double?)?.let { s.exitCode = it.toInt() }
            "read" -> s.reads.add(((op["id"] as String?) ?: "stdout") to ((op["payload"] as String?) ?: ""))
            else -> return null
        }
        return s
    }

    /** Runs one program; null where it did what its specification says, else what it did not. */
    private fun run(wasm: File): String? {
        val spec = spec(wasm) ?: run {
            assumeTrue(false, "needs a proposal or an operation this runner does not have")
            return null
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val b = WasiConfig.builder().args(listOf(wasm.name) + spec.args).stdin(ByteArrayInputStream(ByteArray(0)))
            .stdout(out).stderr(err)
        for ((k, v) in spec.env) b.env(k, v)
        spec.root?.let { b.preopen("/", WasiFs.fromHost(File(wasm.parentFile, it).toPath()).root) }
        val code = Wasi(b.build()).run(WasmContext(VmConfig().apply { maxExecutionMillis = 60_000 }), WasmModule.decode(wasm.readBytes()))
        val problems = ArrayList<String>()
        if (code != spec.exitCode) problems.add("exit code $code, not ${spec.exitCode}")
        for ((id, payload) in spec.reads) {
            val got = (if (id == "stderr") err else out).toByteArray()
            val want = payload.toByteArray(Charsets.UTF_8)
            // (as the testsuite's runner reads them: as many bytes as expected)
            if (!got.copyOf(minOf(got.size, want.size)).contentEquals(want) || got.size < want.size) {
                problems.add("$id ${String(got, Charsets.UTF_8).take(200)}, not $payload")
            }
        }
        if (problems.isEmpty()) return null
        return problems.joinToString("; ") +
            (if (err.size() > 0) " (stderr: ${String(err.toByteArray(), Charsets.UTF_8).take(500)})" else "")
    }
}
