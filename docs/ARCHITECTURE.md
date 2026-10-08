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

## Parsing (`dev.mooner.neonjs.parser`)

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
in the interpreter. The generator reads a code block only through `JitInput` (instructions, handler ranges, registers,
statement starts; constants are read from the frame at run time), and class names end with a hash of it: equal names
mean equal class files, in every run, so code blocks can share a class and a definer can cache translations. A
`CodeCache` per definer hands out one compiled instance per identity (weakly held, so classes still unload): library
code run in many contexts, or functions differing only in constants, compile once. Compilation goes through a
`JitBackend` (`jit/JitBackend.kt`), which takes a batch of code blocks: `ClassBackend` generates their classes and
defines them with one `CodeDefiner.defineAll` call, which `DexCodeDefiner` turns into one dex file and one class
loader per batch (a rejected batch is retried class by class). A backend that emits dex directly would plug in at
`JitBackend`, below the tiering policy and the background compiler. JVM debug
info (source name, line numbers) is left out unless `-Dneonjs.jit.debugInfo` is set; JS stack traces do not need it.

Unboxed numbers (`jit/JitTypes.kt`): before generating a class, `TypeAnalysis` runs a forward dataflow over the code
block and gives each stack slot and register a kind at each instruction: a JVM `double` (NUM), a JVM `int` holding an
int32 number (INT), a JVM `int` 0/1 (BOOL) or an Object (ANY). Registers that only ever hold numbers become `double`
locals; arithmetic and comparisons on two numbers are JVM instructions (`DADD`, `DREM` for `%`, `DCMPG` for `<`/`<=`
and `DCMPL` for the others, so NaN compares false), and an operation with one number and one value of unknown type
calls a `JitRt` helper (`subDA`, `ltAD`, ...) that falls back to the generic operator with the number boxed, so
`valueOf` runs at the same point and BigInt mixing throws the same error. Comparisons test `instanceof Double` inline
instead of calling a helper: HotSpot compiles a boolean helper inlined into a loop condition poorly (split-if), five
times slower. Values are boxed where paths with different kinds meet and before every instruction that is not
specialised. The rules that keep this sound:

- No speculation. Kinds come from what instructions produce (number constants, arithmetic results), never from
  profiles; no check is removed because of a kind and nothing deoptimizes. A wrong kind could only surface as a JVM
  `ClassCastException` (every unbox is a `checkcast`, and every double taken as an INT goes through `exactInt`, which
  throws unless it is an int32 and not -0) or a verifier error, never as one value read as another.
- A closed list of instructions is specialised; every other instruction gets Object operands and the same code as
  before. A register is a `double` only when no instruction but `LOAD_REG` / `STORE_REG` touches it (registers read by
  TDZ checks, iterators, the finally dispatch `JUMP_TABLE`, `INIT_THIS_REG` and `MAKE_METHOD` stay Objects) and every
  read sees a number. Registers are invisible outside their function (closures, `eval`, `with` and mapped `arguments`
  use environment slots) and compiled code is only entered at its start, so nothing else reads or writes a `double`
  local.
- INT is only what is an int32 by definition: integer literals and constants that are int32s (not -0), and the results
  of `&`, `|`, `^`, `<<`, `>>` and `~`. Bitwise operators on two numbers are ToInt32 and a JVM int instruction (the
  JVM masks shift counts to 5 bits, as JS does; `>>>` gives its uint32 as a double), comparisons of two ints are
  `IF_ICMP*`, and an int key goes to `getElemI` / `putElemIA`. No arithmetic is done on ints: `+`, `-`, `*`, `/`, `%`,
  `**`, `++`, `--` and unary `-` widen ints to doubles (int arithmetic would wrap at 2^31, lose -0 as in `-x` or
  `0 * -1`, or throw on `/` and `%`), so their results are NUM. INT and NUM join to NUM (a loop counter starts as the
  literal 0 and is incremented as a double); a register is an `int` local when every read sees INT and a `double` when
  every read sees a number (a read of it that is INT goes through `exactInt`: every store reaching it was an int).
- Exception handlers see, for each register, the join of its kinds over the protected range, and an Object stack.
- Constant kinds are part of `JitInput` (blocks share classes, and constants are read at run time), and so are the
  `neonjs.jit.typed` and `neonjs.jit.int32` settings; `FORMAT` changes whenever the generated code does, since a
  dex-caching definer keys translations by class name.
- A block the analysis gives up on, or whose unboxed code exceeds the JVM method size limit, gets the Object-only code
  (`JvmCompiler.untypedReasons` counts them). `-Dneonjs.jit.typed=false` turns unboxing off,
  `-Dneonjs.jit.int32=false` only INT (integers are then NUM); `-Dneonjs.jit.dump=DIR` writes every generated class to
  DIR. These are JVM properties of the host, not reachable from scripts.

The approach is Rhino's (its optimizer gives variables proven numeric `double` locals). V8, JavaScriptCore and
SpiderMonkey speculate on profiled types and deoptimize when a guard fails; their JIT bugs typically come from a typer
that proves too much and lets a later phase drop a bounds or type check. Here nothing is dropped: interrupt checks,
`pc` stores, element access paths and every runtime check are the same as in the Object code. V8 and JavaScriptCore
also keep int32s unboxed, doing int arithmetic with overflow and -0 checks that deoptimize; the bugs there come from
range analysis that removes those checks. Here int arithmetic is never done, so there is no such check to remove.

Background compilation (`jit/JitQueue.kt`): a block due for compilation gets a `JitTask` (`CodeBlock.jitTask`,
set by CAS, so one thread compiles it) and is queued; daemon workers drain whatever is queued (up to a batch) and
hand it to the backend, one batch per backend. A thread that needs the code now (compiled mode) takes a queued task
over or waits for the worker compiling it. The generator reads only immutable code block fields, so it runs on any
thread; the code is published through the volatile `compiled` field before the task is detached. Closing a context
withdraws its queued tasks.

`Jit.prepare` implements tiering: `INTERPRETER` never compiles, `COMPILED` compiles before the first call (the
caller waits for its block only, then queues the blocks it defines), `ADAPTIVE` queues a block after `jitThreshold`
calls and keeps interpreting it until its code is installed. Compiled code and interpreted code share frames and are
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

The array methods that call back (`map`, `filter`, `forEach`, `some`, `every`, `reduce`) read an element of a dense
`JSArray` directly and add elements to a result array without a property descriptor (`JSArray.createIndexFast`), the
other cases taking the specification's steps. The checks are made for each element, on the array's state at that
moment, and nothing about either array (length, storage, extensibility) is kept across a callback, which may freeze
the result array (reachable through `Symbol.species`), make it sparse, delete elements of the source or put getters
on `Array.prototype`. Fast paths in other engines have broken exactly there, by trusting a length or an element store
read before the callback.

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
