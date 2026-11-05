# Testing during development

All of Test262 in every mode and stress mode takes long; most changes can be checked in a minute or two and the whole
suite run once before a large pull request. `tools/check.sh` has the three levels:

| level | command | what | time (8 cores) |
|---|---|---|---|
| quick | `tools/check.sh quick` | neonjs-core's unit tests; a fixed 1 in 20 of Test262 in the interpreter and in the JIT's stress modes | ~2.5–3 min |
| codegen | `tools/check.sh codegen [REF]` | the classes the JIT generates for that part of Test262, against those of REF (default `main`) | ~1.5 min (~3 the first time for a REF) |
| full | `tools/check.sh full` | what CI runs, then all of Test262 in the stress modes | long: before a large pull request |

Run *quick* after each change, *codegen* after each change that should not change the code generated (a refactoring of
the compilers), *full* before a pull request that touches the interpreter, the compilers or the runtime broadly. CI runs
its own part of *full* on every pull request.

The script checks Test262 out at CI's revision (`TEST262_COMMIT` in `.github/workflows/ci.yml`) into
`third_party/test262` when it is missing. `GRADLE_ARGS` passes arguments to every `./gradlew` run (a toolchain's path,
say), `SAMPLE` changes the part of Test262 (`SAMPLE=5`: one test in five), `THREADS` the runner's threads.

## Quick

The sample is the tests whose path's CRC-32 is 0 modulo 20 (`--sample 20` of the runner): about 2,700 of 50,000, the
same ones in every run, spread over every directory. Each runs in

- the interpreter;
- compiled mode with `t2Stress` (every block compiled by T1, then by T2 at once);
- the same with `deoptStress` (exits taken one time in three, so every exit's state is used);
- adaptive mode with `t2Stress` and `osrStress` (every loop entered compiled after its first iteration), with and
  without `deoptStress`;
- compiled mode with `irBaseline` (every baseline made by the optimizing tier's pipeline without speculations, T1's
  only where it has none: what a language without a compiler of its own runs, docs/IR_NEUTRALITY.md §5), which must
  take no exit at all, and with `baselineCheck`: every baseline generated even where a graph of the same identity has
  a class already (GraphIdentity), and the two compared, which must never differ;
- adaptive mode with `irBaseline`, `t2Stress`, `deoptStress` and `osrStress` (the tier's speculating code over those
  baselines, loops entered in both).

The modes are the ones docs/OPTIMIZING_TIER.md requires before a push, and the baseline modes; the sample keeps them to
10–40 seconds each.
The generated Unicode property tests (`built-ins/RegExp/property-escapes/generated`) are left out: they check RegExp's
tables and take most of the interpreter's time. The timeout is 60 s rather than 10, so that a loaded machine does not
fail a test.

## Generated code unchanged

A refactoring of the compilers (docs/IR_NEUTRALITY.md's steps) should not change one instruction of what they generate.
`codegen` checks that directly: it builds REF in a worktree (`build/check/base`, with this runner), runs the sample
compiling on the calling thread (`--sync-jit`: the code then depends on nothing but the test) in three variants,
dumps every class (`-Dneonjs.jit.dump`), does the same with the working tree and compares the two with `ClassDiffKt`
(neonjs-test262): the same classes, as many of each, with the same code as ASM prints it. T2's class names end with a
counter, which is removed first; T1's end with a hash of their input. The variants:

- `t2`: compiled mode, `t2Stress`: every block by T1 and T2, before it runs;
- `stress`: the same with `deoptStress` (the stress exits in both tiers' code);
- `adaptive`: adaptive mode, `t2Stress` and `osrStress`: blocks compiled after the interpreter filled their profiles and
  caches, so that the speculations and the passes using them (LICM, the facts after shape checks) have something to
  do. Turning LICM off changes classes in all three.

The tests of agents waiting on one another (`built-ins/Atomics/wait`, `waitAsync`, `notify`) are left out of the
dumps: their loops spin for as long as another thread takes, so how often a function is called there, and whether it
gets compiled, depends on timing. Two dumps of the same build are equal without them.

A change that compiles more (code not compiled before) adds classes and must change none: `SUBSET=1 tools/check.sh
codegen` checks that every class of REF's is there, unchanged. `CODEGEN_OPTS` gives both sides' dumps JVM properties
(`CODEGEN_OPTS=-Dneonjs.jit.suspendable=false`: without generators and async functions compiled).

On branch `ir-neutral-core`, whose step 7 compiles generators and async functions, `codegen main` reports those
classes as added until the branch is merged: compare with `SUBSET=1`, with a later commit of the branch (`codegen HEAD`,
the committed tree), or with `CODEGEN_OPTS=-Dneonjs.jit.suspendable=false`.

Step 9 of docs/IR_NEUTRALITY.md (§10, the core as a module of its own) renames what every class names: `run` takes the
core's `VmFrame`, which JavaScript's code casts to its `Frame` first, and the core's code calls its own helpers. The
comparison applies the renames `tools/codegen-equiv.txt` lists to both sides (`type NEW = OLD`: class NEW counts as
OLD; `entry-cast CLASS`: the cast at a `run` method's start is dropped), and prints first how many names differ as
generated. Nothing else counts as no change: a list that grew to hide a difference would be visible in its diff.

REF's dumps are kept in `build/check/dumps/<commit>-*`, so comparing against the same REF again only dumps the working
tree. `ClassDiffKt BEFORE AFTER [--show N] [--subset] [--equiv FILE]` can compare any two dump directories.

## Full

CI's runs (the three modes, compiled with every class translated by dx, intl402), then all of Test262 in the four
stress modes; web-platform-tests and Node.js's tests where `third_party/wpt` and `third_party/node` are checked out (CI
checks them out; see `.github/workflows/ci.yml`).

## Runner options

`neonjs-test262` takes, besides the paths or directories to run and `--mode`:

- `--sample N`: the 1 in N tests described above;
- `--exclude PREFIX`: leaves out tests whose path (relative to `test/`) starts with PREFIX; repeatable;
- `--list FILE`: test paths, one per line (`#` comments);
- `--sync-jit`: compiles on the calling thread (no background compilation);
- `--timeout MS`, `--threads N`, `--known FILE`, `--intl`, `--staging`, `--out FILE`, `-v`.

The JIT's own switches are JVM properties, given in `JAVA_OPTS`: `-Dneonjs.jit.t2Stress`, `-Dneonjs.jit.deoptStress[=N]`,
`-Dneonjs.jit.osrStress`, `-Dneonjs.jit.t2Speculate=false`, `-Dneonjs.jit.dump=DIR`, `-Dneonjs.jit.t2Print=name`,
`-Dneonjs.jit.t2Verify`, `-Dneonjs.jit.t2Stats` (docs/OPTIMIZING_TIER.md, "Testing").
