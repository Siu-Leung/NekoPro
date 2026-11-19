package io.nekohasekai.sagernet.fmt.masque

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseMasque(url: String): MASQUEBean {
    val link = url.trim().replace("masque://", "https://").toHttpUrlOrNull()
        ?: error("invalid masque link ${url.redactLink()}")
    return MASQUEBean().apply {
        serverAddress = link.host.trim().removeSurrounding("[", "]")
        serverPort = link.port
        name = link.fragment ?: ""

        val priv = link.queryParameter("private_key")
            ?: link.queryParameter("private-key")
            ?: link.queryParameter("key")
            ?: link.username.takeIf { it.isNotBlank() }
        privateKey = priv ?: ""

        val pub = link.queryParameter("public_key")
            ?: link.queryParameter("public-key")
            ?: link.queryParameter("pbk")
            ?: link.queryParameter("peer_public_key")
        publicKey = pub ?: ""

        localAddress = link.queryParameter("local_address")
            ?: link.queryParameter("ip")
            ?: "172.16.0.2/32"
        localAddressV6 = link.queryParameter("local_address_v6")
            ?: link.queryParameter("ipv6")
            ?: ""
        uri = link.queryParameter("uri") ?: ""
        sni = link.queryParameter("sni") ?: link.queryParameter("server_name") ?: ""
        mtu = link.queryParameter("mtu")?.toIntOrNull() ?: 1280
        network = link.queryParameter("network")?.takeIf { it.isNotBlank() } ?: "h3"
        congestionController = (link.queryParameter("congestion_controller")
            ?: link.queryParameter("congestion_control")
            ?: link.queryParameter("cc"))?.takeIf { it.isNotBlank() } ?: "bbr"
        link.queryParameter("insecure")?.let {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("allow_insecure")?.let {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("remote_dns_resolve")?.let {
            remoteDnsResolve = it == "1" || it == "true"
        }
        link.queryParameter("udp")?.let {
            udp = it != "0" && !it.equals("false", ignoreCase = true)
        }
    }
}

fun MASQUEBean.toUri(): String {
    val cleanHost = (serverAddress ?: "").trim().removeSurrounding("[", "]")
    val builder = linkBuilder()
        .host(cleanHost)
        .port(serverPort ?: 443)

    if (!privateKey.isNullOrBlank()) {
        builder.addQueryParameter("private_key", privateKey)
    }
    if (!publicKey.isNullOrBlank()) {
        builder.addQueryParameter("public_key", publicKey)
    }
    if (!localAddress.isNullOrBlank()) {
        builder.addQueryParameter("ip", localAddress)
    }
    if (!localAddressV6.isNullOrBlank()) {
        builder.addQueryParameter("ipv6", localAddressV6)
    }
    if (!uri.isNullOrBlank()) {
        builder.addQueryParameter("uri", uri)
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (mtu != null && mtu != 1280) {
        builder.addQueryParameter("mtu", mtu.toString())
    }
    if (!network.isNullOrBlank() && network != "h3") {
        builder.addQueryParameter("network", network)
    }
    if (!congestionController.isNullOrBlank()) {
        builder.addQueryParameter("congestion_controller", congestionController)
    }
    if (allowInsecure == true) {
        builder.addQueryParameter("insecure", "1")
    }
    if (remoteDnsResolve == true) {
        builder.addQueryParameter("remote_dns_resolve", "1")
    }
    if (udp == false) {
        builder.addQueryParameter("udp", "0")
    }
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name!!.urlSafe())
    }
    return builder.toLink("masque")
}

fun buildSingBoxOutboundMasqueBean(bean: MASQUEBean): SingBoxOptions.Outbound_MasqueOptions {
    return SingBoxOptions.Outbound_MasqueOptions().apply {
        type = "masque"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        server_port = bean.serverPort
        private_key = bean.privateKey
        public_key = bean.publicKey
        local_address = bean.localAddress?.takeIf { it.isNotBlank() }
        local_address_v6 = bean.localAddressV6?.takeIf { it.isNotBlank() }
        uri = bean.uri?.takeIf { it.isNotBlank() }
        server_name = bean.sni?.takeIf { it.isNotBlank() }
        mtu = bean.mtu ?: 1280
        udp = bean.udp != false
        network = bean.network?.takeIf { it.isNotBlank() } ?: "h3"
        congestion_controller = bean.congestionController?.takeIf { it.isNotBlank() }
        skip_cert_verify = bean.allowInsecure == true || DataStore.globalAllowInsecure
        remote_dns_resolve = bean.remoteDnsResolve == true
    }
}
