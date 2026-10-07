package dev.mooner.neonjs.intl

import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*

/**
 * ECMA-402 implementation backed by ICU4J. Registered through META-INF/services.
 *
 * [install] runs for every realm, so it only creates the JS function objects; no ICU class is touched (and no
 * locale data is loaded) until an Intl constructor or locale-sensitive method is first used.
 */
class IcuIntlProvider : IntlProvider {
    override fun install(realm: Realm) {
        IntlInstaller.install(realm)
    }
}

internal object IntlInstaller {
    fun install(realm: Realm) {
        val intl = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl%"] = intl
        intl.defineOwn(JSSymbol.toStringTag, "Intl", Attr.CONFIGURABLE)
        intl.method(realm, "getCanonicalLocales", 1) { f, _, args, _ ->
            jsArray(f.realm, Locales.canonicalizeLocaleList(f.realm, args.arg(0)))
        }
        intl.method(realm, "supportedValuesOf", 1) { f, _, args, _ ->
            SupportedValues.supportedValuesOf(f.realm, Ops.toString(args.arg(0)))
        }
        CollatorBuiltins.install(realm, intl)
        DateTimeFormatBuiltins.install(realm, intl)
        DisplayNamesBuiltins.install(realm, intl)
        DurationFormatBuiltins.install(realm, intl)
        ListFormatBuiltins.install(realm, intl)
        LocaleBuiltins.install(realm, intl)
        NumberFormatBuiltins.install(realm, intl)
        PluralRulesBuiltins.install(realm, intl)
        RelativeTimeFormatBuiltins.install(realm, intl)
        SegmenterBuiltins.install(realm, intl)
        TemporalLocale.install(realm)
        installArrayMethods(realm)
        realm.globalObject.defineOwn("Intl", intl, Attr.WC)
    }

    /** Array.prototype.toLocaleString and %TypedArray%.prototype.toLocaleString (ECMA-402 §19.5.1, §19.6.1). */
    private fun installArrayMethods(realm: Realm) {
        realm.arrayPrototype.method(realm, "toLocaleString", 0) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val len = Ops.lengthOfArrayLike(o)
            joinLocale(f.realm, len, args) { k -> o.get(PK.fromIndex(k), o) }
        }
        val taProto = realm.intrinsics["%TypedArray.prototype%"] ?: return
        taProto.method(realm, "toLocaleString", 0) { f, t, args, _ ->
            val ta = t as? JSTypedArray ?: typeErr("%TypedArray%.prototype.toLocaleString: receiver is not a typed array")
            val len = ta.lengthOrOOB()
            if (len < 0) typeErr("%TypedArray%.prototype.toLocaleString: typed array is detached or out of bounds")
            joinLocale(f.realm, len.toLong(), args) { k -> ta.get(PK.fromIndex(k), ta) }
        }
    }

    private inline fun joinLocale(realm: Realm, len: Long, args: Array<Any?>, element: (Long) -> Any?): String {
        val sb = StringBuilder()
        val invokeArgs = arrayOf(args.arg(0), args.arg(1))
        var k = 0L
        while (k < len) {
            realm.tick(k)
            if (k > 0) sb.append(',')
            val e = element(k)
            if (e !== Undefined && e !== Null) {
                sb.append(Ops.toString(Ops.invoke(e, "toLocaleString", invokeArgs)))
                realm.agent.checkStringLength(sb.length.toLong())
            }
            k++
        }
        return sb.toString()
    }
}
