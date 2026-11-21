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
    fun extractClashProxyServerNameserverValidatesStrictRules() {
        val validYaml = """
            dns:
              proxy-server-nameserver:
                - https://dns.example.com/dns-query/token
            proxies:
              - {name: kept, type: socks5, server: example.com, port: 1080}
        """.trimIndent()
        assertEquals(
            listOf("https://dns.example.com/dns-query/token"),
            RawUpdater.extractClashProxyServerNameservers(validYaml),
        )

        val suffixYaml = """
            dns:
              proxy-server-nameserver:
                - https://dns.example.com/dns-query#PROXY
            proxies:
              - {name: kept, type: socks5, server: example.com, port: 1080}
        """.trimIndent()
        assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(suffixYaml))

        val queryYaml = """
            dns:
              proxy-server-nameserver:
                - https://dns.example.com/dns-query?token=1
            proxies:
              - {name: kept, type: socks5, server: example.com, port: 1080}
        """.trimIndent()
        assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(queryYaml))
    }

    /** Auto-adoption must never promote website DNS or another client's local listener. */
    @Test
    fun autoAdoptionRefusesNonportableResolvers() {
        assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(valid))
        val websiteDns = "dns:\n  nameserver: [https://resolver.example/dns-query]\n$valid"
        assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(websiteDns))
        for (candidate in listOf(
            "['udp://127.0.0.1:7874']",
            "['http://[::1]:7874/dns-query']",
            "['tcp://localhost:53']",
            "['https://resolver.example:65536/dns-query']",
            "['https://resolver-a.example/dns-query', 'https://resolver-b.example/dns-query']",
            "['https://resolver.example/dns-query', {unexpected: mapping}]",
        )) {
            val text = "dns:\n  proxy-server-nameserver: $candidate\n$valid"
            assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(text))
        }
    }

    /** Kuromis 形态：psn 指向 Clash 自身监听，真实解析器在 dns.nameserver 的同服务多域名镜像里。 */
    @Test
    fun selfPointingResolverFallsBackToNameserverMirrors() {
        val mirrorPath = "/dns-query/6e4d9b50-uuid"
        val kuromis = """
            dns:
              listen: 0.0.0.0:7874
              proxy-server-nameserver: [udp://127.0.0.1:7874]
              nameserver:
                - https://hydrogen1693.example:44443$mirrorPath
                - https://subprime7404.example:44443$mirrorPath
                - https://tribunal2944.example$mirrorPath
            proxies:
              - {name: kept, type: socks5, server: entry.example, port: 1080}
        """.trimIndent()
        assertEquals(
            listOf(
                "https://hydrogen1693.example:44443$mirrorPath",
                "https://subprime7404.example:44443$mirrorPath",
                "https://tribunal2944.example$mirrorPath",
            ),
            RawUpdater.extractClashProxyServerNameservers(kuromis),
        )
        // 不同服务的镜像（path 不同）不可合并采纳——不猜。
        val mixedServices = kuromis.replace(mirrorPath, mirrorPath).replace(
            "https://tribunal2944.example$mirrorPath", "https://tribunal2944.example/other-path",
        )
        assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(mixedServices))
    }

    @Test
    fun clashYamlImportsMieruAndSnellUserkey() = runBlocking<Unit> {
        val yaml = """
            proxies:
              - {name: m1, type: mieru, server: mieru.example.com, port-range: 20000-20100, transport: UDP, username: u, password: p, mtu: 1360}
              - {name: s6, type: snell, server: snell.example.com, port: 8443, psk: k, userkey: uk1, version: 6}
        """.trimIndent()
        val parsed = RawUpdater.parseSubscription(yaml)
        assertEquals(listOf("m1", "s6"), parsed?.map { it.name })
    }
}
