package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.group.ClashDnsCandidates.Category
import io.nekohasekai.sagernet.group.ClashDnsCandidates.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashDnsCandidatesTest {
    private fun profile(dns: String) = ClashDnsCandidates.parse(
        "proxies: [{name: entry, type: socks5, server: entry.example, port: 1080}]\ndns:\n" +
            dns.trimIndent().prependIndent("  "),
    )

    @Test
    fun allResolverCategoriesArePresentedWithoutImportingPoliciesOrHosts() {
        val result = profile("""
            nameserver: [192.0.2.11, https://one.example/dns-query]
            fallback: ['quic://two.example']
            direct-nameserver: ['tls://three.example']
            default-nameserver: [198.51.100.8]
            nameserver-policy:
              '+.sample.example': https://four.example/dns-query
            proxy-server-nameserver-policy:
              '+.proxy.example': [https://five.example/dns-query, system]
            hosts: {entry.example: 192.0.2.1}
        """)
        assertTrue(result.hasDns)
        assertEquals(emptyList<String>(), result.auto)
        assertEquals(
            listOf(Category.NODE_POLICY, Category.NODE_POLICY, Category.DEFAULT, Category.DEFAULT,
                Category.POLICY, Category.FALLBACK, Category.DIRECT, Category.BOOTSTRAP),
            result.rows.map { it.category },
        )
        assertEquals("+.proxy.example", result.rows.first().policy)
        assertEquals("https://five.example/dns-query", result.rows.first().selectable)
        assertEquals(Status.LOCAL, result.rows[1].status)
    }

    @Test
    fun exactEnabledListenerAndPrivatePathAreNecessaryForMirrors() {
        val path = "/dns-query/aaaaaaaa-1111-bbbb-2222-cccccccccccc"
        val endpoint = "https://mirror-a.example:44443$path"
        val other = "https://mirror-b.example$path"
        fun create(self: String, bind: String, extra: String = "", servers: String = "$endpoint, $other") =
            profile("enable: true\nlisten: '$bind'\nproxy-server-nameserver: ['$self']\nnameserver: [$servers]\n$extra")
        assertEquals(listOf(endpoint, other), create("tcp://127.0.0.1:7874", "0.0.0.0:7874").auto)
        assertEquals(listOf(endpoint, other), create("udp://[::1]:7874", "[::]:7874").auto)
        assertEquals(Status.SELF_REFERENCE, create("tcp://127.0.0.1:7874", "127.0.0.1:7874").rows.first().status)
        for ((pointer, bind) in listOf(
            "udp://127.0.0.1:7874" to "0.0.0.0:53",
            "udp://127.0.0.1:7874" to "127.0.0.2:7874",
            "udp://127.0.0.1:7874" to "192.0.2.2:7874",
            "udp://[::1]:7874" to "0.0.0.0:7874",
            "udp://0.0.0.0:7874" to "0.0.0.0:7874",
            "tls://127.0.0.1:7874" to "127.0.0.1:7874",
        )) assertTrue(create(pointer, bind).auto.isEmpty())
        for (servers in listOf(
            "https://public-a.example/dns-query, https://public-b.example/dns-query",
            "$endpoint, https://public-b.example/dns-query",
            "$endpoint, {malformed: true}",
            "$endpoint, 'https://mirror-b.example$path#PROXY'",
        )) assertTrue(create("udp://127.0.0.1:7874", ":7874", servers = servers).auto.isEmpty())
        assertTrue(profile("listen: ':7874'\nproxy-server-nameserver: ['udp://127.0.0.1:7874']\nnameserver: [$endpoint, $other]").auto.isEmpty())
    }

    @Test
    fun unsafeAndUnsupportedCandidatesCannotBeSelected() {
        val result = profile("""
            nameserver:
              - https://good.example/dns-query
              - 'https://unsafe.example/dns-query#PROXY'
              - 'https://creds@unsafe.example/dns-query'
              - 'https://unsafe.example/dns-query?secret=x'
              - 'https://[::ffff:127.0.0.1]/dns-query'
              - 'https://router.local/dns-query'
              - 'tls://10.0.0.20'
              - 'tcp://0177.0.0.1'
              - https://missing-path.example
              - dhcp://eth0
              - http://unsupported.example/dns-query
              - [nested-list]
        """)
        assertEquals(listOf("https://good.example/dns-query"), result.rows.mapNotNull { it.selectable })
        assertEquals(listOf(Status.SELECTABLE, Status.UNSAFE, Status.UNSAFE, Status.UNSAFE, Status.LOCAL,
            Status.LOCAL, Status.LOCAL, Status.UNSAFE, Status.UNSUPPORTED, Status.LOCAL,
            Status.UNSUPPORTED, Status.INVALID), result.rows.map { it.status })
    }

    @Test
    fun noDnsIsNotAnEmptyDnsSectionAndPreviewIsBounded() {
        assertFalse(ClashDnsCandidates.parse("proxies: []").hasDns)
        assertFalse(ClashDnsCandidates.parse("socks5://entry.example:1080").hasDns)
        assertTrue(profile("enable: false").hasDns)
        assertTrue(profile("enable: false").rows.isEmpty())
        assertTrue(ClashDnsCandidates.parse("proxies: []\ndns: [broken]").malformed)
        assertTrue(ClashDnsCandidates.parse("proxies: []\ndns: {}\ndns: {}").malformed)
        val endpoints = (1..ClashDnsCandidates.MAX_ROWS + 1).joinToString { "https://r$it.example/dns-query" }
        val tooMany = profile("proxy-server-nameserver: [$endpoints]")
        assertTrue(tooMany.truncated)
        assertEquals(ClashDnsCandidates.MAX_ROWS, tooMany.rows.size)
        assertTrue(tooMany.auto.isEmpty())
    }
}
