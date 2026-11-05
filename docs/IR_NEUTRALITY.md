# A language-neutral optimizing pipeline: what T2 can share (draft)

Status (2026-11-05): a review, not an accepted plan; nothing is implemented. It answers, for the plan in
`docs/PYTHON_PLATFORM.md`, how much of the optimizing tier (`jit/opt/`, docs/OPTIMIZING_TIER.md) can become a core
that several languages share, so that a language implementation provides its semantics and gets JVM/ART-shaped
code (unboxed locals, empty JVM stack at block boundaries, exits outside the main method, size budgets, OSR) without
writing an optimizer of its own.

## 1. Summary

- **About two thirds of `jit/opt/` is neutral as a mechanism already**; what binds it to JS is (a) which opcodes the
  passes switch on, (b) the kind lattice, (c) the frame/call shape that exits rebuild, and (d) the feedback the builder
  reads. None of the four is structural: the SSA form, the passes' algorithms and the code generator's framework stay.
- **The largest single lever is an op descriptor table (`OpInfo`).** Seven predicate switches across five files
  hand-maintain the same few facts about each opcode. One table owned by the language (the *dialect*), queried by the
  core, removes most of the JS opcodes from the passes, and it can be done first and checked with no behaviour change.
- **A language needs no T1 of its own.** The neutral pipeline in a non-speculating mode can be a language's baseline
  (§5). JS keeps `JvmCompiler` as it is.
- **The split reaches beyond `jit/opt/`**: exits and on-stack replacement go through `jit/Deopt.kt`, `vm/Frame.kt` and
  `JvmEmitter`, which T1 uses as well (§6, step 4).

## 2. Size of the code concerned

| file | lines | role |
|---|---|---|
| `opt/Ir.kt` | 344 | graph, blocks, values, FrameState, InlineSite, the `Ir.*` operations |
| `opt/IrBuilder.kt` | 1,301 | bytecode → SSA, inlining, speculation choices, cleanup passes |
| `opt/IrCodegen.kt` | 1,861 | JVM bytecode: locals, edges, exits, OSR entries, one case per specialized op |
| `opt/Kinds.kt` | 119 | kind inference (fixpoint) |
| `opt/Licm.kt`, `KnownShapes.kt`, `ScalarReplacement.kt`, `Unswitch.kt`, `QuietInlines.kt` | 125 / 161 / 96 / 241 / 44 | passes |
| `opt/T2.kt`, `T2Code.kt`, `IrCheck.kt` | 140 / 74 / 149 | driver, deopt counters, verifier and printer |
| `jit/JvmEmitter.kt` | 485 | shared with T1: boxing, conversions, comparisons, interrupt check, **`emitHelper`: the generic code of each JS op** |
| `jit/Deopt.kt`, `vm/Frame.kt` | 137 / 241 | exits' runtime, the frame they convert |

## 3. Classification

### 3.1 Neutral as it is

| what | where |
|---|---|
| SSA construction: a phi per register and stack slot at merges, trivial and dead phi removal, value resolution through states and inline sites | `IrBuilder.buildBlock` (`:569`), `fillPhis` (`:888`), `removeTrivialPhis` (`:1120`), `removeDeadPhis` (`:1208`), `resolveAll` (`:1176`) |
| reverse post order, loop headers, unreachable predecessors | `IrBuilder.order` (`:1069`), `Body.reversePostOrder` (`:532`) |
| dominators, verifier | `IrCheck.kt` (`IrVerifier`) |
| register liveness for exit states (dead registers left undefined) | `Body.liveness` (`:403`), `regAt` (`:393`); only `regUse` (`:456`) and `instSuccessors` (`:441`) read opcodes |
| dead code removal driver | `IrBuilder.removeDeadCode` (`:258`); the predicate `pure` is JS (§3.3) |
| LICM: loop bodies, latches, the preheader block (`loopEntry`), exits resuming at the header | `Licm.hoist`; the predicates `movable`/`clean` are JS |
| loop copying on a callee test | `Unswitch.kt` (touches only `IS_CLOSURE`, `CHECK_CLOSURE`, `BRANCH`, `GOTO`) |
| quiet inlined calls driver | `QuietInlines.run`; the predicate `quiet` is JS |
| codegen framework: a local per value by kind, parallel copies on edges, empty JVM stack at block boundaries, labels, the exception table per block, interrupt checks on back edges | `IrCodegen.allocate` (`:256`), `load`/`store`/`done` (`:278-334`), `emitBlock`/`edge` (`:559-580`), `GOTO`/`SWITCH`/`RETURN` terminators |
| exits as private methods outside every exception range, virtual objects allocated there | `IrCodegen.emitExit`/`exitMethod` (`:105-216`): the mechanism; what it builds (`Deopt.Inlined`) is JS |
| on-stack replacement: which headers are entries, entry blocks of enclosing loops made again, declining entries | `IrCodegen.osrEntries` (`:387`), `liveIn` (`:527`) |
| size discipline: locals renumbered by use, `MAX_CODE`, retry without loop copies | `IrCodegen.renumber`/`codeLength`, `T2.compile` |
| deopt counters, per-speculation limits, revert and recompile policy | `T2Code` (`exit`, `DEOPT_LIMIT`, `SPEC_LIMIT`, `RECOMPILES`) |
| code versions | `Jit.install`/`Jit.revert` (`jit/Jit.kt:118-129`) |

### 3.2 Neutral mechanism, JS content

| what | the JS part | how to neutralize |
|---|---|---|
| `Kinds` (`opt/Kinds.kt`) | the lattice is `JT` (ANY/NUM/BOOL/INT, `jit/JitTypes.kt:27-32`), shared with T1's `TypeAnalysis` and `JvmEmitter.box/convert/truth`; `result()` switches on JS ops | the fixpoint stays in the core; the lattice, its JVM types (`D`, `I`, `J`, Object), boxing and conversions become a `KindSystem` the dialect provides. Python needs `INT64` (long, exits on `Math.*Exact` overflow) and a boolean kind; the result kind of each op comes from `OpInfo` |
| `KnownShapes` | facts are `Shape`s, prototype chains, extensibility; `LOAD_SLOT`/`STORE_SLOT`/`ADD_SLOT` establish them | generalize to facts `(guard kind, value, token)` established by guard ops and killed by ops whose `OpInfo` says they may change them (writes, user code). Python: type version tags, instance layouts, dict versions. The largest pass change; do it after the table |
| `ScalarReplacement` | the candidate is `NEW_OBJECT_LITERAL` with an `ObjectLiteralSite`; reads are `GET_PROP`/`LOAD_SLOT` by `PropSite` key | `OpInfo` marks allocations with fixed fields and reads/writes of a field by key; the escape analysis is the core's |
| speculation exits and their feedback | `Speculation(code, pc)`, `CodeBlock.t2Failed` | stays as it is if the language's code unit has the same two fields (§4.2) |
| on-stack replacement dispatch | `JvmCompiler.backEdgeTargets` (JS jumps), `JitRt.osrKinds`/`slotNum`/`slotInt`/`slotBool`, `Frame.slots`/`pc`/`NO_SLOTS` | back-edge targets from the dialect; the slot readers per kind from the `KindSystem`; the frame accessors behind the frame interface (§3.3) |
| inlining policy | budgets (`MAX_INLINE_DEPTH` 3, `MAX_CALLEE_CODE` 400, `INLINE_BUDGET` 2000), recursion once, the guard or the branch to a real call (`IrBuilder.inline`, `:938`) | the policy and the shape (guard / `IS_CLOSURE` branch, join block, result phi) stay; *which* calls are candidates, how arguments bind and what a callee frame holds come from the dialect |

### 3.3 Dialect (each language's own)

| what | where today |
|---|---|
| **the bytecode walk**: stack effects, operand decoding, jumps, the per-op cases | `Body.buildBlock`'s `when (op)` (`IrBuilder.kt:634-861`), `arity`/`pushes`/`BUILT_OPS`/`isJumpOp` (`:1236-1300`), `scan`/`successors` |
| **operand meaning**: `Inst.a/b/c` are raw bytecode operands, reinterpreted per op (`LOAD_SLOT`: a = shape's data index, b = slot, c = chain) | `Ir.kt:109-140`; the descriptor must own this, or the IR needs typed operands |
| constants: `Undefined`, `Null`, booleans, `Double` as boxed by `Ops.num` | `Const`, `IrCodegen.pushConst`/`constObject` (`:234-322`), `IrBuilder.undefined` |
| the call shape: `InlineSite(fn, thisValue, args, env, construct)`, `INLINE_THIS`, `CLOSURE_ENV`, `NEW_THIS`, `CONSTRUCT_RESULT`, `Deopt.Inlined` | `Ir.kt` `InlineSite`, `IrBuilder.inline`, `jit/Deopt.kt:28-36` |
| frame access in generated code: `Frame.code.constants`, `realm.agent`, `args` (`PARAM`), `inlineFn`/`inlinePc` (`INLINE_ENTER`), `pc` stores for stack traces | `IrCodegen.prologue` (`:338`), `PARAM` (`:727`), `inlineEnter` (`:1576`), `storePc` (`:712`) |
| branch flavors: ToBoolean of a kind, `ITER_STEP`'s `NotFound`, `FOR_IN_NEXT`'s null, nullish and undefined tests | `IrCodegen.terminator` `BRANCH` (`:598`) |
| specialized forms: shape checks, slot loads/stores/adds, dense array elements, `length`, `charCodeAt`, Math intrinsics, int32 arithmetic with overflow exits | `IrCodegen.inst` (`:723-1480`), `binary`/`intBinary`/`intUnary` (`:1481-1767`) |
| **the generic form of every op**: a helper call with Object operands | `JvmEmitter.emitHelper` (`jit/JvmEmitter.kt:256`) |
| the speculation choices and their feedback: `ValueProfile` bits, `PropSite.entry`, `GlobalSite.cache`, `callFeedback`, `callHint`, intrinsic ids | `IrBuilder.numberCheck` (`:1021`), `monomorphic` (`:1032`), `inlineTarget` (`:902`), `intrinsicAt` (`:927`), and the `GET_PROP`/`LOAD_GLOBAL`/`PUT_PROP` cases |
| JS-only analyses | `boundedSteps` (`<` on int32 counters), `demandInts`/`preferDoubles`/`intArith` (int32 vs double choice), `readsThis`, `unassignedParams` |
| exception handlers: `CATCH_VALUE` (`JitRt.catchValue`), homes | `buildCatchEntry`, `IrCodegen` `CATCH_VALUE` (`:1099`); the homes mechanism is neutral, the catch value is the language's |

## 4. The core's interfaces

### 4.1 `OpInfo`: one table instead of seven switches

These functions each list opcodes for the same few properties:

| predicate | file | asks |
|---|---|---|
| `pure` | `IrBuilder.kt:288` | computes only its result (may be removed when unused) |
| `takesObject` | `IrBuilder.kt:1169` | takes all operands as Objects (a number check before it is wasted) |
| `pushes`, `arity`, `BUILT_OPS` | `IrBuilder.kt:1239-1300` | result or not, stack inputs |
| `clean`, `movable` | `Licm.kt:112`, `:66` | cannot run code nor write (but slots by shape); may leave a loop |
| `keeps` | `KnownShapes.kt:148` | cannot run code nor change shapes |
| `quiet` | `QuietInlines.kt:31` | cannot throw nor run code |
| `result` | `Kinds.kt:27` | the result kind given the inputs' |

Most entries share the same qualifier: "true when every input is a number" (no `valueOf`, no BigInt mixing). A
descriptor per op covers all of them:

```kotlin
class OpInfo(
    val name: String,
    val inputs: Int,              // stack inputs, or VARIABLE with a decoder
    val result: Boolean,
    val effects: Int,             // READS_HEAP, WRITES_SLOT, WRITES_ENV, WRITES_ANY, RUNS_CODE, THROWS, ALLOCATES
    val pureWhen: Cond,           // ALWAYS, NEVER, INPUTS_NUMERIC (the dialect's notion of "primitive enough")
    val exits: ExitPoint,         // NONE, BEFORE, AFTER (where its FrameState applies)
    val movable: Boolean,         // LICM may move it when its inputs are invariant and its heap is not written
    val objectOperands: Boolean,  // takesObject
    val resultKind: (Inst, KindSystem) -> Kind,
)
```

The passes then ask `info(i).effects and RUNS_CODE == 0 || info(i).pureWhen == INPUTS_NUMERIC && numeric(i)` instead
of listing ops. The JS table is the current switches transcribed; the step changes no generated code, which Test262
and the stress modes confirm byte for byte (`-Dneonjs.jit.dump` before and after can be diffed).

### 4.2 The other interfaces

- `KindSystem`: the lattice (join, which kinds are numbers, which are ints), JVM type per kind, `box`/`convert`/`truth`,
  OSR slot readers, the constant kinds. JS's is `JT`; T1 keeps using `JT` directly.
- `Dialect.builder(code)`: bytecode → blocks with `Inst`s of the dialect's ops, `FrameState`s where an op may exit,
  and the inline call sites (a callee, its argument binding, a guard). The SSA and cleanup machinery is a base class
  the builder calls (`newBlock`, `goto`, phis at merges, `state(pc)`), so a dialect writes the `when (op)` and its
  speculation choices, not the graph mechanics.
- `Dialect.emit(i, ctx)`: the code of a dialect op (specialized or generic); `ctx` gives `load(v, kind)`, `done`,
  `temp`, exits, `storePc`. `emitHelper` moves behind it.
- `FrameModel`: the prologue (constants, agent), parameters, the position store for stack traces, entering and leaving
  an inlined call (depth, interrupt budget, records for stack traces), and what an exit passes to resume a frame and
  each inlined call. JS's is today's `Frame`/`Deopt.Inlined`; a Python frame holds fast locals, cells, keyword
  arguments, the exception state. `InlineSite` keeps `parent`, `callPc`, `callerState`, `depth`, `quiet`, and an opaque
  per-dialect callee state (values the exit serializes).
- Code unit: what the core reads of a code block: code array, constants, line table, handlers, `t2Failed`,
  `t2Recompiles`, `t2Off`, `compiled`/`baseline`. Either a small interface `CodeBlock` implements, or Python code
  units are `CodeBlock`s with their own instruction set (simpler; the JS-only fields stay unused).

## 5. Does each language need a T1?

No, on conditions that can be measured.

T1's role is a baseline that does not speculate, does not deoptimize, is never replaced, and compiles everything the
interpreter runs. The neutral pipeline with speculation off can play it:

- P1 ("IR at parity", docs/OPTIMIZING_TIER.md) passed Test262 with `t2Stress` in every mode, 691K blocks compiled by
  the IR path, its code matching T1's except where T1 inlined calls;
- `T2.SPECULATE` (`-Dneonjs.jit.t2Speculate=false`) already turns every profile- and cache-driven choice off;
- `IrBuilder.inline` already has T1's form of inlining in IR: the `IS_CLOSURE` branch to the body or to a real call;
- `Jit.install`/`revert` only require that the baseline is the version never replaced, not that `JvmCompiler` made it.

What a language provides is the same with one tier or two: interpreter, builder, `OpInfo` + emission, frame model.
JS keeps `JvmCompiler` (the reference Test262 is checked against, and what decides when the paths disagree);
replacing it is a separate decision for later, on measurements.

Conditions for the baseline mode (exit criteria of step 6):

1. **No exits.** With speculation off, inlined calls still get `CHECK_CLOSURE` with a state (`IrBuilder.kt:964`): an
   exit. The baseline mode must build every inlined call as the `IS_CLOSURE` branch (or not inline), and keep only
   the stress mode's exits. Check: the Test262 runner's deopt count is 0 without `deoptStress`. (The measurement
   below had 0 exits, but only because no guard failed there: the exits are in the code.)
2. **Class sharing.** T1 shares classes by `JitInput.identity` through `CodeCache` (library code compiled once for
   every context). `T2.compile` makes one class per block with `g.data`. Without speculation, `data` holds little
   (inlined callees' constants, `Speculation`s), so an identity of the graph's inputs is possible: a design item.
3. **Batches on Android.** T1 goes through `JitBackend` (`ClassBackend.defineAll`: one dex file per batch);
   `T2.compile` defines classes one by one. A baseline compiles every block that gets hot, so it must go through
   `JitBackend`.
4. **Compile time.** IR construction, `Kinds.infer` up to three times and the cleanup passes, against T1's one pass.
   A first rough measurement (below): under about 2 times T1's cost on HotSpot, as an upper bound (T1's cost there is
   its compile time minus what its code gained on execution, and the number of blocks T1 compiled is not counted).
   Android (dx/D8 per class) is not measured.
5. **Size.** Blocks whose IR code is past `T2.MAX_CODE` (8,000 bytes) are not compiled by the IR path; T1 compiles
   them, without checking that limit: 8–9 of ~900 in the measurement below. A baseline has to compile them anyway: split them, or accept code the
   JVM leaves in its bytecode interpreter (still faster than NeonJS's interpreter? unmeasured).
6. **Coverage.** Whatever the interpreter runs. The IR path already covers exception handlers (T1's are JVM handlers
   too); neither covers generators and async functions (`JvmCompiler.canCompile`, `IrBuilder.build:50`): the neutral
   core adds them once, for both languages (`docs/PYTHON_PLATFORM.md` §4.7).

First measurement (2026-11-05, HotSpot 25.0.5, 8 cores, the CLI in compiled mode with `--node`, a script loading 11 of
the Node modules and running `util.inspect` 200 times; 3 runs each, twice, interleaved; wall time of the process):

| mode | wall time | T2 blocks compiled / not compiled | calls inlined | exits |
|---|---|---|---|---|
| `--interpreter` | 1,406–1,514 ms | – | – | – |
| `--compiled` (T1) | 1,650–1,799 ms | – | – | – |
| `--compiled`, `t2Stress`, `t2Speculate=false` | 2,102–2,260 ms | 817–986 / 8–9 (code past 8,000 bytes) | 249–276 | **0** |

T1 adds ~0.27 s over the interpreter; the IR path, compiling the same blocks again right after T1 (JitQueue.kt:126,
partly on worker threads), adds ~0.47 s more. Indicative only: the runs mix compilation with execution, and the
number of blocks compiled varies with how far background compilation got before the process ended. A proper
measurement times `T1` and `T2.compile` per block.

## 6. Order of the split

Each step ends with no change in behaviour, checked with `tools/check.sh` (docs/TESTING.md): `quick` (unit tests,
`MethodSizeTest` among them, and 1 in 20 of Test262 in the stress modes) and, where the step should not change
generated code, `codegen` (the classes generated before and after compared). All of Test262 in the stress modes
(`full`) and NeonBench before the pull request.

| step | what | touches | should generated code change |
|---|---|---|---|
| 1 | `OpInfo` table for JS; the seven predicates become lookups | `opt/*` | no |
| 2 | `KindSystem` behind the core's uses of `JT` (T1 unchanged) | `Kinds`, `IrCodegen`, `JvmEmitter` | no |
| 3 | builder base class (SSA, states, inline shape) + JS builder; `Dialect.emit` with JS's cases and `emitHelper` | `IrBuilder`, `IrCodegen` | no |
| 4 | `FrameModel`: prologue, parameters, positions, inlined call records, exit payload and resume entry; opaque callee state in `InlineSite` | `IrCodegen`, `Ir.kt`, **`jit/Deopt.kt`, `vm/Frame.kt`** (used by T1 too) | no |
| 5 | `KnownShapes` → generic facts; `ScalarReplacement` on `OpInfo` | passes | no |
| 6 | baseline mode (§5's conditions): no-exit inlining, identity and `CodeCache`, `JitBackend` batches, compile time measured | `T2`, `Jit`, `JitBackend` | only in the new mode |
| 7 | generators and async functions in the core (state machine: resume dispatch, locals saved in the frame) | core, JS dialect, `Deopt` | yes (new coverage) |

Only after these does a second dialect start (Python's, docs/PYTHON_PLATFORM.md). A toy dialect (a few arithmetic and
call ops over its own small bytecode) between steps 4 and 6 would show whether the interfaces are enough, before
Python's front end exists.

Scheduling against OPTIMIZING_TIER.md: P4 and P5 keep changing `IrBuilder`, `IrCodegen` and the passes. Step 1 is
mechanical and small enough to do now; steps 3 and 4 move most lines and are best done between T2 changes, not
alongside them.

## 7. What stays in the language's hands

Even with every step done, a dialect decides what is worth speculating on and writes the specialized code of its own
ops (a Python `BINARY_OP` on two small ints: `Math.addExact` with an exit; an attribute load behind a type version
check). The core gives it the mechanisms (guards with exits, kinds and unboxing, LICM of what `OpInfo` marks movable,
facts after guards, scalar replacement of fixed-field allocations, inlining with frame rebuilding, size budgets, OSR)
so that the dialect never handles JVM locals, edges, exit methods or ART's limits itself.
