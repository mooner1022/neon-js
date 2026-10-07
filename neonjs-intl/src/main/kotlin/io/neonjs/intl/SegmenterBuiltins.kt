package io.neonjs.intl

import com.ibm.icu.text.BreakIterator
import io.neonjs.runtime.*
import io.neonjs.vm.Iteration

/** Intl.Segmenter instance ([[InitializedSegmenter]]). */
class JSIntlSegmenter internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var granularity: String = "grapheme"
    /** Prototype break iterator (never used directly; cloned for every %Segments% / %SegmentIterator%). */
    @JvmField internal var icu: BreakIterator? = null
}

/** %Segments% instance ([[SegmentsSegmenter]], [[SegmentsString]]). */
class JSIntlSegments internal constructor(proto: JSObject?, @JvmField val segmenter: JSIntlSegmenter, @JvmField val string: String) : JSObject(proto) {
    /** Break iterator over [string], private to this object (used by containing()). */
    @JvmField internal var bi: BreakIterator? = null
}

/** %SegmentIterator% instance ([[IteratingSegmenter]], [[IteratedString]], [[IteratedStringNextSegmentCodeUnitIndex]]). */
class JSIntlSegmentIterator internal constructor(proto: JSObject?, @JvmField val segmenter: JSIntlSegmenter, @JvmField val string: String) : JSObject(proto) {
    @JvmField var nextIndex: Int = 0
    @JvmField internal var bi: BreakIterator? = null
}

/** Intl.Segmenter (ECMA-402 §18). */
internal object SegmenterBuiltins {
    private val GRANULARITIES = arrayOf("grapheme", "word", "sentence")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.Segmenter.prototype%"] = proto
        val ctor = makeCtor(realm, "Segmenter", 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.Segmenter requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.Segmenter%"] = ctor
        intl.defineOwn("Segmenter", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Segmenter", Attr.CONFIGURABLE)
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ ->
            val s = thisSegmenter(t, "resolvedOptions")
            val o = plainObject(f.realm)
            o.createDataPropertyOrThrow("locale", s.locale)
            o.createDataPropertyOrThrow("granularity", s.granularity)
            o
        }
        proto.method(realm, "segment", 1) { f, t, args, _ ->
            val s = thisSegmenter(t, "segment")
            val str = Ops.toString(args.arg(0))
            JSIntlSegments(f.realm.intrinsic("%SegmentsPrototype%"), s, str)
        }

        val segmentsProto = JSObject(realm.objectPrototype)
        realm.intrinsics["%SegmentsPrototype%"] = segmentsProto
        segmentsProto.method(realm, "containing", 1) { f, t, args, _ ->
            val seg = t as? JSIntlSegments ?: typeErr("Method %Segments%.prototype.containing called on incompatible receiver ${Ops.describe(t)}")
            containing(f.realm, seg, args.arg(0))
        }
        segmentsProto.method(realm, JSSymbol.iterator, 0) { f, t, _, _ ->
            val seg = t as? JSIntlSegments ?: typeErr("Method %Segments%.prototype[@@iterator] called on incompatible receiver ${Ops.describe(t)}")
            JSIntlSegmentIterator(f.realm.intrinsic("%SegmentIteratorPrototype%"), seg.segmenter, seg.string)
        }

        val iterProto = JSObject(realm.iteratorPrototype)
        realm.intrinsics["%SegmentIteratorPrototype%"] = iterProto
        iterProto.method(realm, "next", 0) { f, t, _, _ ->
            val it = t as? JSIntlSegmentIterator ?: typeErr("Method %SegmentIterator%.prototype.next called on incompatible receiver ${Ops.describe(t)}")
            next(f.realm, it)
        }
        iterProto.defineOwn(JSSymbol.toStringTag, "Segmenter String Iterator", Attr.CONFIGURABLE)
    }

    private fun thisSegmenter(t: Any?, method: String): JSIntlSegmenter =
        t as? JSIntlSegmenter ?: typeErr("Method Intl.Segmenter.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, nt: JSObject, locales: Any?, optionsArg: Any?): JSIntlSegmenter {
        val s = JSIntlSegmenter(protoFromCtor(nt, "%Intl.Segmenter.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.getOptionsObject(optionsArg)
        Opt.localeMatcher(options)
        val r = Locales.resolve(realm, requested, emptyList(), emptyMap()) { _, _ -> listOf(null) }
        s.locale = r.locale
        s.dataLocale = r.dataLocale
        s.granularity = Opt.str(options, "granularity", GRANULARITIES, "grapheme")
        return s
    }

    /** A fresh break iterator over [text] for [s] (a clone of the segmenter's prototype iterator). */
    private fun newIterator(s: JSIntlSegmenter, text: String): BreakIterator {
        var proto = s.icu
        if (proto == null) {
            val ul = LocaleInfo.dataLocale(s.dataLocale)
            proto = try {
                when (s.granularity) {
                    "word" -> BreakIterator.getWordInstance(ul)
                    "sentence" -> BreakIterator.getSentenceInstance(ul)
                    else -> BreakIterator.getCharacterInstance(ul)
                }
            } catch (_: RuntimeException) {
                rangeErr("Internal error in segmentation data")
            }
            s.icu = proto
        }
        val bi = proto!!.clone()
        bi.setText(text)
        return bi
    }

    /** FindBoundary-based segment around code unit [index], with the word-likeness of that segment. */
    private fun containing(realm: Realm, seg: JSIntlSegments, index: Any?): Any? {
        val n = Ops.toIntegerOrInfinity(index)
        val len = seg.string.length
        if (n < 0 || n >= len) return Undefined
        val i = n.toInt()
        val bi = seg.bi ?: newIterator(seg.segmenter, seg.string).also { seg.bi = it }
        val start = if (bi.isBoundary(i)) i else bi.preceding(i)
        val end = bi.following(i)
        return segmentData(realm, seg.segmenter, seg.string, start, end, bi.ruleStatus)
    }

    private fun next(realm: Realm, it: JSIntlSegmentIterator): JSObject {
        val start = it.nextIndex
        val len = it.string.length
        if (start >= len) return Iteration.createIterResult(realm, Undefined, true)
        val bi = it.bi ?: newIterator(it.segmenter, it.string).also { b -> it.bi = b }
        val end = bi.following(start)
        it.nextIndex = end
        val data = segmentData(realm, it.segmenter, it.string, start, end, bi.ruleStatus)
        return Iteration.createIterResult(realm, data, false)
    }

    /** CreateSegmentDataObject */
    private fun segmentData(realm: Realm, s: JSIntlSegmenter, string: String, start: Int, end: Int, ruleStatus: Int): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("segment", string.substring(start, end))
        o.createDataPropertyOrThrow("index", start.toDouble())
        o.createDataPropertyOrThrow("input", string)
        if (s.granularity == "word") o.createDataPropertyOrThrow("isWordLike", ruleStatus >= BreakIterator.WORD_NONE_LIMIT)
        return o
    }
}
