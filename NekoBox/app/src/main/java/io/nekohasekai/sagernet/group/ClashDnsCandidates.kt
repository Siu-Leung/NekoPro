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

/** Parses only subscription DNS data. It never resolves a name or makes a network request. */
internal object ClashDnsCandidates {
    enum class Category(val key: String, val policy: Boolean = false) {
        NODE("proxy-server-nameserver"), NODE_POLICY("proxy-server-nameserver-policy", true),
        DEFAULT("nameserver"), POLICY("nameserver-policy", true), FALLBACK("fallback"),
        DIRECT("direct-nameserver"), BOOTSTRAP("default-nameserver"),
    }

    enum class Status { SELECTABLE, SELF_REFERENCE, LOCAL, UNSUPPORTED, UNSAFE, INVALID }

    // Regular classes intentionally do not put a potentially credential-bearing URL in toString().
    class Row(val category: Category, val policy: String?, val label: String?, val status: Status) {
        val selectable: String? get() = label.takeIf { status == Status.SELECTABLE }
    }

    class Profile(
        val rows: List<Row>, val auto: List<String>, val hasDns: Boolean, val truncated: Boolean,
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
    private val ambiguousNumericHost = Regex("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")
    private val ambiguousPathEscape = Regex("(?i)%(?:00|0[ad]|2f|5c)")
    private val listenerAddress = Regex("(\\[[^\\[\\]]+]|[^:]*):([0-9]{1,5})")
    private val profileKey = Regex("""(?m)^[\t ]*(?:\{[\t ]*)?(?:proxies|dns|"proxies"|"dns"|'proxies'|'dns')[\t ]*:""")
    private val absent = Profile(emptyList(), emptyList(), false, false)
    private val malformed = Profile(emptyList(), emptyList(), false, false, malformed = true)

    /**
     * Parses Clash/Mihomo YAML or JSON profile text strictly for DNS candidates without
     * performing network requests or hostname resolution.
     *
     * Tightens SnakeYAML alias and nesting limits to mitigate collection-key alias DAG
     * CPU exhaustion (H-06) and strips any leading UTF-8 BOM before detection (M-05).
     */
    fun parse(text: String): Profile {
        val cleaned = text.removePrefix("\uFEFF")
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
        if (root["proxies"] !is List<*> && root["proxy-providers"] !is Map<*, *>) return absent
        if (!root.containsKey("dns")) return absent
        val dns = root["dns"] as? Map<*, *> ?: return malformed
        val listener = (dns["listen"] as? String)?.takeIf { dns["enable"] == true }
        val rows = mutableListOf<Row>()
        var truncated = false

        fun append(category: Category, value: Any?, policy: String? = null) {
            for (entry in (value as? List<*> ?: listOf(value))) {
                if (rows.size == MAX_ROWS) {
                    truncated = true
                    break
                }
                val endpoint = inspect(entry, listener)
                rows.add(Row(category, policy, endpoint.label, endpoint.status))
            }
        }
        for (category in Category.entries) {
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
            return raw.map { inspect(it, listener) }
        }
        val node = strictList(dns[Category.NODE.key])
        val automatic = when {
            dns["enable"] == false || node.isEmpty() -> emptyList()
            node.all { it.status == Status.SELECTABLE && it.unambiguous } -> node.mapNotNull { it.label }.distinct()
            node.all { it.status == Status.SELF_REFERENCE } && dns["respect-rules"] != true -> {
                // This is a deliberately narrow compatibility heuristic, not Mihomo's DNS routing.
                // An exact listener reference + only DoH mirrors of the same private UUID path.
                // DNS hostnames and ports (including implicit 443) may differ across mirrors.
                val mirrors = strictList(dns[Category.DEFAULT.key])
                val path = mirrors.firstOrNull()?.path
                if (path != null && path.split('/').any(uuidSegment::matches) && mirrors.all {
                        it.status == Status.SELECTABLE && (it.scheme == "https" || it.scheme == "h3") && it.path == path
                    }) mirrors.mapNotNull { it.label }.distinct() else emptyList()
            }
            else -> emptyList()
        }
        return Profile(rows, automatic, true, truncated)
    }

    private class Endpoint(
        val status: Status, val label: String? = null, val scheme: String? = null,
        val path: String? = null, val unambiguous: Boolean = false,
    )

    private fun safeText(s: String): Boolean = s.length <= MAX_ENDPOINT_LENGTH &&
        s.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    private fun inspect(raw: Any?, listener: String?): Endpoint {
        val input = (raw as? String)?.takeIf(::safeText)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Endpoint(Status.INVALID)
        val lower = input.lowercase()
        if (lower == "system" || lower == "local" || lower.startsWith("dhcp://") ||
            lower == "system://" || lower == "local://") return Endpoint(Status.LOCAL, input)
        if (input.any { it == '?' || it == '#' || it == '@' }) return Endpoint(Status.UNSAFE, input)
        if (ambiguousPathEscape.containsMatchIn(input)) return Endpoint(Status.UNSAFE, input)
        val explicit = input.contains("://")
        if (explicit && lower.substringBefore("://") !in portableSchemes) return Endpoint(Status.UNSUPPORTED, input)
        val options = try {
            SingBoxDnsServerParser.parseGroupNodeDns(input, "group-dns-candidate")
        } catch (_: IllegalArgumentException) {
            return Endpoint(Status.INVALID, input)
        }
        val host = options.server?.lowercase()?.trimEnd('.') ?: return Endpoint(Status.INVALID, input)
        val ip = numericAddress(host)
        if (host == "localhost" || (ip != null && ip.isLoopbackAddress)) {
            val self = options.type in setOf("udp", "tcp") && matchesListener(listener, host, ip, options.server_port ?: 53)
            return Endpoint(if (self) Status.SELF_REFERENCE else Status.LOCAL, input)
        }
        if (host.endsWith(".localhost") || host.endsWith(".local") ||
            (ip != null && unsafeAddress(ip))) return Endpoint(Status.LOCAL, input)
        if (host.contains('%') || (ip == null && ('.' !in host || ambiguousNumericHost.matches(host)))) {
            return Endpoint(Status.UNSAFE, input)
        }
        // An empty URI path means '/' in Mihomo, but '/dns-query' in sing-box.
        // Don't silently change the request path; require a path in the candidate.
        if (options.type in setOf("https", "h3") && options.path == null) return Endpoint(Status.UNSUPPORTED, input)
        return Endpoint(Status.SELECTABLE, input, options.type, options.path, explicit || ip != null)
    }

    private fun numericAddress(host: String): InetAddress? =
        if (InetAddresses.isInetAddress(host)) InetAddresses.forString(host) else null

    private fun unsafeAddress(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return true
        val bytes = ip.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return if (ip is Inet4Address) first == 0 || first >= 240 || first == 100 && second in 64..127 ||
            first == 198 && second in 18..19
        else first in 0xfc..0xfd || InetAddresses.isCompatIPv4Address(ip as java.net.Inet6Address)
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
