package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import moe.matsuri.nb4a.SingBoxDnsServerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNodeDnsRegressionTest {
    private val first = "https://resolver-a.example:44443/dns-query/fixture%2Fpath"
    private val second = "https://resolver-b.example/dns-query"

    /** Ordinary groups and legacy subscriptions select their manual resolver before auto-adoption. */
    @Test
    fun groupDnsPriorityAndClearAreCompatibleWithExistingProfiles() {
        val group = ProxyGroup(outboundDns = first)
        assertEquals(first, group.effectiveNodeDns())
        assertFalse(group.requiresNodeDnsAdoption())
        group.type = GroupType.SUBSCRIPTION
        group.subscription = SubscriptionBean().apply {
            nodeDns = second
            autoAdoptNodeDns = true
            adoptedNodeDns = "tls://resolver-c.example:853"
        }
        assertEquals(first, group.effectiveNodeDns())
        assertFalse(group.requiresNodeDnsAdoption())
        group.outboundDns = null
        assertEquals(second, group.effectiveNodeDns())
        group.saveManualNodeDns("  ")
        assertEquals("", group.subscription?.nodeDns)
        assertEquals("tls://resolver-c.example:853", group.effectiveNodeDns())
        assertTrue(group.requiresNodeDnsAdoption())
        group.subscription?.adoptedNodeDns = ""
        assertNull(group.effectiveNodeDns())
        group.subscription?.autoAdoptNodeDns = false
        assertFalse(group.requiresNodeDnsAdoption())
    }

    /** Port, credential path and bootstrap survive conversion; different groups cannot cross-resolve. */
    @Test
    fun resolverTagsAndTunRulesUseTheActualNodeGroup() {
        val plan = GroupNodeDnsPlan()
        assertEquals("dns-group-8", plan.bind(8, listOf(first), "Node-A.Example."))
        assertEquals("dns-group-9", plan.bind(9, listOf(second), "node-b.example"))
        assertEquals("dns-group-8", plan.bind(8, listOf(first), "192.0.2.15"))
        assertEquals("dns-group-10", plan.bind(10, listOf(second), "192.0.2.20"))
        val servers = plan.servers.associateBy { it.tag }
        val resolver = servers.getValue("dns-group-8")
        assertEquals("https", resolver.type)
        assertEquals("resolver-a.example", resolver.server)
        assertEquals(44443, resolver.server_port)
        assertEquals("/dns-query/fixture%2Fpath", resolver.path)
        assertEquals("dns-direct", resolver.domain_resolver)
        assertNull(resolver.detour)
        assertTrue("dns-group-10" in servers)
        val rules = plan.domainRules().associateBy { it.server }
        assertEquals(listOf("node-a.example"), rules.getValue("dns-group-8").domain)
        assertEquals(listOf("node-b.example"), rules.getValue("dns-group-9").domain)
        assertTrue(rules.values.all { it.action == "route" && it.domain_suffix == null })
    }

    /** An unscoped TUN request must not pick one of two different private resolvers. */
    @Test
    fun ambiguousTunLookupDoesNotPickAnArbitraryResolver() {
        val plan = GroupNodeDnsPlan()
        assertEquals("dns-group-1", plan.bind(1, listOf(first), "same.example"))
        assertEquals("dns-group-2", plan.bind(2, listOf(second), "same.example"))
        val rule = plan.domainRules().single()
        assertEquals(listOf("same.example"), rule.domain)
        assertEquals("reject", rule.action)
        assertNull(rule.server)
        assertEquals(2, plan.servers.size)
    }

    /** Explicit / and encoded path bytes are preserved; unsupported forms fail without echoing them. */
    @Test
    fun groupDnsRejectsLossySyntaxWithoutLeakingTheUrl() {
        assertEquals("/", SingBoxDnsServerParser.parseGroupNodeDns("https://resolver.example/", "test").path)
        for (raw in listOf(
            "https://resolver.example:0/dns-query/fixture",
            "https://resolver.example:65536/dns-query/fixture",
            "https://resolver.example:/dns-query/fixture",
            "https://resolver.example/dns-query?fixture=1",
            "https://resolver.example/dns-query#fixture",
            "https://fixture@resolver.example/dns-query",
            "tls://resolver.example/fixture",
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                SingBoxDnsServerParser.parseGroupNodeDns(raw, "test")
            }
            assertFalse(error.message.orEmpty().contains("fixture"))
            assertNull(error.cause)
        }
    }

    /** Full backups retain group DNS; share links and older group records stay compatible. */
    @Test
    fun privateGroupDnsSurvivesBackupButNotSubscriptionSharing() {
        val original = ProxyGroup(id = 9, name = "sample", type = GroupType.SUBSCRIPTION, outboundDns = first,
            subscription = SubscriptionBean().apply { link = "https://subscription.example/config" })
        val backup = ByteBufferOutput(256, -1).use { out ->
            original.serializeToBuffer(out)
            out.toBytes()
        }
        assertEquals(first, ProxyGroup().apply { ByteBufferInput(backup).use { deserializeFromBuffer(it) } }.outboundDns)
        original.export = true
        val share = ByteBufferOutput(256, -1).use { out ->
            original.serializeToBuffer(out)
            out.toBytes()
        }
        val imported = ProxyGroup().apply {
            export = true
            ByteBufferInput(share).use { deserializeFromBuffer(it) }
        }
        assertNull(imported.effectiveNodeDns())
        assertEquals("https://subscription.example/config", imported.subscription?.link)

        val v1 = ByteBufferOutput(256, -1).use { out ->
            out.writeInt(1)
            out.writeLong(91)
            out.writeLong(2)
            out.writeBoolean(false)
            out.writeString("old")
            out.writeInt(GroupType.SUBSCRIPTION)
            out.writeBoolean(true)
            SubscriptionBean().apply { nodeDns = second }.serializeToBuffer(out)
            out.writeInt(3)
            out.writeBoolean(true)
            out.writeLong(100)
            out.writeLong(200)
            out.writeInt(307)
            out.toBytes()
        }
        ByteBufferInput(v1).use { input ->
            val recovered = ProxyGroup().apply { deserializeFromBuffer(input) }
            assertEquals(second, recovered.effectiveNodeDns())
            assertEquals(200L, recovered.landingProxy)
            assertEquals(307, input.readInt())
        }
    }

    @Test
    fun fiveEndpointsUseOneOrderedFallbackTagForOutboundAndTun() {
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION, subscription = SubscriptionBean().apply {
            autoAdoptNodeDns = true
            adoptedNodeDns = ('a'..'e').joinToString("\n") { "https://resolver-$it.example/dns-query/fixture" }
        })
        val plan = GroupNodeDnsPlan()
        val tag = plan.bind(37, group.effectiveNodeDnsList(), "entry.example")
        assertEquals("dns-group-37", tag)
        val tags = (1..5).map { "dns-group-37-$it" }
        val endpoints = plan.servers.dropLast(1)
        assertEquals(tags, endpoints.map { it.tag })
        assertEquals(('a'..'e').map { "resolver-$it.example" }, endpoints.map { it.server })
        assertTrue(endpoints.all { it.path == "/dns-query/fixture" && it.domain_resolver == "dns-direct" })
        val aggregate = plan.servers.last()
        assertEquals(mapOf("type" to "fallback", "tag" to tag, "servers" to tags), aggregate.asMap())
        assertEquals(tag, plan.domainRules().single().server)
    }

    @Test
    fun invalidFirstEndpointAndDuplicatesDoNotLoseSingleEndpointPassthrough() {
        val warnings = mutableListOf<String>()
        val plan = GroupNodeDnsPlan { warnings.add(it) }
        val malformed = "https://bad.example:99999/dns-query/fixture"
        val tag = plan.bind(5, listOf(malformed, "  $first  ", first), "entry.example")
        assertEquals("dns-group-5", tag)
        val endpoint = plan.servers.single()
        assertEquals(tag, endpoint.tag)
        assertEquals("https", endpoint.type)
        assertNull(endpoint.servers)
        assertEquals(listOf("Skipping invalid group DNS endpoint #1 for group 5"), warnings)
        assertThrows(IllegalArgumentException::class.java) {
            GroupNodeDnsPlan().bind(6, listOf(malformed), "entry.example")
        }
    }

    @Test
    fun fallbackMembersKeepTheirNativeTransportTypes() {
        val plan = GroupNodeDnsPlan()
        plan.bind(6, listOf(first, "h3://resolver.example/path", "tls://resolver.example", "quic://resolver.example", "tcp://192.0.2.1", "192.0.2.2", "local"), null)
        assertEquals(listOf("https", "h3", "tls", "quic", "tcp", "udp", "local", "fallback"), plan.servers.map { it.type })
    }

    /** 组级手填支持多行，取第一行为主端点。 */
    @Test
    fun manualMultiLineResolverUsesFirstAsPrimary() {
        val group = ProxyGroup().apply {
            name = "multi"
            type = GroupType.SUBSCRIPTION
            outboundDns = "https://a.example/dns-query/t\nhttps://b.example/dns-query/t"
        }
        assertEquals(2, group.effectiveNodeDnsList().size)
        assertEquals("https://a.example/dns-query/t", group.effectiveNodeDns())
    }

    @Test
    fun exportModeDowngradesMultiEndpointFallbackToStandardPrimaryServer() {
        val plan = GroupNodeDnsPlan()
        val tag = plan.bind(41, listOf(first, second), "entry.example", forExport = true)
        assertEquals("dns-group-41", tag)
        val server = plan.servers.single()
        assertEquals("https", server.type)
        assertEquals(tag, server.tag)
        assertEquals("resolver-a.example", server.server)
        assertNull(server.servers)
    }

    @Test
    fun defaultGroupDomainCollidingWithPrivateGroupEmitsRejectRule() {
        val plan = GroupNodeDnsPlan()
        plan.registerDefaultDomain("shared.example.")
        plan.bind(12, listOf(first), "SHARED.example")
        val rule = plan.domainRules().single()
        assertEquals(listOf("shared.example"), rule.domain)
        assertEquals("reject", rule.action)
        assertNull(rule.server)
    }
}
