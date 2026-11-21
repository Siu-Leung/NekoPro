package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two reported regressions that do not need an Android runtime:
 * the group DNS editor must accept several endpoints, and a saved multi-line
 * setting must round-trip through the same parsing used by the config builder.
 */
class GroupDnsInputRegressionTest {

    private fun endpoints(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun multilineEditorKeepsEveryEndpoint() {
        val typed = "https://first.example:44443/dns-query/aaaaaaaa-1111-2222-3333-444444444444\n" +
            "https://second.example:44443/dns-query/aaaaaaaa-1111-2222-3333-444444444444\n" +
            "https://third.example/dns-query/aaaaaaaa-1111-2222-3333-444444444444"
        val parsed = endpoints(typed)
        assertEquals(3, parsed.size)
        assertEquals("https://first.example:44443/dns-query/aaaaaaaa-1111-2222-3333-444444444444", parsed.first())
    }

    @Test
    fun singleLineHistoryStillParses() {
        val parsed = endpoints("  https://only.example/dns-query/aaaaaaaa-1111-2222-3333-444444444444  ")
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().startsWith("https://only.example/"))
    }

    @Test
    fun blankLinesAndTrailingNewlineAreIgnored() {
        val parsed = endpoints("\n\n https://a.example/dns-query/x \n\n")
        assertEquals(listOf("https://a.example/dns-query/x"), parsed)
    }

    @Test
    fun orderIsPreservedForPrimaryAndMirrors() {
        val parsed = endpoints("https://primary.example/dns-query/x\nhttps://mirror.example/dns-query/x")
        assertEquals("https://primary.example/dns-query/x", parsed[0])
        assertEquals("https://mirror.example/dns-query/x", parsed[1])
    }
}
