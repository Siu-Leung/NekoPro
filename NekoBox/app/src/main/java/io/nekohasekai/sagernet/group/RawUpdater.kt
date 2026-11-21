package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    private const val CLASH_META_USER_AGENT = "clash-meta"
    private const val MAX_SUBSCRIPTION_BYTES = 8L * 1024 * 1024
    private const val MAX_YAML_ALIASES = 8
    private const val MAX_YAML_NESTING_DEPTH = 20
    private val KNOWN_UNSUPPORTED_CLASH_TYPES = setOf(
        "ssr", "ssh", "shadowtls", "naive", "direct", "reject", "reject-drop", "pass", "dns",
    )
    private val HTML_DOCUMENT_PREFIX = Regex("""(?is)^\s*(?:<!doctype\s+html|<html\b|<head\b|<body\b|<script\b)""")

    /**
     * Default DNS-inspection clients, most reliable first. FlClash and Clash Verge are
     * commonly served the complete profile, while plain `clash-meta` is often answered
     * with a nodes-only payload.
     */
    internal val DNS_CANDIDATE_USER_AGENTS = ClashDnsCandidates.CLIENT_USER_AGENTS

    /** Reads a subscription HTTP response body capped at [MAX_SUBSCRIPTION_BYTES] (8 MiB). */
    private fun readBoundedResponseText(response: libcore.HTTPResponse): String =
        response.getContentWithLimit(MAX_SUBSCRIPTION_BYTES).decodeToString()

    /** Detects HTML error/maintenance/challenge pages that must not be parsed as subscriptions. */
    private fun isLikelyHtmlDocument(text: String): Boolean =
        HTML_DOCUMENT_PREFIX.containsMatchIn(text.take(512))

    @SuppressLint("Recycle")
    private fun readSubscriptionFile(link: String): String =
        app.contentResolver.openInputStream(link.toUri())?.bufferedReader()?.use { reader ->
            val maxChars = 16 * 1024 * 1024
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var count: Int
            while (reader.read(buffer).also { count = it } != -1) {
                require(text.length + count <= maxChars) { "Subscription file exceeds 16 MB limit" }
                text.append(buffer, 0, count)
            }
            text.toString()
        } ?: error(app.getString(R.string.no_proxies_found_in_subscription))

    private inline fun <T> withSubscriptionResponse(
        link: String, userAgent: String, forceProxy: Boolean, block: (libcore.HTTPResponse) -> T,
    ): T {
        val client = Libcore.newHttpClient()
        try {
            if (forceProxy) {
                client.forceSocks5(DataStore.mixedPort)
            } else {
                // Only attempt local mixedPort if NekoPro's service actually owns the port.
                if (DataStore.serviceState.started) {
                    client.trySocks5(DataStore.mixedPort)
                }
                client.tryH3Direct()
            }
            if (DataStore.appTLSVersion == "1.3") client.restrictedTLS()
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) allowInsecure()
                setURL(link)
                setUserAgent(userAgent)
            }.execute()
            return block(response)
        } finally {
            client.close()
        }
    }

    /** Read-only, on demand: same source/transport as refresh, without committing a node snapshot. */
    internal suspend fun inspectSubscriptionDns(link: String): ClashDnsCandidates.Profile =
        inspectSubscriptionDns(link, DNS_CANDIDATE_USER_AGENTS)

    /**
     * Read the DNS section using a caller-supplied UA order.
     *
     * Providers gate full profiles behind specific clients: `clash-meta` may return a
     * nodes-only body while FlClash/Clash Verge receive the complete YAML. Try each UA in
     * turn and keep the first response that actually carries a DNS section; a nodes-only
     * answer from one client must not hide a complete one from the next.
     *
     * @param link Subscription URL (`http://`, `https://`, or `content://`).
     * @param userAgents Ordered client identifiers; the first usable answer wins.
     * @param forceProxy Locked proxy requirement across all UA attempts so mid-loop VPN drops fail closed.
     * @param customUserAgent Optional group custom UA tried first when non-blank.
     * @throws Exception if every HTTP attempt failed with a network/TLS/HTTP error.
     */
    internal suspend fun inspectSubscriptionDns(
        link: String,
        userAgents: List<String>,
        forceProxy: Boolean = DataStore.serviceState.connected,
        customUserAgent: String? = null,
    ): ClashDnsCandidates.Profile {
        currentCoroutineContext().ensureActive()
        if (link.startsWith("content://")) {
            return ClashDnsCandidates.parse(readSubscriptionFile(link))
        }
        require(link.startsWith("http://", true) || link.startsWith("https://", true))
        val effectiveUserAgents = buildList {
            customUserAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(userAgents.ifEmpty { DNS_CANDIDATE_USER_AGENTS })
        }.distinct()
        var lastProfile: ClashDnsCandidates.Profile? = null
        var lastError: Exception? = null
        for (userAgent in effectiveUserAgents) {
            currentCoroutineContext().ensureActive()
            val profile = try {
                withSubscriptionResponse(link, userAgent, forceProxy) { response ->
                    ClashDnsCandidates.parse(readBoundedResponseText(response))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                continue
            }
            if (profile.hasDns && profile.rows.any { it.selectable != null }) return profile
            if (lastProfile == null || (profile.hasDns && !lastProfile.hasDns)) lastProfile = profile
        }
        if (lastProfile == null && lastError != null) {
            throw lastError
        }
        return lastProfile ?: ClashDnsCandidates.Profile(
            emptyList(), emptyList(), false, false,
        )
    }

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link.orEmpty()
        // An explicit group DNS is sufficient; a nodes-only subscription can still refresh.
        val requireAdoptedDns = proxyGroup.requiresNodeDnsAdoption()
        val savedManualDns = proxyGroup.manualNodeDns()
        var adoptedNodeDnsCandidate: List<String> = emptyList()
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = try {
                readSubscriptionFile(link)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error(app.getString(R.string.group_dns_candidates_failed))
            }
            proxies = try {
                parseSubscription(contentText).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error(app.getString(R.string.group_dns_candidates_refresh_failed))
            }
            if (proxies.isEmpty()) error(app.getString(R.string.no_proxies_found_in_subscription))
            if (requireAdoptedDns) {
                adoptedNodeDnsCandidate = extractClashProxyServerNameservers(contentText)
                if (adoptedNodeDnsCandidate.isEmpty()) error(app.getString(R.string.group_dns_candidates_auto_missing))
            }
        } else {
            // Align Clash UA fallback order with ClashDnsCandidates (FlClash -> Clash Verge -> clash-meta)
            // so providers that gate the DNS section or AnyTLS certificate pins behind modern Clash clients succeed.
            val candidateUserAgents = buildList {
                subscription.customUserAgent?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (requireAdoptedDns || savedManualDns != null) {
                    addAll(DNS_CANDIDATE_USER_AGENTS)
                }
                if (!requireAdoptedDns) {
                    add(USER_AGENT)
                    addAll(DNS_CANDIDATE_USER_AGENTS)
                    add("v2rayN/7.8.2")
                    add("sing-box/1.14.0")
                }
            }.distinct()

            var validNodesWithoutDns = false
            var lastUserinfo = ""
            var lastDisposition = ""
            proxies = emptyList()

            val forceProxy = DataStore.serviceState.connected ||
                (!byUser && subscription.updateWhenConnectedOnly == true)

            for (candidate in candidateUserAgents) {
                try {
                    val accepted = withSubscriptionResponse(link, candidate, forceProxy) { response ->
                        val responseText = readBoundedResponseText(response)
                        val parsed = parseSubscription(responseText).orEmpty()
                        if (parsed.isEmpty()) return@withSubscriptionResponse false

                        val candidateDns = if (requireAdoptedDns) extractClashProxyServerNameservers(responseText)
                        else emptyList()
                        if (requireAdoptedDns && candidateDns.isEmpty()) {
                            validNodesWithoutDns = true
                            return@withSubscriptionResponse false
                        }

                        lastUserinfo = Util.getStringBox(response.getHeader("Subscription-Userinfo"))
                        if (proxyGroup.name?.startsWith("Subscription #") == true) {
                            lastDisposition = Util.getStringBox(response.getHeader("content-disposition"))
                        }
                        proxies = parsed
                        adoptedNodeDnsCandidate = candidateDns
                        true
                    }
                    if (accepted) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Network, SubscriptionFoundException, and parse exceptions must not leak the URL
                    // or abort remaining candidate User-Agents.
                    Logs.d("Subscription response could not be downloaded or parsed")
                }
            }

            if (proxies.isEmpty()) {
                if (validNodesWithoutDns) error(app.getString(R.string.group_dns_candidates_auto_missing))
                error(app.getString(R.string.group_dns_candidates_refresh_failed))
            }

            subscription.subscriptionUserinfo = lastUserinfo

            // 修改默认名字
            if (proxyGroup.name?.startsWith("Subscription #") == true && lastDisposition.isNotBlank()) {
                val remoteName = Util.decodeFilename(lastDisposition)
                if (remoteName.isNotBlank()) {
                    proxyGroup.name = remoteName
                }
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        // A group resolver must see the original node hostname, not a public DNS result.
        if (subscription.forceResolve == true && savedManualDns == null && adoptedNodeDnsCandidate.isEmpty()) {
            forceResolve(proxies, proxyGroup.id, proxyGroup.effectiveNodeDnsList())
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication == true) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = HashMap<String, ProxyEntity>()
        for (entity in exists) {
            val name = entity.displayName()
            if (nameMap.contains(name) && !toReplace.containsKey(name)) {
                toReplace[name] = entity
            } else {
                toDelete.add(entity)
            }
        }

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                when {
                    existsBean != bean -> {
                        changed++
                        entity.userOrder = userOrder
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    entity.userOrder != userOrder -> {
                        entity.userOrder = userOrder
                        entity.putBean(bean)
                        toUpdate.add(entity)

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                toAdd.add(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = userOrder
                    ).apply {
                        putBean(bean)
                    })
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        SagerDatabase.instance.withTransaction {
            if (toAdd.isNotEmpty()) {
                SagerDatabase.proxyDao.insert(toAdd)
                Logs.d("Inserted profiles: ${toAdd.size}")
            }

            SagerDatabase.proxyDao.updateProxy(toUpdate).also {
                Logs.d("Updated profiles: $it")
            }

            SagerDatabase.proxyDao.deleteProxy(toDelete).also {
                Logs.d("Deleted profiles: $it")
            }

            val currentSelected = DataStore.selectedProxy
            if (currentSelected != 0L && toDelete.any { it.id == currentSelected }) {
                DataStore.selectedProxy = 0L
            }

            val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

            if (existCount != proxies.size) {
                Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
            }

            // The source DNS and node list are one snapshot; a failed refresh changes neither.
            if (adoptedNodeDnsCandidate.isNotEmpty()) subscription.adoptedNodeDns = adoptedNodeDnsCandidate.joinToString("\n")
            subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            SagerDatabase.groupDao.updateGroup(proxyGroup)
        }

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? =
        parseRaw(text, fileName, forSubscription = false)

    /** Only the explicit node resolver or a proven listener mirror set is eligible. */
    internal fun extractClashProxyServerNameservers(text: String): List<String> = ClashDnsCandidates.parse(text).auto

    internal suspend fun parseSubscription(text: String): List<AbstractBean>? {
        currentCoroutineContext().ensureActive()
        return parseRaw(text, "", forSubscription = true).also {
            currentCoroutineContext().ensureActive()
        }
    }

    // Recognize YAML-looking declarations without treating URI substrings as malformed YAML.
    private val clashProxiesKey = Regex("""(?m)^[\t ]*(?:\{[\t ]*)?(?:proxies|"proxies"|'proxies')[\t ]*:""")

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseRaw(rawText: String, fileName: String, forSubscription: Boolean): List<AbstractBean>? {
        val text = rawText.removePrefix("\uFEFF")
        val proxies = mutableListOf<AbstractBean>()
        val hasClashDeclaration = forSubscription && clashProxiesKey.containsMatchIn(text)

        if (hasClashDeclaration || (!forSubscription && text.contains("proxies:"))) {

            // clash & meta

            try {

                val loaderOptions = LoaderOptions().apply {
                    codePointLimit = 8 * 1024 * 1024 // 8 MiB YAML bomb guard
                    setAllowRecursiveKeys(false)
                    maxAliasesForCollections = MAX_YAML_ALIASES
                    nestingDepthLimit = MAX_YAML_NESTING_DEPTH
                }
                val yaml = try {
                    Yaml(SafeConstructor(loaderOptions)).load<Any?>(text) as? Map<*, *>
                } catch (_: StackOverflowError) {
                    throw YAMLException("Clash YAML exceeded safe nesting depth")
                } ?: throw YAMLException("Clash YAML root must be a mapping")

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                val rawProxies = (yaml["proxies"] as? List<*>) ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                )

                fun Map<*, *>.optStr(key: String): String? = this[key]?.toString()
                fun Map<*, *>.optInt(key: String): Int? = this[key]?.toString()?.trim()?.toIntOrNull()
                fun Map<*, *>.optBool(key: String): Boolean = when (val v = this[key]) {
                    is Boolean -> v
                    is Number -> v.toInt() != 0
                    else -> v?.toString()?.let { it.equals("true", ignoreCase = true) || it == "1" } == true
                }

                var skippedKnownUnsupported = 0
                for (rawEntry in rawProxies) {
                    val proxy = rawEntry as? Map<*, *> ?: continue
                    runCatching {
                        // Note: YAML numbers parsed as "Long"
                        val proxyType = proxy.optStr("type")?.lowercase()
                        when (proxyType) {
                            "socks5" -> {
                                val server = proxy.optStr("server") ?: return@runCatching
                                val port = proxy.optInt("port") ?: return@runCatching
                                proxies.add(SOCKSBean().apply {
                                    serverAddress = server
                                    serverPort = port
                                    username = proxy.optStr("username")
                                    password = proxy.optStr("password")
                                    name = proxy.optStr("name")
                                })
                            }

                            "http" -> {
                                val server = proxy.optStr("server") ?: return@runCatching
                                val port = proxy.optInt("port") ?: return@runCatching
                                proxies.add(HttpBean().apply {
                                    serverAddress = server
                                    serverPort = port
                                    username = proxy.optStr("username")
                                    password = proxy.optStr("password")
                                    setTLS(proxy.optBool("tls"))
                                    sni = proxy.optStr("sni") ?: proxy.optStr("servername")
                                    name = proxy.optStr("name")
                                    allowInsecure = proxy.optBool("skip-cert-verify")
                                })
                            }

                            "ss" -> {
                                val server = proxy.optStr("server") ?: return@runCatching
                                val port = proxy.optInt("port") ?: return@runCatching
                                val cipher = proxy.optStr("cipher") ?: return@runCatching
                                val ssPlugin = mutableListOf<String>()
                                val pluginName = proxy.optStr("plugin")
                                if (!pluginName.isNullOrBlank()) {
                                    val opts = proxy["plugin-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
                                    when (pluginName) {
                                        "obfs" -> {
                                            ssPlugin.apply {
                                                add("obfs-local")
                                                add("obfs=" + (opts.optStr("mode") ?: ""))
                                                add("obfs-host=" + (opts.optStr("host") ?: ""))
                                            }
                                        }

                                        "v2ray-plugin" -> {
                                            ssPlugin.apply {
                                                add("v2ray-plugin")
                                                add("mode=" + (opts.optStr("mode") ?: ""))
                                                if (opts.optBool("tls")) add("tls")
                                                add("host=" + (opts.optStr("host") ?: ""))
                                                add("path=" + (opts.optStr("path") ?: ""))
                                                if (opts.optBool("mux")) add("mux=8")
                                            }
                                        }
                                    }
                                }
                                proxies.add(ShadowsocksBean().apply {
                                    serverAddress = server
                                    serverPort = port
                                    password = proxy.optStr("password")
                                    method = clashCipher(cipher)
                                    plugin = ssPlugin.joinToString(";")
                                    name = proxy.optStr("name")
                                })
                            }

                            "vmess", "vless", "trojan" -> {
                                val proxyType = proxy.optStr("type")?.lowercase() ?: return@runCatching
                                val bean = when (proxyType) {
                                    "vmess" -> VMessBean()
                                    "vless" -> VMessBean().apply {
                                        alterId = -1 // make it VLESS
                                        packetEncoding = 2 // clash meta default XUDP
                                    }

                                    "trojan" -> TrojanBean().apply {
                                        security = "tls"
                                    }

                                    else -> error("impossible")
                                }

                                bean.serverAddress = proxy.optStr("server") ?: return@runCatching
                                bean.serverPort = proxy.optInt("port") ?: return@runCatching

                                // Pass 1: parse core scalar options before transport/reality sub-maps
                                // so field ordering in YAML cannot overwrite derived settings.
                                for ((rawKey, rawVal) in proxy) {
                                    val key = rawKey?.toString() ?: continue
                                    when (key) {
                                        "name" -> bean.name = rawVal?.toString()
                                        "password" -> if (bean is TrojanBean) bean.password =
                                            rawVal?.toString()

                                        "uuid" -> if (bean is VMessBean) bean.uuid =
                                            rawVal?.toString()

                                        "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                            rawVal?.toString()?.toIntOrNull()

                                        "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                            rawVal?.toString()

                                        "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                            rawVal?.toString()?.let {
                                                if (it.contains("xtls-rprx-vision")) {
                                                    bean.encryption = "xtls-rprx-vision"
                                                }
                                            }
                                        }

                                        "packet-encoding" -> if (bean is VMessBean) {
                                            bean.packetEncoding = when (rawVal?.toString()) {
                                                "packetaddr" -> 1
                                                "xudp" -> 2
                                                else -> 0
                                            }
                                        }

                                        "tls" -> if (bean is VMessBean) {
                                            bean.security =
                                                if (proxy.optBool("tls")) "tls" else ""
                                        }

                                        "servername", "sni" -> bean.sni = rawVal?.toString()

                                        "alpn" -> bean.alpn = when (rawVal) {
                                            is List<*> -> rawVal.mapNotNull { it?.toString() }.joinToString("\n")
                                            is String -> rawVal
                                            else -> bean.alpn
                                        }

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            proxy.optBool("skip-cert-verify")

                                        "client-fingerprint" -> rawVal?.toString()?.let {
                                            bean.utlsFingerprint = it
                                        }

                                        "network" -> {
                                            when (rawVal?.toString()?.lowercase()) {
                                                "h2", "http" -> bean.type = "http"
                                                "ws", "grpc" -> bean.type = rawVal.toString().lowercase()
                                                "xhttp", "splithttp" -> bean.type = "xhttp"
                                            }
                                        }

                                        "smux" -> (rawVal as? Map<*, *>)?.also { smux ->
                                            for ((sk, sv) in smux) {
                                                when (sk?.toString()) {
                                                    "enabled" -> bean.enableMux =
                                                        sv?.toString().equals("true", ignoreCase = true)

                                                    "max-streams" -> sv?.toString()?.toIntOrNull()?.let {
                                                        bean.muxConcurrency = it
                                                    }

                                                    "padding" -> bean.muxPadding =
                                                        sv?.toString().equals("true", ignoreCase = true)
                                                }
                                            }
                                        }

                                        "ech-opts" -> (rawVal as? Map<*, *>)?.also { ech ->
                                            for ((ek, ev) in ech) {
                                                when (ek?.toString()) {
                                                    "enable" -> bean.enableECH =
                                                        ev?.toString().equals("true", ignoreCase = true)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Pass 2: parse transport & reality sub-maps after core scalar fields
                                (proxy["reality-opts"] as? Map<*, *>)?.also { reality ->
                                    for ((rk, rv) in reality) {
                                        bean.security = "tls"
                                        when (rk?.toString()) {
                                            "public-key" -> bean.realityPubKey = rv?.toString()
                                            "short-id" -> bean.realityShortId = rv?.toString()
                                        }
                                    }
                                }

                                val xhttpOpts = (proxy["xhttp-opts"] ?: proxy["splithttp-opts"]) as? Map<*, *>
                                xhttpOpts?.also { opts ->
                                    bean.type = "xhttp"
                                    for ((xk, xv) in opts) {
                                        when (xk?.toString()?.lowercase()) {
                                            "path" -> bean.path = xv?.toString()
                                            "host" -> bean.host = xv?.toString()
                                            "mode" -> bean.xhttpMode = xv?.toString()
                                            "extra" -> {
                                                bean.xhttpExtra = if (xv is Map<*, *>) org.json.JSONObject(xv).toString() else xv?.toString()
                                            }
                                            "headers" -> (xv as? Map<*, *>)?.forEach { (k, v) ->
                                                if (k?.toString().equals("host", ignoreCase = true) && bean.host.isNullOrBlank()) {
                                                    bean.host = v?.toString()
                                                }
                                            }
                                        }
                                    }
                                }

                                (proxy["ws-opts"] as? Map<*, *>)?.also { ws ->
                                    for ((wk, wv) in ws) {
                                        when (wk?.toString()) {
                                            "headers" -> (wv as? Map<*, *>)?.forEach { (key, value) ->
                                                if (key?.toString()?.lowercase() == "host") {
                                                    bean.host = value?.toString()
                                                }
                                            }

                                            "path" -> bean.path = wv?.toString()

                                            "max-early-data" -> {
                                                bean.wsMaxEarlyData = wv?.toString()?.toIntOrNull()
                                            }

                                            "early-data-header-name" -> {
                                                bean.earlyDataHeaderName = wv?.toString()
                                            }

                                            "v2ray-http-upgrade" -> {
                                                if (wv as? Boolean == true || wv?.toString().equals("true", ignoreCase = true)) {
                                                    bean.type = "httpupgrade"
                                                }
                                            }
                                        }
                                    }
                                }

                                (proxy["h2-opts"] as? Map<*, *>)?.also { h2 ->
                                    for ((hk, hv) in h2) {
                                        when (hk?.toString()) {
                                            "host" -> bean.host = (hv as? List<*>)?.mapNotNull { it?.toString() }?.joinToString("\n")
                                                ?: hv?.toString()

                                            "path" -> bean.path = hv?.toString()
                                        }
                                    }
                                }

                                (proxy["http-opts"] as? Map<*, *>)?.also { http ->
                                    for ((hk, hv) in http) {
                                        when (hk?.toString()) {
                                            "path" -> bean.path = (hv as? List<*>)?.mapNotNull { it?.toString() }?.joinToString("\n")
                                                ?: hv?.toString()

                                            "headers" -> {
                                                (hv as? Map<*, *>)?.forEach { (key, value) ->
                                                    if (key?.toString()?.lowercase() == "host") {
                                                        bean.host = when (value) {
                                                            is List<*> -> value.mapNotNull { it?.toString() }.joinToString("\n")
                                                            else -> value?.toString()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                (proxy["grpc-opts"] as? Map<*, *>)?.also { grpc ->
                                    for ((gk, gv) in grpc) {
                                        if (gk?.toString() == "grpc-service-name") {
                                            bean.path = gv?.toString()
                                        }
                                    }
                                }

                                proxies.add(bean)
                            }

                            "snell" -> {
                                val bean = moe.matsuri.nb4a.proxy.snell.SnellBean()
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString().trim().removeSurrounding("[", "]")
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: 443
                                        "psk" -> bean.psk = rawVal.toString()
                                        "userkey", "user-key" -> bean.userkey = rawVal.toString()
                                        "version" -> bean.version = rawVal.toString().toIntOrNull() ?: 4
                                        "mode" -> bean.mode = rawVal.toString().trim().lowercase()
                                        "obfs-opts" -> {
                                            (rawVal as? Map<*, *>)?.let { obfs ->
                                                bean.obfsMode = obfs["mode"]?.toString()?.trim()?.lowercase() ?: "none"
                                                bean.obfsHost = obfs["host"]?.toString()?.trim() ?: ""
                                            }
                                        }
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                if (bean.version != null && bean.version !in 4..6) {
                                    // Snell v1-v3 are intentionally unsupported; count as skipped rather than malformed.
                                    skippedKnownUnsupported++
                                    return@runCatching
                                }
                                proxies.add(bean)
                            }

                            "mieru" -> {
                                val bean = io.nekohasekai.sagernet.fmt.mieru.MieruBean()
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString().trim().removeSurrounding("[", "]")
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: 0
                                        "port-range" -> bean.portRange = rawVal.toString().trim().replace(':', '-')
                                        "transport" -> bean.protocol = rawVal.toString().trim().uppercase()
                                        "username" -> bean.username = rawVal.toString()
                                        "password" -> bean.password = rawVal.toString()
                                        "mtu" -> bean.mtu = rawVal.toString().toIntOrNull() ?: 1400
                                        "multiplexing" -> bean.multiplexing = rawVal.toString().trim().uppercase()
                                        "handshake-mode" -> bean.handshakeMode = rawVal.toString().trim().uppercase()
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                if ((bean.serverPort ?: 0) <= 0 && bean.portRange.isNullOrBlank()) return@runCatching
                                proxies.add(bean)
                            }

                            "juicity" -> {
                                val bean = io.nekohasekai.sagernet.fmt.juicity.JuicityBean()
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString().trim().removeSurrounding("[", "]")
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: 443
                                        "uuid" -> bean.uuid = rawVal.toString()
                                        "password" -> bean.password = rawVal.toString()
                                        "sni", "servername" -> bean.sni = rawVal.toString()
                                        "skip-cert-verify" -> bean.allowInsecure = rawVal.toString().equals("true", ignoreCase = true)
                                        "congestion-control", "congestion-controller" -> bean.congestionControl = rawVal.toString()
                                        "pinned-certchain-sha256" -> bean.pinnedCertchainSha256 = rawVal.toString()
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                proxies.add(bean)
                            }

                            "masque" -> {
                                val bean = io.nekohasekai.sagernet.fmt.masque.MASQUEBean()
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString().trim().removeSurrounding("[", "]")
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: 443
                                        "private-key" -> bean.privateKey = rawVal.toString()
                                        "public-key", "endpoint-public-key" -> bean.publicKey = rawVal.toString()
                                        "ip", "local-address" -> bean.localAddress = rawVal.toString()
                                        "ipv6", "local-address-v6" -> bean.localAddressV6 = rawVal.toString()
                                        "uri" -> bean.uri = rawVal.toString()
                                        "sni", "servername" -> bean.sni = rawVal.toString()
                                        "mtu" -> bean.mtu = rawVal.toString().toIntOrNull() ?: 1280
                                        "udp" -> bean.udp = rawVal.toString().equals("true", ignoreCase = true)
                                        "network" -> bean.network = rawVal.toString()
                                        "congestion-controller", "congestion-control" -> bean.congestionController = rawVal.toString()
                                        "skip-cert-verify" -> bean.allowInsecure = rawVal.toString().equals("true", ignoreCase = true)
                                        "remote-dns-resolve" -> bean.remoteDnsResolve = rawVal.toString().equals("true", ignoreCase = true)
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                proxies.add(bean)
                            }

                            "anytls" -> {
                                val bean = AnyTLSBean()
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString()
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: return@runCatching
                                        "password" -> bean.password = rawVal.toString()
                                        "client-fingerprint" -> bean.utlsFingerprint = rawVal.toString()
                                        "fingerprint" -> bean.certificateSha256 =
                                            moe.matsuri.nb4a.proxy.anytls.parseAnyTLSClashCertificatePin(rawVal)

                                        "sni", "server-name" -> bean.sni = rawVal.toString()
                                        "skip-cert-verify", "insecure", "allow-insecure" -> bean.allowInsecure =
                                            rawVal.toString().equals("true", ignoreCase = true) || rawVal.toString() == "1"

                                        "alpn" -> {
                                            val alpn = when (rawVal) {
                                                is List<*> -> rawVal.mapNotNull { it?.toString() }
                                                is String -> listOf(rawVal)
                                                else -> null
                                            }
                                            bean.alpn = alpn?.joinToString("\n")
                                        }
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                if (bean.utlsFingerprint.isNullOrBlank() && globalClientFingerprint.isNotBlank()) {
                                    bean.utlsFingerprint = globalClientFingerprint
                                }
                                proxies.add(bean)
                            }

                            "hysteria" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 1
                                var hopPorts = ""
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString()
                                        "port" -> bean.serverPorts = rawVal.toString()
                                        "ports" -> hopPorts = rawVal.toString()

                                        "obfs" -> bean.obfuscation = rawVal.toString()

                                        "auth-str" -> {
                                            bean.authPayloadType = HysteriaBean.TYPE_STRING
                                            bean.authPayload = rawVal.toString()
                                        }

                                        "sni" -> bean.sni = rawVal.toString()

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            rawVal.toString().equals("true", ignoreCase = true)

                                        "up" -> bean.uploadMbps =
                                            rawVal.toString().substringBefore(" ").toIntOrNull()
                                                ?: 100

                                        "down" -> bean.downloadMbps =
                                            rawVal.toString().substringBefore(" ").toIntOrNull()
                                                ?: 100

                                        "recv-window-conn" -> bean.streamReceiveWindow =
                                            rawVal.toString().toIntOrNull() ?: 0

                                        "recv-window" -> bean.connectionReceiveWindow =
                                            rawVal.toString().toIntOrNull() ?: 0

                                        "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                            rawVal.toString().equals("true", ignoreCase = true) || rawVal.toString() == "1"

                                        "alpn" -> {
                                            val alpn = when (rawVal) {
                                                is List<*> -> rawVal.mapNotNull { it?.toString() }
                                                is String -> listOf(rawVal)
                                                else -> null
                                            }
                                            bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                        }
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "hysteria2" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 2
                                var hopPorts = ""
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString()
                                        "port" -> bean.serverPorts = rawVal.toString()
                                        "ports" -> hopPorts = rawVal.toString()

                                        "obfs-password" -> bean.obfuscation = rawVal.toString()

                                        "password" -> bean.authPayload = rawVal.toString()

                                        "sni" -> bean.sni = rawVal.toString()

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            rawVal.toString().equals("true", ignoreCase = true)

                                        "up" -> bean.uploadMbps =
                                            rawVal.toString().substringBefore(" ").toIntOrNull() ?: 0

                                        "down" -> bean.downloadMbps =
                                            rawVal.toString().substringBefore(" ").toIntOrNull() ?: 0
                                    }
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "tuic" -> {
                                val bean = TuicBean()
                                var ip = ""
                                for ((rawKey, rawVal) in proxy) {
                                    if (rawVal == null) continue
                                    when (rawKey?.toString()?.replace("_", "-")) {
                                        "name" -> bean.name = rawVal.toString()
                                        "server" -> bean.serverAddress = rawVal.toString()
                                        "ip" -> ip = rawVal.toString()
                                        "port" -> bean.serverPort = rawVal.toString().toIntOrNull() ?: return@runCatching

                                        "token" -> {
                                            bean.protocolVersion = 4
                                            bean.token = rawVal.toString()
                                        }

                                        "uuid" -> bean.uuid = rawVal.toString()

                                        "password" -> bean.token = rawVal.toString()

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            rawVal.toString().equals("true", ignoreCase = true)

                                        "disable-sni" -> bean.disableSNI =
                                            rawVal.toString().equals("true", ignoreCase = true)

                                        "reduce-rtt" -> bean.reduceRTT =
                                            rawVal.toString().equals("true", ignoreCase = true)

                                        "sni" -> bean.sni = rawVal.toString()

                                        "alpn" -> {
                                            val alpn = when (rawVal) {
                                                is List<*> -> rawVal.mapNotNull { it?.toString() }
                                                is String -> listOf(rawVal)
                                                else -> null
                                            }
                                            bean.alpn = alpn?.joinToString("\n")
                                        }

                                        "congestion-controller" -> bean.congestionController =
                                            rawVal.toString()

                                        "udp-relay-mode" -> bean.udpRelayMode = rawVal.toString()
                                    }
                                }
                                if (ip.isNotBlank()) {
                                    if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress!!.isIpAddress()) {
                                        bean.sni = bean.serverAddress
                                    }
                                    bean.serverAddress = ip
                                }
                                if (bean.serverAddress.isNullOrBlank()) return@runCatching
                                proxies.add(bean)
                            }

                            "wireguard" -> {
                                val server = proxy.optStr("server")?.trim()?.removeSurrounding("[", "]")
                                    ?.takeIf { it.isNotEmpty() } ?: return@runCatching
                                val port = proxy.optInt("port")?.takeIf { it in 1..65535 } ?: return@runCatching
                                val privateKey = proxy.optStr("private-key")?.trim()
                                    ?.takeIf { it.isNotEmpty() } ?: return@runCatching
                                val publicKey = proxy.optStr("public-key")?.trim()
                                    ?.takeIf { it.isNotEmpty() } ?: return@runCatching
                                val addresses = buildList {
                                    proxy.optStr("ip")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                                    proxy.optStr("ipv6")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                                }
                                if (addresses.isEmpty()) return@runCatching
                                val reservedStr = when (val rawReserved = proxy["reserved"]) {
                                    is List<*> -> rawReserved.mapNotNull { it?.toString()?.trim()?.toIntOrNull() }
                                        .takeIf { it.size == 3 }?.joinToString(",")
                                    is String -> rawReserved.trim().takeIf { it.isNotEmpty() }
                                    else -> null
                                }
                                val allowedIpsStr = when (val rawAllowed = proxy["allowed-ips"]) {
                                    is List<*> -> rawAllowed.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                                        .joinToString(",")
                                    is String -> rawAllowed.trim()
                                    else -> ""
                                }
                                proxies.add(WireGuardBean().apply {
                                    name = proxy.optStr("name")
                                    serverAddress = server
                                    serverPort = port
                                    this.privateKey = privateKey
                                    peerPublicKey = publicKey
                                    peerPreSharedKey = proxy.optStr("pre-shared-key")
                                        ?: proxy.optStr("preshared-key")
                                    localAddress = addresses.joinToString("\n")
                                    if (!reservedStr.isNullOrBlank()) reserved = reservedStr
                                    if (allowedIpsStr.isNotBlank()) allowedIps = allowedIpsStr
                                    proxy.optInt("mtu")?.takeIf { it in 576..65535 }?.let { mtu = it }
                                    proxy.optInt("persistent-keepalive")?.takeIf { it in 1..65535 }?.let {
                                        persistentKeepalive = it
                                    }
                                })
                            }

                            else -> {
                                if (proxyType != null && proxyType in KNOWN_UNSUPPORTED_CLASH_TYPES) {
                                    skippedKnownUnsupported++
                                }
                            }
                        }
                    }.onFailure { err ->
                        if (err is CancellationException || forSubscription) throw err
                        Logs.w("Skip malformed proxy entry in YAML: ${err.readableMessage}")
                    }
                }

                if (forSubscription && (proxies.size + skippedKnownUnsupported != rawProxies.size ||
                        (rawProxies.isNotEmpty() && proxies.isEmpty()))
                ) {
                    error("Incomplete Clash subscription: parsed ${proxies.size} (skipped $skippedKnownUnsupported unsupported) of ${rawProxies.size} entries")
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host!!.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                return proxies
            } catch (e: YAMLException) {
                if (forSubscription) error("Malformed Clash subscription YAML")
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                proxies.addAll(parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        val trimmed = text.trim()
        if (forSubscription && isLikelyHtmlDocument(trimmed)) {
            error("HTML response cannot be imported as a subscription")
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val json = JSONTokener(trimmed).nextValue()
                return parseJSON(json, forSubscription = forSubscription)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (forSubscription) {
                    throw IllegalStateException("Malformed JSON subscription: ${e.message}", e)
                }
            }
        }
        val trimmedLower = trimmed.lowercase()
        val isExplicitPlainText = trimmedLower.startsWith("snell://") ||
                trimmedLower.startsWith("mieru://") ||
                trimmedLower.startsWith("vmess://") ||
                trimmedLower.startsWith("vless://") ||
                trimmedLower.startsWith("ss://") ||
                trimmedLower.startsWith("ssr://") ||
                trimmedLower.startsWith("trojan://") ||
                trimmedLower.startsWith("trojan-go://") ||
                trimmedLower.startsWith("hysteria://") ||
                trimmedLower.startsWith("hysteria2://") ||
                trimmedLower.startsWith("hy2://") ||
                trimmedLower.startsWith("tuic://") ||
                trimmedLower.startsWith("juicity://") ||
                trimmedLower.startsWith("masque://") ||
                trimmedLower.startsWith("anytls://") ||
                trimmedLower.startsWith("socks://") ||
                trimmedLower.startsWith("socks5://") ||
                trimmedLower.startsWith("http://") ||
                trimmedLower.startsWith("https://") ||
                trimmedLower.startsWith("sn://")

        if (isExplicitPlainText) {
            try {
                return parseProxies(text, forSubscription = forSubscription)
                    .takeIf { it.isNotEmpty() } ?: error("Not found")
            } catch (e: SubscriptionFoundException) {
                throw e
            } catch (ignored: Exception) {
            }
        }

        try {
            val decoded = text.decodeBase64UrlSafe()
            if (forSubscription && isLikelyHtmlDocument(decoded)) {
                error("HTML response cannot be imported as a subscription")
            }
            return parseProxies(decoded, forSubscription = forSubscription)
                .takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (e: Exception) {
            if (!forSubscription) Logs.d(e.message ?: "Base64 decode fallback")
        }

        try {
            return parseProxies(text, forSubscription = forSubscription)
                .takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        bean.privateKey = iface["PrivateKey"]
        iface["MTU"]?.toIntOrNull()?.let { bean.mtu = it }
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]
            if (endpoint.isNullOrBlank() || !endpoint.contains(":")) {
                continue
            }

            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.substringBeforeLast(":").trim().removeSurrounding("[", "]")
            peerBean.serverPort = endpoint.substringAfterLast(":").trim().toIntOrNull() ?: continue
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PresharedKey"]
            // Collect all AllowedIPs entries across repeated INI lines (matches Address handling).
            val allowedIpsEntries = peer.getAll("AllowedIPs")
                ?.flatMap { it.split(",") }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (allowedIpsEntries.isNotEmpty()) {
                peerBean.allowedIps = allowedIpsEntries.joinToString(",")
            }
            peer["PersistentKeepalive"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                peerBean.persistentKeepalive = it
            }
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> = parseJSON(json, forSubscription = false)

    internal fun parseJSON(json: Any, forSubscription: Boolean): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("servers") && json.optJSONArray("servers") != null -> {
                    // SIP008 Shadowsocks subscription format
                    val servers = json.getJSONArray("servers")
                    for (i in 0 until servers.length()) {
                        val item = servers.optJSONObject(i)
                        if (item == null) {
                            if (forSubscription) error("Malformed SIP008 server entry at index $i")
                            continue
                        }
                        runCatching {
                            if (item.has("method") && item.has("server")) {
                                proxies.add(item.parseShadowsocks())
                            } else if (forSubscription) {
                                error("Incomplete SIP008 server entry at index $i")
                            }
                        }.onFailure {
                            if (forSubscription) throw it
                            Logs.w(it)
                        }
                    }
                    if (forSubscription && proxies.size != servers.length()) {
                        error("Incomplete SIP008 subscription: parsed ${proxies.size} of ${servers.length()} entries")
                    }
                    proxies.forEach { it.initializeDefaultValues() }
                    return proxies
                }

                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") -> {
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else if (json is JSONArray) {
            for (index in 0 until json.length()) {
                val item = json.opt(index)
                if (item != null && isJsonObjectValid(item)) {
                    proxies.addAll(parseJSON(item, forSubscription = forSubscription))
                } else if (forSubscription) {
                    error("Malformed JSON array subscription entry at index $index")
                }
            }
        } else if (forSubscription) {
            error("Unsupported JSON subscription root type")
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
