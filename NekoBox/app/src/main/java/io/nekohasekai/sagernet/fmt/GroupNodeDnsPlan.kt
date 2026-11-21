package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxDnsServerParser
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/** Tracks the isolated node resolvers used by one generated configuration. */
internal class GroupNodeDnsPlan(private val warn: (String) -> Unit = {}) {
    private class Resolver(
        val addresses: List<String>,
        val servers: List<DNSServerOptions>,
        val tag: String,
    )

    private val resolvers = LinkedHashMap<Long, Resolver>()
    private val domainGroups = LinkedHashMap<String, LinkedHashSet<Long>>()
    private val defaultDomains = LinkedHashSet<String>()

    val servers: List<DNSServerOptions>
        get() = resolvers.values.flatMap { it.servers }

    /**
     * Records a domain belonging to a node whose group uses default DNS (no group DNS).
     * If another active group binds the exact same domain to a private resolver, unscoped
     * TUN lookups for that domain will be rejected instead of leaking to the private DNS.
     */
    fun registerDefaultDomain(nodeAddress: String?) {
        val host = nodeAddress?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isNotEmpty() && !SingBoxDnsServerParser.isIpLiteral(host)) {
            defaultDomains.add(host)
        }
    }

    /**
     * Keep the first valid endpoint as primary and the rest as ordered backups.
     * Both outbound dialers and exact TUN DNS rules use the same resolver tag.
     *
     * @param forExport When `true`, omits NekoPro's private `type: "fallback"` wrapper and emits
     * only the primary standard sing-box 1.14 DNS server so exported JSON runs on upstream sing-box.
     */
    fun bind(
        groupId: Long,
        addresses: List<String>,
        nodeAddress: String?,
        forExport: Boolean = false,
    ): String {
        val resolver = resolvers.getOrPut(groupId) {
            val tag = "dns-group-$groupId"
            val endpoints = ArrayList<DNSServerOptions>()
            val validAddresses = linkedSetOf<String>()
            addresses.forEachIndexed { index, raw ->
                val address = raw.trim()
                if (address.isEmpty() || address in validAddresses) return@forEachIndexed
                val endpoint = try {
                    SingBoxDnsServerParser.parseGroupNodeDns(address, "$tag-${endpoints.size + 1}")
                } catch (_: IllegalArgumentException) {
                    warn("Skipping invalid group DNS endpoint #${index + 1} for group $groupId")
                    return@forEachIndexed
                }
                validAddresses.add(address)
                endpoints.add(endpoint)
            }
            require(endpoints.isNotEmpty()) { "no valid group DNS endpoint" }
            val emittedServers = when {
                endpoints.size == 1 || forExport -> {
                    // Upstream sing-box 1.14 does not implement NekoPro's `type: "fallback"` transport;
                    // exported configs keep only the primary standard endpoint under the group tag.
                    listOf(endpoints.first().apply { this.tag = tag })
                }
                else -> {
                    endpoints + DNSServerOptions().apply {
                        type = "fallback"
                        this.tag = tag
                        servers = endpoints.map { it.tag }
                    }
                }
            }
            Resolver(validAddresses.toList(), emittedServers, tag)
        }
        val host = nodeAddress?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isNotEmpty() && !SingBoxDnsServerParser.isIpLiteral(host)) {
            domainGroups.getOrPut(host) { linkedSetOf() }.add(groupId)
        }
        return resolver.tag
    }

    /**
     * A socket's explicit resolver retains its group identity even across chain hops. For TUN
     * queries only the domain is visible. If active groups disagree on that exact domain
     * (either two different private resolvers, or a private resolver colliding with a default-DNS
     * group), refuse the unscoped query instead of routing it to an arbitrary resolver.
     */
    fun domainRules(): List<DNSRule_DefaultOptions> {
        val domainsByTag = LinkedHashMap<String?, MutableList<String>>()
        domainGroups.forEach { (domain, groups) ->
            val tag = if (domain in defaultDomains) {
                null
            } else {
                val choices = groups.map(resolvers::getValue).distinctBy { it.addresses }
                choices.singleOrNull()?.tag
            }
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
