package io.nekohasekai.sagernet.ipinfo

/**
 * 卡片地址的截图友好遮蔽。
 *
 * 设计取舍：
 * - IP 保留前两段，便于自己辨认是哪个机房/段，同时不暴露完整地址。
 * - 域名保留前 4 个字符与顶级域，隐藏中间的用户专属 UUID 段（私有入口域名常含 token）。
 * - 端口不遮蔽：端口本身不含身份信息，遮蔽反而妨碍排查。
 * - 服务商与地区标签不经过这里，永远完整显示（用户明确要求不隐藏）。
 */
object AddressRedactor {

    /** 遮蔽 node 地址（host:port 或 [v6]:port）。 */
    fun redactAddress(address: String): String {
        if (address.isBlank()) return address
        val hasBrackets = address.startsWith("[")
        val colons = address.count { it == ':' }
        // 无方括号且冒号多于一个 = 裸 IPv6，末尾那段是地址而不是端口，不能切。
        val hasPort = address.substringAfterLast(':').all { it.isDigit() } &&
            address.substringAfterLast(':').isNotEmpty() &&
            (hasBrackets || colons == 1)
        val host = if (hasPort) address.substringBeforeLast(':') else address
        val port = if (hasPort) ":" + address.substringAfterLast(':') else ""
        return redactHost(host) + port
    }

    fun redactHost(host: String): String {
        val value = host.trim().removePrefix("[").removeSuffix("]")
        if (value.isEmpty()) return host
        return when {
            value.contains(':') -> value.substringBefore(':') + "::***"
            value.count { it == '.' } == 3 && value.all { it.isDigit() || it == '.' } -> {
                val parts = value.split('.')
                "${parts[0]}.${parts[1]}.*.*"
            }
            else -> {
                val labels = value.split('.')
                if (labels.size >= 3) {
                    "${maskLabel(labels.first())}.***.${labels.last()}"
                } else {
                    maskLabel(value)
                }
            }
        }
    }

    /** 单个标签：≤4 字符全遮，否则保留前 4 位。 */
    private fun maskLabel(label: String): String =
        if (label.length <= 4) "****" else label.take(4) + "****"

    /** 仅遮蔽 IP 本身；服务商标签由调用方单独拼接，不参与遮蔽。 */
    fun maskIp(ip: String, enabled: Boolean): String {
        if (ip.isBlank() || !enabled) return ip
        // 只处理地址字面量；任何非 IP 文本原样返回，避免把服务商标签误遮成 ***。
        if (ip.contains(':')) {
            return if (ip.count { it == ':' } >= 2) ip.substringBefore(':') + "::***" else ip
        }
        val parts = ip.split('.')
        val isIpv4 = parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        return if (isIpv4) "${parts[0]}.${parts[1]}.*.*" else ip
    }
}
