package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionRawStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveReadMetaAndDeleteRoundTrip() {
        val dir = tempFolder.newFolder("sub_raw")
        val yaml = """
            dns:
              enable: true
              nameserver:
                - https://dns.example/dns-query
            proxies:
              - {name: HK-01, type: ss, server: 1.2.3.4, port: 443}
        """.trimIndent()

        val savedMeta = SubscriptionRawStore.save(
            groupId = 42L,
            text = yaml,
            sourceLabel = "FlClash/v0.9.4",
            updatedAtMillis = 1700000000000L,
            storeDir = dir,
        )
        assertNotNull(savedMeta)
        assertEquals(SubscriptionRawStore.RawFormat.CLASH_YAML, savedMeta!!.format)
        assertEquals("FlClash/v0.9.4", savedMeta.userAgent)
        assertEquals(6, savedMeta.lineCount)
        assertTrue(SubscriptionRawStore.exists(42L, dir))

        val loadedMeta = SubscriptionRawStore.readMeta(42L, dir)
        assertEquals(savedMeta, loadedMeta)

        val snapshot = SubscriptionRawStore.read(42L, dir)
        assertNotNull(snapshot)
        assertEquals(yaml, snapshot!!.text)
        assertEquals(savedMeta, snapshot.meta)

        SubscriptionRawStore.delete(42L, dir)
        assertFalse(SubscriptionRawStore.exists(42L, dir))
        assertNull(SubscriptionRawStore.readMeta(42L, dir))
        assertNull(SubscriptionRawStore.read(42L, dir))
    }

    @Test
    fun detectFormatHandlesBomJsonYamlAndUriList() {
        assertEquals(
            SubscriptionRawStore.RawFormat.JSON,
            SubscriptionRawStore.detectFormat("\uFEFF  {\"proxies\": []}"),
        )
        assertEquals(
            SubscriptionRawStore.RawFormat.CLASH_YAML,
            SubscriptionRawStore.detectFormat("\uFEFF# comment\nproxies:\n  - name: a"),
        )
        assertEquals(
            SubscriptionRawStore.RawFormat.PROXY_LIST,
            SubscriptionRawStore.detectFormat("ss://abc@1.1.1.1:443#HK\ntrojan://pass@2.2.2.2:443#JP"),
        )
    }

    @Test
    fun oversizedSaveIsRejectedAndExistingSnapshotPreserved() {
        val dir = tempFolder.newFolder("sub_raw_limit")
        val initial = "proxies:\n  - {name: node, type: socks5, server: 1.1.1.1, port: 1080}"
        assertNotNull(SubscriptionRawStore.save(7L, initial, "clash-meta", 100L, dir))

        val huge = "a".repeat(SubscriptionRawStore.MAX_RAW_BYTES + 128)
        assertNull(SubscriptionRawStore.save(7L, huge, "clash-meta", 200L, dir))

        val preserved = SubscriptionRawStore.read(7L, dir)
        assertNotNull(preserved)
        assertEquals(initial, preserved!!.text)
        assertEquals(100L, preserved.meta.updatedAtMillis)
    }

    @Test
    fun invalidGroupIdBlankTextAndControlLabelsAreRejectedOrSanitized() {
        val dir = tempFolder.newFolder("sub_raw_sanitize")
        assertNull(SubscriptionRawStore.save(0L, "proxies: []", "ua", 1L, dir))
        assertNull(SubscriptionRawStore.save(-1L, "proxies: []", "ua", 1L, dir))
        assertNull(SubscriptionRawStore.save(3L, "   \n  ", "ua", 1L, dir))

        assertEquals(
            "manual-edit",
            SubscriptionRawStore.sanitizeSourceLabel("\u0000\n\r"),
        )
        assertEquals(
            "FlClashv0.9.4",
            SubscriptionRawStore.sanitizeSourceLabel("FlClash\nv0.9.4\u0007"),
        )
    }

    @Test
    fun deleteAllCleansSelectedGroupsOnly() {
        val dir = tempFolder.newFolder("sub_raw_multi")
        SubscriptionRawStore.save(1L, "ss://a", "ua1", 1L, dir)
        SubscriptionRawStore.save(2L, "ss://b", "ua2", 2L, dir)
        SubscriptionRawStore.save(3L, "ss://c", "ua3", 3L, dir)

        SubscriptionRawStore.deleteAll(listOf(1L, 3L), dir)

        assertFalse(SubscriptionRawStore.exists(1L, dir))
        assertTrue(SubscriptionRawStore.exists(2L, dir))
        assertFalse(SubscriptionRawStore.exists(3L, dir))
    }
}
