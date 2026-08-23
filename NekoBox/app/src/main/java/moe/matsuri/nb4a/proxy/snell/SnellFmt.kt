package moe.matsuri.nb4a.proxy.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
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
    val builder = linkBuilder()
        .host(serverAddress!!)
        .port(serverPort!!)
        .username(psk!!)
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
    val link = url.replace("snell://", "https://").toHttpUrlOrNull() ?: error(
        "invalid snell link $url"
    )
    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        psk = link.username
        version = link.queryParameter("version")?.toIntOrNull() ?: 4
        mode = link.queryParameter("mode") ?: "default"
        obfsMode = link.queryParameter("obfs") ?: "none"
        obfsHost = link.queryParameter("obfs-host") ?: ""
        clientFingerprint = link.queryParameter("fp") ?: ""
    }
}
