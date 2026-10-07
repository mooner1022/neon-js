# NeonJS architecture

This document describes how NeonJS is put together and the invariants contributors must preserve.

```
source ──► Lexer/Parser ──► AST ──► ScopeAnalyzer ──► Emitter ──► CodeBlock (stack bytecode)
                                                                     │
                                       ┌─────────────────────────────┴───────────────┐
                                       ▼                                             ▼
                              Interpreter (vm)                          JvmCompiler (jit, ASM)
                                       │                                             │
                                       └──────────► Runtime (objects, builtins) ◄────┘
```

## Parsing (`io.neonjs.parser`)

A hand-written recursive-descent parser in the style of acorn: cover grammars for arrow parameters, destructuring
assignment targets and `async` arrows; all early errors are reported at parse time (duplicate bindings, invalid
assignment targets, `using` placement, class private names, regular-expression literal syntax via the RegExp
compiler, Unicode 17 identifiers from the generated tables). AST nodes carry two annotation slots filled by later
passes: `scope` and `ref`.

## Scope analysis (`compiler/Scopes.kt`)

`ScopeAnalyzer` builds a `Scope` tree (function, block, catch, class, with, module, global, eval) and resolves every
identifier to one of

- `LocalRef(binding)` – a binding in an enclosing function; it lives in a **register** of the frame unless it is
  *captured* by a nested function, `eval`, or `with`, in which case it lives in a heap environment slot;
- `GlobalRef(name)` – global object property or global lexical binding;
- `DynamicRef(name)` – resolved at run time (inside `with`, or code reachable from sloppy direct `eval`).

It also decides TDZ checks (elided when the use is provably after the declaration in the same function), the
`arguments` object kind (mapped arguments force parameters into the environment), Annex B block-function hoisting,
the var scope receiving `eval`-declared variables, and pseudo-bindings for `this`, `new.target`, the home object
and the active function when arrow functions or `eval` capture them.

## Bytecode (`compiler/Op.kt`, `CodeBlock.kt`, `Emitter*.kt`)

A stack machine with registers. A `CodeBlock` has an `IntArray` of instructions, a constant pool, a handler table
(`start, end, handler, stack depth` per entry), line tables and flags. Notable design points:

- `finally` is compiled with a (kind, value) register pair and a `JUMP_TABLE`; `break`/`continue`/`return` crossing
  a finally record a pending-jump kind. The same machinery implements `using` disposal regions (the finalizer runs
  `DisposeResources`, with `AWAIT_CATCH` steps for `await using`) and iterator closing (`IterCtl`).
- Named property accesses (`GET_PROP`/`PUT_PROP`) and global accesses (`LOAD_GLOBAL`/`STORE_GLOBAL`) carry a
  per-instruction `PropSite`/`GlobalSite` constant that holds the inline cache. Every emitted instruction gets its
  own site; a site is never shared between a load and a store.
- Calls in tail position of strict functions are emitted as `TAIL_CALL` (proper tail calls).
- Derived constructors return a `DerivedResult` and the realm-sensitive checks run in the caller.
- Strictness is a property of the code block, with one exception: all parts of a class are strict, including the
  heritage, computed keys and decorators that run in the enclosing (possibly sloppy) function. The emitter records
  the start offsets of such instructions in `CodeBlock.strictPcs` when one of them behaves differently in strict code
  (property stores, deletes, name resolution…); the interpreter consults it (`strictAt`) and the JIT leaves those
  rare code blocks interpreted.
- Classes with decorators or `accessor` fields are built at run time from a `ClassDef` (`vm/Decorators.kt`):
  `CLASS_DEF_NEW`, one `CLASS_ELEMENT` per element, `CLASS_FINISH` (element decorators in the specified order:
  static methods/accessors, instance methods/accessors, static fields, instance fields), `CLASS_DECORATE` and, once
  the class binding is initialized, `CLASS_STATIC_INIT` (static initializers run against the decorated class).

## Interpreter (`vm/Interpreter.kt`)

`execute(frame)` does realm/stack bookkeeping and depth/interrupt checks, then runs either the compiled code of the
`CodeBlock` or `run(frame)`, the main dispatch loop. Frequent instructions are in `run`; less frequent ones in
`slowOp` and `rareOp`. **Each of these methods must stay below 8000 bytes of JVM bytecode**: HotSpot never
JIT-compiles larger methods, which silently makes the whole interpreter several times slower.
`MethodSizeTest` enforces this for the `vm`, `runtime`, `jit`, `builtins` and `interop` packages.

Generators and async functions suspend by returning `SUSPENDED` from `run`; their frame keeps registers, stack and
pc. Async functions, async generators and async module bodies are resumed by promise reactions.

Proper tail calls: `TAIL_CALL` to a plain closure stores the callee in the frame and returns `TAIL`; `callClosure`
loops (a trampoline), so tail-recursive code runs in constant JS and JVM stack. Other callees (natives, proxies,
bound functions) are called directly in the calling function's realm.

## JIT (`jit/`)

`JvmCompiler` translates a `CodeBlock` to one JVM hidden class implementing `CompiledCode.run(Frame)`. The IR stack
maps to the JVM operand stack and registers map to JVM locals; exception handlers become JVM try/catch blocks
converting the throwable with the same `catchValue` logic as the interpreter; loop back-edges count down an
interrupt budget. Operations call static helpers in `JitRt`, which HotSpot inlines. Generators/async functions
(and the rare unsupported instruction or oversized function) stay in the interpreter.

Generated classes (JIT code and `Java.extend` adapters) become classes through a `CodeDefiner`
(`jit/CodeDefiner.kt`): hidden classes on standard JVMs, dex loaded by `InMemoryDexClassLoader` on Android
(`neonjs-android`), where a `DexConverter` translates each class: dx by default, D8 with `neonjs-android-d8`. The
generated code therefore uses only public engine members, Java 8 class file features
when the definer asks for them (`classFileVersion`), and no class refers to another, so each can have its own loader.
A definer is probed once with a trivial class before any real code is generated; without a usable one the engine stays
in the interpreter. Class names derive from the code (not a counter), so a definer can cache translations.

`Jit.prepare` implements tiering: `INTERPRETER` never compiles, `COMPILED` compiles before the first call,
`ADAPTIVE` compiles after `jitThreshold` calls. Compiled code and interpreted code share frames and are
interchangeable at call boundaries.

## Object model (`runtime/`)

- **Values**: `Undefined`, `Null`, `Boolean`, `Double` (all numbers), `String`/`Rope` (`CharSequence`),
  `BigInteger`, `JSSymbol`, `JSObject`. Internal sentinels: `Uninitialized` (TDZ), `Hole`, `NotFound`.
- **Property keys**: `Int` for array indices ≤ `Int.MAX_VALUE`, otherwise `String`; or `JSSymbol` (`PK`).
- **`JSObject`** implements the ordinary internal methods; exotic objects override them and must set `special`
  bits (`EXOTIC_OWN`, `SPECIAL_GET/SET/HAS`) so generic algorithms and caches treat them correctly.
- **`PropertyMap`** stores keys, values, flags and key hashes in insertion order (linear search for small maps, an
  open-addressing index beyond 8 entries).

### Shapes and inline caches

Every `PropertyMap` carries a `Shape` token. Shared shapes form transition trees rooted per *(prototype object,
Java class)*; appending a property follows a transition. Any other structural change — delete, attribute change,
compaction, prototype change that cannot be replayed — gives the map a fresh unique token. Hence two maps with the
same cacheable shape have the same keys, slots, attributes, prototype and class.

`PropCache` (property sites) caches own data/accessor hits, prototype-chain hits (validated by the shapes of every
object on the chain), absent properties, existing-slot stores, add-property transitions and prototype setters, up
to 4 receiver shapes per site. `GlobalCache` caches global lexical bindings by index and global-object properties
by shape. Cache entries are immutable and published with a single field write because code blocks (and so sites)
are shared by contexts running on different threads; shapes belong to one context, so entries never hit across
contexts. Objects with exotic `[[Get]]`/`[[Set]]` use `Shape.UNCACHEABLE`.

**Invariant:** never write `PropertyMap.keys`/`flags` directly — use `add`, `setFlags`, `removeAt`
(`JSObject.defineOwn`, `defineOwnProperty`...). Writing `values[i]` for an existing data slot is fine.

## Environments

`DeclEnv` (slots described by a `ScopeInfo`, plus an `extension` map for sloppy-eval variables), `ObjectEnv`
(`with`), `GlobalEnv` (lexical declarations + global object). Dynamic resolution goes through `Names`; `NameRef`
gives reference semantics for assignments that must resolve before evaluating the right-hand side.

## Agent, realm, jobs

An `Agent` (one per `NeonContext`, thread-local while entered) holds the job queue, the current realm, the frame
stack used for stack traces, and all limits (deadline, instruction budget, depth, allocation budget). A `Realm`
holds the intrinsics and the global environment; `$262.createRealm` and `ShadowRealm` create more realms in the same
agent (a ShadowRealm gets fresh built-ins only, shares the module loader but not host-defined modules).

**External jobs.** Work completing on other threads — host `CompletionStage`s handed to JS, timers, Atomics.waitAsync
wake-ups, `$262.agent` reports — never runs guest code there: the other thread posts a job
(`Agent.postExternalJob`) and the owner thread moves it into the job queue in `runJobs` / `awaitExternal`. While
such work is outstanding it is registered as an `ExternalSource`, so an event loop (`NeonContext.runEventLoop`,
`NeonValue.await`) knows to wait; `closeExternal` (context close) cancels every source. Blocking waits check only
the interrupt flag and the deadline (`checkWaitLimits`), not the instruction budget.

## Modules (`vm/Modules.kt`)

Cyclic module records implement Link, Evaluate (with top-level-await ordering), ResolveExport and namespaces;
imports are live bindings (`ImportRef`). Synthetic modules back JSON modules and host-defined modules
(`NeonContext.defineModule`). Loading goes through a `ModuleLoader` (host-provided `NeonModuleLoader`).

## Built-ins

Defined with a small DSL in `builtins/Builtins.kt` (`method`, `getter`, `accessor`, `makeCtor`...). The RegExp engine
(`regexp/`) is a port of the quickjs-ng design: parser → bytecode → backtracking matcher with an explicit stack,
interrupt checks, and fast paths for the common `exec`/`test`/`replace`/`split` cases. Unicode data
(`unicode/UnicodeData.kt`) is generated by `neonjs-core/tools/gen_unicode.py` from the Unicode Character Database.

## Interop (`interop/`)

`HostBridge` converts values in both directions and selects overloads by conversion cost. `HostClassInfo` caches
the reflective view of a class under a `HostAccess` policy (fields, methods, bean properties, constructors, nested
classes, functional method). `HostObject` and `HostClassObject` are exotic objects exposing them. Interface
implementations are `java.lang.reflect.Proxy`s; `Java.extend` generates subclasses with ASM (`Adapters.kt`), each in
its own class loader. All host→JS re-entry (callbacks, proxies, adapters, `List`/`Map` views) goes through the
context's `ContextGate`, which takes the context lock. `CompletionStage`s become promises settled by external
jobs, and promises passed as `CompletableFuture`/`CompletionStage`/`Future` become futures completed by promise
reactions; JS `Date`/`Temporal.Instant` convert to `java.time` types.

## Optional modules and extensions

- **`neonjs-intl`** implements ECMA-402 on ICU4J. Core only defines the `IntlProvider` SPI (found with
  `ServiceLoader`); the provider is installed last in `Builtins.install`, so it can replace the locale-sensitive
  methods of Date, Number, BigInt, String, Array and %TypedArray%. ICU is loaded lazily on first use; non-thread-safe
  ICU objects belong to one Intl object, and shared caches hold immutable values only.
- **`ext/`**: the console (`ConsoleBuiltins`, formatting by `Inspector`, which never runs guest code) and the opt-in
  web globals (`WebGlobals`: timers on one shared daemon scheduler that only posts external jobs, `structuredClone`,
  `DOMException`, base64, text codecs).

## Testing

- `neonjs-core` unit tests: public API, interop, sandbox/security, inline-cache invalidation, console, language
  corner cases, proposals (decorators, ShadowRealm, deferred imports), web globals, method sizes.
- `neonjs-intl` unit tests: Intl basics, default locale and host-locale hiding, input caps, threads, method sizes.
- `neonjs-test262`: the Test262 runner and the mutation fuzzer `FuzzKt` (see the README). Fuzzer findings become
  regression tests (`SecurityTest.fuzzerRegressions`, `builtinLoopsOverHugeArrayLikesAreInterruptible`).
- `bench/`: micro-benchmarks (`basic.js`, `props.js`), run with the CLI in each execution mode.
