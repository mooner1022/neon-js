package dev.mooner.neonpython

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Codecs (docs/PYTHON_PLATFORM.md §12), as CPython 3.14 has them: Python/codecs.c's registry (search functions, their
 * cache by `_Py_normalize_encoding`'s name, the error handlers by name and PEP 293's protocol for them), the codecs
 * of Objects/unicodeobject.c (UTF-8, UTF-16, UTF-32, UTF-7, Latin-1, ASCII, charmap, unicode_escape,
 * raw_unicode_escape) and of Objects/bytesobject.c (escape), Modules/_codecsmodule.c's `_codecs`, the fields and
 * messages of UnicodeEncodeError, UnicodeDecodeError and UnicodeTranslateError (Objects/exceptions.c), and what
 * `str.encode`, `bytes.decode` and `str(b, encoding)` do with them: utf-8, utf-16, utf-32, ascii and latin-1 (by
 * the names `_Py_normalize_encoding` makes of them) here, any other encoding through the registry. The registry
 * imports CPython's `encodings` package (bundled, PyStdlib) when it is first used, where CPython's start imports it:
 * its search function comes first all the same, but sys.modules has it only from then.
 *
 * A str is a Java String: positions are code points (a surrogate pair is one, as everywhere in NeonPy), so two
 * surrogates that make a pair are one character here where CPython has two. Character names (namereplace,
 * `\N{...}`) are unicodedata's (PyUnicodedata, CPython's names and aliases).
 */
object PyCodecs {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private const val HEX = "0123456789abcdef"
    private const val MAX_UNICODE = 0x10ffff

    private fun ctx() = PyContext.current()
    private fun typeError(msg: String) = PyRt.typeError(msg)
    private fun lookupError(msg: String) = PyException("LookupError", msg)
    private fun s(n: Int) = if (n == 1) "" else "s"

    // ------------------------------------------------------------------ the registry

    /** A context's codec registry (PyInterpreterState.codecs): its search functions, their results by name, the error handlers. */
    class State {
        @JvmField val searchPath = ArrayList<Any?>()
        @JvmField val cache = HashMap<String, Any?>()
        @JvmField val errors = LinkedHashMap<String, Any?>()
        /** Whether `encodings` was imported (or is being imported): its search function comes first, as at CPython's start. */
        @JvmField var initialized = false
    }

    private fun state(ctx: PyContext): State = ctx.codecs ?: State().also { st ->
        ctx.codecs = st
        for ((name, f) in builtinHandlers()) st.errors[name] = f
    }

    /** The registry with `encodings` imported (its search function registered): what a lookup or a register needs. */
    private fun initialized(ctx: PyContext): State {
        val st = state(ctx)
        if (!st.initialized) {
            st.initialized = true
            try {
                PyImport.importModule(ctx, "encodings")
            } catch (t: Throwable) {
                st.initialized = false
                throw t
            }
        }
        return st
    }

    /**
     * `_Py_normalize_encoding`: ASCII letters lowered, digits and dots kept, every other run of characters (a byte of
     * a character past ASCII among them) one `_` between them (none leading or trailing); null where it is longer
     * than [max].
     */
    private fun normalize(encoding: String, max: Int = Int.MAX_VALUE): String? {
        val sb = StringBuilder()
        var punct = false
        for (c in encoding) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.') {
                if (punct && sb.isNotEmpty()) sb.append('_')
                punct = false
                sb.append(if (c in 'A'..'Z') c + 32 else c)
                if (sb.length > max) return null
            } else {
                punct = true
            }
        }
        if (sb.length > max) return null
        return sb.toString()
    }

    /** _PyCodec_Lookup: the codec of [encoding] (a 4-tuple, a CodecInfo), from the cache or the search functions. */
    @JvmStatic
    fun lookup(ctx: PyContext, encoding: String): PyTuple {
        val st = initialized(ctx)
        val v = normalize(encoding)!!
        st.cache[v]?.let { return it as PyTuple }
        if (st.searchPath.isEmpty()) throw lookupError("no codec search functions registered: can't find encoding")
        var result: PyTuple? = null
        for (f in ArrayList(st.searchPath)) {
            val r = PyCalls.callWith(ctx, f, arrayOf(PyStr(v)), NO_ARGS, NO_ARGS)
            if (r === PyNone) continue
            if (r !is PyTuple || r.items.size != 4) throw typeError("codec search functions must return 4-tuples")
            result = r
            break
        }
        if (result == null) throw lookupError("unknown encoding: $encoding")
        st.cache[v] = result
        return result
    }

    /** _PyCodec_LookupTextEncoding: the codec, where it is a text encoding (a CodecInfo's _is_text_encoding not false). */
    private fun lookupText(ctx: PyContext, encoding: String, alternate: String): PyTuple {
        val codec = lookup(ctx, encoding)
        if (codec.sub != null) {
            val attr = try {
                PyRt.getAttr(codec, "_is_text_encoding")
            } catch (e: PyException) {
                if (!e.matches(PyExceptions.type("AttributeError"))) throw e
                null
            }
            if (attr != null && !PyRt.truthy(attr)) {
                throw lookupError("'$encoding' is not a text encoding; use $alternate to handle arbitrary codecs")
            }
        }
        return codec
    }

    /** A note on [e] (_PyErr_FormatNote): "encoding with 'x' codec failed". */
    private fun note(ctx: PyContext, e: PyException, note: String) {
        val o = PyExceptions.objectOf(e, ctx)
        val d = o.dict ?: PyDict().also { o.dict = it }
        val notes = d[PyStr("__notes__")] ?: PyList().also { d[PyStr("__notes__")] = it }
        (notes as? PyList)?.items?.add(PyStr(note))
    }

    /** _PyCodec_EncodeInternal / _PyCodec_DecodeInternal: [coder] called on [obj] (and [errors], where given); what it made. */
    private fun callCoder(ctx: PyContext, coder: Any?, obj: Any?, encoding: String, errors: String?, encode: Boolean): Any? {
        val args = if (errors == null) arrayOf(obj) else arrayOf(obj, PyStr(errors))
        val r = try {
            PyCalls.callWith(ctx, coder, args, NO_ARGS, NO_ARGS)
        } catch (e: PyException) {
            note(ctx, e, "${if (encode) "encoding" else "decoding"} with '$encoding' codec failed")
            throw e
        }
        if (r !is PyTuple || r.items.size != 2) {
            throw typeError(if (encode) "encoder must return a tuple (object, integer)" else "decoder must return a tuple (object,integer)")
        }
        return r.items[0]
    }

    /** PyCodec_Encode (codecs.encode): [obj] encoded by [encoding]'s encoder, whatever it gives. */
    @JvmStatic
    fun encode(ctx: PyContext, obj: Any?, encoding: String, errors: String?): Any? =
        callCoder(ctx, lookup(ctx, encoding).items[0], obj, encoding, errors, true)

    /** PyCodec_Decode (codecs.decode). */
    @JvmStatic
    fun decode(ctx: PyContext, obj: Any?, encoding: String, errors: String?): Any? =
        callCoder(ctx, lookup(ctx, encoding).items[1], obj, encoding, errors, false)

    /** warnings.warn(msg, category), stacklevel 1: the Python code calling the runtime. */
    private fun warn(ctx: PyContext, category: String, msg: String) {
        val w = PyImport.importModule(ctx, "warnings")
        PyCalls.callWith(ctx, PyRt.getAttr(w, "warn"), arrayOf(PyStr(msg), PyExceptions.type(category), 1L), NO_ARGS, NO_ARGS)
    }

    // ------------------------------------------------------------------ str.encode, bytes.decode

    /**
     * PyUnicode_AsEncodedString (str.encode, bytes(str, encoding)): utf-8, utf-16, utf-32, ascii and latin-1 here,
     * any other encoding by its codec (a text encoding's), which must give bytes.
     */
    @JvmStatic
    fun encodeText(ctx: PyContext, str: PyStr, encoding: String?, errors: String?): Any {
        if (encoding == null) return PyBytes(encodeUtf8(ctx, str, errors))
        fastName(encoding)?.let { f ->
            return PyBytes(when (f) {
                "utf8" -> encodeUtf8(ctx, str, errors)
                "utf16" -> encodeUtf16(ctx, str, errors, 0)
                "utf32" -> encodeUtf32(ctx, str, errors, 0)
                "ascii" -> encodeUcs1(ctx, str, errors, 128)
                else -> encodeUcs1(ctx, str, errors, 256)
            })
        }
        val codec = lookupText(ctx, encoding, "codecs.encode()")
        val v = callCoder(ctx, codec.items[0], str, encoding, errors, true)
        if (v is PyBytes || v is PyNumSub && v.num is PyBytes) return v!!
        if (v is PyByteArray) {
            warn(ctx, "RuntimeWarning", "encoder $encoding returned bytearray instead of bytes; use codecs.encode() to encode to arbitrary types")
            return PyBytes(v.bytes().copyOf())
        }
        throw typeError("'$encoding' encoder returned '${PyTypes.name(v)}' instead of 'bytes'; use codecs.encode() to encode to arbitrary types")
    }

    /**
     * PyUnicode_Decode (bytes.decode, str(b, encoding)): nothing to decode gives '' (no codec looked up); utf-8,
     * utf-16, utf-32, ascii and latin-1 here, any other encoding by its codec (given a memoryview), which must give a str.
     */
    @JvmStatic
    fun decodeText(ctx: PyContext, data: ByteArray, encoding: String?, errors: String?): PyStr {
        if (data.isEmpty()) return PyStr.EMPTY
        if (encoding == null) return PyStr(decodeUtf8(ctx, data, errors, true).text)
        fastName(encoding)?.let { f ->
            return PyStr(when (f) {
                "utf8" -> decodeUtf8(ctx, data, errors, true).text
                "utf16" -> decodeUtf16(ctx, data, errors, 0, true).text
                "utf32" -> decodeUtf32(ctx, data, errors, 0, true).text
                "ascii" -> decodeAscii(ctx, data, errors)
                else -> String(data, Charsets.ISO_8859_1)
            })
        }
        val codec = lookupText(ctx, encoding, "codecs.decode()")
        val b = PyBytes(data)
        val view = PyMemoryView(b, b, 0, data.size, 1, "B", 1, true)
        val v = callCoder(ctx, codec.items[1], view, encoding, errors, false)
        if (v is PyStr) return v
        throw typeError("'$encoding' decoder returned '${PyTypes.name(v)}' instead of 'str'; use codecs.decode() to decode to arbitrary types")
    }

    /** The codecs CPython's PyUnicode_Decode and PyUnicode_AsEncodedString have of their own, by [encoding]'s normalized name. */
    private fun fastName(encoding: String): String? {
        val lower = normalize(encoding, 10) ?: return null
        if (lower.startsWith("utf")) {
            var rest = lower.substring(3)
            if (rest.startsWith("_")) rest = rest.substring(1)
            return when (rest) {
                "8" -> "utf8"
                "16" -> "utf16"
                "32" -> "utf32"
                else -> null
            }
        }
        return when (lower) {
            "ascii", "us_ascii" -> "ascii"
            "latin1", "latin_1", "iso_8859_1", "iso8859_1" -> "latin1"
            else -> null
        }
    }

    /** str.encode(encoding='utf-8', errors='strict') */
    @JvmStatic
    fun strEncode(a: Array<Any?>, kw: PyDict?): Any? {
        val self = a.getOrNull(0) as? PyStr ?: throw typeError("descriptor 'encode' for 'str' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        val x = PyMath.clinic("encode", a.copyOfRange(1, a.size), kw, listOf("encoding", "errors"), 0, 0, 2)
        val enc = x[0]?.let { cstr(it, "encode() argument 'encoding'") }
        val err = x[1]?.let { cstr(it, "encode() argument 'errors'") }
        return encodeText(ctx(), self, enc, err)
    }

    /** bytes.decode / bytearray.decode(encoding='utf-8', errors='strict') of [data]. */
    @JvmStatic
    fun bytesDecode(data: ByteArray, a: Array<Any?>, kw: PyDict?): Any? {
        val x = PyMath.clinic("decode", a, kw, listOf("encoding", "errors"), 0, 0, 2)
        val enc = x[0]?.let { cstr(it, "decode() argument 'encoding'") }
        val err = x[1]?.let { cstr(it, "decode() argument 'errors'") }
        return decodeText(ctx(), data, enc, err)
    }

    /**
     * A C string argument (Argument Clinic's `str`): a str (a subclass's instance too) without NUL and encodable in
     * UTF-8; [what] says it in the TypeError ("encode() argument 'errors'"), which names None "None" ([noneName], as
     * _PyArg_BadArgument does) or "NoneType" (as str()'s own check does).
     */
    @JvmStatic
    fun cstr(v: Any?, what: String, noneName: Boolean = true): String {
        val s = v as? PyStr ?: throw typeError("$what must be str, not ${if (noneName) argType(v) else PyTypes.name(v)}")
        checkCstr(s)
        return s.s
    }

    /** The type of [v] as Argument Clinic's messages name it: None "None". */
    private fun argType(v: Any?): String = if (v === PyNone) "None" else PyTypes.name(v)

    private fun checkCstr(s: PyStr) {
        if (!s.bmp && hasLoneSurrogate(s.s)) encodeUtf8(ctx(), s, null)
        if (s.s.indexOf('\u0000') >= 0) throw PyException("ValueError", "embedded null character")
    }

    // ------------------------------------------------------------------ UnicodeError's subclasses

    private val ENCODE_ERROR get() = PyExceptions.type("UnicodeEncodeError")
    private val DECODE_ERROR get() = PyExceptions.type("UnicodeDecodeError")
    private val TRANSLATE_ERROR get() = PyExceptions.type("UnicodeTranslateError")

    /** A UnicodeEncodeError or UnicodeDecodeError as the codecs make one: (encoding, object, start, end, reason). */
    private fun newError(type: PyType, encoding: String, obj: Any, start: Int, end: Int, reason: String): PyBaseException {
        val o = PyBaseException(type)
        val args = arrayOf<Any?>(PyStr(encoding), obj, start.toLong(), end.toLong(), PyStr(reason))
        o.args = PyTuple(args)
        o.setField("encoding", args[0])
        o.setField("object", obj)
        o.setField("start", args[2])
        o.setField("end", args[3])
        o.setField("reason", args[4])
        return o
    }

    private fun isError(v: Any?, t: PyType) = v is PyBaseException && v.type.isSubtype(t)

    /** PyBytes_Check's: a bytes (a subclass's instance too), its bytes. */
    private fun bytesOf(v: Any?): ByteArray? = when (v) {
        is PyBytes -> v.b
        is PyNumSub -> (v.num as? PyBytes)?.b
        else -> null
    }

    /** _PyUnicodeError_GetParams: the error's object, its length, and start and end put within it. */
    private class Params(@JvmField val obj: Any, @JvmField val len: Int, @JvmField val start: Int, @JvmField val end: Int) {
        val slen: Int get() = maxOf(0, end - start)
        fun cps(): IntArray = (obj as PyStr).s.codePoints().toArray()
        fun bytes(): ByteArray = bytesOf(obj)!!
    }

    private fun params(e: PyBaseException, asBytes: Boolean): Params {
        val o = e.fields?.get("object") ?: throw typeError("UnicodeError 'object' attribute is not set")
        val n = if (asBytes) {
            (bytesOf(o) ?: throw typeError("UnicodeError 'object' attribute must be a bytes")).size
        } else {
            (o as? PyStr ?: throw typeError("UnicodeError 'object' attribute must be a string")).length
        }
        var start = (e.fields?.get("start") as? Long) ?: 0L
        if (start < 0) start = 0
        if (start >= n) start = if (n == 0) 0L else (n - 1).toLong()
        var end = (e.fields?.get("end") as? Long) ?: 0L
        if (end < 1) end = 1
        if (end > n) end = n.toLong()
        return Params(o, n, start.toInt(), end.toInt())
    }

    private fun encodingOf(e: PyBaseException): String {
        val v = e.fields?.get("encoding") ?: throw typeError("UnicodeError 'encoding' attribute is not set")
        return (v as? PyStr ?: throw typeError("UnicodeError 'encoding' attribute must be a string")).s
    }

    /** [v] as a C ssize_t (the 'n' of PyArg_ParseTuple): an int (__index__), else CPython's TypeError or OverflowError. */
    private fun ssize(v: Any?): Long = when (val i = PyRt.indexValue(v)) {
        is Long -> i
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
        else -> throw typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** The UnicodeError subclasses' fields (members), __init__ and __str__ (PyExceptions.define calls it). */
    @JvmStatic
    fun defineExceptions() {
        fun self(a: Array<Any?>, n: String): PyBaseException =
            a.getOrNull(0) as? PyBaseException ?: throw typeError("descriptor '$n' requires a 'BaseException' object but received '${PyTypes.name(a.getOrNull(0))}'")
        for (t in listOf(ENCODE_ERROR, DECODE_ERROR, TRANSLATE_ERROR)) {
            for (n in listOf("encoding", "object", "reason")) {
                t.def(n, PyProperty(PyBuiltin(n) { _, a -> self(a, n).fields?.get(n) ?: PyNone },
                    PyBuiltin(n) { _, a -> self(a, n).setField(n, a[1]); PyNone },
                    PyBuiltin(n) { _, a -> self(a, n).fields?.remove(n); PyNone }, null).also { it.name = n })
            }
            for (n in listOf("start", "end")) {
                t.def(n, PyProperty(PyBuiltin(n) { _, a -> self(a, n).fields?.get(n) ?: 0L },
                    PyBuiltin(n) { _, a ->
                        val v = a[1]
                        val x = when (v) {
                            is Long -> v
                            is PyBool -> if (v.value) 1L else 0L
                            is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
                            is PyNumSub -> when (val m = v.num) {
                                is Long -> m
                                is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
                                else -> throw typeError("an integer is required")
                            }
                            else -> throw typeError("an integer is required")
                        }
                        self(a, n).setField(n, x)
                        PyNone
                    },
                    PyBuiltin(n) { _, _ -> throw typeError("can't delete numeric/char attribute") }, null).also { it.name = n })
            }
        }
        fun init(a: Array<Any?>, kw: PyDict?, t: Int): Any? {
            val o = self(a, "__init__")
            if (kw != null && kw.size > 0) throw typeError("${o.type.name}() takes no keyword arguments")
            val args = if (a.size > 1) a.copyOfRange(1, a.size) else NO_ARGS
            o.args = PyTuple(args)
            val want = if (t == 2) 4 else 5
            if (args.size != want) throw typeError("function takes exactly $want arguments (${args.size} given)")
            fun str(i: Int): PyStr = args[i] as? PyStr ?: throw typeError("argument ${i + 1} must be str, not ${argType(args[i])}")
            if (t == 2) {
                val obj = str(0)
                val start = ssize(args[1])
                val end = ssize(args[2])
                val reason = str(3)
                o.setField("object", obj)
                o.setField("start", start)
                o.setField("end", end)
                o.setField("reason", reason)
                return PyNone
            }
            val enc = str(0)
            val obj: Any = if (t == 0) str(1) else {
                if (bytesOf(args[1]) != null) args[1]!!
                else PyBytes((PyByteSeqs.content(args[1]) ?: throw typeError("a bytes-like object is required, not '${PyTypes.name(args[1])}'")).copyOf())
            }
            val start = ssize(args[2])
            val end = ssize(args[3])
            val reason = str(4)
            o.setField("encoding", enc)
            o.setField("object", obj)
            o.setField("start", start)
            o.setField("end", end)
            o.setField("reason", reason)
            return PyNone
        }
        for ((i, t) in listOf(ENCODE_ERROR, DECODE_ERROR, TRANSLATE_ERROR).withIndex()) {
            t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null, i) }, { _, a, kw -> init(a, kw, i) }))
            t.def("__str__", PyBuiltin("__str__") { _, a -> PyStr(errorStr(self(a, "__str__"), i)) })
        }
    }

    /** UnicodeEncodeError_str (k 0), UnicodeDecodeError_str (1), UnicodeTranslateError_str (2). */
    private fun errorStr(e: PyBaseException, k: Int): String {
        val f = e.fields
        val obj = f?.get("object") ?: return ""
        val reason = f["reason"]?.let { PyRt.str(it) } ?: "<NULL>"
        val encoding = if (k == 2) "" else f["encoding"]?.let { PyRt.str(it) } ?: "<NULL>"
        val start = (f["start"] as? Long) ?: 0L
        val end = (f["end"] as? Long) ?: 0L
        if (k == 1) {
            val b = bytesOf(obj) ?: throw typeError("UnicodeError 'object' attribute must be a bytes")
            val len = b.size
            if (start >= 0 && start < len && end >= 0 && end <= len && end == start + 1) {
                return "'$encoding' codec can't decode byte 0x${hex2(b[start.toInt()].toInt() and 0xff)} in position $start: $reason"
            }
            return "'$encoding' codec can't decode bytes in position $start-${end - 1}: $reason"
        }
        val s = (obj as? PyStr ?: throw typeError("UnicodeError 'object' attribute must be a string"))
        val len = s.length
        val what = if (k == 0) "'$encoding' codec can't encode" else "can't translate"
        if (start >= 0 && start < len && end >= 0 && end <= len && end == start + 1) {
            val c = s.s.codePointAt(s.s.offsetByCodePoints(0, start.toInt()))
            val esc = when {
                c <= 0xff -> "\\x" + hex2(c)
                c <= 0xffff -> "\\u" + Integer.toHexString(c).padStart(4, '0')
                else -> "\\U" + Integer.toHexString(c).padStart(8, '0')
            }
            return "$what character '$esc' in position $start: $reason"
        }
        return "$what characters in position $start-${end - 1}: $reason"
    }

    private fun hex2(c: Int) = "${HEX[(c shr 4) and 0xf]}${HEX[c and 0xf]}"

    // ------------------------------------------------------------------ calling error handlers

    /** PyCodec_LookupError: the handler registered as [name] (strict's where it is null). */
    @JvmStatic
    fun lookupErrorHandler(ctx: PyContext, name: String?): Any? {
        val n = name ?: "strict"
        return state(ctx).errors[n] ?: throw lookupError("unknown error handler name '$n'")
    }

    private fun raise(ctx: PyContext, e: PyBaseException): Nothing = throw PyExceptions.raiseObject(e, ctx)

    /**
     * An encoder's errors (unicode_encode_call_errorhandler): the handler, looked up at the first, and the
     * UnicodeEncodeError, made then and given each one's start, end and reason after.
     */
    private class EncodeErrors(@JvmField val ctx: PyContext, @JvmField val encoding: String, @JvmField val errors: String?, @JvmField val str: PyStr) {
        @JvmField var handler: Any? = null
        @JvmField var exc: PyBaseException? = null

        fun exception(reason: String, start: Int, end: Int): PyBaseException {
            val e = exc ?: return newError(ENCODE_ERROR, encoding, str, start, end, reason).also { exc = it }
            e.setField("start", start.toLong())
            e.setField("end", end.toLong())
            e.setField("reason", PyStr(reason))
            return e
        }

        fun raise(reason: String, start: Int, end: Int): Nothing = raise(ctx, exception(reason, start, end))

        /** The handler's replacement (a str or bytes) for [start, end) and the position to go on from. */
        fun call(reason: String, start: Int, end: Int, len: Int): Pair<Any, Int> {
            val h = handler ?: lookupErrorHandler(ctx, errors).also { handler = it }
            val e = exception(reason, start, end)
            val r = PyCalls.callWith(ctx, h, arrayOf(e), NO_ARGS, NO_ARGS)
            val msg = "encoding error handler must return (str/bytes, int) tuple"
            if (r !is PyTuple || r.items.size != 2) throw typeError(msg)
            var newpos = ssize(r.items[1])
            val rep = r.items[0]
            val repv: Any = if (rep is PyStr) rep else PyBytes(bytesOf(rep) ?: throw typeError(msg))
            if (newpos < 0) newpos += len
            if (newpos < 0 || newpos > len) throw PyException("IndexError", "position $newpos from error handler out of bounds")
            return repv to newpos.toInt()
        }
    }

    /**
     * A decoder's errors (unicode_decode_call_errorhandler_writer): the handler, the UnicodeDecodeError, and the input,
     * which the handler may replace (the error's object, read again after it).
     */
    private class DecodeErrors(@JvmField val ctx: PyContext, @JvmField val encoding: String, @JvmField val errors: String?, @JvmField var input: ByteArray) {
        @JvmField var handler: Any? = null
        @JvmField var exc: PyBaseException? = null

        /** Calls the handler for [start, end): its replacement appended to [out]; the position to go on from (in [input]). */
        fun call(reason: String, start: Int, end: Int, out: StringBuilder): Int {
            val h = handler ?: lookupErrorHandler(ctx, errors).also { handler = it }
            val e = exc?.also {
                it.setField("start", start.toLong())
                it.setField("end", end.toLong())
                it.setField("reason", PyStr(reason))
            } ?: newError(DECODE_ERROR, encoding, PyBytes(input), start, end, reason).also { exc = it }
            val r = PyCalls.callWith(ctx, h, arrayOf(e), NO_ARGS, NO_ARGS)
            val msg = "decoding error handler must return (str, int) tuple"
            if (r !is PyTuple || r.items.size != 2) throw typeError(msg)
            val rep = r.items[0] as? PyStr ?: throw typeError(msg)
            var newpos = ssize(r.items[1])
            val o = e.fields?.get("object") ?: throw typeError("UnicodeError 'object' attribute is not set")
            input = bytesOf(o) ?: throw typeError("UnicodeError 'object' attribute must be a bytes")
            val insize = input.size
            if (newpos < 0) newpos += insize
            if (newpos < 0 || newpos > insize) throw PyException("IndexError", "position $newpos from error handler out of bounds")
            out.append(rep.s)
            return newpos.toInt()
        }
    }

    // _Py_GetErrorHandler's: the handlers the codecs know by name (their behaviour CPython's own, whatever is registered)
    private const val H_STRICT = 0
    private const val H_SURROGATEESCAPE = 1
    private const val H_REPLACE = 2
    private const val H_IGNORE = 3
    private const val H_BACKSLASHREPLACE = 4
    private const val H_SURROGATEPASS = 5
    private const val H_XMLCHARREFREPLACE = 6
    private const val H_OTHER = 7

    private fun handlerKind(errors: String?): Int = when (errors) {
        null, "strict" -> H_STRICT
        "surrogateescape" -> H_SURROGATEESCAPE
        "replace" -> H_REPLACE
        "ignore" -> H_IGNORE
        "backslashreplace" -> H_BACKSLASHREPLACE
        "surrogatepass" -> H_SURROGATEPASS
        "xmlcharrefreplace" -> H_XMLCHARREFREPLACE
        else -> H_OTHER
    }

    private fun backslash(out: StringBuilder, ch: Int) {
        out.append('\\')
        when {
            ch >= 0x10000 -> {
                out.append('U')
                for (sh in intArrayOf(28, 24, 20, 16, 12, 8)) out.append(HEX[(ch shr sh) and 0xf])
            }
            ch >= 0x100 -> out.append('u').append(HEX[(ch shr 12) and 0xf]).append(HEX[(ch shr 8) and 0xf])
            else -> out.append('x')
        }
        out.append(HEX[(ch shr 4) and 0xf]).append(HEX[ch and 0xf])
    }

    private fun ascii(out: ByteArrayOutputStream, s: CharSequence) {
        for (c in s) out.write(c.code)
    }

    // ------------------------------------------------------------------ the builtin error handlers (Python/codecs.c)

    private fun wrongType(exc: Any?): Nothing = throw typeError("don't know how to handle ${PyTypes.fullName(PyTypes.typeOf(exc))} in error callback")

    private fun result(rep: Any, pos: Int) = PyTuple(arrayOf(rep, pos.toLong()))

    private fun builtinHandlers(): List<Pair<String, PyBuiltin>> {
        fun handler(name: String, f: (Any?) -> Any?): PyBuiltin = PyBuiltin(name, { _, a ->
            if (a.size != 1) throw typeError("$name() takes exactly one argument (${a.size} given)")
            f(a[0])
        }, { _, _, _ -> throw typeError("$name() takes no keyword arguments") }).also { it.method = false }
        return listOf(
            "strict" to handler("strict_errors") { exc ->
                if (exc is PyBaseException) raise(ctx(), exc)
                throw typeError("codec must pass exception instance")
            },
            "ignore" to handler("ignore_errors") { exc ->
                when {
                    isError(exc, ENCODE_ERROR) || isError(exc, TRANSLATE_ERROR) -> result(PyStr.EMPTY, params(exc as PyBaseException, false).end)
                    isError(exc, DECODE_ERROR) -> result(PyStr.EMPTY, params(exc as PyBaseException, true).end)
                    else -> wrongType(exc)
                }
            },
            "replace" to handler("replace_errors") { exc ->
                when {
                    isError(exc, ENCODE_ERROR) -> params(exc as PyBaseException, false).let { p -> result(PyStr("?".repeat(p.slen)), p.end) }
                    isError(exc, DECODE_ERROR) -> result(PyStr("\ufffd"), params(exc as PyBaseException, true).end)
                    isError(exc, TRANSLATE_ERROR) -> params(exc as PyBaseException, false).let { p -> result(PyStr("\ufffd".repeat(p.slen)), p.end) }
                    else -> wrongType(exc)
                }
            },
            "xmlcharrefreplace" to handler("xmlcharrefreplace_errors") { exc ->
                if (!isError(exc, ENCODE_ERROR)) wrongType(exc)
                val p = params(exc as PyBaseException, false)
                val c = p.cps()
                val sb = StringBuilder()
                for (i in p.start until p.end) sb.append("&#").append(c[i]).append(';')
                result(PyStr(sb.toString()), p.end)
            },
            "backslashreplace" to handler("backslashreplace_errors") { exc ->
                when {
                    isError(exc, ENCODE_ERROR) || isError(exc, TRANSLATE_ERROR) -> {
                        val p = params(exc as PyBaseException, false)
                        val c = p.cps()
                        val sb = StringBuilder()
                        for (i in p.start until p.end) backslash(sb, c[i])
                        result(PyStr(sb.toString()), p.end)
                    }
                    isError(exc, DECODE_ERROR) -> {
                        val p = params(exc as PyBaseException, true)
                        val b = p.bytes()
                        val sb = StringBuilder()
                        for (i in p.start until p.end) sb.append("\\x").append(hex2(b[i].toInt() and 0xff))
                        result(PyStr(sb.toString()), p.end)
                    }
                    else -> wrongType(exc)
                }
            },
            "namereplace" to handler("namereplace_errors") { exc ->
                if (!isError(exc, ENCODE_ERROR)) wrongType(exc)
                val p = params(exc as PyBaseException, false)
                val c = p.cps()
                val sb = StringBuilder()
                for (i in p.start until p.end) {
                    val name = charName(c[i])
                    if (name != null) sb.append("\\N{").append(name).append('}') else backslash(sb, c[i])
                }
                result(PyStr(sb.toString()), p.end)
            },
            "surrogatepass" to handler("surrogatepass") { exc ->
                when {
                    isError(exc, ENCODE_ERROR) -> surrogatePassEncode(exc as PyBaseException)
                    isError(exc, DECODE_ERROR) -> surrogatePassDecode(exc as PyBaseException)
                    else -> wrongType(exc)
                }
            },
            "surrogateescape" to handler("surrogateescape") { exc ->
                when {
                    isError(exc, ENCODE_ERROR) -> {
                        val e = exc as PyBaseException
                        val p = params(e, false)
                        val c = p.cps()
                        val out = ByteArray(p.slen)
                        for (i in p.start until p.end) {
                            if (c[i] < 0xdc80 || c[i] > 0xdcff) raise(ctx(), e)
                            out[i - p.start] = (c[i] - 0xdc00).toByte()
                        }
                        result(PyBytes(out), p.end)
                    }
                    isError(exc, DECODE_ERROR) -> {
                        val e = exc as PyBaseException
                        val p = params(e, true)
                        val b = p.bytes()
                        val sb = StringBuilder()
                        var consumed = 0
                        while (consumed < 4 && consumed < p.slen) {
                            val x = b[p.start + consumed].toInt() and 0xff
                            if (x < 128) break
                            sb.append((0xdc00 + x).toChar())
                            consumed++
                        }
                        if (consumed == 0) raise(ctx(), e)
                        result(PyStr(sb.toString()), p.start + consumed)
                    }
                    else -> wrongType(exc)
                }
            },
        )
    }

    private const val ENC_UNKNOWN = -1
    private const val ENC_UTF8 = 0
    private const val ENC_UTF16BE = 1
    private const val ENC_UTF16LE = 2
    private const val ENC_UTF32BE = 3
    private const val ENC_UTF32LE = 4

    /** get_standard_encoding: the UTF codec [enc] names (the native order little-endian), and its unit's size. */
    private fun standardEncoding(enc: String): Pair<Int, Int> {
        fun at(i: Int) = if (i < enc.length) enc[i] else '\u0000'
        fun lower(i: Int) = at(i).lowercaseChar()
        if (lower(0) == 'u' && lower(1) == 't' && lower(2) == 'f') {
            var i = 3
            if (at(i) == '-' || at(i) == '_') i++
            if (at(i) == '8' && i + 1 == enc.length) return ENC_UTF8 to 3
            for ((digits, be, le, n) in listOf(arrayOf<Any>("16", ENC_UTF16BE, ENC_UTF16LE, 2), arrayOf<Any>("32", ENC_UTF32BE, ENC_UTF32LE, 4))) {
                if (at(i) == (digits as String)[0] && at(i + 1) == digits[1]) {
                    i += 2
                    if (i == enc.length) return (le as Int) to (n as Int)
                    if (at(i) == '-' || at(i) == '_') i++
                    if (lower(i + 1) == 'e' && i + 2 == enc.length) {
                        if (lower(i) == 'b') return (be as Int) to (n as Int)
                        if (lower(i) == 'l') return (le as Int) to (n as Int)
                    }
                    return ENC_UNKNOWN to 0
                }
            }
        } else if (enc == "cp65001") {
            return ENC_UTF8 to 3
        }
        return ENC_UNKNOWN to 0
    }

    private fun surrogatePassEncode(e: PyBaseException): Any? {
        val code = standardEncoding(encodingOf(e)).first
        if (code == ENC_UNKNOWN) raise(ctx(), e)
        val p = params(e, false)
        val c = p.cps()
        val out = ByteArrayOutputStream()
        for (i in p.start until p.end) {
            val ch = c[i]
            if (ch !in 0xd800..0xdfff) raise(ctx(), e)
            writeUnit(out, code, ch)
        }
        return result(PyBytes(out.toByteArray()), p.end)
    }

    private fun writeUnit(out: ByteArrayOutputStream, code: Int, ch: Int) {
        when (code) {
            ENC_UTF8 -> {
                out.write(0xe0 or (ch shr 12))
                out.write(0x80 or ((ch shr 6) and 0x3f))
                out.write(0x80 or (ch and 0x3f))
            }
            ENC_UTF16LE -> { out.write(ch and 0xff); out.write((ch shr 8) and 0xff) }
            ENC_UTF16BE -> { out.write((ch shr 8) and 0xff); out.write(ch and 0xff) }
            ENC_UTF32LE -> { out.write(ch and 0xff); out.write((ch shr 8) and 0xff); out.write((ch shr 16) and 0xff); out.write((ch ushr 24) and 0xff) }
            ENC_UTF32BE -> { out.write((ch ushr 24) and 0xff); out.write((ch shr 16) and 0xff); out.write((ch shr 8) and 0xff); out.write(ch and 0xff) }
        }
    }

    private fun surrogatePassDecode(e: PyBaseException): Any? {
        val (code, size) = standardEncoding(encodingOf(e))
        if (code == ENC_UNKNOWN) raise(ctx(), e)
        val p = params(e, true)
        val b = p.bytes()
        val s = p.start
        fun u(i: Int) = b[s + i].toInt() and 0xff
        var ch = 0
        if (p.len - s >= size) {
            ch = when (code) {
                ENC_UTF8 -> if ((u(0) and 0xf0) == 0xe0 && (u(1) and 0xc0) == 0x80 && (u(2) and 0xc0) == 0x80) {
                    ((u(0) and 0x0f) shl 12) + ((u(1) and 0x3f) shl 6) + (u(2) and 0x3f)
                } else 0
                ENC_UTF16LE -> (u(1) shl 8) or u(0)
                ENC_UTF16BE -> (u(0) shl 8) or u(1)
                ENC_UTF32LE -> (u(3) shl 24) or (u(2) shl 16) or (u(1) shl 8) or u(0)
                else -> (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3)
            }
        }
        if (ch !in 0xd800..0xdfff) raise(ctx(), e)
        return result(PyStr(ch.toChar().toString()), s + size)
    }

    // ------------------------------------------------------------------ character names

    /** unicodedata's name of [cp] as namereplace has it (unicodedata's C API: aliases' private-use code points named too), or null. */
    @JvmStatic
    fun charName(cp: Int): String? = PyUnicodedata.name(cp, false, true)

    /** The code point named [name] (case aside), as `\N{...}` finds it (unicodedata's C API: aliases too); -1 for none. */
    @JvmStatic
    fun charCode(name: String): Int = PyUnicodedata.code(name)

    // ------------------------------------------------------------------ the codecs

    /** What a decoder gives: its text, how much of the input it took, and (utf_16_ex_decode's, utf_32_ex_decode's) the byte order. */
    class Decoded(@JvmField val text: String, @JvmField val consumed: Int, @JvmField val byteorder: Int = 0)

    private fun cpsOf(s: String): IntArray = s.codePoints().toArray()

    // UTF-8

    /** _PyUnicode_AsUTF8String: [str] in UTF-8, a run of surrogates handed to [errors]. */
    @JvmStatic
    fun encodeUtf8(ctx: PyContext, str: PyStr, errors: String?): ByteArray {
        val s = str.s
        if (str.bmp || !hasLoneSurrogate(s)) return s.toByteArray(Charsets.UTF_8)
        val c = cpsOf(s)
        val size = c.size
        val out = ByteArrayOutputStream(size * 3)
        var kind = -1
        var ee: EncodeErrors? = null
        var i = 0
        while (i < size) {
            val ch = c[i++]
            when {
                ch < 0x80 -> out.write(ch)
                ch < 0x800 -> { out.write(0xc0 or (ch shr 6)); out.write(0x80 or (ch and 0x3f)) }
                ch in 0xd800..0xdfff -> {
                    if (kind < 0) kind = handlerKind(errors)
                    var startpos = i - 1
                    var endpos = startpos + 1
                    while (endpos < size && c[endpos] in 0xd800..0xdfff) endpos++
                    var other = false
                    when (kind) {
                        H_REPLACE -> { repeat(endpos - startpos) { out.write('?'.code) }; i = endpos }
                        H_IGNORE -> i = endpos
                        H_SURROGATEPASS -> { for (k in startpos until endpos) writeUnit(out, ENC_UTF8, c[k]); i = endpos }
                        H_BACKSLASHREPLACE -> {
                            val sb = StringBuilder()
                            for (k in startpos until endpos) backslash(sb, c[k])
                            ascii(out, sb)
                            i = endpos
                        }
                        H_XMLCHARREFREPLACE -> {
                            for (k in startpos until endpos) ascii(out, "&#${c[k]};")
                            i = endpos
                        }
                        H_SURROGATEESCAPE -> {
                            var k = startpos
                            while (k < endpos) {
                                if (c[k] !in 0xdc80..0xdcff) break
                                out.write(c[k] and 0xff)
                                k++
                            }
                            if (k >= endpos) i = endpos else {
                                startpos = k
                                other = true
                            }
                        }
                        else -> other = true
                    }
                    if (other) {
                        val e = ee ?: EncodeErrors(ctx, "utf-8", errors, str).also { ee = it }
                        val (rep, newpos) = e.call("surrogates not allowed", startpos, endpos, size)
                        if (rep is PyBytes) out.write(rep.b)
                        else {
                            val r = (rep as PyStr).s
                            if (r.any { it.code >= 0x80 }) e.raise("surrogates not allowed", startpos, endpos)
                            ascii(out, r)
                        }
                        i = newpos
                    }
                }
                ch < 0x10000 -> { out.write(0xe0 or (ch shr 12)); out.write(0x80 or ((ch shr 6) and 0x3f)); out.write(0x80 or (ch and 0x3f)) }
                else -> {
                    out.write(0xf0 or (ch shr 18)); out.write(0x80 or ((ch shr 12) and 0x3f))
                    out.write(0x80 or ((ch shr 6) and 0x3f)); out.write(0x80 or (ch and 0x3f))
                }
            }
        }
        return out.toByteArray()
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i += 2
            else if (Character.isSurrogate(c)) return true
            else i++
        }
        return false
    }

    private fun cont(b: Int) = (b and 0xc0) == 0x80

    /** PyUnicode_DecodeUTF8Stateful: [final] false, an incomplete sequence at the end is left (consumed stops before it). */
    @JvmStatic
    fun decodeUtf8(ctx: PyContext, data: ByteArray, errors: String?, final: Boolean): Decoded {
        var b = data
        var end = b.size
        var asciiOnly = true
        for (x in b) if (x < 0) { asciiOnly = false; break }
        if (asciiOnly) return Decoded(String(b, Charsets.ISO_8859_1), b.size)
        val out = StringBuilder(end)
        var kind = -1
        var de: DecodeErrors? = null
        var s = 0
        while (s < end) {
            // (stringlib's utf8_decode: as far as it goes; why it stopped)
            var status = 0
            while (s < end) {
                val ch = b[s].toInt() and 0xff
                if (ch < 0x80) { out.append(ch.toChar()); s++; continue }
                if (ch < 0xe0) {
                    if (ch < 0xc2) { status = 1; break }
                    if (end - s < 2) break
                    val ch2 = b[s + 1].toInt() and 0xff
                    if (!cont(ch2)) { status = 2; break }
                    out.append(((ch shl 6) + ch2 - ((0xc0 shl 6) + 0x80)).toChar())
                    s += 2
                    continue
                }
                if (ch < 0xf0) {
                    if (end - s < 3) {
                        if (end - s < 2) break
                        val ch2 = b[s + 1].toInt() and 0xff
                        if (!cont(ch2) || (if (ch2 < 0xa0) ch == 0xe0 else ch == 0xed)) status = 2
                        break
                    }
                    val ch2 = b[s + 1].toInt() and 0xff
                    val ch3 = b[s + 2].toInt() and 0xff
                    if (!cont(ch2)) { status = 2; break }
                    if (ch == 0xe0) { if (ch2 < 0xa0) { status = 2; break } }
                    else if (ch == 0xed && ch2 >= 0xa0) { status = 2; break }
                    if (!cont(ch3)) { status = 3; break }
                    out.append(((ch shl 12) + (ch2 shl 6) + ch3 - ((0xe0 shl 12) + (0x80 shl 6) + 0x80)).toChar())
                    s += 3
                    continue
                }
                if (ch < 0xf5) {
                    if (end - s < 4) {
                        if (end - s < 2) break
                        val ch2 = b[s + 1].toInt() and 0xff
                        if (!cont(ch2) || (if (ch2 < 0x90) ch == 0xf0 else ch == 0xf4)) { status = 2; break }
                        if (end - s < 3) break
                        if (!cont(b[s + 2].toInt() and 0xff)) status = 3
                        break
                    }
                    val ch2 = b[s + 1].toInt() and 0xff
                    val ch3 = b[s + 2].toInt() and 0xff
                    val ch4 = b[s + 3].toInt() and 0xff
                    if (!cont(ch2)) { status = 2; break }
                    if (ch == 0xf0) { if (ch2 < 0x90) { status = 2; break } }
                    else if (ch == 0xf4 && ch2 >= 0x90) { status = 2; break }
                    if (!cont(ch3)) { status = 3; break }
                    if (!cont(ch4)) { status = 4; break }
                    out.appendCodePoint((ch shl 18) + (ch2 shl 12) + (ch3 shl 6) + ch4 - ((0xf0 shl 18) + (0x80 shl 12) + (0x80 shl 6) + 0x80))
                    s += 4
                    continue
                }
                status = 1
                break
            }
            if (s >= end && status == 0) break
            val errmsg: String
            val startinpos: Int
            val endinpos: Int
            when (status) {
                0 -> {
                    if (!final) break
                    errmsg = "unexpected end of data"
                    startinpos = s
                    endinpos = end
                }
                1 -> {
                    errmsg = "invalid start byte"
                    startinpos = s
                    endinpos = s + 1
                }
                else -> {
                    if (status == 2 && !final && (b[s].toInt() and 0xff) == 0xed && end - s == 2 && (b[s + 1].toInt() and 0xff) in 0xa0..0xbf) break
                    errmsg = "invalid continuation byte"
                    startinpos = s
                    endinpos = s + status - 1
                }
            }
            if (kind < 0) kind = handlerKind(errors)
            when (kind) {
                H_IGNORE -> s += endinpos - startinpos
                H_REPLACE -> { out.append('\ufffd'); s += endinpos - startinpos }
                H_SURROGATEESCAPE -> {
                    for (k in startinpos until endinpos) out.append((0xdc00 + (b[k].toInt() and 0xff)).toChar())
                    s += endinpos - startinpos
                }
                else -> {
                    val e = de ?: DecodeErrors(ctx, "utf-8", errors, b).also { de = it }
                    s = e.call(errmsg, startinpos, endinpos, out)
                    b = e.input
                    end = b.size
                }
            }
        }
        return Decoded(out.toString(), if (final) data.size else s)
    }

    // ASCII and Latin-1

    /** unicode_encode_ucs1: [str] in ASCII ([limit] 128) or Latin-1 (256), a run of characters past it handed to [errors]. */
    @JvmStatic
    fun encodeUcs1(ctx: PyContext, str: PyStr, errors: String?, limit: Int): ByteArray {
        val s = str.s
        if (str.bmp && s.all { it.code < limit }) return s.toByteArray(Charsets.ISO_8859_1)
        val encoding = if (limit == 256) "latin-1" else "ascii"
        val reason = if (limit == 256) "ordinal not in range(256)" else "ordinal not in range(128)"
        val c = cpsOf(s)
        val size = c.size
        val out = ByteArrayOutputStream(size)
        var kind = -1
        var ee: EncodeErrors? = null
        var pos = 0
        while (pos < size) {
            val ch = c[pos]
            if (ch < limit) {
                out.write(ch)
                pos++
                continue
            }
            var collstart = pos
            var collend = collstart + 1
            while (collend < size && c[collend] >= limit) collend++
            if (kind < 0) kind = handlerKind(errors)
            var other = false
            when (kind) {
                H_STRICT -> (ee ?: EncodeErrors(ctx, encoding, errors, str).also { ee = it }).raise(reason, collstart, collend)
                H_REPLACE -> { repeat(collend - collstart) { out.write('?'.code) }; pos = collend }
                H_IGNORE -> pos = collend
                H_BACKSLASHREPLACE -> {
                    val sb = StringBuilder()
                    for (k in collstart until collend) backslash(sb, c[k])
                    ascii(out, sb)
                    pos = collend
                }
                H_XMLCHARREFREPLACE -> {
                    for (k in collstart until collend) ascii(out, "&#${c[k]};")
                    pos = collend
                }
                H_SURROGATEESCAPE -> {
                    var k = collstart
                    while (k < collend) {
                        if (c[k] !in 0xdc80..0xdcff) break
                        out.write(c[k] - 0xdc00)
                        pos++
                        k++
                    }
                    if (k < collend) {
                        collstart = pos
                        other = true
                    }
                }
                else -> other = true
            }
            if (other) {
                val e = ee ?: EncodeErrors(ctx, encoding, errors, str).also { ee = it }
                val (rep, newpos) = e.call(reason, collstart, collend, size)
                if (rep is PyBytes) out.write(rep.b)
                else {
                    val r = (rep as PyStr).s
                    if (r.any { it.code >= limit }) e.raise(reason, collstart, collend)
                    for (x in r) out.write(x.code)
                }
                pos = newpos
            }
        }
        return out.toByteArray()
    }

    /** PyUnicode_DecodeASCII */
    @JvmStatic
    fun decodeAscii(ctx: PyContext, data: ByteArray, errors: String?): String {
        var b = data
        var e = b.size
        var s = 0
        while (s < e && b[s] >= 0) s++
        if (s == e) return String(b, Charsets.ISO_8859_1)
        val out = StringBuilder(e)
        out.append(String(b, 0, s, Charsets.ISO_8859_1))
        var kind = -1
        var de: DecodeErrors? = null
        while (s < e) {
            val c = b[s].toInt() and 0xff
            if (c < 128) {
                out.append(c.toChar())
                s++
                continue
            }
            if (kind < 0) kind = handlerKind(errors)
            when (kind) {
                H_REPLACE -> { out.append('\ufffd'); s++ }
                H_SURROGATEESCAPE -> { out.append((c + 0xdc00).toChar()); s++ }
                H_IGNORE -> s++
                else -> {
                    val d = de ?: DecodeErrors(ctx, "ascii", errors, b).also { de = it }
                    s = d.call("ordinal not in range(128)", s, s + 1, out)
                    b = d.input
                    e = b.size
                }
            }
        }
        return out.toString()
    }

    // UTF-16 and UTF-32

    /** _PyUnicode_EncodeUTF16: [byteorder] -1 little-endian, 1 big-endian, 0 the native order (little) after a BOM. */
    @JvmStatic
    fun encodeUtf16(ctx: PyContext, str: PyStr, errors: String?, byteorder: Int): ByteArray =
        encodeUtfN(ctx, str, errors, byteorder, 2, if (byteorder < 0) "utf-16-le" else if (byteorder > 0) "utf-16-be" else "utf-16")

    /** _PyUnicode_EncodeUTF32 */
    @JvmStatic
    fun encodeUtf32(ctx: PyContext, str: PyStr, errors: String?, byteorder: Int): ByteArray =
        encodeUtfN(ctx, str, errors, byteorder, 4, if (byteorder == -1) "utf-32-le" else if (byteorder == 1) "utf-32-be" else "utf-32")

    private fun encodeUtfN(ctx: PyContext, str: PyStr, errors: String?, byteorder: Int, unit: Int, encoding: String): ByteArray {
        val le = byteorder <= 0
        val out = ByteArrayOutputStream(str.s.length * unit + unit)
        fun put(v: Int) {
            if (unit == 2) {
                if (le) { out.write(v and 0xff); out.write((v shr 8) and 0xff) } else { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
            } else {
                if (le) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v ushr 24) and 0xff) }
                else { out.write((v ushr 24) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 8) and 0xff); out.write(v and 0xff) }
            }
        }
        if (byteorder == 0) put(0xfeff)
        val c = cpsOf(str.s)
        val len = c.size
        var ee: EncodeErrors? = null
        var pos = 0
        while (pos < len) {
            val ch = c[pos]
            if (ch !in 0xd800..0xdfff) {
                if (unit == 2 && ch >= 0x10000) {
                    put(0xd800 or ((ch - 0x10000) shr 10))
                    put(0xdc00 or ((ch - 0x10000) and 0x3ff))
                } else put(ch)
                pos++
                continue
            }
            val e = ee ?: EncodeErrors(ctx, encoding, errors, str).also { ee = it }
            val (rep, newpos) = e.call("surrogates not allowed", pos, pos + 1, len)
            if (rep is PyBytes) {
                if (rep.b.size % unit != 0) e.raise("surrogates not allowed", pos, pos + 1)
                out.write(rep.b)
            } else {
                val r = (rep as PyStr).s
                if (r.any { it.code >= 0x80 }) e.raise("surrogates not allowed", pos, pos + 1)
                for (x in r) put(x.code)
            }
            pos = newpos
        }
        return out.toByteArray()
    }

    /**
     * PyUnicode_DecodeUTF16Stateful: [byteorder] 0 reads a BOM (which says the order, and is left out), -1 or 1 the
     * order; [final] false, an odd byte or a high surrogate at the end is left. The byte order in effect at the end
     * comes back (utf_16_ex_decode).
     */
    @JvmStatic
    fun decodeUtf16(ctx: PyContext, data: ByteArray, errors: String?, byteorder: Int, final: Boolean): Decoded {
        var b = data
        val size = b.size
        var q = 0
        var e = size
        var bo = byteorder
        if (bo == 0 && size >= 2) {
            val bom = ((b[1].toInt() and 0xff) shl 8) or (b[0].toInt() and 0xff)
            if (bom == 0xfeff) { q = 2; bo = -1 }
            else if (bom == 0xfffe) { q = 2; bo = 1 }
        }
        if (q == e) return Decoded("", size, bo)
        val le = bo <= 0
        val encoding = if (le) "utf-16-le" else "utf-16-be"
        val out = StringBuilder((e - q + 1) / 2)
        var de: DecodeErrors? = null
        fun unit(i: Int): Int = if (le) ((b[i + 1].toInt() and 0xff) shl 8) or (b[i].toInt() and 0xff)
            else ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        while (true) {
            var status = 0
            while (e - q >= 2) {
                val ch = unit(q)
                q += 2
                if (ch !in 0xd800..0xdfff) { out.append(ch.toChar()); continue }
                if (ch >= 0xdc00) { status = 2; break }
                if (e - q < 2) { status = 1; break }
                val ch2 = unit(q)
                q += 2
                if (ch2 !in 0xdc00..0xdfff) { status = 3; break }
                out.appendCodePoint(0x10000 + ((ch - 0xd800) shl 10) + (ch2 - 0xdc00))
            }
            val errmsg: String
            val startinpos: Int
            val endinpos: Int
            when (status) {
                0 -> {
                    if (q == e || !final) break
                    errmsg = "truncated data"
                    startinpos = q
                    endinpos = e
                }
                1 -> {
                    q -= 2
                    if (!final) break
                    errmsg = "unexpected end of data"
                    startinpos = q
                    endinpos = e
                }
                2 -> {
                    errmsg = "illegal encoding"
                    startinpos = q - 2
                    endinpos = startinpos + 2
                }
                else -> {
                    errmsg = "illegal UTF-16 surrogate"
                    startinpos = q - 4
                    endinpos = startinpos + 2
                }
            }
            val d = de ?: DecodeErrors(ctx, encoding, errors, b).also { de = it }
            q = d.call(errmsg, startinpos, endinpos, out)
            b = d.input
            e = b.size
        }
        return Decoded(out.toString(), if (final) size else q, bo)
    }

    /** PyUnicode_DecodeUTF32Stateful, as decodeUtf16. */
    @JvmStatic
    fun decodeUtf32(ctx: PyContext, data: ByteArray, errors: String?, byteorder: Int, final: Boolean): Decoded {
        var b = data
        val size = b.size
        var q = 0
        var e = size
        var bo = byteorder
        fun u(i: Int) = b[i].toLong() and 0xff
        if (bo == 0 && size >= 4) {
            val bom = (u(3) shl 24) or (u(2) shl 16) or (u(1) shl 8) or u(0)
            if (bom == 0x0000feffL) { bo = -1; q = 4 }
            else if (bom == 0xfffe0000L) { bo = 1; q = 4 }
        }
        if (q == e) return Decoded("", size, bo)
        val le = bo <= 0
        val encoding = if (le) "utf-32-le" else "utf-32-be"
        val out = StringBuilder((e - q + 3) / 4)
        var de: DecodeErrors? = null
        while (true) {
            val errmsg: String
            val startinpos: Int
            val endinpos: Int
            if (e - q >= 4) {
                val ch = if (le) (u(q + 3) shl 24) or (u(q + 2) shl 16) or (u(q + 1) shl 8) or u(q)
                    else (u(q) shl 24) or (u(q + 1) shl 16) or (u(q + 2) shl 8) or u(q + 3)
                if (ch in 0xd800L..0xdfffL) {
                    errmsg = "code point in surrogate code point range(0xd800, 0xe000)"
                    startinpos = q
                    endinpos = q + 4
                } else if (ch < 0x110000L) {
                    out.appendCodePoint(ch.toInt())
                    q += 4
                    continue
                } else {
                    errmsg = "code point not in range(0x110000)"
                    startinpos = q
                    endinpos = q + 4
                }
            } else {
                if (q == e || !final) break
                errmsg = "truncated data"
                startinpos = q
                endinpos = e
            }
            val d = de ?: DecodeErrors(ctx, encoding, errors, b).also { de = it }
            q = d.call(errmsg, startinpos, endinpos, out)
            b = d.input
            e = b.size
        }
        return Decoded(out.toString(), if (final) size else q, bo)
    }

    // UTF-7

    private val UTF7_CATEGORY = intArrayOf(
        3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 3, 3, 2, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 3, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0,
        1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 1, 1, 1,
        1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 3, 3,
    )
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun isBase64(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '+'.code || c == '/'.code
    private fun fromBase64(c: Int) = when (c) {
        in 'A'.code..'Z'.code -> c - 'A'.code
        in 'a'.code..'z'.code -> c - 'a'.code + 26
        in '0'.code..'9'.code -> c - '0'.code + 52
        '+'.code -> 62
        else -> 63
    }
    private fun encodeDirect(c: Int, directO: Boolean, directWS: Boolean) =
        c in 1..127 && (UTF7_CATEGORY[c] == 0 || directWS && UTF7_CATEGORY[c] == 2 || directO && UTF7_CATEGORY[c] == 1)
    private fun decodeDirect(c: Int) = c <= 127 && c != '+'.code

    /** _PyUnicode_EncodeUTF7 (Set O and white space direct). */
    @JvmStatic
    fun encodeUtf7(str: PyStr): ByteArray {
        val c = cpsOf(str.s)
        val out = ByteArrayOutputStream(c.size * 2)
        var inShift = false
        var bits = 0
        var buf = 0L
        fun encodeChar(ch0: Int) {
            var ch = ch0
            if (ch >= 0x10000) {
                bits += 16
                buf = (buf shl 16) or (0xd800 or ((ch - 0x10000) shr 10)).toLong()
                while (bits >= 6) { out.write(B64[((buf ushr (bits - 6)) and 0x3f).toInt()].code); bits -= 6 }
                ch = 0xdc00 or ((ch - 0x10000) and 0x3ff)
            }
            bits += 16
            buf = (buf shl 16) or ch.toLong()
            while (bits >= 6) { out.write(B64[((buf ushr (bits - 6)) and 0x3f).toInt()].code); bits -= 6 }
        }
        for (ch in c) {
            if (inShift) {
                if (encodeDirect(ch, true, true)) {
                    if (bits > 0) {
                        out.write(B64[((buf shl (6 - bits)) and 0x3f).toInt()].code)
                        buf = 0
                        bits = 0
                    }
                    inShift = false
                    if (isBase64(ch) || ch == '-'.code) out.write('-'.code)
                    out.write(ch)
                } else encodeChar(ch)
            } else if (ch == '+'.code) {
                out.write('+'.code)
                out.write('-'.code)
            } else if (encodeDirect(ch, true, true)) {
                out.write(ch)
            } else {
                out.write('+'.code)
                inShift = true
                encodeChar(ch)
            }
        }
        if (bits > 0) out.write(B64[((buf shl (6 - bits)) and 0x3f).toInt()].code)
        if (inShift) out.write('-'.code)
        return out.toByteArray()
    }

    /** PyUnicode_DecodeUTF7Stateful: [final] false, a shift sequence not ended is left (its output with it). */
    @JvmStatic
    fun decodeUtf7(ctx: PyContext, data: ByteArray, errors: String?, final: Boolean): Decoded {
        val size = data.size
        if (size == 0) return Decoded("", 0)
        var b = data
        var e = size
        var s = 0
        val out = StringBuilder(size)
        var inShift = false
        var shiftOutStart = 0
        var bits = 0
        var buf = 0L
        var surrogate = 0
        var startinpos = 0
        var de: DecodeErrors? = null
        fun error(msg: String, start: Int, end: Int) {
            val d = de ?: DecodeErrors(ctx, "utf7", errors, b).also { de = it }
            s = d.call(msg, start, end, out)
            b = d.input
            e = b.size
        }
        outer@ while (true) {
            while (s < e) {
                val ch = b[s].toInt() and 0xff
                var errmsg: String? = null
                if (inShift) {
                    if (isBase64(ch)) {
                        buf = (buf shl 6) or fromBase64(ch).toLong()
                        bits += 6
                        s++
                        if (bits >= 16) {
                            val outCh = ((buf ushr (bits - 16)) and 0xffff).toInt()
                            bits -= 16
                            buf = buf and ((1L shl bits) - 1)
                            if (surrogate != 0) {
                                if (outCh in 0xdc00..0xdfff) {
                                    out.appendCodePoint(0x10000 + ((surrogate - 0xd800) shl 10) + (outCh - 0xdc00))
                                    surrogate = 0
                                    continue
                                }
                                out.append(surrogate.toChar())
                                surrogate = 0
                            }
                            if (outCh in 0xd800..0xdbff) surrogate = outCh else out.append(outCh.toChar())
                        }
                        continue
                    }
                    inShift = false
                    if (bits > 0) {
                        if (bits >= 6) {
                            s++
                            errmsg = "partial character in shift sequence"
                        } else if (buf != 0L) {
                            s++
                            errmsg = "non-zero padding bits in shift sequence"
                        }
                    }
                    if (errmsg == null) {
                        if (surrogate != 0 && decodeDirect(ch)) out.append(surrogate.toChar())
                        surrogate = 0
                        if (ch == '-'.code) s++
                        continue
                    }
                } else if (ch == '+'.code) {
                    startinpos = s
                    s++
                    if (s < e && b[s] == '-'.code.toByte()) {
                        s++
                        out.append('+')
                        continue
                    } else if (s < e && !isBase64(b[s].toInt() and 0xff)) {
                        s++
                        errmsg = "ill-formed sequence"
                    } else {
                        inShift = true
                        surrogate = 0
                        shiftOutStart = out.length
                        bits = 0
                        buf = 0
                        continue
                    }
                } else if (decodeDirect(ch)) {
                    s++
                    out.append(ch.toChar())
                    continue
                } else {
                    startinpos = s
                    s++
                    errmsg = "unexpected special character"
                }
                error(errmsg!!, startinpos, s)
            }
            if (inShift && final) {
                inShift = false
                if (surrogate != 0 || bits >= 6 || bits > 0 && buf != 0L) {
                    error("unterminated shift sequence", startinpos, size)
                    if (s < e) continue@outer
                }
            }
            break
        }
        if (final) return Decoded(out.toString(), size)
        if (inShift) {
            out.setLength(shiftOutStart)
            return Decoded(out.toString(), startinpos)
        }
        return Decoded(out.toString(), s)
    }

    // unicode_escape and raw_unicode_escape

    /** PyUnicode_AsUnicodeEscapeString */
    @JvmStatic
    fun encodeUnicodeEscape(str: PyStr): ByteArray {
        val c = cpsOf(str.s)
        val sb = StringBuilder(c.size)
        for (ch in c) {
            when {
                ch < 0x100 -> when {
                    ch >= ' '.code && ch < 127 -> if (ch != '\\'.code) sb.append(ch.toChar()) else sb.append("\\\\")
                    ch == '\t'.code -> sb.append("\\t")
                    ch == '\n'.code -> sb.append("\\n")
                    ch == '\r'.code -> sb.append("\\r")
                    else -> sb.append("\\x").append(hex2(ch))
                }
                else -> backslash(sb, ch)
            }
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** PyUnicode_AsRawUnicodeEscapeString */
    @JvmStatic
    fun encodeRawUnicodeEscape(str: PyStr): ByteArray {
        val c = cpsOf(str.s)
        val out = ByteArrayOutputStream(c.size)
        for (ch in c) {
            if (ch < 0x100) out.write(ch)
            else {
                val sb = StringBuilder()
                backslash(sb, ch)
                ascii(out, sb)
            }
        }
        return out.toByteArray()
    }

    private fun hexDigit(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    /**
     * _PyUnicode_DecodeUnicodeEscapeStateful: Python's escapes (`\N{...}` by the names [charCode] knows); [final]
     * false, an escape cut off at the end is left. An escape Python has not (`\q`, an octal one past 0o377): a
     * DeprecationWarning, as CPython's.
     */
    @JvmStatic
    fun decodeUnicodeEscape(ctx: PyContext, data: ByteArray, errors: String?, final: Boolean): Decoded {
        val size = data.size
        if (size == 0) return Decoded("", 0)
        var b = data
        var end = size
        var s = 0
        val out = StringBuilder(size)
        var de: DecodeErrors? = null
        var firstInvalid = -1
        var consumed = -1
        while (s < end) {
            var c = b[s++].toInt() and 0xff
            if (c != '\\'.code) {
                out.append(c.toChar())
                continue
            }
            val startinpos = s - 1
            var message: String
            var incomplete = false
            if (s >= end) {
                message = "\\ at end of string"
                incomplete = true
            } else {
                c = b[s++].toInt() and 0xff
                when (c.toChar()) {
                    '\n' -> continue
                    '\\' -> { out.append('\\'); continue }
                    '\'' -> { out.append('\''); continue }
                    '"' -> { out.append('"'); continue }
                    'b' -> { out.append('\b'); continue }
                    'f' -> { out.append('\u000c'); continue }
                    't' -> { out.append('\t'); continue }
                    'n' -> { out.append('\n'); continue }
                    'r' -> { out.append('\r'); continue }
                    'v' -> { out.append('\u000b'); continue }
                    'a' -> { out.append('\u0007'); continue }
                    in '0'..'7' -> {
                        var ch = c - '0'.code
                        if (s < end && b[s] >= '0'.code.toByte() && b[s] <= '7'.code.toByte()) {
                            ch = (ch shl 3) + b[s++] - '0'.code
                            if (s < end && b[s] >= '0'.code.toByte() && b[s] <= '7'.code.toByte()) ch = (ch shl 3) + b[s++] - '0'.code
                        }
                        if (ch > 0xff && firstInvalid == -1) firstInvalid = ch
                        out.appendCodePoint(ch)
                        continue
                    }
                    'x', 'u', 'U', 'N' -> {}
                    else -> {
                        if (firstInvalid == -1) firstInvalid = c
                        out.append('\\').append(c.toChar())
                        continue
                    }
                }
                if (c == 'N'.code) {
                    message = "malformed \\N character escape"
                    if (s >= end) incomplete = true
                    else if (b[s] == '{'.code.toByte()) {
                        val start = ++s
                        while (s < end && b[s] != '}'.code.toByte()) s++
                        if (s >= end) incomplete = true
                        else if (s > start) {
                            val name = String(b, start, s - start, Charsets.ISO_8859_1)
                            s++
                            val ch = charCode(name)
                            if (ch >= 0) {
                                out.appendCodePoint(ch)
                                continue
                            }
                            message = "unknown Unicode character name"
                        }
                    }
                } else {
                    var count = when (c.toChar()) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    message = when (count) { 2 -> "truncated \\xXX escape"; 4 -> "truncated \\uXXXX escape"; else -> "truncated \\UXXXXXXXX escape" }
                    var ch = 0L
                    var bad = false
                    while (count > 0) {
                        if (s >= end) { incomplete = true; break }
                        val d = hexDigit(b[s].toInt() and 0xff)
                        if (d < 0) { bad = true; break }
                        ch = (ch shl 4) + d
                        s++
                        count--
                    }
                    if (!incomplete && !bad) {
                        if (ch > MAX_UNICODE) message = "illegal Unicode character"
                        else {
                            out.appendCodePoint(ch.toInt())
                            continue
                        }
                    }
                }
            }
            if (incomplete && !final) {
                consumed = startinpos
                break
            }
            val d = de ?: DecodeErrors(ctx, "unicodeescape", errors, b).also { de = it }
            s = d.call(message, startinpos, s, out)
            b = d.input
            end = b.size
        }
        if (firstInvalid != -1) {
            warn(ctx, "DeprecationWarning", if (firstInvalid > 0xff) "\"\\${Integer.toOctalString(firstInvalid)}\" is an invalid octal escape sequence. Such sequences will not work in the future. "
                else "\"\\${firstInvalid.toChar()}\" is an invalid escape sequence. Such sequences will not work in the future. ")
        }
        return Decoded(out.toString(), if (final) size else if (consumed >= 0) consumed else size)
    }

    /** _PyUnicode_DecodeRawUnicodeEscapeStateful: `\uXXXX` and `\UXXXXXXXX` only. */
    @JvmStatic
    fun decodeRawUnicodeEscape(ctx: PyContext, data: ByteArray, errors: String?, final: Boolean): Decoded {
        val size = data.size
        if (size == 0) return Decoded("", 0)
        var b = data
        var end = size
        var s = 0
        val out = StringBuilder(size)
        var de: DecodeErrors? = null
        var consumed = -1
        while (s < end) {
            var c = b[s++].toInt() and 0xff
            if (c != '\\'.code || s >= end && final) {
                out.append(c.toChar())
                continue
            }
            val startinpos = s - 1
            var message = "\\ at end of string"
            var incomplete = false
            if (s >= end) incomplete = true
            else {
                c = b[s++].toInt() and 0xff
                var count: Int
                if (c == 'u'.code) {
                    count = 4
                    message = "truncated \\uXXXX escape"
                } else if (c == 'U'.code) {
                    count = 8
                    message = "truncated \\UXXXXXXXX escape"
                } else {
                    out.append('\\').append(c.toChar())
                    continue
                }
                var ch = 0L
                var bad = false
                while (count > 0) {
                    if (s >= end) { incomplete = true; break }
                    val d = hexDigit(b[s].toInt() and 0xff)
                    if (d < 0) { bad = true; break }
                    ch = (ch shl 4) + d
                    s++
                    count--
                }
                if (!incomplete && !bad) {
                    if (ch > MAX_UNICODE) message = "\\Uxxxxxxxx out of range"
                    else {
                        out.appendCodePoint(ch.toInt())
                        continue
                    }
                }
            }
            if (incomplete && !final) {
                consumed = startinpos
                break
            }
            val d = de ?: DecodeErrors(ctx, "rawunicodeescape", errors, b).also { de = it }
            s = d.call(message, startinpos, s, out)
            b = d.input
            end = b.size
        }
        return Decoded(out.toString(), if (final) size else if (consumed >= 0) consumed else size)
    }

    // escape (bytes to bytes)

    /** _codecs.escape_encode's: bytes as a bytes literal writes them (no quotes). */
    @JvmStatic
    fun escapeEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        for (x in data) {
            val c = x.toInt() and 0xff
            when {
                c == '\''.code || c == '\\'.code -> { out.write('\\'.code); out.write(c) }
                c == '\t'.code -> ascii(out, "\\t")
                c == '\n'.code -> ascii(out, "\\n")
                c == '\r'.code -> ascii(out, "\\r")
                c < ' '.code || c >= 0x7f -> ascii(out, "\\x" + hex2(c))
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    /** PyBytes_DecodeEscape: a bytes literal's escapes; `\x` without two hex digits as [errors] says (strict, replace, ignore). */
    @JvmStatic
    fun escapeDecode(ctx: PyContext, data: ByteArray, errors: String?): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        val end = data.size
        var s = 0
        var firstInvalid = -1
        while (s < end) {
            if (data[s] != '\\'.code.toByte()) {
                out.write(data[s++].toInt())
                continue
            }
            s++
            if (s == end) throw PyException("ValueError", "Trailing \\ in string")
            val c = data[s++].toInt() and 0xff
            when (c.toChar()) {
                '\n' -> {}
                '\\' -> out.write('\\'.code)
                '\'' -> out.write('\''.code)
                '"' -> out.write('"'.code)
                'b' -> out.write(8)
                'f' -> out.write(12)
                't' -> out.write(9)
                'n' -> out.write(10)
                'r' -> out.write(13)
                'v' -> out.write(11)
                'a' -> out.write(7)
                in '0'..'7' -> {
                    var v = c - '0'.code
                    if (s < end && data[s] >= '0'.code.toByte() && data[s] <= '7'.code.toByte()) {
                        v = (v shl 3) + data[s++] - '0'.code
                        if (s < end && data[s] >= '0'.code.toByte() && data[s] <= '7'.code.toByte()) v = (v shl 3) + data[s++] - '0'.code
                    }
                    if (v > 0xff && firstInvalid == -1) firstInvalid = v
                    out.write(v and 0xff)
                }
                'x' -> {
                    if (s + 1 < end) {
                        val d1 = hexDigit(data[s].toInt() and 0xff)
                        val d2 = hexDigit(data[s + 1].toInt() and 0xff)
                        if (d1 >= 0 && d2 >= 0) {
                            out.write((d1 shl 4) + d2)
                            s += 2
                            continue
                        }
                    }
                    when (errors) {
                        null, "strict" -> throw PyException("ValueError", "invalid \\x escape at position ${s - 2}")
                        "replace" -> out.write('?'.code)
                        "ignore" -> {}
                        else -> throw PyException("ValueError", "decoding error; unknown error handling code: $errors")
                    }
                    if (s < end && hexDigit(data[s].toInt() and 0xff) >= 0) s++
                }
                else -> {
                    if (firstInvalid == -1) firstInvalid = c
                    out.write('\\'.code)
                    s--
                }
            }
        }
        if (firstInvalid != -1) {
            warn(ctx, "DeprecationWarning", if (firstInvalid > 0xff) "b\"\\${Integer.toOctalString(firstInvalid)}\" is an invalid octal escape sequence. Such sequences will not work in the future. "
                else "b\"\\${firstInvalid.toChar()}\" is an invalid escape sequence. Such sequences will not work in the future. ")
        }
        return out.toByteArray()
    }

    // charmap

    @JvmField val ENCODING_MAP: PyType = PyTypes.newBuiltin("EncodingMap", subclassable = false).also { t ->
        t.def("size", PyBuiltin("size") { _, a ->
            val m = a.getOrNull(0) as? EncodingMap ?: throw typeError("descriptor 'size' for 'EncodingMap' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
            // (CPython's struct's size: 64 bytes, less the one of level23 counted in its tables)
            (63 + 16 * m.count2 + 128 * m.count3).toLong()
        })
    }

    /** PyUnicode_BuildEncodingMap's three-level trie (a decoding table's inverse, for code points up to U+FFFF). */
    class EncodingMap(@JvmField val level1: IntArray, @JvmField val count2: Int, @JvmField val count3: Int, @JvmField val level23: IntArray) : PyObject(ENCODING_MAP) {
        fun lookup(c: Int): Int {
            if (c > 0xffff) return -1
            if (c == 0) return 0
            var i = level1[c shr 11]
            if (i == 0xff) return -1
            i = level23[16 * i + ((c shr 7) and 0xf)]
            if (i == 0xff) return -1
            i = level23[16 * count2 + 128 * i + (c and 0x7f)]
            return if (i == 0) -1 else i
        }
    }

    /** PyUnicode_BuildEncodingMap: an EncodingMap of the decoding table [map], or a dict where one does not do. */
    @JvmStatic
    fun buildEncodingMap(map: String): Any {
        val c = cpsOf(map)
        val length = minOf(c.size, 256)
        val level1 = IntArray(32) { 0xff }
        val level2 = IntArray(512) { 0xff }
        var count2 = 0
        var count3 = 0
        var needDict = c[0] != 0
        for (i in 1 until length) {
            val ch = c[i]
            if (ch == 0 || ch > 0xffff) { needDict = true; break }
            if (ch == 0xfffe) continue
            if (level1[ch shr 11] == 0xff) level1[ch shr 11] = count2++
            if (level2[ch shr 7] == 0xff) level2[ch shr 7] = count3++
        }
        if (count2 >= 0xff || count3 >= 0xff) needDict = true
        if (needDict) {
            val d = PyDict()
            for (i in 0 until length) d[c[i].toLong()] = i.toLong()
            return d
        }
        val level23 = IntArray(16 * count2 + 128 * count3)
        java.util.Arrays.fill(level23, 0, 16 * count2, 0xff)
        count3 = 0
        for (i in 1 until length) {
            val ch = c[i]
            if (ch == 0xfffe) continue
            val i2 = 16 * level1[ch shr 11] + ((ch shr 7) and 0xf)
            if (level23[i2] == 0xff) level23[i2] = count3++
            level23[16 * count2 + 128 * level23[i2] + (ch and 0x7f)] = i
        }
        return EncodingMap(level1, count2, count3, level23)
    }

    /** PyMapping_GetOptionalItem: mapping[key], or null where it has none (a KeyError, an IndexError: LookupErrors). */
    private fun mapItem(mapping: Any?, key: Long): Any? = try {
        PyRt.getItem(mapping, key)
    } catch (e: PyException) {
        if (!e.matches(PyExceptions.type("LookupError"))) throw e
        null
    }

    /** PyUnicode_DecodeCharmap: [mapping] a str (an exact one: a table) or a mapping of bytes to code points or strs; None Latin-1. */
    @JvmStatic
    fun decodeCharmap(ctx: PyContext, data: ByteArray, errors: String?, mapping: Any?): String {
        if (mapping == null || mapping === PyNone) return String(data, Charsets.ISO_8859_1)
        if (data.isEmpty()) return ""
        var b = data
        var e = b.size
        var s = 0
        val out = StringBuilder(e)
        var de: DecodeErrors? = null
        val table = if (mapping is PyStr && mapping.sub == null) cpsOf(mapping.s) else null
        while (s < e) {
            val ch = b[s].toInt() and 0xff
            var undefined = false
            if (table != null) {
                val x = if (ch < table.size) table[ch] else 0xfffe
                if (x == 0xfffe) undefined = true else out.appendCodePoint(x)
            } else {
                val item = mapItem(mapping, ch.toLong())
                val value = cLong(item)
                when {
                    item == null || item === PyNone -> undefined = true
                    value != null -> {
                        if (value == 0xfffeL) undefined = true
                        else if (value < 0 || value > MAX_UNICODE) throw typeError("character mapping must be in range(0x110000)")
                        else out.appendCodePoint(value.toInt())
                    }
                    item is PyStr -> {
                        if (item.length == 1) {
                            val v = item.s.codePointAt(0)
                            if (v == 0xfffe) undefined = true else out.appendCodePoint(v)
                        } else out.append(item.s)
                    }
                    else -> throw typeError("character mapping must return integer, None or str")
                }
            }
            if (!undefined) {
                s++
                continue
            }
            val d = de ?: DecodeErrors(ctx, "charmap", errors, b).also { de = it }
            s = d.call("character maps to <undefined>", s, s + 1, out)
            b = d.input
            e = b.size
        }
        return out.toString()
    }

    /** An int (PyLong_Check's: a bool, a subclass's instance) as a C long (PyLong_AsLong), or null for anything else. */
    private fun cLong(v: Any?): Long? {
        val n = when (v) {
            is Long, is BigInteger -> v
            is PyBool -> if (v.value) 1L else 0L
            is PyNumSub -> v.num.takeIf { it is Long || it is BigInteger }
            else -> null
        } ?: return null
        return n as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
    }

    /** charmapencode_lookup: a byte (0..255), bytes, or null (undefined). */
    private fun charmapLookup(c: Int, mapping: Any?): Any? {
        val x = mapItem(mapping, c.toLong()) ?: return null
        if (x === PyNone) return null
        cLong(x)?.let { v ->
            if (v < 0 || v > 255) throw typeError("character mapping must be in range(256)")
            return v.toInt()
        }
        bytesOf(x)?.let { return it }
        throw typeError("character mapping must return integer, bytes or None, not ${PyTypes.name(x)}")
    }

    /** charmapencode_output: [c] written as [mapping] has it, or false (undefined). */
    private fun charmapOutput(c: Int, mapping: Any?, out: ByteArrayOutputStream): Boolean {
        if (mapping is EncodingMap) {
            val r = mapping.lookup(c)
            if (r == -1) return false
            out.write(r)
            return true
        }
        return when (val r = charmapLookup(c, mapping)) {
            null -> false
            is Int -> { out.write(r); true }
            else -> { out.write(r as ByteArray); true }
        }
    }

    /** _PyUnicode_EncodeCharmap: [mapping] an EncodingMap or a mapping of code points to bytes; None Latin-1. */
    @JvmStatic
    fun encodeCharmap(ctx: PyContext, str: PyStr, errors: String?, mapping: Any?): ByteArray {
        if (mapping == null || mapping === PyNone) return encodeUcs1(ctx, str, errors, 256)
        val c = cpsOf(str.s)
        val size = c.size
        val out = ByteArrayOutputStream(size)
        val encoding = "charmap"
        val reason = "character maps to <undefined>"
        var kind = -1
        var ee: EncodeErrors? = null
        fun errs() = ee ?: EncodeErrors(ctx, encoding, errors, str).also { ee = it }
        var inpos = 0
        while (inpos < size) {
            if (charmapOutput(c[inpos], mapping, out)) {
                inpos++
                continue
            }
            // (charmap_encoding_error: the run of characters it has not)
            val collstart = inpos
            var collend = inpos + 1
            while (collend < size) {
                val defined = if (mapping is EncodingMap) mapping.lookup(c[collend]) != -1 else charmapLookup(c[collend], mapping) != null
                if (defined) break
                collend++
            }
            if (kind < 0) kind = handlerKind(errors)
            when (kind) {
                H_STRICT -> errs().raise(reason, collstart, collend)
                H_REPLACE -> {
                    for (k in collstart until collend) if (!charmapOutput('?'.code, mapping, out)) errs().raise(reason, collstart, collend)
                    inpos = collend
                }
                H_IGNORE -> inpos = collend
                H_XMLCHARREFREPLACE -> {
                    for (k in collstart until collend) for (ch in "&#${c[k]};") {
                        if (!charmapOutput(ch.code, mapping, out)) errs().raise(reason, collstart, collend)
                    }
                    inpos = collend
                }
                else -> {
                    val (rep, newpos) = errs().call(reason, collstart, collend, size)
                    if (rep is PyBytes) out.write(rep.b)
                    else for (ch in cpsOf((rep as PyStr).s)) {
                        if (!charmapOutput(ch, mapping, out)) errs().raise(reason, collstart, collend)
                    }
                    inpos = newpos
                }
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ _codecs (Modules/_codecsmodule.c)

    /** _PyArg_CheckPositional's messages. */
    private fun checkPositional(name: String, n: Int, min: Int, max: Int) {
        if (n < min) throw typeError("$name expected ${if (min == max) "" else "at least "}$min argument${s(min)}, got $n")
        if (n > max) throw typeError("$name expected ${if (min == max) "" else "at most "}$max argument${s(max)}, got $n")
    }

    /** A Py_buffer argument: a bytes-like object's bytes (with [acceptStr], a str's UTF-8). */
    private fun buffer(v: Any?, acceptStr: Boolean = false): ByteArray {
        if (acceptStr && v is PyStr) return encodeUtf8(ctx(), v, null)
        if (v is PyMemoryView && !v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
        return PyByteSeqs.content(v) ?: throw typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")
    }

    /** An `errors: str(accept={str, NoneType}) = None` argument. */
    private fun errorsArg(fn: String, a: Array<Any?>, i: Int): String? {
        val v = a.getOrNull(i) ?: return null
        if (v === PyNone) return null
        val s = v as? PyStr ?: throw typeError("$fn() argument ${i + 1} must be str or None, not ${PyTypes.name(v)}")
        checkCstr(s)
        return s.s
    }

    private fun strArg(fn: String, a: Array<Any?>, i: Int): PyStr =
        a[i] as? PyStr ?: throw typeError("$fn() argument ${i + 1} must be str, not ${argType(a[i])}")

    /** A C int argument (PyLong_AsInt). */
    private fun intArg(v: Any?): Int = when (val i = PyRt.indexValue(v)) {
        is Long -> if (i < Int.MIN_VALUE || i > Int.MAX_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int") else i.toInt()
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C int")
        else -> throw typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    private fun tuple(vararg v: Any?) = PyTuple(arrayOf(*v))

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        fun noKw(name: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ -> throw typeError("_codecs.$name() takes no keyword arguments") }
        // (METH_O)
        fun def1(name: String, f: (Any?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                if (a.size != 1) throw typeError("_codecs.$name() takes exactly one argument (${a.size} given)")
                f(a[0])
            }, noKw(name))
        }
        // (by position only, from min to max of them)
        fun defN(name: String, min: Int, max: Int, f: (Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                checkPositional(name, a.size, min, max)
                f(a)
            }, noKw(name))
        }
        fun cname(fn: String, v: Any?): String {
            val s = v as? PyStr ?: throw typeError("$fn() argument must be str, not ${argType(v)}")
            checkCstr(s)
            return s.s
        }

        // the registry
        def1("register") { f ->
            if (!PyCalls.isCallable(f)) throw typeError("argument must be callable")
            initialized(ctx).searchPath.add(f)
            PyNone
        }
        def1("unregister") { f ->
            val st = state(ctx)
            val i = st.searchPath.indexOfFirst { it === f }
            if (i >= 0) {
                st.searchPath.removeAt(i)
                st.cache.clear()
            }
            PyNone
        }
        def1("lookup") { v -> lookup(ctx, cname("lookup", v)) }
        fun codecArgs(fn: String, a: Array<Any?>, kw: PyDict?): Triple<Any?, String, String?> {
            val x = PyMath.clinic(fn, a, kw, listOf("obj", "encoding", "errors"), 0, 1, 3)
            val enc = x[1]?.let { cstr(it, "$fn() argument 'encoding'") } ?: "utf-8"
            val err = x[2]?.let { cstr(it, "$fn() argument 'errors'") }
            return Triple(x[0], enc, err)
        }
        d[PyStr("encode")] = PyBuiltin("encode", { _, a -> codecArgs("encode", a, null).let { (o, e, r) -> encode(ctx, o, e, r) } },
            { _, a, kw -> codecArgs("encode", a, kw).let { (o, e, r) -> encode(ctx, o, e, r) } })
        d[PyStr("decode")] = PyBuiltin("decode", { _, a -> codecArgs("decode", a, null).let { (o, e, r) -> decode(ctx, o, e, r) } },
            { _, a, kw -> codecArgs("decode", a, kw).let { (o, e, r) -> decode(ctx, o, e, r) } })

        // the error handlers
        defN("register_error", 2, 2) { a ->
            val name = cstr(a[0], "register_error() argument 1")
            if (!PyCalls.isCallable(a[1])) throw typeError("handler must be callable")
            state(ctx).errors[name] = a[1]
            PyNone
        }
        def1("lookup_error") { v -> lookupErrorHandler(ctx, cname("lookup_error", v)) }
        def1("_unregister_error") { v ->
            val name = cname("_unregister_error", v)
            if (name in listOf("strict", "ignore", "replace", "xmlcharrefreplace", "backslashreplace", "namereplace", "surrogatepass", "surrogateescape")) {
                throw PyException("ValueError", "cannot un-register built-in error handler '$name'")
            }
            PyBool.of(state(ctx).errors.remove(name) != null)
        }

        // the codecs: (what they made, how much they took)
        fun decoder(name: String, acceptStr: Boolean = false, f: (ByteArray, String?, Boolean) -> Decoded) {
            defN(name, 1, 3) { a ->
                val data = buffer(a[0], acceptStr)
                val errors = errorsArg(name, a, 1)
                val final = if (a.size > 2) PyRt.truthy(a[2]) else name.contains("escape")
                val r = f(data, errors, final)
                tuple(PyStr(r.text), r.consumed.toLong())
            }
        }
        decoder("utf_7_decode") { b, e, f -> decodeUtf7(ctx, b, e, f) }
        decoder("utf_8_decode") { b, e, f -> decodeUtf8(ctx, b, e, f) }
        decoder("utf_16_decode") { b, e, f -> decodeUtf16(ctx, b, e, 0, f) }
        decoder("utf_16_le_decode") { b, e, f -> decodeUtf16(ctx, b, e, -1, f) }
        decoder("utf_16_be_decode") { b, e, f -> decodeUtf16(ctx, b, e, 1, f) }
        decoder("utf_32_decode") { b, e, f -> decodeUtf32(ctx, b, e, 0, f) }
        decoder("utf_32_le_decode") { b, e, f -> decodeUtf32(ctx, b, e, -1, f) }
        decoder("utf_32_be_decode") { b, e, f -> decodeUtf32(ctx, b, e, 1, f) }
        decoder("unicode_escape_decode", true) { b, e, f -> decodeUnicodeEscape(ctx, b, e, f) }
        decoder("raw_unicode_escape_decode", true) { b, e, f -> decodeRawUnicodeEscape(ctx, b, e, f) }
        for ((name, utf32) in listOf("utf_16_ex_decode" to false, "utf_32_ex_decode" to true)) {
            defN(name, 1, 4) { a ->
                val data = buffer(a[0])
                val errors = errorsArg(name, a, 1)
                val bo = if (a.size > 2) intArg(a[2]) else 0
                val final = a.size > 3 && PyRt.truthy(a[3])
                val r = if (utf32) decodeUtf32(ctx, data, errors, bo, final) else decodeUtf16(ctx, data, errors, bo, final)
                tuple(PyStr(r.text), r.consumed.toLong(), r.byteorder.toLong())
            }
        }
        defN("latin_1_decode", 1, 2) { a ->
            val data = buffer(a[0])
            errorsArg("latin_1_decode", a, 1)
            tuple(PyStr(String(data, Charsets.ISO_8859_1)), data.size.toLong())
        }
        defN("ascii_decode", 1, 2) { a ->
            val data = buffer(a[0])
            tuple(PyStr(decodeAscii(ctx, data, errorsArg("ascii_decode", a, 1))), data.size.toLong())
        }
        defN("charmap_decode", 1, 3) { a ->
            val data = buffer(a[0])
            val errors = errorsArg("charmap_decode", a, 1)
            tuple(PyStr(decodeCharmap(ctx, data, errors, a.getOrNull(2))), data.size.toLong())
        }
        defN("escape_decode", 1, 2) { a ->
            val data = buffer(a[0], true)
            tuple(PyBytes(escapeDecode(ctx, data, errorsArg("escape_decode", a, 1))), data.size.toLong())
        }
        defN("readbuffer_encode", 1, 2) { a ->
            val data = buffer(a[0], true)
            errorsArg("readbuffer_encode", a, 1)
            tuple(PyBytes(data.copyOf()), data.size.toLong())
        }
        defN("escape_encode", 1, 2) { a ->
            val data = bytesOf(a[0]) ?: throw typeError("escape_encode() argument 1 must be bytes, not ${argType(a[0])}")
            errorsArg("escape_encode", a, 1)
            tuple(PyBytes(escapeEncode(data)), data.size.toLong())
        }
        fun encoder(name: String, max: Int = 2, f: (PyStr, String?, Array<Any?>) -> ByteArray) {
            defN(name, 1, max) { a ->
                val str = strArg(name, a, 0)
                val errors = errorsArg(name, a, 1)
                tuple(PyBytes(f(str, errors, a)), str.length.toLong())
            }
        }
        encoder("utf_7_encode") { s, _, _ -> encodeUtf7(s) }
        encoder("utf_8_encode") { s, e, _ -> encodeUtf8(ctx, s, e) }
        encoder("utf_16_encode", 3) { s, e, a -> encodeUtf16(ctx, s, e, if (a.size > 2) intArg(a[2]) else 0) }
        encoder("utf_16_le_encode") { s, e, _ -> encodeUtf16(ctx, s, e, -1) }
        encoder("utf_16_be_encode") { s, e, _ -> encodeUtf16(ctx, s, e, 1) }
        encoder("utf_32_encode", 3) { s, e, a -> encodeUtf32(ctx, s, e, if (a.size > 2) intArg(a[2]) else 0) }
        encoder("utf_32_le_encode") { s, e, _ -> encodeUtf32(ctx, s, e, -1) }
        encoder("utf_32_be_encode") { s, e, _ -> encodeUtf32(ctx, s, e, 1) }
        encoder("unicode_escape_encode") { s, _, _ -> encodeUnicodeEscape(s) }
        encoder("raw_unicode_escape_encode") { s, _, _ -> encodeRawUnicodeEscape(s) }
        encoder("latin_1_encode") { s, e, _ -> encodeUcs1(ctx, s, e, 256) }
        encoder("ascii_encode") { s, e, _ -> encodeUcs1(ctx, s, e, 128) }
        encoder("charmap_encode", 3) { s, e, a -> encodeCharmap(ctx, s, e, a.getOrNull(2)) }
        def1("charmap_build") { v ->
            val m = v as? PyStr ?: throw typeError("charmap_build() argument must be str, not ${argType(v)}")
            if (m.s.isEmpty()) throw typeError("bad argument type for built-in operation")
            buildEncodingMap(m.s)
        }
        // (Windows' code pages, which encodings' search function asks for on a win32 host: none known here)
        if ((ctx.sysModule.dict[PyStr("platform")] as? PyStr)?.s == "win32") {
            defN("code_page_encode", 2, 3) { a ->
                val cp = intArg(a[0])
                strArg("code_page_encode", a, 1)
                errorsArg("code_page_encode", a, 2)
                codePageError(cp)
            }
            defN("code_page_decode", 2, 4) { a ->
                val cp = intArg(a[0])
                buffer(a[1])
                errorsArg("code_page_decode", a, 2)
                codePageError(cp)
            }
        }
    }

    private fun codePageError(cp: Int): Nothing {
        if (cp < 0) throw PyException("ValueError", "invalid code page number")
        val e = PyBaseException(PyExceptions.type("OSError"))
        e.args = PyTuple(arrayOf(22L, PyStr("The parameter is incorrect")))
        e.setField("errno", 22L)
        e.setField("strerror", PyStr("The parameter is incorrect"))
        throw PyException(e).also { e.thrown = it }
    }
}
