package io.neonjs.intl

import com.ibm.icu.impl.ICUData
import com.ibm.icu.impl.ICUResourceBundle
import com.ibm.icu.text.Collator
import com.ibm.icu.text.NumberingSystem
import com.ibm.icu.util.Calendar
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.UResourceBundle
import io.neonjs.runtime.*

/** ICU-backed locale information used by Intl.Locale (likely subtags, calendars, hour cycles, week data, ...). */
internal object LocaleInfo {
    /** The ICU locale for [locale]'s data: its best available prefix, or root (never ICU's JVM-default fallback). */
    fun dataLocale(locale: String): ULocale {
        val found = Locales.bestAvailable(LanguageTag.baseName(locale)) ?: return ULocale.ROOT
        return ULocale.forLanguageTag(found)
    }

    fun likelySubtags(base: String, maximize: Boolean): String {
        return try {
            val u = ULocale.forLanguageTag(base)
            val r = if (maximize) ULocale.addLikelySubtags(u) else ULocale.minimizeSubtags(u)
            val tag = r.toLanguageTag()
            if (LanguageTag.isStructurallyValid(tag)) LanguageTag.canonicalize(tag) else base
        } catch (_: Exception) {
            base
        }
    }

    /** Converts an ICU calendar type ("gregorian") to its BCP 47 form ("gregory"). */
    fun calendarToBcp47(icu: String): String = ULocale.toUnicodeLocaleType("ca", icu) ?: icu

    fun collationToBcp47(icu: String): String = ULocale.toUnicodeLocaleType("co", icu) ?: icu

    /** RegionPreference: (region, regionOverride). */
    fun regionPreference(locale: String): Pair<String, String?> {
        val p = LanguageTag.split(locale)
        var region = p.region?.uppercase()
        if (region == null) {
            region = subdivisionRegion(locale, "sd")
            if (region == null) {
                val max = LanguageTag.split(likelySubtags(LanguageTag.baseName(locale), true))
                region = max.region?.uppercase() ?: "001"
            }
        }
        return region to subdivisionRegion(locale, "rg")
    }

    /** CanonicalUnicodeSubdivision: the region part of a valid "rg"/"sd" subdivision keyword. */
    private fun subdivisionRegion(locale: String, key: String): String? {
        val v = LanguageTag.unicodeKeyword(locale, key) ?: return null
        if (v.length !in 3..7) return null
        val region = if (LanguageTag.isDigit(v[0])) v.substring(0, 3) else v.substring(0, 2)
        if (!LanguageTag.isRegionSubtag(region)) return null
        return region.uppercase()
    }

    private fun lookupRegion(locale: String): String {
        val (region, override) = regionPreference(locale)
        return override ?: region
    }

    fun calendars(loc: JSIntlLocale): List<Any?> {
        loc.calendar?.let { return listOf(it) }
        val region = lookupRegion(loc.locale)
        val list = Calendar.getKeywordValuesForLocale("calendar", ULocale("und_$region"), true)
        return list.map { calendarToBcp47(it) }.distinct()
    }

    fun collations(loc: JSIntlLocale): List<Any?> {
        loc.collation?.let { return listOf(it) }
        val values = Collator.getKeywordValuesForLocale("collation", dataLocale(loc.locale), true)
        return values.map { collationToBcp47(it) }.filter { it != "standard" && it != "search" }.distinct().sorted()
    }

    private val timeData: UResourceBundle by lazy {
        UResourceBundle.getBundleInstance(ICUData.ICU_BASE_NAME, "supplementalData", ICUResourceBundle.ICU_DATA_CLASS_LOADER).get("timeData")
    }

    private fun hourCyclesFor(key: String): List<String>? {
        val e = try { timeData.get(key) } catch (_: Exception) { return null }
        val out = LinkedHashSet<String>()
        try { hcOf(e.get("preferred").string)?.let { out.add(it) } } catch (_: Exception) {}
        try { for (s in e.get("allowed").stringArray) hcOf(s)?.let { out.add(it) } } catch (_: Exception) {}
        return if (out.isEmpty()) null else out.toList()
    }

    private fun hcOf(skeleton: String): String? = when (skeleton.firstOrNull()) {
        'h' -> "h12"
        'H' -> "h23"
        'K' -> "h11"
        'k' -> "h24"
        else -> null
    }

    fun hourCycles(loc: JSIntlLocale): List<Any?> {
        loc.hourCycle?.let { return listOf(it) }
        val (region, override) = regionPreference(loc.locale)
        val language = LanguageTag.split(loc.locale).language
        val regions = if (override != null) listOf(override, region) else listOf(region)
        for (r in regions) {
            hourCyclesFor("${language}_$r")?.let { return it }
            hourCyclesFor(r)?.let { return it }
        }
        return hourCyclesFor("001") ?: listOf("h23")
    }

    fun numberingSystems(loc: JSIntlLocale): List<Any?> {
        loc.numberingSystem?.let { return listOf(it) }
        val ns = NumberingSystem.getInstance(dataLocale(loc.locale))
        return listOf(ns.name)
    }

    fun timeZones(loc: JSIntlLocale): List<Any?>? {
        val region = LanguageTag.split(loc.locale).region ?: return null
        val ids = TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, region.uppercase(), null)
        return ids.map { TimeZone.getIanaID(it) ?: it }.distinct().sorted()
    }

    fun direction(loc: JSIntlLocale): String {
        val max = LanguageTag.split(likelySubtags(LanguageTag.baseName(loc.locale), true))
        val script = max.script ?: return "ltr"
        val code = com.ibm.icu.lang.UScript.getCodeFromName(script)
        return if (code >= 0 && com.ibm.icu.lang.UScript.isRightToLeft(code)) "rtl" else "ltr"
    }

    /** ISO weekday (1 = Monday ... 7 = Sunday) of an ICU Calendar day constant (1 = Sunday ... 7 = Saturday). */
    private fun isoDay(icu: Int): Int = if (icu == Calendar.SUNDAY) 7 else icu - 1

    fun weekInfo(realm: Realm, loc: JSIntlLocale): JSObject {
        val (region, override) = regionPreference(loc.locale)
        val wd = Calendar.getWeekDataForRegion(override ?: region)
        var firstDay = isoDay(wd.firstDayOfWeek)
        when (loc.firstDayOfWeek) {
            "mon" -> firstDay = 1
            "tue" -> firstDay = 2
            "wed" -> firstDay = 3
            "thu" -> firstDay = 4
            "fri" -> firstDay = 5
            "sat" -> firstDay = 6
            "sun" -> firstDay = 7
        }
        val weekend = ArrayList<Any?>()
        var d = isoDay(wd.weekendOnset)
        val end = isoDay(wd.weekendCease)
        for (i in 0 until 7) {
            weekend.add(d.toDouble())
            if (d == end) break
            d = d % 7 + 1
        }
        weekend.sortBy { it as Double }
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("firstDay", firstDay.toDouble())
        o.createDataPropertyOrThrow("weekend", jsArray(realm, weekend))
        return o
    }
}
