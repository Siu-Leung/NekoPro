package io.nekohasekai.sagernet.fmt.wireguard

import com.google.common.net.InetAddresses
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

private const val MAX_KEEPALIVE_SECONDS = 65535

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

/**
 * Validates and normalizes comma/newline-delimited WireGuard IP or CIDR entries.
 *
 * Bare IPs receive `/32` (IPv4) or `/128` (IPv6); explicit CIDRs are validated
 * against IPv4 (`0..32`) and IPv6 (`0..128`) prefix bounds.
 *
 * @throws IllegalArgumentException if any non-empty item is not a valid IP or CIDR.
 */
fun normalizeWireGuardAddresses(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.listByLineOrComma().mapNotNull { item ->
        val addr = item.trim()
        if (addr.isEmpty()) return@mapNotNull null
        if (addr.contains('/')) {
            val slashIdx = addr.indexOf('/')
            val ipPart = addr.substring(0, slashIdx).trim().removeSurrounding("[", "]")
            val prefixStr = addr.substring(slashIdx + 1).trim()
            val prefix = prefixStr.toIntOrNull()
            require(InetAddresses.isInetAddress(ipPart) && prefix != null) {
                "Invalid WireGuard CIDR: '$addr'"
            }
            val maxPrefix = if (':' in ipPart) 128 else 32
            require(prefix in 0..maxPrefix) {
                "Invalid WireGuard CIDR prefix length in '$addr' (expected 0..$maxPrefix)"
            }
            "$ipPart/$prefix"
        } else {
            val ipPart = addr.removeSurrounding("[", "]")
            require(InetAddresses.isInetAddress(ipPart)) {
                "Invalid WireGuard IP address: '$addr'"
            }
            if (':' in ipPart) "$ipPart/128" else "$ipPart/32"
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
                if (keepalive in 1..MAX_KEEPALIVE_SECONDS) {
                    persistent_keepalive_interval = keepalive
                } else if (keepalive > MAX_KEEPALIVE_SECONDS) {
                    persistent_keepalive_interval = MAX_KEEPALIVE_SECONDS
                }
                val rawReserved = bean.reserved ?: ""
                if (rawReserved.isNotBlank()) {
                    reserved = parseReservedBytes(rawReserved)
                }
            }
        )
    }
}
