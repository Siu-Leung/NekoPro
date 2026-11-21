package moe.matsuri.nb4a

import com.google.common.net.InetAddresses
import java.net.URI

/**
 * Parses legacy sing-box / NekoBox DNS server address strings (e.g. "local",
 * "https://dns.google/dns-query", "tls://1.1.1.1:853", "quic://dns.adguard-dns.com",
 * "h3://dns.google/dns-query", "tcp://8.8.8.8", "udp://223.5.5.5:53",
 * "https+local://223.5.5.5/dns-query", "dhcp://auto", or bare IPv4/IPv6) into
 * sing-box 1.14 typed DNSServerOptions objects.
 */
object SingBoxDnsServerParser {

    /**
     * Builds a direct, group-only resolver, bootstrapped through the existing direct DNS.
     * sing-box 1.14 treats `path` as a URI path, not a query; reject lossy forms here without
     * logging the address (the path can be a credential). Global DNS keeps its existing parser.
     */
    fun parseGroupNodeDns(rawAddress: String, tag: String): SingBoxOptions.DNSServerOptions {
        val input = rawAddress.trim()
        require(input.isNotEmpty() && input.none { it == '?' || it == '#' || it == '@' }) {
            "Invalid group DNS URL"
        }
        val server = try {
            parse(input, tag, domainResolver = "dns-direct", strict = true)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid group DNS URL")
        }
        // An explicit root path is valid and is different from the implicit /dns-query default.
        if (server.type in setOf("https", "h3") && input.endsWith('/') && server.path == null) {
            server.path = "/"
        }
        return server
    }

    fun parse(
        rawAddress: String,
        tag: String,
        defaultDetour: String? = null,
        domainResolver: String? = null,
        strict: Boolean = false,
    ): SingBoxOptions.DNSServerOptions {
        val trimmed = rawAddress.trim()
        require(trimmed.isNotEmpty()) { "Empty DNS server address for $tag" }

        val lower = trimmed.lowercase()
        val isLocalToken = lower == "local" || lower == "system" ||
            (if (strict) lower == "dhcp://auto" else lower.startsWith("dhcp://"))
        if (isLocalToken) {
            return SingBoxOptions.DNSServerOptions().apply {
                this.type = "local"
                this.tag = tag
            }
        }

        val hasScheme = trimmed.contains("://")
        var forceDirect = false
        val scheme: String
        val uriForParse: URI

        if (hasScheme) {
            val rawScheme = trimmed.substringBefore("://").lowercase()
            scheme = if (rawScheme.endsWith("+local")) {
                forceDirect = true
                rawScheme.removeSuffix("+local")
            } else {
                rawScheme
            }
            val rest = trimmed.substringAfter("://")
            uriForParse = parseAuthorityAndPath(scheme, rest)
        } else {
            scheme = "udp"
            uriForParse = parseAuthorityAndPath("udp", trimmed)
        }

        val mappedType = when (scheme) {
            "udp", "tcp", "tls", "https", "quic", "h3" -> scheme
            else -> throw IllegalArgumentException("Unsupported DNS server scheme '$scheme' in '$trimmed'")
        }

        val host = uriForParse.host?.trim()?.removeSurrounding("[", "]")
        require(!host.isNullOrBlank()) { "Missing DNS server host in '$trimmed'" }

        if (strict) {
            require(uriForParse.port == -1 || uriForParse.port in 1..65535)
            require(!uriForParse.rawAuthority.orEmpty().endsWith(':'))
            require(mappedType in setOf("https", "h3") || uriForParse.rawPath.orEmpty().isEmpty())
        }
        val explicitPort = uriForParse.port.takeIf { it in 1..65535 }
        val effectiveDetour = if (forceDirect || defaultDetour.isNullOrBlank()) null else defaultDetour

        return SingBoxOptions.DNSServerOptions().apply {
            this.type = mappedType
            this.tag = tag
            this.server = host
            if (explicitPort != null) {
                this.server_port = explicitPort
            }
            if (mappedType == "https" || mappedType == "h3") {
                val rawPath = uriForParse.rawPath.orEmpty()
                val rawQuery = uriForParse.rawQuery.orEmpty()
                val fullPath = buildString {
                    if (rawPath.isNotEmpty() && rawPath != "/") {
                        append(rawPath)
                    }
                    if (rawQuery.isNotEmpty()) {
                        if (isEmpty()) append("/dns-query")
                        append('?')
                        append(rawQuery)
                    }
                }
                if (fullPath.isNotEmpty()) {
                    this.path = fullPath
                }
            }
            if (effectiveDetour != null) {
                this.detour = effectiveDetour
            }
            if (!isIpLiteral(host) && !domainResolver.isNullOrBlank()) {
                this.domain_resolver = domainResolver
            }
        }
    }

    /**
     * Extracts the bare host (without IPv6 brackets) from a DNS address string,
     * or returns null for non-network transports ("local", "dhcp://...", invalid input).
     */
    fun extractHost(rawAddress: String): String? {
        return runCatching { parse(rawAddress, "probe").server }.getOrNull()
    }

    private fun parseAuthorityAndPath(scheme: String, rest: String): URI {
        // Bare unbracketed IPv6 literal (e.g. "2001:4860:4860::8888" or "2606:4700:4700::1111/dns-query")
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        val suffix = if (authorityEnd >= 0) rest.substring(authorityEnd) else ""
        val normalizedAuthority = if (
            !authority.startsWith("[") &&
            authority.count { it == ':' } >= 2
        ) {
            "[$authority]"
        } else {
            authority
        }
        return URI("$scheme://$normalizedAuthority$suffix")
    }

    /**
     * Strictly validates IPv4/IPv6 literals using Guava [InetAddresses.isInetAddress],
     * matching Go's `netip.ParseAddr` (rejecting leading zeros such as `010.0.0.1`
     * and malformed colon sequences such as `a:b` or `:::`).
     */
    internal fun isIpLiteral(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        val addrPart = clean.substringBefore('%')
        if (addrPart.isEmpty()) return false
        // Reject leading-zero IPv4 octets (e.g. "010.0.0.1") which Go netip rejects.
        if ('.' in addrPart && ':' !in addrPart) {
            val parts = addrPart.split('.')
            if (parts.any { it.length > 1 && it.startsWith('0') }) return false
        }
        return InetAddresses.isInetAddress(addrPart)
    }
}
