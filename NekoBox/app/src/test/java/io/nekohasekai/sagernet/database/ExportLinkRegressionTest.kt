package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.copyForEntryAddress
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.ipinfo.NodeShareExport
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip node links without an Android runtime or a live resolver. */
class ExportLinkRegressionTest {
    private fun fixture(host: String, sni: String = "", pin: String = ""): ProxyEntity =
        ProxyEntity().putBean(AnyTLSBean().apply {
            initializeDefaultValues()
            name = "fixture"
            serverAddress = host
            serverPort = 15009
            password = "fixture-pass"
            this.sni = sni
            certificateSha256 = pin
        })

    @Test
    fun privateEntryUsesIpButKeepsExplicitSniAndPin() {
        val pin = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val node = fixture("entry.example", "sni.example", pin)
        for (universal in listOf(true, false)) {
            val link = node.exportLink(universal, "192.0.2.9")
            assertTrue(link.startsWith("sn://")) // AnyTLS with pin requires the universal form.
            val decoded = parseUniversal(link) as AnyTLSBean
            assertEquals("192.0.2.9", decoded.serverAddress)
            assertEquals(15009, decoded.serverPort)
            assertEquals("sni.example", decoded.sni)
            assertEquals(pin, decoded.certificateSha256)
            assertEquals("fixture-pass", decoded.password)
            assertFalse(decoded.allowInsecure == true)
        }
        assertEquals("entry.example", node.requireBean().serverAddress)
    }

    @Test
    fun ipv6KeepsImplicitTlsNameInStandardLink() {
        val node = fixture("entry.example")
        val link = node.exportLink(false, "2001:db8::9")
        assertTrue(link.contains("[2001:db8::9]:15009"))
        val decoded = parseAnytls(link)
        assertEquals("2001:db8::9", decoded.serverAddress)
        assertEquals("entry.example", decoded.sni)
        assertEquals("", node.anyTLSBean!!.sni)
    }

    @Test
    fun unknownOrInvalidAnswerAndLiteralNodesRemainUnchanged() {
        val hostname = fixture("entry.example").apply { entryIp = "192.0.2.10" }
        assertEquals(hostname.toStdLink(), hostname.exportLink(false))
        assertEquals(hostname.toStdLink(), hostname.exportLink(false, "not-an-ip"))
        val literal = fixture("198.51.100.9")
        assertEquals(literal.toStdLink(), literal.exportLink(false, "203.0.113.7"))
    }

    @Test
    fun resolvesOnlyConfiguredDnsAndReusesAnswerForRepeatedHost() = runBlocking {
        val node = fixture("entry.example")
        var requests = 0
        val dns = listOf("https://resolver.example/dns-query", "https://backup.example/dns-query")
        val resolve: suspend (String, List<String>) -> String? = { host, endpoints ->
            requests++
            assertEquals("entry.example", host)
            assertEquals(dns, endpoints)
            "192.0.2.9"
        }
        assertNull(NodeShareExport(emptyList(), resolve).entryAddress(node))
        val batch = NodeShareExport(dns, resolve)
        assertEquals("192.0.2.9", parseAnytls(batch.link(node)).serverAddress)
        assertEquals("192.0.2.9", parseAnytls(batch.link(fixture("ENTRY.example"))).serverAddress)
        assertEquals(1, requests)
        assertEquals(0, batch.unresolvedCount)
    }

    @Test
    fun failedPrivateDnsKeepsOriginalLinkAndReportsPartialResult() = runBlocking {
        val node = fixture("entry.example").apply { entryIp = "192.0.2.10" }
        val export = NodeShareExport(listOf("https://resolver.example/dns-query")) { _, _ -> null }
        assertEquals(node.toStdLink(), export.link(node))
        assertEquals(1, export.unresolvedCount)
    }

    @Test
    fun entryAddressExportPreservesMasqueBlankSniHttpUpgradeHostAndSnellObfsHost() {
        val masque = io.nekohasekai.sagernet.fmt.masque.MASQUEBean().apply {
            initializeDefaultValues()
            serverAddress = "warp.example.com"
            serverPort = 2408
            sni = ""
        }
        val masqueCopy = masque.copyForEntryAddress("192.0.2.50") as io.nekohasekai.sagernet.fmt.masque.MASQUEBean
        assertEquals("192.0.2.50", masqueCopy.serverAddress)
        assertEquals("", masqueCopy.sni)

        val vlessUpgrade = io.nekohasekai.sagernet.fmt.v2ray.VMessBean().apply {
            initializeDefaultValues()
            serverAddress = "cdn.example.com"
            serverPort = 443
            type = "httpupgrade"
            security = "tls"
            sni = "sni.example.com"
            host = ""
        }
        val upgradeCopy = vlessUpgrade.copyForEntryAddress("192.0.2.51") as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
        assertEquals("192.0.2.51", upgradeCopy.serverAddress)
        assertEquals("sni.example.com", upgradeCopy.host)

        val snell = moe.matsuri.nb4a.proxy.snell.SnellBean().apply {
            initializeDefaultValues()
            serverAddress = "snell.example.com"
            serverPort = 8443
            version = 4
            obfsMode = "http"
            obfsHost = ""
        }
        val snellCopy = snell.copyForEntryAddress("192.0.2.52") as moe.matsuri.nb4a.proxy.snell.SnellBean
        assertEquals("192.0.2.52", snellCopy.serverAddress)
        assertEquals("snell.example.com", snellCopy.obfsHost)

        val customConfigNode = AnyTLSBean().apply {
            initializeDefaultValues()
            serverAddress = "entry.example"
            customConfigJson = "{\"log\":{}}"
        }
        assertNull(customConfigNode.copyForEntryAddress("192.0.2.53"))
    }

    @Test
    fun entryAddressExportRejectsFakeIpAndSinkholeAnswers() = runBlocking {
        val node = fixture("entry.example")
        val fakeExport = NodeShareExport(listOf("https://resolver.example/dns-query")) { _, _ -> "198.18.0.1" }
        assertNull(fakeExport.entryAddress(node))
        assertEquals(1, fakeExport.unresolvedCount)

        val sinkholeExport = NodeShareExport(listOf("https://resolver.example/dns-query")) { _, _ -> "0.0.0.0" }
        assertNull(sinkholeExport.entryAddress(node))
        assertEquals(1, sinkholeExport.unresolvedCount)
    }
}
