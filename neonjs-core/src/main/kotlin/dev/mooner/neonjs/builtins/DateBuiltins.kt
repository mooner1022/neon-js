package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*

/** Date instance: an ordinary object with a `[[DateValue]]` internal slot holding a time value (or NaN). */
class JSDate(proto: JSObject?, @JvmField var timeValue: Double) : JSObject(proto) {
    override val className: String get() = "Date"
}

internal object DateBuiltins {
    private fun thisDate(t: Any?, method: String): JSDate =
        t as? JSDate ?: typeErr("Date.prototype.$method called on incompatible receiver ${Ops.toDisplayString(t)}")

    private fun thisTime(t: Any?, method: String): Double = thisDate(t, method).timeValue

    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Date.prototype%"] = proto
        val ctor = makeCtor(realm, "Date", 7, proto) { _, _, args, nt ->
            if (nt == null) {
                DateTime.toDateString(DateTime.now())
            } else {
                val tv = when (args.size) {
                    0 -> DateTime.now()
                    1 -> {
                        val value = args[0]
                        DateTime.timeClip(
                            if (value is JSDate) {
                                value.timeValue
                            } else {
                                val v = Ops.toPrimitive(value)
                                if (v is CharSequence) DateParser.parse(v.toString()) else Ops.toNumber(v)
                            },
                        )
                    }
                    else -> DateTime.timeClip(DateTime.utc(fromComponents(args)))
                }
                JSDate(Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%Date.prototype%") }, tv)
            }
        }
        realm.intrinsics["%Date%"] = ctor
        realm.global("Date", ctor)

        ctor.method(realm, "now", 0) { _, _, _, _ -> DateTime.now() }
        ctor.method(realm, "parse", 1) { _, _, args, _ -> DateParser.parse(Ops.toString(args.arg(0))) }
        ctor.method(realm, "UTC", 7) { _, _, args, _ -> DateTime.timeClip(fromComponents(args)) }

        installGetters(realm, proto)
        installSetters(realm, proto)
        installConversions(realm, proto)
    }

    /**
     * The date of the Date(year, month[, date[, hours[, minutes[, seconds[, ms]]]]]) and Date.UTC forms, before UTC
     * adjustment and TimeClip. Arguments are converted in order; years 0..99 map to 1900..1999.
     */
    private fun fromComponents(args: Array<Any?>): Double {
        val y = Ops.toNumber(args.arg(0))
        val m = if (args.size > 1) Ops.toNumber(args[1]) else 0.0
        val dt = if (args.size > 2) Ops.toNumber(args[2]) else 1.0
        val h = if (args.size > 3) Ops.toNumber(args[3]) else 0.0
        val min = if (args.size > 4) Ops.toNumber(args[4]) else 0.0
        val s = if (args.size > 5) Ops.toNumber(args[5]) else 0.0
        val milli = if (args.size > 6) Ops.toNumber(args[6]) else 0.0
        return DateTime.makeDate(DateTime.makeDay(DateTime.makeFullYear(y), m, dt), DateTime.makeTime(h, min, s, milli))
    }

    private fun installGetters(realm: Realm, proto: JSObject) {
        fun getter(name: String, local: Boolean, field: (Double) -> Double) {
            proto.method(realm, name, 0) { _, t, _, _ ->
                val tv = thisTime(t, name)
                if (tv.isNaN()) Double.NaN else field(if (local) DateTime.localTime(tv) else tv)
            }
        }
        for (local in listOf(true, false)) {
            val utc = if (local) "" else "UTC"
            getter("get${utc}Date", local, DateTime::dateFromTime)
            getter("get${utc}Day", local, DateTime::weekDay)
            getter("get${utc}FullYear", local, DateTime::yearFromTime)
            getter("get${utc}Hours", local, DateTime::hourFromTime)
            getter("get${utc}Milliseconds", local, DateTime::msFromTime)
            getter("get${utc}Minutes", local, DateTime::minFromTime)
            getter("get${utc}Month", local, DateTime::monthFromTime)
            getter("get${utc}Seconds", local, DateTime::secFromTime)
        }
        getter("getTime", false) { it }
        proto.method(realm, "getTimezoneOffset", 0) { _, t, _, _ ->
            val tv = thisTime(t, "getTimezoneOffset")
            if (tv.isNaN()) Double.NaN else (tv - DateTime.localTime(tv)) / DateTime.MS_PER_MINUTE
        }
        // Annex B
        getter("getYear", true) { DateTime.yearFromTime(it) - 1900 }
    }

    private fun installSetters(realm: Realm, proto: JSObject) {
        /**
         * Setters of hours (first = 0), minutes (1), seconds (2) or milliseconds (3), each taking the fields from
         * [first] on as optional arguments. The date value is read before the arguments are converted.
         */
        fun timeSetter(name: String, local: Boolean, first: Int) {
            val count = 4 - first
            proto.method(realm, name, count) { _, t, args, _ ->
                val d = thisDate(t, name)
                val tv = d.timeValue
                val present = args.size.coerceIn(1, count)
                val values = DoubleArray(present) { Ops.toNumber(args.arg(it)) }
                if (tv.isNaN()) {
                    Double.NaN
                } else {
                    val lt = if (local) DateTime.localTime(tv) else tv
                    fun field(i: Int, current: (Double) -> Double) = if (i >= first && i - first < present) values[i - first] else current(lt)
                    val time = DateTime.makeTime(
                        field(0, DateTime::hourFromTime),
                        field(1, DateTime::minFromTime),
                        field(2, DateTime::secFromTime),
                        field(3, DateTime::msFromTime),
                    )
                    val date = DateTime.makeDate(DateTime.day(lt), time)
                    val u = DateTime.timeClip(if (local) DateTime.utc(date) else date)
                    d.timeValue = u
                    u
                }
            }
        }

        /**
         * Setters of the full year (first = 0), month (1) or date (2), each taking the fields from [first] on as
         * optional arguments. Only the year setters start from +0 when the date value is NaN.
         */
        fun dateSetter(name: String, local: Boolean, first: Int) {
            val count = 3 - first
            proto.method(realm, name, count) { _, t, args, _ ->
                val d = thisDate(t, name)
                val tv = d.timeValue
                val present = args.size.coerceIn(1, count)
                val values = DoubleArray(present) { Ops.toNumber(args.arg(it)) }
                if (tv.isNaN() && first != 0) {
                    Double.NaN
                } else {
                    val lt = if (tv.isNaN()) 0.0 else if (local) DateTime.localTime(tv) else tv
                    fun field(i: Int, current: (Double) -> Double) = if (i >= first && i - first < present) values[i - first] else current(lt)
                    val day = DateTime.makeDay(field(0, DateTime::yearFromTime), field(1, DateTime::monthFromTime), field(2, DateTime::dateFromTime))
                    val date = DateTime.makeDate(day, DateTime.timeWithinDay(lt))
                    val u = DateTime.timeClip(if (local) DateTime.utc(date) else date)
                    d.timeValue = u
                    u
                }
            }
        }

        for (local in listOf(true, false)) {
            val utc = if (local) "" else "UTC"
            dateSetter("set${utc}Date", local, 2)
            dateSetter("set${utc}FullYear", local, 0)
            timeSetter("set${utc}Hours", local, 0)
            timeSetter("set${utc}Milliseconds", local, 3)
            timeSetter("set${utc}Minutes", local, 1)
            dateSetter("set${utc}Month", local, 1)
            timeSetter("set${utc}Seconds", local, 2)
        }
        proto.method(realm, "setTime", 1) { _, t, args, _ ->
            val d = thisDate(t, "setTime")
            val u = DateTime.timeClip(Ops.toNumber(args.arg(0)))
            d.timeValue = u
            u
        }
        // Annex B
        proto.method(realm, "setYear", 1) { _, t, args, _ ->
            val d = thisDate(t, "setYear")
            val tv = d.timeValue
            val y = Ops.toNumber(args.arg(0))
            val lt = if (tv.isNaN()) 0.0 else DateTime.localTime(tv)
            val day = DateTime.makeDay(DateTime.makeFullYear(y), DateTime.monthFromTime(lt), DateTime.dateFromTime(lt))
            val u = DateTime.timeClip(DateTime.utc(DateTime.makeDate(day, DateTime.timeWithinDay(lt))))
            d.timeValue = u
            u
        }
    }

    private fun installConversions(realm: Realm, proto: JSObject) {
        fun format(name: String, impl: (Double) -> String): NativeFunction =
            proto.method(realm, name, 0) { _, t, _, _ -> impl(thisTime(t, name)) }

        format("toDateString") { tv -> if (tv.isNaN()) DateTime.INVALID_DATE else DateTime.dateString(DateTime.localTime(tv)) }
        format("toISOString") { tv ->
            if (!tv.isFinite()) rangeErr("Invalid time value")
            DateTime.toISOString(tv)
        }
        format("toLocaleDateString", DateTime::toLocaleDateString)
        format("toLocaleString", DateTime::toLocaleString)
        format("toLocaleTimeString", DateTime::toLocaleTimeString)
        format("toString", DateTime::toDateString)
        format("toTimeString") { tv ->
            if (tv.isNaN()) DateTime.INVALID_DATE else DateTime.timeString(DateTime.localTime(tv)) + DateTime.timeZoneString(tv)
        }
        val toUTCString = format("toUTCString", DateTime::toUTCString)
        // Annex B: toGMTString is the same function object as toUTCString.
        proto.value("toGMTString", toUTCString)

        proto.method(realm, "toJSON", 1) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val tv = Ops.toPrimitive(o, Ops.HINT_NUMBER)
            if (tv is Double && !tv.isFinite()) Null else Ops.invoke(o, "toISOString", EMPTY_ARGS)
        }
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisTime(t, "valueOf") }
        proto.method(realm, "toTemporalInstant", 0) { _, t, _, _ -> dev.mooner.neonjs.builtins.temporal.TemporalBuiltins.dateToTemporalInstant(t) }
        proto.method(realm, JSSymbol.toPrimitive, 1, Attr.CONFIGURABLE) { _, t, args, _ ->
            if (t !is JSObject) typeErr("Date.prototype[Symbol.toPrimitive] called on non-object")
            val hint = args.arg(0)
            val tryFirst = when (if (hint is CharSequence) hint.toString() else null) {
                "string", "default" -> Ops.HINT_STRING
                "number" -> Ops.HINT_NUMBER
                else -> typeErr("Invalid hint: ${Ops.toDisplayString(hint)}")
            }
            Ops.ordinaryToPrimitive(t, tryFirst)
        }
    }
}
