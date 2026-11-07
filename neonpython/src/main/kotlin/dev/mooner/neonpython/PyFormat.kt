package dev.mooner.neonpython

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Formatting (docs/PYTHON_PLATFORM.md §11, M5): format() and __format__ with the format-spec mini-language for ints,
 * floats and strs, f-strings' fields and conversions (FORMAT_VALUE, FORMAT_SPEC, BUILD_STRING), str.format and `%`,
 * all as CPython 3.14 writes them. A float is rounded from its exact binary value, half to even (as CPython does:
 * 2.675 to two places is 2.67), never through String.format.
 */
object PyFormat {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** A parsed format spec: [[fill]align][sign][z][#][0][width][grouping][.precision][type]. */
    class Spec(
        @JvmField val fill: Int, @JvmField val align: Char, @JvmField val sign: Char, @JvmField val z: Boolean,
        @JvmField val alt: Boolean, @JvmField val width: Int, @JvmField val grouping: Char, @JvmField val precision: Int,
        @JvmField val type: Char,
    )

    private fun invalid(spec: String, v: Any?) = PyException("ValueError", "Invalid format specifier '$spec' for object of type '${PyTypes.name(v)}'")

    /** [spec] parsed for a value of [v]'s type (its messages name it); 0 for what it does not say. */
    @JvmStatic
    fun parse(spec: String, v: Any?): Spec {
        val cps = spec.codePoints().toArray()
        var i = 0
        var fill = ' '.code
        var align = 0.toChar()
        fun isAlign(c: Int) = c == '<'.code || c == '>'.code || c == '='.code || c == '^'.code
        if (cps.size >= 2 && isAlign(cps[1])) {
            fill = cps[0]
            align = cps[1].toChar()
            i = 2
        } else if (cps.isNotEmpty() && isAlign(cps[0])) {
            align = cps[0].toChar()
            i = 1
        }
        var sign = 0.toChar()
        if (i < cps.size && (cps[i] == '+'.code || cps[i] == '-'.code || cps[i] == ' '.code)) sign = cps[i++].toChar()
        var z = false
        if (i < cps.size && cps[i] == 'z'.code) { z = true; i++ }
        var alt = false
        if (i < cps.size && cps[i] == '#'.code) { alt = true; i++ }
        if (i < cps.size && cps[i] == '0'.code) {
            // ('0': zero padding, after the sign, where no alignment is given)
            if (align == 0.toChar()) {
                fill = '0'.code
                align = '='.code.toChar().let { if (v is PyStr) '<' else it }
            }
            i++
        }
        var width = -1
        val ws = i
        while (i < cps.size && cps[i] in '0'.code..'9'.code) i++
        if (i > ws) width = String(cps, ws, i - ws).toInt()
        var grouping = 0.toChar()
        if (i < cps.size && (cps[i] == ','.code || cps[i] == '_'.code)) grouping = cps[i++].toChar()
        if (i < cps.size && (cps[i] == ','.code || cps[i] == '_'.code)) throw PyException("ValueError", "Cannot specify both ',' and '_'.")
        var precision = -1
        if (i < cps.size && cps[i] == '.'.code) {
            i++
            val ps = i
            while (i < cps.size && cps[i] in '0'.code..'9'.code) i++
            if (i == ps) throw PyException("ValueError", "Format specifier missing precision")
            precision = String(cps, ps, i - ps).toInt()
        }
        var type = 0.toChar()
        if (i < cps.size) type = cps[i++].toChar()
        if (i < cps.size) throw invalid(spec, v)
        return Spec(fill, align, sign, z, alt, width, grouping, precision, type)
    }

    // ------------------------------------------------------------------ format()

    /** format(v, spec): its class's __format__ (an instance's), else the builtin types' own, else object's. */
    @JvmStatic
    fun format(v: Any?, spec: String): String {
        if (PyRt.special(v)) {
            val t = PyTypes.typeOf(v)
            PyDunder.userMethod(t, "__format__")?.let { f ->
                val r = PyCalls.callWith(PyContext.current(), PyClasses.descrGet(null, f, v, t), arrayOf(PyStr(spec)), NO_ARGS, NO_ARGS)
                return (r as? PyStr ?: throw PyRt.typeError("__format__ must return a str, not ${PyTypes.name(r)}")).s
            }
            // (an int's or a float's subclass: its number's format; empty, its str)
            if (v is PyNumSub) return if (spec.isEmpty()) PyRt.str(v) else builtinFormat(v.num, spec)
        }
        return builtinFormat(v, spec)
    }

    /** The builtin types' __format__ (a subclass's instance as its base type's value), object's for the rest. */
    @JvmStatic
    fun builtinFormat(v: Any?, spec: String): String = when (v) {
        is PyBool -> if (spec.isEmpty()) v.toString() else formatInt(BigInteger.valueOf(if (v.value) 1L else 0L), parse(spec, v), v)
        is Long -> if (spec.isEmpty()) v.toString() else formatInt(BigInteger.valueOf(v), parse(spec, v), v)
        is BigInteger -> if (spec.isEmpty()) PyRt.decimal(v) else formatInt(v, parse(spec, v), v)
        is Double -> if (spec.isEmpty()) PyRt.floatRepr(v) else formatFloat(v, parse(spec, v), v)
        is PyStr -> if (spec.isEmpty()) v.s else formatStr(v.s, parse(spec, v), v)
        else -> {
            if (spec.isNotEmpty()) throw PyRt.typeError("unsupported format string passed to ${PyTypes.name(v)}.__format__")
            PyRt.str(v)
        }
    }

    /** [body] (and its [sign] and [prefix]) padded to the spec's width: fill and alignment ([dflt] where none). */
    private fun pad(sign: String, prefix: String, body: String, s: Spec, dflt: Char): String {
        val len = sign.length + prefix.length + body.codePointCount(0, body.length)
        if (s.width <= len) return sign + prefix + body
        val n = s.width - len
        val fill = String(Character.toChars(s.fill))
        return when (if (s.align == 0.toChar()) dflt else s.align) {
            '<' -> sign + prefix + body + fill.repeat(n)
            '^' -> fill.repeat(n / 2) + sign + prefix + body + fill.repeat(n - n / 2)
            '=' -> sign + prefix + fill.repeat(n) + body
            else -> fill.repeat(n) + sign + prefix + body
        }
    }

    private fun signOf(neg: Boolean, s: Spec): String = if (neg) "-" else when (s.sign) {
        '+' -> "+"
        ' ' -> " "
        else -> ""
    }

    /** [digits] grouped by [sep] every [n] from the right. */
    private fun group(digits: String, sep: Char, n: Int): String {
        if (sep == 0.toChar() || digits.length <= n) return digits
        val sb = StringBuilder()
        val first = digits.length % n
        if (first > 0) sb.append(digits, 0, first)
        var i = first
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append(digits, i, i + n)
            i += n
        }
        return sb.toString()
    }

    /**
     * The integer part [digits], grouped, and padded with zeros where the spec pads with zeros after the sign: the
     * zeros grouped too (CPython's '00,001,234').
     */
    private fun zeroGrouped(digits: String, sep: Char, n: Int, room: Int, s: Spec): String {
        var d = digits
        var g = group(d, sep, n)
        if (s.fill == '0'.code && s.align == '=') while (g.length < room) {
            d = "0$d"
            g = group(d, sep, n)
        }
        return g
    }

    private fun unknown(type: Char, v: Any?) = PyException("ValueError", "Unknown format code '$type' for object of type '${PyTypes.name(v)}'")

    @JvmStatic
    fun formatInt(n: BigInteger, s: Spec, v: Any?): String {
        val t = s.type
        if (t in "eEfFgG%") return formatFloat(n.toDouble(), s, v)
        if (t != 0.toChar() && t !in "bcdoxXn") throw unknown(t, v)
        if (s.precision >= 0) throw PyException("ValueError", "Precision not allowed in integer format specifier")
        if (s.z) throw PyException("ValueError", "Negative zero coercion (z) not allowed in integer format specifier")
        if (t == 'c') {
            if (s.sign != 0.toChar()) throw PyException("ValueError", "Sign not allowed with integer format specifier 'c'")
            if (s.alt) throw PyException("ValueError", "Alternate form (#) not allowed with integer format specifier 'c'")
            if (n.signum() < 0 || n > BigInteger.valueOf(0x10ffff)) throw PyException("OverflowError", "%c arg not in range(0x110000)")
            return pad("", "", String(Character.toChars(n.toInt())), s, '<')
        }
        if (s.grouping == ',' && t != 0.toChar() && t != 'd') throw PyException("ValueError", "Cannot specify ',' with '$t'.")
        if (s.grouping == '_' && t == 'n') throw PyException("ValueError", "Cannot specify '_' with 'n'.")
        val radix = when (t) { 'b' -> 2; 'o' -> 8; 'x', 'X' -> 16; else -> 10 }
        var digits = if (radix == 10) PyRt.decimal(n.abs()) else n.abs().toString(radix)
        if (t == 'X') digits = digits.uppercase()
        val prefix = if (!s.alt) "" else when (t) { 'b' -> "0b"; 'o' -> "0o"; 'x' -> "0x"; 'X' -> "0X"; else -> "" }
        val sign = signOf(n.signum() < 0, s)
        val every = if (radix == 10) 3 else 4
        val body = zeroGrouped(digits, s.grouping, every, s.width - sign.length - prefix.length, s)
        return pad(sign, prefix, body, s, '>')
    }

    @JvmStatic
    fun formatFloat(d: Double, s: Spec, v: Any?): String {
        var t = s.type
        if (t != 0.toChar() && t !in "eEfFgGn%") throw unknown(t, v)
        if (s.grouping == '_' && t == 'n') throw PyException("ValueError", "Cannot specify '_' with 'n'.")
        if (t == 'n') t = 'g'
        val upper = t == 'E' || t == 'F' || t == 'G'
        var neg = d < 0 || d == 0.0 && 1.0 / d < 0
        var body: String
        if (d.isNaN() || d.isInfinite()) {
            body = if (d.isNaN()) "nan" else "inf"
            if (d.isNaN()) neg = false
            if (upper) body = body.uppercase()
            if (t == '%') body += "%"
            return pad(signOf(neg, s), "", body, s, '>')
        }
        val a = Math.abs(d)
        body = when (t) {
            'f', 'F' -> fixed(a, if (s.precision < 0) 6 else s.precision, s.alt)
            'e', 'E' -> exponent(a, if (s.precision < 0) 6 else s.precision, s.alt)
            'g', 'G' -> general(a, if (s.precision < 0) 6 else s.precision, s.alt, false)
            '%' -> fixed(a * 100, if (s.precision < 0) 6 else s.precision, s.alt) + "%"
            else -> if (s.precision < 0) {
                val r = PyRt.floatRepr(a)
                if (s.alt && !r.contains('.') && !r.contains('e') && !r.contains("inf")) "$r." else r
            } else general(a, s.precision, s.alt, true)
        }
        if (upper) body = body.uppercase()
        // (z: a negative zero, after rounding, as zero)
        if (s.z && neg && body.none { it in '1'..'9' }) neg = false
        val sign = signOf(neg, s)
        // the integer part grouped; the rest as it is
        val end = body.indexOfFirst { it == '.' || it == 'e' || it == 'E' || it == '%' }.let { if (it < 0) body.length else it }
        val intPart = zeroGrouped(body.substring(0, end), s.grouping, 3, s.width - sign.length - (body.length - end), s)
        return pad(sign, "", intPart + body.substring(end), s, '>')
    }

    /** [a] (not negative) with [p] decimals, rounded from its exact value half to even. */
    private fun fixed(a: Double, p: Int, alt: Boolean): String {
        val r = BigDecimal(a).setScale(p, RoundingMode.HALF_EVEN).toPlainString()
        return if (alt && p == 0) "$r." else r
    }

    /** [a]'s digits rounded to [n] significant ones, and its decimal exponent. */
    private fun significant(a: Double, n: Int): Pair<String, Int> {
        if (a == 0.0) return "0".repeat(n) to 0
        val r = BigDecimal(a).round(MathContext(n, RoundingMode.HALF_EVEN))
        val u = r.unscaledValue().toString()
        val exp = u.length - 1 - r.scale()
        return u.padEnd(n, '0').substring(0, n) to exp
    }

    private fun exp(e: Int) = "e" + (if (e < 0) "-" else "+") + Math.abs(e).toString().padStart(2, '0')

    /** [a] as d.ddd e±XX with [p] decimals. */
    private fun exponent(a: Double, p: Int, alt: Boolean): String {
        val (digits, e) = significant(a, p + 1)
        val mant = if (p > 0) digits[0] + "." + digits.substring(1) else if (alt) digits + "." else digits
        return mant + exp(e)
    }

    /**
     * 'g' with [p] significant digits: fixed where its exponent is from -4 to below p, else with an exponent; zeros at
     * the end dropped, unless [alt]. [repr] (no type): a fixed one keeps a digit after the point.
     */
    private fun general(a: Double, p0: Int, alt: Boolean, repr: Boolean): String {
        val p = if (p0 == 0) 1 else p0
        val (digits, e) = significant(a, p)
        // (no type: a digit after the point, so the exponent's form from p - 1 on)
        var out = if (e >= -4 && e < (if (repr) p - 1 else p)) {
            val dec = p - 1 - e
            if (e >= 0) {
                val ip = digits.substring(0, e + 1)
                if (dec > 0) ip + "." + digits.substring(e + 1) else ip
            } else "0." + "0".repeat(-e - 1) + digits
        } else {
            (if (p > 1) digits[0] + "." + digits.substring(1) else digits) + exp(e)
        }
        if (!alt) {
            val ePos = out.indexOf('e')
            val mant = if (ePos < 0) out else out.substring(0, ePos)
            val rest = if (ePos < 0) "" else out.substring(ePos)
            val trimmed = if (mant.contains('.')) mant.trimEnd('0').trimEnd('.') else mant
            out = trimmed + rest
        } else if (!out.contains('.')) {
            val ePos = out.indexOf('e')
            out = if (ePos < 0) "$out." else out.substring(0, ePos) + "." + out.substring(ePos)
        }
        if (repr && !out.contains('.') && !out.contains('e')) out += ".0"
        return out
    }

    @JvmStatic
    fun formatStr(str: String, s: Spec, v: Any?): String {
        if (s.type != 0.toChar() && s.type != 's') throw unknown(s.type, v)
        if (s.sign != 0.toChar()) throw PyException("ValueError", "Sign not allowed in string format specifier")
        if (s.alt) throw PyException("ValueError", "Alternate form (#) not allowed in string format specifier")
        if (s.align == '=') throw PyException("ValueError", "'=' alignment not allowed in string format specifier")
        if (s.grouping != 0.toChar()) throw PyException("ValueError", "Cannot specify '${s.grouping}' with 's'.")
        val body = if (s.precision >= 0 && str.codePointCount(0, str.length) > s.precision) str.substring(0, str.offsetByCodePoints(0, s.precision)) else str
        return pad("", "", body, s, '<')
    }

    // ------------------------------------------------------------------ conversions and f-strings

    /** ascii(v): its repr, what is not ASCII escaped. */
    @JvmStatic
    fun ascii(v: Any?): String {
        val r = PyRt.repr(v)
        val sb = StringBuilder()
        var i = 0
        while (i < r.length) {
            val c = r.codePointAt(i)
            i += Character.charCount(c)
            when {
                c < 0x80 -> sb.appendCodePoint(c)
                c < 0x100 -> sb.append("\\x").append(Integer.toHexString(c).padStart(2, '0'))
                c < 0x10000 -> sb.append("\\u").append(Integer.toHexString(c).padStart(4, '0'))
                else -> sb.append("\\U").append(Integer.toHexString(c).padStart(8, '0'))
            }
        }
        return sb.toString()
    }

    internal fun convert(v: Any?, conv: Int): Any? = when (conv) {
        1 -> PyStr(PyRt.str(v))
        2 -> PyStr(PyRt.repr(v))
        3 -> PyStr(ascii(v))
        else -> v
    }

    /** FORMAT_VALUE: an f-string's field without a spec (conversion [conv]: 0 none, 1 !s, 2 !r, 3 !a). */
    @JvmStatic
    fun formatValue(v: Any?, conv: Int): Any? {
        val c = convert(v, conv)
        if (c is PyStr && c.sub == null) return c
        return PyStr(format(c, ""))
    }

    /** FORMAT_SPEC: a field with its spec (a str, its own fields formatted). */
    @JvmStatic
    fun formatSpec(v: Any?, spec: Any?, conv: Int): Any? {
        // (an AST object's spec may be any expression: PyObject_Format's check)
        val s = spec as? PyStr ?: throw PyException("SystemError", "Format specifier must be a string, not ${PyTypes.name(spec)}")
        return PyStr(format(convert(v, conv), s.s))
    }

    /** BUILD_STRING: an f-string's parts joined (an AST object's parts may be other than strs: _PyUnicode_JoinArray's error). */
    @JvmStatic
    fun buildString(parts: Array<Any?>): Any? {
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            sb.append((p as? PyStr ?: throw PyRt.typeError("sequence item $i: expected str instance, ${PyTypes.name(p)} found")).s)
        }
        return PyStr(sb.toString())
    }

    // ------------------------------------------------------------------ str.format

    /** str.format(*args, **kw) (format_map: [kw] the mapping). */
    @JvmStatic
    fun strFormat(fmt: String, args: Array<Any?>, kw: Any?): String = StrFormat(args, kw).render(fmt, 2)

    /** One str.format call: its arguments, and whether its fields are numbered automatically or by hand. */
    private class StrFormat(val args: Array<Any?>, val kw: Any?) {
        var auto = 0
        var manual = false
        var automatic = false

        fun field(text: String, depth: Int): String {
            // name[.attr|[key]]*[!conv][:spec]
            var i = 0
            while (i < text.length && text[i] != '.' && text[i] != '[' && text[i] != '!' && text[i] != ':') i++
            val first = text.substring(0, i)
            var v: Any? = if (first.isEmpty() || first.all { it.isDigit() }) {
                val k = if (first.isEmpty()) {
                    if (manual) throw PyException("ValueError", "cannot switch from manual field specification to automatic field numbering")
                    automatic = true
                    auto++
                } else {
                    if (automatic) throw PyException("ValueError", "cannot switch from automatic field numbering to manual field specification")
                    manual = true
                    first.toInt()
                }
                if (k >= args.size) throw PyException("IndexError", "Replacement index $k out of range for positional args tuple")
                args[k]
            } else {
                val key = PyStr(first)
                when (kw) {
                    is PyDict -> if (kw.containsKey(key)) kw[key] else throw PyException.keyError(key)
                    null -> throw PyException.keyError(key)
                    else -> PyRt.getItem(kw, key)
                }
            }
            while (i < text.length && (text[i] == '.' || text[i] == '[')) {
                if (text[i] == '.') {
                    val s = ++i
                    while (i < text.length && text[i] != '.' && text[i] != '[' && text[i] != '!' && text[i] != ':') i++
                    if (i == s) throw PyException("ValueError", "Empty attribute in format string")
                    v = PyRt.getAttr(v, text.substring(s, i))
                } else {
                    val close = text.indexOf(']', i)
                    if (close < 0) throw PyException("ValueError", "Missing ']' in format string")
                    val k = text.substring(i + 1, close)
                    v = PyRt.getItem(v, if (k.isNotEmpty() && k.all { it.isDigit() }) k.toLong() else PyStr(k))
                    i = close + 1
                }
            }
            var conv = 0
            if (i < text.length && text[i] == '!') {
                if (i + 1 >= text.length) throw PyException("ValueError", "end of string while looking for conversion specifier")
                conv = when (text[i + 1]) {
                    's' -> 1
                    'r' -> 2
                    'a' -> 3
                    else -> throw PyException("ValueError", "Unknown conversion specifier ${text[i + 1]}")
                }
                i += 2
                if (i < text.length && text[i] != ':') throw PyException("ValueError", "expected ':' after conversion specifier")
            }
            var spec = ""
            if (i < text.length && text[i] == ':') {
                if (depth <= 0) throw PyException("ValueError", "Max string recursion exceeded")
                spec = render(text.substring(i + 1), depth - 1)
            }
            return format(convert(v, conv), spec)
        }

        fun render(f: String, depth: Int): String {
            val sb = StringBuilder()
            var i = 0
            while (i < f.length) {
                val c = f[i]
                if (c == '{') {
                    if (i + 1 < f.length && f[i + 1] == '{') {
                        sb.append('{')
                        i += 2
                        continue
                    }
                    // the field: to its '}' (a spec may hold fields of its own)
                    var level = 1
                    var j = i + 1
                    while (j < f.length) {
                        if (f[j] == '{') level++
                        if (f[j] == '}' && --level == 0) break
                        j++
                    }
                    if (j >= f.length) throw PyException("ValueError", if (i + 1 >= f.length) "Single '{' encountered in format string" else "expected '}' before end of string")
                    sb.append(field(f.substring(i + 1, j), depth))
                    i = j + 1
                } else if (c == '}') {
                    if (i + 1 < f.length && f[i + 1] == '}') {
                        sb.append('}')
                        i += 2
                        continue
                    }
                    throw PyException("ValueError", "Single '}' encountered in format string")
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ %

    /**
     * `fmt % arg`: the arguments a tuple's items, else [arg] alone (or, for %(key)s, a mapping). With [bytes], bytes'
     * formatting over their bytes as Latin-1 (PyByteSeqs.percent): %s and %b of bytes-like objects, %a and %r ascii(),
     * %c an int or one byte, keys bytes.
     */
    @JvmStatic
    fun percent(fmt: String, arg: Any?, bytes: Boolean = false): String {
        val items: Array<Any?> = if (arg is PyTuple) arg.items else arrayOf(arg)
        // (CPython's PyMapping_Check: whatever takes a subscript, but a tuple or a str)
        val mapping = arg !is PyTuple && arg !is PyStr && (arg is PyDict || arg is PyList || arg is PyRange ||
            PyRt.special(arg) && PyTypes.typeOf(arg).lookup("__getitem__") != null)
        var next = 0
        var usedMapping = false
        fun take(): Any? {
            if (next >= items.size) throw PyRt.typeError("not enough arguments for format string")
            return items[next++]
        }
        val sb = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if (c != '%') {
                sb.append(c)
                i++
                continue
            }
            i++
            if (i >= fmt.length) throw PyException("ValueError", "incomplete format")
            var value: Any? = null
            var have = false
            if (fmt[i] == '(') {
                if (!mapping) throw PyRt.typeError("format requires a mapping")
                var level = 1
                val ks = ++i
                while (i < fmt.length && level > 0) {
                    if (fmt[i] == '(') level++ else if (fmt[i] == ')') level--
                    i++
                }
                if (level > 0) throw PyException("ValueError", "incomplete format key")
                val key = fmt.substring(ks, i - 1)
                value = PyRt.getItem(arg, if (bytes) PyBytes(key.toByteArray(Charsets.ISO_8859_1)) else PyStr(key))
                have = true
                usedMapping = true
            }
            var left = false
            var plus = false
            var space = false
            var alt = false
            var zero = false
            while (i < fmt.length && fmt[i] in "-+ #0") {
                when (fmt[i]) { '-' -> left = true; '+' -> plus = true; ' ' -> space = true; '#' -> alt = true; else -> zero = true }
                i++
            }
            var width = -1
            if (i < fmt.length && fmt[i] == '*') {
                val w = take()
                width = (w as? Long ?: throw PyRt.typeError("* wants int")).toInt()
                if (width < 0) { left = true; width = -width }
                i++
            } else {
                val ws = i
                while (i < fmt.length && fmt[i].isDigit()) i++
                if (i > ws) width = fmt.substring(ws, i).toInt()
            }
            var precision = -1
            if (i < fmt.length && fmt[i] == '.') {
                i++
                if (i < fmt.length && fmt[i] == '*') {
                    precision = (take() as? Long ?: throw PyRt.typeError("* wants int")).toInt()
                    i++
                } else {
                    val ps = i
                    while (i < fmt.length && fmt[i].isDigit()) i++
                    precision = if (i > ps) fmt.substring(ps, i).toInt() else 0
                }
            }
            while (i < fmt.length && fmt[i] in "hlL") i++
            if (i >= fmt.length) throw PyException("ValueError", "incomplete format")
            val type = fmt[i++]
            if (type == '%') {
                sb.append('%')
                continue
            }
            if (!have) value = take()
            val align = if (left) '<' else if (zero && type !in "sra c") '=' else '>'
            val fill = if (zero && !left && type !in "srac") '0'.code else ' '.code
            val sign = if (plus) '+' else if (space) ' ' else 0.toChar()
            fun spec(t: Char, p: Int = precision) = Spec(fill, align, sign, false, alt, width, 0.toChar(), p, t)
            val out = when (type) {
                's', 'r', 'a', 'b' -> {
                    if (type == 'b' && !bytes) throw PyException("ValueError", "unsupported format character 'b' (0x62) at index ${i - 1}")
                    val s = if (bytes) (if (type == 's' || type == 'b') PyByteSeqs.percentBytes(PyContext.current(), value, type) else ascii(value))
                        else when (type) { 's' -> PyRt.str(value); 'r' -> PyRt.repr(value); else -> ascii(value) }
                    formatStr(s, Spec(' '.code, if (left) '<' else '>', 0.toChar(), false, false, width, 0.toChar(), precision, 's'), value)
                }
                'd', 'i', 'u' -> {
                    val n = when (val value = PyRt.indexValue(value) ?: PyRt.num(value)) {
                        is Long -> BigInteger.valueOf(value)
                        is BigInteger -> value
                        is PyBool -> BigInteger.valueOf(if (value.value) 1L else 0L)
                        is Double -> BigDecimal(value).toBigInteger()
                        else -> throw PyRt.typeError("%$type format: a real number is required, not ${PyTypes.name(value)}")
                    }
                    formatInt(n, spec('d', -1), value)
                }
                'x', 'X', 'o' -> {
                    val n = when (val value = PyRt.indexValue(value) ?: value) {
                        is Long -> BigInteger.valueOf(value)
                        is BigInteger -> value
                        is PyBool -> BigInteger.valueOf(if (value.value) 1L else 0L)
                        else -> throw PyRt.typeError("%$type format: an integer is required, not ${PyTypes.name(value)}")
                    }
                    formatInt(n, spec(type, -1), value)
                }
                'e', 'E', 'f', 'F', 'g', 'G' -> formatFloat(PyRt.toDouble(value), spec(type, if (precision < 0) 6 else precision), value)
                'c' -> {
                    val ch = if (bytes) PyByteSeqs.percentChar(value) else when (value) {
                        is Long -> String(Character.toChars(value.toInt()))
                        is PyStr -> if (value.length == 1) value.s else throw PyRt.typeError("%c requires an int or a unicode character, not a string of length ${value.length}")
                        else -> throw PyRt.typeError("%c requires an int or a unicode character, not ${PyTypes.name(value)}")
                    }
                    formatStr(ch, Spec(' '.code, if (left) '<' else '>', 0.toChar(), false, false, width, 0.toChar(), -1, 's'), value)
                }
                else -> throw PyException("ValueError", "unsupported format character '$type' (0x${Integer.toHexString(type.code)}) at index ${i - 1}")
            }
            sb.append(out)
        }
        // (a tuple's items all used; a single argument used, unless it is a mapping)
        if (!usedMapping && (if (arg is PyTuple) next < items.size else !mapping && next == 0)) {
            throw PyRt.typeError("not all arguments converted during ${if (bytes) "bytes" else "string"} formatting")
        }
        return sb.toString()
    }
}
