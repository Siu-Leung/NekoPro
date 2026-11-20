package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun parseReservedBytes(anyStr: String): List<Int>? {
    val trimmed = anyStr.trim()
    if (trimmed.isEmpty()) return null
    val list = trimmed.listByLineOrComma()
    if (list.size == 3) {
        val parsed = ArrayList<Int>(3)
        var valid = true
        for (s in list) {
            val v = s.replace("[", "").replace("]", "").trim().toIntOrNull()
            if (v == null || v !in 0..255) {
                valid = false
                break
            }
            parsed.add(v)
        }
        if (valid) return parsed
    }
    return runCatching {
        val bytes = Util.b64Decode(trimmed)
        if (bytes.size == 3) bytes.map { it.toInt() and 0xFF } else null
    }.getOrNull()
}

fun normalizeWireGuardAddresses(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.listByLineOrComma().mapNotNull { item ->
        val addr = item.trim()
        when {
            addr.isEmpty() -> null
            addr.contains("/") -> addr
            addr.contains(":") -> "$addr/128"
            else -> "$addr/32"
        }
    }
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = normalizeWireGuardAddresses(bean.localAddress)
        private_key = bean.privateKey
        mtu = bean.mtu
        peers = listOf(
            SingBoxOptions.WireGuardEndpointPeer().apply {
                address = bean.serverAddress
                port = bean.serverPort
                public_key = bean.peerPublicKey
                if (!bean.peerPreSharedKey.isNullOrBlank()) {
                    pre_shared_key = bean.peerPreSharedKey
                }
                allowed_ips = normalizeWireGuardAddresses(bean.allowedIps).ifEmpty {
                    listOf("0.0.0.0/0", "::/0")
                }
                val keepalive = bean.persistentKeepalive ?: 0
                if (keepalive > 0) {
                    persistent_keepalive_interval = keepalive
                }
                val rawReserved = bean.reserved ?: ""
                if (rawReserved.isNotBlank()) {
                    reserved = parseReservedBytes(rawReserved)
                }
            }
        )
    }
}
