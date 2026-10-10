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
it triggers; with `limitsPerTask`, the time, instruction and allocation limits per event-loop task instead, waits
uncounted):

| Limit | Policy | Enforcement points |
|---|---|---|
| wall-clock time | `maxExecutionTime` | loop back-edges, calls, job dispatch, RegExp matcher, long built-in loops (including generic array methods over array-likes with lengths up to 2^53 - 1), `Atomics.wait`, event-loop waits |
| instruction budget | `maxStatements` | same as above |
| call depth | `maxCallDepth` | every JS call (proper tail calls do not grow the depth but remain interruptible) |
| string length | `maxStringLength` | concatenation, `repeat`, `pad*`, `join`, JSON, template building… |
| allocated bytes | `maxAllocatedBytes` | sampled with the time checks; large allocations (buffers, Java arrays, long strings) are charged before they happen |
| host interrupt | `NeonContext.interrupt()` | same as time; also ends an event loop waiting in the context |

Termination exceptions are not JS exceptions: `catch`, `finally` and promise reactions cannot intercept them.
Exceeded limits are sticky for the rest of the evaluation, so the condition is raised again at the next check even
if host code swallowed the exception. Every entry into a context from the host (`eval`, a call through a
`NeonValue`, a callback proxy invoked by a host thread) starts a fresh limit window.

**Host access.** Nothing of the host is visible unless the host puts it there (`ctx[...]`, `exposeClass`,
`defineModule`, `setFunction`) or enables the `Java` global. `HostAccess` decides what is visible on those objects:

- `NONE`: host objects are opaque: no members, not callable, not array-like.
- `EXPLICIT`: only members annotated with `@HostExport` (or members of annotated classes).
- `ALL`: all public members.

At every level a deny list blocks `java.lang.Class`, class loaders, `System`, `Runtime`, processes, threads,
`SecurityManager`, modules, `StackWalker`, `Unsafe`, `MethodHandles`, object serialization streams, reflection
and `java.lang.invoke` packages, `sun.*`/`com.sun.*`/`jdk.internal.*`, `java.security.*`, management and
instrumentation APIs, and the engine itself: all of its packages, and the API classes that control engines, contexts,
policies and values or read files (`NeonEngine`, `NeonContext`, `NeonValue`, `NeonScript`, `HostAccess`,
`SandboxPolicy`, the module loaders), so engine objects a script gets hold of are opaque (exceptions, annotations and
the interfaces host code implements stay visible). `getClass` (while `java.lang.Class` is denied),
`wait`, `notify*` and `finalize` are never exposed. Arrays are judged by their element type, host objects by their
own class (an instance of a denied class stays opaque, and cannot be called, even when it is typed as an allowed
interface). An object of a non-public class shows the methods of its public supertypes, as Java code outside its
package sees them, and only those of supertypes that are not denied. Two denied bases are not held against the
instances of their subclasses unless the embedder denies them itself: `java.lang.reflect.Proxy`, whose instances'
public methods are those of their interfaces, and `kotlin.jvm.internal`, the bases of Kotlin lambdas and function
references, which can then be called while their own members (reflection) stay hidden. Only lambdas, anonymous
classes and objects whose only role is a functional interface are callable, through that interface. `Java.type`
additionally requires an explicit `allowLookup` predicate. `Java.extend` requires `allowImplementations` and only
extends accessible, non-final, non-denied classes; adapters are defined in their own class loader, and under
`EXPLICIT` their methods are visible only where they override an exported method. Host-defined modules
(`defineModule`) expose exactly the values given.

The embedder can lift entries of the built-in deny list (`HostAccess.Builder.allowClass`, `allowPackage`) or drop it
(`defaultDenyList(false)`); classes it denies itself (`denyClass`, `denyPackage`) stay denied. A lifted class is then
as exposed as any other: lifting `java.lang.System` hands scripts `exit`, `setProperty`, `getenv` and `load` unless a
member filter removes them, and lifting `java.lang.Class` also exposes `getClass()`. Lifting the engine's internal
packages breaks the sandbox's assumptions.

**Realms created by scripts.** A `ShadowRealm` gets fresh intrinsics only: none of the host's globals, no `Java`
object, no console, and it cannot import host-defined modules (`defineModule`; modules from the context's
loader are available). Only primitives and callables cross its boundary (callables as
wrapped functions), so host objects cannot be smuggled in or out. It shares the agent of its creator, so every
limit above applies to code running inside it, and `allowEval(false)` also disables `ShadowRealm.prototype.evaluate`.

**Determinism and host fingerprinting.** `SandboxPolicy.builder().deterministic(seed, fixedTime)` makes
`Math.random`, `Date.now`, `new Date()` reproducible, which also removes timing side channels based on the clock,
and pins the time zone to UTC and the default locale to `en-US`. `timeZone(...)` and `defaultLocale(...)` set them
explicitly; without either, scripts can observe the host's time zone and locale.

**Robustness.** Deeply nested source code, runaway recursion and pathological regular expressions end in catchable
`SyntaxError`/`RangeError`s or termination, never in a crashed host thread. A mutation fuzzer over the Test262 corpus
(`dev.mooner.neonjs.test262.FuzzKt`) checks for engine-internal exceptions, runs that escape the time limit, and
differences between the interpreter and compiled code. Classes used on error paths are initialized eagerly so that a
`StackOverflowError` cannot poison class initialization for the whole JVM.

## Host responsibilities

- **Expose carefully.** Everything reachable from an exposed object is reachable from JS (subject to `HostAccess`):
  return values, fields, nested objects. Prefer `EXPLICIT` access with small facade classes for untrusted code.
- **Callbacks run on your threads.** A Java thread invoking a JS callback (listener proxy, `Java.extend` adapter,
  `List`/`Map` view) enters the context and holds its lock while JS runs, so other threads using that context wait
  for it — at most the policy's time limit, after which they get a `NeonTimeoutException`. That also ends the case of
  JS blocking on host work that needs the same context on another thread (a host method waiting on a future that
  evaluates in the context), which would otherwise wait forever; without a time limit the wait is unbounded. Once
  the context is closed, entering it throws `IllegalStateException`, from callbacks and proxies too: unregister
  listeners that hold JS functions before closing. Likewise, a `CompletableFuture` made from a JS promise is completed
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
  callbacks run on the context's thread under its limits. Timers are capped per context (`maxTimers`, which
  `AbortSignal.timeout` counts against too), take no string callbacks, and are cancelled when the context is closed.
  An exception thrown by an event listener does not escape `dispatchEvent`: it is rethrown by a microtask, so it
  reaches the context's uncaught error handler (or ends the call running jobs without one). A `Blob`'s size is
  charged to the allocation budget before its bytes are allocated (a blob of many copies of another is cheap to ask
  for), and the bytes of a blob are shared with its slices and clones, never written.
- **Intl data.** `neonjs-intl` bundles ICU4J; all of its packages (`com.ibm.icu.*`) are on the deny list, so
  scripts reach locale data only through `Intl`. `Intl` resolves locales itself and passes ICU only available
  locales (or root), so the host's locale is never used implicitly, and it caps language tags (512 characters) and
  locale lists (1,000 entries).

## Reporting

Please report vulnerabilities privately to the maintainers rather than in public issues.
