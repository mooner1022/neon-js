package dev.mooner.neonjs.node

import java.util.TreeMap

/**
 * The `.env` format of Node.js (`node --env-file`, `util.parseEnv`): Node v26.11.1's parser (src/node_dotenv.cc, MIT
 * license, see NOTICE) as it reads a file's text. `NAME=VALUE` lines, `export NAME=VALUE`, `#` comments (also after an
 * unquoted value), values in single, double or back quotes that may span lines, and `\n` in double quotes as a newline.
 * A host can give scripts the variables of a `.env` file it read: `NodeOptions.builder().env(DotEnv.parse(text))`.
 */
object DotEnv {
    /** The variables of [content], in the order of their names' code points (as Node's object has them). */
    @JvmStatic
    fun parse(content: String): Map<String, String> {
        val store = TreeMap<String, String>(CODE_POINT_ORDER)
        // Node reads the text as UTF-8: a lone surrogate is U+FFFD. Handle windows newlines "\r\n": remove "\r" and
        // keep only "\n"
        val text = wellFormed(content).replace("\r", "")
        // the rest of the text to parse, as Node's string_view: text[s, e)
        var s = 0
        var e = text.length
        fun trim() {
            while (s < e && isSpace(text[s])) s++
            while (e > s && isSpace(text[e - 1])) e--
        }
        fun find(c: Char, from: Int = 0): Int = text.indexOf(c, s + from).let { if (it < 0 || it >= e) -1 else it - s }
        fun trimmed(from: Int, to: Int): String {
            var a = from
            var b = to
            while (a < b && isSpace(text[a])) a++
            while (b > a && isSpace(text[b - 1])) b--
            return text.substring(a, b)
        }

        trim()
        while (s < e) {
            // Skip empty lines and comments
            if (text[s] == '\n' || text[s] == '#') {
                val newline = find('\n')
                if (newline >= 0) s += newline + 1 else s = e
                continue
            }

            // Find the next equals sign or newline in a single pass.
            var equalOrNewline = -1
            for (k in s until e) {
                if (text[k] == '=' || text[k] == '\n') {
                    equalOrNewline = k - s
                    break
                }
            }
            // If we found nothing or found a newline before equals, the line is invalid
            if (equalOrNewline < 0 || text[s + equalOrNewline] == '\n') {
                if (equalOrNewline >= 0) {
                    s += equalOrNewline + 1
                    trim()
                    continue
                }
                break
            }

            // We found an equals sign, extract the key
            var key = trimmed(s, s + equalOrNewline)
            s += equalOrNewline + 1

            // If the value is not present (e.g. KEY=) set it to an empty string
            if (s >= e || text[s] == '\n') {
                store[key] = ""
                continue
            }

            trim()

            // Skip lines with empty keys after trimming spaces.
            if (key.isEmpty()) continue

            // Remove export prefix from key and ensure proper spacing.
            if (key.startsWith("export ")) key = key.substring(7).trim(' ', '\t', '\n')

            if (s >= e) {
                // In case the last line is a single key without value
                store[key] = ""
                break
            }

            // Expand new line if \n it's inside double quotes
            if (text[s] == '"') {
                val closingQuote = find('"', 1)
                if (closingQuote >= 0) {
                    store[key] = text.substring(s + 1, s + closingQuote).replace("\\n", "\n")
                    val newline = find('\n', closingQuote + 1)
                    if (newline >= 0) s += newline + 1 else s = e
                    continue
                }
            }

            // Handle quoted values (single quotes, double quotes, backticks)
            val front = text[s]
            if (front == '\'' || front == '"' || front == '`') {
                val closingQuote = find(front, 1)
                if (closingQuote < 0) {
                    // No closing quote: the rest of the line is the value, quote included
                    val newline = find('\n')
                    if (newline >= 0) {
                        store[key] = text.substring(s, s + newline)
                        s += newline + 1
                    } else {
                        store[key] = text.substring(s, e)
                        break
                    }
                } else {
                    // Found closing quote - take content between quotes
                    store[key] = text.substring(s + 1, s + closingQuote)
                    val newline = find('\n', closingQuote + 1)
                    if (newline >= 0) s += newline + 1 else s = e
                    // No valid data here, skip to next line
                    continue
                }
            } else {
                // Regular key value pair, up to a comment
                val newline = find('\n')
                val lineEnd = if (newline >= 0) s + newline else e
                val hash = text.indexOf('#', s).let { if (it < 0 || it >= lineEnd) lineEnd else it }
                store[key] = trimmed(s, hash)
                if (newline >= 0) s += newline + 1 else s = e
            }

            trim()
        }
        return store
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n'

    private fun wellFormed(s: String): String {
        var out: StringBuilder? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
            if (pair) {
                out?.append(c)?.append(s[i + 1])
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) {
                if (out == null) out = StringBuilder(s.length).append(s, 0, i)
                out.append('�')
            } else {
                out?.append(c)
            }
            i++
        }
        return out?.toString() ?: s
    }

    /** UTF-8 byte order, as the names of Node's std::map compare. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }
}
