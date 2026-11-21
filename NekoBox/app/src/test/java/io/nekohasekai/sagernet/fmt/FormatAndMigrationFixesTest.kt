package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.hysteria.getFirstPort
import io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildSingBoxOutboundMieruBean
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean
import moe.matsuri.nb4a.proxy.snell.parseSnell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        val parsed = parseSnell("snell://secret-psk@snell.example.com:8443?version=6&mode=default&userkey=user-key-123#s6")
        assertEquals("user-key-123", parsed.userkey)
        assertEquals(6, parsed.version)
    }

    @Test
    fun mieruPortRangeAndUriRoundTrip() {
        val rangeOnly = MieruBean().apply {
            serverAddress = "mieru.example.com"
            serverPort = 0
            portRange = "20000-20100"
            initializeDefaultValues()
        }
        assertEquals(20000, rangeOnly.serverPort)

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

        val parsed = parseMieru("mieru://alice:pw@mieru.example.com:20000?transport=UDP&mtu=1350&port-range=20000-20100&multiplexing=MULTIPLEXING_HIGH&handshake-mode=HANDSHAKE_NO_WAIT#m1")
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

    @Test
    fun kryoDeserializationAcrossSmallStreamChunksPreservesTrailingFields() {
        val longPad = "p".repeat(5000)
        val mieru = MieruBean().apply {
            initializeDefaultValues()
            name = "mieru-node"
            serverAddress = "m.example.com"
            serverPort = 20000
            username = longPad
            password = "pw"
            portRange = "20000-20100"
            multiplexing = "MULTIPLEXING_HIGH"
            handshakeMode = "HANDSHAKE_NO_WAIT"
            customOutboundJson = "{\"detour\":\"front\"}"
        }
        val wg = WireGuardBean().apply {
            initializeDefaultValues()
            name = "wg-node"
            serverAddress = "wg.example.com"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = longPad
            peerPublicKey = "pub"
            allowedIps = "10.0.0.0/8"
            persistentKeepalive = 25
            customConfigJson = "{\"log\":{\"level\":\"warn\"}}"
        }
        val snell = SnellBean().apply {
            initializeDefaultValues()
            name = "snell-node"
            serverAddress = "s.example.com"
            serverPort = 8443
            psk = longPad
            version = 6
            userkey = "ukey-999"
            customOutboundJson = "{\"tcp_fast_open\":true}"
        }
        val group = ProxyGroup(
            id = 42L,
            userOrder = 3L,
            ungrouped = false,
            name = "SubGroup",
            type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                link = "https://sub.example.com/$longPad"
                nodeDns = "https://dns.node.example/dns-query"
                autoAdoptNodeDns = true
                adoptedNodeDns = "tls://1.1.1.1"
            },
            order = 1,
            isSelector = true,
            frontProxy = 11L,
            landingProxy = 22L,
        )

        // 1) KryoConverters (>4KB ByteArray) round-trip
        val mieruClone = mieru.clone()
        assertEquals("mieru-node", mieruClone.name)
        assertEquals("20000-20100", mieruClone.portRange)
        assertEquals("{\"detour\":\"front\"}", mieruClone.customOutboundJson)

        val wgClone = wg.clone()
        assertEquals("wg-node", wgClone.name)
        assertEquals("10.0.0.0/8", wgClone.allowedIps)
        assertEquals(25, wgClone.persistentKeepalive)
        assertEquals("{\"log\":{\"level\":\"warn\"}}", wgClone.customConfigJson)

        val snellClone = snell.clone()
        assertEquals("snell-node", snellClone.name)
        assertEquals("ukey-999", snellClone.userkey)
        assertEquals("{\"tcp_fast_open\":true}", snellClone.customOutboundJson)

        // 2) Backup stream deserialization with tiny 32-byte ByteBufferInput chunks
        //    forcing chunk exhaustion at every versioned field boundary
        val rawOut = ByteArrayOutputStream()
        ByteBufferOutput(rawOut).use { out ->
            mieru.serializeToBuffer(out)
            wg.serializeToBuffer(out)
            snell.serializeToBuffer(out)
            group.serializeToBuffer(out)
            out.flush()
        }
        ByteBufferInput(ByteArrayInputStream(rawOut.toByteArray()), 32).use { input ->
            val m2 = MieruBean().apply { deserializeFromBuffer(input); initializeDefaultValues() }
            val w2 = WireGuardBean().apply { deserializeFromBuffer(input); initializeDefaultValues() }
            val s2 = SnellBean().apply { deserializeFromBuffer(input); initializeDefaultValues() }
            val g2 = ProxyGroup().apply { deserializeFromBuffer(input); initializeDefaultValues() }

            assertEquals("mieru-node", m2.name)
            assertEquals("20000-20100", m2.portRange)
            assertEquals("{\"detour\":\"front\"}", m2.customOutboundJson)

            assertEquals("wg-node", w2.name)
            assertEquals("10.0.0.0/8", w2.allowedIps)
            assertEquals(25, w2.persistentKeepalive)
            assertEquals("{\"log\":{\"level\":\"warn\"}}", w2.customConfigJson)

            assertEquals("snell-node", s2.name)
            assertEquals("ukey-999", s2.userkey)
            assertEquals("{\"tcp_fast_open\":true}", s2.customOutboundJson)

            assertEquals(42L, g2.id)
            assertEquals("https://dns.node.example/dns-query", g2.subscription?.nodeDns)
            assertEquals(true, g2.subscription?.autoAdoptNodeDns)
            assertEquals("tls://1.1.1.1", g2.subscription?.adoptedNodeDns)
            assertEquals(1, g2.order)
            assertEquals(true, g2.isSelector)
            assertEquals(11L, g2.frontProxy)
            assertEquals(22L, g2.landingProxy)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun singBoxOptionAsMapSerializesWithoutInfiniteRecursion() {
        val wg = WireGuardBean().apply {
            initializeDefaultValues()
            localAddress = "10.0.0.2/32"
            privateKey = "priv"
            serverAddress = "wg.example.com"
            serverPort = 51820
            peerPublicKey = "pub"
            allowedIps = "10.0.0.0/8"
            persistentKeepalive = 25
        }
        val ep = buildSingBoxEndpointWireGuardBean(wg).apply {
            _hack_config_map["tag"] = "proxy"
            _hack_config_map["domain_resolver"] = "dns-group-1"
            _hack_custom_config = "{\"mtu\": 1380}"
        }
        val root = moe.matsuri.nb4a.SingBoxOptions.MyOptions().apply {
            endpoints = mutableListOf(ep)
            outbounds = mutableListOf(
                moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption("{\"type\":\"direct\",\"tag\":\"direct\"}")
            )
        }
        val map = root.asMap()
        val endpoints = map["endpoints"] as List<Map<String, Any?>>
        assertEquals("proxy", endpoints[0]["tag"])
        assertEquals("dns-group-1", endpoints[0]["domain_resolver"])
        assertEquals(1380L, endpoints[0]["mtu"])
        val outbounds = map["outbounds"] as List<Map<String, Any?>>
        assertEquals("direct", outbounds[0]["tag"])
    }
}
