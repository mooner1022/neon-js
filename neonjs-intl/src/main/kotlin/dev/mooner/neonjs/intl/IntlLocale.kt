package dev.mooner.neonjs.intl

import dev.mooner.neonjs.runtime.*

/** Intl.Locale instance (`[[InitializedLocale]]`). All slots are canonical strings; null means undefined. */
class JSIntlLocale internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField var calendar: String? = null
    @JvmField var collation: String? = null
    @JvmField var firstDayOfWeek: String? = null
    @JvmField var hourCycle: String? = null
    @JvmField var caseFirst: String? = null
    @JvmField var numeric: Boolean = false
    @JvmField var numberingSystem: String? = null
}

/** Intl.Locale (ECMA-402 §14). */
internal object LocaleBuiltins {
    private val KEYS = listOf("ca", "co", "fw", "hc", "kf", "kn", "nu")
    private val HOUR_CYCLES = arrayOf("h11", "h12", "h23", "h24")
    private val CASE_FIRST = arrayOf("upper", "lower", "false")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.Locale.prototype%"] = proto
        val ctor = makeCtor(realm, "Locale", 1, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.Locale requires 'new'")
            construct(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.Locale%"] = ctor
        intl.defineOwn("Locale", ctor, Attr.WC)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Locale", Attr.CONFIGURABLE)

        proto.method(realm, "maximize", 0) { f, t, _, _ -> derived(f.realm, thisLocale(t, "maximize"), true) }
        proto.method(realm, "minimize", 0) { f, t, _, _ -> derived(f.realm, thisLocale(t, "minimize"), false) }
        proto.method(realm, "toString", 0) { _, t, _, _ -> thisLocale(t, "toString").locale }
        proto.getter(realm, "baseName") { _, t, _, _ -> LanguageTag.baseName(thisLocale(t, "baseName").locale) }
        proto.getter(realm, "calendar") { _, t, _, _ -> thisLocale(t, "calendar").calendar ?: Undefined }
        proto.getter(realm, "caseFirst") { _, t, _, _ -> thisLocale(t, "caseFirst").caseFirst ?: Undefined }
        proto.getter(realm, "collation") { _, t, _, _ -> thisLocale(t, "collation").collation ?: Undefined }
        proto.getter(realm, "firstDayOfWeek") { _, t, _, _ -> thisLocale(t, "firstDayOfWeek").firstDayOfWeek ?: Undefined }
        proto.getter(realm, "hourCycle") { _, t, _, _ -> thisLocale(t, "hourCycle").hourCycle ?: Undefined }
        proto.getter(realm, "numeric") { _, t, _, _ -> thisLocale(t, "numeric").numeric }
        proto.getter(realm, "numberingSystem") { _, t, _, _ -> thisLocale(t, "numberingSystem").numberingSystem ?: Undefined }
        proto.getter(realm, "language") { _, t, _, _ -> LanguageTag.split(thisLocale(t, "language").locale).language }
        proto.getter(realm, "script") { _, t, _, _ ->
            LanguageTag.split(thisLocale(t, "script").locale).script?.let { it[0].uppercaseChar() + it.substring(1) } ?: Undefined
        }
        proto.getter(realm, "region") { _, t, _, _ -> LanguageTag.split(thisLocale(t, "region").locale).region?.uppercase() ?: Undefined }
        proto.getter(realm, "variants") { _, t, _, _ ->
            val v = LanguageTag.split(thisLocale(t, "variants").locale).variants
            if (v.isEmpty()) Undefined else v.joinToString("-")
        }
        proto.method(realm, "getCalendars", 0) { f, t, _, _ -> jsArray(f.realm, LocaleInfo.calendars(thisLocale(t, "getCalendars"))) }
        proto.method(realm, "getCollations", 0) { f, t, _, _ -> jsArray(f.realm, LocaleInfo.collations(thisLocale(t, "getCollations"))) }
        proto.method(realm, "getHourCycles", 0) { f, t, _, _ -> jsArray(f.realm, LocaleInfo.hourCycles(thisLocale(t, "getHourCycles"))) }
        proto.method(realm, "getNumberingSystems", 0) { f, t, _, _ ->
            jsArray(f.realm, LocaleInfo.numberingSystems(thisLocale(t, "getNumberingSystems")))
        }
        proto.method(realm, "getTimeZones", 0) { f, t, _, _ ->
            val zones = LocaleInfo.timeZones(thisLocale(t, "getTimeZones"))
            if (zones == null) Undefined else jsArray(f.realm, zones)
        }
        proto.method(realm, "getTextInfo", 0) { f, t, _, _ ->
            val o = plainObject(f.realm)
            o.createDataPropertyOrThrow("direction", LocaleInfo.direction(thisLocale(t, "getTextInfo")))
            o
        }
        proto.method(realm, "getWeekInfo", 0) { f, t, _, _ -> LocaleInfo.weekInfo(f.realm, thisLocale(t, "getWeekInfo")) }
    }

    private fun thisLocale(t: Any?, method: String): JSIntlLocale =
        t as? JSIntlLocale ?: typeErr("Method Intl.Locale.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun construct(realm: Realm, nt: JSObject, tagArg: Any?, optionsArg: Any?): JSIntlLocale {
        val locale = JSIntlLocale(protoFromCtor(nt, "%Intl.Locale.prototype%", realm))
        if (tagArg !is CharSequence && tagArg !is JSObject) typeErr("First argument to Intl.Locale constructor can't be empty or missing")
        val tag0 = if (tagArg is JSIntlLocale) tagArg.locale else Ops.toString(tagArg)
        val options = Opt.coerceToObject(optionsArg)
        if (!LanguageTag.isStructurallyValid(tag0)) rangeErr("Incorrect locale information provided")
        var tag = LanguageTag.canonicalize(tag0)
        tag = updateLanguageId(tag, options)
        val opt = HashMap<String, String?>()
        opt["ca"] = typeOption(options, "calendar")
        opt["co"] = typeOption(options, "collation")
        var fw = Opt.string(options, "firstDayOfWeek", null, null) as String?
        if (fw != null) {
            fw = weekdayToString(fw)
            if (!Opt.isUnicodeType(fw)) rangeErr("Incorrect firstDayOfWeek: $fw")
        }
        opt["fw"] = fw
        opt["hc"] = Opt.string(options, "hourCycle", HOUR_CYCLES, null) as String?
        opt["kf"] = Opt.string(options, "caseFirst", CASE_FIRST, null) as String?
        val kn = Opt.boolean(options, "numeric", null)
        opt["kn"] = kn?.toString()
        opt["nu"] = typeOption(options, "numberingSystem")
        val r = makeLocaleRecord(tag, opt)
        locale.locale = r["locale"]!!
        locale.calendar = r["ca"]
        locale.collation = r["co"]
        locale.firstDayOfWeek = r["fw"]
        locale.hourCycle = r["hc"]
        locale.caseFirst = r["kf"]
        val rkn = r["kn"]
        locale.numeric = rkn == "true" || rkn == ""
        locale.numberingSystem = r["nu"]
        return locale
    }

    private fun typeOption(options: JSObject, name: String): String? {
        val v = Opt.string(options, name, null, null) as? String ?: return null
        if (!Opt.isUnicodeType(v)) rangeErr("Incorrect $name: $v")
        return v
    }

    /** WeekdayToString */
    private fun weekdayToString(fw: String): String = when (fw) {
        "0", "7" -> "sun"
        "1" -> "mon"
        "2" -> "tue"
        "3" -> "wed"
        "4" -> "thu"
        "5" -> "fri"
        "6" -> "sat"
        else -> fw
    }

    /** UpdateLanguageId */
    private fun updateLanguageId(tag: String, options: JSObject): String {
        val p = LanguageTag.split(tag)
        val language = Opt.str(options, "language", null, p.language)
        if (!LanguageTag.isLanguageSubtag(language)) rangeErr("Incorrect language: $language")
        val script = Opt.string(options, "script", null, p.script) as String?
        if (script != null && !LanguageTag.isScriptSubtag(script)) rangeErr("Incorrect script: $script")
        val region = Opt.string(options, "region", null, p.region) as String?
        if (region != null && !LanguageTag.isRegionSubtag(region)) rangeErr("Incorrect region: $region")
        val variantsDefault = if (p.variants.isEmpty()) null else p.variants.joinToString("-")
        val variants = Opt.string(options, "variants", null, variantsDefault) as String?
        var variantList = p.variants
        if (variants != null) {
            if (variants.isEmpty()) rangeErr("Incorrect variants")
            val subtags = variants.lowercase().split('-')
            for (v in subtags) if (!LanguageTag.isVariantSubtag(v)) rangeErr("Incorrect variants: $variants")
            if (subtags.toSet().size != subtags.size) rangeErr("Duplicate variants: $variants")
            variantList = subtags
        }
        val newTag = LanguageTag.build(language.lowercase(), script, region, variantList, p.extensions, p.privateUse)
        if (!LanguageTag.isStructurallyValid(newTag)) rangeErr("Incorrect locale information provided")
        return newTag
    }

    /** MakeLocaleRecord: returns the slots plus "locale". */
    private fun makeLocaleRecord(tag: String, opt: Map<String, String?>): Map<String, String?> {
        val (attrs, kws0) = LanguageTag.unicodeExtensionOf(tag)
        val keywords = kws0.toMutableList()
        val result = HashMap<String, String?>()
        for (key in KEYS) {
            val idx = keywords.indexOfFirst { it.first == key }
            var value: String? = if (idx >= 0) keywords[idx].second else null
            val override = opt[key]
            if (override != null) {
                value = LanguageTag.canonicalizeUValue(key, override.lowercase())
                if (idx >= 0) keywords[idx] = key to value else keywords.add(key to value)
            }
            result[key] = value
        }
        val base = LanguageTag.removeUnicodeExtension(tag)
        result["locale"] = if (attrs.isNotEmpty() || keywords.isNotEmpty()) {
            LanguageTag.insertUnicodeExtensionAndCanonicalize(base, attrs, keywords)
        } else LanguageTag.canonicalize(base)
        // slots hold the canonical keyword values (e.g. "kn-true" -> "")
        val (_, finalKws) = LanguageTag.unicodeExtensionOf(result["locale"]!!)
        for (key in KEYS) result[key] = finalKws.firstOrNull { it.first == key }?.second
        return result
    }

    /** maximize() / minimize() */
    private fun derived(realm: Realm, loc: JSIntlLocale, maximize: Boolean): JSIntlLocale {
        val base = LanguageTag.baseName(loc.locale)
        val newBase = LocaleInfo.likelySubtags(base, maximize)
        val p = LanguageTag.split(loc.locale)
        val nb = LanguageTag.split(newBase)
        val tag = LanguageTag.canonicalize(LanguageTag.build(nb.language, nb.script, nb.region, nb.variants, p.extensions, p.privateUse))
        val result = JSIntlLocale(realm.intrinsic("%Intl.Locale.prototype%"))
        result.locale = tag
        result.calendar = loc.calendar
        result.collation = loc.collation
        result.firstDayOfWeek = loc.firstDayOfWeek
        result.hourCycle = loc.hourCycle
        result.caseFirst = loc.caseFirst
        result.numeric = loc.numeric
        result.numberingSystem = loc.numberingSystem
        return result
    }
}
