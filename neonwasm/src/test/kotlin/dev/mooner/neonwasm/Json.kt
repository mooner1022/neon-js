package dev.mooner.neonwasm

/** A minimal JSON reader for the spec tests' commands: objects as maps, arrays as lists, numbers as Doubles. */
internal class Json private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(s: String): Any? = Json(s).run {
            val v = value()
            ws()
            if (i != s.length) error("trailing content")
            v
        }
    }

    private fun error(what: String): Nothing = throw IllegalArgumentException("JSON: $what at $i")

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        if (i >= s.length) error("unexpected end")
        return when (val ch = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (ch == '-' || ch.isDigit()) num() else error("unexpected '$ch'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) error("bad literal")
        i += word.length
        return v
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i] in "+-.eE" || s[i].isDigit())) i++
        return s.substring(start, i).toDouble()
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) error("unterminated string")
            val ch = s[i++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun obj(): Map<String, Any?> {
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            if (s[i++] != ':') error("':' expected")
            m[k] = value()
            ws()
            when (s[i++]) {
                ',' -> continue
                '}' -> return m
                else -> error("',' or '}' expected")
            }
        }
    }

    private fun arr(): List<Any?> {
        i++
        val l = ArrayList<Any?>()
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            when (s[i++]) {
                ',' -> continue
                ']' -> return l
                else -> error("',' or ']' expected")
            }
        }
    }
}
