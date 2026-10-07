package dev.mooner.neonjs.intl

import dev.mooner.neonjs.runtime.JSSymbol
import dev.mooner.neonjs.runtime.Realm

/**
 * Per-realm Intl state. A realm (like its agent) is used by one thread at a time, so the caches here need no
 * synchronization; they hold ICU objects that are not thread-safe and must never be shared between realms.
 */
internal class IntlState {
    /** DefaultLocale(), resolved on first use. */
    @JvmField var defaultLocale: String? = null

    /** `%Intl%.[[FallbackSymbol]]` */
    @JvmField val fallbackSymbol = JSSymbol("IntlLegacyConstructedSymbol")

    /** Small LRU cache for the locale-sensitive convenience methods (toLocaleString & co.). */
    private val cache = object : LinkedHashMap<Any, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Any>?): Boolean = size > 32
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cached(key: Any, create: () -> T): T {
        cache[key]?.let { return it as T }
        val v = create()
        cache[key] = v
        return v
    }

    companion object {
        const val KEY = "%neonjs.IntlState%"

        fun of(realm: Realm): IntlState = realm.intrinsicsAny[KEY] as? IntlState ?: IntlState().also { realm.intrinsicsAny[KEY] = it }
    }
}
