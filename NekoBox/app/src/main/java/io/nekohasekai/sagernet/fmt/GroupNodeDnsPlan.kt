package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxDnsServerParser
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/** Tracks the isolated node resolvers used by one generated configuration. */
internal class GroupNodeDnsPlan {
    private data class Resolver(val address: String, val server: DNSServerOptions)

    private val resolvers = LinkedHashMap<Long, Resolver>()
    private val domainGroups = LinkedHashMap<String, LinkedHashSet<Long>>()

    val servers: List<DNSServerOptions> get() = resolvers.values.map { it.server }

    /** Return the explicit dial resolver tag; remember an exact host for TUN DNS, never a suffix. */
    fun bind(groupId: Long, address: String, nodeAddress: String?): String {
        val resolver = resolvers.getOrPut(groupId) {
            Resolver(address, SingBoxDnsServerParser.parseGroupNodeDns(address, "dns-group-$groupId"))
        }
        val host = nodeAddress?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isNotEmpty() && !SingBoxDnsServerParser.isIpLiteral(host)) {
            domainGroups.getOrPut(host) { linkedSetOf() }.add(groupId)
        }
        return resolver.server.tag
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
            val tag = choices.singleOrNull()?.server?.tag
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
