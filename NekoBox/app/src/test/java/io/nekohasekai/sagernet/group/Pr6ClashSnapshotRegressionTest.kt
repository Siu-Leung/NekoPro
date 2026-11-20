package io.nekohasekai.sagernet.group

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Pr6ClashSnapshotRegressionTest {
    private val valid = "proxies:\n  - {name: kept, type: socks5, server: example.com, port: 1080}\n"

    @Test
    fun partialSnapshotsAreRejectedButManualImportIsStillPermissive() = runBlocking<Unit> {
        for (badEntry in listOf(
            "not-a-mapping",
            "{name: broken, type: socks5, server: example.com, port: invalid}",
            "{name: unsupported, type: not-supported, server: example.com, port: 1080}",
        )) {
            val text = valid + "  - $badEntry\n"
            assertNotNull(runCatching { RawUpdater.parseSubscription(text) }.exceptionOrNull())
            assertEquals(listOf("kept"), RawUpdater.parseRaw(text)?.map { it.name })
        }
    }

    @Test
    fun malformedYamlCannotFallBackToExtractingLinks() = runBlocking<Unit> {
        val text = valid + "  - [\n  # socks5://example.com:1080\n"
        assertNotNull(runCatching { RawUpdater.parseSubscription(text) }.exceptionOrNull())
    }

    @Test
    fun completeSnapshotIsAccepted() = runBlocking<Unit> {
        assertEquals(listOf("kept"), RawUpdater.parseSubscription(valid)?.map { it.name })
    }

    @Test
    fun partialSip008SnapshotIsRejectedForSubscriptionButManualImportPermitsValidEntries() = runBlocking<Unit> {
        val sip008 = """
            {
              "version": 1,
              "servers": [
                {"id": "1", "remarks": "good", "server": "example.com", "server_port": 8388, "password": "pw", "method": "aes-256-gcm"},
                {"id": "2", "remarks": "broken", "server": "example.com"}
              ]
            }
        """.trimIndent()
        assertNotNull(runCatching { RawUpdater.parseSubscription(sip008) }.exceptionOrNull())
        assertEquals(listOf("good"), RawUpdater.parseRaw(sip008)?.map { it.name })
    }
}
