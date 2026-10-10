package dev.mooner.neonjs.unicode

/**
 * UTS #46 (Unicode IDNA Compatibility Processing) ToASCII with the flags of the URL Standard's "domain to ASCII":
 * CheckHyphens false, CheckBidi and CheckJoiners true, nontransitional, and UseSTD3ASCIIRules / VerifyDnsLength
 * false. The data comes from [IdnaData] (Unicode 17); normalization is `java.text.Normalizer`'s, as for
 * `String.prototype.normalize`.
 */
internal object Idna {
    private const val VALID = 0
    private const val MAPPED = 1
    private const val DISALLOWED = 2
    private const val IGNORED = 3
    private const val DEVIATION = 4

    /** Runs over the code points: [starts] ascending, with the values of each run. */
    private class Runs(@JvmField val starts: IntArray, @JvmField val a: IntArray, @JvmField val b: IntArray?) {
        fun index(cp: Int): Int {
            var i = starts.binarySearch(cp)
            if (i < 0) i = -i - 2
            return i
        }
    }

    private fun runs(s: String, width: Int): Runs {
        val v = UnicodeTables.decodeInts(s)
        val n = v.size / width
        val starts = IntArray(n)
        val a = IntArray(n)
        val b = if (width == 3) IntArray(n) else null
        var at = 0
        for (i in 0 until n) {
            at += v[i * width]
            starts[i] = at
            a[i] = v[i * width + 1]
            if (b != null) b[i] = v[i * width + 2]
        }
        return Runs(starts, a, b)
    }

    private val status by lazy { runs(IdnaData.STATUS, 3) }
    private val mappings: Array<IntArray> by lazy {
        val v = UnicodeTables.decodeInts(IdnaData.MAPPINGS)
        var k = 1
        Array(v[0]) {
            val len = v[k++]
            val s = v.copyOfRange(k, k + len)
            k += len
            s
        }
    }
    private val bidi by lazy { runs(IdnaData.BIDI, 2) }
    private val joining by lazy { runs(IdnaData.JOINING, 2) }
    private val virama by lazy { UnicodeTables.decodeSet(IdnaData.VIRAMA) }
    private val marks by lazy { UnicodeTables.generalCategory("M")!! }

    private fun statusOf(cp: Int): Int = status.a[status.index(cp)]
    private fun bidiOf(cp: Int): String = IdnaData.BIDI_CLASSES[bidi.a[bidi.index(cp)]]
    private fun joiningOf(cp: Int): String = IdnaData.JOINING_TYPES[joining.a[joining.index(cp)]]

    /**
     * The URL Standard's "domain to ASCII" (beStrict false): the ASCII form of [domain], or null when it fails
     * (including an empty result). An ASCII domain is only lower-cased, even with labels that are no valid IDNA.
     */
    fun domainToAscii(domain: String): String? {
        val r = if (domain.all { it.code <= 0x7F }) domain.lowercase(java.util.Locale.ROOT) else toAscii(domain)
        return if (r.isNullOrEmpty()) null else r
    }

    /** UTS #46 ToASCII; null on failure. */
    fun toAscii(domain: String): String? {
        var error = false
        // 1. map
        val sb = StringBuilder(domain.length)
        var i = 0
        while (i < domain.length) {
            val cp = domain.codePointAt(i)
            i += Character.charCount(cp)
            when (statusOf(cp)) {
                MAPPED -> for (m in mappings[status.b!![status.index(cp)]]) sb.appendCodePoint(m)
                IGNORED -> {}
                else -> sb.appendCodePoint(cp)
            }
        }
        // 2. normalize, 3. break
        val labels = java.text.Normalizer.normalize(sb, java.text.Normalizer.Form.NFC).split('.').toMutableList()
        // 4. convert / validate
        for ((k, label) in labels.withIndex()) {
            if (label.startsWith("xn--")) {
                if (label.any { it.code > 0x7F }) {
                    error = true
                    continue
                }
                val u = Punycode.decode(label.substring(4))
                if (u == null || u.isEmpty() || u.all { it.code <= 0x7F }) {
                    error = true
                    continue
                }
                labels[k] = u
            }
        }
        val bidiDomain = labels.any { l -> l.codePoints().anyMatch { val b = bidiOf(it); b == "R" || b == "AL" || b == "AN" } }
        for (label in labels) if (label.isNotEmpty() && !valid(label, bidiDomain)) error = true
        if (error) return null
        // ToASCII: Punycode for the labels with non-ASCII code points
        val out = StringBuilder()
        for ((k, label) in labels.withIndex()) {
            if (k > 0) out.append('.')
            if (label.any { it.code > 0x7F }) {
                out.append("xn--").append(Punycode.encode(label) ?: return null)
            } else out.append(label)
        }
        return out.toString()
    }

    /** Validity criteria (UTS #46 section 4.1) for nontransitional processing with the URL Standard's flags. */
    private fun valid(label: String, bidiDomain: Boolean): Boolean {
        if (!java.text.Normalizer.isNormalized(label, java.text.Normalizer.Form.NFC)) return false
        if (label.startsWith("xn--")) return false
        if (label.indexOf('.') >= 0) return false
        val cps = label.codePoints().toArray()
        if (CharRanges.contains(marks, cps[0])) return false
        for (cp in cps) {
            val s = statusOf(cp)
            if (s != VALID && s != DEVIATION) return false
        }
        if (!contextJ(cps)) return false
        if (bidiDomain && !bidiRule(cps)) return false
        return true
    }

    /** The ContextJ rules of RFC 5892 (appendix A.1 and A.2) for ZERO WIDTH NON-JOINER and ZERO WIDTH JOINER. */
    private fun contextJ(cps: IntArray): Boolean {
        for ((i, cp) in cps.withIndex()) {
            if (cp != 0x200C && cp != 0x200D) continue
            if (i > 0 && CharRanges.contains(virama, cps[i - 1])) continue
            if (cp == 0x200D) return false
            // (Joining_Type:{L,D})(Joining_Type:T)* ZWNJ (Joining_Type:T)*(Joining_Type:{R,D})
            var j = i - 1
            while (j >= 0 && joiningOf(cps[j]) == "T") j--
            if (j < 0 || joiningOf(cps[j]).let { it != "L" && it != "D" }) return false
            j = i + 1
            while (j < cps.size && joiningOf(cps[j]) == "T") j++
            if (j >= cps.size || joiningOf(cps[j]).let { it != "R" && it != "D" }) return false
        }
        return true
    }

    /** The six conditions of RFC 5893 section 2. */
    private fun bidiRule(cps: IntArray): Boolean {
        val classes = cps.map { bidiOf(it) }
        val first = classes[0]
        if (first != "L" && first != "R" && first != "AL") return false
        var end = classes.size - 1
        while (end > 0 && classes[end] == "NSM") end--
        if (first == "R" || first == "AL") {
            val allowed = setOf("R", "AL", "AN", "EN", "ES", "CS", "ET", "ON", "BN", "NSM")
            if (classes.any { it !in allowed }) return false
            if (classes[end] !in setOf("R", "AL", "EN", "AN")) return false
            if ("EN" in classes && "AN" in classes) return false
        } else {
            val allowed = setOf("L", "EN", "ES", "CS", "ET", "ON", "BN", "NSM")
            if (classes.any { it !in allowed }) return false
            if (classes[end] != "L" && classes[end] != "EN") return false
        }
        return true
    }
}

/**
 * Punycode (RFC 3492): the Bootstring parameters of IDNA. Both directions take time quadratic in the length of a label
 * and nothing bounds that length, so they check for the agent's interrupts and limits as they go.
 */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    private fun adapt(delta0: Int, numPoints: Int, first: Boolean): Int {
        var delta = if (first) delta0 / DAMP else delta0 / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        else -> -1
    }

    private fun digitChar(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun tick(i: Int) {
        if (i and 0x3FF == 0x3FF) dev.mooner.neonjs.runtime.Agent.current.get()?.checkInterrupt()
    }

    /** The code points [input] encodes, or null when it is not valid Punycode. */
    fun decode(input: String): String? {
        if (input.any { it.code > 0x7F }) return null
        val out = ArrayList<Int>()
        val b = input.lastIndexOf('-')
        if (b > 0) for (i in 0 until b) out.add(input[i].code)
        var n = INITIAL_N
        var i = 0L
        var bias = INITIAL_BIAS
        var p = if (b > 0) b + 1 else 0
        while (p < input.length) {
            val oldi = i
            var w = 1L
            var k = BASE
            while (true) {
                if (p >= input.length) return null
                val d = digit(input[p++])
                if (d < 0) return null
                i += d * w
                if (i > Int.MAX_VALUE) return null
                val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                if (d < t) break
                w *= BASE - t
                if (w > Int.MAX_VALUE) return null
                k += BASE
            }
            bias = adapt((i - oldi).toInt(), out.size + 1, oldi == 0L)
            n += (i / (out.size + 1)).toInt()
            if (n > 0x10FFFF || n < 0) return null
            i %= (out.size + 1)
            out.add(i.toInt(), n)
            i++
            tick(out.size)
        }
        val sb = StringBuilder(out.size)
        for (cp in out) {
            if (cp in 0xD800..0xDFFF) return null
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** The Punycode of the code points of [input], or null on overflow. */
    fun encode(input: String): String? {
        val cps = input.codePoints().toArray()
        val out = StringBuilder()
        for (c in cps) if (c < 0x80) out.append(c.toChar())
        val basic = out.length
        var h = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (h < cps.size) {
            val m = cps.filter { it >= n }.minOrNull() ?: break
            delta += (m - n).toLong() * (h + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for ((idx, c) in cps.withIndex()) {
                tick(idx)
                if (c < n) delta++
                if (delta > Int.MAX_VALUE) return null
                if (c == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                        if (q < t) break
                        out.append(digitChar((t + (q - t) % (BASE - t)).toInt()))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digitChar(q.toInt()))
                    bias = adapt(delta.toInt(), h + 1, h == basic)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }
}
