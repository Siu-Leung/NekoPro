package io.nekohasekai.sagernet.ipinfo

import com.google.common.net.InetAddresses
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
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
        val trimmedAsn = asn.trim()
        val trimmedOrg = organization.trim()
        val provider = when {
            trimmedAsn.isBlank() -> trimmedOrg
            trimmedOrg.isBlank() -> trimmedAsn
            trimmedOrg.equals(trimmedAsn, ignoreCase = true) -> trimmedAsn
            trimmedOrg.startsWith("$trimmedAsn ", ignoreCase = true) -> trimmedOrg
            else -> "$trimmedAsn $trimmedOrg"
        }
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
    private const val IPAPI_IS_URL = "https://api.ipapi.is/"
    private const val IPINFO_IO_URL = "https://ipinfo.io/"
    private const val IPINFO_IO_EXIT_URL = "https://ipinfo.io/json"
    private const val FREE_IPAPI_URL = "https://freeipapi.com/api/json/"
    private const val FREE_IPAPI_EXIT_URL = "https://freeipapi.com/api/json"
    private const val IPWHOIS_URL = "https://ipwho.is/"
    private const val CF_ONE_TRACE_URL = "https://1.1.1.1/cdn-cgi/trace"
    private const val CF_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val STEP_PROBE_TIMEOUT_MS = 5_000
    internal const val PROBE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val MAX_DNS = 65535L
    private const val MAX_OWNER = 16384L
    private const val OWNER_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_OWNER_CACHE_SIZE = 512

    private val ownerCache = ConcurrentHashMap<String, Pair<Long, IpOwner>>()

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCaSignature: Pair<Long, Long>? = null

    @Volatile
    private var cachedCustomTrust: Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager>? = null

    /** Pure literal check. Never calls a resolver, including on malformed input. */
    fun isIpLiteral(value: String): Boolean = literal(value) != null

    /**
     * Compares two IP literals canonically (handling bracketed IPv6 and compressed/expanded forms)
     * without triggering any DNS resolution.
     */
    fun sameIp(first: String, second: String): Boolean {
        val a = literal(first) ?: return false
        val b = literal(second) ?: return false
        return runCatching { InetAddresses.forString(a) == InetAddresses.forString(b) }.getOrDefault(false)
    }

    /** Returns `true` when [endpoint] is an HTTPS DoH resolver handled directly by [IpInfoStore]. */
    fun isHttpsResolver(endpoint: String): Boolean = groupDohUrl(endpoint) != null

    /**
     * Checks whether [ip] is a usable node entry address, rejecting sinkhole, unspecified,
     * loopback, link-local, multicast, FakeIP (`198.18.0.0/15`), IPv6 ULA (`fc00::/7`),
     * IPv4-compatible IPv6, and reserved (`240.0.0.0/4`) ranges.
     */
    fun isUsableEntryIp(ip: String): Boolean {
        val address = literal(ip) ?: return false
        val parsed = runCatching { InetAddresses.forString(address) }.getOrNull() ?: return false
        return !isSinkholeOrFakeIp(parsed)
    }

    /**
     * Strict public Internet routability check used before disclosing an IP to external
     * attribution services (`api.ip.sb`). Blocks all private, CGNAT, FakeIP, documentation,
     * loopback, link-local, multicast, ULA, and reserved ranges.
     */
    fun isPublicRoutableIp(ip: String): Boolean {
        val address = literal(ip) ?: return false
        val parsed = runCatching { InetAddresses.forString(address) }.getOrNull() ?: return false
        if (isSinkholeOrFakeIp(parsed)) return false
        if (parsed.isSiteLocalAddress) return false
        val raw = extractIpv4OrIpv6Bytes(parsed)
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            val b2 = raw[2].toInt() and 0xFF
            // CGNAT 100.64.0.0/10, IETF 192.0.0.0/24, TEST-NET-1/2/3.
            if (b0 == 100 && (b1 and 0xC0) == 0x40) return false
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false
            if (b0 == 198 && b1 == 51 && b2 == 100) return false
            if (b0 == 203 && b1 == 0 && b2 == 113) return false
            return true
        }
        // Block IPv6 documentation prefix 2001:db8::/32.
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val b2 = raw[2].toInt() and 0xFF
        val b3 = raw[3].toInt() and 0xFF
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) return false
        return true
    }

    private fun extractIpv4OrIpv6Bytes(parsed: InetAddress): ByteArray {
        val bytes = parsed.address
        // Unmap IPv4-mapped IPv6 (::ffff:a.b.c.d) to inspect the underlying IPv4 range.
        if (bytes.size == 16 &&
            (0..9).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        ) {
            return bytes.copyOfRange(12, 16)
        }
        return bytes
    }

    private fun isSinkholeOrFakeIp(parsed: InetAddress): Boolean {
        if (parsed.isAnyLocalAddress || parsed.isLoopbackAddress ||
            parsed.isLinkLocalAddress || parsed.isMulticastAddress
        ) return true
        val bytes = parsed.address
        if (bytes.size == 16) {
            // Block deprecated IPv4-compatible IPv6 (::a.b.c.d where first 12 bytes are 0).
            if ((0..11).all { bytes[it].toInt() == 0 }) return true
        }
        val raw = extractIpv4OrIpv6Bytes(parsed)
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            // Block 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, FakeIP/RFC2544 198.18.0.0/15, >= 224.0.0.0/3.
            if (b0 == 0 || b0 == 127 || b0 >= 224) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            return false
        }
        // Block IPv6 ULA fc00::/7 using bitmask instead of hex string prefix.
        val firstByte = raw[0].toInt() and 0xFF
        return (firstByte and 0xFE) == 0xFC
    }

    private fun literal(value: String): String? {
        val trimmed = value.trim()
        val candidate = if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            trimmed.substring(1, trimmed.lastIndex).takeIf { ':' in it } ?: return null
        } else trimmed
        // The Guava parser does not resolve names. Scope IDs are not public IPs.
        return candidate.takeIf { '%' !in it && InetAddresses.isInetAddress(it) }
    }

    /**
     * Loads the user's custom root CA (`filesDir/ca.pem`) merged with the system trust store,
     * cached by file modification time and size. Returns `null` when absent or outside Android.
     */
    private fun loadCustomTrustPair(): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager>? {
        val caFile = runCatching { File(SagerNet.application.filesDir, "ca.pem") }.getOrNull()
            ?: return null
        if (!caFile.isFile || caFile.length() <= 0L) {
            cachedCaSignature = null
            cachedCustomTrust = null
            return null
        }
        val signature = caFile.lastModified() to caFile.length()
        if (cachedCaSignature == signature) return cachedCustomTrust
        return runCatching {
            val customCerts = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(caFile.readBytes()))
                .filterIsInstance<X509Certificate>()
            if (customCerts.isEmpty()) return@runCatching null

            val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
            val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return@runCatching null

            val customKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                customCerts.forEachIndexed { idx, cert -> setCertificateEntry("custom_$idx", cert) }
            }
            val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(customKs) }
            val customTm = customTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return@runCatching null

            val combinedTm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    systemTm.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        systemTm.checkServerTrusted(chain, authType)
                    } catch (_: Exception) {
                        customTm.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    systemTm.acceptedIssuers + customTm.acceptedIssuers
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(combinedTm), null)
            }
            (sslContext.socketFactory to combinedTm).also {
                cachedCaSignature = signature
                cachedCustomTrust = it
            }
        }.getOrNull()
    }

    /**
     * Builds an OkHttp client trusting any imported custom root CA (`ca.pem`) with the
     * requested call timeout, without forcing physical network socket binding.
     */
    private fun customCaClient(timeoutSeconds: Long): OkHttpClient {
        val builder = client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        loadCustomTrustPair()?.let { (factory, trustManager) ->
            builder.sslSocketFactory(factory, trustManager)
        }
        return builder.build()
    }

    /**
     * Builds an OkHttp client for Group DoH resolution bound to the physical underlying
     * network (bypassing TUN/VPN exit) and trusting any imported custom root CA (`ca.pem`).
     */
    private fun directDohClient(): Pair<OkHttpClient, Boolean> {
        val builder = client.newBuilder().callTimeout(6, TimeUnit.SECONDS)
        val underlying = runCatching { SagerNet.underlyingNetwork }.getOrNull()
        val boundUnderlying = underlying != null
        if (underlying != null) {
            builder.socketFactory(underlying.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    underlying.getAllByName(hostname).toList()
            })
        }
        loadCustomTrustPair()?.let { (factory, trustManager) ->
            builder.sslSocketFactory(factory, trustManager)
        }
        return builder.build() to boundUnderlying
    }

    private fun canonicalCacheKey(ip: String): String? =
        literal(ip)?.let { runCatching { InetAddresses.toAddrString(InetAddresses.forString(it)) }.getOrNull() }

    private fun readCachedOwner(ip: String): IpOwner? {
        val key = canonicalCacheKey(ip) ?: return null
        val cached = ownerCache[key] ?: return null
        if (System.currentTimeMillis() - cached.first > OWNER_CACHE_TTL_MS) {
            ownerCache.remove(key)
            return null
        }
        return cached.second
    }

    private fun storeCachedOwner(owner: IpOwner): IpOwner {
        val key = canonicalCacheKey(owner.ip) ?: return owner
        if (ownerCache.size >= MAX_OWNER_CACHE_SIZE) {
            ownerCache.keys.firstOrNull()?.let(ownerCache::remove)
        }
        ownerCache[key] = System.currentTimeMillis() to owner
        return owner
    }

    /**
     * Builds ordered OkHttp clients for external IP owner lookups (`api.ip.sb` / `ipwho.is`).
     * Whenever the proxy service is connected (in either VPN or Proxy-only mode), tries the
     * local `mixedPort` first so attribution requests succeed even when direct ISP access to
     * GeoIP APIs is blocked or throttled, then falls back to `customCaClient`.
     */
    private data class GeoProviderSpec(
        val url: HttpUrl,
        val parse: (ByteArray?) -> IpOwner?,
    )

    private fun buildOwnerProviders(address: String): List<GeoProviderSpec> = listOf(
        GeoProviderSpec(
            url = OWNER_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment(address).build(),
            parse = { parseOwner(it, expectedIp = address) },
        ),
        GeoProviderSpec(
            url = IPAPI_IS_URL.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("q", address).build(),
            parse = { parseIpApiIsOwner(it, expectedIp = address) },
        ),
        GeoProviderSpec(
            url = IPINFO_IO_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment(address).addPathSegment("json").build(),
            parse = { parseIpInfoIoOwner(it, expectedIp = address) },
        ),
        GeoProviderSpec(
            url = FREE_IPAPI_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment(address).build(),
            parse = { parseFreeIpApiOwner(it, expectedIp = address) },
        ),
        GeoProviderSpec(
            url = IPWHOIS_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment(address).build(),
            parse = { parseIpWhoisOwner(it, expectedIp = address) },
        ),
    )

    private fun buildExitProviders(): List<GeoProviderSpec> = listOf(
        GeoProviderSpec(EXIT_URL.toHttpUrlOrNull()!!) { parseOwner(it) },
        GeoProviderSpec(IPAPI_IS_URL.toHttpUrlOrNull()!!) { parseIpApiIsOwner(it) },
        GeoProviderSpec(IPINFO_IO_EXIT_URL.toHttpUrlOrNull()!!) { parseIpInfoIoOwner(it) },
        GeoProviderSpec(FREE_IPAPI_EXIT_URL.toHttpUrlOrNull()!!) { parseFreeIpApiOwner(it) },
        GeoProviderSpec(IPWHOIS_URL.toHttpUrlOrNull()!!) { parseIpWhoisOwner(it) },
    )

    private fun ownerClients(): List<OkHttpClient> {
        val directClient = customCaClient(timeoutSeconds = 5L)
        val isConnected = runCatching { DataStore.serviceState.connected }.getOrDefault(false)
        if (!isConnected) return listOf(directClient)
        val mixedPort = runCatching { DataStore.mixedPort }.getOrDefault(0)
        if (mixedPort !in 1..65535) return listOf(directClient)
        val localProxyClient = customCaClient(timeoutSeconds = 5L).newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort)))
            .build()
        return listOf(localProxyClient, directClient)
    }

    /** Called ONLY after the user confirms disclosure of this IP to GeoIP attribution APIs. */
    suspend fun owner(ip: String): IpOwner? {
        val address = literal(ip) ?: return null
        if (!isPublicRoutableIp(address)) return null
        readCachedOwner(address)?.let { return it }

        val providers = buildOwnerProviders(address)
        for (httpClient in ownerClients()) {
            for (provider in providers) {
                provider.parse(httpClient.read(request(provider.url), MAX_OWNER))
                    ?.takeIf { it.label().isNotBlank() }
                    ?.let { return storeCachedOwner(it) }
            }
        }
        return null
    }

    /**
     * Resolves ASN/ISP ownership for [ip] preferentially over the node's own isolated [box]
     * tunnel (avoiding direct-ISP blocks against GeoIP endpoints when VPN is disconnected),
     * falling back to [owner] if [box] is null or all providers over [box] fail.
     */
    suspend fun ownerViaBox(box: libcore.BoxInstance?, ip: String, timeoutMs: Int = 8000): IpOwner? {
        val address = literal(ip) ?: return null
        if (!isPublicRoutableIp(address)) return null
        readCachedOwner(address)?.let { return it }

        if (box != null) {
            val perCallTimeout = timeoutMs.coerceAtMost(STEP_PROBE_TIMEOUT_MS)
            for (provider in buildOwnerProviders(address)) {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        box.fetchHttpsJson(provider.url.toString(), PROBE_USER_AGENT, MAX_OWNER, perCallTimeout)
                    }.getOrNull()
                }
                provider.parse(raw)
                    ?.takeIf { it.label().isNotBlank() }
                    ?.let { return storeCachedOwner(it) }
            }
        }
        return owner(address)
    }

    /**
     * Probes the real egress IP and ASN/ISP strictly through [box]'s `"proxy"` outbound/endpoint
     * across 5 GeoIP/ASN providers plus Cloudflare `1.1.1.1` / `www.cloudflare.com` trace fallbacks.
     */
    suspend fun probeExitViaBox(box: libcore.BoxInstance, timeoutMs: Int = 10000): IpOwner? {
        val perCallTimeout = timeoutMs.coerceAtMost(STEP_PROBE_TIMEOUT_MS)
        var bareExitCandidate: IpOwner? = null

        for (provider in buildExitProviders()) {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    box.fetchHttpsJson(provider.url.toString(), PROBE_USER_AGENT, MAX_OWNER, perCallTimeout)
                }.getOrNull()
            }
            val parsed = provider.parse(raw)?.takeIf { isPublicRoutableIp(it.ip) } ?: continue
            if (parsed.asn.isNotBlank() || parsed.organization.isNotBlank()) {
                return storeCachedOwner(parsed)
            }
            if (bareExitCandidate == null) {
                bareExitCandidate = parsed
            }
        }

        val traceOwner = bareExitCandidate ?: run {
            for (traceUrl in listOf(CF_ONE_TRACE_URL, CF_TRACE_URL)) {
                val traceBytes = withContext(Dispatchers.IO) {
                    runCatching { box.fetchHttpsJson(traceUrl, PROBE_USER_AGENT, MAX_OWNER, perCallTimeout) }.getOrNull()
                }
                val candidate = parseCloudflareTrace(traceBytes)?.takeIf { isPublicRoutableIp(it.ip) }
                if (candidate != null) return@run candidate
            }
            null
        } ?: return null

        val enriched = ownerViaBox(box, traceOwner.ip, perCallTimeout)
        if (enriched != null && enriched.label().isNotBlank()) {
            return enriched
        }
        return storeCachedOwner(traceOwner)
    }

    /**
     * Configured group DNS is authoritative for this action: unsupported/failed servers never
     * send the node hostname to system DNS. With no group DNS, prefer [SagerNet.underlyingNetwork]
     * to bypass TUN FakeDNS poisoning and reject non-usable/FakeIP answers.
     */
    suspend fun resolveEntry(host: String, groupDns: List<String>): String? {
        literal(host)?.let { return it }
        val name = dnsName(host) ?: return null
        if (groupDns.isEmpty()) return withContext(Dispatchers.IO) {
            // Prefer the physical underlying network so active TUN FakeDNS cannot return 198.18.x.x.
            val addresses = runCatching {
                SagerNet.underlyingNetwork?.getAllByName(name)
            }.getOrNull() ?: runCatching {
                InetAddress.getAllByName(name)
            }.getOrNull() ?: return@withContext null
            addresses.asSequence()
                .mapNotNull { it.hostAddress?.let(::literal) }
                .firstOrNull(::isUsableEntryIp)
        }
        val (dohClient, boundUnderlying) = directDohClient()
        // WHY: Some Android OEM ROMs or Always-On VPN policies reject direct physical socketFactory
        // calls from the UI process; fall back to default routing (still rejecting FakeIP/bogons).
        val fallbackDohClient by lazy { customCaClient(timeoutSeconds = 6L) }
        return withTimeoutOrNull(14000L) {
            for (endpoint in groupDns.distinct()) {
                val url = groupDohUrl(endpoint)
                if (url != null) {
                    for (type in listOf(1, 28)) {
                        val question = buildQuery(name, type)
                        val request = Request.Builder().url(url)
                            .post(question.toRequestBody("application/dns-message".toMediaType()))
                            .header("Accept", "application/dns-message")
                            .header("User-Agent", PROBE_USER_AGENT)
                            .build()
                        val answer = dohClient.read(request, MAX_DNS)
                            ?: (if (boundUnderlying) fallbackDohClient.read(request, MAX_DNS) else null)
                            ?: continue
                        parseAnswer(answer, question)?.takeIf(::isUsableEntryIp)?.let {
                            return@withTimeoutOrNull it
                        }
                    }
                } else {
                    resolveEntryViaSingBoxDns(name, endpoint)?.let {
                        return@withTimeoutOrNull it
                    }
                }
            }
            null
        }
    }

    /**
     * Resolves [name] through non-HTTPS group DNS schemes (`h3://`, `tls://`, `quic://`, `tcp://`,
     * `udp://`) using an ephemeral `sing-box` test DNS instance.
     */
    private suspend fun resolveEntryViaSingBoxDns(name: String, endpoint: String): String? =
        withContext(Dispatchers.IO) {
            val parsedGroupServer = runCatching {
                moe.matsuri.nb4a.SingBoxDnsServerParser.parseGroupNodeDns(endpoint, "dns-group")
            }.getOrNull() ?: return@withContext null
            val options = moe.matsuri.nb4a.SingBoxOptions.MyOptions().apply {
                log = moe.matsuri.nb4a.SingBoxOptions.LogOptions().apply { level = "panic" }
                dns = moe.matsuri.nb4a.SingBoxOptions.DNSOptions().apply {
                    servers = mutableListOf(
                        moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions().apply {
                            type = "local"
                            tag = "dns-direct"
                        },
                        parsedGroupServer,
                    )
                    final_ = "dns-group"
                }
                outbounds = mutableListOf(
                    moe.matsuri.nb4a.SingBoxOptions.Outbound().apply {
                        type = "direct"
                        tag = "direct"
                    }
                )
            }
            val configJson = io.nekohasekai.sagernet.fmt.gson.gson.toJson(options.asMap())
            var testBox: libcore.BoxInstance? = null
            try {
                testBox = libcore.Libcore.newTestSingBoxInstance(
                    configJson,
                    moe.matsuri.nb4a.net.LocalResolverImpl,
                )
                testBox.start()
                val resolved = testBox.lookupDomainWithTag(name, "dns-group", 6000)
                literal(resolved)?.takeIf(::isUsableEntryIp)
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { testBox?.close() }
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

    // WHY: api.ip.sb sits behind Cloudflare WAF which blocks default `okhttp/*` User-Agents with HTTP 403.
    internal fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", PROBE_USER_AGENT)
        .build()

    private fun sanitizeJsonText(raw: String?): String = raw.orEmpty()
        .take(256)
        .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .trim()

    private fun readJsonField(obj: JsonObject?, key: String): String =
        sanitizeJsonText(obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString)

    internal fun parseOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        val ip = literal(readJsonField(json, "ip")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val countryCode = readJsonField(json, "country_code").takeIf { IpOwner.countryFlag(it).isNotEmpty() }.orEmpty()
        val asn = readJsonField(json, "asn").toLongOrNull()?.takeIf { it in 1..4294967295L }?.let { "AS$it" }.orEmpty()
        val org = readJsonField(json, "asn_organization").ifBlank { readJsonField(json, "organization") }
        IpOwner(ip, countryCode, readJsonField(json, "country"), asn, org)
    }.getOrNull()

    /**
     * Parses BGP/ASN responses from `https://api.ipapi.is/` (`location`, `asn`, and `company` objects).
     */
    internal fun parseIpApiIsOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        val ip = literal(readJsonField(json, "ip")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val locationObj = json.get("location")?.takeIf { it.isJsonObject }?.asJsonObject
        val asnObj = json.get("asn")?.takeIf { it.isJsonObject }?.asJsonObject
        val companyObj = json.get("company")?.takeIf { it.isJsonObject }?.asJsonObject
        val countryCode = readJsonField(locationObj, "country_code")
            .uppercase()
            .takeIf { IpOwner.countryFlag(it).isNotEmpty() }
            .orEmpty()
        val asn = readJsonField(asnObj, "asn")
            .toLongOrNull()
            ?.takeIf { it in 1..4294967295L }
            ?.let { "AS$it" }
            .orEmpty()
        val org = readJsonField(asnObj, "org")
            .ifBlank { readJsonField(companyObj, "name") }
            .ifBlank { readJsonField(asnObj, "descr") }
        IpOwner(ip, countryCode, readJsonField(locationObj, "country"), asn, org)
    }.getOrNull()

    /**
     * Parses GeoIP/ASN responses from `https://ipinfo.io/json` and `https://ipinfo.io/<ip>/json`,
     * splitting combined `org` fields like `"AS4134 Chinanet"` into structured ASN and ISP name.
     */
    internal fun parseIpInfoIoOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        val ip = literal(readJsonField(json, "ip")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val countryCode = readJsonField(json, "country")
            .uppercase()
            .takeIf { IpOwner.countryFlag(it).isNotEmpty() }
            .orEmpty()
        val rawOrg = readJsonField(json, "org")
        var asn = ""
        var org = rawOrg
        if (rawOrg.startsWith("AS", ignoreCase = true)) {
            val firstToken = rawOrg.substringBefore(' ')
            val num = firstToken.drop(2).toLongOrNull()
            if (num != null && num in 1..4294967295L) {
                asn = "AS$num"
                org = rawOrg.substringAfter(' ', "").trim()
            }
        }
        IpOwner(ip = ip, countryCode = countryCode, asn = asn, organization = org)
    }.getOrNull()

    /**
     * Parses GeoIP/ASN responses from `https://freeipapi.com/api/json`.
     */
    internal fun parseFreeIpApiOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        val ip = literal(readJsonField(json, "ipAddress")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val countryCode = readJsonField(json, "countryCode")
            .uppercase()
            .takeIf { IpOwner.countryFlag(it).isNotEmpty() }
            .orEmpty()
        val asn = readJsonField(json, "asn")
            .removePrefix("AS")
            .removePrefix("as")
            .toLongOrNull()
            ?.takeIf { it in 1..4294967295L }
            ?.let { "AS$it" }
            .orEmpty()
        val org = readJsonField(json, "asnOrganization")
        IpOwner(ip, countryCode, readJsonField(json, "countryName"), asn, org)
    }.getOrNull()

    /**
     * Parses fallback GeoIP/ASN JSON responses from `https://ipwho.is/` with identical
     * IP address validation, `expectedIp` matching, and control-character sanitization.
     */
    internal fun parseIpWhoisOwner(body: ByteArray?, expectedIp: String? = null): IpOwner? = runCatching {
        val json = JsonParser.parseString(body?.decodeToString() ?: return null).asJsonObject
        if (json.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return null
        val ip = literal(readJsonField(json, "ip")) ?: return null
        if (expectedIp != null && InetAddresses.forString(ip) != InetAddresses.forString(expectedIp)) return null
        val countryCode = readJsonField(json, "country_code").takeIf { IpOwner.countryFlag(it).isNotEmpty() }.orEmpty()
        val connection = json.get("connection")?.takeIf { it.isJsonObject }?.asJsonObject
        val asn = readJsonField(connection, "asn").toLongOrNull()?.takeIf { it in 1..4294967295L }?.let { "AS$it" }.orEmpty()
        val org = readJsonField(connection, "org").ifBlank { readJsonField(connection, "isp") }
        IpOwner(ip, countryCode, readJsonField(json, "country"), asn, org)
    }.getOrNull()

    /**
     * Parses Cloudflare `cdn-cgi/trace` key-value text (`ip=...` and `loc=...`) as a last-resort
     * exit verification fallback when JSON GeoIP APIs rate-limit shared proxy egress IPs.
     */
    internal fun parseCloudflareTrace(body: ByteArray?): IpOwner? = runCatching {
        val text = body?.decodeToString() ?: return null
        var rawIp = ""
        var rawLoc = ""
        for (line in text.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = sanitizeJsonText(line.substring(idx + 1))
            when (k) {
                "ip" -> rawIp = v
                "loc" -> rawLoc = v
            }
        }
        val ip = literal(rawIp) ?: return null
        val countryCode = rawLoc.uppercase().takeIf { IpOwner.countryFlag(it).isNotEmpty() }.orEmpty()
        IpOwner(ip = ip, countryCode = countryCode)
    }.getOrNull()

    /** Loopback CONNECT with unique credentials; there is no non-proxy route or local DNS fallback. */
    internal suspend fun probeExit(port: Int, password: String): IpOwner? {
        // WHY: Cold-starting an isolated sing-box test box + TLS proxy handshake + GeoIP lookup
        // frequently takes 5-9s on cellular links, so 12s prevents false exit-probe timeouts.
        val proxyClient = client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException()
            })
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic("probe", password)).build()
            }.build()
        parseOwner(proxyClient.read(request(EXIT_URL.toHttpUrlOrNull()!!), MAX_OWNER))?.let { return it }
        return parseIpWhoisOwner(proxyClient.read(request(IPWHOIS_URL.toHttpUrlOrNull()!!), MAX_OWNER))
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
