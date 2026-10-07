package io.neonjs.intl

import io.neonjs.builtins.temporal.JSTemporalDuration
import io.neonjs.builtins.temporal.JSTemporalInstant
import io.neonjs.builtins.temporal.JSTemporalPlainDate
import io.neonjs.builtins.temporal.JSTemporalPlainDateTime
import io.neonjs.builtins.temporal.JSTemporalPlainMonthDay
import io.neonjs.builtins.temporal.JSTemporalPlainTime
import io.neonjs.builtins.temporal.JSTemporalPlainYearMonth
import io.neonjs.builtins.temporal.JSTemporalZonedDateTime
import io.neonjs.runtime.*

/**
 * The ECMA-402 `toLocaleString` of the Temporal types, replacing core's locale-independent versions: the date-time
 * types format through a DateTimeFormat created with the type's required / default fields, ZonedDateTime in its own
 * time zone, and Duration through DurationFormat.
 */
internal object TemporalLocale {
    fun install(realm: Realm) {
        fun viaDateTimeFormat(protoName: String, type: String, required: String, defaults: String, isType: (Any?) -> Boolean) {
            val proto = realm.intrinsics[protoName] ?: return
            proto.method(realm, "toLocaleString", 0) { f, t, a, _ ->
                if (!isType(t)) typeErr("Temporal.$type.prototype.toLocaleString called on incompatible receiver ${Ops.describe(t)}")
                val r = f.realm
                val locales = a.arg(0)
                val options = a.arg(1)
                val ctor = r.intrinsic("%Intl.DateTimeFormat%")
                val dtf = if (locales === Undefined && options === Undefined) {
                    IntlState.of(r).cached("dtf:$required:$defaults") { DtfCreate.create(r, ctor, Undefined, Undefined, required, defaults) }
                } else DtfCreate.create(r, ctor, locales, options, required, defaults)
                DtfFormatting.format(r, dtf, t!!)
            }
        }
        viaDateTimeFormat("%Temporal.PlainDate.prototype%", "PlainDate", "date", "date") { it is JSTemporalPlainDate }
        viaDateTimeFormat("%Temporal.PlainDateTime.prototype%", "PlainDateTime", "any", "all") { it is JSTemporalPlainDateTime }
        viaDateTimeFormat("%Temporal.PlainTime.prototype%", "PlainTime", "time", "time") { it is JSTemporalPlainTime }
        viaDateTimeFormat("%Temporal.PlainYearMonth.prototype%", "PlainYearMonth", "date", "date") { it is JSTemporalPlainYearMonth }
        viaDateTimeFormat("%Temporal.PlainMonthDay.prototype%", "PlainMonthDay", "date", "date") { it is JSTemporalPlainMonthDay }
        viaDateTimeFormat("%Temporal.Instant.prototype%", "Instant", "any", "all") { it is JSTemporalInstant }

        realm.intrinsics["%Temporal.ZonedDateTime.prototype%"]?.method(realm, "toLocaleString", 0) { f, t, a, _ ->
            val z = t as? JSTemporalZonedDateTime
                ?: typeErr("Temporal.ZonedDateTime.prototype.toLocaleString called on incompatible receiver ${Ops.describe(t)}")
            val r = f.realm
            // the time zone is the ZonedDateTime's own; a timeZone option is a TypeError
            val dtf = DtfCreate.create(r, r.intrinsic("%Intl.DateTimeFormat%"), a.arg(0), a.arg(1), "any", "all", z.timeZone.id)
            if (z.calendar != "iso8601" && z.calendar != dtf.calendar) {
                rangeErr("Calendar ${z.calendar} does not match the DateTimeFormat calendar ${dtf.calendar}")
            }
            DtfFormatting.format(r, dtf, JSTemporalInstant(null, z.epochNs))
        }

        realm.intrinsics["%Temporal.Duration.prototype%"]?.method(realm, "toLocaleString", 0) { f, t, a, _ ->
            if (t !is JSTemporalDuration) typeErr("Temporal.Duration.prototype.toLocaleString called on incompatible receiver ${Ops.describe(t)}")
            DurationFormatBuiltins.format(f.realm, a.arg(0), a.arg(1), t)
        }
    }
}
