package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val MIERU_SCHEME_REGEX = Regex("^mieru://", RegexOption.IGNORE_CASE)

/**
 * Normalizes a Mieru port-range string by converting colon separators (`20000:20100`)
 * into the hyphen format (`20000-20100`) required by mihomo's Mieru client.
 *
 * @param raw User-entered or imported port-range string.
 * @return Normalized `"begin-end"` string (or empty string if blank).
 * @throws IllegalArgumentException if [raw] is non-blank and does not form a valid `1..65535` range.
 */
internal fun normalizeMieruPortRange(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val normalized = trimmed.replace(':', '-')
    val parts = normalized.split('-').map { it.trim() }
    require(parts.size == 2) { "Invalid Mieru port range '$raw': expected start-end" }
    val startPort = parts[0].toIntOrNull()
    val endPort = parts[1].toIntOrNull()
    require(startPort != null && endPort != null && startPort in 1..65535 && endPort in startPort..65535) {
        "Invalid Mieru port range '$raw': ports must satisfy 1 <= start <= end <= 65535"
    }
    return "$startPort-$endPort"
}

fun buildSingBoxOutboundMieruBean(bean: MieruBean): SingBoxOptions.Outbound_MieruOptions {
    return SingBoxOptions.Outbound_MieruOptions().apply {
        type = "mieru"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        val range = bean.portRange?.trim().orEmpty()
        if (range.isNotEmpty()) {
            port_range = normalizeMieruPortRange(range)
        } else {
            server_port = bean.serverPort
        }
        transport = (bean.protocol?.trim()?.uppercase()).takeUnless { it.isNullOrEmpty() } ?: "TCP"
        if (transport == "UDP") mtu = bean.mtu ?: 1400
        udp = true
        username = bean.username ?: ""
        password = bean.password ?: ""
        bean.multiplexing?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { multiplexing = it }
        bean.handshakeMode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { handshake_mode = it }
    }
}

fun MieruBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val normalizedRange = runCatching { normalizeMieruPortRange(portRange) }.getOrDefault(portRange?.trim().orEmpty())
    val effectivePort = serverPort?.takeIf { it in 1..65535 }
        ?: normalizedRange.substringBefore('-').trim().toIntOrNull()?.takeIf { it in 1..65535 }
        ?: 443
    val builder = linkBuilder()
        .host(cleanHost)
        .port(effectivePort)
        .username(username ?: "")
        .password(password ?: "")
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name!!.urlSafe())
    }
    val proto = (protocol?.trim()?.uppercase()).takeUnless { it.isNullOrEmpty() } ?: "TCP"
    builder.addQueryParameter("transport", proto)
    if (proto == "UDP" && (mtu ?: 1400) != 1400) {
        builder.addQueryParameter("mtu", (mtu ?: 1400).toString())
    }
    if (normalizedRange.isNotEmpty()) {
        builder.addQueryParameter("port-range", normalizedRange)
    }
    multiplexing?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
        builder.addQueryParameter("multiplexing", it)
    }
    handshakeMode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
        builder.addQueryParameter("handshake-mode", it)
    }
    return builder.toLink("mieru")
}

/**
 * Parses a `mieru://` share link, supporting both NekoBox/Clash query parameters
 * (`?port-range=`, `?transport=`, `#name`) and upstream `mieru get share-link`
 * query parameters (`?port=20000` or `?port=20000-20100`, `?profile=...`).
 */
fun parseMieru(url: String): MieruBean {
    val trimmed = url.trim()
    val replaced = MIERU_SCHEME_REGEX.replaceFirst(trimmed, "https://")
    val link = replaced.toHttpUrlOrNull()
        ?: error("invalid mieru link ${url.redactLink()}")
    // Detect whether the authority explicitly specified a port (to avoid OkHttp's default 443
    // overriding upstream `mieru get share-link` `?port=` query parameter).
    val authority = replaced.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPortPart = authority.substringAfterLast('@')
    val hasExplicitAuthorityPort = if (hostPortPart.startsWith('[')) {
        hostPortPart.substringAfter(']', "").startsWith(':')
    } else {
        ':' in hostPortPart
    }

    val rawQueryPort = link.queryParameter("port")?.trim().orEmpty()
    val rawPortRange = (link.queryParameter("port-range") ?: link.queryParameter("portRange"))
        ?.trim().orEmpty()

    var parsedPort = if (hasExplicitAuthorityPort) link.port else 0
    var parsedRange = rawPortRange
    if (rawQueryPort.isNotEmpty()) {
        if ('-' in rawQueryPort || ':' in rawQueryPort) {
            if (parsedRange.isEmpty()) parsedRange = rawQueryPort
        } else {
            val singlePort = rawQueryPort.toIntOrNull()
            if (singlePort != null && singlePort in 1..65535 && !hasExplicitAuthorityPort) {
                parsedPort = singlePort
            }
        }
    }
    val normalizedRange = if (parsedRange.isNotEmpty()) normalizeMieruPortRange(parsedRange) else ""
    if (parsedPort !in 1..65535) {
        parsedPort = normalizedRange.substringBefore('-').toIntOrNull()?.takeIf { it in 1..65535 }
            ?: link.port
    }

    return MieruBean().apply {
        serverAddress = link.host.trim().removeSurrounding("[", "]")
        serverPort = parsedPort
        username = link.username
        password = link.password
        name = link.fragment?.takeIf { it.isNotBlank() }
            ?: link.queryParameter("profile")?.trim().orEmpty()
        val proto = (link.queryParameter("transport") ?: link.queryParameter("protocol"))
            ?.trim()?.uppercase()
        protocol = if (proto == "UDP") "UDP" else "TCP"
        mtu = link.queryParameter("mtu")?.toIntOrNull() ?: 1400
        portRange = normalizedRange
        multiplexing = link.queryParameter("multiplexing")?.trim()?.uppercase() ?: ""
        handshakeMode = (link.queryParameter("handshake-mode") ?: link.queryParameter("handshakeMode"))
            ?.trim()?.uppercase() ?: ""
    }
}
