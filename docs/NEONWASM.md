# NeonWasm: WebAssembly on the Neon execution core

Status (2026-11-06): the first slice is done. It has a decoder, a validator and an interpreter for WebAssembly 2.0 without
SIMD, and all of the 2.0 core spec tests it can read pass. WASI preview 1 runs over an in-memory file system (§6), and
all of the WASI testsuite's preview 1 programs pass. Module `neonwasm`, package `dev.mooner.neonwasm`. It depends on
the execution core (`neon-vm`) only: no JavaScript.

NeonWasm is the wasm runtime of docs/PYTHON_PLATFORM.md §5. C libraries and extensions are compiled ahead of time with
wasi-sdk to `.wasm` and run on NeonWasm, on the JVM and on Android alike. The runtime is to run interpreted first, then
translated to JVM bytecode (dex on Android) as a dialect of the neutral core (docs/IR_NEUTRALITY.md). The same runtime
can later give NeonJS its `WebAssembly` API.

## 1. The first slice

| file | what |
|---|---|
| `WasmTypes.kt` | value types (their encodings), function types (equal by structure), limits, table/memory/global types; the errors |
| `WasmModule.kt` | a decoded and validated module: `WasmModule.decode(bytes)` |
| `WasmDecoder.kt` | the binary format (§5): LEB128s with their limits, sections in order and of exact sizes, UTF-8 names, constant expressions |
| `WasmValidator.kt` | validation (§3, the algorithm of appendix A.3), compiling each body to the interpreter's code as it goes |
| `Code.kt` | that code: instructions with decoded immediates, blocks gone, branches resolved (`Op`) |
| `Interpreter.kt` | the dispatch loop; the less common numeric instructions (`Numeric`) and the bulk ones (`Bulk`) |
| `WasmRuntime.kt` | memories, tables, globals, functions (a module's or the host's), little-endian access |
| `WasmInstance.kt` | `WasmContext` (where code runs, within the core's limits) and instantiation (§4.5.4) |

Supported: the MVP with multi-value, bulk memory, sign extension, non-trapping float-to-int conversions and reference
types (several tables, `funcref`/`externref`, the `table.*` instructions). This is what wasi-sdk emits by default.

Not yet supported:
- SIMD (a `v128` is malformed here);
- threads;
- WebAssembly 3.0's features (exceptions, tail calls, GC, memory64, multiple memories);
- the text format;
- the JS API.

## 2. How it works

- **Decoding and validation happen at once.** `WasmModule.decode` rejects a malformed module
  (`WasmMalformedException`) or an invalid one (`WasmInvalidException`). A module that decodes can always be
  instantiated, given its imports.
- **Validation compiles each body.** The validator knows the operand stack's height at every instruction. So each
  branch gets the position it jumps to and the height its values move to, and blocks leave nothing at run time:
  - a branch whose values are already in place is a plain jump;
  - `if` is a conditional jump, and `else` a jump to the end;
  - unreachable code is not emitted.
- **Values.** All of a context's frames share one `long[]` holding numbers' bits, with an `Object[]` beside it for
  references. i32 and f32 values sit in the low 32 bits.
  - Reinterpretations and `i32.wrap_i64` are therefore no code at all.
  - Floats keep their exact bits: `neg`, `abs` and `copysign` are bit operations.
  - The roundings (`ceil`, `floor`, `trunc`, `nearest`, `sqrt`) and `min`/`max` quiet a signaling NaN, as wasm
    requires. Java's `Math.floor` and `Math.ceil` return the NaN unchanged, so this is done explicitly.
- **Calls.** Each wasm call is one JVM call of the interpreter. The callee's frame starts at its arguments on the
  shared stack, so nothing is copied. A host function's arguments are read from there, and a call from the host
  starts above the frames in use, so wasm → host → wasm re-entry works.
- **Dispatch size.** The dispatch method is about 4,300 bytes of bytecode, under HotSpot's 8,000-byte limit on methods
  it compiles.
- **Memory.**
  - A memory is a `byte[]` of whole pages, so it can grow to 32,767 pages (a JVM array's limit), not wasm32's 65,536.
    Past that, `memory.grow` gives −1, as wasm allows.
  - Loads and stores use little-endian byte-array VarHandles on the JVM. On ART, values are assembled from bytes,
    because ART's view accesses are slow at unaligned addresses (as for NeonJS's typed arrays: `BufferOps`).
- **Limits** (the execution core's `VmAgent`, from `VmConfig`):
  - Call depth is limited by `maxCallDepth`: past it the call is a trap, "call stack exhausted", and so is a JVM
    `StackOverflowError`.
  - `checkInterrupt()` runs at loop headers every 4,096 iterations and at calls every 1,024. It enforces the time
    limit, the instruction budget, the allocation limit and the host's interrupt. These end the execution with a
    `TerminationException`, which is not a trap.
  - `memory.grow` is charged against the allocation limit beforehand; where the limit refuses, the result is −1.
- **The host's API** (not a stable API yet):
  - `WasmContext(config).instantiate(module, imports)`, then `instance.function(name)!!.call(args)`.
  - Values cross as `Int` (i32), `Long` (i64), `Float` (f32), `Double` (f64), and references as they are: a
    `WasmFunction` for funcref, any object for externref, null for none.
  - `HostFunction(type) { args -> results }` is a function a module can import.
  - The spec tests use the raw path (`invokeRaw`), which keeps NaN payloads exact. The typed path goes through
    `Float`/`Double`.

## 3. Testing

**The spec tests.**
- **Source:** the core tests of WebAssembly/spec at its tag `wg-2.0` (fffc6e12, `WASM_SPEC_COMMIT` in ci.yml). These
  are the 90 files of `test/core`; its `simd/` directory is left out.
- **Conversion:** `wasm-tools json-from-wast` (wasm-tools 1.263.0, `WASM_TOOLS_VERSION`) converts them. Locally this is
  `tools/wasm-spec.sh`, which `tools/check.sh` runs; CI downloads wasm-tools' release binary. Without wasm-tools,
  `SpecTest` is skipped.
- **Runner:** `SpecTest` is one JUnit test per file, running its commands in order. It handles named and registered
  modules and the `spectest` host module.
- **Checks:**
  - `assert_return` compares bits, with `nan:canonical` / `nan:arithmetic` where the test says so.
  - Traps are compared by message.
  - `assert_invalid` and `assert_malformed` check that the module is rejected. Their messages are compared too, and
    mismatches are counted, not failed.
- **Known failures:** `neonwasm/spec-known-failures.txt` (file:line). `SpecTest` reports a listed command that passes.

Result:
- 27,437 commands pass and none fail.
- 581 are skipped: the `assert_malformed` tests of the text format (NeonWasm reads binaries only).
- 29 rejections give another message than the spec's. For example, "alignment must not be larger than natural" where
  the spec says "malformed memop flags", and "unexpected end" where it says "integer representation too long".
- No known failures.

`WasmRuntimeTest` checks what the spec tests do not:
- the core's limits: time, instruction budget, interrupt from another thread;
- the call-depth trap, and the context going on after it;
- host functions both ways, and host exceptions passing through;
- `memory.grow` at its maximum and at the allocation limit;
- link errors;
- floats through the typed API.

## 4. Speed

A first measurement of the interpreter (HotSpot 25, Ryzen 7 9800X3D, medians of 8 runs in one JVM):
- a recursive `fib(30)`, 2.7M calls: 35 ms;
- a loop of 3M iterations (`s += i & 7`): 135 ms.

For comparison, the same loop is 113–127 ms in NeonPy's interpreter and 66 ms in CPython 3.14
(docs/PYTHON_PLATFORM.md §10.2). Translation is what makes wasm fast; the interpreter is for starting immediately and
for code that is not hot.

## 5. Next

1. **Translation**, a dialect of the neutral core (docs/IR_NEUTRALITY.md):
   - i32/i64/f64 map to the core's `Rep.INT`/`LONG`/`DOUBLE`. f32 needs a `Rep.FLOAT`, a change to the core on its
     branch.
   - Large functions are split to fit the JVM's and ART's limits.
   - On-stack replacement from the interpreter.
   - Classes go through `CodeDefiner`, dex on Android.
2. **The JS `WebAssembly` API** in NeonJS, tested by WPT `wasm/jsapi`.
3. **3.0's features as code needs them:** exception handling (C++), tail calls. The spec tests then move to `wg-3.0`.
4. **NeonPy's FFI** onto C libraries compiled to wasm (docs/PYTHON_PLATFORM.md W2).
5. **More of WASI** where programs need it (§6): host directories behind the same boundary, preview 2.

## 6. WASI preview 1

Package `dev.mooner.neonwasm.wasi`:

| file | what |
|---|---|
| `Wasi.kt` | one program's WASI: its descriptors (the standard streams, then the preopened directories); `run` instantiates, calls `_start` and gives the exit code |
| `WasiConfig.kt` | arguments, environment, standard streams, preopens, clocks (`WasiClock`), randomness |
| `WasiFs.kt` | the in-memory file system: inodes for files, directories and symbolic links, hard links, nanosecond times; `fromHost` copies a host directory in |
| `WasiFunctions.kt` | the process, clocks, randomness, streams, `poll_oneoff`; sockets (none) |
| `WasiFiles.kt` | `fd_*` on files and directories, `path_*`, and the one routine that resolves paths |
| `WasiAbi.kt` | error numbers, flags and rights; the guest's memory, bounds-checked |

```
val out = ByteArrayOutputStream()
val wasi = Wasi(WasiConfig.builder().args("prog").env("K", "V").stdout(out).preopen("/", WasiFs().root).build())
val code = wasi.run(WasmContext(VmConfig()), WasmModule.decode(bytes))
```

**The sandbox.**
- A program sees only what its configuration gives it: its arguments and environment (nothing of the host's), its
  streams, and the preopened directories of in-memory file systems.
- No host file is ever opened: `WasiFs.fromHost` copies a directory in.
- There are no sockets (`sock_*` give ENOTSOCK, or EBADF for no descriptor) and no processes (`proc_raise` is
  ENOSYS).
- **Paths.** A path is resolved from the directory descriptor a call is given, and that directory is the boundary.
  An absolute path, or `..` above that directory, is ENOTCAPABLE, through symbolic links too. A symbolic link to an
  absolute path is refused when it is made, and at most 40 links are followed (ELOOP past that).
- **Waits.** `poll_oneoff` waits for a clock on the calling thread, in slices of at most 50 ms. Between them it
  checks the execution core's limits (`checkWaitLimits`), so a time limit or an interrupt ends the wait.
- **Clocks and randomness** are the configuration's; `deterministic(seed, time)` fixes both. NeonJS's
  `SandboxPolicy.deterministic` is separate; the two meet when the JS API comes.
- A guest pointer out of bounds makes the call fail with EFAULT rather than trap.
- **Rights.** Each descriptor reports what applies to its kind, as wasmtime does. Only reading and writing are
  enforced; `fd_fdstat_set_rights` is ENOTSUP, as in wasmtime.

**Testing.**
- **Source:** WebAssembly/wasi-testsuite's `prod/testsuite-base` at 9d84631 (`WASI_TESTSUITE_COMMIT` in ci.yml;
  `tools/wasi-testsuite.sh`). These are its prebuilt preview 1 programs: 14 in C (wasi-sdk), 46 in Rust (wasmtime's
  own tests of WASI) and 12 in AssemblyScript.
- **Runner:** `WasiTestsuiteTest` runs each as its JSON says (arguments, environment, a directory copied into memory
  and preopened as `/`). It compares the exit code, and stdout and stderr where the JSON gives them, as the
  testsuite's own runner does: the output's first bytes against the expected ones.
- **Result:** all 72 pass, and `neonwasm/wasi-known-failures.txt` is empty. Endive, the other JVM runtime the
  testsuite lists, has 6 expected failures that come from Java's file API; an in-memory file system has none of them.
- **`WasiTest`** covers the sandbox's side: a `poll_oneoff` of 10 s ended by a time limit of 300 ms, and `fromHost`
  copying rather than opening.

Not yet supported:
- host directories (behind the same boundary);
- sockets;
- preview 2 (the component model);
- stdin as an interactive stream: `poll_oneoff` reports streams always ready.
