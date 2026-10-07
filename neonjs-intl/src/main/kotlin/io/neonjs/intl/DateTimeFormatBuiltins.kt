package io.neonjs.intl

import io.neonjs.builtins.JSDate
import io.neonjs.runtime.*

/** Intl.DateTimeFormat (ECMA-402 §11) and Date.prototype.toLocale{,Date,Time}String (§21.4). */
internal object DateTimeFormatBuiltins {
    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.DateTimeFormat.prototype%"] = proto
        val ctor = makeCtor(realm, "DateTimeFormat", 0, proto) { f, t, args, nt ->
            val dtf = DtfCreate.create(f.realm, nt ?: f, args.arg(0), args.arg(1), "any", "date")
            // ChainDateTimeFormat (normative optional legacy constructor behaviour)
            if (nt == null && t is JSObject && Ops.ordinaryHasInstance(f, t)) {
                t.definePropertyOrThrow(IntlState.of(f.realm).fallbackSymbol, PropertyDescriptor.data(dtf, Attr.NONE))
                t
            } else dtf
        }
        realm.intrinsics["%Intl.DateTimeFormat%"] = ctor
        intl.defineOwn("DateTimeFormat", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DateTimeFormat", Attr.CONFIGURABLE)

        proto.getter(realm, "format") { f, t, _, _ ->
            val dtf = unwrap(f.realm, t, "format")
            dtf.boundFormat ?: run {
                val bf = NativeFunction(f.realm, "", 1, { bf, _, args, _ ->
                    val date = args.arg(0)
                    val x: Any = if (date === Undefined) io.neonjs.builtins.DateTime.now() else DtfFormatting.toFormattable(date)
                    DtfFormatting.format(bf.realm, dtf, x)
                })
                dtf.boundFormat = bf
                bf
            }
        }
        proto.method(realm, "formatToParts", 1) { f, t, args, _ ->
            val dtf = thisDtf(t, "formatToParts")
            val date = args.arg(0)
            val x: Any = if (date === Undefined) io.neonjs.builtins.DateTime.now() else DtfFormatting.toFormattable(date)
            val v = DtfFormatting.handle(dtf, x)
            DtfFormatting.partsArray(f.realm, DtfFormatting.partsOf(dtf, v), false)
        }
        proto.method(realm, "formatRange", 2) { f, t, args, _ ->
            val dtf = thisDtf(t, "formatRange")
            val (x, y) = rangeArgs(args)
            val parts = DtfFormatting.rangeParts(dtf, x, y)
            val sb = StringBuilder()
            for (p in parts) sb.append(p.value)
            f.realm.checkedString(sb)
        }
        proto.method(realm, "formatRangeToParts", 2) { f, t, args, _ ->
            val dtf = thisDtf(t, "formatRangeToParts")
            val (x, y) = rangeArgs(args)
            DtfFormatting.partsArray(f.realm, DtfFormatting.rangeParts(dtf, x, y), true)
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ -> resolvedOptions(f.realm, unwrap(f.realm, t, "resolvedOptions")) }

        installDateMethods(realm)
    }

    private fun rangeArgs(args: Array<Any?>): Pair<Any, Any> {
        val a = args.arg(0)
        val b = args.arg(1)
        if (a === Undefined || b === Undefined) typeErr("startDate and endDate are required")
        val x = DtfFormatting.toFormattable(a)
        val y = DtfFormatting.toFormattable(b)
        return x to y
    }

    private fun thisDtf(t: Any?, method: String): JSIntlDateTimeFormat =
        t as? JSIntlDateTimeFormat ?: typeErr("Method Intl.DateTimeFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    /** UnwrapDateTimeFormat followed by the `[[InitializedDateTimeFormat]]` check. */
    private fun unwrap(realm: Realm, t: Any?, method: String): JSIntlDateTimeFormat {
        if (t !is JSObject) typeErr("Method Intl.DateTimeFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")
        if (t is JSIntlDateTimeFormat) return t
        val ctor = realm.intrinsic("%Intl.DateTimeFormat%")
        if (Ops.ordinaryHasInstance(ctor, t)) {
            val v = t.get(IntlState.of(realm).fallbackSymbol, t)
            if (v is JSIntlDateTimeFormat) return v
        }
        typeErr("Method Intl.DateTimeFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")
    }

    private fun resolvedOptions(realm: Realm, dtf: JSIntlDateTimeFormat): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("locale", dtf.locale)
        o.createDataPropertyOrThrow("calendar", dtf.calendar)
        o.createDataPropertyOrThrow("numberingSystem", dtf.numberingSystem)
        o.createDataPropertyOrThrow("timeZone", dtf.timeZone)
        val hc = dtf.hourCycle
        if (hc != null) {
            o.createDataPropertyOrThrow("hourCycle", hc)
            o.createDataPropertyOrThrow("hour12", hc == "h11" || hc == "h12")
        }
        if (dtf.dateStyle == null && dtf.timeStyle == null) {
            for ((k, v) in dtf.format.fields) {
                o.createDataPropertyOrThrow(k, if (k == "fractionalSecondDigits") v.toDouble() else v)
            }
        }
        dtf.dateStyle?.let { o.createDataPropertyOrThrow("dateStyle", it) }
        dtf.timeStyle?.let { o.createDataPropertyOrThrow("timeStyle", it) }
        return o
    }

    // ------------------------------------------------------------------ Date.prototype

    private fun installDateMethods(realm: Realm) {
        val dateProto = realm.intrinsics["%Date.prototype%"] ?: return
        dateProto.method(realm, "toLocaleString", 0) { f, t, args, _ -> dateToLocale(f.realm, t, args, "any", "all", "toLocaleString") }
        dateProto.method(realm, "toLocaleDateString", 0) { f, t, args, _ -> dateToLocale(f.realm, t, args, "date", "date", "toLocaleDateString") }
        dateProto.method(realm, "toLocaleTimeString", 0) { f, t, args, _ -> dateToLocale(f.realm, t, args, "time", "time", "toLocaleTimeString") }
    }

    private fun dateToLocale(realm: Realm, t: Any?, args: Array<Any?>, required: String, defaults: String, method: String): String {
        val d = t as? JSDate ?: typeErr("Date.prototype.$method called on incompatible receiver ${Ops.toDisplayString(t)}")
        val x = d.timeValue
        if (x.isNaN()) return "Invalid Date"
        val locales = args.arg(0)
        val options = args.arg(1)
        val ctor = realm.intrinsic("%Intl.DateTimeFormat%")
        val dtf = if (locales === Undefined && options === Undefined) {
            IntlState.of(realm).cached("dtf:$required:$defaults") { DtfCreate.create(realm, ctor, Undefined, Undefined, required, defaults) }
        } else DtfCreate.create(realm, ctor, locales, options, required, defaults)
        return DtfFormatting.format(realm, dtf, x)
    }
}
