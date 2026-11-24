package io.nekohasekai.sagernet.ipinfo

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpInfoRegressionTest {
    @Test
    fun literalsDoNotRequireDns() {
        assertTrue(IpInfoStore.isIpLiteral("192.0.2.19"))
        assertTrue(IpInfoStore.isIpLiteral("[2001:db8::19]"))
        assertFalse(IpInfoStore.isIpLiteral("node.example"))
        assertFalse(IpInfoStore.isIpLiteral("192.0.2.999"))
        assertFalse(IpInfoStore.isIpLiteral("192.0.2.19:443"))
        assertFalse(IpInfoStore.isIpLiteral("fe80::1%iface"))
    }

    @Test
    fun validatesDnsResponseAndQuestion() {
        val query = IpInfoStore.buildDnsQueryA("node.example")
        assertEquals(1, query[2].toInt())
        assertEquals(1, query[5].toInt())
        val response = answer(query, 1, byteArrayOf(192.toByte(), 0, 2, 19))
        assertEquals("192.0.2.19", IpInfoStore.parseAnswer(response, query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf(response.size - 1), query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }, query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf().apply { this[2] = 0x83.toByte() }, query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf().apply { this[13] = 'x'.code.toByte() }, query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf().apply { this[query.size + 1] = query.size.toByte() }, query))
    }

    @Test
    fun acceptsAaaaButNotUnrelatedAnswer() {
        val query = IpInfoStore.buildQuery("node.example", 28)
        val address = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val response = answer(query, 28, address)
        assertEquals("2001:db8::1", IpInfoStore.parseAnswer(response, query))
        assertNull(IpInfoStore.parseAnswer(response.copyOf().apply { this[query.size + 1] = 17 }, query))
    }

    @Test
    fun groupEndpointsAreNeverSilentlyRewrittenToUntrustedDestinations() {
        assertEquals("https://dns.example/dns-query", IpInfoStore.groupDohUrl("https://dns.example").toString())
        assertEquals("https://dns.example/private-path", IpInfoStore.groupDohUrl("https+local://dns.example/private-path").toString())
        assertEquals("https://dns.example/", IpInfoStore.groupDohUrl("https://dns.example/").toString())
        assertNull(IpInfoStore.groupDohUrl("https://dns.example/path?secret=test"))
        assertNull(IpInfoStore.groupDohUrl("https://user@dns.example/path"))
        assertNull(IpInfoStore.groupDohUrl("tls://dns.example"))
    }

    @Test
    fun hidesAddressesButKeepsProviderText() {
        val hostname = "private.node.example"
        assertEquals("Address hidden", AddressRedactor.cardTitle("$hostname:443", "", hostname, "Address hidden", true))
        assertEquals("$hostname:443", AddressRedactor.cardTitle("$hostname:443", "", hostname, "Address hidden", false))
        assertEquals("Named node", AddressRedactor.cardTitle("Named node", "Named node", hostname, "Address hidden", true))
        assertEquals("•••", AddressRedactor.redactAddress("$hostname:443"))
        assertEquals("•••", AddressRedactor.maskIp("192.0.2.19", true))
        assertEquals("192.0.2.19", AddressRedactor.maskIp("192.0.2.19", false))
        assertEquals(
            "AS64500 Example ISP (•••) ••• ••• •••",
            AddressRedactor.scrub(
                "AS64500 Example ISP (192.0.2.19) [2001:db8::19] fe80::19%iface PRIVATE.NODE.EXAMPLE.",
                hostname,
            ),
        )
    }

    @Test
    fun validatesOwnerResponseAgainstRequestedAddress() {
        val payload = """{"ip":"2001:db8::19","country_code":"HK","asn":64500,"asn_organization":"Example ISP"}"""
            .encodeToByteArray()
        val owner = IpInfoStore.parseOwner(payload, "2001:db8:0:0:0:0:0:19")
        assertNotNull(owner)
        assertTrue(owner!!.label().contains("AS64500 Example ISP"))
        assertNull(IpInfoStore.parseOwner(payload, "2001:db8::20"))
        assertNull(IpInfoStore.parseOwner("""{"ip":"example.invalid"}""".encodeToByteArray()))
        assertEquals("", IpOwner.countryFlag("香港"))
    }

    @Test
    fun testBoxHasOnlyPinnedOutboundAndRejectsUnprovableInputs() {
        val generated = """
            {"dns":{"servers":[]},"outbounds":[
              {"tag":"proxy","type":"socks","server":"192.0.2.19","server_port":1080},
              {"tag":"direct","type":"direct"},{"tag":"bypass","type":"direct"}
            ],"route":{"final":"direct"}}
        """.trimIndent()
        val config = JsonParser.parseString(IpInfoStore.exitProbeConfig(generated, 12345, "fixture-only")!!).asJsonObject
        assertEquals("proxy", config.getAsJsonObject("route").get("final").asString)
        assertEquals(1, config.getAsJsonArray("outbounds").size())
        assertEquals("socks", config.getAsJsonArray("outbounds")[0].asJsonObject.get("type").asString)
        val inbound = config.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals("fixture-only", inbound.getAsJsonArray("users")[0].asJsonObject.get("password").asString)
        assertTrue(config.getAsJsonObject("log").get("disabled").asBoolean)
        assertNull(IpInfoStore.exitProbeConfig(generated.replace("\"socks\"", "\"direct\""), 12345, "fixture-only"))
        assertNull(IpInfoStore.exitProbeConfig(generated.replace("\"socks\"", "\"selector\""), 12345, "fixture-only"))
        assertNull(IpInfoStore.exitProbeConfig(
            generated.replace("\"server_port\":1080", "\"server_port\":1080,\"detour\":\"direct\""),
            12345,
            "fixture-only",
        ))
    }

    @Test
    fun rejectsFakeIpPrivateAndBogonsForPublicRoutingAndEntryUse() {
        for (nonPublic in listOf(
            "198.18.0.1", "198.19.255.254", "0.0.0.0", "127.0.0.1",
            "10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1",
            "192.0.2.1", "224.0.0.1", "::1", "fc00::1", "fd12:3456::1",
            "fe80::1", "2001:db8::1", "::ffff:127.0.0.1",
        )) {
            assertFalse("Expected non-public: $nonPublic", IpInfoStore.isPublicRoutableIp(nonPublic))
        }
        assertTrue(IpInfoStore.isPublicRoutableIp("1.1.1.1"))
        assertTrue(IpInfoStore.isPublicRoutableIp("2606:4700:4700::1111"))

        for (unusable in listOf("198.18.0.1", "198.19.0.5", "0.0.0.0", "127.0.0.1", "::1", "fd00::1", "fe80::1")) {
            assertFalse("Expected unusable entry IP: $unusable", IpInfoStore.isUsableEntryIp(unusable))
        }
        assertTrue(IpInfoStore.isUsableEntryIp("1.1.1.1"))
        assertTrue(IpInfoStore.isUsableEntryIp("192.0.2.9"))
    }

    @Test
    fun scrubsCjkAdjacentHostnamesAndHexPrefixedIpv6() {
        assertEquals(
            "香港••• 节点",
            AddressRedactor.scrub("香港node1.example.com 节点", "node1.example.com"),
        )
        assertEquals(
            "abcde:•••",
            AddressRedactor.scrub("abcde:2001:db8::1"),
        )
        assertEquals(
            "DoH https+local://••• failed on •••",
            AddressRedactor.scrub("DoH https+local://dns.secret-sub.xyz/token123 failed on edge1.transit-provider.net:8443"),
        )
        assertEquals(
            "exchange failed for [redacted-url]",
            moe.matsuri.nb4a.Protocols.sanitizeErrorMessage("exchange failed for https+local://dns.secret-sub.xyz/token123"),
        )
    }

    @Test
    fun setsBrowserCompatibleUserAgentAndParsesFallbackProviders() {
        val req = IpInfoStore.request(okhttp3.HttpUrl.Builder().scheme("https").host("api.ip.sb").addPathSegment("geoip").build())
        val ua = req.header("User-Agent").orEmpty()
        assertTrue("Expected non-okhttp User-Agent, got: $ua", ua.startsWith("Mozilla/5.0") && !ua.contains("okhttp", ignoreCase = true))

        val ipWhoisPayload = """
            {"ip":"45.207.209.10","success":true,"country":"Hong Kong","country_code":"HK","connection":{"asn":9269,"org":"HKT Limited","isp":"PCCW"}}
        """.trimIndent().encodeToByteArray()
        val whoisOwner = IpInfoStore.parseIpWhoisOwner(ipWhoisPayload, "45.207.209.10")
        assertNotNull(whoisOwner)
        assertTrue(whoisOwner!!.label().contains("AS9269 HKT Limited"))
        assertNull(IpInfoStore.parseIpWhoisOwner(ipWhoisPayload, "45.207.209.11"))
        assertNull(IpInfoStore.parseIpWhoisOwner("""{"ip":"45.207.209.10","success":false}""".encodeToByteArray()))

        val tracePayload = "fl=29f12\nh=www.cloudflare.com\nip=104.28.14.99\nloc=SG\ntls=TLSv1.3\n".encodeToByteArray()
        val traceOwner = IpInfoStore.parseCloudflareTrace(tracePayload)
        assertNotNull(traceOwner)
        assertEquals("104.28.14.99", traceOwner!!.ip)
        assertEquals("SG", traceOwner.countryCode)
        assertTrue(traceOwner.label().contains("SG"))

        assertTrue(IpInfoStore.sameIp("2001:0db8::1", "[2001:db8::1]"))
        assertTrue(IpInfoStore.sameIp("1.1.1.1", "1.1.1.1"))
        assertFalse(IpInfoStore.sameIp("1.1.1.1", "1.0.0.1"))
        assertFalse(IpInfoStore.sameIp("example.com", "1.1.1.1"))

        val ipApiIsPayload = """
            {"ip":"14.23.28.105","location":{"country_code":"CN","country":"China"},"asn":{"asn":4134,"org":"Chinanet","descr":"CHINANET-BACKBONE"},"company":{"name":"China Telecom"}}
        """.trimIndent().encodeToByteArray()
        val ipApiOwner = IpInfoStore.parseIpApiIsOwner(ipApiIsPayload, "14.23.28.105")
        assertNotNull(ipApiOwner)
        assertTrue(ipApiOwner!!.label().contains("CN"))
        assertTrue(ipApiOwner.label().contains("AS4134 Chinanet"))

        val ipInfoPayload = """
            {"ip":"45.207.209.10","country":"HK","org":"AS9269 HKT Limited"}
        """.trimIndent().encodeToByteArray()
        val ipInfoOwner = IpInfoStore.parseIpInfoIoOwner(ipInfoPayload, "45.207.209.10")
        assertNotNull(ipInfoOwner)
        assertEquals("AS9269", ipInfoOwner!!.asn)
        assertEquals("HKT Limited", ipInfoOwner.organization)

        val freeIpApiPayload = """
            {"ipAddress":"183.232.253.16","countryCode":"CN","countryName":"China","asn":"AS9808","asnOrganization":"China Mobile"}
        """.trimIndent().encodeToByteArray()
        val freeOwner = IpInfoStore.parseFreeIpApiOwner(freeIpApiPayload, "183.232.253.16")
        assertNotNull(freeOwner)
        assertTrue(freeOwner!!.label().contains("AS9808 China Mobile"))

        val rawAnsiLog = "E[0m[2568] conn closed\n\u001B[36mINFO\u001B[0m[2576] [\u001B[38;5;193m2136742438\u001B[0m 0ms] inbound/mixed"
        val cleanedLog = io.nekohasekai.sagernet.ui.LogcatFragment.stripAnsiAndNormalize(rawAnsiLog)
        assertEquals("[2568] conn closed\nINFO[2576] [2136742438 0ms] inbound/mixed", cleanedLog)
    }

    private fun answer(query: ByteArray, type: Int, address: ByteArray): ByteArray =
        query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, address.size.toByte()) + address
}
