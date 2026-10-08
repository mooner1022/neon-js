package dev.mooner.neonjs.runtime

/**
 * `this as T` as a plain checkcast, for runtime paths that JIT-generated code calls (and the JVM may inline into it).
 * Kotlin compiles `x as T` from a nullable `x` into `Intrinsics.checkNotNull(x, "null cannot be cast to non-null type
 * T")`, which loads that string on every cast. On ART, inlined into a generated class, the load became a runtime call
 * that walks the stack each time and made calls in compiled code up to 2x slower. Use it only where the value cannot
 * be null.
 */
@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
internal inline fun <T> Any?.cast(): T = this as T
