package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingBoxDnsServerParserTest {

    @Test
    fun parsesHttpsDomainWithDetourAndExplicitDomainResolver() {
        val server = SingBoxDnsServerParser.parse(
            rawAddress = "https://dns.google/dns-query",
            tag = "dns-remote",
            defaultDetour = "proxy",
            domainResolver = "dns-direct",
        )
        assertEquals("https", server.type)
        assertEquals("dns-remote", server.tag)
        assertEquals("dns.google", server.server)
        assertNull(server.server_port)
        assertEquals("/dns-query", server.path)
        assertEquals("proxy", server.detour)
        assertEquals("dns-direct", server.domain_resolver)
        assertNull(server.address)
    }

    @Test
    fun parsesHttpsDomainWithDetourWithoutDomainResolver() {
        for (resolver in listOf(null, "", " ")) {
            val server = SingBoxDnsServerParser.parse(
                rawAddress = "https://dns.google/dns-query",
                tag = "dns-remote",
                defaultDetour = "proxy",
                domainResolver = resolver,
            )
            assertEquals("proxy", server.detour)
            assertNull(server.domain_resolver)
        }
    }

    @Test
    fun parsesHttpsIpDirectWithoutDomainResolverOrDetour() {
        val server = SingBoxDnsServerParser.parse(
            rawAddress = "https://223.5.5.5/dns-query",
            tag = "dns-direct",
            defaultDetour = null,
            domainResolver = "dns-local",
        )
        assertEquals("https", server.type)
        assertEquals("dns-direct", server.tag)
        assertEquals("223.5.5.5", server.server)
        assertNull(server.server_port)
        assertEquals("/dns-query", server.path)
        assertNull(server.detour)
        assertNull(server.domain_resolver)
    }

    @Test
    fun parsesHttpsDomainDirectWithDomainResolver() {
        val server = SingBoxDnsServerParser.parse(
            rawAddress = "https://dns.alidns.com:8443/custom-dns?foo=bar",
            tag = "dns-direct",
            defaultDetour = null,
            domainResolver = "dns-local",
        )
        assertEquals("https", server.type)
        assertEquals("dns.alidns.com", server.server)
        assertEquals(8443, server.server_port)
        assertEquals("/custom-dns?foo=bar", server.path)
        assertNull(server.detour)
        assertEquals("dns-local", server.domain_resolver)
    }

    @Test
    fun parsesPlusLocalSchemeAsDirect() {
        val server = SingBoxDnsServerParser.parse(
            rawAddress = "https+local://dns.google/dns-query",
            tag = "dns-remote",
            defaultDetour = "proxy",
            domainResolver = "dns-direct",
        )
        assertEquals("https", server.type)
        assertEquals("dns.google", server.server)
        assertNull(server.detour)
        assertEquals("dns-direct", server.domain_resolver)
    }

    @Test
    fun parsesTlsQuicH3TcpUdp() {
        val tls = SingBoxDnsServerParser.parse("tls://1.1.1.1:853", "dns-remote", "proxy", "dns-direct")
        assertEquals("tls", tls.type)
        assertEquals("1.1.1.1", tls.server)
        assertEquals(853, tls.server_port)
        assertEquals("proxy", tls.detour)

        val quic = SingBoxDnsServerParser.parse("quic://dns.adguard-dns.com", "dns-direct", null, "dns-local")
        assertEquals("quic", quic.type)
        assertEquals("dns.adguard-dns.com", quic.server)
        assertNull(quic.server_port)
        assertEquals("dns-local", quic.domain_resolver)

        val h3 = SingBoxDnsServerParser.parse("h3://dns.google/dns-query", "dns-remote", "proxy", "dns-direct")
        assertEquals("h3", h3.type)
        assertEquals("dns.google", h3.server)
        assertEquals("/dns-query", h3.path)

        val tcp = SingBoxDnsServerParser.parse("tcp://8.8.8.8:5353", "dns-remote", "proxy", "dns-direct")
        assertEquals("tcp", tcp.type)
        assertEquals("8.8.8.8", tcp.server)
        assertEquals(5353, tcp.server_port)

        val udp = SingBoxDnsServerParser.parse("udp://223.5.5.5", "dns-direct", null, "dns-local")
        assertEquals("udp", udp.type)
        assertEquals("223.5.5.5", udp.server)
        assertNull(udp.server_port)
    }

    @Test
    fun parsesBareIpv4AndIpv6() {
        val v4 = SingBoxDnsServerParser.parse("223.5.5.5", "dns-direct", null, "dns-local")
        assertEquals("udp", v4.type)
        assertEquals("223.5.5.5", v4.server)
        assertNull(v4.server_port)
        assertNull(v4.domain_resolver)

        val v4Port = SingBoxDnsServerParser.parse("223.5.5.5:5353", "dns-direct", null, "dns-local")
        assertEquals("udp", v4Port.type)
        assertEquals("223.5.5.5", v4Port.server)
        assertEquals(5353, v4Port.server_port)

        val v6 = SingBoxDnsServerParser.parse("2001:4860:4860::8888", "dns-remote", "proxy", "dns-direct")
        assertEquals("udp", v6.type)
        assertEquals("2001:4860:4860::8888", v6.server)
        assertNull(v6.server_port)
        assertNull(v6.domain_resolver)

        val v6Port = SingBoxDnsServerParser.parse("[2001:4860:4860::8888]:5353", "dns-remote", "proxy", "dns-direct")
        assertEquals("udp", v6Port.type)
        assertEquals("2001:4860:4860::8888", v6Port.server)
        assertEquals(5353, v6Port.server_port)
    }

    @Test
    fun parsesLocalAndDhcpAsLocalType() {
        for (addr in listOf("local", "LOCAL", "system", "dhcp://auto")) {
            val server = SingBoxDnsServerParser.parse(addr, "dns-direct", "proxy", "dns-local")
            assertEquals("local", server.type)
            assertEquals("dns-direct", server.tag)
            assertNull(server.server)
            assertNull(server.detour)
            assertNull(server.domain_resolver)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedScheme() {
        SingBoxDnsServerParser.parse("rcode://success", "dns-block")
    }
}
