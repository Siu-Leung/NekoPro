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
        assertEquals("dns-group-8", plan.bind(8, first, "Node-A.Example."))
        assertEquals("dns-group-9", plan.bind(9, second, "node-b.example"))
        assertEquals("dns-group-8", plan.bind(8, first, "192.0.2.15"))
        assertEquals("dns-group-10", plan.bind(10, second, "192.0.2.20"))
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
        assertEquals("dns-group-1", plan.bind(1, first, "same.example"))
        assertEquals("dns-group-2", plan.bind(2, second, "same.example"))
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
}
