package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ipinfo.IpInfoStore
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.snell.SnellBean

/**
 * Prepare an independent export copy using a caller-verified group DNS answer.
 * Only formats whose endpoint and protocol identity can be preserved are eligible.
 * A null return means there is no safe substitution; the caller may export the original.
 */
internal fun AbstractBean.copyForEntryAddress(answer: String?): AbstractBean? {
    val originalHost = serverAddress?.trim()?.takeUnless { it.isBlank() || IpInfoStore.isIpLiteral(it) }
        ?: return null
    val ip = answer?.trim()?.takeIf(IpInfoStore::isIpLiteral)?.removeSurrounding("[", "]")
        ?: return null
    if (!canExportEntryAddress()) return null
    return clone().apply {
        keepOriginalConnectionIdentity(originalHost)
        serverAddress = ip
        finalAddress = ip
    }
}

/** Exclude raw config, third-party plugins and transports whose authority cannot be written back. */
internal fun AbstractBean.canExportEntryAddress(): Boolean {
    if (!customOutboundJson.isNullOrBlank()) return false
    return when (this) {
        is ShadowsocksBean -> plugin.isNullOrBlank()
        is TrojanGoBean -> plugin.isNullOrBlank()
        is StandardV2RayBean -> type != "grpc" || security == "tls"
        is AnyTLSBean, is HysteriaBean, is JuicityBean, is MASQUEBean, is MieruBean,
        is SOCKSBean, is SnellBean, is SSHBean, is TuicBean, is WireGuardBean -> true
        else -> false
    }
}

/** Replacing a dial address must not turn an implicit TLS identity or HTTP Host into an IP. */
private fun AbstractBean.keepOriginalConnectionIdentity(hostname: String) {
    val hostWithPort = "$hostname:$serverPort"
    when (this) {
        is AnyTLSBean -> if (sni.isNullOrBlank()) sni = hostname
        is HysteriaBean -> if (sni.isNullOrBlank()) sni = hostname
        is JuicityBean -> if (sni.isNullOrBlank()) sni = hostname
        is MASQUEBean -> if (sni.isNullOrBlank()) sni = hostname
        is TuicBean -> if (sni.isNullOrBlank()) sni = hostname
        is TrojanGoBean -> {
            if (sni.isNullOrBlank()) sni = hostname
            if (type == "ws" && host.isNullOrBlank()) host = hostname
        }
        is StandardV2RayBean -> {
            if (security == "tls" && sni.isNullOrBlank()) sni = hostname
            if (host.isNullOrBlank()) {
                host = when (type) {
                    "ws", "httpupgrade" -> hostWithPort
                    "http", "xhttp", "splithttp" -> hostname
                    else -> host
                }
            }
        }
    }
}
