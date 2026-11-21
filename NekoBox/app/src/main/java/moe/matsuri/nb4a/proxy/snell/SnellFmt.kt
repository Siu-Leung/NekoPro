package moe.matsuri.nb4a.proxy.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

private const val SNELL_DEFAULT_VERSION = 4
private const val SNELL_MIN_VERSION = 4
private const val SNELL_MAX_VERSION = 6
private const val SNELL_V6_VERSION = 6
private const val SNELL_DEFAULT_PORT = 443
private const val SNELL_DEFAULT_MODE = "default"
private const val SNELL_OBFS_NONE = "none"
private const val SNELL_SCHEME_PREFIX = "snell://"

/**
 * Validates that a Snell protocol version is supported by sing-box native Snell outbound (v4–v6).
 *
 * @param version Parsed or stored Snell version (`null` defaults to v4).
 * @return Validated Snell version in `4..6`.
 * @throws IllegalArgumentException When the version is outside `4..6` (e.g. legacy v1–v3).
 */
internal fun requireSupportedSnellVersion(version: Int?): Int {
    val resolvedVersion = version ?: SNELL_DEFAULT_VERSION
    require(resolvedVersion in SNELL_MIN_VERSION..SNELL_MAX_VERSION) {
        "Unsupported Snell version: $resolvedVersion (only versions $SNELL_MIN_VERSION-$SNELL_MAX_VERSION are supported)"
    }
    return resolvedVersion
}

/**
 * Builds the sing-box native Snell outbound options from a stored [SnellBean].
 *
 * @param bean Configured Snell node bean.
 * @return Populated [SingBoxOptions.Outbound_SnellOptions].
 * @throws IllegalArgumentException When [SnellBean.version] is outside `4..6`.
 */
fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    val validatedVersion = requireSupportedSnellVersion(bean.version)
    // sing-box 1.14 native snell outbound accepts version 4 (v4/v5 compatible client) or 6.
    val nativeVersion = if (validatedVersion == SNELL_V6_VERSION) SNELL_V6_VERSION else SNELL_DEFAULT_VERSION
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        server_port = bean.serverPort
        psk = bean.psk
        val trimmedUserKey = bean.userkey?.trim()
        if (!trimmedUserKey.isNullOrEmpty()) userkey = trimmedUserKey
        version = nativeVersion
        reuse = true
        if (nativeVersion == SNELL_V6_VERSION) {
            mode = bean.mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: SNELL_DEFAULT_MODE
        } else {
            val normalizedObfs = bean.obfsMode?.trim()?.lowercase()
            if (normalizedObfs == "http" || normalizedObfs == "tls") {
                obfs_mode = normalizedObfs
                val trimmedObfsHost = bean.obfsHost?.trim()
                if (!trimmedObfsHost.isNullOrEmpty()) {
                    obfs_host = trimmedObfsHost
                }
            }
        }
    }
}

/**
 * Serializes this [SnellBean] into a `snell://` share link.
 *
 * @return Encoded `snell://` URI string.
 * @throws IllegalArgumentException When [SnellBean.version] is outside `4..6`.
 */
fun SnellBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val validatedVersion = requireSupportedSnellVersion(version)
    val builder = linkBuilder()
        .host(cleanHost)
        .port(serverPort ?: SNELL_DEFAULT_PORT)
        .username(psk ?: "")
    val beanName = name
    if (!beanName.isNullOrBlank()) {
        builder.encodedFragment(beanName.urlSafe())
    }
    builder.addQueryParameter("version", validatedVersion.toString())
    if (!userkey.isNullOrBlank()) builder.addQueryParameter("userkey", userkey!!.trim())
    if (validatedVersion == SNELL_V6_VERSION) {
        val normalizedMode = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: SNELL_DEFAULT_MODE
        builder.addQueryParameter("mode", normalizedMode)
    } else {
        // Snell v6 removed legacy HTTP/TLS obfs; only emit obfs parameters for v4/v5.
        val normalizedObfs = this.obfsMode?.trim()?.lowercase()
        if (!normalizedObfs.isNullOrBlank() && normalizedObfs != SNELL_OBFS_NONE) {
            builder.addQueryParameter("obfs", normalizedObfs)
            val trimmedObfsHost = this.obfsHost?.trim()
            if (!trimmedObfsHost.isNullOrBlank()) {
                builder.addQueryParameter("obfs-host", trimmedObfsHost)
            }
        }
    }
    val trimmedFingerprint = this.clientFingerprint?.trim()
    if (!trimmedFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", trimmedFingerprint)
    }
    return builder.toLink("snell")
}

private fun percentDecodePreservingPlus(raw: String): String {
    return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
}

private fun parseSnellVersionParam(rawVersion: String?): Int {
    val trimmed = rawVersion?.trim().orEmpty()
    if (trimmed.isEmpty()) return SNELL_DEFAULT_VERSION
    val parsed = trimmed.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid Snell version parameter: $trimmed")
    return requireSupportedSnellVersion(parsed)
}

/**
 * Parses a `snell://` share link into a [SnellBean].
 *
 * Accepts case-insensitive `snell://` schemes, both `userkey` and `user-key` query keys,
 * and rejects legacy Snell versions outside `4..6` instead of silently downgrading/upgrading.
 *
 * @param url Raw `snell://` URI string.
 * @return Parsed [SnellBean].
 * @throws IllegalArgumentException When the link is malformed or specifies an unsupported version.
 */
fun parseSnell(url: String): SnellBean {
    val trimmedUrl = url.trim()
    require(trimmedUrl.startsWith(SNELL_SCHEME_PREFIX, ignoreCase = true)) {
        "invalid snell link ${url.redactLink()}"
    }
    val mainPart = trimmedUrl.substringBefore('#').trim()
    val rawFragment = if (trimmedUrl.contains('#')) trimmedUrl.substringAfter('#').trim() else ""
    val decodedName = if (rawFragment.isNotBlank()) {
        percentDecodePreservingPlus(rawFragment)
    } else ""

    val rawAuthority = mainPart.substring(SNELL_SCHEME_PREFIX.length)
    val hasUserInfoColon = rawAuthority.contains('@') && rawAuthority.substringBeforeLast('@').contains(':')

    val normalizedHttpUrl = "https://$rawAuthority".toHttpUrlOrNull()
    if (normalizedHttpUrl != null) {
        return SnellBean().apply {
            serverAddress = normalizedHttpUrl.host.trim().removeSurrounding("[", "]")
            serverPort = normalizedHttpUrl.port
            name = decodedName.ifBlank { normalizedHttpUrl.fragment ?: "" }
            psk = if (hasUserInfoColon || normalizedHttpUrl.encodedPassword.isNotEmpty()) {
                "${normalizedHttpUrl.username}:${normalizedHttpUrl.password}"
            } else {
                normalizedHttpUrl.username
            }
            version = parseSnellVersionParam(normalizedHttpUrl.queryParameter("version"))
            mode = normalizedHttpUrl.queryParameter("mode")?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: SNELL_DEFAULT_MODE
            obfsMode = normalizedHttpUrl.queryParameter("obfs")?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: SNELL_OBFS_NONE
            obfsHost = normalizedHttpUrl.queryParameter("obfs-host")?.trim() ?: ""
            clientFingerprint = normalizedHttpUrl.queryParameter("fp")?.trim() ?: ""
            userkey = (normalizedHttpUrl.queryParameter("userkey")
                ?: normalizedHttpUrl.queryParameter("user-key"))?.trim() ?: ""
        }
    }

    // Fallback regex parser for non-standard URIs (supports IPv6 with [] and IPv4/domain).
    val regex = Regex(
        """^snell://([^@]+)@(?:\[([a-fA-F0-9:]+)\]|([^:]+)):(\d+)(?:\?(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    val match = regex.find(mainPart) ?: throw IllegalArgumentException("invalid snell link ${url.redactLink()}")
    val parsedPsk = percentDecodePreservingPlus(match.groupValues[1])
    val host = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
    val portStr = match.groupValues[4]
    val queryStr = match.groupValues[5]
    val queryParams = if (queryStr.isNotEmpty()) {
        queryStr.split('&').filter { it.contains('=') }.associate {
            val parts = it.split('=', limit = 2)
            percentDecodePreservingPlus(parts[0]) to percentDecodePreservingPlus(parts.getOrElse(1) { "" })
        }
    } else emptyMap()

    return SnellBean().apply {
        serverAddress = host.trim().removeSurrounding("[", "]")
        serverPort = portStr.toIntOrNull() ?: SNELL_DEFAULT_PORT
        name = decodedName
        psk = parsedPsk
        version = parseSnellVersionParam(queryParams["version"])
        mode = queryParams["mode"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: SNELL_DEFAULT_MODE
        obfsMode = queryParams["obfs"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: SNELL_OBFS_NONE
        obfsHost = queryParams["obfs-host"]?.trim() ?: ""
        clientFingerprint = queryParams["fp"]?.trim() ?: ""
        userkey = (queryParams["userkey"] ?: queryParams["user-key"])?.trim() ?: ""
    }
}
