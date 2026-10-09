# NeonJS

[![CI](https://github.com/mooner1022/neon-js/actions/workflows/ci.yml/badge.svg)](https://github.com/mooner1022/neon-js/actions/workflows/ci.yml)
[![Android](https://github.com/mooner1022/neon-js/actions/workflows/android.yml/badge.svg)](https://github.com/mooner1022/neon-js/actions/workflows/android.yml)
[![Release](https://github.com/mooner1022/neon-js/actions/workflows/release.yml/badge.svg)](https://github.com/mooner1022/neon-js/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.mooner.neonjs/neonjs-core?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.mooner.neonjs/neonjs-core)

NeonJS is a JavaScript engine for the JVM, written in Kotlin. It is built for embedding untrusted or semi-trusted
scripts in Java/Kotlin applications:

- **Standards**: ECMAScript 2025, the ES2026 additions (explicit resource management, `Array.fromAsync`,
  `Error.isError`, Iterator sequencing, Uint8Array base64…) and the Stage 3 proposals tracked by Test262 (Temporal,
  decorators, ShadowRealm, source-phase and deferred imports, import text/bytes, immutable ArrayBuffers…),
  validated against Test262. ECMA-402 (`Intl`) comes from the optional `neonjs-intl` module (ICU4J); put it on
  the class path to get it, or turn it off per engine with `NeonEngine.builder().intl(false)`.
- **Security sandbox**: time, instruction, call-depth, string-length and memory limits; deny-by-default host access;
  deterministic mode; no way for scripts to intercept termination.
- **Java/Kotlin interop** in the style of Rhino and GraalJS: expose objects, classes, functions and values; receive
  events through JS callbacks; call JS from the host; implement or extend Java types in JS.
- **Two execution tiers**: a bytecode interpreter and a JIT that compiles hot functions to JVM bytecode, selectable
  per engine (interpreter-only, compile-everything, or adaptive).

## Status

| | |
|---|---|
| Test262 `test/` without `intl402/` and `staging/` | **48,747 / 48,747 pass** in interpreter, compiled and adaptive modes |
| Test262 `staging/` | 1,467 / 1,483 — the rest are SpiderMonkey extensions left out on purpose (`f.caller`/`f.arguments`, 12), two Annex B tests that contradict the main suite, and two engine-specific Date parsing heuristics |
| Test262 `intl402/` (with `neonjs-intl`) | **3,363 / 3,365** in all modes — the two failures are ICU 78 data limits: one Chinese-calendar month boundary (2030) where ICU's astronomy differs from the official table Temporal uses, and islamic-civil eras that ICU does not distinguish |
| Skipped Test262 features | `export-defer` (no stable semantics yet) |
| JVM | Java 21+ (built with a JDK 25 toolchain, `jvmTarget` 21) |

Implemented highlights: full ES2025 syntax and semantics (classes with private members, generators, async
iteration, modules with top-level await and import attributes, destructuring, optional chaining…), `using` /
`await using`, proper tail calls, a from-scratch RegExp engine (Unicode 17 tables, `v` flag, lookbehind, named
groups, modifiers, legacy `RegExp.$1`…), TypedArrays/SharedArrayBuffer/Atomics with real multi-agent
`wait`/`notify` and `waitAsync`, immutable ArrayBuffers, Iterator helpers, Set methods, DisposableStack,
Array.fromAsync, `Promise.allKeyed`, Float16, base64/hex on `Uint8Array`, Date with a full parser, Temporal (IANA
time zones from the JDK's tz data; the non-ISO calendars and `toLocaleString` with `neonjs-intl`),
decorators (including `accessor` fields and `Symbol.metadata`), ShadowRealm, `import defer` / `import source` /
`import.defer()`, and `with { type: "json" | "text" | "bytes" }` imports.

Known differences: `f.caller` / `f.arguments` (non-standard) are not supported, so a function never exposes its
callers; Temporal computes offsets with the JDK's tz data while `Intl.DateTimeFormat` formats with ICU's, which can
disagree for a few zones' history when the two databases are different releases; `Date.parse` of strings outside
the specified formats follows the engine's own heuristics, which differ from SpiderMonkey's in places.

## Installation

Releases are published to Maven Central as `dev.mooner.neonjs:<module>`
([docs/RELEASING.md](docs/RELEASING.md) describes the release process):

```kotlin
dependencies {
    implementation("dev.mooner.neonjs:neonjs-core:…")
    implementation("dev.mooner.neonjs:neonjs-intl:…")   // optional: Intl and the non-ISO Temporal calendars (ICU4J)
}
```

The jars are Java 21 class files. For Android, see [Android](#android).

## Building

```bash
./gradlew build                      # core library, CLI, Test262 runner, unit tests
./gradlew :neonjs-cli:installDist    # CLI in neonjs-cli/build/install/neonjs/bin/
./gradlew :neonjs-cli:fatJar         # single executable jar: neonjs-cli/build/libs/neonjs-cli-<version>-all.jar
```

### CLI

```
neonjs [--interpreter | --compiled | --adaptive] [--module] [--dis] [-e CODE] [-f FILE ...] [file.js | file.mjs ...]
```

Local files run in the order given, either as plain arguments or with `-f FILE` / `--file FILE` / `--file=FILE`
(use `-f` for a file name that starts with `-`); `-e` code runs first. A missing or unreadable file is reported and
the remaining files still run (exit status 1); a bad option exits with status 2. Without files or `-e` it starts a
REPL. The executable jar runs the same way: `java -jar neonjs-cli-<version>-all.jar [options] [files]` (it
includes `neonjs-intl`, and needs no `-Xss` flag). `print(...)` and `console.*` write to stdout/stderr; `.mjs`
files (or `--module`) are run as ES modules with relative imports resolved from the file system.

## Embedding

```kotlin
import dev.mooner.neonjs.*

val engine = NeonEngine.builder()
    .executionMode(ExecutionMode.ADAPTIVE)      // or INTERPRETER / COMPILED, or .optimizationLevel(-1..9)
    .sandbox(SandboxPolicy.STRICT)               // 5 s per evaluation, depth 1000, no eval
    .hostAccess(HostAccess.EXPLICIT)             // only @HostExport members are visible
    .build()

engine.newContext().use { ctx ->
    ctx["config"] = mapOf("retries" to 3)                       // host values become JS values / host objects
    ctx.setFunction("log") { args -> println(args.joinToString(" ")); null }
    val result = ctx.eval("log('starting'); config.retries * 2")
    println(result.asInt())                                      // 6
}
```

A `NeonEngine` holds configuration and is thread-safe; it creates isolated `NeonContext`s (each with its own
realm, globals and job queue). A context is single-threaded but may be entered from any thread: calls are
serialized by an internal lock, so host callbacks invoked on other threads are safe. Compiled scripts
(`engine.compile(source)`) can be evaluated in any context of the same engine.

### Execution modes

- `INTERPRETER`: bytecode interpreter only.
- `ADAPTIVE` (default): functions start interpreted; after `jitThreshold` calls (or long-running loops) they are
  compiled to JVM bytecode by background threads, and keep running interpreted until their code is ready. Functions
  that become hot together are compiled together.
- `COMPILED`: every function is compiled before its first call; the calling thread waits for its own function only,
  while the functions a script defines are compiled in parallel by the background threads.

Compiled code is shared: functions whose bytecode is the same (they may differ in constants or position) and the same
code run in many contexts get one class. `backgroundCompilation(false)` compiles on the calling thread instead. The
background threads are shared by all engines and their work is not charged to a context's sandbox limits; system
properties: `neonjs.jit.threads` (default 1–2 by core count, 0 = none), `neonjs.jit.batch` (32),
`neonjs.jit.maxPending` (4096, beyond which callers compile themselves), `neonjs.jit.debugInfo` (JVM line numbers in
generated classes, for profilers), `neonjs.jit.typed` (`false` keeps numbers boxed in generated code),
`neonjs.jit.int32` (`false` keeps int32 values as doubles in generated code), `neonjs.jit.elem` (`false` boxes the
numbers generated code stores into typed arrays and reads from arrays), `neonjs.jit.inline` (`false` compiles calls
without inlining the callee), `neonjs.jit.inlineMinCount` (16: calls a site must have made to one function, while its
caller ran interpreted, before that function is inlined there), `neonjs.jit.dump` (a directory to write the generated
classes to).

### Values

`NeonValue` wraps a JS value: `asInt/asDouble/asString/asBoolean/asBigInteger`, `as(Class)` / `to<T>()` for
conversions (collections, functional interfaces, host objects), `getMember/putMember/memberKeys`,
`getElement/arraySize`, `call/callMember/newInstance`, and `await()` for promises.

JS → host conversions follow the declared parameter types of the called method (numbers, strings, enums, arrays,
`List`/`Map` live views, functional interfaces from JS functions, any interface from a JS object, and `java.time`
types or `java.util.Date` from a JS `Date` or `Temporal.Instant` — local types in the context's time zone;
`NeonValue.asInstant()` does the same). Host → JS:
numbers and strings become primitives, `null` becomes `null`, everything else becomes a host object.

**Asynchronous host APIs.** A `CompletionStage` / `CompletableFuture` handed to JS becomes a promise, and a promise
(or thenable) passed where a `CompletableFuture`, `CompletionStage` or `Future` is expected becomes a future:

```kotlin
class Http { fun get(url: String): CompletableFuture<String> = client.sendAsync(...).thenApply { it.body() } }
ctx["http"] = Http()
val result = ctx.eval("(async () => (await http.get('https://example.com')).length)()")
println(result.await().asInt())          // runs jobs and waits for the future, within the sandbox time limit
ctx.runEventLoop()                       // or: run jobs until no promise awaits a host future
```

Futures may complete on any thread: the completion is queued and the promise is settled on the context's thread
the next time it runs jobs (`runJobs`, `runEventLoop`, `NeonValue.await`, or any evaluation). Futures made from JS
promises complete inside those job runs, on the context's thread — chain on them (`thenAccept`…) rather than
blocking that thread with `get()`.

### Host objects, classes and callbacks

```kotlin
class Point(@JvmField var x: Int, @JvmField var y: Int) {
    fun length() = Math.sqrt((x * x + y * y).toDouble())
}

ctx.exposeClass("Point", Point::class.java)         // new Point(3, 4), static members, instanceof
ctx["bus"] = eventBus                                 // bus.on(e => ...) — JS functions become Java callbacks
ctx.defineModule("host:db", mapOf("query" to queryFunction, "version" to 2))   // import { query } from 'host:db'
```

- Java fields, methods (with overload resolution), Kotlin properties (`getX/isX/setX`), Kotlin companion
  members, enums and nested classes are visible according to the `HostAccess` policy.
- Java arrays and `List`s behave like JS arrays; `Iterable`s and `Iterator`s work with `for…of` and spread; `Map`
  entries are readable and writable as properties.
- JS functions convert to any functional interface (`Runnable`, `Consumer`, Kotlin `fun interface`s, …) and JS
  objects to any interface. The proxies re-enter the context safely from any thread.
- Host exceptions surface in JS as `HostError` objects (catchable); uncaught JS errors surface in the host as
  `NeonException` with `guestValue` and the JS stack trace.

With `SandboxPolicy.builder().exposeJavaGlobal(true)` and a `HostAccess` that allows class lookup, scripts get a
`Java` object: `Java.type("java.util.ArrayList")`, `Java.type("int[]")`, `Java.from(javaCollection)`,
`Java.to(jsArray, "long[]")`, and `Java.extend(AbstractType, Interface…, { method() {…} })` /
`Java.super(instance)` to subclass Java classes in JS (adapters are generated with ASM). Under
`HostAccess.EXPLICIT`, an adapter's overrides are visible exactly when the method they override is exported.

### Sandboxing

```kotlin
val policy = SandboxPolicy.builder()
    .maxExecutionTime(2_000)            // ms per top-level evaluation (also covers promise jobs)
    .maxStatements(50_000_000)          // instruction budget
    .maxCallDepth(1_000)
    .maxStringLength(16 * 1024 * 1024)
    .maxAllocatedBytes(256L * 1024 * 1024)   // per evaluation, measured by HotSpot's thread allocation counter
    .allowEval(false)                   // eval / Function constructor / ShadowRealm.prototype.evaluate
    .deterministic(seed = 42, fixedTime = 0) // Math.random and Date.now become reproducible (UTC, en-US)
    .timeZone("Asia/Seoul")             // local time zone of Date / Temporal.Now, instead of the host's
    .defaultLocale("ko-KR")             // default locale of Intl, instead of the host's
    .build()
```

Limits are enforced at loop back-edges, calls, job dispatch, inside long-running built-ins (RegExp matching,
`join`, `repeat`, buffer and array allocation…) and in compiled code alike. Exceeding one throws a
`NeonTerminatedException` subclass (`NeonTimeoutException`, `NeonResourceLimitException`,
`NeonInterruptedException`); JS `catch`/`finally` blocks and promise handlers cannot intercept it.
`ctx.interrupt()` stops a running evaluation from another thread.

`ShadowRealm`s created by a script get fresh built-ins only — no host globals, no `Java` object, no console, no
host-defined modules — and run under the same limits as their creator.

`HostAccess.NONE` makes host objects opaque, `EXPLICIT` exposes only `@HostExport` members, and `ALL` exposes all
public members. Every level applies a deny list (reflection, `Class`, class loaders, `System`, `Runtime`,
processes, threads, `Unsafe`, serialization streams, engine internals…) and hides `getClass`, `wait`, `notify`
and `finalize`. Embedders that trust their scripts with some of these lift entries of the built-in list explicitly:

```kotlin
HostAccess.builder(HostAccess.Level.ALL)
    .allowClass("java.lang.System")                  // one class, also inside a denied package
    .filterMembers { it.name != "exit" }             // and narrow its members if needed
    .allowPackage("java.lang.management")            // a package and its subpackages
    // .defaultDenyList(false)                       // or no built-in list at all (fully trusted scripts)
    .build()
```

Classes denied with `denyClass` / `denyPackage` stay denied. See [docs/SECURITY.md](docs/SECURITY.md) for the
threat model.

### Console

Contexts get a `console` object (`log`, `info`, `warn`, `error`, `debug`, `trace`, `dir`, `table`, `group`,
`count`, `time`, `assert`, format specifiers) writing to a `NeonConsole` sink — stdout/stderr by default,
`NeonEngine.builder().console(sink)` to redirect, `console(null)` to omit it. Formatting never runs guest code
(getters, proxies and `toString` overrides are not invoked).

### Web globals

`NeonEngine.builder().webGlobals(true)` adds what scripts written for browsers and Node commonly expect:
`queueMicrotask`, `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval`, `structuredClone` (with
`transfer`), `DOMException`, `atob` / `btoa`, and UTF-8 `TextEncoder` / `TextDecoder` (with `encodeInto`, `fatal`,
`ignoreBOM` and streaming). Timers fire on the context's thread while the host runs its event loop:

```kotlin
ctx.eval("setTimeout(() => console.log('later'), 100)")
ctx.runEventLoop()            // returns once no timer, promise job or host future is pending
```

`runEventLoop` is one evaluation as far as `maxExecutionTime` is concerned, so give long-running loops (an endless
`setInterval`) a generous limit or stop them with `interrupt()`. Timer callbacks must be functions (no string
evaluation), and `SandboxPolicy.maxTimers` caps pending timers per context (10,000 by default). `TextDecoder`
supports only UTF-8 (other labels are a `RangeError`). The CLI enables these globals and runs the event loop after
each script.

## Android

NeonJS is a JVM engine that also runs on Android: the jars (core, intl, Kotlin stdlib, ASM) are converted to dex by
the app build like any library, and the engine keeps to APIs available from **API level 26**. Only code generated at
run time needs platform support, so it goes through one interface, `dev.mooner.neonjs.jit.CodeDefiner`:

| Platform | Definer | Compiled / adaptive mode | `Java.extend` |
|---|---|---|---|
| Standard JVM | built in (`JvmCodeDefiner`: hidden classes) | JVM bytecode | yes |
| Android with `neonjs-android` | `DexCodeDefiner`, found through `ServiceLoader` | each JIT class translated to dex (dx, or D8 with `neonjs-android-d8`), loaded by `InMemoryDexClassLoader` | yes |
| Android without it | none usable: detected once, before any code generation | interpreter only | no (TypeError) |

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.mooner.neonjs:neonjs-core:…")
    implementation("dev.mooner.neonjs:neonjs-android:…")   // JIT on Android
    // implementation("dev.mooner.neonjs:neonjs-android-d8:…")  // optional: D8 instead of dx (see below)
    implementation("dev.mooner.neonjs:neonjs-intl:…")      // optional: Intl, Temporal calendars (bundles ICU4J)
}
```

`neonjs-android-d8` gets its r8 dependency from Google's Maven repository (`google()`), which Android builds already
use. The jars are Java 21 class files, so the app's build tools (D8) must accept that class file version.

The engine needs API 26+ (`java.time`, `java.lang.invoke`, `InMemoryDexClassLoader`). Adding `neonjs-android` is
enough; to also keep converted code across launches, pass a cache directory:
`NeonEngine.builder().codeDefiner(DexCodeDefiner(context.codeCacheDir))` (dex files are stored read-only, as Android
14+ requires for dynamically loaded code). The jars carry their R8/ProGuard rules (the engine is kept as is, because
generated code calls it by name). Loading code generated at run time is dynamic code loading in Google Play's sense:
check the policies that apply to how your app obtains its scripts.

Measured on a Galaxy Z Fold7 (Android 17, API 37) with the CLI dexed by d8, AOT-compiled with dex2oat (as an
installed app is) and run through `app_process` with the screen on:
- Test262 (`language/`, `built-ins/`, `annexB/`) passes on ART in the interpreter (48,631 / 48,631) and with the dex
  JIT, where every compiled function is translated by dx on the device. ART's own JIT then compiles the loaded code
  to machine code like any other.
- A simple numeric loop runs about 5× faster with the dex JIT than in the interpreter (≈38 vs ≈180 ns per iteration).
- Compiling a small function costs about 0.3 ms with dx (about 0.06 ms with hidden classes on a desktop JVM) and about
  16 KB of memory when it gets a dex file and class loader of its own. Background compilation puts the functions
  compiled together in one dex file and class loader, and identical code is compiled once. Keep the default
  `ExecutionMode.ADAPTIVE`, which compiles hot functions only; `COMPILED` pays this for every function that runs.

The translation step is pluggable as well (`dev.mooner.neonjs.android.DexConverter`). The default is dx, the dexer of
the Android SDK before D8: no longer maintained, but it only ever reads the engine's own generated code, and every class
Test262 generates translates. `neonjs-android-d8` replaces it with D8 from Google's r8 library (`com.android.tools:r8`),
which is maintained and has no Java 8 class file ceiling. Adding the module selects it; `DexCodeDefiner(converter = …)`
or `-Dneonjs.dexConverter=dx|d8` choose explicitly. D8 is made for whole-program builds, though: each conversion costs
far more on a phone (5,000 small functions converted one at a time, measured back to back: ≈44 s against ≈2.2 s with
dx), which the batches of background compilation spread over many functions, and the library adds ≈7.7 MB of dex.
Test262 passes with it (on a JVM, every generated class translated; on the device, the statements and Array parts in
compiled mode, 191,685 functions translated by D8). It has run on API 37 only; it refers to some APIs newer than level
26, and where a conversion fails the code stays in the interpreter (`Java.extend` throws a TypeError). Emitting dex
directly, without class files, is reviewed in [docs/DEX_BACKEND.md](docs/DEX_BACKEND.md).

Without a device, `-Dneonjs.codeDefiner=dev.mooner.neonjs.android.DexCheckingDefiner` runs any test suite on a JVM with
every generated class translated by dx first (the whole Test262 main suite passes this way, also with D8:
`neonjs-android-d8` on the class path and `-Dneonjs.dexConverter=d8`); `-Dneonjs.codeDefiner=isolated` reproduces the
class loading conditions only. Limit on Android: memory-allocation limits (`maxAllocatedBytes`) are unavailable (HotSpot
only).

Android 8.0 and 8.1 (API 26–27): class hierarchy analysis in their ART keeps a pointer to the only implementation of an
interface method after the implementing class has been unloaded, and reads it when the next implementing class is
linked; during a garbage collection that aborts the runtime (`Check failed: self == thread_running_gc_`) or corrupts
the heap (fixed in Android 9). Generated classes are unloaded with their class loaders, so the engine never lets one be
the only implementation there: the JIT's `CompiledCode` has two built-in implementations, and `Java.extend` adapters
that implement interfaces stay loaded for the life of the process on those versions (elsewhere they are unloaded with
their context).

## Project layout

| Module / package | Contents |
|---|---|
| `neonjs-core` `dev.mooner.neonjs` | public API: `NeonEngine`, `NeonContext`, `NeonValue`, `SandboxPolicy`, `HostAccess`, module loaders |
| `…parser` | lexer and parser (ESTree-like AST, early errors, Unicode 17 identifiers) |
| `…compiler` | scope analysis and bytecode emitter |
| `…vm` | interpreter, environments, generators/async, promises jobs, modules, eval |
| `…jit` | JVM bytecode compiler (ASM) and the `CodeDefiner` SPI that turns its output into classes |
| `…runtime` | object model (shapes, property maps, inline caches), values, abstract operations, agent/realm |
| `…builtins`, `…regexp`, `…unicode` | standard library, RegExp engine, Unicode tables |
| `…interop` | host object bridge, overload selection, interface proxies, `Java.extend` adapters |
| `neonjs-intl` | ECMA-402 (`Intl`, locale-sensitive methods) on ICU4J, found through `ServiceLoader` when on the class path |
| `neonjs-android` | `CodeDefiner` translating generated classes to dex (dx) for the JIT and `Java.extend` on Android |
| `neonjs-android-d8` | optional `DexConverter` using D8 (r8 library) instead of dx |
| `neonjs-cli` | command-line runner and REPL |
| `neonjs-test262` | Test262 runner |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the internals.

## Testing

```bash
./gradlew :neonjs-core:test                       # unit tests (API, interop, sandbox, inline caches, …)
git clone https://github.com/tc39/test262 third_party/test262
./gradlew :neonjs-test262:installDist
neonjs-test262/build/install/neonjs-test262/bin/neonjs-test262 --root third_party/test262 [--mode compiled|adaptive] [--staging] [--intl] [path filters]
```

`--staging` and `--intl` add `test/staging/` and `test/intl402/` to the default selection.

A mutation fuzzer over the same corpus runs each mutated test in the interpreter and in compiled mode under tight
limits, and reports engine-internal exceptions, runs that ignore the time limit, and results that differ between
the two tiers:

```bash
java -Xss64m -cp "neonjs-test262/build/install/neonjs-test262/lib/*" dev.mooner.neonjs.test262.FuzzKt --root third_party/test262 --iterations 100000 --threads 6 --seed 1 --out fuzz-out
```

The runner executes each test in strict and sloppy mode as required, supports `$262` (including
`createRealm`, `evalScript`, `detachArrayBuffer` and multi-agent `$262.agent`), modules and async tests, and
writes failures to `--out`. Note: Test262 must be checked out with LF line endings (`core.autocrlf=false`); a few
tests check source text exactly.

The runner exits with status 1 when a test fails that `--known FILE` does not list (`neonjs-test262/known-failures.txt`
holds the two ICU-data failures of `intl402/`).

### On Android devices and emulators

`tools/android` runs the Test262 runner and Android-specific checks on a device or emulator through adb and
`app_process` (no APK):

```bash
./gradlew :neonjs-test262:installDist :neonjs-test262:d8Libs
python3 tools/android/bundle.py --out build/android/neonjs-test262.jar neonjs-test262/build/install/neonjs-test262/lib
python3 tools/android/device.py install build/android/neonjs-test262.jar          # push, compile ahead of time
python3 tools/android/device.py run neonjs-test262 dev.mooner.neonjs.test262.AndroidCheck
python3 tools/android/device.py push-test262 third_party/test262 language/statements built-ins/Array
python3 tools/android/device.py test262 neonjs-test262 --mode compiled --timeout 60000 language/statements built-ins/Array
```

`bundle.py` dexes jars with the SDK's D8 and keeps their resources; adding `neonjs-test262/build/d8-libs` makes a
bundle that uses D8 at run time. `AndroidCheck` covers what Test262 does not: the dex definer, background batches,
`Java.extend`, default methods of JS-implemented interfaces and the cache directory. `tools/android/ci-emulator.sh`
runs these with dx and with D8 (pick the device with `ANDROID_SERIAL`); the Android workflow runs it on API 26
and API 34 emulators. An emulator without Android Studio (on Windows it uses the Windows Hypervisor Platform; in
`cmd`, quote the package names, which contain `;`):

```bash
sdkmanager "system-images;android-26;default;x86_64"
avdmanager create avd -n neonjs-api26 -k "system-images;android-26;default;x86_64" -d pixel
emulator -avd neonjs-api26 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
```

When the runtime aborts, `device.py` prints the abort message and the native backtrace of the crashing thread from the
device's crash log.

## License

NeonJS is licensed under the [Apache License 2.0](LICENSE). It includes data derived from the Unicode Character
Database, under the Unicode License V3 (see [NOTICE](NOTICE)).
