package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.unicode.Idna

/**
 * `URL` and `URLSearchParams` of the WHATWG URL Standard: the basic URL parser (with state overrides for the
 * setters), host parsing (domains through UTS #46, IPv4, IPv6, opaque hosts), serialization, origins, and the
 * application/x-www-form-urlencoded format.
 */
internal object Url {
    // ------------------------------------------------------------------ URL records

    sealed class Host {
        class Domain(@JvmField val name: String) : Host()
        class Ipv4(@JvmField val address: Long) : Host()
        class Ipv6(@JvmField val pieces: IntArray) : Host()
        class Opaque(@JvmField val value: String) : Host()
        object Empty : Host()

        fun serialize(): String = when (this) {
            is Domain -> name
            is Ipv4 -> serializeIpv4(address)
            is Ipv6 -> "[" + serializeIpv6(pieces) + "]"
            is Opaque -> value
            Empty -> ""
        }
    }

    class Record {
        @JvmField var scheme = ""
        @JvmField var username = ""
        @JvmField var password = ""
        @JvmField var host: Host? = null
        /** The port, or -1 for none. */
        @JvmField var port = -1
        @JvmField var path = ArrayList<String>()
        /** The opaque path of a URL that has one (path is then unused). */
        @JvmField var opaquePath: String? = null
        @JvmField var query: String? = null
        @JvmField var fragment: String? = null

        val isSpecial: Boolean get() = scheme in SPECIAL
        val includesCredentials: Boolean get() = username.isNotEmpty() || password.isNotEmpty()
        val cannotHaveCredentialsOrPort: Boolean get() = host == null || host === Host.Empty || scheme == "file"

        fun copy(): Record {
            val r = Record()
            r.scheme = scheme; r.username = username; r.password = password; r.host = host; r.port = port
            r.path = ArrayList(path); r.opaquePath = opaquePath; r.query = query; r.fragment = fragment
            return r
        }

        fun assign(o: Record) {
            scheme = o.scheme; username = o.username; password = o.password; host = o.host; port = o.port
            path = o.path; opaquePath = o.opaquePath; query = o.query; fragment = o.fragment
        }

        /** "shorten a URL's path" */
        fun shortenPath() {
            if (scheme == "file" && path.size == 1 && isNormalizedDriveLetter(path[0])) return
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
        }

        fun pathname(): String = opaquePath ?: path.joinToString("") { "/$it" }

        fun serialize(excludeFragment: Boolean = false): String {
            val sb = StringBuilder(scheme).append(':')
            val h = host
            if (h != null) {
                sb.append("//")
                if (includesCredentials) {
                    sb.append(username)
                    if (password.isNotEmpty()) sb.append(':').append(password)
                    sb.append('@')
                }
                sb.append(h.serialize())
                if (port >= 0) sb.append(':').append(port)
            } else if (opaquePath == null && path.size > 1 && path[0].isEmpty()) sb.append("/.")
            sb.append(pathname())
            query?.let { sb.append('?').append(it) }
            if (!excludeFragment) fragment?.let { sb.append('#').append(it) }
            return sb.toString()
        }

        /** The ASCII serialization of the URL's origin ("null" for an opaque one). */
        fun origin(): String = when (scheme) {
            "blob" -> {
                val inner = parse(pathname(), null)
                if (inner != null && (inner.scheme == "http" || inner.scheme == "https")) inner.origin() else "null"
            }
            "ftp", "http", "https", "ws", "wss" -> "$scheme://${host!!.serialize()}" + (if (port >= 0) ":$port" else "")
            else -> "null"
        }

        /** "potentially strip trailing spaces from an opaque path" */
        fun stripTrailingSpaces() {
            val p = opaquePath ?: return
            if (fragment != null || query != null) return
            opaquePath = p.trimEnd(' ')
        }
    }

    private val SPECIAL = mapOf("ftp" to 21, "file" to -1, "http" to 80, "https" to 443, "ws" to 80, "wss" to 443)

    private fun defaultPort(scheme: String): Int = SPECIAL[scheme] ?: -1

    // ------------------------------------------------------------------ percent-encoding

    private fun c0Control(c: Int) = c <= 0x1F || c > 0x7E
    private fun fragmentSet(c: Int) = c0Control(c) || c == 0x20 || c == '"'.code || c == '<'.code || c == '>'.code || c == '`'.code
    private fun querySet(c: Int) = c0Control(c) || c == 0x20 || c == '"'.code || c == '#'.code || c == '<'.code || c == '>'.code
    private fun specialQuerySet(c: Int) = querySet(c) || c == '\''.code
    private fun pathSet(c: Int) = querySet(c) || c == '?'.code || c == '^'.code || c == '`'.code || c == '{'.code || c == '}'.code
    private fun userinfoSet(c: Int) = pathSet(c) || c == '/'.code || c == ':'.code || c == ';'.code || c == '='.code || c == '@'.code ||
        c in '['.code..'^'.code || c == '|'.code
    private fun componentSet(c: Int) = userinfoSet(c) || c in '$'.code..'&'.code || c == '+'.code || c == ','.code
    private fun formSet(c: Int) = componentSet(c) || c == '!'.code || c in '\''.code..')'.code || c == '~'.code

    private const val HEX = "0123456789ABCDEF"

    /** UTF-8 percent-encode of the code point [cp] with [set] (a byte in the set becomes %XX). */
    private fun percentEncode(sb: StringBuilder, cp: Int, set: (Int) -> Boolean, spaceAsPlus: Boolean = false) {
        if (cp < 0x80) {
            if (spaceAsPlus && cp == 0x20) sb.append('+')
            else if (set(cp)) sb.append('%').append(HEX[cp shr 4]).append(HEX[cp and 0xF])
            else sb.append(cp.toChar())
            return
        }
        for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
    }

    private fun percentEncodeString(s: String, set: (Int) -> Boolean, spaceAsPlus: Boolean = false): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            percentEncode(sb, cp, set, spaceAsPlus)
        }
        return sb.toString()
    }

    private fun hexValue(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    /** Percent-decode of the UTF-8 of [s], as bytes. */
    private fun percentDecode(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '%'.code && i + 2 < bytes.size) {
                val h = hexValue(bytes[i + 1].toInt() and 0xFF)
                val l = hexValue(bytes[i + 2].toInt() and 0xFF)
                if (h >= 0 && l >= 0) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ hosts

    private fun forbiddenHost(c: Int) = c == 0 || c == 9 || c == 0xA || c == 0xD || c == 0x20 || c == '#'.code || c == '/'.code ||
        c == ':'.code || c == '<'.code || c == '>'.code || c == '?'.code || c == '@'.code || c == '['.code || c == '\\'.code ||
        c == ']'.code || c == '^'.code || c == '|'.code

    private fun forbiddenDomain(c: Int) = forbiddenHost(c) || c <= 0x1F || c == '%'.code || c == 0x7F

    /** The host parser; null on failure. */
    fun parseHost(input: String, isOpaque: Boolean): Host? {
        if (input.startsWith("[")) {
            if (!input.endsWith("]")) return null
            return parseIpv6(input.substring(1, input.length - 1))?.let { Host.Ipv6(it) }
        }
        if (isOpaque) {
            if (input.any { forbiddenHost(it.code) }) return null
            // an empty host is the empty string, whatever parsed it
            return if (input.isEmpty()) Host.Empty else Host.Opaque(percentEncodeString(input, ::c0Control))
        }
        val domain = utf8DecodeWithoutBom(percentDecode(input))
        val ascii = Idna.domainToAscii(domain) ?: return null
        if (ascii.any { forbiddenDomain(it.code) }) return null
        if (endsInNumber(ascii)) return parseIpv4(ascii)?.let { Host.Ipv4(it) }
        return Host.Domain(ascii)
    }

    /** "UTF-8 decode without BOM": a leading BOM is kept as U+FEFF. */
    private fun utf8DecodeWithoutBom(bytes: ByteArray): String = WhatwgUtf8.decode(bytes)

    private fun endsInNumber(input: String): Boolean {
        val parts = input.split('.').toMutableList()
        if (parts.last().isEmpty()) {
            if (parts.size == 1) return false
            parts.removeAt(parts.size - 1)
        }
        val last = parts.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return parseIpv4Number(last) != null
    }

    /** IPv4 number parser: the value (Long.MAX_VALUE when it overflows), or null on failure. */
    private fun parseIpv4Number(input0: String): Long? {
        var input = input0
        if (input.isEmpty()) return null
        var radix = 10
        if (input.length >= 2 && (input.startsWith("0x") || input.startsWith("0X"))) {
            input = input.substring(2)
            radix = 16
        } else if (input.length >= 2 && input.startsWith("0")) {
            input = input.substring(1)
            radix = 8
        }
        if (input.isEmpty()) return 0
        var v = 0L
        for (c in input) {
            val d = Character.digit(c, radix)
            if (d < 0 || c.code > 0x7F) return null
            v = if (v > (Long.MAX_VALUE - d) / radix) Long.MAX_VALUE else v * radix + d
        }
        return v
    }

    private fun parseIpv4(input: String): Long? {
        val parts = input.split('.').toMutableList()
        if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
        if (parts.size > 4) return null
        val numbers = ArrayList<Long>()
        for (p in parts) numbers.add(parseIpv4Number(p) ?: return null)
        for (k in 0 until numbers.size - 1) if (numbers[k] > 255) return null
        val last = numbers.last()
        if (last >= Math.pow(256.0, (5 - numbers.size).toDouble())) return null
        var ipv4 = last
        for (k in 0 until numbers.size - 1) ipv4 += numbers[k] * Math.pow(256.0, (3 - k).toDouble()).toLong()
        return ipv4
    }

    private fun serializeIpv4(a: Long): String = "${a shr 24 and 0xFF}.${a shr 16 and 0xFF}.${a shr 8 and 0xFF}.${a and 0xFF}"

    private fun parseIpv6(input: String): IntArray? {
        val address = IntArray(8)
        var pieceIndex = 0
        var compress = -1
        var p = 0
        val n = input.length
        fun c(i: Int): Int = if (i < n) input[i].code else -1
        if (c(p) == ':'.code) {
            if (c(p + 1) != ':'.code) return null
            p += 2
            pieceIndex++
            compress = pieceIndex
        }
        while (c(p) != -1) {
            if (pieceIndex == 8) return null
            if (c(p) == ':'.code) {
                if (compress != -1) return null
                p++
                pieceIndex++
                compress = pieceIndex
                continue
            }
            var value = 0
            var length = 0
            while (length < 4 && c(p) != -1 && hexValue(c(p)) >= 0) {
                value = value * 0x10 + hexValue(c(p))
                p++
                length++
            }
            if (c(p) == '.'.code) {
                if (length == 0) return null
                p -= length
                if (pieceIndex > 6) return null
                var numbersSeen = 0
                while (c(p) != -1) {
                    var ipv4Piece = -1
                    if (numbersSeen > 0) {
                        if (c(p) == '.'.code && numbersSeen < 4) p++ else return null
                    }
                    if (c(p) !in '0'.code..'9'.code) return null
                    while (c(p) in '0'.code..'9'.code) {
                        val number = c(p) - '0'.code
                        if (ipv4Piece == -1) ipv4Piece = number
                        else if (ipv4Piece == 0) return null
                        else ipv4Piece = ipv4Piece * 10 + number
                        if (ipv4Piece > 255) return null
                        p++
                    }
                    address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece
                    numbersSeen++
                    if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
                }
                if (numbersSeen != 4) return null
                break
            } else if (c(p) == ':'.code) {
                p++
                if (c(p) == -1) return null
            } else if (c(p) != -1) return null
            address[pieceIndex] = value
            pieceIndex++
        }
        if (compress != -1) {
            var swaps = pieceIndex - compress
            pieceIndex = 7
            while (pieceIndex != 0 && swaps > 0) {
                val t = address[pieceIndex]
                address[pieceIndex] = address[compress + swaps - 1]
                address[compress + swaps - 1] = t
                pieceIndex--
                swaps--
            }
        } else if (pieceIndex != 8) return null
        return address
    }

    private fun serializeIpv6(a: IntArray): String {
        // the first longest run of two or more zero pieces is compressed
        var compress = -1
        var best = 1
        var i = 0
        while (i < 8) {
            if (a[i] == 0) {
                var j = i
                while (j < 8 && a[j] == 0) j++
                if (j - i > best) {
                    best = j - i
                    compress = i
                }
                i = j
            } else i++
        }
        val sb = StringBuilder()
        var ignore0 = false
        for (k in 0 until 8) {
            if (ignore0 && a[k] == 0) continue
            ignore0 = false
            if (compress == k) {
                sb.append(if (k == 0) "::" else ":")
                ignore0 = true
                continue
            }
            sb.append(Integer.toHexString(a[k]))
            if (k != 7) sb.append(':')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ the basic URL parser

    enum class State {
        SCHEME_START, SCHEME, NO_SCHEME, SPECIAL_RELATIVE_OR_AUTHORITY, PATH_OR_AUTHORITY, RELATIVE, RELATIVE_SLASH,
        SPECIAL_AUTHORITY_SLASHES, SPECIAL_AUTHORITY_IGNORE_SLASHES, AUTHORITY, HOST, HOSTNAME, PORT, FILE, FILE_SLASH,
        FILE_HOST, PATH_START, PATH, OPAQUE_PATH, QUERY, FRAGMENT,
    }

    private fun isC0OrSpace(c: Char) = c.code <= 0x20
    private fun isAsciiAlpha(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
    private fun isWindowsDriveLetter(s: String) = s.length == 2 && isAsciiAlpha(s[0].code) && (s[1] == ':' || s[1] == '|')
    private fun isNormalizedDriveLetter(s: String) = s.length == 2 && isAsciiAlpha(s[0].code) && s[1] == ':'

    private fun startsWithDriveLetter(cps: IntArray, from: Int): Boolean {
        if (cps.size - from < 2) return false
        if (!isAsciiAlpha(cps[from]) || (cps[from + 1] != ':'.code && cps[from + 1] != '|'.code)) return false
        if (cps.size - from == 2) return true
        val c = cps[from + 2]
        return c == '/'.code || c == '\\'.code || c == '?'.code || c == '#'.code
    }

    private fun isSingleDot(s: String) = s == "." || s.equals("%2e", ignoreCase = true)
    private fun isDoubleDot(s: String) = s == ".." || s.equals(".%2e", ignoreCase = true) || s.equals("%2e.", ignoreCase = true) ||
        s.equals("%2e%2e", ignoreCase = true)

    /** Parses [input] against [base] (no state override); null on failure. */
    fun parse(input: String, base: Record?): Record? {
        val url = Record()
        return if (basicParse(input, base, url, null)) url else null
    }

    /**
     * The basic URL parser. Without [override] it fills [url] (a new record) and returns false on failure. With a state
     * override it changes [url] in place, as the setters do; false means failure, or a change it would not make
     * (the URL is then unchanged only for the documented early returns).
     */
    fun basicParse(input0: String, base: Record?, url: Record, override: State?): Boolean {
        var input = input0
        if (override == null) input = input.trim { isC0OrSpace(it) }
        input = input.filter { it != '\t' && it != '\n' && it != '\r' }
        val cps = input.codePoints().toArray()
        var state = override ?: State.SCHEME_START
        val buffer = StringBuilder()
        // the opaque path and the fragment grow by a code point at a time: built here, stored when the parse ends
        // (no state reads them meanwhile)
        val opaque = StringBuilder()
        val fragment = StringBuilder()
        val agent = Agent.current.get()
        var steps = 0
        var atSignSeen = false
        var insideBrackets = false
        var passwordTokenSeen = false
        var pointer = 0
        val EOF = -1
        fun remainingStartsWith(s: String): Boolean {
            for (k in s.indices) {
                val i = pointer + 1 + k
                if (i >= cps.size || cps[i] != s[k].code) return false
            }
            return true
        }
        while (true) {
            val c = if (pointer < cps.size) cps[pointer] else EOF
            when (state) {
                State.SCHEME_START -> {
                    if (c != EOF && isAsciiAlpha(c)) {
                        buffer.append(c.toChar().lowercaseChar())
                        state = State.SCHEME
                    } else if (override == null) {
                        state = State.NO_SCHEME
                        pointer--
                    } else return false
                }
                State.SCHEME -> {
                    if (c != EOF && (isAsciiAlpha(c) || c in '0'.code..'9'.code || c == '+'.code || c == '-'.code || c == '.'.code)) {
                        buffer.append(c.toChar().lowercaseChar())
                    } else if (c == ':'.code) {
                        val b = buffer.toString()
                        if (override != null) {
                            if (url.isSpecial != (b in SPECIAL)) return true
                            if ((url.includesCredentials || url.port >= 0) && b == "file") return true
                            if (url.scheme == "file" && url.host === Host.Empty) return true
                        }
                        url.scheme = b
                        if (override != null) {
                            if (url.port == defaultPort(url.scheme)) url.port = -1
                            return true
                        }
                        buffer.setLength(0)
                        if (url.scheme == "file") state = State.FILE
                        else if (url.isSpecial && base != null && base.scheme == url.scheme) state = State.SPECIAL_RELATIVE_OR_AUTHORITY
                        else if (url.isSpecial) state = State.SPECIAL_AUTHORITY_SLASHES
                        else if (remainingStartsWith("/")) {
                            state = State.PATH_OR_AUTHORITY
                            pointer++
                        } else {
                            url.opaquePath = ""
                            state = State.OPAQUE_PATH
                        }
                    } else if (override == null) {
                        buffer.setLength(0)
                        state = State.NO_SCHEME
                        pointer = -1
                    } else return false
                }
                State.NO_SCHEME -> {
                    if (base == null || (base.opaquePath != null && c != '#'.code)) return false
                    if (base.opaquePath != null && c == '#'.code) {
                        url.scheme = base.scheme
                        url.opaquePath = base.opaquePath
                        url.query = base.query
                        url.fragment = ""
                        state = State.FRAGMENT
                    } else if (base.scheme != "file") {
                        state = State.RELATIVE
                        pointer--
                    } else {
                        state = State.FILE
                        pointer--
                    }
                }
                State.SPECIAL_RELATIVE_OR_AUTHORITY -> {
                    if (c == '/'.code && remainingStartsWith("/")) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer++
                    } else {
                        state = State.RELATIVE
                        pointer--
                    }
                }
                State.PATH_OR_AUTHORITY -> {
                    if (c == '/'.code) state = State.AUTHORITY else {
                        state = State.PATH
                        pointer--
                    }
                }
                State.RELATIVE -> {
                    url.scheme = base!!.scheme
                    if (c == '/'.code) state = State.RELATIVE_SLASH
                    else if (url.isSpecial && c == '\\'.code) state = State.RELATIVE_SLASH
                    else {
                        url.username = base.username
                        url.password = base.password
                        url.host = base.host
                        url.port = base.port
                        url.path = ArrayList(base.path)
                        url.query = base.query
                        if (c == '?'.code) {
                            url.query = ""
                            state = State.QUERY
                        } else if (c == '#'.code) {
                            url.fragment = ""
                            state = State.FRAGMENT
                        } else if (c != EOF) {
                            url.query = null
                            url.shortenPath()
                            state = State.PATH
                            pointer--
                        }
                    }
                }
                State.RELATIVE_SLASH -> {
                    if (url.isSpecial && (c == '/'.code || c == '\\'.code)) state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                    else if (c == '/'.code) state = State.AUTHORITY
                    else {
                        url.username = base!!.username
                        url.password = base.password
                        url.host = base.host
                        url.port = base.port
                        state = State.PATH
                        pointer--
                    }
                }
                State.SPECIAL_AUTHORITY_SLASHES -> {
                    if (c == '/'.code && remainingStartsWith("/")) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer++
                    } else {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer--
                    }
                }
                State.SPECIAL_AUTHORITY_IGNORE_SLASHES -> {
                    if (c != '/'.code && c != '\\'.code) {
                        state = State.AUTHORITY
                        pointer--
                    }
                }
                State.AUTHORITY -> {
                    if (c == '@'.code) {
                        if (atSignSeen) buffer.insert(0, "%40")
                        atSignSeen = true
                        val b = buffer.toString()
                        val username = StringBuilder(url.username)
                        val password = StringBuilder(url.password)
                        var k = 0
                        while (k < b.length) {
                            val cp = b.codePointAt(k)
                            k += Character.charCount(cp)
                            if (cp == ':'.code && !passwordTokenSeen) {
                                passwordTokenSeen = true
                                continue
                            }
                            percentEncode(if (passwordTokenSeen) password else username, cp, ::userinfoSet)
                        }
                        url.username = username.toString()
                        url.password = password.toString()
                        buffer.setLength(0)
                    } else if (c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code)) {
                        if (atSignSeen && buffer.isEmpty()) return false
                        pointer -= buffer.codePointCount(0, buffer.length) + 1
                        buffer.setLength(0)
                        state = State.HOST
                    } else buffer.appendCodePoint(c)
                }
                State.HOST, State.HOSTNAME -> {
                    if (override != null && url.scheme == "file") {
                        pointer--
                        state = State.FILE_HOST
                    } else if (c == ':'.code && !insideBrackets) {
                        if (buffer.isEmpty()) return false
                        if (override == State.HOSTNAME) return false
                        val host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                        url.host = host
                        buffer.setLength(0)
                        state = State.PORT
                    } else if (c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code)) {
                        pointer--
                        if (url.isSpecial && buffer.isEmpty()) return false
                        if (override != null && buffer.isEmpty() && (url.includesCredentials || url.port >= 0)) return false
                        val host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                        url.host = host
                        buffer.setLength(0)
                        state = State.PATH_START
                        if (override != null) return true
                    } else {
                        if (c == '['.code) insideBrackets = true
                        if (c == ']'.code) insideBrackets = false
                        buffer.appendCodePoint(c)
                    }
                }
                State.PORT -> {
                    if (c in '0'.code..'9'.code) buffer.appendCodePoint(c)
                    else if (c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code) || override != null) {
                        if (buffer.isNotEmpty()) {
                            var port = 0L
                            for (d in buffer) {
                                port = port * 10 + (d - '0')
                                if (port > 65535) return false
                            }
                            url.port = if (port.toInt() == defaultPort(url.scheme)) -1 else port.toInt()
                            buffer.setLength(0)
                            if (override != null) return true
                        }
                        if (override != null) return false
                        state = State.PATH_START
                        pointer--
                    } else return false
                }
                State.FILE -> {
                    url.scheme = "file"
                    url.host = Host.Empty
                    if (c == '/'.code || c == '\\'.code) state = State.FILE_SLASH
                    else if (base != null && base.scheme == "file") {
                        url.host = base.host
                        url.path = ArrayList(base.path)
                        url.query = base.query
                        if (c == '?'.code) {
                            url.query = ""
                            state = State.QUERY
                        } else if (c == '#'.code) {
                            url.fragment = ""
                            state = State.FRAGMENT
                        } else if (c != EOF) {
                            url.query = null
                            if (!startsWithDriveLetter(cps, pointer)) url.shortenPath() else url.path = ArrayList()
                            state = State.PATH
                            pointer--
                        }
                    } else {
                        state = State.PATH
                        pointer--
                    }
                }
                State.FILE_SLASH -> {
                    if (c == '/'.code || c == '\\'.code) state = State.FILE_HOST
                    else {
                        if (base != null && base.scheme == "file") {
                            url.host = base.host
                            if (!startsWithDriveLetter(cps, pointer) && base.path.isNotEmpty() && isNormalizedDriveLetter(base.path[0]))
                                url.path.add(base.path[0])
                        }
                        state = State.PATH
                        pointer--
                    }
                }
                State.FILE_HOST -> {
                    if (c == EOF || c == '/'.code || c == '\\'.code || c == '?'.code || c == '#'.code) {
                        pointer--
                        if (override == null && isWindowsDriveLetter(buffer.toString())) {
                            // the buffer is kept for the path state
                            state = State.PATH
                        } else if (buffer.isEmpty()) {
                            url.host = Host.Empty
                            if (override != null) return true
                            state = State.PATH_START
                        } else {
                            var host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                            if (host is Host.Domain && host.name == "localhost") host = Host.Empty
                            url.host = host
                            if (override != null) return true
                            buffer.setLength(0)
                            state = State.PATH_START
                        }
                    } else buffer.appendCodePoint(c)
                }
                State.PATH_START -> {
                    if (url.isSpecial) {
                        state = State.PATH
                        if (c != '/'.code && c != '\\'.code) pointer--
                    } else if (override == null && c == '?'.code) {
                        url.query = ""
                        state = State.QUERY
                    } else if (override == null && c == '#'.code) {
                        url.fragment = ""
                        state = State.FRAGMENT
                    } else if (c != EOF) {
                        state = State.PATH
                        if (c != '/'.code) pointer--
                    } else if (override != null && url.host == null) url.path.add("")
                }
                State.PATH -> {
                    val slash = c == '/'.code || (url.isSpecial && c == '\\'.code)
                    if (c == EOF || slash || (override == null && (c == '?'.code || c == '#'.code))) {
                        val b = buffer.toString()
                        if (isDoubleDot(b)) {
                            url.shortenPath()
                            if (!slash) url.path.add("")
                        } else if (isSingleDot(b) && !slash) {
                            url.path.add("")
                        } else if (!isSingleDot(b)) {
                            if (url.scheme == "file" && url.path.isEmpty() && isWindowsDriveLetter(b)) {
                                url.path.add(b.substring(0, 1) + ":")
                            } else url.path.add(b)
                        }
                        buffer.setLength(0)
                        if (c == '?'.code) {
                            url.query = ""
                            state = State.QUERY
                        }
                        if (c == '#'.code) {
                            url.fragment = ""
                            state = State.FRAGMENT
                        }
                    } else {
                        percentEncode(buffer, c, ::pathSet)
                    }
                }
                State.OPAQUE_PATH -> {
                    if (c == '?'.code) {
                        url.query = ""
                        state = State.QUERY
                    } else if (c == '#'.code) {
                        url.fragment = ""
                        state = State.FRAGMENT
                    } else if (c == ' '.code) {
                        // a space before a query or fragment is encoded, so that it survives their removal
                        val next = if (pointer + 1 < cps.size) cps[pointer + 1] else EOF
                        opaque.append(if (next == '?'.code || next == '#'.code) "%20" else " ")
                    } else if (c != EOF) percentEncode(opaque, c, ::c0Control)
                }
                State.QUERY -> {
                    if ((override == null && c == '#'.code) || c == EOF) {
                        val set: (Int) -> Boolean = if (url.isSpecial) ::specialQuerySet else ::querySet
                        url.query = (url.query ?: "") + percentEncodeString(buffer.toString(), set)
                        buffer.setLength(0)
                        if (c == '#'.code) {
                            url.fragment = ""
                            state = State.FRAGMENT
                        }
                    } else if (c != EOF) buffer.appendCodePoint(c)
                }
                State.FRAGMENT -> if (c != EOF) percentEncode(fragment, c, ::fragmentSet)
            }
            // the end is where the pointer is after the state ran (a state may move it back from the end)
            if (pointer >= cps.size) break
            pointer++
            if (++steps and 0xFFF == 0) agent?.checkInterrupt()
        }
        if (opaque.isNotEmpty()) url.opaquePath = (url.opaquePath ?: "") + opaque
        if (fragment.isNotEmpty()) url.fragment = (url.fragment ?: "") + fragment
        return true
    }

    // ------------------------------------------------------------------ application/x-www-form-urlencoded

    fun parseForm(input: String): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (seq in input.split('&')) {
            if (seq.isEmpty()) continue
            val eq = seq.indexOf('=')
            val name = if (eq >= 0) seq.substring(0, eq) else seq
            val value = if (eq >= 0) seq.substring(eq + 1) else ""
            out.add(WhatwgUtf8.decode(percentDecode(name.replace('+', ' '))) to WhatwgUtf8.decode(percentDecode(value.replace('+', ' '))))
        }
        return out
    }

    fun serializeForm(list: List<Pair<String, String>>): String = list.joinToString("&") { (n, v) ->
        percentEncodeString(n, ::formSet, spaceAsPlus = true) + "=" + percentEncodeString(v, ::formSet, spaceAsPlus = true)
    }

    // ------------------------------------------------------------------ the interfaces

    class JSURL(proto: JSObject?, @JvmField val url: Record) : JSObject(proto) {
        override val className: String get() = "URL"
        @JvmField var searchParams: JSURLSearchParams? = null
    }

    class JSURLSearchParams(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "URLSearchParams"
        @JvmField var list = ArrayList<Pair<String, String>>()
        @JvmField var url: JSURL? = null

        /** The update steps: the URL's query follows the list. */
        fun update() {
            val u = url ?: return
            val q = serializeForm(list)
            u.url.query = q.ifEmpty { null }
            if (q.isEmpty()) u.url.stripTrailingSpaces()
        }
    }

    private fun newSearchParams(realm: Realm, url: JSURL): JSURLSearchParams {
        val p = JSURLSearchParams(realm.intrinsic("%URLSearchParams.prototype%"))
        p.url = url
        url.url.query?.let { p.list = parseForm(it) }
        return p
    }

    private fun newURL(realm: Realm, proto: JSObject, r: Record): JSURL {
        val u = JSURL(proto, r)
        u.searchParams = newSearchParams(realm, u)
        return u
    }

    /** A setter's parse: the basic URL parser changing [url] in place, as far as it gets (a failure keeps the changes so far). */
    private fun setterParse(value: String, url: Record, state: State) {
        basicParse(value, null, url, state)
    }

    /** "API URL parser": the URL [url] (a USVString) against [base], or null on failure. */
    private fun apiParse(url: Any?, base: Any?): Record? {
        val parsedBase = if (base === Undefined) null else parse(Idl.usvString(base), null) ?: return null
        return parse(Idl.usvString(url), parsedBase)
    }

    fun install(realm: Realm) {
        val u = WebInterface.define(realm, "URL", 1) { a, proto ->
            Idl.required(a, 1, "URL constructor")
            val urlArg = Idl.usvString(a[0])
            val baseArg = if (a.size > 1 && a[1] !== Undefined) Idl.usvString(a[1]) else null
            val base = if (baseArg != null) parse(baseArg, null) ?: typeErr("Invalid base URL: $baseArg") else null
            val r = parse(urlArg, base) ?: typeErr("Invalid URL: $urlArg")
            newURL(realm, proto, r)
        }
        u.staticOperation("parse", 1) { f, _, a, _ ->
            Idl.required(a, 1, "URL.parse")
            val r = apiParse(a[0], a.arg(1)) ?: return@staticOperation Null
            newURL(f.realm, f.realm.intrinsic("%URL.prototype%"), r)
        }
        u.staticOperation("canParse", 1) { _, _, a, _ ->
            Idl.required(a, 1, "URL.canParse")
            apiParse(a[0], a.arg(1)) != null
        }
        fun url(t: Any?, m: String) = Idl.self<JSURL>(t, "URL", m)
        // percent-encoding may triple a string: what goes back to JS is held to the string length limit
        fun href(f: NativeFunction, t: Any?, m: String): String = url(t, m).url.serialize().also { f.realm.agent.checkStringLength(it.length.toLong()) }
        u.attribute("href", { f, t, _, _ -> href(f, t, "href") }, { _, t, a, _ ->
            val x = url(t, "href")
            val v = Idl.usvString(a.arg(0))
            val r = parse(v, null) ?: typeErr("Invalid URL: $v")
            x.url.assign(r)
            x.searchParams!!.list = r.query?.let { parseForm(it) } ?: ArrayList()
            Undefined
        })
        u.attribute("origin", { _, t, _, _ -> url(t, "origin").url.origin() })
        u.attribute("protocol", { _, t, _, _ -> url(t, "protocol").url.scheme + ":" }, { _, t, a, _ ->
            val x = url(t, "protocol")
            setterParse(Idl.usvString(a.arg(0)) + ":", x.url, State.SCHEME_START)
            Undefined
        })
        u.attribute("username", { _, t, _, _ -> url(t, "username").url.username }, { _, t, a, _ ->
            val x = url(t, "username")
            val v = Idl.usvString(a.arg(0))
            if (!x.url.cannotHaveCredentialsOrPort) x.url.username = percentEncodeString(v, ::userinfoSet)
            Undefined
        })
        u.attribute("password", { _, t, _, _ -> url(t, "password").url.password }, { _, t, a, _ ->
            val x = url(t, "password")
            val v = Idl.usvString(a.arg(0))
            if (!x.url.cannotHaveCredentialsOrPort) x.url.password = percentEncodeString(v, ::userinfoSet)
            Undefined
        })
        u.attribute("host", { _, t, _, _ ->
            val r = url(t, "host").url
            val h = r.host ?: return@attribute ""
            if (r.port < 0) h.serialize() else h.serialize() + ":" + r.port
        }, { _, t, a, _ ->
            val x = url(t, "host")
            val v = Idl.usvString(a.arg(0))
            if (x.url.opaquePath == null) setterParse(v, x.url, State.HOST)
            Undefined
        })
        u.attribute("hostname", { _, t, _, _ -> url(t, "hostname").url.host?.serialize() ?: "" }, { _, t, a, _ ->
            val x = url(t, "hostname")
            val v = Idl.usvString(a.arg(0))
            if (x.url.opaquePath == null) setterParse(v, x.url, State.HOSTNAME)
            Undefined
        })
        u.attribute("port", { _, t, _, _ -> url(t, "port").url.port.let { if (it < 0) "" else it.toString() } }, { _, t, a, _ ->
            val x = url(t, "port")
            val v = Idl.usvString(a.arg(0))
            if (!x.url.cannotHaveCredentialsOrPort) {
                if (v.isEmpty()) x.url.port = -1 else setterParse(v, x.url, State.PORT)
            }
            Undefined
        })
        u.attribute("pathname", { _, t, _, _ -> url(t, "pathname").url.pathname() }, { _, t, a, _ ->
            val x = url(t, "pathname")
            val v = Idl.usvString(a.arg(0))
            if (x.url.opaquePath == null) {
                x.url.path = ArrayList()
                setterParse(v, x.url, State.PATH_START)
            }
            Undefined
        })
        u.attribute("search", { _, t, _, _ -> url(t, "search").url.query.let { if (it.isNullOrEmpty()) "" else "?$it" } }, { _, t, a, _ ->
            val x = url(t, "search")
            val v = Idl.usvString(a.arg(0))
            if (v.isEmpty()) {
                x.url.query = null
                x.searchParams!!.list = ArrayList()
                x.url.stripTrailingSpaces()
            } else {
                val input = v.removePrefix("?")
                x.url.query = ""
                setterParse(input, x.url, State.QUERY)
                x.searchParams!!.list = parseForm(x.url.query ?: "")
            }
            Undefined
        })
        u.attribute("searchParams", { _, t, _, _ -> url(t, "searchParams").searchParams })
        u.attribute("hash", { _, t, _, _ -> url(t, "hash").url.fragment.let { if (it.isNullOrEmpty()) "" else "#$it" } }, { _, t, a, _ ->
            val x = url(t, "hash")
            val v = Idl.usvString(a.arg(0))
            if (v.isEmpty()) {
                x.url.fragment = null
                x.url.stripTrailingSpaces()
            } else {
                x.url.fragment = ""
                setterParse(v.removePrefix("#"), x.url, State.FRAGMENT)
            }
            Undefined
        })
        u.operation("toJSON", 0) { f, t, _, _ -> href(f, t, "toJSON") }
        // stringifier: toString returns href
        u.operation("toString", 0) { f, t, _, _ -> href(f, t, "toString") }

        val p = WebInterface.define(realm, "URLSearchParams", 0) { a, proto ->
            val sp = JSURLSearchParams(proto)
            val init = a.arg(0)
            when {
                init === Undefined -> {}
                init is JSObject && Ops.getMethod(init, JSSymbol.iterator) !== Undefined -> {
                    val pairs = Idl.sequence(realm, init, "URLSearchParams init") { inner -> Idl.sequence(realm, inner, "URLSearchParams init") { Idl.usvString(it) } }
                    for (pair in pairs) {
                        if (pair.size != 2) typeErr("URLSearchParams: a pair must have exactly two items")
                        sp.list.add(pair[0] to pair[1])
                    }
                }
                init is JSObject -> for ((k, v) in Idl.record(init, { Idl.usvString(it) }, { Idl.usvString(it) })) sp.list.add(k to v)
                else -> sp.list = parseForm(Idl.usvString(init).removePrefix("?"))
            }
            sp
        }
        fun sp(t: Any?, m: String) = Idl.self<JSURLSearchParams>(t, "URLSearchParams", m)
        p.attribute("size", { _, t, _, _ -> sp(t, "size").list.size.toDouble() })
        p.operation("append", 2) { _, t, a, _ ->
            val x = sp(t, "append")
            Idl.required(a, 2, "URLSearchParams.append")
            x.list.add(Idl.usvString(a[0]) to Idl.usvString(a[1]))
            x.update()
            Undefined
        }
        p.operation("delete", 1) { _, t, a, _ ->
            val x = sp(t, "delete")
            Idl.required(a, 1, "URLSearchParams.delete")
            val name = Idl.usvString(a[0])
            val value = if (a.size > 1 && a[1] !== Undefined) Idl.usvString(a[1]) else null
            x.list.removeIf { it.first == name && (value == null || it.second == value) }
            x.update()
            Undefined
        }
        p.operation("get", 1) { _, t, a, _ ->
            val x = sp(t, "get")
            Idl.required(a, 1, "URLSearchParams.get")
            val name = Idl.usvString(a[0])
            x.list.firstOrNull { it.first == name }?.second ?: Null
        }
        p.operation("getAll", 1) { f, t, a, _ ->
            val x = sp(t, "getAll")
            Idl.required(a, 1, "URLSearchParams.getAll")
            val name = Idl.usvString(a[0])
            Builtins.arrayOf(f.realm, x.list.filter { it.first == name }.map { it.second })
        }
        p.operation("has", 1) { _, t, a, _ ->
            val x = sp(t, "has")
            Idl.required(a, 1, "URLSearchParams.has")
            val name = Idl.usvString(a[0])
            val value = if (a.size > 1 && a[1] !== Undefined) Idl.usvString(a[1]) else null
            x.list.any { it.first == name && (value == null || it.second == value) }
        }
        p.operation("set", 2) { _, t, a, _ ->
            val x = sp(t, "set")
            Idl.required(a, 2, "URLSearchParams.set")
            val name = Idl.usvString(a[0])
            val value = Idl.usvString(a[1])
            val first = x.list.indexOfFirst { it.first == name }
            if (first < 0) x.list.add(name to value) else {
                x.list[first] = name to value
                var k = -1
                x.list.removeIf { k++; k > first && it.first == name }
            }
            x.update()
            Undefined
        }
        p.operation("sort", 0) { _, t, _, _ ->
            val x = sp(t, "sort")
            // stable, by the code units of the names
            x.list.sortWith { l, r -> l.first.compareTo(r.first) }
            x.update()
            Undefined
        }
        p.pairIterable(JSURLSearchParams::class.java) { x -> x.list }
        p.operation("toString", 0) { f, t, _, _ -> serializeForm(sp(t, "toString").list).also { f.realm.agent.checkStringLength(it.length.toLong()) } }
    }
}

/** The UTF-8 decoder of the Encoding Standard (invalid sequences become U+FFFD, a leading BOM is kept). */
internal object WhatwgUtf8 {
    fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        var cp = 0
        var needed = 0
        var seen = 0
        var lower = 0x80
        var upper = 0xBF
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> out.append(b.toChar())
                    in 0xC2..0xDF -> { needed = 1; cp = b and 0x1F }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2; cp = b and 0xF
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3; cp = b and 0x7
                    }
                    else -> out.append('�')
                }
                i++
                continue
            }
            if (b !in lower..upper) {
                cp = 0; needed = 0; seen = 0; lower = 0x80; upper = 0xBF
                out.append('�')
                continue
            }
            lower = 0x80; upper = 0xBF
            cp = (cp shl 6) or (b and 0x3F)
            seen++
            i++
            if (seen == needed) {
                out.appendCodePoint(cp)
                cp = 0; needed = 0; seen = 0
            }
        }
        if (needed != 0) out.append('�')
        return out.toString()
    }
}
