package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.getFirstPort
import io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAndMigrationFixesTest {

    @Test
    fun hysteriaPortHoppingHandlesRangesAndSinglePorts() {
        assertEquals(20000, getFirstPort("20000-30000"))
        assertEquals(443, getFirstPort("443,20000-30000"))
        assertEquals(8443, getFirstPort("8443:8450"))
        assertEquals(
            listOf("443:443", "20000:30000", "40000:40100"),
            hopPortsToSingboxList("443,20000-30000,40000:40100")
        )
    }

    @Test
    fun sshBeanDefaultsToPort22() {
        val bean = SSHBean()
        bean.initializeDefaultValues()
        assertEquals(22, bean.serverPort)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun migratesLegacyWireGuardOutboundToSingBox114Endpoint() {
        val wgOutbound = linkedMapOf<String, Any?>(
            "type" to "wireguard",
            "tag" to "proxy",
            "local_address" to listOf("10.0.0.2/32"),
            "private_key" to "privkey",
            "server" to "1.2.3.4",
            "server_port" to 51820,
            "peer_public_key" to "pubkey",
            "reserved" to listOf(1, 2, 3),
            "gso" to true,
        )
        val directOutbound = linkedMapOf<String, Any?>(
            "type" to "direct",
            "tag" to "direct",
        )
        val configMap = linkedMapOf<String, Any>(
            "outbounds" to mutableListOf<Any?>(wgOutbound, directOutbound),
        )

        migrateLegacyWireGuardOutbounds(configMap)

        val outbounds = configMap["outbounds"] as List<Map<String, Any?>>
        assertEquals(1, outbounds.size)
        assertEquals("direct", outbounds[0]["tag"])

        val endpoints = configMap["endpoints"] as? List<Map<String, Any?>>
        assertNotNull(endpoints)
        assertEquals(1, endpoints!!.size)
        val ep = endpoints[0]
        assertEquals("wireguard", ep["type"])
        assertEquals("proxy", ep["tag"])
        assertEquals(listOf("10.0.0.2/32"), ep["address"])
        assertFalse(ep.containsKey("local_address"))
        assertFalse(ep.containsKey("gso"))
        val peers = ep["peers"] as List<Map<String, Any?>>
        assertEquals(1, peers.size)
        assertEquals("1.2.3.4", peers[0]["address"])
        assertEquals(51820, peers[0]["port"])
        assertEquals("pubkey", peers[0]["public_key"])
        assertEquals(listOf(1, 2, 3), peers[0]["reserved"])
        assertTrue((peers[0]["allowed_ips"] as List<*>).contains("0.0.0.0/0"))
    }
}
