package io.nekohasekai.sagernet.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetImportPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsOnlyAPlainDbBasenameInsideTheAssetDirectory() {
        val root = temporaryFolder.newFolder("assets")

        val destination = AssetImportPolicy.destination(root, "custom.db")

        assertEquals(root.canonicalFile, destination.parentFile.canonicalFile)
        assertEquals("custom.db", destination.name)
    }

    @Test
    fun rejectsTraversalAbsoluteAndEncodedSeparators() {
        val root = temporaryFolder.newFolder("assets")
        val maliciousNames = listOf(
            "../escape.db",
            "/tmp/escape.db",
            "dir\\escape.db",
            "..%2Fescape.db",
            "%2e%2e%5cescape.db",
            "bad\u0000.db",
        )

        maliciousNames.forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                AssetImportPolicy.destination(root, name)
            }
        }
    }
}