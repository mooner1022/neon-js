package io.neonjs.intl

import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.ListFormatter
import io.neonjs.runtime.*
import io.neonjs.vm.Iteration

/** Intl.ListFormat instance (`[[InitializedListFormat]]`). */
class JSIntlListFormat internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var type: String = "conjunction"
    @JvmField var style: String = "long"
    /** ICU list formatter (immutable, created on first use). */
    @JvmField internal var icu: ListFormatter? = null
}

/** Intl.ListFormat (ECMA-402 §13). */
internal object ListFormatBuiltins {
    private val TYPES = arrayOf("conjunction", "disjunction", "unit")
    private val STYLES = arrayOf("long", "short", "narrow")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.ListFormat.prototype%"] = proto
        val ctor = makeCtor(realm, "ListFormat", 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.ListFormat requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.ListFormat%"] = ctor
        intl.defineOwn("ListFormat", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.ListFormat", Attr.CONFIGURABLE)
        proto.method(realm, "format", 1) { f, t, args, _ ->
            val lf = thisListFormat(t, "format")
            val list = stringListFromIterable(f.realm, args.arg(0))
            f.realm.checkedString(formatter(lf).format(list))
        }
        proto.method(realm, "formatToParts", 1) { f, t, args, _ ->
            val lf = thisListFormat(t, "formatToParts")
            val list = stringListFromIterable(f.realm, args.arg(0))
            formatToParts(f.realm, lf, list)
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ ->
            val lf = thisListFormat(t, "resolvedOptions")
            val o = plainObject(f.realm)
            o.createDataPropertyOrThrow("locale", lf.locale)
            o.createDataPropertyOrThrow("type", lf.type)
            o.createDataPropertyOrThrow("style", lf.style)
            o
        }
    }

    private fun thisListFormat(t: Any?, method: String): JSIntlListFormat =
        t as? JSIntlListFormat ?: typeErr("Method Intl.ListFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, nt: JSObject, locales: Any?, optionsArg: Any?): JSIntlListFormat {
        val lf = JSIntlListFormat(protoFromCtor(nt, "%Intl.ListFormat.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.getOptionsObject(optionsArg)
        Opt.localeMatcher(options)
        val r = Locales.resolve(realm, requested, emptyList(), emptyMap()) { _, _ -> listOf(null) }
        lf.locale = r.locale
        lf.dataLocale = r.dataLocale
        lf.type = Opt.str(options, "type", TYPES, "conjunction")
        lf.style = Opt.str(options, "style", STYLES, "long")
        return lf
    }

    private fun formatter(lf: JSIntlListFormat): ListFormatter {
        lf.icu?.let { return it }
        val type = when (lf.type) {
            "disjunction" -> ListFormatter.Type.OR
            "unit" -> ListFormatter.Type.UNITS
            else -> ListFormatter.Type.AND
        }
        val width = when (lf.style) {
            "short" -> ListFormatter.Width.SHORT
            "narrow" -> ListFormatter.Width.NARROW
            else -> ListFormatter.Width.WIDE
        }
        val f = try {
            ListFormatter.getInstance(LocaleInfo.dataLocale(lf.dataLocale), type, width)
        } catch (_: RuntimeException) {
            rangeErr("Internal error in list format data")
        }
        lf.icu = f
        return f
    }

    /** StringListFromIterable */
    private fun stringListFromIterable(realm: Realm, iterable: Any?): List<String> {
        if (iterable === Undefined) return emptyList()
        val rec = Iteration.getIterator(realm, iterable, false)
        val list = ArrayList<String>()
        var total = 0L
        while (true) {
            val next = Iteration.stepValue(rec)
            if (next === NotFound) return list
            if (next !is CharSequence) {
                Iteration.closeAndRethrow(rec, JSException.typeError("Iterable yielded ${Ops.toDisplayString(next)} which is not a string"))
            }
            val s = next.toString()
            total += s.length
            realm.agent.checkStringLength(total)
            list.add(s)
            realm.tick(list.size.toLong())
        }
    }

    private fun formatToParts(realm: Realm, lf: JSIntlListFormat, list: List<String>): JSArray {
        val fl = formatter(lf).formatToValue(list)
        val s = fl.toString()
        realm.agent.checkStringLength(s.length.toLong())
        val parts = ArrayList<Any?>()
        val cfp = ConstrainedFieldPosition()
        cfp.constrainField(ListFormatter.Field.ELEMENT)
        var pos = 0
        var i = 0L
        while (fl.nextPosition(cfp)) {
            realm.tick(i++)
            if (cfp.start > pos) parts.add(partObject(realm, "literal", s.substring(pos, cfp.start)))
            parts.add(partObject(realm, "element", s.substring(cfp.start, cfp.limit)))
            pos = cfp.limit
        }
        if (pos < s.length) parts.add(partObject(realm, "literal", s.substring(pos)))
        return jsArray(realm, parts)
    }
}
