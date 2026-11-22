package io.nekohasekai.sagernet.group

import com.google.common.net.InetAddresses
import moe.matsuri.nb4a.SingBoxDnsServerParser
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Parses subscription DNS candidates across Clash/Mihomo YAML, sing-box JSON, and
 * Surge/Surfboard `.conf` profiles without performing network requests or DNS lookups.
 */
internal object ClashDnsCandidates {
    enum class Category(val key: String, val policy: Boolean = false) {
        NODE("proxy-server-nameserver"),
        NODE_POLICY("proxy-server-nameserver-policy", true),
        DEFAULT("nameserver"),
        POLICY("nameserver-policy", true),
        FALLBACK("fallback"),
        DIRECT("direct-nameserver"),
        BOOTSTRAP("default-nameserver"),
        SING_BOX("dns.servers"),
        SURGE("doh-server / dns-server"),
        SCANNED("detected-doh-urls"),
    }

    enum class Status { SELECTABLE, SELF_REFERENCE, LOCAL, UNSUPPORTED, UNSAFE, INVALID }

    // Regular classes intentionally do not put a potentially credential-bearing URL in toString().
    class Row(val category: Category, val policy: String?, val label: String?, val status: Status) {
        val selectable: String? get() = label.takeIf { status == Status.SELECTABLE }
    }

    class Profile(
        val rows: List<Row>,
        val auto: List<String>,
        val hasDns: Boolean,
        val truncated: Boolean,
        val malformed: Boolean = false,
    )

    /**
     * Default inspection clients, most likely to return a complete profile first.
     *
     * Providers commonly serve a nodes-only body to plain `clash-meta` while answering
     * FlClash or Clash Verge with the full YAML including `dns`. The order is therefore a
     * fallback chain, not a preference: the first response that carries a DNS section wins.
     */
    val CLIENT_USER_AGENTS = listOf(
        "FlClash/v0.9.4 clash-verge Platform/android",
        "clash-verge/v2.0.0",
        "clash-meta",
        "NekoPro/Android",
    )

    const val MAX_ROWS = 128
    private const val MAX_ENDPOINT_LENGTH = 2048
    private const val MAX_YAML_ALIASES = 8
    private const val MAX_YAML_NESTING_DEPTH = 20
    private val portableSchemes = setOf("udp", "tcp", "tls", "https", "quic", "h3")
    private val uuidSegment = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    private val ambiguousNumericHost = Regex("(?i)(?:0x[0x0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")
    private val ambiguousPathEscape = Regex("(?i)%(?:00|0[ad]|2f|5c)")
    private val listenerAddress = Regex("(\\[[^\\[\\]]+]|[^:]*):([0-9]{1,5})")
    private val profileKey = Regex(
        """(?m)^[\t ]*(?:\{[\t ]*)?(?:proxies|proxy-providers|outbounds|dns|"proxies"|"proxy-providers"|"outbounds"|"dns"|'proxies'|'proxy-providers'|'outbounds'|'dns')[\t ]*:"""
    )
    private val surgeSectionRegex = Regex("""(?im)^[\t ]*\[(?:General|Proxy)][\t ]*$""")
    private val embeddedEncryptedDnsRegex = Regex(
        """(?i)\b(?:https|h3|doh|doh3|tls|quic)(?:\+local)?://[^\s'"<>`\[\]{}()]+"""
    )
    private val safeEcsValueRegex = Regex("""^[0-9a-fA-F:./]{1,64}$""")
    private val clashCategories = listOf(
        Category.NODE,
        Category.NODE_POLICY,
        Category.DEFAULT,
        Category.POLICY,
        Category.FALLBACK,
        Category.DIRECT,
        Category.BOOTSTRAP,
    )
    private val absent = Profile(emptyList(), emptyList(), false, false)
    private val malformed = Profile(emptyList(), emptyList(), false, false, malformed = true)

    /**
     * Parses Clash/Mihomo YAML, sing-box JSON, or Surge `.conf` profile text strictly for DNS
     * candidates without performing network requests or hostname resolution.
     */
    fun parse(text: String): Profile {
        val cleaned = text.removePrefix("\uFEFF")
        if (surgeSectionRegex.containsMatchIn(cleaned) && !cleaned.trimStart().startsWith('{')) {
            return parseSurgeConfig(cleaned)
        }
        val looksLikeProfile = profileKey.containsMatchIn(cleaned)
        if (!looksLikeProfile && !cleaned.trimStart().startsWith('{')) return absent
        val options = LoaderOptions().apply {
            codePointLimit = 8 * 1024 * 1024
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
            maxAliasesForCollections = MAX_YAML_ALIASES
            nestingDepthLimit = MAX_YAML_NESTING_DEPTH
        }
        val root = try {
            val dumper = DumperOptions()
            Yaml(SafeConstructor(options), Representer(dumper), dumper, options).load<Any?>(cleaned) as? Map<*, *>
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            null
        } ?: return if (looksLikeProfile) malformed else absent

        val isClashProfile = root["proxies"] is List<*> || root["proxy-providers"] is Map<*, *>
        val isSingBoxProfile = root["outbounds"] is List<*> || root["endpoints"] is List<*>
        if (!isClashProfile && !isSingBoxProfile) return absent
        if (!root.containsKey("dns")) return absent
        val dns = root["dns"] as? Map<*, *> ?: return malformed

        if (isSingBoxProfile && !isClashProfile && dns["servers"] is List<*>) {
            return parseSingBoxDns(dns)
        }
        return parseClashDns(dns)
    }

    /**
     * Parses [text] using [parse] first, and if no standard DNS section is found, scans raw lines
     * (including YAML/conf comments and custom provider fields) for valid encrypted DNS URLs.
     * Intended for interactive user inspection inside the Subscription Config / Private DNS UI.
     */
    fun parseOrScanInteractive(text: String): Profile {
        val structured = parse(text)
        if (structured.hasDns && structured.rows.any { it.selectable != null }) {
            return structured
        }
        val scannedRows = scanEmbeddedEncryptedDnsRows(text)
        if (scannedRows.isEmpty()) return structured
        val mergedRows = (structured.rows + scannedRows).take(MAX_ROWS)
        return Profile(
            rows = mergedRows,
            auto = structured.auto,
            hasDns = true,
            truncated = structured.truncated || (structured.rows.size + scannedRows.size > MAX_ROWS),
            malformed = structured.malformed,
        )
    }

    private fun parseClashDns(dns: Map<*, *>): Profile {
        val listener = (dns["listen"] as? String)?.takeIf { dns["enable"] == true }
        val preferH3 = dns["prefer-h3"] == true
        val rows = mutableListOf<Row>()
        var truncated = false

        fun append(category: Category, value: Any?, policy: String? = null) {
            for (entry in (value as? List<*> ?: listOf(value))) {
                if (rows.size == MAX_ROWS) {
                    truncated = true
                    break
                }
                val endpoint = inspect(entry, listener, preferH3)
                rows.add(Row(category, policy, endpoint.label, endpoint.status))
            }
        }
        for (category in clashCategories) {
            if (!dns.containsKey(category.key)) continue
            val value = dns[category.key]
            if (category.policy && value is Map<*, *>) {
                for ((key, endpoints) in value) {
                    if (rows.size == MAX_ROWS) {
                        truncated = true
                        break
                    }
                    val label = (key as? String)?.takeIf { it.isNotBlank() && safeText(it) }
                    if (label == null) append(category, null) else append(category, endpoints, label)
                }
            } else if (!category.policy) {
                append(category, value)
            } else {
                append(category, null)
            }
        }

        fun strictList(value: Any?): List<Endpoint> {
            val raw = value as? List<*> ?: return emptyList()
            if (raw.isEmpty() || raw.size > MAX_ROWS || raw.any { it !is String }) return emptyList()
            return raw.map { inspect(it, listener, preferH3) }
        }
        val node = strictList(dns[Category.NODE.key])
        // WHY: Providers frequently append `system` or `dhcp://auto` after their private DoH URL
        // in `proxy-server-nameserver` as an OS fallback. Ignoring only benign system keywords
        // (never `127.0.0.1` loopback sockets) preserves strict safety while adopting the DoH URL.
        val nonSystemNode = node.filterNot { it.isSystemKeyword }
        val automatic = when {
            dns["enable"] == false || nonSystemNode.isEmpty() -> emptyList()
            nonSystemNode.all { it.status == Status.SELECTABLE && it.unambiguous } ->
                nonSystemNode.mapNotNull { it.label }.distinct()
            nonSystemNode.all { it.status == Status.SELF_REFERENCE } && dns["respect-rules"] != true -> {
                val mirrors = strictList(dns[Category.DEFAULT.key])
                val path = mirrors.firstOrNull()?.path
                if (path != null && path.split('/').any(uuidSegment::matches) && mirrors.all {
                        it.status == Status.SELECTABLE && (it.scheme == "https" || it.scheme == "h3") && it.path == path
                    }
                ) {
                    mirrors.mapNotNull { it.label }.distinct()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
        return Profile(rows, automatic, true, truncated)
    }

    private fun parseSingBoxDns(dns: Map<*, *>): Profile {
        val rawServers = dns["servers"] as? List<*> ?: return malformed
        val rows = mutableListOf<Row>()
        var truncated = false
        for (item in rawServers) {
            if (rows.size == MAX_ROWS) {
                truncated = true
                break
            }
            val serverMap = item as? Map<*, *>
            if (serverMap == null) {
                rows.add(Row(Category.SING_BOX, null, null, Status.INVALID))
                continue
            }
            val detour = (serverMap["detour"] as? String)?.trim().orEmpty()
            val isDirectDetour = detour.isEmpty() ||
                detour.equals("direct", ignoreCase = true) ||
                detour.equals("bypass", ignoreCase = true)

            val legacyAddress = (serverMap["address"] as? String)?.trim()
            if (!legacyAddress.isNullOrEmpty()) {
                if (!isDirectDetour) {
                    rows.add(Row(Category.SING_BOX, null, legacyAddress, Status.UNSAFE))
                } else {
                    val endpoint = inspect(legacyAddress, listener = null, preferH3 = false)
                    rows.add(Row(Category.SING_BOX, null, endpoint.label, endpoint.status))
                }
                continue
            }

            val type = (serverMap["type"] as? String)?.trim()?.lowercase().orEmpty()
            if (type in setOf("local", "dhcp")) {
                rows.add(Row(Category.SING_BOX, null, type, Status.LOCAL))
                continue
            }
            if (type == "fakeip" || type !in portableSchemes) {
                rows.add(Row(Category.SING_BOX, null, type.ifEmpty { null }, Status.UNSUPPORTED))
                continue
            }
            val host = (serverMap["server"] as? String)?.trim().orEmpty()
            if (host.isEmpty()) {
                rows.add(Row(Category.SING_BOX, null, null, Status.INVALID))
                continue
            }
            val port = (serverMap["server_port"] as? Number)?.toInt()
            val path = (serverMap["path"] as? String)?.trim()
            val formattedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val portSegment = if (port != null && port > 0) ":$port" else ""
            val pathSegment = when {
                type !in setOf("https", "h3") -> ""
                path.isNullOrEmpty() -> "/dns-query"
                path.startsWith("/") -> path
                else -> "/$path"
            }
            val candidateUrl = "$type://$formattedHost$portSegment$pathSegment"
            if (!isDirectDetour) {
                rows.add(Row(Category.SING_BOX, null, candidateUrl, Status.UNSAFE))
            } else {
                val endpoint = inspect(candidateUrl, listener = null, preferH3 = false)
                rows.add(Row(Category.SING_BOX, null, endpoint.label, endpoint.status))
            }
        }
        return Profile(rows, emptyList(), true, truncated)
    }

    private fun parseSurgeConfig(cleaned: String): Profile {
        val rows = mutableListOf<Row>()
        var truncated = false
        var sawDnsDirective = false
        var currentSection = ""
        for (rawLine in cleaned.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) {
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            if (currentSection != "general") continue
            val key = line.substringBefore('=', "").trim().lowercase()
            if (key != "doh-server" && key != "dns-server" && key != "encrypted-dns-server") continue
            sawDnsDirective = true
            val rawValues = line.substringAfter('=', "").split(',')
            for (token in rawValues) {
                val candidate = token.trim()
                if (candidate.isEmpty()) continue
                if (rows.size == MAX_ROWS) {
                    truncated = true
                    break
                }
                val endpoint = inspect(candidate, listener = null, preferH3 = false)
                rows.add(Row(Category.SURGE, null, endpoint.label, endpoint.status))
            }
        }
        if (!sawDnsDirective) return absent
        return Profile(rows, emptyList(), true, truncated)
    }

    private fun scanEmbeddedEncryptedDnsRows(text: String): List<Row> {
        val cleaned = text.removePrefix("\uFEFF")
        val seen = linkedSetOf<String>()
        val rows = ArrayList<Row>()
        for (match in embeddedEncryptedDnsRegex.findAll(cleaned)) {
            if (rows.size >= MAX_ROWS) break
            val rawCandidate = match.value.trimEnd(',', ';', ')', ']', '}', '.', '\'', '"')
            val inspected = inspect(rawCandidate, listener = null, preferH3 = false)
            val selectable = inspected.label?.takeIf { inspected.status == Status.SELECTABLE } ?: continue
            if (seen.add(selectable)) {
                rows.add(Row(Category.SCANNED, null, selectable, Status.SELECTABLE))
            }
        }
        return rows
    }

    private class Endpoint(
        val status: Status,
        val label: String? = null,
        val scheme: String? = null,
        val path: String? = null,
        val unambiguous: Boolean = false,
        val isSystemKeyword: Boolean = false,
    )

    private fun safeText(s: String): Boolean = s.length <= MAX_ENDPOINT_LENGTH &&
        s.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    /**
     * Normalizes Mihomo/Sing-box DNS endpoint notation while rejecting proxy-detour `#` tags
     * (such as `#PROXY`), credential userinfo (`@`), and lossy query strings (`?`).
     *
     * Supported direct Mihomo `#` flags:
     * - `#DIRECT`
     * - `#h3=true` / `#h3=false` (`h3=true` promotes `https://` to `h3://`)
     * - `#ecs=<ip-or-cidr>` / `#ecs-override=true|false`
     * - `#skip-cert-verify=false`
     */
    private fun normalizePortableEndpoint(input: String, preferH3: Boolean): String? {
        if (input.any { it == '?' || it == '@' }) return null
        var forceH3 = preferH3
        val withoutFragment = if ('#' in input) {
            val base = input.substringBefore('#').trim()
            val fragment = input.substringAfter('#').trim()
            if (base.isEmpty() || fragment.isEmpty()) return null
            for (rawParam in fragment.split('&')) {
                val param = rawParam.trim()
                if (param.isEmpty()) return null
                val lowerParam = param.lowercase()
                when {
                    lowerParam == "direct" -> Unit
                    lowerParam == "h3=true" -> forceH3 = true
                    lowerParam == "h3=false" -> forceH3 = false
                    lowerParam == "ecs-override=true" || lowerParam == "ecs-override=false" -> Unit
                    lowerParam == "skip-cert-verify=false" -> Unit
                    lowerParam.startsWith("ecs=") -> {
                        val ecsVal = param.substringAfter('=').trim()
                        if (!safeEcsValueRegex.matches(ecsVal)) return null
                    }
                    else -> return null
                }
            }
            base
        } else {
            input
        }

        if (!withoutFragment.contains("://")) return withoutFragment
        val rawScheme = withoutFragment.substringBefore("://").lowercase()
        val rest = withoutFragment.substringAfter("://")
        val normalizedScheme = when (rawScheme) {
            "doh" -> "https"
            "doh3" -> "h3"
            else -> if (rawScheme.endsWith("+local")) rawScheme.removeSuffix("+local") else rawScheme
        }
        val finalScheme = if (normalizedScheme == "https" && forceH3) "h3" else normalizedScheme
        return "$finalScheme://$rest"
    }

    private fun inspect(raw: Any?, listener: String?, preferH3: Boolean = false): Endpoint {
        val input = (raw as? String)?.takeIf(::safeText)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Endpoint(Status.INVALID)
        val lower = input.lowercase()
        if (lower == "system" || lower == "local" || lower.startsWith("dhcp://") ||
            lower == "system://" || lower == "local://"
        ) {
            return Endpoint(Status.LOCAL, input, isSystemKeyword = true)
        }
        val normalized = normalizePortableEndpoint(input, preferH3)
            ?: return Endpoint(Status.UNSAFE, input)
        if (ambiguousPathEscape.containsMatchIn(normalized)) return Endpoint(Status.UNSAFE, input)
        val normalizedLower = normalized.lowercase()
        val explicit = normalized.contains("://")
        if (explicit && normalizedLower.substringBefore("://") !in portableSchemes) {
            return Endpoint(Status.UNSUPPORTED, input)
        }
        val options = try {
            SingBoxDnsServerParser.parseGroupNodeDns(normalized, "group-dns-candidate")
        } catch (_: IllegalArgumentException) {
            return Endpoint(Status.INVALID, input)
        }
        val host = options.server?.lowercase()?.trimEnd('.') ?: return Endpoint(Status.INVALID, input)
        val ip = numericAddress(host)
        if (host == "localhost" || (ip != null && ip.isLoopbackAddress)) {
            val self = options.type in setOf("udp", "tcp") && matchesListener(listener, host, ip, options.server_port ?: 53)
            return Endpoint(if (self) Status.SELF_REFERENCE else Status.LOCAL, normalized)
        }
        if (host.endsWith(".localhost") || host.endsWith(".local") ||
            (ip != null && unsafeAddress(ip))
        ) {
            return Endpoint(Status.LOCAL, normalized)
        }
        if (host.contains('%') || (ip == null && ('.' !in host || ambiguousNumericHost.matches(host)))) {
            return Endpoint(Status.UNSAFE, input)
        }
        // An empty URI path means '/' in Mihomo, but '/dns-query' in sing-box.
        // Don't silently change the request path; require a path in the candidate.
        if (options.type in setOf("https", "h3") && options.path == null) {
            return Endpoint(Status.UNSUPPORTED, input)
        }
        return Endpoint(Status.SELECTABLE, normalized, options.type, options.path, explicit || ip != null)
    }

    private fun numericAddress(host: String): InetAddress? =
        if (InetAddresses.isInetAddress(host)) InetAddresses.forString(host) else null

    private fun unsafeAddress(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return true
        val bytes = ip.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return if (ip is Inet4Address) {
            first == 0 || first >= 240 || (first == 100 && second in 64..127) ||
                (first == 198 && second in 18..19)
        } else {
            first in 0xfc..0xfd || InetAddresses.isCompatIPv4Address(ip as java.net.Inet6Address)
        }
    }

    private fun matchesListener(raw: String?, host: String, ip: InetAddress?, port: Int): Boolean {
        val addr = raw?.trim()?.let(listenerAddress::matchEntire) ?: return false
        if (addr.groupValues[2].toIntOrNull() != port) return false
        val bind = addr.groupValues[1].removeSurrounding("[", "]").lowercase().trimEnd('.')
        if (bind.isEmpty() || bind == host) return true
        val bindIp = numericAddress(bind) ?: return false
        if (ip == null) return false
        // A family-specific wildcard covers only the corresponding loopback addresses.
        return bindIp == ip || (bindIp.isAnyLocalAddress && (bindIp is Inet4Address) == (ip is Inet4Address))
    }
}
