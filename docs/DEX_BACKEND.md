# Emitting dex directly: review

On Android the JIT currently generates JVM class files with ASM and translates them to dex (dx, or D8 with
`neonjs-android-d8`). This note reviews emitting dex directly from NeonJS bytecode, with Google's dexlib2 or a writer
of our own. Status: not started; the `JitBackend` interface (`jit/JitBackend.kt`) is where such a backend would plug
in, below the tiering policy and the background compiler.

## What the current path cannot fix

**Methods ART never compiles.** ART skips compiling methods of `UINT16_MAX / 4` (16,383) code units or virtual
registers and more (`Compiler::IsPathologicalCase`, `art/compiler/compiler.cc`), for the JIT and ahead of time alike;
such methods only ever run in ART's interpreter. dx expands JVM bytecode considerably, so methods well below the JVM's
64 KB limit cross that line. Converting every class Test262 generates with dx and measuring the `run` methods:

| dex code units | < 1K | 1K–4K | 4K–8K | 8K–16,383 | ≥ 16,383 |
|---|---|---|---|---|---|
| classes (97,102 distinct) | 86,962 | 9,315 | 580 | 107 | **138** (largest 159,151) |

None exceeded the register limit. The 24 Test262 functions reported as "method too large" are the JVM limit and are
not among these; the 138 are compiled and installed today, and on a device they run as dex interpreted by ART rather
than in NeonJS's own (ahead-of-time compiled) interpreter. Whether that is slower has not been measured.

With dx there is no remedy at the source: it is frozen. The current path can only reject such classes after
conversion (read `insns_size` of the `code_item`s in the dex just produced and fail that class, so the function stays
in NeonJS's interpreter). A direct backend knows the size while emitting and can stop early.

**Java 8 class files.** dx reads class files up to version 52, which constrains the code generator. D8 lifts this, at
a large fixed cost per run (see the README).

## dexlib2

`com.android.tools.smali:smali-dexlib2` 3.0.10 (released 2026-09-04): the smali/baksmali library, maintained by
Google since the original project stopped. 1.2 MB plus Guava (31.1-android, 2.8 MB; both shrink under R8). Its
classes are Java 8 class files, so it runs on API 26.

Checked with a prototype (outside the repository):

- `DexPool` with a `MemoryDataStore` writes a dex file in memory; `MethodImplementationBuilder` builds code with
  forward labels (`getLabel` / `addLabel`) and try blocks (`addCatch`). `dexdump` decodes the output as written.
- Writing a class whose `run` method has 22 code units (read the frame's constants, count down the interrupt budget,
  call a static runtime helper through a register range, branch, return): about 90–100 µs per class with one class per
  dex file, about 8.5 µs per class in batches of 32, on a desktop JVM. These are floor numbers: real methods are
  larger, and the time grows with them.
- `MethodAnalyzer` propagates register types but is not a verifier: it accepted a read of an uninitialized register,
  an int used as an object reference, and an int passed for an `Object` parameter.

A writer of our own would replace the dependency with the dex container format (header, id sections, `class_data`,
`code_item`s with try/catch tables, map list, Adler-32 and SHA-1) and instruction encoding (formats, payloads,
alignment); dexlib2 provides both.

## What a direct backend involves

The JVM generator (`JvmCompiler`, 748 lines) has 161 opcode cases, 152 of them calls to `JitRt` helpers. A dex
generator mirrors it case by case:

- **Registers.** JS registers map to fixed dex registers; the operand stack maps to a contiguous register window, its
  depth at each pc known statically (`Op` stack effects). Helper arguments taken from the stack are then already a
  contiguous range for `invoke-static/range`; the frame, constants array and immediates are moved into scratch
  registers just above the stack top. Instructions address 256 registers unless a `/range` or `/16` form exists, so
  large frames need moves to low registers.
- **Exceptions.** Handler ranges become try blocks with a catch-all; `move-exception` must be the first instruction of
  a handler (Dalvik bytecode spec). Registers live across a try range must have consistent types at every throwing
  instruction; NeonJS values are all object references (registers start as `undefined`), with a few int temporaries
  (the interrupt budget, branch conditions) kept in their own registers.
- **Switches, tail calls, returns**: `packed-switch` payloads, `return-object`.
- **Size cap**: stop at 16,383 code units (see above) and leave the function interpreted.

## Testing

This is the main cost. Today every generated class is also checked on a JVM: `DexCheckingDefiner` converts it to
dex and runs the JVM class, and Test262 runs that way in about a minute. Dex emitted directly has no such path:

- Neither D8 nor R8 reads dex back into class files (D8: `CfApplicationWriter cannot write non cf writable code`;
  R8: `R8 does not support compiling DEX inputs`).
- dexlib2's `MethodAnalyzer` does not reject invalid code; a JVM-side check would be our own lint over its register
  types (operand categories per instruction).
- So correctness rests on device runs. A hard verification failure surfaces as a JIT failure reason (`compileAll`
  catches it at definition, and the function stays interpreted); soft failures, which make ART run a method in its
  slower interpreter with access checks, do not.

## Recommendation

Not now. Compilation already runs off the JS thread and in batches (on a desktop JVM, 5,000 distinct functions
compiled at once take 671 ms instead of 1,331 ms through dx, 2,400 ms instead of 7,150 ms through D8), so saving the
translation step matters less than it did; the seam for a direct backend exists; and its testing would rest on device
runs.

Follow-ups:

1. **Size cap on the current path**: reject classes whose dex `run` method reaches 16,383 code units (a small parser of
   the converter's output in `neonjs-android`), after measuring on a device that such a function is indeed faster in
   NeonJS's interpreter than as ART-interpreted dex.
2. **If the direct backend is built**: a register-type lint on `MethodAnalyzer` output for the desktop test suite, and
   Test262 on a device in compiled and adaptive mode as the gate.
