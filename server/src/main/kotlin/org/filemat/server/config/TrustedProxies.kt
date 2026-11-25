package org.filemat.server.config

import java.net.Inet6Address
import java.net.InetAddress

/**
 * The reverse proxies whose `X-Forwarded-For` and `X-Forwarded-Proto` headers are believed (`FM_TRUSTED_PROXIES`).
 * Nobody by default: without a proxy in front (the Windows zip, a plain `docker run`), anyone could send these headers,
 * get a new rate limit bucket with each made-up address, and write any address into the audit log.
 *
 * Entries are IP addresses or ranges (`10.0.0.0/8`, `fd00::/8`), separated by commas, because IPv6 addresses contain colons.
 */
object TrustedProxies {
    private class Range(val network: ByteArray, val prefixLength: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val fullBytes = prefixLength / 8
            for (i in 0 until fullBytes) if (address[i] != network[i]) return false
            val restBits = prefixLength % 8
            if (restBits == 0) return true
            val mask = (0xFF shl (8 - restBits)) and 0xFF
            return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }
    }

    @Volatile
    private var ranges: List<Range> = emptyList()

    /** Longest client address taken from a header, so a proxy that passes junk through can't fill the logs */
    private const val MAX_ADDRESS_LENGTH = 64

    /**
     * Sets the trusted proxies from the value of `FM_TRUSTED_PROXIES`. Null or blank trusts nobody.
     * Throws [IllegalArgumentException] for an entry that isn't an IP address or range, so a typo doesn't go unnoticed.
     */
    fun configure(raw: String?) {
        ranges = parse(raw)
    }

    /**
     * Parses a list of IP addresses and ranges. Never looks up a host name.
     */
    private fun parse(raw: String?): List<Range> {
        raw ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            parseRange(entry) ?: throw IllegalArgumentException(
                "FM_TRUSTED_PROXIES: \"$entry\" is not an IP address or range (like 10.0.0.0/8). Separate entries with commas."
            )
        }
    }

    private fun parseRange(entry: String): Range? {
        val address = parseAddress(entry.substringBefore('/')) ?: return null
        val bits = address.size * 8
        if ('/' !in entry) return Range(address, bits)
        val prefix = entry.substringAfter('/').toIntOrNull() ?: return null
        if (prefix !in 0..bits) return null
        return Range(address, prefix)
    }

    /**
     * The bytes of an IP address literal, or null when [text] isn't one. IPv4-mapped IPv6 addresses become IPv4.
     */
    internal fun parseAddress(text: String): ByteArray? {
        val value = text.trim().removePrefix("[").removeSuffix("]")
        if (value.isEmpty()) return null

        if (':' !in value) {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                part.toInt().takeIf { it <= 255 } ?: return null
            }
            return ByteArray(4) { bytes[it].toByte() }
        }

        // With a colon, Java parses it as an IPv6 literal and never looks it up
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
        if (address is Inet6Address) {
            // `::ffff:a.b.c.d` given to getByName already comes back as IPv4; this covers the expanded form too
            val b = address.address
            val isMapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            if (isMapped) return b.copyOfRange(12, 16)
        }
        return address.address
    }

    /**
     * Whether [address] is one of the trusted proxies
     */
    fun isTrusted(address: String?): Boolean {
        val current = ranges
        if (current.isEmpty() || address == null) return false
        val bytes = parseAddress(address) ?: return false
        return current.any { it.contains(bytes) }
    }

    /**
     * The client's address: [remoteAddress] itself, unless it is a trusted proxy. Then the `X-Forwarded-For` entries
     * ([forwardedFor], the header values in order) are read from the right, and the first one that isn't a trusted
     * proxy is the client. Entries to its left were sent by the client and could be anything.
     */
    fun clientAddress(remoteAddress: String, forwardedFor: List<String>): String {
        if (!isTrusted(remoteAddress)) return remoteAddress
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        var client = remoteAddress
        for (hop in hops.asReversed()) {
            client = hop
            if (!isTrusted(hop)) break
        }
        return client.take(MAX_ADDRESS_LENGTH)
    }
}
