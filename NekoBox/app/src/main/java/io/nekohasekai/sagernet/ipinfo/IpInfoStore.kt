package io.nekohasekai.sagernet.ipinfo

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一个 IP 的归属信息。服务商与地区不参与脱敏，IP 本身可被界面隐藏。 */
data class IpOwner(
    val ip: String,
    val countryCode: String = "",
    val country: String = "",
    val asn: String = "",
    val organization: String = "",
) {
    /** 供卡片显示的紧凑标签，例如 “🇭🇰 HK · AS13335 Cloudflare”。 */
    fun label(): String = buildList {
        val flag = countryFlag(countryCode)
        val place = listOf(flag, countryCode.ifBlank { country }).filter { it.isNotBlank() }.joinToString(" ")
        if (place.isNotBlank()) add(place)
        val provider = listOf(asn, organization).filter { it.isNotBlank() }.joinToString(" ")
        if (provider.isNotBlank()) add(provider)
    }.joinToString(" · ")

    companion object {
        /** 由两位国家码生成旗帜 emoji；非法输入返回空串而不是异常字符。 */
        fun countryFlag(code: String): String {
            if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return ""
            val base = 0x1F1E6 - 'A'.code
            return String(Character.toChars(base + code[0].uppercaseChar().code)) +
                String(Character.toChars(base + code[1].uppercaseChar().code))
        }
    }
}

/**
 * 入口/出口 IP 的解析与归属查询。
 *
 * - 入口：只在配置了组节点 DNS（通常是私有 DoH）时用该解析器查询，避免把私有入口域名
 *   泄露给公网 DNS；没有组 DNS 时退回系统解析（普通节点）。
 * - 出口：只有当前正在运行的节点才有意义——此时请求天然经 VPN 走该节点，返回的就是
 *   它的真实落地 IP。
 * - 归属：api.ip.sb（HTTPS，无需 token，含 ASN）；失败静默，不打断任何流程。
 *
 * 结果按 IP 缓存在内存里，避免反复请求同一地址。
 */
object IpInfoStore {

    private const val OWNER_ENDPOINT = "https://api.ip.sb/geoip/"
    private const val ENTRY_TIMEOUT_MS = 6000L
    private const val EXIT_TIMEOUT_MS = 8000L

    private val ownerCache = ConcurrentHashMap<String, IpOwner>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 查询单个 IP 的归属；带内存缓存。失败返回 null（调用方保持沉默）。 */
    suspend fun owner(ip: String): IpOwner? {
        if (!isIpLiteral(ip)) return null
        ownerCache[ip]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(OWNER_ENDPOINT + ip)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NekoPro/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string() ?: return@runCatching null
                    val json = JsonParser.parseString(body).asJsonObject
                    val asnNumber = json.get("asn")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val owner = IpOwner(
                        ip = ip,
                        countryCode = json.get("country_code")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        country = json.get("country")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        asn = if (asnNumber.isBlank()) "" else "AS$asnNumber",
                        organization = json.get("asn_organization")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                            .ifBlank { json.get("organization")?.takeIf { !it.isJsonNull }?.asString.orEmpty() },
                    )
                    ownerCache[ip] = owner
                    owner
                }
            }.onFailure { Logs.d("IpOwner lookup failed for $ip: ${it.message}") }.getOrNull()
        }
    }

    /**
     * 解析节点入口域名。优先使用组 DNS（私有 DoH，能解析私有域名且不泄露给公网），
     * 其次系统解析。返回第一个可用地址；失败返回 null。
     */
    suspend fun resolveEntry(host: String, groupDns: List<String>): String? {
        if (host.isBlank()) return null
        if (isIpLiteral(host)) return host
        withContext(Dispatchers.IO) {
            for (dns in groupDns) {
                dohQuery(dns, host, ENTRY_TIMEOUT_MS)?.let { return@withContext it }
            }
            // 私有 DoH 全部失败时退回系统解析：普通 (公网) 节点域名仍可解析，
            // 私有域名会失败并返回 null，符合“不猜、不泄露”的预期。
            runCatching { InetAddress.getByName(host)?.hostAddress }.getOrNull()
        }?.let { return it }
        return null
    }

    /**
     * 取当前出口 IP。必须在 VPN 服务运行、且目标节点处于选中状态时调用，
     * 这样请求自然经该节点出网。失败返回 null。
     */
    suspend fun currentExitIp(): String? = withContext(Dispatchers.IO) {
        for (endpoint in listOf("https://api.ip.sb/ip", "https://api.ipify.org")) {
            val value = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "NekoPro/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()?.trim()?.substringBefore('\n')
                }
            }.getOrNull()
            if (!value.isNullOrBlank() && isIpLiteral(value)) return@withContext value
        }
        null
    }

    /** 是否是合法的 IPv4/IPv6 字面量（不做 DNS 查询）。 */
    fun isIpLiteral(value: String): Boolean {
        val candidate = value.trim().removePrefix("[").removeSuffix("]")
        if (candidate.isEmpty()) return false
        return runCatching { InetAddress.getByName(candidate) }.isSuccess &&
            (candidate.all { it.isDigit() || it == '.' } || candidate.contains(':'))
    }

    /**
     * 用一条最小的 DoH 查询（RFC 8484 POST application/dns-message）解析 A 记录。
     * 只支持 https:// 端点：这正是私有 DoH 的常见形态；其它传输返回 null 交给下一候选。
     */
    private fun dohQuery(endpoint: String, host: String, timeoutMs: Long): String? {
        if (!endpoint.startsWith("https://", ignoreCase = true)) return null
        val url = endpoint.substringBefore('?').substringBefore('#')
        val query = buildDnsQueryA(host)
        val callClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .post(query.toRequestBody("application/dns-message".toMediaType()))
                .header("Accept", "application/dns-message")
                .header("User-Agent", "NekoPro/1.0")
                .build()
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.bytes() ?: return@runCatching null
                parseFirstA(body)
            }
        }.getOrNull()
    }

    /** 构造一个 A 查询的 DNS 报文（递归期望，随机 ID 由报文头高位随机化）。 */
    internal fun buildDnsQueryA(host: String): ByteArray {
        val header = byteArrayOf(
            (Math.random() * 256).toInt().toByte(), (Math.random() * 256).toInt().toByte(),
            0x01, 0x00, // flags: recursion desired
            0x00, 0x01, // QDCOUNT
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val labels = host.trimEnd('.').split('.')
        val nameSize = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(header.size + nameSize + 4)
        header.copyInto(out)
        var offset = header.size
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) continue
            out[offset++] = label.length.toByte()
            for (ch in label) out[offset++] = ch.code.toByte()
        }
        out[offset++] = 0 // root
        out[offset++] = 0
        out[offset++] = 1 // QTYPE = A
        out[offset++] = 0
        out[offset++] = 1 // QCLASS = IN
        return out.copyOf(offset)
    }

    /** 从 DNS 响应中取出第一条 A 记录；压缩指针按规范解析。 */
    internal fun parseFirstA(message: ByteArray): String? {
        if (message.size < 12) return null
        val answerCount = ((message[6].toInt() and 0xFF) shl 8) or (message[7].toInt() and 0xFF)
        if (answerCount == 0) return null
        var offset = 12
        // 跳过问题段的 QNAME
        offset = skipName(message, offset) ?: return null
        offset += 4 // QTYPE + QCLASS
        repeat(answerCount) {
            offset = skipName(message, offset) ?: return null
            if (offset + 10 > message.size) return null
            val type = ((message[offset].toInt() and 0xFF) shl 8) or (message[offset + 1].toInt() and 0xFF)
            val rdLength = ((message[offset + 8].toInt() and 0xFF) shl 8) or (message[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > message.size) return null
            if (type == 1 && rdLength == 4) {
                return "${message[offset].toInt() and 0xFF}.${message[offset + 1].toInt() and 0xFF}." +
                    "${message[offset + 2].toInt() and 0xFF}.${message[offset + 3].toInt() and 0xFF}"
            }
            offset += rdLength
        }
        return null
    }

    /** 跳过（可能带压缩指针的）域名，返回其后的偏移；越界返回 null。 */
    private fun skipName(message: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += length + 1
        }
        return null
    }
}
