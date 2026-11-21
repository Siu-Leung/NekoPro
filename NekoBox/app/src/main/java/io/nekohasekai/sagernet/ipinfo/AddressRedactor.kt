package io.nekohasekai.sagernet.ipinfo

import java.net.IDN

/** Screenshot privacy is a display policy; it must not alter dialling or exports. */
object AddressRedactor {
    private const val HIDDEN = "•••"
    private val ipv6 = Regex("(?i)\\[?[0-9a-f:.]*:[0-9a-f:.]*:[0-9a-f:.]*(?:%[a-z0-9_.~-]+)?\\]?")
    private val ipv4 = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")

    fun redactAddress(address: String): String = if (address.isBlank()) "" else HIDDEN

    fun maskIp(ip: String, enabled: Boolean): String = if (enabled) redactAddress(ip) else ip

    /** Keep owner/name text, not literal IPs or the node's known hostname (also in IDN form). */
    fun scrub(text: String, host: String = ""): String {
        val cleanHost = host.trim().removeSurrounding("[", "]").trimEnd('.')
        val knownHosts = listOf(cleanHost, runCatching { IDN.toASCII(cleanHost) }.getOrDefault(""), IDN.toUnicode(cleanHost))
        var result = text
        for (known in knownHosts.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }) {
            val pattern = "(?<![\\p{L}\\p{N}])${Regex.escape(known)}\\.?(?![\\p{L}\\p{N}])"
            result = Regex(pattern, RegexOption.IGNORE_CASE).replace(result, HIDDEN)
        }
        // Preserve punctuation around a bare IPv6 literal (e.g. "AS1:2001:db8::1").
        result = ipv6.replace(result) { match ->
            val value = match.value
            val candidate = value.removeSurrounding("[", "]").substringBefore('%').trimEnd('.')
            when {
                IpInfoStore.isIpLiteral(candidate) -> HIDDEN
                candidate.startsWith(':') && IpInfoStore.isIpLiteral(candidate.removePrefix(":")) -> ":$HIDDEN"
                else -> value
            }
        }
        return ipv4.replace(result) { if (IpInfoStore.isIpLiteral(it.value)) HIDDEN else it.value }
    }

    /** Never reuse a generated title: formatter output may include an address or a URL. */
    fun cardTitle(displayName: String, explicitName: String?, host: String, hiddenTitle: String, hide: Boolean): String =
        when {
            !hide -> displayName
            explicitName.isNullOrBlank() -> hiddenTitle
            else -> scrub(explicitName, host)
        }
}
