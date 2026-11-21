package io.nekohasekai.sagernet.ipinfo

import com.google.common.net.InetAddresses
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** An owner label is metadata, not evidence about how a request was routed. */
data class IpOwner(
    val ip: String,
    val countryCode: String = "",
    val country: String = "",
    val asn: String = "",
    val organization: String = "",
) {
    fun label(): String = buildList {
        val flag = countryFlag(countryCode)
        val place = listOf(flag, countryCode.ifBlank { country }).filter { it.isNotBlank() }.joinToString(" ")
        if (place.isNotBlank()) add(place)
        val provider = listOf(asn, organization).filter { it.isNotBlank() }.joinToString(" ")
        if (provider.isNotBlank()) add(provider)
    }.joinToString(" · ")

    companion object {
        fun countryFlag(code: String): String {
            if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return ""
            val base = 0x1F1E6 - 'A'.code
            return String(Character.toChars(base + code[0].uppercaseChar().code)) +
                String(Character.toChars(base + code[1].uppercaseChar().code))
        }
    }
}

object IpInfoStore {
    private const val OWNER_URL = "https://api.ip.sb/geoip/"
    private const val EXIT_URL = "https://api.ip.sb/geoip"
    private const val MAX_DNS = 65535L
    private const val MAX_OWNER = 16384L
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Pure literal check. Never calls a resolver, including on malformed input. */
    fun isIpLiteral(value: String): Boolean = literal(value) != null

    private fun literal(value: String): String? {
        val trimmed = value.trim()
        val candidate = if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            trimmed.substring(1, trimmed.lastIndex).takeIf { ':' in it } ?: return null
        } else trimmed
        // The Guava parser does not resolve names. Scope IDs are not public IPs.
        return candidate.takeIf { '%' !in it && InetAddresses.isInetAddress(it) }
    }

    /** Called ONLY after the user confirms disclosure of this IP to api.ip.sb. */
    suspend fun owner(ip: String): IpOwner? {
        val address = literal(ip) ?: return null
        val parsed = InetAddresses.forString(address)
        if (parsed.isAnyLocalAddress || parsed.isLoopbackAddress || parsed.isLinkLocalAddress ||
            parsed.isSiteLocalAddress || parsed.isMulticastAddress ||
            address.startsWith("fc", true) || address.startsWith("fd", true) ||
            parsed.address.let { it.size == 4 && it[0].toInt() == 100 && (it[1].toInt() and 0xC0) == 0x40 }
        ) return null
        val url = OWNER_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment(address).build()
        return parseOwner(client.read(request(url), MAX_OWNER), expectedIp = address)
    }

    /**
     * Configured group DNS is authoritative for this action: unsupported/failed servers never
     * send the node hostname to system DNS. With no group DNS, use the OS answers as usual.
     */
    suspend fun resolveEntry(host: String, groupDns: List<String>): String? {
        literal(host)?.let { return it }
        val name = dnsName(host) ?: return null
        if (groupDns.isEmpty()) return withContext(Dispatchers.IO) {
            // The platform call is blocking; suppress its result on lifecycle cancellation.
            runCatching { InetAddress.getAllByName(name).firstOrNull()?.hostAddress }.getOrNull()?.let(::literal)
        }
        return withTimeoutOrNull(12000L) {
            for (endpoint in groupDns.distinct()) {
                val url = groupDohUrl(endpoint) ?: continue
                for (type in listOf(1, 28)) {
                    val question = buildQuery(name, type)
                    val request = Request.Builder().url(url)
                        .post(question.toRequestBody("application/dns-message".toMediaType()))
                        .header("Accept", "application/dns-message").build()
                    val answer = client.read(request, MAX_DNS) ?: continue
                    parseAnswer(answer, question)?.let { return@withTimeoutOrNull it }
                }
            }
            null
        }
    }

    internal fun groupDohUrl(endpoint: String): HttpUrl? {
        val raw = endpoint.trim()
        if (raw.any { it == '?' || it == '#' || it == '@' || it == '\\' }) return null
        val standard = if (raw.startsWith("https+local://", true)) "https://" + raw.substringAfter("://") else raw
        val url = standard.toHttpUrlOrNull()?.takeIf { it.isHttps && standard.startsWith("https://", true) }
            ?: return null
        return if ('/' !in standard.substringAfter("://")) url.newBuilder().encodedPath("/dns-query").build() else url
    }

    private fun request(url: HttpUrl): Request = Request.Builder().url(url).header("Accept", "application/json").build()

    internal fun parseOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        fun field(key: String): String = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            .take(256).filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
        val ip = literal(field("ip")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val countryCode = field("country_code").takeIf { IpOwner.countryFlag(it).isNotEmpty() }.orEmpty()
        val asn = field("asn").toLongOrNull()?.takeIf { it in 1..4294967295L }?.let { "AS$it" }.orEmpty()
        IpOwner(ip, countryCode, field("country"), asn, field("asn_organization").ifBlank { field("organization") })
    }.getOrNull()

    /** Loopback CONNECT with unique credentials; there is no non-proxy route or local DNS fallback. */
    internal suspend fun probeExit(port: Int, password: String): IpOwner? {
        val proxyClient = client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException()
            })
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic("probe", password)).build()
            }.build()
        return parseOwner(proxyClient.read(request(EXIT_URL.toHttpUrlOrNull()!!), MAX_OWNER))
    }

    /** Accept only a generated concrete single outbound. No selectors, endpoints or arbitrary routing. */
    internal fun exitProbeConfig(generated: String, port: Int, password: String): String? = runCatching {
        val source = JsonParser.parseString(generated).asJsonObject
        if (source.getAsJsonArray("endpoints")?.size()?.let { it != 0 } == true ||
            source.getAsJsonArray("inbounds")?.size()?.let { it != 0 } == true
        ) return null
        val outbounds = source.getAsJsonArray("outbounds") ?: return null
        val outbound = outbounds.map { it.asJsonObject }.singleOrNull { it.get("tag")?.asString == "proxy" } ?: return null
        if (outbounds.size() != 3 || outbound.get("type")?.asString !in setOf(
                "socks", "http", "shadowsocks", "vmess", "vless", "vless_xhttp", "trojan", "hysteria", "hysteria2",
                "ssh", "tuic", "anytls", "snell", "mieru", "juicity", "masque",
            ) || outbound.has("detour") || outbound.get("server")?.asString.isNullOrBlank()
        ) return null
        val dns = source.getAsJsonObject("dns") ?: return null
        JsonObject().apply {
            add("log", JsonObject().apply { addProperty("disabled", true) })
            add("dns", dns)
            add("outbounds", JsonArray().apply { add(outbound) })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("tag", "ip-probe-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", port)
                    add("users", JsonArray().apply { add(JsonObject().apply {
                        addProperty("username", "probe")
                        addProperty("password", password)
                    }) })
                })
            })
            add("route", JsonObject().apply {
                addProperty("auto_detect_interface", true)
                addProperty("default_domain_resolver", "dns-direct")
                addProperty("final", "proxy")
            })
        }.toString()
    }.getOrNull()

    internal fun newProbePassword(): String = UUID.randomUUID().toString()

    private suspend fun OkHttpClient.read(request: Request, limit: Long): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val data = response.use {
                        runCatching {
                            val body = it.body ?: return@runCatching null
                            if (!it.isSuccessful || body.contentLength() > limit) return@runCatching null
                            if (request.method == "POST" && body.contentType()?.let { type ->
                                    type.type == "application" && type.subtype == "dns-message"
                                } != true) return@runCatching null
                            val source = body.source()
                            if (source.request(limit + 1)) null else source.readByteArray()
                        }.getOrNull()
                    }
                    cont.resume(data)
                }
            })
        }

    private fun dnsName(host: String): String? = runCatching {
        val name = IDN.toASCII(host.trim().removeSuffix("."), IDN.USE_STD3_ASCII_RULES)
        name.takeIf { it.length in 1..253 && it.split('.').all { label -> label.length in 1..63 } &&
            it.any { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } }
    }.getOrNull()

    internal fun buildDnsQueryA(host: String): ByteArray = buildQuery(requireNotNull(dnsName(host)), 1)

    internal fun buildQuery(host: String, type: Int): ByteArray {
        val id = kotlin.random.Random.nextInt(65536)
        val header = byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        val name = host.split('.').flatMap { label -> listOf(label.length.toByte()) + label.encodeToByteArray().toList() }
        return header + name.toByteArray() + byteArrayOf(0, 0, type.toByte(), 0, 1)
    }

    private data class DnsName(val text: String, val next: Int)

    private fun ByteArray.u16(offset: Int): Int = ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)

    /** Decode backwards-only compression pointers; bound work and expanded name length. */
    private fun readName(message: ByteArray, start: Int): DnsName? {
        var pos = start
        var next = -1
        var jumps = 0
        val name = StringBuilder()
        while (pos in message.indices) {
            val len = message[pos].toInt() and 255
            when {
                len == 0 -> return DnsName(name.toString().lowercase(), if (next < 0) pos + 1 else next)
                len >= 192 -> {
                    if (pos + 1 >= message.size || ++jumps > 32) return null
                    val pointer = message.u16(pos) and 0x3fff
                    if (pointer !in 12 until pos) return null
                    if (next < 0) next = pos + 2
                    pos = pointer
                }
                len > 63 || pos + len >= message.size || name.length + len + 1 > 254 -> return null
                else -> {
                    if (name.isNotEmpty()) name.append('.')
                    for (i in pos + 1..pos + len) {
                        val ch = (message[i].toInt() and 255).toChar()
                        if (!(ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_')) return null
                        name.append(ch)
                    }
                    pos += len + 1
                }
            }
        }
        return null
    }

    internal fun parseAnswer(message: ByteArray, query: ByteArray): String? {
        if (message.size < 12 || message.u16(0) != query.u16(0) || message.u16(4) != 1) return null
        if (message.u16(2) and 0xFA0F != 0x8000) return null // response, query opcode, no truncation/errors
        val question = readName(message, 12) ?: return null
        val original = readName(query, 12) ?: return null
        val qtype = query.u16(original.next)
        if (question.text != original.text || question.next + 4 > message.size ||
            message.u16(question.next) != qtype || message.u16(question.next + 2) != 1
        ) return null
        val aliases = mutableMapOf<String, String>()
        val addresses = mutableMapOf<String, String>()
        var pos = question.next + 4
        repeat(message.u16(6)) {
            val name = readName(message, pos) ?: return null
            pos = name.next
            if (pos + 10 > message.size) return null
            val type = message.u16(pos)
            val klass = message.u16(pos + 2)
            val len = message.u16(pos + 8)
            pos += 10
            if (pos + len > message.size) return null
            if (klass == 1 && type == qtype && len == if (qtype == 1) 4 else 16) {
                addresses.putIfAbsent(name.text, InetAddresses.toAddrString(InetAddress.getByAddress(message.copyOfRange(pos, pos + len))))
            } else if (klass == 1 && type == 5) {
                val target = readName(message, pos) ?: return null
                if (target.next != pos + len) return null
                aliases.putIfAbsent(name.text, target.text)
            }
            pos += len
        }
        var name = question.text
        val visited = HashSet<String>()
        while (visited.add(name)) name = aliases[name] ?: return addresses[name]
        return null
    }
}
