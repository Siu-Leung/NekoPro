package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.widget.formatBinaryByteRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionConfigStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun savesReadsAndDeletesSubscriptionConfigAtomically() {
        val store = SubscriptionConfigFileStore(tempFolder.newFolder("sub_configs"))
        val sampleYaml = """
            dns:
              enable: true
              proxy-server-nameserver:
                - https://private.doh.example/dns-query#DIRECT
            proxies:
              - { name: HK-01, type: anytls, server: entry.example, port: 443 }
        """.trimIndent()

        store.save(groupId = 7L, content = sampleYaml, userAgent = "FlClash/v0.9.4", updatedAtMs = 1700000000000L)
        assertTrue(store.exists(7L))

        val loaded = store.read(7L)
        assertNotNull(loaded)
        assertEquals(sampleYaml, loaded!!.content)
        assertEquals("FlClash/v0.9.4", loaded.userAgent)
        assertEquals(1700000000000L, loaded.updatedAtMs)

        store.delete(7L)
        assertNull(store.read(7L))
    }

    @Test
    fun locatesAndExtractsDnsSnippetAcrossYamlAndSurgeFormats() {
        val yaml = """
            mixed-port: 7890
            dns:
              enable: true
              proxy-server-nameserver:
                - https://private-doh.example/dns-query
            proxies:
              - { name: HK, type: ss, server: 1.2.3.4, port: 8388 }
        """.trimIndent()

        assertTrue(SubscriptionConfigStore.findDnsSectionOffset(yaml) > 0)
        val snippet = SubscriptionConfigStore.extractDnsSectionSnippet(yaml)
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("proxy-server-nameserver"))
        assertTrue(!snippet.contains("proxies:"))
    }

    @Test
    fun formatsBinaryByteRatesConsistently() {
        assertEquals("0 B/s", formatBinaryByteRate(0L))
        assertEquals("512 B/s", formatBinaryByteRate(512L))
        assertEquals("1.0 KB/s", formatBinaryByteRate(1024L))
        assertEquals("10.00 MB/s", formatBinaryByteRate(10L * 1024L * 1024L))
    }
}
