package io.nekohasekai.sagernet.ipinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址归属的纯 JVM 回归：DNS 报文构造/解析与脱敏判定都在本地完成，
 * 不需要网络、真实 DoH 或任何凭据。
 */
class IpInfoRegressionTest {

    @Test
    fun buildsAQueryWithValidWireLayout() {
        val query = IpInfoStore.buildDnsQueryA("entry.example.com")
        // 头部 12 字节：ID(2) flags(2) QD(2) AN(2) NS(2) AR(2)
        assertEquals(0x01, query[2].toInt() and 0xFF)
        assertEquals(0x00, query[3].toInt() and 0xFF)
        assertEquals(1, query[5].toInt() and 0xFF)
        // QNAME: 5"entry" 7"example" 3"com" 0
        assertEquals(5, query[12].toInt())
        assertEquals("entry".toByteArray().toList(), query.copyOfRange(13, 18).toList())
        assertEquals(7, query[18].toInt())
        assertEquals("example".toByteArray().toList(), query.copyOfRange(19, 26).toList())
        assertEquals(3, query[26].toInt())
        assertEquals("com".toByteArray().toList(), query.copyOfRange(27, 30).toList())
        assertEquals(0, query[30].toInt())
        // QTYPE=A(1) 在 31-32，QCLASS=IN(1) 在 33-34（高字节为 0）
        assertEquals(0, query[31].toInt())
        assertEquals(1, query[32].toInt())
        assertEquals(0, query[33].toInt())
        assertEquals(1, query[34].toInt())
        assertEquals(35, query.size)
    }

    @Test
    fun parsesFirstAnswerAddress() {
        val response = dnsResponse("entry.example.com", byteArrayOf(212.toByte(), 87, 192.toByte(), 20))
        assertEquals("212.87.192.20", IpInfoStore.parseFirstA(response))
    }

    @Test
    fun rejectsMalformedAnswers() {
        assertNull(IpInfoStore.parseFirstA(ByteArray(0)))
        assertNull(IpInfoStore.parseFirstA(ByteArray(11)))
        // 没有 answer
        assertNull(IpInfoStore.parseFirstA(IpInfoStore.buildDnsQueryA("a.example")))
        // answer 段被截断
        val truncated = dnsResponse("a.example", byteArrayOf(1, 2, 3, 4)).copyOf(20)
        assertNull(IpInfoStore.parseFirstA(truncated))
    }

    @Test
    fun countryFlagIsSafeForOddInput() {
        assertEquals("\uD83C\uDDED\uD83C\uDDF0", IpOwner.countryFlag("HK"))
        assertEquals("\uD83C\uDDED\uD83C\uDDF0", IpOwner.countryFlag("hk"))
        assertEquals("", IpOwner.countryFlag(""))
        assertEquals("", IpOwner.countryFlag("H"))
        assertEquals("", IpOwner.countryFlag("香港"))
    }

    @Test
    fun ownerLabelKeepsProviderAndPlace() {
        val label = IpOwner("212.87.192.20", "HK", "Hong Kong", "AS13335", "Cloudflare, Inc.").label()
        assertTrue(label.contains("HK"))
        assertTrue(label.contains("AS13335"))
        assertTrue(label.contains("Cloudflare, Inc."))
        // 只有 IP 时标签为空——调用方据此隐藏该行而不是显示空白
        assertEquals("", IpOwner("1.2.3.4").label())
    }

    @Test
    fun distinguishesLiteralsFromHostnames() {
        assertTrue(IpInfoStore.isIpLiteral("212.87.192.20"))
        assertTrue(IpInfoStore.isIpLiteral("2606:4700::1111"))
        assertTrue(IpInfoStore.isIpLiteral("[2606:4700::1111]"))
        assertTrue(!IpInfoStore.isIpLiteral("entry.example.com"))
        assertTrue(!IpInfoStore.isIpLiteral(""))
    }

    @Test
    fun redactsDomainsButKeepsPortAndTld() {
        assertEquals(
            "27a5****.***.sbs:15009",
            AddressRedactor.redactAddress("27a57b10-a7d0-45d2-b28e-4d361c4519f6.csmmdglwhg.sbs:15009"),
        )
        // 短标签整体遮蔽，避免只剩一两个字符仍可辨认
        assertEquals("****.***.com", AddressRedactor.redactAddress("ab.example.com"))
        // 端口不参与遮蔽
        assertTrue(AddressRedactor.redactAddress("node.example.net:443").endsWith(":443"))
    }

    @Test
    fun redactsIpv4AndIpv6KeepingLeadingOctets() {
        assertEquals("212.87.*.*", AddressRedactor.redactAddress("212.87.192.20"))
        assertEquals("212.87.*.*:443", AddressRedactor.redactAddress("212.87.192.20:443"))
        assertEquals("2606::***", AddressRedactor.redactAddress("2606:4700::1111"))
        assertEquals("2606::***:443", AddressRedactor.redactAddress("[2606:4700::1111]:443"))
    }

    @Test
    fun maskIpHonoursToggleAndNeverTouchesBlankOrProvider() {
        assertEquals("212.87.192.20", AddressRedactor.maskIp("212.87.192.20", false))
        assertEquals("212.87.*.*", AddressRedactor.maskIp("212.87.192.20", true))
        assertEquals("", AddressRedactor.maskIp("", true))
        // 服务商标签由调用方拼接，这里不可能出现
        assertEquals("AS13335 Cloudflare", AddressRedactor.maskIp("AS13335 Cloudflare", true))
    }

    /** 最小 A 应答：头部 + 问题段 + 一条压缩指针答案。 */
    private fun dnsResponse(host: String, address: ByteArray): ByteArray {
        val question = IpInfoStore.buildDnsQueryA(host).copyOfRange(12, IpInfoStore.buildDnsQueryA(host).size)
        val out = ArrayList<Byte>()
        // flags: response + recursion available, QD=1, AN=1
        listOf(0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00).forEach { out.add(it.toByte()) }
        question.forEach { out.add(it) }
        // 指针指向 offset 12，TYPE=A CLASS=IN TTL=60 RDLENGTH=4
        listOf(0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04).forEach { out.add(it.toByte()) }
        address.forEach { out.add(it) }
        return out.toByteArray()
    }
}
