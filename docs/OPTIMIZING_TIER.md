# Optimizing tier (T2): design and plan

Status (2026-10-23): P0 done; P1 done but for inlining, which moves to P2 (the IR and its code run Test262 in every
mode with `-Dneonjs.jit.t2Stress`); P2 next. Nothing here is enabled by default; `RuntimeConfig.tier2` (default off)
gates every part that costs anything.

This document is the plan for a second compiler tier that speculates on profiled types, inlines deeply, removes
allocations and hoists loads, and deoptimizes to the interpreter when a speculation fails. It complements
`docs/ARCHITECTURE.md`, whose JIT section describes the tier that exists today (T1) and the contracts every tier
follows.

## Why

The present compiler (T1, `JvmCompiler`) translates bytecode one instruction at a time and never speculates: a value
is unboxed only when the instructions that produce it make it a number, and a guarded call is inlined one level deep
with the ordinary call compiled next to it. HotSpot's C2 then optimizes the generated class, and on HotSpot that
already removes most boxes. ART does far less, and what is left on both VMs is what neither can see through: the
`JitRt` helpers, the property map behind a shape check, an allocation that escapes into a helper.

Measured upper bounds (hand-rewritten JS, adaptive mode; `art-perf-notes`, 2026-10-23):

| case                                   | HotSpot        | ART (API 34 emulator) | what removes the cost              |
|----------------------------------------|----------------|-----------------------|------------------------------------|
| non-escaping `{x, y}` literal in a loop | 64 -> 5 ms     | 420-560 -> 5-9 ms     | escape analysis, scalar replacement |
| 4 property loads in a loop              | 38 -> 4.7 ms   | 227 -> 3 ms           | shape guards hoisted, values unboxed |
| nested calls                            | 175 -> 28 ms   | 770-880 -> 96-104 ms  | inlining more than one level        |
| method using `this`                     | 72 -> 58 ms    | 365-422 -> 274-312 ms | inlining through property loads     |
| nbody                                   | -              | 910 -> 690 ms (`+()`) | speculation on number loads         |

A JS call costs about 70 ns on ART and an object literal about 100 ns; a box 6-8 ns. These are the costs a
speculating tier removes and T1, by its rules, cannot.

## Tiers

| tier | what                                  | speculates | code shared between blocks   | when                          |
|------|---------------------------------------|------------|------------------------------|-------------------------------|
| T0   | interpreter (`vm/Interpreter.kt`)      | no         | -                            | always available; records feedback |
| T1   | `JvmCompiler`, one pass, `TypeAnalysis` | no         | yes (`JitInput.identity`)     | as today (`jitThreshold`, OSR) |
| T2   | IR compiler (new, `jit/opt/`)          | yes        | no (one instance per block)  | hot T1 code, with `tier2` on  |

T1 stays the safety net: it compiles everything T0 runs (but generators and async functions), it is what a block
returns to when its T2 code is invalidated, and its rules (ARCHITECTURE, "Unboxed numbers", "Inlined calls") do not
change. T2 is an addition, not a replacement, and not an alternative compiler the user picks instead of T1.

User switch: `RuntimeConfig.tier2` (internal, `-Dneonjs.jit.tier2=true` for tests) until P5; then a builder option
(`NeonEngine.Builder.optimizingTier(Boolean)`, possibly also `optimizationLevel(10)`), off by default until the P5 exit
criteria hold. The three execution modes keep their meaning; T2 only ever replaces T1 code of hot blocks:

- interpreter: no compiled code, no T2.
- compiled: T1 before the first call, as now; with `tier2`, hot T1 code goes to T2 (feedback is thinner here: see
  "Feedback").
- adaptive: T0 -> T1 as now; with `tier2`, hot T1 code goes to T2.

## Code versions (P0)

Today a block's compiled code, once installed in `CodeBlock.compiled`, is never replaced, and frames rely on that:
`Frame` does not keep its code, `Interpreter.execute(f)` reads `f.code.compiled` again, and T1 classes read the
callees they inline from `CodeBlock.inlineTargets`. With T2 a block has up to two versions:

- `CodeBlock.baseline`: the T1 code. Installed once and never replaced, together with `inlineTargets`, which belongs
  to it. `JvmCompiler.compileAll`'s "already installed" short-circuit reads this field: compiling a block for T1 a
  second time must not hand back its T2 code.
- `CodeBlock.compiled`: the entry code, the version new activations run: the baseline, or T2 code. T2 code is
  installed over the baseline and replaced by the baseline again when it is invalidated.

The contract that replaces "never replaced": every version ever installed in `compiled` supports both entries of
`CompiledCode.run` (a frame made for compiled code, from the start; an interpreted frame at a loop header, or
`JitRt.NO_OSR`), and an activation keeps running the version it started with (the JVM keeps its class alive). So a
frame need not pin its code, and every reader of `compiled` stays correct when it changes between two reads:

- `Frame`'s constructor (whether to allocate slots) and `Interpreter.invokeClosure` / `constructClosure` (the code
  passed to `runFrame`): either version runs a frame made for compiled code.
- `Interpreter.execute(f)` (single argument): re-reads `compiled` for a compiled frame: either version.
- `Jit.osr`: either version answers an OSR request (or `NO_OSR`).
- `Jit.prepare` / `Jit.loop` / `JitQueue`: test `compiled != null` (some version installed), unchanged.
- `JvmCompiler.compileAll`: reads `baseline` (see above).

T2 code is never shared through `CodeCache`: it embeds per-block feedback (shapes, call targets, constants), so each
T2 instance belongs to one block. Its class could be shared by identity like T1's, its data never; P1 starts with one
class per block (hot blocks are few) and revisits it if the definer's per-batch cost shows (dex on Android).

## Feedback

Exists already, shared by T0 and T1 (both fill the same objects):

- property shapes: `PropSite.entry` (up to 4 `PropIC` entries: receiver shape, prototype chain shapes, slot, kind);
- globals: `GlobalSite.cache` (lexical binding index or global object shape and slot);
- call targets: `CodeBlock.callFeedback` (majority callee per CALL, interpreter only) and the compiler's static
  targets (`CodeBlock.callHintPcs`);
- object literal layouts: `ObjectLiteralSite.layout`.

New in P0, `CodeBlock.profile` (a `Profile`, allocated on first use, written only by the interpreter, only with
`tier2` on in adaptive mode): the kinds of values seen

- as results of `GET_PROP`, `GET_ELEM`, `CALL` and `LOAD_ENV` (the values T2 speculates on most: loads and returns);
- in each parameter at entry.

A kind is a bit set (int32-valued number, other number, string, boolean, undefined/null, object, other) OR-ed in
place: one byte per instruction, no allocation per record. Arithmetic is not profiled: T2 speculates on the operands'
kinds (which come from the loads above, constants and other arithmetic) and learns from deoptimizations where that
was wrong (below), as V8 does for overflow.

T1 code records nothing. In adaptive mode a block runs at least `jitThreshold` (1000) interpreted calls, or about 64K
loop iterations, before T1 compiles it, which is enough feedback for its hot paths. In compiled mode there is none
but the static targets and the existing caches; T2 then speculates only where those say something, and the deopt
counters correct it. If that proves too thin, T1 gets a profiling variant (P5), compiled only with `tier2` on.

Deopt feedback: every exit of T2 code has a counter and a reason (which speculation failed). When a block's T2 code
deoptimizes too often it is invalidated, and the next T2 compile of the block does not make the speculations that
failed (the site is marked in the profile). After a few such recompiles the block stays at T1.

## Deoptimization (P0 runtime, P3 for T2)

Only eager deoptimization: generated code tests its assumptions where it relies on them, and a failing test jumps to
an exit. There is no lazy deoptimization (no code patching, no invalidating code that is on the stack because some
other code changed an assumption): assumptions that other code can break (a prototype's shape, a global's value, a
closure's code) are tested at their use, like every other guard. Invalidating T2 code only changes which version new
activations start with.

An exit leaves compiled code for the interpreter in the middle of a function:

1. The exit materializes the interpreter state of every frame live at that point: for the outermost frame (the one
   compiled code runs), its registers and operand stack; for each inlined call around the point, the callee closure,
   its `this`, its arguments, its environment, and its registers and stack. Values held unboxed are boxed; values
   that only exist as fields of a scalar-replaced object (P4) are allocated as the object.
2. `Deopt.resume(f, pc, values, inner)` converts `f`, the frame of the compiled activation, into an interpreted frame
   in place (`Frame.slots` becomes a `var`): it keeps its identity, which `JSErrorObject.capturedAt`, `Frame.parent`
   chains and `Agent.topFrame` hold. An inlined call recorded in the frame (`Frame.inlineFn`) ends, with the call depth
   given back, since the inlined callee is about to get a frame of its own.
3. The innermost frame runs first: each inlined callee becomes an interpreted frame (`Interpreter.execute`, so it has
   its depth, realm and `topFrame` bookkeeping and shows in stack traces with its caller's `pc` at the call), resumes at
   its exit pc, and its result is pushed onto its caller's stack, which then resumes after the `CALL`. An exception
   leaving a callee is thrown in the caller at the `CALL`'s pc, through the caller's own handlers (a pending throw in
   `FrameExt`, which generalizes the one `await` uses).
4. The outermost frame then continues in the interpreter. When its compiled code was entered from the start, the exit
   calls `Interpreter.run(f)` and returns its result (one nested interpreter activation per deoptimized compiled
   activation). When it was entered by OSR (the frame had slots), the exit returns `JitRt.DEOPTED` instead and the
   interpreter activation that called `Jit.osr` continues with the frame's new `pc` and `sp`: otherwise a loop that
   enters compiled code and deoptimizes on every iteration would nest an interpreter per iteration.

The call that performs the exit lies outside every exception range of the method (exits are emitted after the body,
like the boxing trampolines): the interpreter has already run the frame's handlers on whatever the rest of the
function throws, so the compiled method's handlers must not see it again.

Where an exit may be placed: before an instruction that has done nothing yet (the interpreter executes it again), or
after one that completed (the interpreter continues with its result). Never after part of an instruction's effect.

T1's state at any instruction boundary is known from its type analysis, which is why T1 can exit too (the deopt
stress mode, P0): a register held as a JVM `double` or `int` local whose kind at the exit is a number is boxed from
the local; one whose kind is not a number there holds a value no load ever reads (every load of a register kept
unboxed sees a number on every path, and registers are invisible outside their function), so `undefined` is as good
as any; Object registers and stack values are copied, unboxed stack values boxed.

Tests (P0): `-Dneonjs.jit.deoptStress=N` gives T1 code exits that are taken every Nth time they are reached:
at loop headers, at the start of an inlined body (the callee's frame at pc 0 and the caller's waiting for the call,
with the values below the call on its stack), at loop headers inside an inlined body, and right after an inlined
call returns (the caller's stack below plus the result). Test262 passes with it in compiled and adaptive mode,
together with `-Dneonjs.jit.osrStress`. This exercises the runtime half of deoptimization on every program Test262
has before T2 exists.

## IR (P1)

A control flow graph of basic blocks in SSA form, not a sea of nodes: easier to dump, check and debug, and enough for
the optimizations below (V8's Maglev and Turboshaft, JavaScriptCore's DFG and B3 are CFG-based).

- `Graph`: blocks in reverse post order, the entry block, loop headers with their back edges and depth.
- `Block`: phis, a list of instructions, a terminator (`Goto`, `Branch`, `Return`, `Throw`, `Deopt`), predecessors and
  successors, its dominator.
- `Value` (instruction): an operation, inputs, a type (a set of JS kinds, as profiled), a representation (Object,
  double, int32, boolean as int), effects (reads/writes of abstract heaps: object fields by shape and slot, array
  elements, globals, environments, "anything" for a generic helper), and for those that can exit a `FrameState`.
- `FrameState` (P3, with the first exits): code block, pc, register values, stack values, and the caller's state for
  an inlined call (which also records the callee closure, `this`, arguments, environment as values). Shared between
  exits with the same state. The builder has the register and stack values at every instruction already.

The builder walks the bytecode as `TypeAnalysis` does, keeping an abstract stack and registers of SSA values; it
creates phis at merges and FrameStates at every instruction that may exit. Initially T2 compiles functions without
exception handlers, scope instructions (`PUSH_SCOPE` and friends), `eval`, `with` or `arguments`; anything else stays
T1. Each later phase widens the set.

Operations (initial set): constants and parameters; the generic form of every supported instruction (a `JitRt` call,
effects "anything", exactly T1's code); typed forms (`Int32Add` with an overflow exit, `Float64Mul`, int and double
comparisons, `ToInt32`); guards (`CheckNumber`, `CheckInt32`, `CheckShape(obj, shape)`, `CheckClosureCode`,
`CheckBounds`, `CheckNotHole`), each exiting with its FrameState; loads and stores (`LoadSlot(obj, slot)`,
`StoreSlot`, `LoadElement`, `ArrayLength`, `LoadGlobalCell`); `NewObjectLiteral(site, values)`; calls (`CallGeneric`,
`CallKnown(code block)`); `Box`/`Unbox` conversions.

## Optimizations (P2-P4)

In order, each a separate pass with its own tests:

1. Inlining (in the builder, P2): call targets from `callFeedback` and the static targets, and methods from the
   shapes of the loads that produce them (`o.m()` where `o`'s shape and the holder's slot are known). Up to a depth
   and a bytecode budget per compile, recursion cut off. Inlined frames get FrameStates chained to the caller's.
2. Speculation (P3): generic operations become typed ones behind guards, from the profiles: a load that only ever
   produced int32s gets `CheckInt32`, a property load with a monomorphic `PropSite` becomes `CheckShape` + `LoadSlot`,
   a global that holds the same function object becomes a `CheckGlobalCell` + constant. Arithmetic on int32 operands
   is `Int32Add` and friends with an overflow (and -0) exit; a site that exited for overflow is compiled with doubles
   the next time.
3. Intrinsics (P3): `Math.*`, `String.prototype.charCodeAt`, array `length`/`push`, `Number.isInteger` and the like,
   behind a check that the function is the realm's original.
4. Canonicalization and constant folding; GVN over dominators, with effects deciding what a load may be merged with
   (P4); redundant guard elimination (a guard dominated by an identical one goes); LICM: guards and loads whose inputs
   are loop-invariant and whose heap is not written in the loop move to the preheader (P4).
5. Escape analysis and scalar replacement (P4): an allocation (object literal, `new` of an inlined constructor) whose
   only uses are loads and stores of known slots and FrameStates becomes its field values; FrameStates keep a virtual
   object, which an exit allocates. Partial escape analysis (materialize on the escaping branch only) after that.
6. Representation selection and dead code elimination.

No check is removed by range analysis before P5, and maybe never: overflow and bounds checks stay where the input is
not a constant or a typed result. These are the checks whose wrongful removal causes most JIT bugs elsewhere.

## Code generation (P1)

JVM bytecode again, one class per T2 compile, defined by the same `CodeDefiner` and implementing `CompiledCode`.

- Each SSA value that is used beyond its definition gets a JVM local of its representation; blocks become labels;
  the JVM operand stack is empty at every block boundary, so HotSpot can OSR-compile every loop (T1's inlined loops
  below a non-empty stack cannot be, see `art-perf-notes`).
- Exits are emitted after the body, outside every exception range. To keep the main method small, the code that
  boxes a FrameState's values and calls `Deopt.resume` lives in a static method of the class per exit (or per shared
  FrameState), called with the raw values: the main method only passes them. HotSpot does not compile methods over
  8000 bytes of bytecode (`MethodSizeTest`'s limit): every T2 compile has a size budget, and inlining stops before it.
- T2 data (shapes, code blocks, sites, the realm's intrinsic functions) is in an array the instance holds, read
  through a field: never class constants, so the class stays independent of the objects. Function objects are not
  held where the code block suffices (they hold their realm).
- Realms: a block can run in several realms (scripts compiled once, `createRealm`). Shapes are per prototype object,
  hence per realm, so T2 code speculating on one realm's shapes fails its guards in another, deoptimizes, and is
  eventually invalidated. Acceptable while multi-realm hot code is rare; per-realm versions would need more than one
  entry slot and are not planned.

## Safety rules for T2

These replace, for T2 only, T1's "no speculation" rule:

1. Every assumption is a guard dominating every use that relies on it, exiting with a complete FrameState. Nothing is
   assumed from a profile without a guard.
2. A guard is removed only when an identical guard dominates it, or the value is a constant or the result of a typed
   operation that guarantees it. Never because of a range, before P5.
3. An exit captures everything the interpreter reads; values the code does not hold (virtual objects) are
   materialized. An exit is placed before an instruction has any effect or after it completed.
4. Interrupt checks at loop back edges, the call depth limit, `pc` positions for stack traces (one per inlined level)
   and realm checks stay as observable as in T1.
5. Every guard can be forced to fail (`deoptStress`), and Test262 passes that way.

## Phases

Sizes are rough; each phase ends with its exit criteria met and a push.

- **P0: foundations** (~1.5K lines). This document; ARCHITECTURE rescoped; code versions (`baseline` / `compiled`);
  `Profile` and interpreter recording behind `tier2`; the deopt runtime (`Deopt.resume`, in-place frame conversion,
  inlined frames, pending throws, the OSR sentinel); T1 deopt stress exits.
  Exit: Test262 0 failures in the three modes, and with `deoptStress` (compiled, adaptive, and adaptive with
  `osrStress`); fuzzer clean; no change with `tier2` off (`MethodSizeTest`, NeonBench within noise).
- **P1: IR at parity** (~2K, done). `jit/opt/`: IR, builder, kinds, verifier (terminators, predecessors, SSA
  dominance), printer, codegen with T1's unboxing rules and helper calls (`JvmEmitter`, shared with T1), on-stack
  replacement entries, `-Dneonjs.jit.t2Stress` (every block compiled by T2 right after its baseline, when it can be).
  Result: Test262 0 failures with `t2Stress` in compiled, adaptive and adaptive + `osrStress` mode (691K blocks
  compiled by T2 in compiled mode; blocks with exception handlers, `for-in`, class definitions stay T1); the IR of
  127K Test262 functions verified. On HotSpot, T2 code matches T1 but where T1 inlines calls (fib, closures: 25-40%
  slower until P2), and runs NeonBench's int case 30x faster, since HotSpot can OSR-compile its loops (T1 keeps
  values on the JVM stack below inlined loops). The T2 compile queue moves to P5.
- **P2: inlining** (~2K). Multi-level inlining in the builder, inlined frames in FrameStates and stack traces
  (a position per level in the frame), constructor calls. Exit: nested-calls bound reached within 2x; Test262 with
  `t2Stress` + `deoptStress`.
- **P3: speculation** (~3K). Profile-driven guards, typed arithmetic with overflow exits, shape-guarded loads and
  stores, global cells, intrinsics, deopt counters and invalidation. Exit: property-load and nbody bounds within 1.5x;
  no deopt loops in jsbench and NeonBench (counters).
- **P4: optimization passes** (~3K). GVN, guard elimination, LICM, escape analysis and scalar replacement, partial
  escape analysis. Exit: literal-in-loop bound within 2x; fuzzer clean with every pass on and with each pass off.
- **P5: tiering and tuning** (~1.5K). T1 -> T2 counters, OSR from T1 into T2 at loop headers, background T2 queue,
  thresholds, profiling T1 if compiled mode needs it, the builder option, Android measurement. Exit: StarLight's
  NeonBench and the user's benchmark gain on ART, first run included; default stays off until a release cycle has
  run with it.

Total about 15K lines over several months; P0 and P1 are pure infrastructure with no speed-up of their own.

## Testing

- Modes: `-Dneonjs.jit.tier2`, `-Dneonjs.jit.t2Stress` (P1), `-Dneonjs.jit.deoptStress=N`, combined with Test262's
  `--mode compiled|adaptive` and `-Dneonjs.jit.osrStress`. The runner prints deoptimization counts next to the OSR line.
- Fuzzer (`neonjs-test262` `Fuzz.kt`): differential against the interpreter in each mode, then with the stress flags.
- Unit tests: deopt chains built by hand (P0); IR dumps for small functions (golden text); one test per pass on the
  shapes it should and should not change.
- Benchmarks: the IR bound cases (scratchpad `bench/ir.js`, to be moved into the repo), jsbench, NeonBench, on HotSpot
  and on the API 34 emulator; the phone only to confirm, pinned to the prime cores (`starlight-app` notes).

## Risks

- Compile time and memory on Android: T2 compiles only hot blocks, in the background; dex conversion is per batch.
- Code size: HotSpot's 8000-byte limit and the 64KB method limit; budgets in the inliner, exits outside the main
  method.
- Deopt loops: per-exit counters, invalidation, a recompile limit per block.
- Correctness: the stress modes force every exit; Test262 and the fuzzer run in every mode before each push; T1 stays
  the reference and the fallback.
- Effort: P0-P1 bring no speed-up; the plan can stop after any phase with T1 unchanged.
