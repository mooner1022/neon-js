package dev.mooner.neonjs.builtins.temporal

import dev.mooner.neonjs.builtins.JSDate
import dev.mooner.neonjs.builtins.global
import dev.mooner.neonjs.builtins.method
import dev.mooner.neonjs.runtime.*

/** Installs the Temporal namespace object, its constructors and Temporal.Now. */
object TemporalBuiltins {
    @JvmStatic
    fun install(realm: Realm) {
        val temporal = JSObject(realm.objectPrototype)
        realm.intrinsics["%Temporal%"] = temporal
        temporal.defineOwn(JSSymbol.toStringTag, "Temporal", Attr.CONFIGURABLE)
        InstantBuiltins.install(realm, temporal)
        PlainDateTimeBuiltins.install(realm, temporal)
        PlainDateBuiltins.install(realm, temporal)
        PlainTimeBuiltins.install(realm, temporal)
        PlainYearMonthBuiltins.install(realm, temporal)
        PlainMonthDayBuiltins.install(realm, temporal)
        DurationBuiltins.install(realm, temporal)
        ZonedDateTimeBuiltins.install(realm, temporal)
        installNow(realm, temporal)
        realm.global("Temporal", temporal)
    }

    private fun systemDateTime(realm: Realm, tzLike: Any?): IsoDateTime {
        val tz = if (tzLike === Undefined) TZ.system(realm) else TZ.toTimeZone(tzLike)
        return TZ.isoDateTimeFor(tz, InstantBuiltins.systemNs(realm))
    }

    private fun installNow(realm: Realm, temporal: JSObject) {
        val now = JSObject(realm.objectPrototype)
        now.defineOwn(JSSymbol.toStringTag, "Temporal.Now", Attr.CONFIGURABLE)
        now.method(realm, "timeZoneId", 0) { f, _, _, _ -> TZ.system(f.realm).id }
        now.method(realm, "instant", 0) { f, _, _, _ -> TCreate.instant(InstantBuiltins.systemNs(f.realm)) }
        now.method(realm, "plainDateTimeISO", 0) { f, _, args, _ ->
            TCreate.plainDateTime(systemDateTime(f.realm, args.arg(0)), TCal.ISO)
        }
        now.method(realm, "zonedDateTimeISO", 0) { f, _, args, _ ->
            val v = args.arg(0)
            val tz = if (v === Undefined) TZ.system(f.realm) else TZ.toTimeZone(v)
            TCreate.zoned(InstantBuiltins.systemNs(f.realm), tz, TCal.ISO)
        }
        now.method(realm, "plainDateISO", 0) { f, _, args, _ ->
            TCreate.plainDate(systemDateTime(f.realm, args.arg(0)).date, TCal.ISO)
        }
        now.method(realm, "plainTimeISO", 0) { f, _, args, _ ->
            TCreate.plainTime(systemDateTime(f.realm, args.arg(0)).time)
        }
        temporal.defineOwn("Now", now, Attr.WC)
    }

    /** Date.prototype.toTemporalInstant */
    @JvmStatic
    fun dateToTemporalInstant(thisValue: Any?): Any {
        val d = thisValue as? JSDate ?: throw JSException.typeError("Date.prototype.toTemporalInstant called on incompatible receiver")
        return TCreate.instant(InstantBuiltins.msToNs(d.timeValue))
    }
}
