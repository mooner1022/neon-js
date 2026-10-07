# NeonJS security model

NeonJS is designed so that a host can run scripts it does not trust. This document states what the sandbox
guarantees, how, and where the host remains responsible.

## Threat model

Scripts may be malicious: they may try to exhaust CPU, memory or stack, escape to the host (reflection, class
loading, file/process access), interfere with other contexts, observe host internals, or prevent their own
termination. The host's Java code and the classes it chooses to expose are trusted.

## Guarantees

**Isolation.** Each `NeonContext` has its own realm, intrinsics, globals, job queue and object graph. Shapes and
inline caches never carry state between contexts (see ARCHITECTURE.md). Compiled scripts can be shared between
contexts because code blocks are immutable apart from caches that are validated per context.

**Termination.** With a `SandboxPolicy` the engine enforces, per top-level evaluation (including the promise jobs
it triggers):

| Limit | Policy | Enforcement points |
|---|---|---|
| wall-clock time | `maxExecutionTime` | loop back-edges, calls, job dispatch, RegExp matcher, long built-in loops (including generic array methods over array-likes with lengths up to 2^53 - 1), `Atomics.wait`, event-loop waits |
| instruction budget | `maxStatements` | same as above |
| call depth | `maxCallDepth` | every JS call (proper tail calls do not grow the depth but remain interruptible) |
| string length | `maxStringLength` | concatenation, `repeat`, `pad*`, `join`, JSON, template building… |
| allocated bytes | `maxAllocatedBytes` | sampled with the time checks; large allocations (buffers, Java arrays, long strings) are charged before they happen |
| host interrupt | `NeonContext.interrupt()` | same as time |

Termination exceptions are not JS exceptions: `catch`, `finally` and promise reactions cannot intercept them.
Exceeded limits are sticky for the rest of the evaluation, so the condition is raised again at the next check even
if host code swallowed the exception. Every entry into a context from the host (`eval`, a call through a
`NeonValue`, a callback proxy invoked by a host thread) starts a fresh limit window.

**Host access.** Nothing of the host is visible unless the host puts it there (`ctx[...]`, `exposeClass`,
`defineModule`, `setFunction`) or enables the `Java` global. `HostAccess` decides what is visible on those objects:

- `NONE`: host objects are opaque.
- `EXPLICIT`: only members annotated with `@HostExport` (or members of annotated classes).
- `ALL`: all public members.

At every level a deny list blocks `java.lang.Class`, class loaders, `System`, `Runtime`, processes, threads,
`SecurityManager`, modules, `StackWalker`, `Unsafe`, `MethodHandles`, object serialization streams, reflection
and `java.lang.invoke` packages, `sun.*`/`com.sun.*`/`jdk.internal.*`, `java.security.*`, management and
instrumentation APIs, and the engine's own internal packages. `getClass`, `wait`, `notify*` and `finalize` are
never exposed. Arrays are judged by their element type. `Java.type` additionally requires an explicit
`allowLookup` predicate. `Java.extend` requires `allowImplementations` and only extends accessible, non-final,
non-denied classes; adapters are defined in their own class loader, and under `EXPLICIT` their methods are visible
only where they override an exported method. Host-defined modules (`defineModule`) expose exactly the values given.

**Realms created by scripts.** A `ShadowRealm` gets fresh intrinsics only: none of the host's globals, no `Java`
object, no console, and it cannot import host-defined modules (`defineModule`; modules from the context's
loader are available). Only primitives and callables cross its boundary (callables as
wrapped functions), so host objects cannot be smuggled in or out. It shares the agent of its creator, so every
limit above applies to code running inside it, and `allowEval(false)` also disables `ShadowRealm.prototype.evaluate`.

**Determinism and host fingerprinting.** `SandboxPolicy.builder().deterministic(seed, fixedTime)` makes
`Math.random`, `Date.now`, `new Date()` reproducible, which also removes timing side channels based on the clock,
and pins the time zone to UTC and the default locale to `en-US`. `timeZone(...)` and `defaultLocale(...)` set them
explicitly; without either, scripts can observe the host's time zone and locale.

**Robustness.** Deeply nested source code, runaway recursion and pathological regular expressions end in
catchable `SyntaxError`/`RangeError`s or termination, never in a crashed host thread. A mutation fuzzer over the
Test262 corpus (`io.neonjs.test262.FuzzKt`) checks for engine-internal exceptions, runs that escape the time limit,
and differences between the interpreter and compiled code. Classes used on error paths
are initialized eagerly so that a `StackOverflowError` cannot poison class initialization for the whole JVM.

## Host responsibilities

- **Expose carefully.** Everything reachable from an exposed object is reachable from JS (subject to `HostAccess`):
  return values, fields, nested objects. Prefer `EXPLICIT` access with small facade classes for untrusted code.
- **Callbacks run on your threads.** A Java thread invoking a JS callback (listener proxy, `Java.extend` adapter,
  `List`/`Map` view) enters the context and holds its lock while JS runs, so other threads using that context wait
  for it (bounded by the policy's time limit). Likewise, a `CompletableFuture` made from a JS promise is completed
  in a job on the context's thread with the context lock held, so its dependent stages (`thenAccept`…) run there
  too unless they are `*Async` stages.
- **Termination crossing Java frames.** If JS is terminated while running inside a host callback, the termination
  exception propagates through the host's Java frames. Host code that catches `RuntimeException` broadly may
  swallow it; the engine re-raises the condition at the next check, but avoid catching `Throwable`/
  `RuntimeException` around callbacks into JS.
- **Thread stack size.** The engine bounds JS recursion with `maxCallDepth`, but the JVM stack of the calling
  thread must be large enough to reach that depth (deep recursion otherwise ends in a `RangeError` from a
  `StackOverflowError`, which is safe but earlier than configured). The CLI uses `-Xss16m`; give embedding
  threads a few MB of stack for the default depth.
- **Allocation budget** relies on HotSpot's per-thread allocation counter (`com.sun.management.ThreadMXBean`); on
  other JVMs it is unavailable and only the size caps apply. Memory retained across evaluations is not limited:
  bound the lifetime of contexts that run untrusted code.
- **Module loading.** `FileSystemModuleLoader` confines resolution to its root directory (including through
  symbolic links). Custom loaders must do their own confinement.
- **Futures.** A `CompletionStage` given to JS counts as pending work until it completes: `runEventLoop` and
  `NeonValue.await` wait for it (bounded by the time limit and `interrupt()`). Its completion value goes through
  the normal host → JS conversion, and a failure becomes a `HostError` carrying only the exception's message.
- **Timers** (`webGlobals(true)`). One shared daemon thread schedules all timers; it only queues jobs, and the
  callbacks run on the context's thread under its limits. Timers are capped per context (`maxTimers`), take no
  string callbacks, and are cancelled when the context is closed.
- **Intl data.** `neonjs-intl` bundles ICU4J; all of its packages (`com.ibm.icu.*`) are on the deny list, so
  scripts reach locale data only through `Intl`. `Intl` resolves locales itself and passes ICU only available
  locales (or root), so the host's locale is never used implicitly, and it caps language tags (512 characters) and
  locale lists (1,000 entries).

## Reporting

Please report vulnerabilities privately to the maintainers rather than in public issues.
