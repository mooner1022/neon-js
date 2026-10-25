# Performance: NeonJS, V8 and Rhino

Measured on 2026-10-25 at commit fa1a7b0, on a desktop JVM. The benchmark is [`bench/compare.js`](../bench/compare.js)
and the runner [`bench/compare.sh`](../bench/compare.sh). Android, NeonJS's main target, is not measured here.

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
