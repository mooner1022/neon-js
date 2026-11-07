# Python on NeonJS: current state and requirements (draft)

Status (2026-11-05): a review, not a plan that has been accepted. Nothing in it is implemented. This draft sums up the
current state of the code and what running Python on NeonJS's execution infrastructure would need (T0/T1/T2, the
code definer, the sandbox), with C native libraries compiled to wasm.

---

## 0. Summary

1. **T2's IR is not language-neutral yet.** An IR instruction's `op` is the JS bytecode opcode itself; value kinds are
   JS kinds; exits resume in the JS interpreter. "Running Python on T2's IR as it is" is not possible. What can be
   reused is the **IR skeleton** (SSA CFG, FrameState, exits, OSR, codegen, the passes' structure) and the
   **backend contracts** (`CompiledCode`, `CodeDefiner`/dex, `JitQueue`, code versions). Each instruction's semantics
   has to be written again for Python.
2. **Recommended structure**: a separate Python front end, bytecode, interpreter and object model, plus the T2 IR core
   split into a "language-neutral core + per-language dialects". Do not compile Python to the JS bytecode (§2).
3. **Recommended order**: Python bytecode + interpreter (with inline caches and profiles) first. Then T2 "generic mode"
   (P1 parity level) as the baseline compiler, then speculation. This follows the order the JS side took. T2 is still
   at P4/P5 and changing, so the IR split has to happen alongside that work.
4. **C via wasm has two halves.** The wasm runtime on the JVM is the easier half. The hard half is a **CPython C-API
   bridge**: CPython extensions assume `PyObject*` structs, reference counts and type slots in linear memory, while
   our objects live on the JVM heap. This is the riskiest unknown in the whole plan, so the first spike should check it
   (§6).
5. Scale: T2 alone was estimated at ~15K lines, and neonjs-core is 54.8K lines today. The Python runtime + stdlib C
   modules + wasm + C-API bridge together are **larger than the current core**.

---

## 1. Current state

### 1.1 The project overall

| module | Kotlin lines (main) | notes |
|---|---|---|
| neonjs-core | 54,782 | parser, compiler, interpreter, T1/T2, runtime, builtins |
| neonjs-intl | 5,051 | ICU4J |
| neonjs-node | 2,304 | + Node's own JS ported by `neonjs-node/tools/port_node.py` |
| neonjs-test262 | 1,910 | Test262/WPT/Node test runners, fuzzer |
| neonjs-net / cli / android / android-d8 | 902 / 396 / 343 / 81 | |

The JS engine is mature: Test262 passes in full in all three modes, and the sandbox, interop and Android/dex paths
are all in place.

### 1.2 Where T2 stands (docs/OPTIMIZING_TIER.md)

- Per the document's status line (2026-10-23): P0–P2 done, P3 under way. Many P4 items have landed since ("Done so
  far"). **P5 (tiering, background queue, tuning, turning it on by default) not started**.
- Off by default (`RuntimeConfig.tier2`, `NeonEngine.Builder.optimizingTier`).
- **Generators and async functions are not compiled by T1, and therefore not by T2 either**
  (`JvmCompiler.canCompile`, `neonjs-core/src/main/kotlin/dev/mooner/neonjs/jit/JvmCompiler.kt:154`;
  `T2.submit` checks the same condition). Python uses generators far more than JS does, so this limit matters more
  for Python (§4.7).
- T2 is designed on the assumption that **a T1 baseline exists** (an invalidated block goes back to the baseline).

### 1.3 How tightly T2's IR is bound to JS (evidence)

| place | what it shows |
|---|---|
| `jit/opt/Ir.kt:109-110` `Inst.op` | "a bytecode instruction (Op) with its own semantics". IR instructions are JS `Op` values 0–190 plus `Ir.*` 1000–1105 |
| `Ir.kt` `Value.kind` | `JT` kinds (`ANY/NUM/BOOL/INT`: `jit/JitTypes.kt:27-32`), i.e. the JS number model (double + int32) |
| `Ir.kt` `InlineSite` | `fn`, `thisValue`, `env`, `construct`: the JS call model |
| `Ir.kt` `Ir.*` operations | `LOAD_SLOT`/`ADD_SLOT` (JS Shape/PropCache), `NEW_THIS`, `CONSTRUCT_RESULT`, `CHECK_TDZ`, `LOAD_GLOBAL_LEX`… |
| `IrBuilder.kt` (1,301 lines) | 141 direct references to `Op.`/`JitRt`/`Interpreter`/`JSObject`/`Shape`: it walks the JS bytecode |
| `IrCodegen.kt` (1,861 lines) | 84 such references; the generic form of each op is the T1 `JvmEmitter`'s JS helper call |
| `jit/Deopt.kt` | resumes in `Interpreter.run` (JS); `Deopt.Inlined.fn: JSClosure` |
| `vm/Frame.kt:7-14` | `fn: JSClosure?`, `thisValue`, `newTarget`, `env: Env?`: a JS frame |
| `compiler/CodeBlock.kt:127` `ValueProfile` | JS kinds INT/DOUBLE/STRING/BOOLEAN/NULLISH/OBJECT/FUNCTION |

The **language-neutral parts** are: the `Graph`/`Block`/`Phi`/`FrameState` structures, the verifier and printer, the
codegen's local allocation, phi parallel copies, exit methods, OSR entries and code size budget, `T2Code`'s deopt
counter and invalidation policy, and the class definition path.

ARCHITECTURE.md's "Contracts for other code generators" (`docs/ARCHITECTURE.md:295`) is the seam meant for this kind
of extension. Its WebAssembly entry (`:318`) says wasm functions do not go through the CodeBlock pipeline and share
only `CodeDefiner` and an identity cache.

### 1.4 WebAssembly

**No implementation.** The only references are comments: the `"WebAssembly.Module"` toStringTag example in
`vm/Modules.kt:23,206` (source-phase imports), and a Test262 harness comment in `neonjs-test262/.../Exec.kt:306`. On the JS side too, WPT `encoding/` has some tests that fail because WebAssembly is missing
(README). A wasm runtime would therefore also serve **JS's `WebAssembly` API** (§5.2).

### 1.5 Reuse classification

| use as is | reuse as a pattern, code is JS-bound | not reusable |
|---|---|---|
| `CodeDefiner` (hidden classes / dex), `DexConverter`, `CodeCache`, `JitBackend`, `JitQueue` | `Frame`, the `Deopt` protocol (in-place frame conversion, inlined frames, pending throw) | the meaning of `Op` (ADD's ToPrimitive, EQ, CALL's `this`…) |
| `Agent`'s limits (time, instructions, depth, memory), external jobs, event loop | Shape/PropertyMap/PropCache: Python attribute lookup is MRO + data descriptors first, so the cache algorithm differs; hidden classes for instance `__dict__`s are reusable | `TypeAnalysis`/`Kinds` (Python int → BigInteger on overflow, not double) |
| `HostBridge`/`HostAccess` (host interop, overload choice) | SSA CFG + LICM/KnownShapes/ScalarReplacement/Unswitch (keyed today on JS shapes and effects) | JS strings (UTF-16 `CharSequence`; Python `str` is indexed by code point) |
| `SandboxPolicy`, `NeonContext` lifecycle, `MethodSizeTest` | `ValueProfile`, `CallFeedback`, the fuzzer and Test262 runner structure | all builtins, the RegExp engine (Python `re` has different semantics) |
| | `tools/port_node.py` (porting an external stdlib) | |

---

## 2. Integration strategies

| | A. Python → JS bytecode | B. Add Python ops to the shared `Op` table | **C. Separate front end + neutral IR core (recommended)** |
|---|---|---|---|
| idea | Compile Python to the existing Op set; Python semantics as helper CALLs | Add Python ops to `Op.kt`, `Interpreter`, T1 and T2 | Python's own `PyCode`/interpreter/object model; split the T2 IR into core + dialects |
| fast start | ◎ | △ | ○ |
| T2 speculation | ✗ every operator is an opaque helper CALL, nothing to speculate through | ○ | ◎ |
| deopt | resumes in the JS interpreter → Python state rebuilt only through the JS frame model | possible | resumes in the Python interpreter |
| JS regression risk | low | **high**: `Op.kt` is a generated table, and `Interpreter.run`/`slowOp`/`rareOp` must each stay under 8,000 bytes, so adding ops eats into that budget | medium (the IR split refactor; checked by Test262 + stress modes) |

**Recommendation: C.** Reasons:

- What makes T2 fast is the "generic op → guard + typed op" rewrite, and that needs a dialect that knows what each op
  means. In A that information disappears.
- Python's frame model (fast locals, cells/free vars, value stack, exception table, `f_back`/`f_locals` introspection)
  does not map onto the JS `Frame`. Exits must be able to resume in **the interpreter of the same bytecode**.
- B mixes two semantics in one dispatch loop and threatens JS performance (method size limits, a larger dispatch
  table) and correctness.

### 2.1 Shape of the split

- `jit/opt/core`: `Graph`, `Block`, `Phi`, `Value`, `FrameState` (holding the dialect's resume info), effects, the
  verifier, the printer, the codegen framework (locals, parallel copies, exit methods, OSR entries, code size budget),
  common typed ops (`Int32Add`, `Int64AddExact`, `Float64*`, comparisons, `Box`/`Unbox`, `Check*`).
- `Dialect` interface: op descriptors (name, operand count, effects, can it exit, how its generic code is emitted),
  kinds and representations, the bytecode → IR builder, the deopt resume entry (`resume(frame, pc, values, inlined)`),
  and which passes apply (KnownShapes is JS-dialect only, and so on).
- `jit/opt/js`: the current `IrBuilder`, `Op` handling, Shape guards, `NEW_THIS`… moved here.
- `python/jit`: the Python dialect.

Check while splitting: Test262 passes with `t2Stress`, `t2Stress + deoptStress` and `osrStress`, and NeonBench and
the `MethodSizeTest` results do not change. **This refactor should be timed against T2's P4/P5 schedule**: splitting
too early adds to the cost of every change made while the IR is still moving.

### 2.2 Python's baseline

T2 assumes a T1 baseline. A separate T1 for Python (a direct bytecode → JVM translator) is a lot of work, so:

- **T0** Python interpreter (adaptive specialization in the style of PEP 659: inline caches + profiles)
- **T2-generic**: IR → codegen with speculation off. Every op is a helper call, as at P1 parity. This takes T1's role
  (the baseline). For JS this mode already exists (`T2.SPECULATE`, `-Dneonjs.jit.t2Speculate=false`, `jit/opt/T2.kt`).
- **T2-spec**: speculation, inlining and LICM. When it is invalidated, it goes back to T2-generic.

To do this, the code version contract (`baseline`/`compiled`) has to allow that "a baseline can be T2-generic code".

---

## 3. Python runtime requirements

### 3.1 Target version

- Pick one target language/ABI version. If Pyodide wheel compatibility (§5.3) matters, match the version tied to that
  platform tag: per the pyodide-build docs, `pyemscripten_2026_0_wasm32` = Python 3.14 / Emscripten 5.0.3. A wheel
  built for one platform version does not work on another.
- If wheel compatibility does not matter, choose freely; the language features to support are about the same in 3.13
  and 3.14 (§3.2).

### 3.2 Front end

- **Tokenizer**: INDENT/DEDENT, implicit line joining, f-strings tokenized as nested grammar (3.12, PEP 701), string
  prefixes, number literals (`_`, complex), Unicode identifiers (NFKC normalization: whether the `unicode/` tables
  are enough needs checking; `java.text.Normalizer` is an option).
- **Parser**: CPython's `Grammar/python.gram` (PEG) as the reference. A hand-written recursive-descent parser is
  consistent with the JS parser (acorn style). The soft keywords `match`/`case`/`type`, `except*`, PEP 695 type
  parameters, decorators, walrus, positional-only/keyword-only parameters.
- **Symbol tables**: LEGB, `global`/`nonlocal`, cells/free vars, class scopes not visible from methods, the `__class__`
  cell (zero-argument `super()`), comprehension scopes (inlined in 3.12+, PEP 709), annotation scopes (PEP 695/649:
  3.14 evaluates annotations lazily), the scope analysis `exec`/`eval`/`locals()` need. Different rules from
  `Scopes.kt`, so a separate implementation.
- Errors: `SyntaxError` with exact positions (line, column, end position).

### 3.3 Bytecode and interpreter

- `PyCode`: instruction array, constants, names, fast locals, cell/free vars, **exception table** (the zero-cost
  exception table of 3.11+), position table, flags (generator, coroutine…). Its own format is fine: no need to
  reproduce CPython's bytecode, but keeping the same concepts keeps `dis` and the frame-related APIs easy.
- Interpreter: split into `run`/`slowOp`/`rareOp` like the JS interpreter, **every method under 8,000 bytes**
  (add the Python package to `MethodSizeTest`).
- **Inline caches in the interpreter** (the core of T0 speed, and T2's feedback): `LOAD_ATTR` (type version tag +
  instance layout), `LOAD_GLOBAL` (globals/builtins dict versions), `CALL` (callee identity, argument shape),
  `BINARY_OP` (operand types), `FOR_ITER` (range/list/tuple fast paths), `LOAD_METHOD`-style calls without allocating
  a bound method.
- Profiles: Python's own kinds (small int, bigint, float, str, bool, None, list, tuple, dict, instances by type…).

### 3.4 Object model

- `PyObject` (type pointer) and **type objects** (with slots like `tp_*` for fast paths, MRO by C3 linearization,
  a **version tag** invalidated by any change to the class or its MRO).
- Attribute lookup: `type.__getattribute__`/`object.__getattribute__` (data descriptors → instance `__dict__` →
  non-data descriptors → `__getattr__`), `super`, metaclasses, `__init_subclass__`, `__set_name__`,
  `__class_getitem__`, `__slots__` (member descriptors).
- Instance `__dict__`: a **split-keys dict** (CPython's key-sharing dict) ≈ the Shape/PropertyMap hidden classes. But
  `obj.__dict__` has to be a real `dict` object (identity, mutation, iteration order), so the views have to keep the
  two in sync.
- `dict`: insertion order, arbitrary hashable keys, `__hash__`/`__eq__` that may run user code (mutation during a
  lookup), version counters (global caches).
- `__del__`/finalizers: the JVM has no deterministic finalization. Without CPython's refcounting, idioms like
  `open(...).read()` that rely on timely file closing depend on GC timing (as in PyPy, GraalPy and Jython). Weak
  references (`weakref`, `WeakValueDictionary`, callbacks) as an external job via `java.lang.ref.Cleaner`/
  `ReferenceQueue`.
- `id()`: needs a stable integer identity (a counter field or an identity map).

### 3.5 Numbers and strings

- `int`: **long fast path + BigInteger**. Overflow promotes (no exception), so T2 needs typed ops that exit on
  `Math.addExact` overflow. Floor semantics for `//` and `%`, `int ** negative` → float, `bool` is a subclass of
  `int`, the int/str conversion length limit (`sys.set_int_max_str_digits`).
- `float`: double, but `repr` is the shortest round-tripping representation (`NumberConv` can be reused),
  `float('nan')` comparisons, `math.fsum`, `round` (banker's rounding).
- `complex`, `decimal` (`_pydecimal` is pure Python), `fractions` (pure Python).
- `str`: **indexing by code point**. A PEP 393-style Latin-1/UCS-2/UCS-4 representation, or UTF-16 + an index cache.
  Surrogate escapes (`surrogateescape`), the `str.*` methods follow Unicode rules (`casefold`, `isidentifier`…).
- `bytes`/`bytearray`/`memoryview` + the **buffer protocol** (also essential for the C bridge, §5.4).

### 3.6 Exceptions, generators, coroutines

- Exceptions are used as control flow (StopIteration, KeyError, AttributeError in `hasattr`/`getattr(..., default)`).
  Exceptions without JVM stack traces (`JSException`'s `writableStackTrace=false` approach), **traceback objects built
  lazily**, `__context__`/`__cause__`/`__suppress_context__`/`__notes__`, `ExceptionGroup`/`except*`.
- `with`/`async with`, `try/finally` + `return`.
- Generators, `yield from`, `send`/`throw`/`close`, `async`/`await`, async generators, `asyncio` (run on the Agent's
  event loop and external jobs).
- **Generators in compiled code**: this cannot be left to the interpreter as it is for JS (§4.7).

### 3.7 Standard library

- **Pure Python `Lib/*.py`**: vendor CPython's (PSF license; the same approach as `neonjs-node/tools/port_node.py`:
  copy files + apply patches with a script). Many have pure fallbacks: `_pydatetime`, `_pydecimal`, `_py_abc`,
  `heapq`, `bisect`, `json`'s pure path.
- **Rewrite in Kotlin** (these are C modules in CPython; this is the real cost):
  - core: `builtins`, `sys`, `_io`, `_thread`, `_weakref`, `_abc`, `_codecs`, `_signal`, `_warnings`, `_imp`/
    `importlib` hooks, `marshal`
  - performance/semantics: `_sre` (the engine under `re`; **Python regex semantics, not the JS RegExp engine's**.
    `re._parser`/`re._compiler` are Python, so only the `_sre` VM is needed), `_collections`, `itertools`,
    `_functools`, `_operator`, `math`, `cmath`, `_struct`, `binascii`, `_random`, `_hashlib`/`_md5`/`_sha*`,
    `_bisect`, `_heapq`, `array`, `unicodedata`, `_json`, `_datetime`, `_pickle` (or pure `pickle`), `zlib`
    (java.util.zip, as `neonjs-node`'s ZlibBinding does)
  - OS: `posix`/`os`, `time`, `select`, `socket`, `_ssl`: **deny by default**, opened only within policy (§3.8).
- Reference: check the module lists of RustPython and GraalPy, which faced the same choice (they have not been
  verified for this draft).

### 3.8 Sandbox, embedding, interop

- Use the Agent's limits as they are: instruction budget (interpreter back edges/calls, T2 back edges), time, call
  depth, memory (allocation budget; wasm linear memory counts too), string length.
- Deterministic mode: hash randomization (`PYTHONHASHSEED`-style fixed seed), `random`, `time`, `os.urandom`.
- File system and network: deny by default. A virtual FS (only paths the host provides) and `socket` through
  `neonjs-net`'s policy.
- `import`: through a host `ModuleLoader`-style SPI, with a whitelist/virtual FS for `sys.path`.
- Interop: `HostBridge`'s conversion and overload rules applied to Python values (Java objects ↔ Python objects,
  `__getattr__`-based host objects). Python ↔ JS calls (polyglot) as a later phase.
- Threads: one Agent = one Python interpreter state. Real `threading` needs a GIL-like design that does not conflict
  with the context lock (`ContextGate`). Decide early whether to start single-threaded (only `_thread` stubs).
- API: `NeonEngine.builder().language(PYTHON)`-style or a separate `NeonPython` entry point, plus a CLI option.

---

## 4. What T2 needs to change for Python

1. **The IR split** (§2.1). Op descriptors that carry semantics and effects; switching on `Op` constants out of the core.
2. **Kinds/representations**: add `INT64` (Python small int), exits on `Math.*Exact` overflow, the floor `//`/`%`
   sequence, BigInteger stays an Object.
3. **Profiles and guards**: `CheckType(obj, type)` + `CheckTypeVersion` (class mutation invalidates), instance layout
   guards (split-keys dict ≈ Shape), `CheckDictVersion` (module globals/builtins: the counterpart of JS's
   `LOAD_GLOBAL_LEX`/`SLOT`).
4. **Calls**: Python's argument binding (positional/keyword/defaults/`*args`/`**kwargs`) is heavy, so inline only
   simple positional calls. `func.__code__` and `__defaults__` can be reassigned, so guard on the function object's
   code/defaults identity.
5. **Deopt**: resume in the Python interpreter frame (fast locals, cells, value stack, exception table position). The
   Frame needs to become abstract (`Frame` → a common base + `JsFrame`/`PyFrame`), or a separate `PyFrame` with the
   Deopt protocol implemented again for it.
6. **Frame introspection**: `sys._getframe()`, `f_locals`, `f_lineno`, `f_back`, tracebacks, `locals()`,
   `inspect.currentframe()`. Inlined frames must be materializable on demand. `sys.settrace`/`setprofile` turn T2 off
   (or invalidate everything) for the whole context, otherwise debuggers, coverage and pytest's assertion rewriting
   break.
7. **Compiling generators**: Python's for-loop iterators, generator expressions, `asyncio` and context managers
   (`contextlib.contextmanager`) all run on generators. T2/T2-generic should compile generators as **state machines**
   (a resume dispatch + locals saved in the frame), and inline simple generators into for-loops (V8/PyPy-style
   generator inlining) later. Applying the same work to JS removes the T1 limit too.
8. **Intrinsics**: `len`, `isinstance`, `range` iteration, `list.append`, `dict.get`, `str` methods, `math.*`, behind
   a guard that the builtins are the originals (the counterpart of JS's `CheckIntrinsic`).
9. **Code size**: the same 8,000-byte (HotSpot) / 10,000-dex-code-unit (ART) limits. Python's generic ops have more
   helper calls, so the budget runs out sooner.

---

## 5. C native libraries: wasm

### 5.1 Two halves

```
 C extension source ──(wasi-sdk + our Python.h, ahead of time)────────► .wasm
                                                                      │
 Python runtime (JVM)                                                 ▼
   PyObject (JVM heap) ◄──── C-API bridge (handles/mirrors, refcount↔GC) ────► wasm instance (linear memory)
                                    ▲                                         │
                                    └─────── imports: PyLong_FromLong, PyArg_ParseTuple, ... ◄──┘
```

- **The wasm runtime** (decoding, validation, execution, memory, tables, imports/exports): a well-defined
  specification with a test suite (the spec tests). The easier half.
- **The C-API bridge**: emulating CPython's ABI. The harder half, and the risk of this whole plan.

### 5.2 Wasm runtime

| | build our own | adopt Chicory |
|---|---|---|
| what it is | decoder/validator/interpreter + wasm → JVM bytecode through `CodeDefiner` | pure-Java wasm runtime (no JNI): interpreter + compiler to JVM bytecode |
| Android | can reuse the existing `CodeDefiner`/dex path as it is | an experimental backend compiling straight to Dalvik bytecode at run time was announced in July 2025; its maturity in 2026 is **unconfirmed** |
| sandbox integration | instruction budget, interrupts, memory limits all built in | **needs checking** whether its interrupt/fuel hooks fit the Agent's limits |
| fit with the project | consistent with its from-scratch policy (RegExp, Temporal…) | external dependency (Apache-2.0) |

- Decided (§9): **our own runtime, NeonWasm** (`neonwasm`). An interpreter and the spec tests first; then wasm becomes
  **a dialect of the neutral core** (docs/IR_NEUTRALITY.md): wasm's value types map onto the core's representations
  (i32/i64/f64 to `Rep.INT`/`LONG`/`DOUBLE`; f32 needs a `Rep.FLOAT`), and the core gives it SSA, on-stack
  replacement, interrupt budgets, the tiering policy and `CodeDefiner` (hidden classes on the JVM, dex on Android).
  Chicory stays a reference.
- **Large functions** are the translation's main risk: C compiled with inlining makes wasm functions past the JVM's
  64 KB method limit and HotSpot's 8,000-byte JIT limit (CPython's own evaluation loop, for §5.3's compatibility mode,
  certainly). They need splitting; one left to the interpreter is slow.
- wasmtime-java and other native bindings conflict with the Android/no-natives constraint, so they are out.
- Needed features: MVP + multi-value, bulk memory, sign-ext, non-trapping float-to-int, reference types (function
  tables), **exception handling** (C++ extensions; or emscripten's `invoke_*` trampoline style), setjmp/longjmp
  (wasm EH or something like Asyncify), SIMD optional (can be turned off at build time). Threads/shared memory later.
- Memory representation: wasm32 is up to 4 GiB, but a JVM `byte[]` is ~2 GiB at most. A `byte[]` + little-endian
  access (`ByteBuffer`/`VarHandle`; check `VarHandle` availability on Android) or splitting. The FFM `MemorySegment` is
  not available on Android.
- Sandbox: back-edge/call budgets in wasm code, `memory.grow` counted against the memory limit, a cap on stack depth
  (wasm → Python → wasm re-entry).
- **Side effect**: the same runtime can also provide **JS's `WebAssembly` API** (fewer WPT encoding failures, more of
  the npm ecosystem). One engine serving two uses improves the cost-benefit.

### 5.3 Toolchain and ABI choices

| choice | what | pros | cons |
|---|---|---|---|
| **(1) our own SDK + limited API (abi3)** | wasi-sdk + our `Python.h` (`Py_LIMITED_API`), extensions rebuilt from source | the smallest API surface. In the 3.12+ limited API, `Py_INCREF`/`Py_DECREF` are opaque function calls (gh-105387), so refcounts can be emulated without struct access | many key packages (numpy…) do not support the limited API |
| **(2a) NeonPy + Pyodide's extensions** | load prebuilt wheels like `pyemscripten_2026_0_wasm32` (PEP 783) into NeonPy | no need to build numpy/pandas/scipy and the like | they are built against the full CPython API: every object passed to C must be a CPython struct in linear memory (`Py_INCREF` writes `ob_refcnt`, macros read layouts), plus emscripten dynamic linking (`dylink.0`, GOT, `__memory_base`) and emscripten's JS-implemented imports in Kotlin. Bound to one Python/emscripten version. The cpyext problem, years |
| **(2b) Pyodide compatibility mode** | Pyodide as it is (its CPython main module and its packages) on NeonWasm | Pyodide's package set (numpy, pandas) works, unmodified; no ABI to emulate (CPython is there) | only emscripten's runtime imports, the `dylink.0` loader and a virtual FS to write, but that Python is CPython at wasm-translated speed, not NeonPy (no T2), and its objects are not NeonPy's (a bridge, as Pyodide's JS ↔ Python one) |
| **(3) HPy** | a handle-based API | cleanest for GC/handles | packages ported to HPy are few (current status **unconfirmed**) |
| **(4) ctypes/cffi-style FFI** | Python calls plain C libraries (no CPython API) compiled to wasm | **by far the most feasible**: the bridge is only a type mapping + memory copies | not CPython extensions (no numpy) |

- Decided order (§9): **(4) → (1) on the main path; (2b) once NeonWasm runs; (2a) only on evidence from (2b)**. (4)
  gets the wasm runtime, Python integration and the sandbox up quickly; (1) is the first CPython extension
  compatibility; (2b) gives Pyodide's packages to those who need them, and is NeonWasm's hardest test (CPython itself)
  and a differential reference for NeonPy.
- Whatever the choice: pure-Python wheels (`py3-none-any`, from PyPI or Pyodide) run on NeonPy as they are, and
  Pyodide's build recipes and patches are where our own wasi-sdk builds of a package start.
- Toolchain (§9): **wasi-sdk** (clang/LLVM's wasm32 backend with wasi-libc) builds C for NeonPy: plain modules whose
  imports are WASI and ours, no JS glue. Emscripten only builds Pyodide's own packages (2b). Our own C compiler is not
  worth it (C17 with GCC extensions, an optimizer, a libc: years for nothing a build tool lacks).
- Running CPython itself compiled to wasm (as Pyodide does) as the main path would give up T2 entirely, so it is not
  the main path: it is (2b), the **compatibility mode**, and a **differential testing reference**.

### 5.4 Designing the C-API bridge (for (1)/(2a))

- **Handles/mirrors**: when a JVM object goes to C, it gets a mirror struct in linear memory (`ob_refcnt`, `ob_type`,
  + per-type layouts the macros read directly: `PyTupleObject.ob_item`, `PyListObject`, `PyBytesObject.ob_sval`,
  `PyUnicode` data…) + a JVM ↔ pointer table. The cpyext (PyPy) and GraalPy approach. With the limited API, the
  structs that need mirrors are far fewer.
- **Refcount ↔ GC**: while a mirror's refcount > 0, the table holds the JVM object strongly; at 0 it becomes weak.
  Objects of C-defined types (`tp_basicsize`) live in linear memory, with a JVM proxy that frees them when unreachable
  (`Cleaner`). **Cycles across the boundary leak**: a known cpyext problem.
- **Type objects**: turning C `PyTypeObject`/`PyType_Spec` slots (`tp_call`, `nb_add`, `sq_item`, `tp_getattro`…)
  into Python type slots (the call path JVM → wasm).
- **Error indicator**: `PyErr_SetString` → a per-thread pending exception, NULL/-1 return conventions. No unwinding
  crosses the wasm boundary, which is an advantage.
- **Buffer protocol**: `bytes`/`bytearray`/`memoryview` ↔ linear memory. The choice between copying and keeping JVM
  objects' storage in linear memory decides the performance of data-heavy extensions.
- **Re-entry**: C → `PyObject_Call` → Python (T2 code included) → back into wasm. Sandbox interrupts must work in both
  directions.
- **GIL/threads**: the C API assumes the GIL (`PyGILState_*`, `Py_BEGIN_ALLOW_THREADS`). Under a single-threaded
  model these are no-ops.
- **libc/syscalls**: implement WASI preview1 (`fd_*`, `clock_*`, `random_get`…) over the sandbox policy: the virtual
  FS, the deterministic clock and seeded randomness connect naturally.

### 5.5 Being realistic about scope

- Pure C libraries (zlib, sqlite, libyaml-style) through (4): feasible.
- Small and medium extensions through the limited API (1): feasible, depending on how well the bridge holds up.
- numpy/pandas-scale on NeonPy itself ((2a), or the non-limited API): **a separate, multi-year project.** GraalPy
  spent years here. The goal is explicitly "the small and medium extensions first"; numpy/pandas come through (2b).

### 5.6 Where native code is compiled, and where it runs

- **C → wasm: ahead of time only**, on a build machine (a package's CI, our package index). Not on devices: clang
  compiled to wasm could run on NeonWasm, but at tens of MB and slowly, for no common need.
- **The `.wasm` is CPU-independent**: one build for every Android ABI and the desktop JVM, where native `.so` files
  are one per ABI. One wheel platform tag of ours suffices; an index of prebuilt packages (as Pyodide keeps) spares
  users compiling.
- **wasm → code runs on the device**, at a time the engine picks (§9's decision 2), as JavaScript's does:

  | when | for | cost |
  |---|---|---|
  | interpreted | immediate start, no generated code (the safest for store policies) | the slowest |
  | translated on the device when hot (default, with the interpreter first) | packages downloaded at run time too | translation time and memory; ART runs dex loaded at run time with its JIT, not ahead of time |
  | translated when the app is built (a Gradle plugin, later) | packages bundled with the app | none at run time: ART compiles them ahead of time (baseline profiles apply) |

  On the device this is `neonjs-android`'s path as it is: classes to dex (`DexCodeDefiner`), loaded in memory
  (`InMemoryDexClassLoader`, API 26), optionally cached as read-only files in `codeCacheDir` (Android 14's rule for
  dynamically loaded code) and reused, keyed by the module's hash and the translator's version.
- **Google Play**: the Device and Network Abuse policy forbids downloading executable code (dex, JAR, `.so`) from
  outside Play, except "code that runs in a virtual machine or an interpreter where either provides indirect access to
  Android APIs"; interpreted code loaded at run time must not allow policy violations. Sandboxed wasm and Python fit
  the exception as JavaScript engines do; downloaded wasm translated to dex on the device is less clear-cut and
  should be checked against the policy's current text before release. The conservative setting: packages bundled and
  translated at build time; downloaded ones interpreted.

---

## 6. Phased plan (sizes are rough, in the style of OPTIMIZING_TIER.md)

| phase | contents | exit criteria |
|---|---|---|
| **S0: risk spikes** (2–4 weeks) | ① wasm interpreter prototype (or Chicory) + a minimal C-API bridge: a handwritten C extension (`PyArg_ParseTuple`, `PyLong_*`, `Py_BuildValue`, one type definition) compiled with wasi-sdk and called from a **stub Python object model**. ② A sketch of the IR split: move one op of the JS dialect behind a `Dialect` interface and confirm Test262 does not change | a C function called from Python; refcount/GC experiment; go/no-go on the split |
| **Y1: Python core** (~15–20K) | tokenizer, parser, symtable, `PyCode`, interpreter (with inline caches), the core object model, int/float/str/list/dict/tuple/set, exceptions, generators, import (vendored Lib), `builtins`/`sys` | a CPython `Lib/test` subset passes (known-failures list); differential fuzzer against CPython clean |
| **Y2: stdlib + sandbox** (~15K+) | the Kotlin C modules of §3.7, sandbox policy, host interop, CLI | `re`, `json`, `datetime`, `collections`, `itertools`, `asyncio` basics; `SecurityTest`-level sandbox tests |
| **Y3: T2 generic** (~5K + split) | IR core/dialect split, Python dialect, T2-generic as the baseline, OSR, generator state machines | Python tests pass with `t2Stress` + `deoptStress`; Test262 unchanged; MethodSizeTest |
| **Y4: T2 speculation** (~5–8K) | small int/float speculation, type versions + attribute slots, dict-version globals, inlining, intrinsics, LICM | in-house benchmark (the pyperformance subset is a candidate) faster than the interpreter by N× |
| **W1: wasm runtime, NeonWasm** (~8–12K; parallel with Y1) | validation, interpreter, WASI preview1, sandbox integration, then translation as a dialect of the core (`Rep.FLOAT`, large functions split), (optional) JS `WebAssembly` API | the wasm spec tests interpreted and translated; WPT `wasm/jsapi` (if the JS API is done) |
| **W2: FFI (4)** (~3K) | ctypes/cffi-style calls into C libraries | zlib/sqlite compiled to wasm usable from Python |
| **W3: C-API (1)** (~15K+) | handles/mirrors, type slots, buffer protocol, our SDK (`Python.h`, libpython stub, build script) | a few limited-API packages built and their tests passing |
| **W4: Pyodide compatibility mode (2b)** (~3–5K) | emscripten's runtime imports in Kotlin, the `dylink.0` loader, a virtual FS, Pyodide's CPython and packages on NeonWasm, a bridge to NeonPy's objects | Pyodide's own tests of numpy/pandas (a subset) pass on the JVM and on Android |
| **W5: under review** | Pyodide's extensions inside NeonPy (2a) | only on evidence from W4 |

S0 is chosen by **risk**, not convenience. The parser is a known problem; whether the C-API bridge is feasible and
whether the IR split stays JS-regression-free are the unknowns.

---

## 7. Testing

- CPython's `Lib/test` (regrtest) as the Test262 counterpart: a known-failures list (`python-known-failures.txt`), a
  pass count tracked in CI.
- Differential fuzzer: compare against host CPython (an extension of `Fuzz.kt`'s interpreter-vs-modes comparison):
  outputs/exceptions in every tier.
- Python versions of the stress flags: `t2Stress`, `deoptStress`, `osrStress`.
- wasm: the official spec tests (`wast`), the WASI testsuite.
- C-API: a test extension for each piece of API surface + refcount leak checks.

---

## 8. Cross-cutting constraints

- **Android/ART**: no natives; generated code goes through dex (the size limits apply the same way to Python T2 and
  wasm AOT); interpreter method sizes; per-allocation cost (ART allocates expensively, so avoiding Python object boxes
  matters more).
- **Licenses**: CPython `Lib/` and headers are PSF (NOTICE updates needed); Chicory Apache-2.0; if referring to GraalPy
  (UPL) and Pyodide (MPL-2.0) code, **no copying without review**, ideas only.
- **Module layout (proposal)**: `neonjs-core` (keeps the neutral IR core and the JS dialect) / `neonpython`
  (NeonPy: front end, runtime, builtins, vendored Lib) / `neonwasm` (runtime, WASI, optional JS API) / `neonpython-capi`
  (bridge + SDK). The project name and Maven coordinates (`neonjs-*`) need a separate decision about a platform rename.
- **Effort**: over everything above, larger than the current core (54.8K). Each phase should end with its exit
  criteria met, so the plan can stop at any phase (the same principle as OPTIMIZING_TIER.md's "Risks").

---

## 9. Decisions

Made (2026-11-05):

1. Target: **Python 3.14** (the language; if Pyodide compatibility comes later, `pyemscripten_2026_0_wasm32` is the
   matching tag).
2. Wasm runtime: **our own**, integrated with the engine: an interpreter, and translation to JVM bytecode through
   `CodeDefiner` (dex on Android), so that when code is translated (ahead of time, on first use, when hot) is the
   engine's choice, on the JVM and on Android alike.
3. C extension scope, first target: **(4) FFI and (1) the limited API**. (2) Pyodide/pyemscripten compatibility later,
   if there is room.
4. **The IR split comes first** (docs/IR_NEUTRALITY.md): the optimizing stages made as language-neutral as they can be,
   so that a language implementation gets JVM/ART-shaped code without caring much about optimization.
5. Threads: **single-threaded first, designed with a GIL in mind** (per-interpreter state reachable from one place,
   no global mutable state in the runtime, the points where a GIL would be released marked).
6. **JS's `WebAssembly` API goes with the wasm runtime.**
7. Naming: the Python implementation is **NeonPy**; its module, artifact and package are `neonpython`
   (`dev.mooner.neonpython`), as GraalPy's repository is `graalpython` (PyPI's `neonpy` is taken by an abandoned
   2019 placeholder, `neonpython` is free). JavaScript stays NeonJS; the wasm runtime would be NeonWasm (`neonwasm`).
   The neutral core's own name comes with its module split.

Made later the same day:

8. **NeonPy's C toolchain is wasi-sdk** (clang/LLVM, wasi-libc): C → wasm ahead of time only. Emscripten only for
   Pyodide's own packages (2b). No C compiler of ours (§5.3).
9. **wasm → JVM/dex is ours (NeonWasm)**: an interpreter first, then a dialect of the neutral core (§5.2).
10. **Native code ships as `.wasm`** (CPU-independent) and runs on devices interpreted, translated when hot (cached),
    or translated at app build time; downloaded packages within Play's policy (§5.6).
11. **Pyodide's packages: through a compatibility mode (2b)**, Pyodide as it is on NeonWasm, after W1; the main path
    stays NeonPy with (4) and (1); Pyodide's extensions inside NeonPy (2a) only on evidence from (2b) (§5.3).

Consequences for the plan above: §5.2's comparison is settled (our own runtime; Chicory only as a reference); §6's
order starts with the IR split (docs/IR_NEUTRALITY.md §6) instead of S0's split sketch; W1 includes the JS API; Y3
"T2 generic" is the neutral pipeline's baseline mode (no T1 for Python: docs/IR_NEUTRALITY.md §5).

## 10. Representations and the first slice

Decided before any code, since they are the expensive ones to change:

| Python | on the JVM | why |
|---|---|---|
| `int` | `java.lang.Long`, `java.math.BigInteger` past 64 bits (normalized back where it fits) | the kind system's `Rep.LONG` holds the small ones unboxed; arithmetic is `Math.*Exact`, an overflow giving a BigInteger (an exit, in speculating code) |
| `float` | `java.lang.Double` | `Rep.DOUBLE` |
| `bool` | `PyBool.TRUE` / `PyBool.FALSE` | a subclass of int (`True + 1 == 2`): never `java.lang.Boolean`, whose meaning is JavaScript's |
| `None` | `PyNone` | |
| `str` | `PyStr` (a `String` and whether it is all in the BMP: indexing by code point stays O(1) there) | Python indexes by code point, JavaScript's strings by UTF-16 unit: never a bare `String` |
| `list`, `tuple`, `dict` | `PyList` (an ArrayList), `PyTuple` (an array), `PyDict` (insertion-ordered, keys by Python's equality: `1 == 1.0 == True`) | |
| function | `PyFunction` (its code block, the globals of its module; defaults later) | |
| code | a `CodeUnit` (the core's) whose `dialect` is Python's (`PyDialect`): the optimizing tier's core compiles it (docs/IR_NEUTRALITY.md), never T1 | |
| frame | `PyFrame : VmFrame` (its code unit, `PyContext` and globals) | the core's frame (docs/IR_NEUTRALITY.md §10): no JavaScript in it |
| a local without a value | null, the dead value of NeonPy's graphs: `LOAD_FAST_CHECK` raises the UnboundLocalError (no Python value is null: None is `PyNone`) | one value for the interpreter, exits and on-stack replacement |

`CodeBlock.flags`' GENERATOR, ASYNC and NO_JIT bits mean the same for every language (the core reads them).

The first slice proves the platform: source → tokenizer → parser → compiler → a code block of Python's bytecode →
Python's interpreter → the core's baselines through the JIT's own tiering (`Jit.prepare`) → the same results. Its
language: `def` with positional parameters, `return`, `if`/`elif`/`else`, `while`, `for` over `range` and lists,
`break`/`continue`, assignment and augmented assignment, int/float/str/bool/None, arithmetic and comparisons, `and`,
`or`, `not`, list/tuple/dict literals, indexing, calls, `print`, `len`, `range`. Then, layer by layer, the rest of §3.

### 10.1 The first slice (done, 2026-11-05)

NeonPy, module `neonpython` (package `dev.mooner.neonpython`), on the core's language interface (docs/IR_NEUTRALITY.md):

| layer | file | what |
|---|---|---|
| values | `PyValues.kt` | the table above, `PyRange`, `PyIterator`, `PyException` (type, message, line) |
| runtime | `PyRt.kt`, `PyBuiltins.kt` | each operation's meaning, once, for the interpreter and compiled code alike; `print`, `len`, `range`, `str`, `repr`, `int`, `float`, `bool`, `abs`, `min`, `max`, `sum`, `list`, `tuple`; list, dict and str methods |
| front end | `PyLexer.kt`, `PyParser.kt` | Python's tokens (INDENT/DEDENT, ints of any size, strings and their escapes); a recursive-descent parser of the slice's grammar (CPython's precedence); what is not supported yet is a SyntaxError saying so |
| compiler | `PyCompiler.kt`, `PyOp.kt` | a code block per module and function: registers for locals (parameters first) and temporaries, an operand stack empty at every statement; `LOAD_FAST_CHECK` where a local may have no value (a dataflow pass, as CPython's compiler does); the line table; `PyCodeInfo` in `CodeBlock.dialectData` |
| interpreter | `PyInterpreter.kt` | `PyContext` (output, builtins, the core's agent: `VmAgent`), `PyFrame`, the dispatch loop; calls through `Jit.prepare`, back edges through `Jit.loop`/`Jit.osr` |
| dialect | `PyDialect.kt` | `PyBuilder` (the core's `GraphBuilder`), `PyOps`, `PyKinds` (Objects, and bools as JVM ints for branches), `PyCodegen` (calls of `PyRt`), the resumer |

What the core needed (beyond docs/IR_NEUTRALITY.md's steps): blocks of another language go to the tier's pipeline
(`Jit.canCompile`, `ClassBackend`, `T2.submit`), never to T1; the language interface public (`Dialect`, `Graph`,
`GraphBuilder`, `IrCodegen`, `KindSystem`, `OpTable`, `Emit`, `JvmEmitter`, `Kinds`, `Facts`), marked as no stable
API; `CodeBlock.dialectData`; a graph's identity (the shared baselines' cache) includes its dialect. Then the core
became a module of its own (docs/IR_NEUTRALITY.md §10, `neon-vm`): NeonPy depends on it alone, with no JavaScript on
its class path (no realm, no `Undefined`, no `FunctionKind`).

Tests (`neonpython`, in `tools/check.sh quick`): eleven programs whose expected output CPython 3.14 recorded
(`src/test/resources/programs`), each run interpreted, compiled and adaptive; CPython's exceptions, messages and lines
for eleven errors in each mode; every block a program ran compiled by the tier (none falling back to the interpreter);
a loop continuing in compiled code; the front end's tokens, precedence and errors.

Speed, the baselines only (calls of the runtime for every operation, nothing speculated), ms on this machine:

| program | interpreted | compiled | CPython 3.14 |
|---|---|---|---|
| `fib(27)` | 80 | 30 | 22 |
| a `while` loop of 3M iterations | 288 | 47 | 110 |
| primes below 200K (nested loops) | 800 | 145 | 292 |

Calls are the slow part (a `PyFrame`, `Jit.prepare` and the runtime's dispatch per call).

### 10.2 Speculation (done, 2026-11-05)

With the tier on (`VmConfig.tier2`), NeonPy's blocks get speculating code over their baselines, the core's second
language to get it. The interpreter notes the operand types of each arithmetic and comparison instruction
(`PyProfile`: both Longs, both Doubles, other); where only ints or only floats were seen there, and no exit was taken
there before, `PyBuilder` builds the operation unboxed: `CHECK_INT`/`CHECK_FLOAT` (exit where a value is another),
`INT_OP` (`+ - * // % & | ^`, negation on `long`s: exit where Python's result leaves 64 bits or the operation would
raise), `FLOAT_OP`, `INT_CMP`/`FLOAT_CMP` (JVM booleans). An exit writes the frame and goes on in the interpreter,
which does the operation again (a BigInteger, the exception); the core notes the failed speculation and compiles the
block again without it. Ints are `long`s and floats `double`s in the code (`PyKinds`), boxed where something takes an
Object, checked at on-stack replacement entries (`PyRt.osrKinds`). The core's LICM moves invariant checks out of loops.

The loop of `while i < n: s += i & 7; i += 1`, as built:

```
B3 (pc 8) <- B2, B4 loop
  v2 = phi k0, v9 :int
  v3 = phi k0, v11 :int
  v5 = IntCmp LT v3, v1 :bool          (v1 = CheckInt of the argument n, moved before the loop)
B4 (pc 15) <- B3
  v7 = Int BITAND v3, k1 :int
  v9 = Int ADD v2, v7 [exit at pc 22] :int
  v11 = Int ADD v3, k2 [exit at pc 29] :int
```

Tests: every program also runs adaptive with the tier on; `speculation.py` passes 2**63 in a loop (the exit, the
failure noted, the speculating code installed).

Speed, ms: medians of 5 runs in one JVM (two such runs), each run a new `PyContext` compiling its code again on the
calling thread (`jitThreshold` 2, `backgroundJit` off), so compilation is counted. HotSpot 25, Ryzen 7 9800X3D,
Windows 11; CPython 3.14.6 on the same machine, each program exec'd in its process 5 times. (§10.1's figures came from
another machine.)

| program | interpreted | compiled | adaptive | adaptive, tier on | CPython 3.14 |
|---|---|---|---|---|---|
| `fib(27)` | 79–85 (37–126) | 12–16 | 12–13 | 16–17 | 15–16 |
| a `while` loop of 3M iterations | 113–127 | 18 | 17–18 | 10 | 66 |
| primes below 200K (nested loops) | 343–345 | 66–67 | 65–67 | 31 | 203 |

Speculating code about halves the loops (no boxes, no runtime calls for their arithmetic and comparisons): about 6.5
times CPython's speed there. `fib` gains nothing: calls are most of its time (§10.1), and its 4 ms more are the
tier's compilation (the same 4 ms at `fib(30)`, 44 → 48, and at `fib(32)`, 114 → 118).

Known limits: the deopt stress mode (`-Dneonjs.jit.deoptStress`) adds no exits to NeonPy's code (`PyBuilder` ignores
it), so its exit path is tested by real exits only (overflows, other types); no inlining of calls yet. (Generated
classes are defined in the dialect's class loader, which sees NeonPy's runtime: docs/IR_NEUTRALITY.md §10.)

Next, in order: cheaper calls (the callee's code called directly, inlining through the core's `InlineSite`); then the
language, layer by layer: default and
keyword arguments, closures, exceptions (`try`, `raise`), classes and the object model (§3.4), generators (the core's
suspensions), comprehensions, slices, f-strings, modules.

## 11. The whole language (plan, 2026-11-06)

The goal now is all of Python 3.14's syntax, with its semantics. It must run the same in the interpreter, the tier's
baselines and its speculating code, and be checked against CPython 3.14: programs' output, and the type, message and
line of their errors.

The standard library is not the goal (§3.7 and Y2 come after). What the syntax needs is: the builtins, the exception
classes, the types behind literals, and modules for `import` to import. This is §6's Y1, about 15–20K lines, done as
milestones. Each milestone is commits gated by the quick check and pushed when it is done.

### 11.1 Order

| | what | needs |
|---|---|---|
| M0 | this plan; room for opcodes; runtime calls as table rows in the dialect; a size test of the interpreter's dispatch | — |
| M1 | functions and scopes: defaults, keyword arguments, `*args`/`**kwargs`, keyword-only and positional-only parameters, `lambda`, closures and `nonlocal`, decorators, starred unpacking (calls, literals, targets), `:=`, `del`, `assert`, `global` done right | — |
| M2 | the object model: type objects (the builtins' too), `class`, instances, the MRO, `super()`, the special methods (operators, containers, iteration, calls, `str`/`repr`/`hash`/`bool`/`len`), `property`/`staticmethod`/`classmethod`, `isinstance`/`type`/`getattr`…, class decorators, `__init_subclass__`/`__set_name__` | M1 (`super()` needs the `__class__` cell) |
| M3 | exceptions: the hierarchy as classes, `try`/`except`/`else`/`finally`, `raise … from`, chaining, `with` (several items, parenthesized), `except*` and `ExceptionGroup`, `except A, B:` (PEP 758), `return`/`break` in `finally` (PEP 765's warning) | M2 |
| M4 | generators: `yield`, `yield from`, `send`/`throw`/`close`, generator expressions, list/set/dict comprehensions (done; M4b, the tier's suspensions, done: their code compiled, suspending at YIELD) | M3 (a generator's `close` is an exception) |
| M5 | literals and strings: f-strings as PEP 701 tokenizes them, the format-spec mini-language, `%` and `str.format`, t-strings (PEP 750, `string.templatelib`), bytes, complex, sets, slices and `slice`, `...` | M2 |
| M6 | `match` (every pattern; or-patterns binding the same names, checked when compiling) | M2 |
| M7 | `import`: module objects, a host-given loader (no file system unless the host gives one), a minimal `sys` | M2 |
| M8 | `async def`, `await`, `async for`/`async with`, async generators and comprehensions (driven with `send` in tests: no asyncio; done, their code compiled as generators' is) | M4 |
| M9 | deferred annotations (PEP 649/749: `__annotate__`; done, M9a), type parameters and `type` aliases (PEP 695) with `list[int]`'s GenericAlias (done, M9b), what is left: private names' mangling, `__static_attributes__` and `__firstlineno__` (done, M9c) | M2 |

### 11.2 Decisions (the expensive ones to change later)

- **Exceptions are objects.** A raised exception is an instance of a BaseException class. The JVM carries it in a
  `PyException`, whose `type`, `message` and `line` stay a view for the host and the tests. A sandbox's termination
  (the core's `TerminationException`) is never a Python exception, so no `except` catches it, in the interpreter or
  in the tier's code (its catch entries' `CATCH_VALUE`).
- **Every value has a type object** (`PyType`), the builtins' values too, and attributes are looked up through the
  type's MRO with descriptors: functions bind, and `property`, `staticmethod` and `classmethod` work as descriptors.
  - A user class's instance is an object of the class with a dict.
  - Builtin values stay what they are (Long, Double, `PyStr`, `PyList`…). An instance of a user subclass of list,
    dict, set, tuple or str is that value with its class set (and a dict).
  - Subclasses of int and float come later.
- **Calls.** `CALL` keeps its fast path for exactly the positional arguments a function takes (§10.2's speed rests
  on it). Keyword arguments are `CALL_KW` (their names a tuple constant), and `*` and `**` are `CALL_EX`. One routine
  binds arguments to parameters, with CPython's messages.
- **Scopes.** A symbol table is built before compiling: local, global, nonlocal, free and cell variables.
  - Class bodies are dicts, and the functions in them do not see their names; `__class__` serves `super()`.
  - Comprehensions are nested functions, as before 3.12. PEP 709's inlining shows only in `locals()` inside a
    comprehension and in tracebacks' frames.
- **Generators keep their frames' slots,** as JavaScript's do (docs/IR_NEUTRALITY.md §9). The interpreter suspends
  at a yield, and the tier's code through the core's suspensions; `yield from` and `await` loop over sends.
- **Exceptions in compiled code** go through the core's handlers (`CodeUnit.handlers`), whose entries have an empty
  operand stack. So the operand stack stays empty at every statement, and what a `try`, `with` or `for` needs across
  its body is kept in registers.
- **New instructions follow a checklist** (on `PyOp`). A plain runtime call is a row of the dialect's table
  (`PyGeneric`); control flow is written by hand.

Made with the object model (M2):

- **Comparisons give bools.** `<`, `==` and the rest are JVM booleans in the tier's code (the BOOL kind), so a user
  `__eq__` or `__lt__` that returns something else than a bool gives its truth value. CPython would give the object
  itself; that is seen only where a comparison's result is a value (`print(a == b)`), as with numpy's arrays.
- **No subclasses of int and float yet.** Their values are Longs and Doubles, which hold no class. A subclass of
  list, dict, tuple or str (set when sets come, M5) is that value with its class set: `PyValue.sub`, the class and
  the instance's dict, null for the type's own values. `PyRt` checks it once per operation (`special`): an instance
  of a subclass goes through its class's special methods, then its base type's paths (the `...Values` functions);
  the type's own values never pay more than that check. The four types' `__new__` and `__init__` make and fill a
  subclass's instances (`list(x)` itself keeps its direct path), and their special methods are in their dicts as
  builtins for `super()` and explicit calls.
- **A class body is a code unit,** run with the class's namespace (a dict) as its locals: LOAD_NAME, STORE_NAME and
  LOAD_FROM_DICT_OR_DEREF read and write that dict, and its `__class__` cell goes to `type.__new__` as
  `__classcell__`, as in CPython.
- **`super()` without arguments** is compiled as `super(__class__, <first parameter>)` where `super` is the
  builtin's name in that function. A function without parameters, or outside a class, gets CPython's RuntimeError.
- **Special methods are looked up on the type,** and only a class's own count: dispatch (`PyDunder`) skips the
  builtins' methods (object's `__eq__`, `__repr__`…), which `PyRt`'s paths for builtin values stand for. `PyRt`
  tries its fast paths first and calls `PyDunder` only where an operand is an instance. Containers compare items by
  identity first, as CPython's do. Whether a class overrides `__getattribute__`, `__setattr__` or `__delattr__` is
  a flag of its type (`PyType.hooks`), set when the class is made or given one; a base given one later does not
  flag its existing subclasses.
Made with exceptions (M3):

- **An exception the runtime raises is a name and a message** (`PyException("TypeError", ...)`, as before), and its
  object is made where Python code first sees it: an except clause, a finally (`PyExceptions.objectOf`; KeyError keeps
  its key, NameError its name). `raise` throws the object's own `PyException`, the same one each time it is raised
  again, so it keeps the line where it was first raised, as CPython's traceback does. Runtime code that catches an
  exception tests its class (`PyException.matches`), never its name.
- **The exception handled is the context's** (`PyContext.handled`): PUSH_EXC_INFO sets it entering a handler and
  keeps the one before in a register; POP_EXCEPT puts it back, also when the handler is left by an exception (each
  handler body is in a range whose handler does it). An exception raised meanwhile gets it as `__context__`, when it
  is raised or (the runtime's) when its object is made.
- **A finally body is copied**, as CPython copies it: once for the normal way out, once for an exception, and once
  for each `return`, `break` and `continue` leaving through it. The ranges such leaving goes through are closed
  around the copied code (a range is segments), so that what leaving runs is not handled by the try it leaves.
- **`raise` ends a block** in the tier's graphs: the exception made by a runtime call, then `Ir.THROW`. A catch entry's
  `Ir.CATCH_VALUE` is `PyExceptions.catchValue`, which lets a sandbox's termination and the JVM's errors through.
- **OSError's errno subclasses** use Linux's numbers for the network errors (Android's), where they differ by platform.
- No warnings yet: PEP 765's SyntaxWarning for a `return`, `break` or `continue` in a finally is not printed.
- **`with`** looks __exit__ up first, then __enter__, on the manager's type (LOAD_SPECIAL), and keeps the bound
  __exit__ in a register; storing the target is inside the range __exit__ handles, as in CPython. Leaving the body by
  `return`, `break` or `continue` calls __exit__ with three Nones (a with is an fblock).
- **`except*`** keeps its state in a register (`PyExceptions.ExcStar`: what the clauses have not matched, the match
  a clause handles, what the clauses raised anew and raised again). Each clause splits what is left (a naked
  exception that matches goes into a group of its own); at the end what was raised anew comes first, then what is
  left and what was raised again, rebuilt in the raised group's shape by the leaves' identity, as CPython's
  `_PyExc_PrepReraiseStar` does; one exception alone is raised as it is.
Made with generators (M4):

- **A generator's frame is interpreted for now** (`PyGenerator`, its code flagged `CodeUnit.GENERATOR`, never handed to
  the JIT; the tier's suspensions are the next step). It keeps JavaScript's conventions, so that the tier's resume
  entries read it as the interpreter leaves it: YIELD writes the value to the frame (`suspendValue`), its pc the
  instruction after and its sp after the pop, and returns SUSPENDED; resuming pushes the value sent at sp and sets
  the frame's mode, which the instruction after reads (RESUME raises a thrown exception there, inside the yield's
  try; SEND, in `yield from`, passes it on to the iterator's throw, or closes it for GeneratorExit).
- **A generator has its own exception handled** across its yields (PyGenerator.handled); the callers' are on a stack
  under it (`PyContext.handledStack`), and the nearest is what a bare `raise` raises again and what an exception gets
  as its context while the generator has none (`currentHandled`).
- **Comprehensions are functions** taking their first iterable's iterator (`.0`), made where they are; list and dict
  comprehensions append to a register, a generator expression is a generator. Set comprehensions come with sets (M5).

Made with literals and strings (M5):

- **Floats are formatted from their exact binary value**, rounded half to even (`BigDecimal`, as CPython's
  dtoa): `f"{2.675:.2f}"` is `2.67`, never Java's `String.format` (which gives `2.68`). The mini-language is
  CPython's for ints, floats and strs (`PyFormat`), str.format and `%` too; a class's `__format__` comes first.
- **Sets are CPython's table** (`PySet`: open addressing with its linear and perturbed probes, its dummies, its
  growth at 3/5 full, pop's finger, the paths of update, intersection and difference), laid out by CPython's hashes
  where they are not randomized (`PyHash`: ints, floats, None, tuples, frozensets, slices), so that a set of ints or
  tuples iterates and prints as CPython's does. A str's hash is the JVM's: CPython randomizes them, so no program can
  rely on the order of a set of strs. `hash()` gives CPython's values for those types.

Made with import (M7):

- **A host gives the modules** (`PyModuleLoader.find(name)`: a module's source, its path, whether it is a package);
  NeonPy never reads a file system. A context without a loader imports its builtin modules only: `sys` (its
  modules, path, argv, stdout and stderr writing to the context's `out` and `err`, exit, exc_info, the recursion
  limit, version), `builtins`, `string` and `string.templatelib`, `__future__`. The registry comes before the
  loader. Other standard modules (math, re...) are not the syntax's: §3.7.
- **import is importlib's**: sys.modules first; a module put there before its body runs (a circular import meets it
  partially initialized, with CPython's message), taken out where its body raises; a submodule an attribute of its
  package; relative imports from `__package__`; `from m import x` an attribute or a submodule; `import a.b as x`
  IMPORT_NAME then IMPORT_FROM, as 3.12 compiles it. A module's attributes are its dict (PEP 562's `__getattr__`
  after). `__spec__` and `__loader__` are None.

Made with coroutines (M8):

- **Coroutines and async generators are generators' frames** (`PyGenBase`: `PyGenerator`, `PyCoroutine`,
  `PyAsyncGenerator`, by the code's kind, `PyCodeInfo.kind`), compiled as generators' are (M4b). `await` is `yield
  from`'s SEND loop over GET_AWAITABLE's iterator (a coroutine itself, else what its type's `__await__` gives). What a
  suspended frame awaits (CPython's `_PyGen_yf`: its pc at the jump back to a SEND) is `cr_await`, and a coroutine
  that has one cannot be awaited again.
- **An async generator's yields are wrapped** (ASYNC_GEN_WRAP), so that the awaitables stepping it (`__anext__` and
  `asend`, `athrow` and `aclose`: `PyAsyncGenASend`, `PyAsyncGenAThrow`, their states and messages genobject.c's)
  tell a value it yields, which ends the awaitable (StopIteration(value)), from one an await in it passes up to the
  driver. aclose throws GeneratorExit in without closing what it awaits (`MODE_THROW_ALL`, CPython's
  `close_on_genexit` 0), so its finally bodies can await.
- **`async for`'s handler covers only the awaiting of the next value** (GET_ANEXT and its SEND loop), not the target
  or the body: END_ASYNC_FOR ends the loop at a StopAsyncIteration and raises anything else again, never making it
  the exception handled. `async with` is `with` with `__aenter__` and `__aexit__` (`__aexit__` looked up first) and
  each call awaited, where `return`, `break` and `continue` leave its body too. LOAD_SPECIAL's messages are 3.14's:
  the type's qualified name, and the hint where the type has the other protocol's two methods.
- **An asynchronous comprehension is a coroutine** (comprehensions are functions here), called and then awaited
  where it is; an async generator expression is an async generator, which any function may make (its first
  iterable's `__aiter__` called there: GET_AITER, the one async instruction outside coroutines' code, a runtime call
  in the tier). The symbol table decides which comprehensions are asynchronous and rejects `await`, `async for`,
  `async with` and asynchronous comprehensions where CPython's does; `yield from` in an async function and a `return`
  with a value in an async generator are the compiler's errors, as in CPython.
- **No event loop**: the tests drive coroutines with send() and throw(). There are no finalizers, so no "never
  awaited" warning, and an async generator dropped while suspended is not closed (CPython's would aclose it).

Made with annotations (M9a):

- **Annotations are evaluated when asked for** (PEP 649, 749): a function's, a class's and a module's annotations
  are the code of an `__annotate__(format, /)` (an annotation scope: function-like, its parameter `.format` to its
  own names), which gives their dict for formats 1 and 2 and raises NotImplementedError above (ANNOTATE_CHECK).
  `__annotations__` calls it once and keeps the dict, with CPython's getters and setters of functions, types (the
  class's `__annotate_func__` and `__annotations_cache__` in its dict; none for builtin types) and modules (kept in
  the module's dict once its body has run). A function's is made before it, around its def (its parameters are
  not visible there); a class's at the end of its body; a module's first (its code compiled after the body's, a
  constant filled then). A function's local annotations are never evaluated, only checked for their errors.
- **An annotation scope in a class reads the class's names first**, through the class's `__classdict__` cell
  (LOAD_FROM_DICT_OR_GLOBALS, LOAD_FROM_DICT_OR_CELL), which type.__new__ points at the class's dict
  (`__classdictcell__`), so a lazily evaluated annotation sees the class as it is then.
- **A module's annotations, and a class's in a compound statement, are conditional**: each records that its
  statement ran (`__conditional_annotations__`, a global or the class's cell) and the `__annotate__` gives only
  those.
- **`from __future__ import annotations`** (PEP 563): annotations are strings, the expression as CPython's
  compiler writes it back (`PyUnparse`, Python/ast_unparse.c's precedences, constants and f-string rebuilding); a
  module's and a class's go into their `__annotations__` dict as they run (SETUP_ANNOTATIONS), a function's
  `__annotate__` gives them.

Made with type expressions and type parameters (M9b):

- **What CPython does in C, and the typing.py code the syntax calls**: types.GenericAlias (`list[int]`),
  typing.Union (`int | str`), TypeVar, TypeVarTuple, ParamSpec, TypeAliasType and typing.Generic with its
  subscription and `__init_subclass__` (typing._GenericAlias, `K[int]`), natively (`PyTyping`). Not the rest of the
  typing module (Optional, Callable, Protocol, Literal, TypedDict, ForwardRef: `K['fwd']` keeps the str, `T | 'x'`
  makes no ForwardRef) and no `typing` module to import; messages name these types by their C `tp_name`
  (`PyType.tpName`: 'typing.TypeVar' object is not callable).
- **A generic's `<generic parameters of X>` is a function** made and called where the def, class or alias is (a
  def's defaults evaluated outside, its arguments): it makes the type parameters (their bounds, constraints and
  defaults, like a `type` alias's value, lazily evaluated `(.format=1, /)` functions), then the function, class
  (its bases evaluated inside, Generic[...] after them; its body stores `__type_params__`) or alias. It and the
  scopes in it are function-like and, in a class, read the class's names as annotation scopes do; qualnames skip
  it. The tier compiles it as any function (its instructions are runtime calls).
- **Class statements resolve their bases by `__mro_entries__`** (PEP 560) and keep `__orig_bases__`; substitution of
  type variables in aliases and unions follows `_Py_subs_parameters` (defaults, a TypeVarTuple's arguments),
  Generic's typing's `_generic_class_getitem` (a ParamSpec's list).

Made with what was left (M9c):

- **Private names are mangled** (`__x` in a class as `_C__x`, CPython's _Py_Mangle): each scope knows the class it
  is in (`Scope.private`; a generic class's type parameters' scope mangles only them), the symbol table records
  names mangled and the compiler mangles names, attributes and annotation keys as it emits them; keyword arguments
  of calls and class patterns' keywords are never mangled, nor dunders, dotted names, or in a class named only
  underscores. A function's `__name__` and qualname keep the name as written.
- **A class statement stores `__firstlineno__`** (its first decorator's line, else its own) **and
  `__static_attributes__`**: the names of its and its functions' stores to attributes of a name `self`, sorted,
  as written (not an augmented assignment's, nor an annotation's without a value). type() makes neither.
  `__dict__` and `__weakref__` are not in a class's dict, nor `__doc__` where it has none (CPython's type.__new__
  adds them).
- **`__debug__` is the constant True**, and binding it anywhere (a name, a parameter, a keyword argument, an
  attribute, an import, a target) CPython's compile error.
- **compile(), exec() and eval()** compile at run time (`PyEval`): a module's code in name mode (LOAD_NAME,
  STORE_NAME, so that separate locals are a class body's), an eval's returning its expression; their default
  namespaces the caller's, a function's locals a snapshot the call site takes (SET_EVAL_LOCALS, as `locals()`'s:
  compiled code has no registers to read), dir()'s too. A compile error is CPython's SyntaxError object: the
  parser's with its line's text, the compiler's without, the symbol table's with its message as its only argument.
  PyCF_ONLY_AST gives `_ast` objects (`PyAstObjects`); an `ast.AST` given to compile() is read back into NeonPy's
  tree (`PyAstCompile`) and compiled as a parsed source is (12.2).

- **A statement gives its temporaries back** when it ends, so that the registers of one try or with serve the next:
  the tier's code keeps every register a handler may read in a home at the start of each block a handler covers
  (GraphBuilder), so a function's size grows with its handlers times those registers. (The core could keep only the
  registers the block's own handler reads: a change for the core's branch.)

- **Augmented assignment is its own instruction,** `INPLACE op`: `__iadd__` and the rest, lists changed in place,
  CPython's `+=` messages. `PyRt.inplace` keeps numbers and strs as cheap as `a op b`, and the speculating tier
  builds it as the binary operator where the interpreter saw only ints or only floats.

Made with the tier's suspensions (M4b):

- **A generator's code is compiled on the core's suspensions** (docs/IR_NEUTRALITY.md §9), as JavaScript's
  generators are: the builder ends a block at YIELD with SUSPEND, whose state is the interpreter's at the RESUME
  after it, and goes on where the generator resumes (RESUME_SLOTs, then the value it was resumed with, which RESUME
  checks for a throw); `PyGenerators.suspend` leaves the frame as the interpreter's YIELD does. SEND is a step of the
  iterator in its register and the value on the stack (what it yields, or Done's result), a branch on Done. A
  generator's frame is an interpreted one (`Graph.startsInterpreted`), so that compiled code and the interpreter each
  resume what the other suspended: each resumption runs the code's compiled code at the frame's pc where it has an
  entry there, the interpreter otherwise and after an exit. `-Dneonjs.jit.suspendable=false` leaves them interpreted.
- **Inlining a generator into the loop consuming it** (no resumption, no iterator protocol per value) is what would
  make generator-heavy code fast next; until then the protocol costs more than a small body.

### 11.3 How each milestone is checked

- **Programs.** Programs under `src/test/resources/programs/` whose output CPython 3.14 recorded (`python -I x.py >
  x.out`), each run in the four modes. Errors are checked in `errorsAreCPythons`, and every block must be the tier's
  code (`noBlockFallsBackToTheInterpreter`).
- **Grammar and semantics** are read from CPython, not recalled: `ast.dump`, `dis` and `symtable` of the snippet,
  and its messages as it prints them.
- **Speed.** §10.2's programs are measured again after M1 (calls) and M2 (attributes and operators), and a change is
  written down in the milestone's commit.
- **Size.** The interpreter's dispatch stays under HotSpot's 8,000-byte limit on methods it compiles; a test checks
  it.

### 11.4 CPython's own tests (the sweep after M9, 2026-11-06)

CPython 3.14's `Lib/test` files for the syntax were run on NeonPy with small stand-ins for what they import
(unittest, test.support, warnings, textwrap, typing, math, weakref, collections…), and on CPython with the same
stand-ins, so that only NeonPy's differences count. test_syntax's doctests ran through a small doctest that
compares each example's output, or its exception's last line. A corpus of 239 malformed sources compares the
SyntaxError's type, message and line.

| test | NeonPy | CPython (same stand-ins) | what is left |
|---|---|---|---|
| test_grammar | 70/75 | 70/75 | (the stand-ins' warnings) |
| test_syntax (doctests) | 482/510 | 483/510 | one needs the ast module |
| test_named_expressions | 74/74 | 74/74 | |
| test_keywordonlyarg, test_decorators, test_augassign | 11/11, 16/16, 7/7 | the same | |
| test_except_star | 60/60 | 60/60 | |
| test_positional_only_arg, test_dictcomps | 26/28, 9/10 | the same | |
| test_scope | 36/41 | 37/41 | `__del__` run as objects die |
| test_raise | 32/37 | 33/37 | traceback objects (`tb_next`) |
| test_exception_group | 28/52 | 33/52 | tracebacks, RecursionError on deep groups, split copying notes, a repr check's order |
| test_opcodes | 6/8 | 7/8 | open() |
| test_type_aliases | not run | | needs typing's constructors (`TypeVar('T')`, `TypeAliasType(...)`) |
| the error corpus | 239/239 | | |

How the errors are made, so that later syntax keeps them:

- **The tokenizer's errors are CPython's lexer's** (brackets on a stack, string prefixes, numbers'
  verify_end_of_number, tabs measured twice for TabError, escapes' positions as the decoder counts them). It
  tokenizes the whole source first, which gives CPython's rule that a later tokenizer error wins over a parser's.
  A bracket left open is the parser's to report: "'(' was never closed" when the parse reaches the end (an error
  no speculation takes back, `PySyntaxError.fatal`), or fails having looked past the bracket's line.
- **Where the parse fails without one of CPython's invalid_ rules, the message is "invalid syntax";** "expected
  'X'" only at the grammar's forced tokens (`&&':'` after def, else, try, finally; `&&'('` after a def's name),
  "expected ':'" at a header's line end (the invalid_*_stmt rules).
- **CPython's invalid_ rules are checks at the points the parser fails or ends an expression,** each trying what
  the rule matches with `speculate` (the tokens put back after; the checks off inside, as CPython's
  expression_without_invalid has them off): two expressions in brackets "Perhaps you forgot a comma?" (by token
  levels), `=` where a named expression is expected, the first target's last element for "cannot assign to X
  here", arguments, parameters, imports, patterns, displays. A rule that only CPython's second pass would try (the
  first one failed) is tried only where the parse has failed: an assignment's targets after one does not parse,
  a match subject's error only where the line is no statement either.

## 12. What is left after the syntax (plan, 2026-11-07)

After §11, what is left is the language's last gaps and the standard library (§3.7, Y2). On branch
`neonpy-stdlib`, stacked on `neonpy-language`. Each step is commits gated by the quick check and pushed when it is
done. Each module is checked against CPython's `Lib/test/test_X.py` (through the conformance harness, §11.4, with
stand-ins until the real `unittest` imports: from then on CPython's own).

### 12.1 Order (by what needs what)

| | what | needs |
|---|---|---|
| L1 | rich comparisons: `__lt__` and the others may return any object; `in`, `not in`, `is` stay bools; a branch tests truth | — |
| L2 | subclasses of int and float (and of complex, bytes): a value with its class, as `PyValue.sub` for str and list; never speculated on as an int | — |
| L3 | frames and tracebacks: a frame chain (`sys._getframe`, `f_back`), frame objects (`f_code`, `f_lineno`, `f_globals`, `f_locals`), traceback objects added as an exception passes each frame (`tb_next`, `tb_lineno`), `with_traceback`, `sys.exc_info`; an uncaught exception printed as CPython prints it | — |
| L4 | weak references (`_weakref`: `ref`, `proxy`, callbacks) and `__del__`, run when the JVM's collector finds the object unreachable (12.2), and a `gc` module | — |
| S1 | the bundled library: CPython's `Lib` at tag v3.14.6 in `third_party/cpython`, a port script (file list and rules, as `neonjs-node/tools/port_node.py`), PSF's license in NOTICE, a loader behind the host's; `sys` as the library reads it | L1–L4 |
| S2 | the C modules, in Kotlin, in the order the imports need them: `_thread` (single-threaded), `_operator`, `itertools`, `math`, `time`, `errno`, `_string`, `_collections`, `_random`, `_struct`; with them the pure modules (`collections`, `functools`, `abc`, `types`, `enum`, `copy`, `heapq`, `bisect`, `string`, `contextlib`, `datetime` through `_pydatetime`, `random`, `fractions`…) | S1 |
| S3 | `_sre` (the engine of `re`, Python's semantics: `re._parser` and `re._compiler` are Python) → `re`, `json`; `_typing` (the types §11 made, their constructors callable) → `typing` | S2 |
| S4 | `_io`, `open()`, `os` and `posix` over a file system the host gives (none by default: §3.8), `_codecs` and `encodings`; `sys.stdout` a text stream | S2 |
| S5 | `_ast` from `PyAst` (`compile(..., ast.PyCF_ONLY_AST)`, a tree compiled), `inspect`, `dataclasses`, `tokenize` | S3, S4 |
| S6 | asyncio's basics: `_contextvars`, a loop of timers and a ready queue without I/O (no `selectors`, no `socket`), `asyncio.run`, tasks, `gather`, `sleep`, `wait_for`, queues, locks, events | S2–S5 |
| C1 | a generator inlined into the loop consuming it (no resumption or iterator protocol per value): the core's work, on a core branch, not here | — |

### 12.2 Decisions

- **`__del__` runs when the collector finds the object unreachable, not at its last reference** (PyPy's choice: the
  JVM counts no references). The objects of classes with `__del__` are registered with a reference queue that is
  drained on the interpreter's thread at safe points (calls' interrupt checks), and `gc.collect()` asks the JVM to
  collect and then drains it. A weak reference's callback goes the same way. A `__del__` is called on an object of
  the class with the instance's dict (the instance itself is gone), so an instance whose dict holds itself is never
  finalized. In code the tier compiled, `del x` and `x = None` let go of the local (`PyIr.RELEASE`), but a value a
  statement only looked at stays reachable until the function returns while the JVM still interprets the method (its
  interpreter keeps every local holding an object); nulling locals after their last use would be the core's work.
- **A module body larger than the tier's block limit is interpreted.** It runs once, at import. The bundled
  library's module bodies are not held to `noBlockFallsBackToTheInterpreter`.
- **`dis` is NeonPy's, not CPython's.** NeonPy's bytecode is its own, so `dis` lists NeonPy's instructions and
  keeps CPython's names that other modules read (`COMPILER_FLAG_NAMES`). There is no `opcode` module.
- **The sandbox's defaults come first:** no file system, an empty `os.environ`, `getcwd()` `/`, `random` seeded by
  the host, `time` from the agent's clock.
- **The bundled library is CPython's own files, unchanged** (`neonpython/tools/port_cpython.py`, a rules file only
  where one must differ, said in the INDEX): their lines, and so their tracebacks' line numbers, are CPython's, and
  CPython's own `Lib/test` can check them. Where CPython has a C accelerator with a pure fallback (`_abc`,
  `_functools`, `_heapq`, `_bisect`, `_operator`, `_warnings`), the fallback runs until the accelerator is worth it;
  where it has none (`_collections`, `itertools`, `_sre`, `_contextvars`, `_thread`, `_weakref`), the module is Kotlin.
- **`sys.implementation.name` is `'neonpy'` and its `cache_tag` None** (no bytecode is written); `sys.prefix` is
  `/usr`, the library at `/usr/lib/python3.14`. Code asking whether it runs on CPython is told it does not:
  `sys.version` is `'3.14.6 (NeonPy)'`, and `platform` (a rules file) parses it as NeonPy's, so
  `platform.python_implementation()` is `'NeonPy'`.
- **A builtin type's classes are each context's.** The builtin types are every context's objects, so
  `object.__subclasses__()` lists only the asking context's classes (and the builtin types); descriptors the builtin
  types show (`FunctionType.__code__`) take no attributes of their own.
- **A bytearray stays resizable while memoryviews of it live** (CPython raises BufferError): no reference count tells
  when a view is gone, and refusing would break the common `memoryview(buf)[...]` temporaries; a view the resize left
  past the end raises where it is read.
- **`re` runs on a port of CPython's SRE engine,** not on `java.util.regex`: CPython's parser and compiler (Python)
  emit SRE code, which `_sre` runs as sre_lib.h does, so the semantics (backtracking order, groups in repeats, Unicode
  classes, case folding) are CPython's. Its contexts are on an explicit stack.
- **`typing` is CPython's typing.py over a Kotlin `_typing`,** and what CPython's C hands to typing.py NeonPy hands
  to it too: `Generic`'s subscription and `__init_subclass__`, a class statement's type parameters
  (`typing._GenericAlias(Generic, ...)`), `__typing_subst__`, `*Ts` (`typing.Unpack`). So `class A[T]: ...` imports
  `typing`, as in CPython, and its errors are raised in typing.py, at typing.py's lines. `types.GenericAlias` and
  unions substitute as genericaliasobject.c does, through the attributes of any argument.
- **`math` takes what CPython takes from libm from `java.lang.Math`;** what Java lacks (erf, erfc, acosh, asinh,
  atanh, log2) is fdlibm's and FreeBSD's C, ported, and the rest of mathmodule.c is ported as CPython writes it. The
  last digit of a libm function's result is the platform's (CPython's is its C runtime's: they differ from each other
  too). A C `long` is 64 bits, as on Linux and Android (`factorial`'s limit, `sumprod`'s and `prod`'s fast paths).
- **The sandbox is a POSIX system whatever the host is.** `sys.platform` is `'linux'` (`'android'` on ART, as CPython
  3.13+ says there), `posix` is always the os module (`os.name` `'posix'`, `/` paths), errno's names, numbers and
  messages are Linux's (`neonpython/tools/gen_errno.py` makes them from Linux CPython), `uname()` says `Linux` and
  `localhost`, the ids are 0 and the pid 1. What a sandbox has no use for (processes, signals, users, terminals, hard
  links, directory descriptors, `dir_fd`) posix does not have, so `os` says it has none. Linux CPython is the oracle
  for what is POSIX-shaped (`os`, paths, `io`).
- **The file system is the host's to give, none by default** (§3.8; GraalVM's `IOAccess.NONE`): `PyContext`'s
  `fileSystem`, a `PyFileSystem` of absolute POSIX path strings (posix resolves `.`, `..` and its working directory
  first), not `java.nio.file.Path`s, which an in-memory one could not give without a `FileSystemProvider`. None
  (`PyFileSystem.NONE`) makes every access a PermissionError with EPERM ("Operation not permitted": a policy, not a
  missing file, which FileNotFoundError would wrongly say). `directory(root, readOnly)` makes a host directory `/`
  with no way out of it: `..` stops at the root, a link leading out of it is refused with EACCES. `memory()` keeps one
  in memory; `readOnly(fs)` refuses changes with EROFS. Whatever it is, the bundled library is read-only at
  `/usr/lib/python3.14` (GraalVM's language home): `linecache`, `inspect` and tracebacks read its sources there.
- **Descriptors 0, 1 and 2 are the context's**: 0 reads nothing (its end at once), 1 and 2 write to the context's
  output and error output (UTF-8, a character split between writes kept for the next); files opened get 3 on.
  `sys.stdin`, `sys.stdout` and `sys.stderr` are text streams over them written through (CPython's `-u`), so `print`,
  `sys.stdout.write` and `os.write(1, ...)` keep their order; `print` writes to `sys.stdout` as it is at the call
  (`contextlib.redirect_stdout`), a file not a standard stream through its `write`, piece by piece as CPython calls it.
  `os.environ` is the host's map (empty by default); putenv and unsetenv change the context's own.
  The standard streams' errors are `surrogateescape` (stdin, stdout) and `backslashreplace` (stderr), as CPython's in
  UTF-8 mode, which NeonPy is (`sys.flags.utf8_mode` 1): Windows CPython, not in UTF-8 mode, says `strict`, but
  Linux CPython is the oracle here.
- **`_io` is CPython's `_pyio`** (a rules file, `neonpython/tools/ports/_io.py`), its messages for misuse C `_io`'s
  where `_pyio`'s are its own (closed, detached and wrong-mode files, the unsupported operations). A file written
  without `close()` keeps what is buffered until the collector finalizes it (CPython's reference count flushes it at
  once); descriptors are never reused, so a late finalizer cannot close a newer file. `datetime` is `_pydatetime`
  (no C `_datetime`), `pickle` the pure one, `locale` its emulation (no `_locale`). `operator`'s functions are
  `_operator`'s builtins, as CPython's C ones are, so that a class holding one does not bind it. Its getter classes,
  `concat` and `length_hint` are the pure ones.
- **`importlib` is NeonPy's own**, its API over NeonPy's import (`_imp`: a module's source found without running
  it, a module run again for reload), with CPython's `ModuleSpec`, checks and messages; NeonPy never runs
  `importlib._bootstrap`. A module NeonPy imports has no `__spec__` (None): `importlib.util.find_spec` makes the
  one CPython's would have given it. Code objects' offsets are twice NeonPy's pc (each int of its bytecode two
  bytes), `f_lasti` and `tb_lasti` among them; `co_positions()` has lines only.
- **Builtins' text signatures are CPython's**: `neonpython/tools/gen_signatures.py` takes each builtin function's
  and method's `__text_signature__` from Linux CPython, so `inspect.signature(len)` works as there.
- **`time`'s zone is UTC** (`TZ` is not read): localtime is gmtime, as in a container without zone data.
- **There is one thread.** `_thread` starts none: `start_new_thread` and `Thread.start()` raise `RuntimeError("can't
  start new thread")`. Locks, conditions and queues work for the one thread. A wait with a timeout sleeps in slices
  of at most 10 ms and checks the agent's limits. A wait that nothing could ever end raises RuntimeError at once
  instead of hanging: a lock already held, a recv with no data, `select(None)` with nothing ready, a future nothing
  will resolve.
- **asyncio is CPython's, unchanged**, and runs on the sandbox's `_socket`, `select` and `_signal`, without
  `_asyncio`. Its loop is `_UnixSelectorEventLoop` with `SelectSelector`, since there is no poll or epoll, and its
  clock is `time.monotonic`. `run_in_executor` and `to_thread` raise the one-thread RuntimeError. The default
  executor is shut down in the loop's thread (a rules file for `asyncio/base_events.py`, the lines kept), because it
  has no workers to join. `sys.set_asyncgen_hooks`' firstiter is called as CPython calls it. Its finalizer is kept
  but never called, because no asynchronous generator is finalized when collected. asyncio's
  `shutdown_asyncgens()` still closes the ones firstiter saw.
- **No network and no processes; the only signals are the program's own.**
  - `socketpair()` and `os.pipe()` ends are posix descriptors connected in memory, with Linux's buffers, shutdown,
    errors and timeouts.
  - connect, bind, listen, accept, sendto, recvfrom and name lookups raise PermissionError (EPERM).
  - `fork_exec` raises PermissionError (EPERM), so subprocess starts nothing.
  - `raise_signal` runs the handler at once, after writing the wakeup byte. SIG_DFL ends the program with
    `SystemExit(128 + n)`. Arming `alarm` or `setitimer` is refused.
- **`compile()`'s mode `'single'` and incomplete input are codeop's.**
  - `'single'` takes one statement. It gives an expression statement's value to `sys.displayhook`, and a compound
    statement must end with a newline.
  - `PyCF_ALLOW_INCOMPLETE_INPUT` raises `_IncompleteInputError` where the source's end made the error, so
    `codeop.compile_command` and `code.InteractiveConsole` answer as CPython's do.
  - Raw `compile()` with the flag differs from CPython in a few cases. In those, pegen's lookahead had already reached
    the end, so CPython calls the input incomplete: `'def f(:'` in `'single'` and `'for'` in `'eval'`.
- **A function's `__code__` can be set only to a code object with its bytecode**, that is, one that `replace()` made
  of its own: NeonPy's functions keep their bytecode. `replace()` may change the names, the filename, the first line
  and `CO_ITERABLE_COROUTINE`, which is what `types.coroutine` needs.
- **`unicodedata` is CPython's two databases** (16.0.0, and 3.2.0 as `ucd_3_2_0`) in one resource,
  `unicodedata.bin`. `neonpython/tools/gen_unicodedata.py` makes it from CPython itself, plus `Modules/`'s name tables
  for the aliases and named sequences. `\N{...}`, `namereplace` and `stringprep` use it.
  - `hashlib` is CPython built without OpenSSL (no `_hashlib`). md5, sha1, sha2, sha3/shake and blake2 are Kotlin, so
    no JVM or Android provider is needed, and there is no `pbkdf2_hmac` or `scrypt`.
  - A C `unsigned long` is 64 bits, as on Linux.
  - The hash objects' attributes are properties.
- **compile() of an AST object takes CPython's three steps.**
  - Its nodes are read as Python-ast.c's obj2ast reads them. `PyAsdl.kt` is the ASDL as data, and
    `neonpython/tools/gen_ast.py` writes it beside `_ast.py`.
  - They are checked as ast.c's `_PyAST_Validate` checks them, with its messages, then compiled from NeonPy's tree.
  - With PyCF_ONLY_AST it gives a new tree (type comments kept), after checking its `from __future__` features.
  - Columns are taken as given: there is no source to turn UTF-8 offsets into characters, and CPython reports them
    the same way without one.
  - Where CPython's compiler crashes on a None that its validator let through (an identifier, a pattern, a handler, a
    type parameter), NeonPy raises the validator's ValueError ("None disallowed in identifier list").
  - PyCF_OPTIMIZED_AST gives the tree unoptimized.

### 12.3 Progress (2026-11-07, later)

Done on `neonpy-stdlib`: L1–L4; S1 (the bundled library, `sys` as it reads it); S2's `_thread`, `itertools`,
`_collections`, `math`, `time`, `errno`, `_random` with `random`, `_struct` with `struct`, `_string` with `string`,
`_sha2`/`_md5`/`_sha1`, `_operator` (index); S3's `_sre` with `re`, `_typing` with `typing` and `annotationlib`,
`json`; S4's `posix` with `os`, `stat`, `posixpath`, `genericpath`, `shutil`, `glob`, `fnmatch`, `tempfile`,
`contextlib`, `_io` (`_pyio`) with `io` and `open()`, `_codecs` with `codecs` and 84 `encodings`, the standard
streams; S5's `_ast` (generated from CPython's ASDL) with `ast` (`unparse` too), `_tokenize` (CPython's lexer) with
`token` and `tokenize`, `dis` (NeonPy's), `importlib` (NeonPy's), `inspect`, `dataclasses`, `traceback`, `linecache`
(the library's sources through its mount); and `datetime`, `calendar`, `locale`, `pickle`, `textwrap`, `enum`,
`functools`, `collections`, `copy`, `weakref`, `warnings`, and the rest of the pure modules they import. With them
the runtime gained what they stand on: subclasses of the builtin types, metaclass protocols, `__prepare__`,
`__slots__`, mappingproxy, the descriptor protocol's corners (`__delete__`, `__set_name__`), type's own
descriptors, code objects' bytecode attributes, settable function names, builtins' signatures.

Import costs (a cold JVM, interpreted: os 146 ms, re 140, json 37, typing 47, io 21, dataclasses with inspect 125,
traceback 57, datetime 30; adaptive, warm: about 230 ms for all of them; CPython about 40 ms). The neonpython tests
pass with a 300 MB heap.

Since then:
- S6: asyncio, CPython's, unchanged. It runs on a single-threaded `_thread` with `threading`, `queue` and
  `concurrent.futures`, and on sandbox `_socket`, `select`, `_signal` and `_posixsubprocess` with `socket`,
  `selectors`, `signal` and `subprocess`.
- `binascii` and `zlib`, with `base64` and `gzip`.
- `unicodedata`, with both of CPython's databases.
- `hashlib` with blake2 and sha3, `hmac` and `secrets`.
- `pathlib`, `zipfile`, `tarfile`, `wave`, `configparser`, `tomllib`, `argparse`, `optparse`, `getopt`, `logging`,
  `sched`, `decimal` (`_pydecimal`), `fractions` and `statistics`.
- `code` and `codeop`.

Next: `xml` (it needs `pyexpat`), `str`'s Unicode tables from CPython, and `C1` on a core branch.

## References (checked in this review)

- Chicory: [docs](https://docsearch.algolia.com/mcp/docs/repo/dylibso/chicory), [porting the Chicory compiler to Android (2025-07)](https://hn.jakechampion.name/reader/https://blog.evacchi.dev/posts/2025/07/11/wasm-the-hard-way-porting-the-chicory-compiler-to-android), [mcpx4j Android support](https://docs.mcp.run/blog/2025/07/11/android-support-mcpx4j), [Endive](https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm)
- Pyodide/PEP 783: [PEP 783](https://peps.python.org/pep-0783/), [pyodide-build platform tags](https://pyodide-build.readthedocs.io/en/stable/reference/platform.html), [Discourse discussion](https://discuss.python.org/t/pep-783-emscripten-packaging/86862/89)
- Limited API `Py_INCREF` as a function: [python-checkins gh-105387](https://www.python.org/pipermail/python-checkins/2023-June/183584.html), [Discourse](https://discuss.python.org/t/limited-c-api-implement-py-incref-and-py-decref-as-function-calls/27592)
- To check: the current Pyodide release, HPy package support, Chicory's Android backend maturity, the RustPython/GraalPy module lists
