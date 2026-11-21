package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.SingBoxDnsServerParser
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/**
 * Tracks the isolated node resolvers used by one generated configuration.
 *
 * 容错设计（2026-11-21）：一个分组可绑定多个解析器端点（私有 DoH 服务常见的多域名
 * 入口）。第一个端点注册为主 tag `dns-group-<gid>`，其余注册为镜像
 * `dns-group-<gid>-1`、`dns-group-<gid>-2`…全部加入 dns.servers。拨号期
 * `domain_resolver` 指向主 tag；vendored mihomo_adapter 的 resolver 识别镜像后缀，
 * 主端点失败时依序回退（见 protect.go isGroupDNSMirrorTag）。
 */
internal class GroupNodeDnsPlan {
    private data class Resolver(
        val address: String,
        val servers: List<DNSServerOptions>,
        val primaryTag: String,
    )

    private val resolvers = LinkedHashMap<Long, Resolver>()
    private val domainGroups = LinkedHashMap<String, LinkedHashSet<Long>>()

    val servers: List<DNSServerOptions>
        get() = resolvers.values.flatMap { it.servers }

    /**
     * Bind a group to one or more resolver endpoints (one per line). The first
     * valid endpoint becomes the primary; the rest become ordered mirrors.
     * Malformed lines are skipped with a warning — a partial mirror list beats
     * failing the whole config build.
     */
    fun bind(groupId: Long, addresses: List<String>, nodeAddress: String?): String {
        val resolver = resolvers.getOrPut(groupId) {
            val servers = ArrayList<DNSServerOptions>()
            var primaryTag = ""
            addresses.distinct().forEachIndexed { index, address ->
                if (address.isBlank()) return@forEachIndexed
                val tag = if (index == 0) "dns-group-$groupId" else "dns-group-$groupId-$index"
                val server = try {
                    SingBoxDnsServerParser.parseGroupNodeDns(address, tag)
                } catch (_: IllegalArgumentException) {
                    Logs.w("Skipping invalid group DNS endpoint #${index + 1} for group $groupId")
                    return@forEachIndexed
                }
                if (index == 0) primaryTag = tag
                servers.add(server)
            }
            require(primaryTag.isNotEmpty()) { "no valid group DNS endpoint" }
            Resolver(addresses.joinToString("\n"), servers, primaryTag)
        }
        val host = nodeAddress?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isNotEmpty() && !SingBoxDnsServerParser.isIpLiteral(host)) {
            domainGroups.getOrPut(host) { linkedSetOf() }.add(groupId)
        }
        return resolver.primaryTag
    }

    /**
     * A socket's explicit resolver retains its group identity even across chain hops. For TUN
     * queries only the domain is visible. If active groups disagree on that exact domain, refuse
     * the unscoped query instead of sending it to an arbitrary group's private DNS or the public one.
     */
    fun domainRules(): List<DNSRule_DefaultOptions> {
        val domainsByTag = LinkedHashMap<String?, MutableList<String>>()
        domainGroups.forEach { (domain, groups) ->
            val choices = groups.map(resolvers::getValue).distinctBy { it.address }
            val tag = choices.singleOrNull()?.primaryTag
            domainsByTag.getOrPut(tag) { mutableListOf() }.add(domain)
        }
        return domainsByTag.map { (tag, domains) ->
            DNSRule_DefaultOptions().apply {
                domain = domains
                if (tag == null) {
                    action = "reject"
                } else {
                    action = "route"
                    server = tag
                }
            }
        }
    }
}
