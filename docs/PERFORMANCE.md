# Performance: NeonJS, V8 and Rhino

Measured on 2026-10-25 at commit fa1a7b0, on a desktop JVM. The benchmark is [`bench/compare.js`](../bench/compare.js)
and the runner [`bench/compare.sh`](../bench/compare.sh). Android, NeonJS's main target, is measured on ART in
the emulator: see [Android](#android).

## Setup

| | |
|---|---|
| Machine | AMD Ryzen 7 9800X3D (8 cores, 16 threads), 31 GB, Windows 11 |
| JVM (NeonJS, Rhino) | Temurin 25.0.2, default heap, `-Xss16m` |
| NeonJS | fa1a7b0, CLI (`neonjs --interpreter / --compiled / --adaptive`); T2: `--adaptive` with `-Dneonjs.jit.tier2=true` |
| V8 | Node.js 25.9.0 (V8 14.1.146.11) |
| Rhino | 1.9.1 (`rhino-all`), `-version 200`; interpreted `-opt -1`, compiled `-opt 9` |

`bench/compare.js` has ten workloads, each aimed at a different part of an engine:
- `sieve`: integer loop over a `Uint8Array`
- `nbody`: floating point on object fields
- `binaryTrees`: allocation and GC
- `polymorphic`: one call site over six shapes
- `strings`: build, join, search, case mapping, split
- `regex`
- `functional`: closures, `map`/`filter`/`reduce`/`sort`
- `collections`: `Map` and `Set`
- `matmul`: a `Float64Array` matrix product
- `json`: `stringify` and `parse`

They are the algorithms of `jsbench.js`, written in a dialect all three engines parse. Rhino has no block scoping in
loops and no classes, so the file uses `var`, constructor functions and no destructuring. Every engine runs the same
file.

Method:
- Each workload runs once at a quarter of its size to warm up, then three times; the median of the three is
  reported.
- Each workload returns a checksum, which must match on every engine. A result computed differently is not a time.
- Every configuration runs in its own process, one at a time, on an otherwise idle machine.
- The whole run was repeated three times; the tables give the median of the three medians.
- Run-to-run spread was within 25% except where noted. Read differences under about 20% as noise.

## Results

Milliseconds per run, lower is faster:

| workload | V8 | NeonJS interpreter | NeonJS compiled | NeonJS adaptive (default) | NeonJS adaptive + T2 | Rhino interpreted | Rhino compiled |
|---|---:|---:|---:|---:|---:|---:|---:|
| sieve | 129 | 12,002 | 780 | 782 | 440 | 10,724 | 449 |
| nbody | 178 | 33,812 | 4,738 | 4,456 | 31,163 ¹ | 37,624 | 4,834 |
| binaryTrees | 83 | 2,232 | 456 | 444 | 367 | 4,912 | wrong result ² |
| polymorphic | 196 | 1,585 | 460 | 425 | 310 | 2,271 | 424 |
| strings | 168 | 957 | 356 | 348 | 269 | 1,736 | 554 |
| regex | 163 | 1,617 | 1,826 | 1,709 | 2,038 | 3,117 | 2,806 |
| functional | 173 | 1,527 | 572 | 570 | 560 | 2,484 | 481 |
| collections | 144 | 536 | 332 | 285 | 281 | 737 | 337 |
| matmul | 140 | 33,044 | 1,972 | 2,801 | 2,937 | 27,595 | 1,676 |
| json | 168 | 354 | 354 | 363 | 378 | 382 | 385 |

Geometric mean of the time ratios (lower is faster):

| | vs V8 (10 workloads) | vs the Rhino counterpart |
|---|---:|---:|
| NeonJS interpreter | 18.0x | 0.73x of Rhino interpreted (10) |
| NeonJS compiled | 5.1x | 1.00x of Rhino compiled (9, without `binaryTrees`) |
| NeonJS adaptive (default) | 5.1x | 1.00x of Rhino compiled (9) |
| NeonJS adaptive + T2 | 5.5x (3.7x without `nbody`, where adaptive is 4.2x) | 1.12x of Rhino compiled (9) |
| Rhino interpreted | 24.5x | — |
| Rhino compiled | 5.0x (9) | — |

Wall time of the whole run, including JVM start, warm-up and compilation:
- V8: 5 s.
- NeonJS: compiled 39–45 s, adaptive 42–44 s, interpreter 283–288 s, adaptive + T2 125–135 s (`nbody`¹).
- Rhino: compiled 40–42 s, interpreted 299–307 s.

¹ The optimizing tier compiles `nbody` into a JVM method of more than 8,000 bytes of bytecode. HotSpot never
JIT-compiles such a method (`-XX:+DontCompileHugeMethods`), so it runs in the JVM's bytecode interpreter. With
`-XX:-DontCompileHugeMethods` the same run takes 2,097 ms, 2.1x faster than without T2. The tier has no size limit
for the code it generates yet; until it does, this number is what T2 users on HotSpot get.

² Rhino 1.9.1's compiled mode (`-opt 0` and above) evaluates `{ l: make(d - 1), r: make(d - 1) }` wrongly: `make(2)`
returns a tree of depth 1. Its checksum differs, so the time (12 ms) is left out. Interpreted Rhino gets it right.
Rhino 1.7.15.1's compiled mode has the same bug (checksum 87377 on Android).

## Reading the numbers

- **Against Rhino**, the engine NeonJS replaces in most embeddings, the default adaptive mode matches Rhino's
  compiled mode overall (geometric mean 1.00x). By workload:
  - NeonJS is faster on strings (0.63x), regular expressions (0.61x), `Map`/`Set` (0.85x), JSON and `nbody`.
  - It is slower on the typed-array loops: `sieve` 1.74x, `matmul` 1.67x.
  - It is slower on `functional` (1.18x).
  - `polymorphic` is even.
  
  NeonJS's interpreter takes 27% less time than Rhino's on the geometric mean, and half of it on allocation, strings
  and `regex`.
- **Against V8**, NeonJS's compiled tiers take about 5x the time, as does compiled Rhino. The gap is widest for
  numeric code: 25x on `nbody`, 20x on `matmul`. It is narrowest where the work happens in built-ins: `json` 2.2x,
  `collections` 2.0x, `strings` 2.1x. V8 also reaches its speed within the warm-up run, while the JVM engines need
  their whole run and a JVM start.
- **The optimizing tier (T2)**, off by default, speeds up the shape- and type-dependent workloads over adaptive mode:
  `sieve` 1.8x, `polymorphic` 1.4x, `strings` 1.3x, `binaryTrees` 1.2x. Apart from `nbody` (¹), it does not change
  the others.
- **Adaptive against compiled mode** is the same except for `matmul`, where adaptive takes 2,801 ms against 1,972 ms
  in all three passes. The cause has not been analyzed yet.

## Noted spread

Medians over the three passes; these varied by more than 25% between passes:
- NeonJS compiled: `sieve` 622–838 ms, `nbody` 4,310–5,431 ms, `functional` 567–833 ms.
- NeonJS adaptive: `sieve` 621–782 ms, `json` 350–459 ms.
- NeonJS adaptive + T2: `json` 368–488 ms.
- NeonJS interpreter: `regex` 1,579–2,024 ms.
- Rhino compiled: `sieve` 443–604 ms.

## Android

Measured on 2026-10-25 at the same commit, on ART in the Android emulator. The emulator runs ART for x86_64 on the
desktop machine above, so:
- the numbers compare engines and tiers on ART;
- they are not phone times;
- against the desktop tables, the CPU is the same, so a difference is ART against HotSpot.

A phone was not used because its clock and temperature could not be held steady between runs.

| | |
|---|---|
| Device | Android emulator, `sdk_phone64_x86_64` Android 14 (API 34, UE1A.230829.036), 6 vCPUs, 4 GB, on the Ryzen 7 9800X3D above (WHPX), otherwise idle |
| Runtime | ART of that image, `app_process`; default heap (`dalvik.vm.heapgrowthlimit` 192 MB, as an app without `largeHeap`) |
| Engines | dex bundles compiled ahead of time (`dex2oat --compiler-filter=speed`); code each engine generates is turned into dex at run time and compiled by ART's JIT |
| NeonJS | fa1a7b0, the CLI's main class; T2 as on the desktop |
| Rhino compiled | 1.7.15.1 with rhino-android 1.6.0 (`AndroidContextFactory`, dx) |
| Rhino interpreted | 1.9.1 and 1.7.15.1 |

Why Rhino 1.7 for compiled mode: from 1.8 on, Rhino's compiled code links against `jdk.dynalink`. Android has no
`jdk.dynalink`, so compiled Rhino 1.9.1 fails there with `ClassNotFoundException`, and 1.7.15.1 is the newest Rhino
that compiles on Android. It is not a weak baseline: on ART its interpreter takes 0.92x the time of 1.9.1's. The
launcher, the runner and their setup are in [`bench/android`](../bench/android/README.md).

Method:
- As on the desktop, with one change: the interpreters ran at a quarter of the size, since a full-size run of one
  interpreter takes about ten minutes on ART.
- The emulator ran the whole set three times, one process at a time, unpinned. The tables give the median of the three
  medians.
- Spread between the passes was within 10%, except Rhino 1.7 interpreted on `matmul` (13.2–16.9 s).
- Every checksum matched, except Rhino 1.7's compiled `binaryTrees` (²). At full size the checksums match the expected
  values. At a quarter size they match V8's at that size.

Compiled tiers at full size, milliseconds per run:

| workload | NeonJS compiled | NeonJS adaptive (default) | NeonJS adaptive + T2 | Rhino 1.7 compiled |
|---|---:|---:|---:|---:|
| sieve | 2,259 | 2,260 | 2,943 | 3,968 |
| nbody | 17,430 | 17,832 | 155,476 ³ | 38,485 |
| binaryTrees | 1,588 | 1,588 | 1,189 | wrong result ² |
| polymorphic | 1,221 | 1,238 | 610 | 1,837 |
| strings | 2,183 | 2,226 | 1,878 | 5,598 |
| regex | 3,377 | 3,353 | 4,401 | 9,261 |
| functional | 1,632 | 1,660 | 1,542 | 3,875 |
| collections | 1,098 | 1,138 | 1,093 | 1,642 |
| matmul | 15,548 | 15,341 | 16,669 | 15,524 |
| json | 1,734 | 1,716 | 1,749 | 2,616 |

Interpreters at a quarter of the size, milliseconds per run (compare them with each other only, not with the table
above):

| workload | NeonJS interpreter | Rhino 1.9.1 interpreted | Rhino 1.7 interpreted |
|---|---:|---:|---:|
| sieve | 5,614 | 5,769 | 6,069 |
| nbody | 15,314 | 31,538 | 29,055 |
| binaryTrees | 5,729 | 15,308 | 11,343 |
| polymorphic | 854 | 1,664 | 1,128 |
| strings | 922 | 1,877 | 1,824 |
| regex | 928 | 3,201 | 2,569 |
| functional | 1,254 | 2,596 | 3,114 |
| collections | 384 | 512 | 554 |
| matmul | 14,610 | 14,506 | 13,414 |
| json | 446 | 659 | 660 |

Geometric mean of the time ratios (lower is faster):

| | against | ratio |
|---|---|---:|
| NeonJS compiled | Rhino 1.7 compiled (9, without `binaryTrees`) | 0.55x |
| NeonJS adaptive (default) | Rhino 1.7 compiled (9) | 0.55x |
| NeonJS adaptive + T2 | Rhino 1.7 compiled (9) | 0.68x (0.54x without `nbody`) |
| NeonJS interpreter | Rhino 1.9.1 interpreted (10) | 0.56x |
| NeonJS interpreter | Rhino 1.7 interpreted (10) | 0.61x |

Wall time of the whole run, including the start of the process:
- NeonJS: compiled 158–159 s, adaptive 158–165 s, adaptive + T2 611–616 s (³).
- Rhino 1.7 compiled: 269–272 s.
- At a quarter of the size: NeonJS interpreter 154–155 s, Rhino 1.9.1 interpreted 261–271 s, Rhino 1.7 interpreted
  234–250 s.

³ The same limit as ¹, on ART: ART does not compile a method of more than 10,000 dex code units
(`--huge-method-max`). With `-Xcompiler-option --huge-method-max=1000000`, `nbody` alone takes 3,838 ms, 4.6x faster
than without T2. Apps cannot pass that option, so the 155 s is what T2 gives an app today.

### Reading the Android numbers

- **NeonJS's default mode takes 0.55x the time of compiled Rhino** on ART. On the desktop it took 1.00x of Rhino
  1.9.1's time. The Rhino version is not the whole difference: here Rhino 1.7's interpreter is as fast as 1.9.1's. By
  workload, against compiled Rhino:
  - `regex` 0.36x, `strings` 0.40x, `functional` 0.43x, `nbody` 0.46x, `sieve` 0.57x.
  - `json`, `polymorphic` and `collections` about 0.67x.
  - `matmul` even.
- **NeonJS's interpreter takes 0.56x the time of Rhino 1.9.1's** (0.73x on the desktop). It takes about a third of the
  time on allocation (`binaryTrees`) and `regex`, and half on `nbody`, strings, `polymorphic` and `functional`. It is
  even on `sieve` and `matmul`.
- **`matmul` takes the same time in every engine and every tier**: 15.3–16.7 s compiled, 13.4–14.6 s interpreted at a
  quarter of the size. Where that time goes on ART, in both engines alike, has not been analyzed.
- **T2 on ART** halves `polymorphic` and speeds up `binaryTrees` 1.3x and `strings` 1.2x over adaptive mode. It slows
  down `sieve` and `regex` by 1.3x. Without `nbody` (³), its geometric mean is 0.93x of adaptive mode.
- **Compiled and adaptive modes are even** on ART. That includes `matmul`, where adaptive mode was slower on HotSpot.
- **ART against HotSpot**, same CPU: NeonJS's adaptive mode takes 2–6x the time it takes on the desktop JVM (`regex`
  2.0x, `strings` 6.4x).

## Reproducing

```bash
./gradlew :neonjs-cli:installDist
bench/compare.sh build/bench-results path/to/rhino-all-1.9.1.jar    # node on the PATH
```

`compare.sh` writes one file per configuration (`workload median-ms warm-up-ms checksum`). A single engine runs
`compare.js` directly:
- `node bench/compare.js`
- `neonjs bench/compare.js`
- `java -jar rhino-all.jar -version 200 bench/compare.js`

Only V8 and Rhino receive the scale argument; NeonJS's CLI passes script arguments to `--node` scripts only.

On Android, see [`bench/android/README.md`](../bench/android/README.md).
