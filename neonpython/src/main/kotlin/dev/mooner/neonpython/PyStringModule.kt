package dev.mooner.neonpython

/**
 * `_string` (docs/PYTHON_PLATFORM.md §12, S2): what CPython's string.Formatter stands on, its format-string parser
 * as Objects/stringlib/unicode_format.h has it (MarkupIterator, parse_field, the field-name iterator), CPython's
 * messages.
 */
object PyStringModule {
    @JvmField val FORMATTER_ITERATOR = PyLibrary.type("formatteriterator")
    @JvmField val FIELDNAME_ITERATOR = PyLibrary.type("fieldnameiterator")

    /** A piece of a format string: its literal text, and the field after it (null where none). */
    private class Piece(val literal: String, val field: String?, val spec: String?, val conversion: Int)

    /** CPython's parse_field over [s] from [i0]: the field, its spec, its conversion, and where it ends. */
    private fun parseField(s: String, i0: Int, end: Int): Pair<Piece, Int> {
        var i = i0
        var c = 0
        val nameStart = i
        loop@ while (i < end) {
            c = s[i++].code
            when (c) {
                '{'.code -> throw PyException("ValueError", "unexpected '{' in field name")
                '['.code -> {
                    while (i < end && s[i] != ']') i++
                    continue@loop
                }
                '}'.code, ':'.code, '!'.code -> break@loop
                else -> continue@loop
            }
        }
        val name = s.substring(nameStart, i - 1)
        var conversion = 0
        if (c == '!'.code || c == ':'.code) {
            if (c == '!'.code) {
                if (i >= end) throw PyException("ValueError", "end of string while looking for conversion specifier")
                conversion = s.codePointAt(i)
                i += Character.charCount(conversion)
                if (i < end) {
                    c = s[i++].code
                    if (c == '}'.code) return Piece("", name, "", conversion) to i
                    if (c != ':'.code) throw PyException("ValueError", "expected ':' after conversion specifier")
                }
            }
            val specStart = i
            var count = 1
            while (i < end) {
                when (s[i++]) {
                    '{' -> count++
                    '}' -> {
                        count--
                        if (count == 0) return Piece("", name, s.substring(specStart, i - 1), conversion) to i
                    }
                    else -> {}
                }
            }
            throw PyException("ValueError", "unmatched '{' in format spec")
        } else if (c != '}'.code) {
            throw PyException("ValueError", "expected '}' before end of string")
        }
        return Piece("", name, "", conversion) to i
    }

    /** CPython's MarkupIterator_next from [i0]: the next piece and where the next begins, or null at the end. */
    private fun next(s: String, i0: Int): Pair<Piece, Int>? {
        val end = s.length
        if (i0 >= end) return null
        var i = i0
        val start = i
        var c = 0
        var markup = false
        while (i < end) {
            c = s[i++].code
            if (c == '{'.code || c == '}'.code) {
                markup = true
                break
            }
        }
        val atEnd = i >= end
        var len = i - start
        if (c == '}'.code && (atEnd || s[i].code != c)) throw PyException("ValueError", "Single '}' encountered in format string")
        if (atEnd && c == '{'.code) throw PyException("ValueError", "Single '{' encountered in format string")
        if (!atEnd) {
            if (s[i].code == c) {
                // (an escaped brace: the literal up to and with one of them, no field)
                i++
                markup = false
            } else len--
        }
        val literal = s.substring(start, start + len)
        if (!markup) return Piece(literal, null, null, 0) to i
        val (field, after) = parseField(s, i, end)
        return Piece(literal, field.field, field.spec, field.conversion) to after
    }

    /** CPython's get_integer (unicode_format.h): the decimal value of [s] (any script's digits), or -1 where it is
     *  not one. */
    @JvmStatic fun integer(s: String): Long {
        if (s.isEmpty()) return -1
        var acc = 0L
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val d = PyUnicodeType.toDecimal(cp)
            if (d < 0) return -1
            if (acc > (Long.MAX_VALUE - d) / 10) throw PyException("ValueError", "Too many decimal digits in format string")
            acc = acc * 10 + d
            i += Character.charCount(cp)
        }
        return acc
    }

    private fun str(v: Any?, fn: String): String = (v as? PyStr)?.s ?: ((v as? PyNumSub)?.num as? PyStr)?.s
        ?: throw PyRt.typeError("$fn() argument must be str, not ${PyTypes.name(v)}")

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        d[PyStr("formatter_parser")] = PyBuiltin("formatter_parser") { _, a ->
            if (a.size != 1) throw PyRt.typeError("formatter_parser() takes exactly one argument (${a.size} given)")
            val s = str(a[0], "formatter_parser")
            var i = 0
            PyIterator {
                val r = next(s, i) ?: return@PyIterator PyIterator.STOP
                i = r.second
                val p = r.first
                PyTuple(arrayOf(PyStr(p.literal), p.field?.let { PyStr(it) } ?: PyNone, p.spec?.let { PyStr(it) } ?: PyNone,
                    if (p.conversion == 0) PyNone else PyStr(String(Character.toChars(p.conversion)))))
            }.also { it.type = FORMATTER_ITERATOR }
        }
        d[PyStr("formatter_field_name_split")] = PyBuiltin("formatter_field_name_split") { _, a ->
            if (a.size != 1) throw PyRt.typeError("formatter_field_name_split() takes exactly one argument (${a.size} given)")
            val s = str(a[0], "formatter_field_name_split")
            // (the part up to the first '.' or '[': an int where it is digits)
            var i = 0
            while (i < s.length && s[i] != '.' && s[i] != '[') i++
            val firstText = s.substring(0, i)
            val n = integer(firstText)
            val first: Any = if (n >= 0) n else PyStr(firstText)
            val end = s.length
            var idx = i
            val rest = PyIterator {
                if (idx >= end) return@PyIterator PyIterator.STOP
                val c = s[idx++]
                val attr: Boolean
                val name: String
                when (c) {
                    '.' -> {
                        attr = true
                        val st = idx
                        while (idx < end && s[idx] != '.' && s[idx] != '[') idx++
                        name = s.substring(st, idx)
                    }
                    '[' -> {
                        attr = false
                        val st = idx
                        while (idx < end && s[idx] != ']') idx++
                        if (idx >= end) throw PyException("ValueError", "Missing ']' in format string")
                        name = s.substring(st, idx)
                        idx++
                    }
                    else -> throw PyException("ValueError", "Only '.' or '[' may follow ']' in format field specifier")
                }
                if (name.isEmpty()) throw PyException("ValueError", "Empty attribute in format string")
                val key: Any = if (!attr) integer(name).let { k -> if (k >= 0) k else PyStr(name) } else PyStr(name)
                PyTuple(arrayOf(PyBool.of(attr), key))
            }.also { it.type = FIELDNAME_ITERATOR }
            PyTuple(arrayOf(first, rest))
        }
    }
}
