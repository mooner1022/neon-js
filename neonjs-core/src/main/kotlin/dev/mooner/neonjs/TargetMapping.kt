package dev.mooner.neonjs

import java.util.function.Function
import java.util.function.Predicate

/**
 * Where a [HostAccess.Builder.targetTypeMapping] ranks among NeonJS's own conversions of a JS value to a host type, as
 * GraalJS's `HostAccess.TargetMappingPrecedence` does.
 */
enum class TargetMappingPrecedence {
    /** Before every conversion of NeonJS's own: chosen even over an exact match (`over(Integer)` over `over(String)` for `"42"`). */
    HIGHEST,

    /**
     * Before NeonJS's own conversions (the default), but an exact match still wins an overload: for `"42"`,
     * `over(String)` is chosen over a mapping to `over(Integer)`. GraalJS reports such a tie as ambiguous; NeonJS never
     * does.
     */
    HIGH,

    /** After the conversions that lose nothing: it applies where only a lossy one would (or none). */
    LOW,

    /** Only where no conversion of NeonJS's own applies. */
    LOWEST,
}

/**
 * A conversion of JS values to host type [targetType] (a parameter, a field, an element of a `List` or `Map` view,
 * `NeonValue.as`): a value that converts to [sourceType] as it would for a parameter of that type, and that [accepts]
 * (null: any), becomes what [converter] makes of it. A `ClassCastException` from [converter] is a TypeError in JS; any
 * other exception a host error.
 */
class TargetMapping internal constructor(
    val sourceType: Class<*>,
    val targetType: Class<*>,
    val accepts: Predicate<Any?>?,
    val converter: Function<Any?, Any?>,
    val precedence: TargetMappingPrecedence,
)
