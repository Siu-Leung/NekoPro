package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.getFirstPort
import io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildSingBoxOutboundMieruBean
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.mieru.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean
import moe.matsuri.nb4a.proxy.snell.parseSnell
import moe.matsuri.nb4a.proxy.snell.toUri
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

    @Test
    fun wireGuardPreservesAllowedIpsAndPersistentKeepalive() {
        val bean = WireGuardBean().apply {
            initializeDefaultValues()
            localAddress = "10.0.0.2/32"
            privateKey = "priv"
            serverAddress = "wg.example.com"
            serverPort = 51820
            peerPublicKey = "pub"
            allowedIps = "10.0.0.0/8, 192.168.1.0/24"
            persistentKeepalive = 25
        }
        val ep = buildSingBoxEndpointWireGuardBean(bean)
        assertEquals(listOf("10.0.0.0/8", "192.168.1.0/24"), ep.peers[0].allowed_ips)
        assertEquals(25, ep.peers[0].persistent_keepalive_interval)
    }

    @Test
    fun snellUserkeyRoundTripsAndPopulatesOutbound() {
        val bean = SnellBean().apply {
            initializeDefaultValues()
            name = "s6"
            serverAddress = "snell.example.com"
            serverPort = 8443
            psk = "secret-psk"
            userkey = "user-key-123"
            version = 6
            mode = "default"
        }
        val outbound = buildSingBoxOutboundSnellBean(bean)
        assertEquals("user-key-123", outbound.userkey)
        val parsed = parseSnell(bean.toUri())
        assertEquals("user-key-123", parsed.userkey)
        assertEquals(6, parsed.version)
    }

    @Test
    fun mieruPortRangeAndUriRoundTrip() {
        val bean = MieruBean().apply {
            initializeDefaultValues()
            name = "m1"
            serverAddress = "mieru.example.com"
            serverPort = 20000
            portRange = "20000-20100"
            protocol = "UDP"
            mtu = 1350
            username = "alice"
            password = "pw"
            multiplexing = "MULTIPLEXING_HIGH"
            handshakeMode = "HANDSHAKE_NO_WAIT"
        }
        val out = buildSingBoxOutboundMieruBean(bean)
        assertEquals("20000-20100", out.port_range)
        assertEquals(null, out.server_port)
        assertEquals(1350, out.mtu)
        assertEquals("MULTIPLEXING_HIGH", out.multiplexing)
        assertEquals("HANDSHAKE_NO_WAIT", out.handshake_mode)

        val parsed = parseMieru(bean.toUri())
        assertEquals("20000-20100", parsed.portRange)
        assertEquals("UDP", parsed.protocol)
        assertEquals(1350, parsed.mtu)
        assertEquals("alice", parsed.username)
        assertEquals("pw", parsed.password)
    }

    @Test
    fun subscriptionEffectiveNodeDnsPrefersManualOverAdopted() {
        val sub = SubscriptionBean().apply {
            initializeDefaultValues()
            autoAdoptNodeDns = true
            adoptedNodeDns = "https://adopted.example/dns-query"
        }
        assertEquals("https://adopted.example/dns-query", sub.effectiveNodeDns())
        sub.nodeDns = "https://manual.example/dns-query"
        assertEquals("https://manual.example/dns-query", sub.effectiveNodeDns())
        sub.nodeDns = ""
        sub.autoAdoptNodeDns = false
        assertEquals(null, sub.effectiveNodeDns())
    }
}
