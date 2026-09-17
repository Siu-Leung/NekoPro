package moe.matsuri.nb4a.proxy.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        server_port = bean.serverPort
        psk = bean.psk
        version = bean.version ?: 4
        if (version == 6) mode = bean.mode ?: "default"
        reuse = true
        udp = true
        // obfs-opts: only set when obfs mode is not "none"
        val obfsMode = bean.obfsMode
        if (!obfsMode.isNullOrBlank() && obfsMode != "none") {
            val opts = HashMap<String, String>()
            opts["mode"] = obfsMode
            val obfsHost = bean.obfsHost
            if (!obfsHost.isNullOrBlank()) {
                opts["host"] = obfsHost
            }
            obfs_opts = opts
        }
        // client fingerprint (uTLS)
        val clientFingerprint = bean.clientFingerprint
        if (!clientFingerprint.isNullOrBlank()) {
            client_fingerprint = clientFingerprint
        }
    }
}

fun SnellBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val builder = linkBuilder()
        .host(cleanHost)
        .port(serverPort ?: 443)
        .username(psk ?: "")
    val beanName = name
    if (!beanName.isNullOrBlank()) {
        builder.encodedFragment(beanName.urlSafe())
    }
    builder.addQueryParameter("version", (version ?: 4).toString())
    if (version == 6) builder.addQueryParameter("mode", mode ?: "default")
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

fun parseSnell(url: String): SnellBean {
    val trimmedUrl = url.trim()
    val mainPart = trimmedUrl.substringBefore('#').trim()
    val rawFragment = if (trimmedUrl.contains('#')) trimmedUrl.substringAfter('#').trim() else ""
    val decodedName = if (rawFragment.isNotBlank()) {
        runCatching { URLDecoder.decode(rawFragment, "UTF-8") }.getOrDefault(rawFragment)
    } else ""

    val link = mainPart.replace("snell://", "https://").toHttpUrlOrNull()
    if (link != null) {
        return SnellBean().apply {
            serverAddress = link.host.trim().removeSurrounding("[", "]")
            serverPort = link.port
            name = decodedName.ifBlank { link.fragment ?: "" }
            psk = link.username
            version = link.queryParameter("version")?.toIntOrNull() ?: 4
            mode = link.queryParameter("mode") ?: "default"
            obfsMode = link.queryParameter("obfs") ?: "none"
            obfsHost = link.queryParameter("obfs-host") ?: ""
            clientFingerprint = link.queryParameter("fp") ?: ""
        }
    }

    // Fallback regex parser for non-standard URIs (supports IPv6 with [] and IPv4/domain)
    val regex = Regex("""^snell://([^@]+)@(?:\[([a-fA-F0-9:]+)\]|([^:]+)):(\d+)(?:\?(.*))?$""")
    val match = regex.find(mainPart) ?: error("invalid snell link $url")
    val psk = match.groupValues[1]
    val host = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
    val portStr = match.groupValues[4]
    val queryStr = match.groupValues[5]
    val queryParams = if (queryStr.isNotEmpty()) {
        queryStr.split('&').filter { it.contains('=') }.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
    } else emptyMap()

    return SnellBean().apply {
        serverAddress = host.trim().removeSurrounding("[", "]")
        serverPort = portStr.toIntOrNull() ?: 443
        name = decodedName
        this.psk = psk
        version = queryParams["version"]?.toIntOrNull() ?: 4
        mode = queryParams["mode"] ?: "default"
        obfsMode = queryParams["obfs"] ?: "none"
        obfsHost = queryParams["obfs-host"] ?: ""
        clientFingerprint = queryParams["fp"] ?: ""
    }
}
