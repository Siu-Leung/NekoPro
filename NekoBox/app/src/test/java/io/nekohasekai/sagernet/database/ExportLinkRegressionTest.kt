package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出链接的纯 JVM 回归：私有入口域名必须换成已解析的真实 IP，
 * 而公网可解析域名、纯 IP 节点、以及没有解析结果的节点都必须保持原样。
 */
class ExportLinkRegressionTest {

    private fun anyTls(server: String) = AnyTlsFixture(server)

    private class AnyTlsFixture(private val server: String) {
        fun entity(entryIp: String): ProxyEntity = ProxyEntity().apply {
            type = ProxyEntity.TYPE_ANYTLS
            anyTLSBean = moe.matsuri.nb4a.proxy.anytls.AnyTLSBean().apply {
                initializeDefaultValues()
                name = "fixture"
                serverAddress = server
                serverPort = 15009
                password = "dummy"
                sni = "sni.example.com"
            }
            this.entryIp = entryIp
        }
    }

    @Test
    fun privateEntryDomainIsExportedAsResolvedIp() {
        val entity = anyTls("27a57b10-a7d0-45d2-b28e-4d361c4519f6.csmmdglwhg.example").entity("212.87.192.20")
        val link = entity.exportLink(universal = true)
        assertTrue("link should carry the IP: $link", link.contains("212.87.192.20"))
        assertTrue("link must not leak the private host: $link", !link.contains("csmmdglwhg"))
        // 导出后节点本身必须还原，不能影响卡片与连接
        assertEquals(
            "27a57b10-a7d0-45d2-b28e-4d361c4519f6.csmmdglwhg.example",
            entity.requireBean().serverAddress,
        )
    }

    @Test
    fun publicHostWithoutResolutionStaysUntouched() {
        val entity = anyTls("public.example.com").entity("")
        val link = entity.exportLink(universal = true)
        assertTrue(link.contains("public.example.com"))
    }

    @Test
    fun ipLiteralNodeIsNeverRewritten() {
        val entity = anyTls("198.51.100.9").entity("203.0.113.7")
        val link = entity.exportLink(universal = false)
        assertTrue(link.contains("198.51.100.9"))
        assertTrue(!link.contains("203.0.113.7"))
    }

    @Test
    fun invalidStoredValueIsIgnored() {
        val entity = anyTls("private.example.com").entity("not-an-ip")
        val link = entity.exportLink(universal = true)
        assertTrue(link.contains("private.example.com"))
    }

    @Test
    fun standardLinkAlsoUsesResolvedIp() {
        val entity = anyTls("token.private.example.net").entity("212.87.192.20")
        val std = entity.exportLink(universal = false)
        assertTrue("standard link should use the IP: $std", std.contains("212.87.192.20"))
    }
}
