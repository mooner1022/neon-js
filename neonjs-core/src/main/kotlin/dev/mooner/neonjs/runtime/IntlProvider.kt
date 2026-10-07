package dev.mooner.neonjs.runtime

import java.util.ServiceLoader

/**
 * Hook for ECMA-402 (`Intl` and the locale-sensitive methods of String, Number, BigInt, Date, Array...). The
 * implementation lives in the optional `neonjs-intl` module (ICU4J) and is found with [ServiceLoader]; without it the
 * engine keeps its built-in locale-independent behaviour.
 */
interface IntlProvider {
    /**
     * Installs `Intl` into [realm] and replaces the locale-sensitive built-in methods. Called once per realm at the
     * end of `Builtins.install`; must be cheap (load locale data lazily, on first use).
     */
    fun install(realm: Realm)
}

object IntlSupport {
    /** The installed provider, or null when `neonjs-intl` is not on the class path. */
    @JvmStatic
    val provider: IntlProvider? by lazy {
        val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, IntlProvider::class.java.classLoader).distinct()
        loaders.firstNotNullOfOrNull { l -> ServiceLoader.load(IntlProvider::class.java, l).firstOrNull() }
    }
}
