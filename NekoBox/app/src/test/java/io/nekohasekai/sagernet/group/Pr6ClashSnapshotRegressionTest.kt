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
}
