package io.nekohasekai.sagernet.ipinfo

import io.nekohasekai.sagernet.fmt.AbstractBean
import java.net.IDN

/** Screenshot privacy is a display policy; it must not alter dialling or exports. */
object AddressRedactor {
    private const val HIDDEN = "•••"
    private val urlSchemeRegex = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s"'<>]+""")
    private val ipv6 = Regex("(?i)\\[?[0-9a-f:.]*:[0-9a-f:.]*:[0-9a-f:.]*(?:%[a-z0-9_.~-]+)?\\]?")
    private val ipv4 = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val commonFqdn = Regex(
        "(?i)(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|io|me|cc|xyz|top|vip|club|icu|shop|site|online|tech|cloud|pro|dev|app|ai|co|uk|jp|kr|sg|hk|tw|us|de|fr|ru|nl|ca|au|cn|info|biz|link|click|work|live|fun|sbs|cfd|cyou|buzz|moe|one|run|place)\\b\\.?(?![a-z0-9-])"
    )

    fun redactAddress(address: String): String = if (address.isBlank()) "" else HIDDEN

    fun maskIp(ip: String, enabled: Boolean): String = if (enabled) redactAddress(ip) else ip

    /** Keep owner/name text, not literal IPs, URLs, or hostnames (also in IDN form). */
    fun scrub(text: String, host: String = ""): String {
        val cleanHost = host.trim().removeSurrounding("[", "]").trimEnd('.')
        val knownHosts = listOf(
            cleanHost,
            runCatching { IDN.toASCII(cleanHost) }.getOrDefault(""),
            IDN.toUnicode(cleanHost),
        )
        var result = urlSchemeRegex.replace(text) { match ->
            "${match.groupValues[1]}$HIDDEN"
        }
        for (known in knownHosts.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }) {
            // Use ASCII domain boundaries for ASCII hostnames so CJK-adjacent names
            // (e.g. "香港node1.example.com") are still redacted properly.
            val boundaryClass = if (known.all { it.code <= 0x7F }) "A-Za-z0-9-" else "\\p{L}\\p{N}-"
            val pattern = "(?<![$boundaryClass])${Regex.escape(known)}\\.?(?![$boundaryClass])"
            result = Regex(pattern, RegexOption.IGNORE_CASE).replace(result, HIDDEN)
        }
        result = commonFqdn.replace(result, HIDDEN)
        // Preserve non-IP prefixes around a bare IPv6 literal (e.g. "AS1:2001:db8::1", "abcde:2001:db8::1").
        result = ipv6.replace(result) { match ->
            redactIpv6Token(match.value)
        }
        return ipv4.replace(result) { if (IpInfoStore.isIpLiteral(it.value)) HIDDEN else it.value }
    }

    /**
     * Redacts an IPv6 token matched by [ipv6], scanning colon-delimited suffixes when a
     * non-IP hex prefix (such as `"abcde:2001:db8::1"`) prevented a full-token match.
     */
    private fun redactIpv6Token(value: String): String {
        val candidate = value.removeSurrounding("[", "]").substringBefore('%').trimEnd('.')
        if (IpInfoStore.isIpLiteral(candidate)) return HIDDEN
        var colonIndex = value.indexOf(':')
        while (colonIndex >= 0 && colonIndex < value.lastIndex) {
            val tail = value.substring(colonIndex + 1)
            val tailCandidate = tail.removeSurrounding("[", "]").substringBefore('%').trimEnd('.')
            if (tailCandidate.contains(':') && IpInfoStore.isIpLiteral(tailCandidate)) {
                return value.substring(0, colonIndex + 1) + HIDDEN
            }
            colonIndex = value.indexOf(':', colonIndex + 1)
        }
        return value
    }

    /** Never reuse a generated title: formatter output may include an address or a URL. */
    fun cardTitle(
        displayName: String,
        explicitName: String?,
        host: String,
        hiddenTitle: String,
        hide: Boolean,
    ): String = when {
        !hide -> displayName
        explicitName.isNullOrBlank() -> hiddenTitle
        else -> scrub(explicitName, host)
    }

    /** Convenience helper formatting [bean] display name under screenshot privacy policy [hide]. */
    fun beanTitle(
        bean: AbstractBean,
        hide: Boolean,
        hiddenTitle: String = HIDDEN,
    ): String = cardTitle(
        displayName = bean.displayName(),
        explicitName = bean.name,
        host = bean.serverAddress.orEmpty(),
        hiddenTitle = hiddenTitle,
        hide = hide,
    )
}
