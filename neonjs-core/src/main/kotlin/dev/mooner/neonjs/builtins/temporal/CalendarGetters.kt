package dev.mooner.neonjs.builtins.temporal

import dev.mooner.neonjs.builtins.getter
import dev.mooner.neonjs.runtime.*

/** Installs the calendar-derived accessor properties shared by the date-carrying Temporal types. */
internal object CalendarGetters {
    fun interface DateOf {
        fun get(thisValue: Any?, name: String): IsoDate
    }

    fun interface CalOf {
        fun get(thisValue: Any?, name: String): String
    }

    const val KIND_DATE = 0
    const val KIND_YEAR_MONTH = 1
    const val KIND_MONTH_DAY = 2

    fun install(realm: Realm, proto: JSObject, kind: Int, cal: CalOf, date: DateOf) {
        /** CalendarISOToDate of the receiver (brand check first, as the getters' RequireInternalSlot). */
        fun cd(t: Any?, name: String): CalDate {
            val d = date.get(t, name)
            return TCal.isoToDate(cal.get(t, name), d)
        }
        proto.getter(realm, "calendarId") { _, t, _, _ -> cal.get(t, "calendarId") }
        if (kind != KIND_MONTH_DAY) {
            proto.getter(realm, "era") { _, t, _, _ -> cd(t, "era").era ?: Undefined }
            proto.getter(realm, "eraYear") { _, t, _, _ ->
                val c = cd(t, "eraYear")
                if (c.era == null) Undefined else c.eraYear.toDouble()
            }
            proto.getter(realm, "year") { _, t, _, _ -> cd(t, "year").year.toDouble() }
            proto.getter(realm, "month") { _, t, _, _ -> cd(t, "month").month.toDouble() }
        }
        proto.getter(realm, "monthCode") { _, t, _, _ -> MonthCodes.toString(cd(t, "monthCode").monthCode) }
        if (kind != KIND_YEAR_MONTH) {
            proto.getter(realm, "day") { _, t, _, _ -> cd(t, "day").day.toDouble() }
        }
        if (kind == KIND_DATE) {
            proto.getter(realm, "dayOfWeek") { _, t, _, _ -> TM.dayOfWeek(date.get(t, "dayOfWeek")).toDouble() }
            proto.getter(realm, "dayOfYear") { _, t, _, _ -> cd(t, "dayOfYear").dayOfYear.toDouble() }
            // only the ISO 8601 calendar has a well-defined week numbering
            proto.getter(realm, "weekOfYear") { _, t, _, _ ->
                val d = date.get(t, "weekOfYear")
                if (cal.get(t, "weekOfYear") == TCal.ISO) TM.weekOfYear(d)[0].toDouble() else Undefined
            }
            proto.getter(realm, "yearOfWeek") { _, t, _, _ ->
                val d = date.get(t, "yearOfWeek")
                if (cal.get(t, "yearOfWeek") == TCal.ISO) TM.weekOfYear(d)[1].toDouble() else Undefined
            }
            proto.getter(realm, "daysInWeek") { _, t, _, _ -> date.get(t, "daysInWeek"); 7.0 }
        }
        if (kind != KIND_MONTH_DAY) {
            proto.getter(realm, "daysInMonth") { _, t, _, _ -> cd(t, "daysInMonth").daysInMonth.toDouble() }
            proto.getter(realm, "daysInYear") { _, t, _, _ -> cd(t, "daysInYear").daysInYear.toDouble() }
            proto.getter(realm, "monthsInYear") { _, t, _, _ -> cd(t, "monthsInYear").monthsInYear.toDouble() }
            proto.getter(realm, "inLeapYear") { _, t, _, _ -> cd(t, "inLeapYear").inLeapYear }
        }
    }
}
