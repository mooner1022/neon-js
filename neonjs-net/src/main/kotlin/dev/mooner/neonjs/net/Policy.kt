package dev.mooner.neonjs.net

import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * A pattern of allowed hosts: a host name (`example.com`), its subdomains (`*.example.com`, which does not match
 * `example.com` itself), or an IP address (`127.0.0.1`, `[::1]`), with an optional port (any port without one).
 */
class HostPattern private constructor(private val host: String, private val address: InetAddress?, private val wildcard: Boolean, private val port: Int) {
    /** Whether [host] (as a URL serializes it: ASCII, an IPv6 address in brackets) and [port] match. */
    fun matches(host: String, port: Int): Boolean {
        if (this.port >= 0 && port != this.port) return false
        if (address != null) return AddressRange.literal(host.removePrefix("[").removeSuffix("]")) == address
        val h = host.lowercase(java.util.Locale.ROOT)
        return if (wildcard) h.endsWith(".${this.host}") else h == this.host
    }

    override fun toString(): String = (if (wildcard) "*." else "") + host + (if (port >= 0) ":$port" else "")

    companion object {
        @JvmStatic
        fun parse(pattern: String): HostPattern {
            var p = pattern.trim().lowercase(java.util.Locale.ROOT)
            fun bad(): Nothing = throw IllegalArgumentException("Invalid host pattern: $pattern")
            var port = -1
            fun port(s: String) {
                port = s.toIntOrNull()?.takeIf { it in 0..65535 } ?: bad()
            }
            if (p.startsWith("[")) {
                val end = p.indexOf(']')
                if (end < 0) bad()
                val rest = p.substring(end + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) bad()
                    port(rest.substring(1))
                }
                val a = AddressRange.literal(p.substring(1, end)) ?: bad()
                return HostPattern(p.substring(0, end + 1), a, false, port)
            }
            if (p.count { it == ':' } > 1) {
                // an IPv6 address without brackets, and so without port
                return HostPattern("[$p]", AddressRange.literal(p) ?: bad(), false, -1)
            }
            val colon = p.indexOf(':')
            if (colon >= 0) {
                port(p.substring(colon + 1))
                p = p.substring(0, colon)
            }
            val wildcard = p.startsWith("*.")
            if (wildcard) p = p.substring(2)
            if (p.isEmpty() || '*' in p || '/' in p) bad()
            AddressRange.literal(p)?.let { if (!wildcard) return HostPattern(p, it, false, port) }
            // non-ASCII names compare in their ASCII form, as URLs give them
            val ascii = dev.mooner.neonjs.ext.WebGlobals.domainToAscii(p) ?: bad()
            return HostPattern(ascii, null, wildcard, port)
        }
    }
}

/** An address range in CIDR notation (`10.0.0.0/8`, `::1/128`; a bare address is the range of that address alone). */
class AddressRange private constructor(private val base: ByteArray, private val bits: Int) {
    fun contains(a: InetAddress): Boolean {
        val b = a.address
        if (b.size != base.size) return false
        var left = bits
        var i = 0
        while (left > 0) {
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((b[i].toInt() and mask) != (base[i].toInt() and mask)) return false
            left -= 8
            i++
        }
        return true
    }

    companion object {
        @JvmStatic
        fun parse(cidr: String): AddressRange {
            val slash = cidr.indexOf('/')
            val addr = (if (slash < 0) cidr else cidr.substring(0, slash)).trim().removePrefix("[").removeSuffix("]")
            val bytes = literal(addr)?.address ?: throw IllegalArgumentException("Not an IP address: $cidr")
            val bits = if (slash < 0) bytes.size * 8 else cidr.substring(slash + 1).trim().toIntOrNull() ?: throw IllegalArgumentException("Invalid prefix length: $cidr")
            require(bits in 0..bytes.size * 8) { "Invalid prefix length: $cidr" }
            return AddressRange(bytes, bits)
        }

        /** [s] as an IP address literal, without any name lookup; null when it is none. */
        internal fun literal(s: String): InetAddress? {
            val v4 = s.split('.')
            if (v4.size == 4 && v4.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 }) {
                return InetAddress.getByAddress(ByteArray(4) { v4[it].toInt().toByte() })
            }
            if (':' !in s || !s.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
            // contains ':' and only address characters: InetAddress parses it as a literal, with no lookup
            return try {
                InetAddress.getByName(s)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * The rule against non-public addresses: loopback, private (RFC 1918, unique local), carrier-grade NAT, link-local,
 * multicast, unspecified, documentation, benchmarking and reserved ranges, and the IPv4 addresses inside IPv6 ones
 * (mapped, NAT64, 6to4) by the IPv4 rule.
 */
object AddressRules {
    private val V4_BLOCKED = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24",
        "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
        "224.0.0.0/4", "240.0.0.0/4",
    ).map { AddressRange.parse(it) }
    private val V6_BLOCKED = listOf(
        "::/128", "::1/128", "100::/64", "2001::/23", "2001:db8::/32", "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8",
    ).map { AddressRange.parse(it) }
    private val NAT64 = AddressRange.parse("64:ff9b::/96")
    private val SIX_TO_FOUR = AddressRange.parse("2002::/16")

    /** Whether [a] is a public address. */
    @JvmStatic
    fun isPublic(a: InetAddress): Boolean {
        if (a is Inet4Address) return V4_BLOCKED.none { it.contains(a) }
        if (a !is Inet6Address) return false
        val b = a.address
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) addresses
        if (b.copyOfRange(0, 10).all { it == 0.toByte() } && (b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte() || b[10] == 0.toByte() && b[11] == 0.toByte())) {
            if (b[10] == 0.toByte() && b.copyOfRange(12, 16).all { it == 0.toByte() }) return false
            return isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        }
        if (NAT64.contains(a)) return isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        if (SIX_TO_FOUR.contains(a)) return isPublic(InetAddress.getByAddress(b.copyOfRange(2, 6)))
        return V6_BLOCKED.none { it.contains(a) }
    }

    /** Whether the policy lets a connection go to [a]: a public address, or one in an allowed range. */
    @JvmStatic
    fun allows(options: NetOptions, a: InetAddress): Boolean = isPublic(a) || options.allowedAddresses.any { it.contains(a) }
}

/** A connection refused by the network policy. */
class PolicyException(message: String) : ConnectException(message)

/**
 * The sockets of the policy's client: each checks the address it is about to connect to, before any packet is sent,
 * so names resolving to a refused address, IP literals and redirects all meet the same rule.
 */
internal class PolicySocketFactory(private val options: NetOptions) : SocketFactory() {
    private inner class PolicySocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val a = (endpoint as? InetSocketAddress)?.address ?: throw PolicyException("an unresolved address is not allowed by the network policy")
            // the address itself is not told: a script must not learn what internal names resolve to
            if (!AddressRules.allows(options, a)) throw PolicyException("the address is not allowed by the network policy (not a public address)")
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(): Socket = PolicySocket()

    private fun refuse(): Nothing = throw UnsupportedOperationException("connected sockets are not made by the policy's factory")

    override fun createSocket(host: String?, port: Int): Socket = refuse()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = refuse()
    override fun createSocket(host: InetAddress?, port: Int): Socket = refuse()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = refuse()
}
