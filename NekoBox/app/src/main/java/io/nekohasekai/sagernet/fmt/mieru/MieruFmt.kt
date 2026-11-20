package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundMieruBean(bean: MieruBean): SingBoxOptions.Outbound_MieruOptions {
    return SingBoxOptions.Outbound_MieruOptions().apply {
        type = "mieru"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        val range = bean.portRange?.trim().orEmpty()
        if (range.isNotEmpty()) {
            port_range = range
        } else {
            server_port = bean.serverPort
        }
        transport = (bean.protocol?.trim()?.uppercase()).takeUnless { it.isNullOrEmpty() } ?: "TCP"
        if (transport == "UDP") mtu = bean.mtu ?: 1400
        udp = true
        username = bean.username ?: ""
        password = bean.password ?: ""
        bean.multiplexing?.trim()?.takeIf { it.isNotEmpty() }?.let { multiplexing = it }
        bean.handshakeMode?.trim()?.takeIf { it.isNotEmpty() }?.let { handshake_mode = it }
    }
}

fun MieruBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val effectivePort = serverPort?.takeIf { it in 1..65535 }
        ?: portRange?.substringBefore('-')?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
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
    portRange?.trim()?.takeIf { it.isNotEmpty() }?.let {
        builder.addQueryParameter("port-range", it)
    }
    multiplexing?.trim()?.takeIf { it.isNotEmpty() }?.let {
        builder.addQueryParameter("multiplexing", it)
    }
    handshakeMode?.trim()?.takeIf { it.isNotEmpty() }?.let {
        builder.addQueryParameter("handshake-mode", it)
    }
    return builder.toLink("mieru")
}

fun parseMieru(url: String): MieruBean {
    val link = url.trim().replace("mieru://", "https://").toHttpUrlOrNull()
        ?: error("invalid mieru link ${url.redactLink()}")
    return MieruBean().apply {
        serverAddress = link.host.trim().removeSurrounding("[", "]")
        serverPort = link.port
        username = link.username
        password = link.password
        name = link.fragment ?: ""
        val proto = (link.queryParameter("transport") ?: link.queryParameter("protocol"))
            ?.trim()?.uppercase()
        protocol = if (proto == "UDP") "UDP" else "TCP"
        mtu = link.queryParameter("mtu")?.toIntOrNull() ?: 1400
        portRange = link.queryParameter("port-range") ?: link.queryParameter("portRange") ?: ""
        multiplexing = link.queryParameter("multiplexing") ?: ""
        handshakeMode = link.queryParameter("handshake-mode") ?: link.queryParameter("handshakeMode") ?: ""
    }
}
