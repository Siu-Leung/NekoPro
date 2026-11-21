package moe.matsuri.nb4a.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AnyTLSCertificateRegressionTest {
    private val hex = "ff".repeat(32)
    private val colonHex = hex.chunked(2).joinToString(":").uppercase()
    private val base64 = "/".repeat(42) + "8="
    private val other = "A".repeat(43) + "="

    @Test
    fun strictClashHexAndEditableDigestsUsePaddedStandardBase64() {
        assertEquals(base64, parseAnyTLSClashCertificatePin(hex))
        assertEquals(base64, parseAnyTLSClashCertificatePin("  $colonHex  "))
        assertNull(parseAnyTLSClashCertificatePin("  "))
        assertEquals(listOf(base64, other), parseAnyTLSCertificatePins("$colonHex\r\n\n$other"))
        for (invalid in listOf(base64, "f:$hex", "$hex:", hex.replaceRange(2, 3, ":"), "z".repeat(64), true)) {
            assertThrows(IllegalArgumentException::class.java) { parseAnyTLSClashCertificatePin(invalid) }
        }
        for (invalid in listOf(base64.dropLast(1), base64.replace('/', '_'), "A".repeat(42) + "B=", "bad")) {
            assertThrows(IllegalArgumentException::class.java) { parseAnyTLSCertificatePins("$base64\n$invalid") }
        }
    }

    private fun clash(vararg entries: String) = """
        global-client-fingerprint: firefox
        proxies:
          - {name: sample, type: anytls, server: example.com, port: 443, password: dummy, ${entries.joinToString(", ")}}
    """.trimIndent()

    @Test
    fun clashPinAndClientFingerprintAreIndependentOfYamlOrder() = runBlocking<Unit> {
        val pin = "fingerprint: '$colonHex'"
        val client = "client-fingerprint: chrome"
        for (text in listOf(clash(pin, client), clash(client, pin))) {
            val bean = RawUpdater.parseSubscription(text)?.single() as AnyTLSBean
            assertEquals(base64, bean.certificateSha256)
            assertEquals("chrome", bean.utlsFingerprint)
        }
        val inherited = RawUpdater.parseSubscription(clash(pin))?.single() as AnyTLSBean
        assertEquals(base64, inherited.certificateSha256)
        assertEquals("firefox", inherited.utlsFingerprint)
        val blank = RawUpdater.parseSubscription(clash("fingerprint: '  '"))?.single() as AnyTLSBean
        assertEquals("", blank.certificateSha256)
        assertEquals("firefox", blank.utlsFingerprint)
    }

    @Test
    fun malformedNonblankClashPinRejectsSnapshot() = runBlocking<Unit> {
        val invalid = clash("fingerprint: browser", "client-fingerprint: chrome")
        val mixed = "$invalid\n  - {name: good, type: socks5, server: example.org, port: 1080}"
        assertNotNull(runCatching { RawUpdater.parseSubscription(mixed) }.exceptionOrNull())
    }

    @Test
    fun outboundKeepsPinsSeparateAndDoesNotEnableInsecure() {
        val bean = AnyTLSBean().apply {
            initializeDefaultValues()
            certificateSha256 = "$base64\n$other"
            utlsFingerprint = "safari"
        }
        val tls = buildSingBoxOutboundAnyTLSBean(bean, globalAllowInsecure = false).tls
        assertEquals(listOf(base64, other), tls.certificate_sha256)
        assertEquals("safari", tls.utls.fingerprint)
        assertNull(tls.insecure)
        val options = tls.asMap()
        assertEquals(listOf(base64, other), options["certificate_sha256"])
        assertNull(options["certificate_public_key_sha256"])
        assertNull(options["insecure"])
        assertEquals(true, buildSingBoxOutboundAnyTLSBean(bean, globalAllowInsecure = true).tls.insecure)
        bean.certificateSha256 = "not a pin"
        assertThrows(IllegalArgumentException::class.java) { buildSingBoxOutboundAnyTLSBean(bean, false) }
        bean.certificateSha256 = ""
        assertNull(buildSingBoxOutboundAnyTLSBean(bean, false).tls.certificate_sha256)
    }

    @Test
    fun v0AndV1MigrateOldHexWithoutConsumingExtraFields() {
        for (version in 0..1) {
            val recovered = readLegacy(version, colonHex)
            assertEquals(base64, recovered.certificateSha256)
            assertEquals("", recovered.utlsFingerprint)
            assertEquals("legacy name", recovered.name)
            assertEquals("custom", recovered.customOutboundJson)
            assertEquals(if (version == 1) "30s" else "", recovered.idleSessionCheckInterval)
            val browser = readLegacy(version, "chrome")
            assertEquals("chrome", browser.utlsFingerprint)
            assertEquals("", browser.certificateSha256)
        }
    }

    private fun readLegacy(version: Int, fingerprint: String): AnyTLSBean {
        val data = ByteBufferOutput(256).apply {
            writeInt(version)
            writeString("example.com"); writeInt(443)
            writeString("dummy"); writeString("sni.example.com"); writeString("")
            writeString(""); writeString(fingerprint)
            writeBoolean(false); writeString("")
            if (version == 1) {
                writeString("30s"); writeString("60s"); writeInt(1); writeString("metadata")
            }
            writeInt(1); writeString("legacy name"); writeString("custom"); writeString("")
        }.toBytes()
        return AnyTLSBean().apply {
            deserializeFromBuffer(ByteBufferInput(data))
            initializeDefaultValues()
        }
    }

    @Test
    fun v2RoundTripsPinsAndBrowserFingerprint() {
        val source = AnyTLSBean().apply {
            initializeDefaultValues()
            name = "sample"
            utlsFingerprint = "chrome"
            certificateSha256 = "$base64\n$other"
        }
        val data = ByteBufferOutput(256).apply { source.serializeToBuffer(this) }.toBytes()
        val restored = AnyTLSBean().apply { deserializeFromBuffer(ByteBufferInput(data)) }
        assertEquals(source.certificateSha256, restored.certificateSha256)
        assertEquals("chrome", restored.utlsFingerprint)
        assertEquals("sample", restored.name)
    }
}
