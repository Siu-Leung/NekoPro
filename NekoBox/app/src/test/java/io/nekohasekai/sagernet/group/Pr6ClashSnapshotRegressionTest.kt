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
            "['https://resolver.example/dns-query', {unexpected: mapping}]",
            "['https://resolver.example/dns-query', 'udp://127.0.0.1:7874']",
            "['https://resolver.example/dns-query', null]",
        )) {
            val text = "dns:\n  proxy-server-nameserver: $candidate\n$valid"
            assertEquals(emptyList<String>(), RawUpdater.extractClashProxyServerNameservers(text))
        }
    }

    @Test
    fun directNodeResolversKeepExplicitOrderEvenWhenDifferentServices() {
        val resolvers = listOf("https://resolver-b.example:9443/first", "tls://resolver-a.example")
        val text = "dns:\n  proxy-server-nameserver: [${resolvers.joinToString()}, ${resolvers.first()}]\n$valid"
        assertEquals(resolvers, RawUpdater.extractClashProxyServerNameservers(text))
    }

    /** The local listener may forward to a single private DoH service with multiple mirrors. */
    @Test
    fun selfPointingResolverFallsBackToNameserverMirrors() {
        val mirrorPath = "/dns-query/01234567-89ab-cdef-0123-456789abcdef"
        val kuromis = """
            dns:
              enable: true
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

    @Test
    fun htmlErrorPageIsRejectedInSubscriptionMode() = runBlocking<Unit> {
        val html = "<!DOCTYPE html><html><body>Visit https://portal.example.com to renew</body></html>"
        assertNotNull(runCatching { RawUpdater.parseSubscription(html) }.exceptionOrNull())
    }

    @Test
    fun clashSubscriptionSkipsKnownUnsupportedTypesAndImportsWireGuard() = runBlocking<Unit> {
        val yaml = """
            proxies:
              - {name: legacy-ssr, type: ssr, server: ssr.example.com, port: 443, cipher: aes-256-cfb, password: p, obfs: plain, protocol: origin}
              - {name: old-snell, type: snell, server: s3.example.com, port: 443, psk: k, version: 3}
              - {name: wg1, type: wireguard, server: wg.example.com, port: 51820, ip: 10.0.0.2/32, private-key: priv, public-key: pub, mtu: 1280}
              - {name: kept, type: socks5, server: example.com, port: 1080}
        """.trimIndent()
        val parsed = RawUpdater.parseSubscription(yaml)
        assertEquals(listOf("wg1", "kept"), parsed?.map { it.name })
    }
}
