package dev.mooner.neonjs.intl

import com.ibm.icu.text.Collator
import com.ibm.icu.text.NumberingSystem
import com.ibm.icu.util.Currency
import com.ibm.icu.util.TimeZone
import dev.mooner.neonjs.runtime.*

/** Intl.supportedValuesOf and the identifier lists behind it (computed lazily, immutable afterwards). */
internal object SupportedValues {
    /** AvailableCalendars (canonical BCP 47 calendar types), as required by Intl.Era-monthcode. */
    val calendars: List<String> = listOf(
        "buddhist", "chinese", "coptic", "dangi", "ethioaa", "ethiopic", "gregory", "hebrew", "indian", "islamic-civil",
        "islamic-tbla", "islamic-umalqura", "iso8601", "japanese", "persian", "roc",
    )

    /** Calendar types accepted as input (canonical types plus aliases that canonicalize to them). */
    fun isSupportedCalendar(ca: String): Boolean = ca in calendars

    val collations: List<String> by lazy {
        Collator.getKeywordValues("collation").map { LocaleInfo.collationToBcp47(it) }
            .filter { it != "standard" && it != "search" }.distinct().sorted()
    }

    val currencies: List<String> by lazy {
        // only codes with display names, so that Intl.DisplayNames supports exactly this list
        val names = com.ibm.icu.text.CurrencyDisplayNames.getInstance(com.ibm.icu.util.ULocale.ENGLISH, true)
        val all = HashSet<String>()
        for (c in Currency.getAvailableCurrencies()) {
            val code = c.currencyCode
            if (code.length == 3 && code.all { it in 'A'..'Z' } && names.getName(code) != null) all.add(code)
        }
        all.sorted()
    }

    /** Numbering systems with a simple digit mapping (the only ones usable by NumberFormat/DateTimeFormat). */
    val numberingSystems: List<String> by lazy {
        NumberingSystem.getAvailableNames().filter { name ->
            val ns = NumberingSystem.getInstanceByName(name)
            ns != null && !ns.isAlgorithmic && ns.radix == 10 && name.length in 3..8 && name.all { it in 'a'..'z' || it in '0'..'9' }
        }.distinct().sorted()
    }

    fun isSupportedNumberingSystem(nu: String): Boolean = nu in numberingSystemSet

    private val numberingSystemSet: Set<String> by lazy { numberingSystems.toHashSet() }

    /** Simple units sanctioned for use in ECMAScript (Table "IsSanctionedSingleUnitIdentifier"). */
    val units: List<String> = listOf(
        "acre", "bit", "byte", "celsius", "centimeter", "day", "degree", "fahrenheit", "fluid-ounce", "foot", "gallon",
        "gigabit", "gigabyte", "gram", "hectare", "hour", "inch", "kilobit", "kilobyte", "kilogram", "kilometer", "liter",
        "megabit", "megabyte", "meter", "microsecond", "mile", "mile-scandinavian", "milliliter", "millimeter",
        "millisecond", "minute", "month", "nanosecond", "ounce", "percent", "petabyte", "pound", "second", "stone",
        "terabit", "terabyte", "week", "yard", "year",
    )

    val unitSet: Set<String> = units.toHashSet()

    fun supportedValuesOf(realm: Realm, key: String): JSArray {
        val list: List<String> = when (key) {
            "calendar" -> calendars
            "collation" -> collations
            "currency" -> currencies
            "numberingSystem" -> numberingSystems
            "timeZone" -> TimeZones.primaryIdentifiers
            "unit" -> units
            else -> rangeErr("Invalid key : $key")
        }
        return jsArray(realm, list)
    }
}

/** IANA time zone identifiers (via ICU's tz data). */
internal object TimeZones {
    /** Three-letter IDs that ICU (like java.util.TimeZone) accepts but that are not IANA Zone or Link names. */
    private val JAVA_ONLY = setOf(
        "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST", "CTT", "EAT", "ECT", "IET", "IST", "JST",
        "MIT", "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST",
    )

    /** Every available Zone and Link name, keyed by its ASCII-lowercase form. */
    private val byLowerCase: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (id in TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.ANY, null, null)) {
            if (id.startsWith("SystemV/") || id in JAVA_ONLY) continue
            m[id.lowercase()] = id
        }
        for (id in listOf("UTC", "Etc/UTC", "Etc/GMT", "GMT")) m.putIfAbsent(id.lowercase(), id)
        m
    }

    /** AvailablePrimaryTimeZoneIdentifiers, sorted. */
    val primaryIdentifiers: List<String> by lazy {
        val out = HashSet<String>()
        for (id in TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL, null, null)) {
            if (id.startsWith("SystemV/") || id in JAVA_ONLY) continue
            out.add(primaryOf(id))
        }
        out.remove("Etc/UTC")
        out.remove("Etc/GMT")
        out.add("UTC")
        out.sorted()
    }

    /** The primary identifier of an available identifier ("UTC" for the UTC aliases). */
    fun primaryOf(id: String): String {
        val iana = TimeZone.getIanaID(TimeZone.getCanonicalID(id) ?: id) ?: id
        return when (iana) {
            "Etc/UTC", "Etc/GMT", "GMT", "Etc/UCT", "UCT", "Etc/Universal", "Universal", "Etc/Zulu", "Zulu", "Etc/GMT0",
            "Etc/GMT+0", "Etc/GMT-0", "GMT0", "GMT+0", "GMT-0", "Etc/Greenwich", "Greenwich" -> "UTC"
            else -> iana
        }
    }

    /** GetAvailableNamedTimeZoneIdentifier: (identifier as listed, primary identifier) or null. */
    fun find(name: String): Pair<String, String>? {
        if (name.length > 64) return null
        val id = byLowerCase[name.lowercase()] ?: return null
        return id to primaryOf(id)
    }
}
