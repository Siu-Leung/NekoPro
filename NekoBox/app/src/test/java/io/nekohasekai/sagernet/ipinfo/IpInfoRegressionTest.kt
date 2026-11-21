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

    private fun answer(query: ByteArray, type: Int, address: ByteArray): ByteArray =
        query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, address.size.toByte()) + address
}
