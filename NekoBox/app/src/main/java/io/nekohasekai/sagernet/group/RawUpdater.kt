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
import moe.matsuri.nb4a.SingBoxDnsServerParser
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

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link!!.startsWith("content://")) {
            val maxChars = 16 * 1024 * 1024
            val contentText = app.contentResolver.openInputStream(link!!.toUri())?.use { input ->
                val reader = input.bufferedReader()
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var read: Int
                while (reader.read(buf).also { read = it } != -1) {
                    if (sb.length + read > maxChars) {
                        error("Subscription file exceeds 16 MB limit")
                    }
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }

            proxies = contentText?.let { parseSubscription(it) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val autoAdoptNodeDns = subscription.autoAdoptNodeDns == true
            // 候选 UA 链：自动采用节点 DNS 模式优先协商完整 Clash YAML（clash-meta -> FlClash），否则沿用常规回退链
            val candidateUserAgents = buildList {
                subscription.customUserAgent?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (autoAdoptNodeDns) {
                    add("clash-meta")
                    add("FlClash/v0.9.4 clash-verge Platform/android")
                } else {
                    add(USER_AGENT)
                    add("clash-meta")
                    add("v2rayN/7.8.2")
                    add("sing-box/1.14.0")
                }
            }.distinct()

            var lastError: Throwable? = null
            var lastUserinfo = ""
            var lastDisposition = ""
            var adoptedNodeDnsCandidate: String? = null
            proxies = emptyList()

            val forceProxy = DataStore.serviceState.connected ||
                (!byUser && subscription.updateWhenConnectedOnly == true)

            for (candidate in candidateUserAgents) {
                val client = Libcore.newHttpClient().apply {
                    if (forceProxy) {
                        forceSocks5(DataStore.mixedPort)
                    } else {
                        trySocks5(DataStore.mixedPort)
                        tryH3Direct()
                    }
                    when (DataStore.appTLSVersion) {
                        "1.3" -> restrictedTLS()
                    }
                }
                try {
                    val response = client.newRequest().apply {
                        if (DataStore.allowInsecureOnRequest) {
                            allowInsecure()
                        }
                        setURL(subscription.link)
                        setUserAgent(candidate)
                    }.execute()
                    val responseText = Util.getStringBox(response.contentString)
                    val parsed = parseSubscription(responseText)
                    if (parsed.isNullOrEmpty()) {
                        throw IllegalStateException("no proxies found with UA: $candidate")
                    }
                    if (autoAdoptNodeDns) {
                        val extractedDns = extractClashProxyServerNameserver(responseText)
                            ?: throw IllegalStateException("no supported dns.proxy-server-nameserver with UA: $candidate")
                        adoptedNodeDnsCandidate = extractedDns
                    }
                    proxies = parsed

                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))
                        .takeIf { it.isNotBlank() }?.let { lastUserinfo = it }
                    if (proxyGroup.name?.startsWith("Subscription #") == true) {
                        val remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                        if (remoteName.isNotBlank()) {
                            lastDisposition = remoteName
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionFoundException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    Logs.d("Subscription download failed with UA $candidate: ${e.message}")
                } finally {
                    client.close()
                }
            }

            if (proxies.isEmpty()) {
                throw (lastError ?: error(app.getString(R.string.no_proxies_found)))
            }

            subscription.subscriptionUserinfo = lastUserinfo
            if (autoAdoptNodeDns && !adoptedNodeDnsCandidate.isNullOrBlank()) {
                subscription.adoptedNodeDns = adoptedNodeDnsCandidate
            }

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

        if (subscription.forceResolve == true) forceResolve(proxies, proxyGroup.id)

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

            subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            SagerDatabase.groupDao.updateGroup(proxyGroup)
        }
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? =
        parseRaw(text, fileName, forSubscription = false)

    internal fun extractClashProxyServerNameserver(text: String): String? {
        if (!clashProxiesKey.containsMatchIn(text)) return null
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = 8 * 1024 * 1024
        }
        val yaml = runCatching {
            Yaml(SafeConstructor(loaderOptions)).load<Any?>(text) as? Map<*, *>
        }.getOrNull() ?: return null
        val dns = yaml["dns"] as? Map<*, *> ?: return null
        val raw = dns["proxy-server-nameserver"] ?: return null
        val entries = when (raw) {
            is String -> listOf(raw.trim()).filter { it.isNotEmpty() }
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            else -> return null
        }.distinct()
        if (entries.size != 1) return null
        val candidate = entries[0]
        // Reject Mihomo-specific proxy suffix modifiers (#PROXY, #DIRECT) or query params not faithfully supported by sing-box DoH path.
        if (candidate.contains('#') || candidate.contains('?')) return null
        val lower = candidate.lowercase()
        if (lower == "system" || lower == "local" || lower.startsWith("dhcp://")) return null
        return runCatching {
            SingBoxDnsServerParser.parse(
                rawAddress = candidate,
                tag = "probe-node-dns",
                defaultDetour = null,
                domainResolver = "dns-direct",
            )
            candidate
        }.getOrNull()
    }

    internal suspend fun parseSubscription(text: String): List<AbstractBean>? {
        currentCoroutineContext().ensureActive()
        return parseRaw(text, "", forSubscription = true).also {
            currentCoroutineContext().ensureActive()
        }
    }

    // Recognize YAML-looking declarations without treating URI substrings as malformed YAML.
    private val clashProxiesKey = Regex("""(?m)^[\t ]*(?:\{[\t ]*)?(?:proxies|"proxies"|'proxies')[\t ]*:""")

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseRaw(text: String, fileName: String, forSubscription: Boolean): List<AbstractBean>? {
        val proxies = mutableListOf<AbstractBean>()
        val hasClashDeclaration = forSubscription && clashProxiesKey.containsMatchIn(text)

        if (hasClashDeclaration || (!forSubscription && text.contains("proxies:"))) {

            // clash & meta

            try {

                val loaderOptions = LoaderOptions().apply {
                    codePointLimit = 8 * 1024 * 1024 // 8 MiB YAML bomb guard
                }
                val yaml = Yaml(SafeConstructor(loaderOptions)).load<Any?>(text) as? Map<*, *>
                    ?: throw YAMLException("Clash YAML root must be a mapping")

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

                for (rawEntry in rawProxies) {
                    val proxy = rawEntry as? Map<*, *> ?: continue
                    runCatching {
                        // Note: YAML numbers parsed as "Long"
                        when (proxy.optStr("type")?.lowercase()) {
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
                                        "userkey" -> bean.userkey = rawVal.toString()
                                        "version" -> bean.version = rawVal.toString().toIntOrNull() ?: 4
                                        "mode" -> bean.mode = rawVal.toString()
                                        "obfs-opts" -> {
                                            (rawVal as? Map<*, *>)?.let { obfs ->
                                                bean.obfsMode = obfs["mode"]?.toString() ?: "none"
                                                bean.obfsHost = obfs["host"]?.toString() ?: ""
                                            }
                                        }
                                    }
                                }
                                if (bean.version != null && bean.version !in 4..6) return@runCatching
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
                                        "port-range" -> bean.portRange = rawVal.toString().trim()
                                        "transport" -> bean.protocol = rawVal.toString().trim().uppercase()
                                        "username" -> bean.username = rawVal.toString()
                                        "password" -> bean.password = rawVal.toString()
                                        "mtu" -> bean.mtu = rawVal.toString().toIntOrNull() ?: 1400
                                        "multiplexing" -> bean.multiplexing = rawVal.toString().trim()
                                        "handshake-mode" -> bean.handshakeMode = rawVal.toString().trim()
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
                                        "client-fingerprint", "fingerprint" -> bean.utlsFingerprint =
                                            rawVal.toString()

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
                        }
                    }.onFailure { err ->
                        if (err is CancellationException || forSubscription) throw err
                        Logs.w("Skip malformed proxy entry in YAML: ${err.readableMessage}")
                    }
                }

                if (forSubscription && proxies.size != rawProxies.size) {
                    error("Incomplete Clash subscription: parsed ${proxies.size} of ${rawProxies.size} entries")
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

        try {
            val json = JSONTokener(text).nextValue()
            return parseJSON(json, forSubscription = forSubscription)
        } catch (e: IllegalStateException) {
            if (forSubscription) throw e
        } catch (ignored: Exception) {
        }

        val trimmed = text.trim()
        val isExplicitPlainText = trimmed.startsWith("snell://") ||
                trimmed.startsWith("vmess://") ||
                trimmed.startsWith("vless://") ||
                trimmed.startsWith("ss://") ||
                trimmed.startsWith("ssr://") ||
                trimmed.startsWith("trojan://") ||
                trimmed.startsWith("trojan-go://") ||
                trimmed.startsWith("hysteria://") ||
                trimmed.startsWith("hysteria2://") ||
                trimmed.startsWith("hy2://") ||
                trimmed.startsWith("tuic://") ||
                trimmed.startsWith("juicity://") ||
                trimmed.startsWith("masque://") ||
                trimmed.startsWith("anytls://") ||
                trimmed.startsWith("socks://") ||
                trimmed.startsWith("socks5://") ||
                trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ||
                trimmed.startsWith("sn://")

        if (isExplicitPlainText) {
            try {
                return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
            } catch (e: SubscriptionFoundException) {
                throw e
            } catch (ignored: Exception) {
            }
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (e: Exception) {
            Logs.d(e.message ?: "Base64 decode fallback")
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
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
            peer["AllowedIPs"]?.trim()?.takeIf { it.isNotEmpty() }?.let { peerBean.allowedIps = it }
            peer["PersistentKeepalive"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
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
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
