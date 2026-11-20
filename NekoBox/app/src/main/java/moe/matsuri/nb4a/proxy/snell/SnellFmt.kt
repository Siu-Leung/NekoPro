package moe.matsuri.nb4a.proxy.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    val clampedVersion = (bean.version ?: 4).coerceIn(4, 6)
    // sing-box 1.14 native snell outbound accepts version 4 (v4/v5 compatible client) or 6.
    val nativeVersion = if (clampedVersion == 6) 6 else 4
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        server_port = bean.serverPort
        psk = bean.psk
        val uk = bean.userkey?.trim()
        if (!uk.isNullOrEmpty()) userkey = uk
        version = nativeVersion
        reuse = true
        if (nativeVersion == 6) {
            mode = bean.mode?.takeIf { it.isNotBlank() } ?: "default"
        } else {
            val obfs = bean.obfsMode?.trim()?.lowercase()
            if (obfs == "http" || obfs == "tls") {
                obfs_mode = obfs
                val host = bean.obfsHost?.trim()
                if (!host.isNullOrEmpty()) {
                    obfs_host = host
                }
            }
        }
    }
}

fun SnellBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val clampedVersion = (version ?: 4).coerceIn(4, 6)
    val builder = linkBuilder()
        .host(cleanHost)
        .port(serverPort ?: 443)
        .username(psk ?: "")
    val beanName = name
    if (!beanName.isNullOrBlank()) {
        builder.encodedFragment(beanName.urlSafe())
    }
    builder.addQueryParameter("version", clampedVersion.toString())
    if (!userkey.isNullOrBlank()) builder.addQueryParameter("userkey", userkey!!.trim())
    if (clampedVersion == 6) builder.addQueryParameter("mode", mode?.takeIf { it.isNotBlank() } ?: "default")
    val obfsMode = this.obfsMode
    if (!obfsMode.isNullOrBlank() && obfsMode != "none") {
        builder.addQueryParameter("obfs", obfsMode)
        val obfsHost = this.obfsHost
        if (!obfsHost.isNullOrBlank()) {
            builder.addQueryParameter("obfs-host", obfsHost)
        }
    }
    val clientFingerprint = this.clientFingerprint
    if (!clientFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", clientFingerprint)
    }
    return builder.toLink("snell")
}

private fun percentDecodePreservingPlus(raw: String): String {
    return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
}

fun parseSnell(url: String): SnellBean {
    val trimmedUrl = url.trim()
    val mainPart = trimmedUrl.substringBefore('#').trim()
    val rawFragment = if (trimmedUrl.contains('#')) trimmedUrl.substringAfter('#').trim() else ""
    val decodedName = if (rawFragment.isNotBlank()) {
        percentDecodePreservingPlus(rawFragment)
    } else ""

    val rawAuthority = mainPart.substringAfter("snell://", "")
    val hasUserInfoColon = rawAuthority.contains('@') && rawAuthority.substringBeforeLast('@').contains(':')

    val link = mainPart.replace("snell://", "https://").toHttpUrlOrNull()
    if (link != null) {
        return SnellBean().apply {
            serverAddress = link.host.trim().removeSurrounding("[", "]")
            serverPort = link.port
            name = decodedName.ifBlank { link.fragment ?: "" }
            psk = if (hasUserInfoColon || link.encodedPassword.isNotEmpty()) {
                "${link.username}:${link.password}"
            } else {
                link.username
            }
            version = (link.queryParameter("version")?.toIntOrNull() ?: 4).coerceIn(4, 6)
            mode = link.queryParameter("mode")?.takeIf { it.isNotBlank() } ?: "default"
            obfsMode = link.queryParameter("obfs")?.takeIf { it.isNotBlank() } ?: "none"
            obfsHost = link.queryParameter("obfs-host") ?: ""
            clientFingerprint = link.queryParameter("fp") ?: ""
            userkey = link.queryParameter("userkey") ?: ""
        }
    }

    // Fallback regex parser for non-standard URIs (supports IPv6 with [] and IPv4/domain)
    val regex = Regex("""^snell://([^@]+)@(?:\[([a-fA-F0-9:]+)\]|([^:]+)):(\d+)(?:\?(.*))?$""")
    val match = regex.find(mainPart) ?: error("invalid snell link ${url.redactLink()}")
    val psk = percentDecodePreservingPlus(match.groupValues[1])
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
        serverPort = portStr.toIntOrNull() ?: 443
        name = decodedName
        this.psk = psk
        version = (queryParams["version"]?.toIntOrNull() ?: 4).coerceIn(4, 6)
        mode = queryParams["mode"]?.takeIf { it.isNotBlank() } ?: "default"
        obfsMode = queryParams["obfs"]?.takeIf { it.isNotBlank() } ?: "none"
        obfsHost = queryParams["obfs-host"] ?: ""
        clientFingerprint = queryParams["fp"] ?: ""
        userkey = queryParams["userkey"] ?: ""
    }
}
