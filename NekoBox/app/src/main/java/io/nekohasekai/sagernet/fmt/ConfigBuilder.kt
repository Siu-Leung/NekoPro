package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.fmt.masque.buildSingBoxOutboundMasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildSingBoxOutboundMieruBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.security.ClashApiSecret
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config!!, // Compatibility is applied only when libcore decodes this config.
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val groupNodeDnsPlan = GroupNodeDnsPlan()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(visited: MutableSet<Long> = hashSetOf()): MutableList<ProxyEntity> {
        if (!visited.add(id)) {
            Logs.w("Detected proxy chain cycle at profile $id, breaking cycle")
            return mutableListOf()
        }
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies!!)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies!!) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal(HashSet(visited)))
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.takeIf { it > 0 && it != id }?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.takeIf { it > 0 && it != id }?.let { SagerDatabase.proxyDao.getById(it) }
        val visited = hashSetOf<Long>()
        val list = resolveChainInternal(visited)
        if (frontProxy != null) {
            list.addAll(frontProxy.resolveChainInternal(HashSet(visited)))
        }
        if (landingProxy != null) {
            list.addAll(0, landingProxy.resolveChainInternal(HashSet(visited)))
        }
        return list
    }

    val isGlobalMode = DataStore.routeMode == 1
    val extraRules = if (forTest || isGlobalMode) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        val enableClashApi = DataStore.enableClashAPI && !forExport
        if (!forTest && (enableClashApi || DataStore.enableCacheFile)) experimental = ExperimentalOptions().apply {
            if (enableClashApi) clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                secret = ClashApiSecret.value
                external_ui = java.io.File(SagerNet.application.filesDir, "yacd").absolutePath
            }
            if (DataStore.enableCacheFile) cache_file = CacheFile().apply {
                enabled = true
                path = "cache.db"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "warn"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                mtu = DataStore.mtu
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }

                    IPv6Mode.ONLY -> {
                        address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }

                    else -> {
                        address = listOf(
                            VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                            VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                        )
                    }
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            default_domain_resolver = "dns-direct"
            // WireGuard is an endpoint since 1.13; without route.final the
            // default outbound falls back to the first *outbound* (direct),
            // so a WireGuard-only profile would leak all traffic direct.
            // "proxy" resolves both to the selector/exit outbound and to a
            // WireGuard endpoint tagged "proxy" (OutboundManager checks the
            // endpoint manager for the final tag at start).
            final_ = TAG_PROXY
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // sing-box 1.11+ 迁移: sniff / domain_strategy 从 inbound 顶层
        // 移到 route rule actions (legacy inbound fields 在 1.13 已移除)
        if (needSniff) {
            route.rules.add(Rule_DefaultOptions().apply {
                // Preserve the legacy inbound scope. Internal mapping
                // inbounds carry proxy handshakes and must not be sniffed.
                inbound = listOf("tun-in", TAG_MIXED)
                action = "sniff"
                sniff_override_destination = needSniffOverride
            })
        }
        if (DataStore.resolveDestination) {
            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf("tun-in", TAG_MIXED)
                action = "resolve"
                strategy = genDomainStrategy(true)
            })
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            if (profileList.isEmpty()) {
                throw IllegalStateException("Empty proxy chain for ${entity.displayName()}")
            }
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                val entityGroup = SagerDatabase.groupDao.getById(proxyEntity.groupId)
                val groupNodeDns = entityGroup?.effectiveNodeDns()
                // A custom outbound owns its `server` setting, not the bean's default placeholder.
                val entryHost = if (bean is ConfigBean) {
                    (gson.fromJson(bean.config, Map::class.java)?.get("server") as? String).orEmpty()
                } else bean.serverAddress.orEmpty()
                val entryIsDomain = entryHost.isNotBlank() && !SingBoxDnsServerParser.isIpLiteral(entryHost)
                if (entryIsDomain && groupNodeDns == null && entityGroup?.requiresNodeDnsAdoption() == true) {
                    // 逻辑修正（2026-11-21）：不再硬性中止构建。自动采纳开启但订阅源从不提供
                    // dns.proxy-server-nameserver 时（如纯节点订阅），旧行为（≤1.4.1）是回退
                    // default_domain_resolver=dns-direct 拨号；公网可解析的节点仍可用，私有
                    // 入口域名会在拨号期报明确的 SERVFAIL。硬抛错会让整组节点连测速都起不来，
                    // 且用户无从在测速界面补救。此处降级为警告，由 GroupSettings 的状态行和
                    // 刷新提示引导手填。
                    Logs.w("Group '${entityGroup.displayName()}' has auto-adopt node DNS enabled but no resolver; falling back to the default resolver for ${bean.displayName()}")
                }
                if (entryIsDomain && groupNodeDns != null && proxyEntity.needExternal()) {
                    // 组解析器无法绑定到外部插件的本地 SOCKS 占位出站（占位是 127.0.0.1）。
                    // 与上一条同理改为警告：插件自身仍按其原有方式解析入口域名。
                    Logs.w("Group resolver cannot bind to external plugin outbound ${bean.displayName()}; skipping explicit domain_resolver")
                }

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    if (groupNodeDns.isNullOrBlank()) {
                        bypassDNSBeans += proxyEntity.requireBean()
                    }
                }

                val existingGlobalTag = if (needGlobal) globalOutbounds[proxyEntity.id] else null
                if (existingGlobalTag != null) {
                    tagOut = existingGlobalTag
                } else {
                    // last profile set as "proxy"
                    if (chainId == 0L && index == 0) {
                        tagOut = TAG_PROXY
                    }

                    // selector human readable name
                    if (buildSelector && index == 0) {
                        tagOut = selectorName(bean.displayName())
                    }
                }

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    if (existingGlobalTag != null) {
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is MASQUEBean ->
                            buildSingBoxOutboundMasqueBean(bean)

                        is MieruBean ->
                            buildSingBoxOutboundMieruBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is WireGuardBean -> // sing-box 1.13+: WireGuard is an endpoint
                            buildSingBoxEndpointWireGuardBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is moe.matsuri.nb4a.proxy.snell.SnellBean ->
                            moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp (type-safe check, zero reflection)
                    if ((bean as? io.nekohasekai.sagernet.fmt.UdpOverTcpConfigurable)?.sUoT == true) {
                        _hack_config_map["udp_over_tcp"] = true
                    }

                    // domain_strategy
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    // Explicit resolver also applies in URL tests and on detoured chain hops.
                    if (groupNodeDns != null && !proxyEntity.needExternal()) {
                        _hack_config_map["domain_resolver"] = try {
                            groupNodeDnsPlan.bind(proxyEntity.groupId, groupNodeDns, entryHost)
                        } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException(app.getString(R.string.group_node_dns_invalid))
                        }
                    }

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort!!
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                val isWireGuardCustom = currentOutbound is CustomSingBoxOption &&
                    (currentOutbound.getBasicMap()["type"] as? String) == "wireguard"
                if (currentOutbound is Endpoint || isWireGuardCustom) {
                    if (endpoints == null) endpoints = mutableListOf()
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                if (it.type == TYPE_CONFIG && (it.requireBean() as? ConfigBean)?.type == 0) {
                    return@forEach
                }
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
                if (!isVPN) {
                    runOnMainDispatcher {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            val uidList = rule.packages.map {
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                val hasNonDnsConditions =
                    rule.ip.isNotBlank() ||
                        rule.port.isNotBlank() ||
                        rule.sourcePort.isNotBlank() ||
                        rule.network.isNotBlank() ||
                        rule.source.isNotBlank() ||
                        rule.protocol.isNotBlank() ||
                        rule.config.isNotBlank()

                if (!hasNonDnsConditions) {
                    when (rule.outbound) {
                        -1L -> {
                            userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                        }

                        0L -> {
                            if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-fake"
                                inbound = listOf("tun-in")
                                query_type = listOf("A", "AAAA")
                            }
                            userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-remote"
                            }
                        }

                        -2L -> {
                            userDNSRuleList += makeDnsRuleObj().apply {
                                action = "predefined"
                                rcode = "NOERROR"
                            }
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    runOnMainDispatcher {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                runCatching {
                    val config = gson.fromJson(it.config, Map::class.java)
                    config?.get("server")?.toString()?.takeIf { s -> s.isNotBlank() }?.let { s ->
                        serverAddr = s
                    }
                }
            }

            if (!serverAddr.isNullOrBlank() && !serverAddr!!.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            SingBoxDnsServerParser.extractHost(it)?.let { host ->
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        // Group DoH bootstraps through its explicit `dns-direct` resolver; that socket lookup
        // bypasses TUN rules. Do not route a node's exact host to public DNS if the names overlap.
        val groupNodeDnsRules = groupNodeDnsPlan.domainRules()
        val privateNodeHosts = groupNodeDnsRules.flatMap { it.domain.orEmpty() }.toHashSet()
        groupNodeDnsPlan.servers.forEach { serverOpt ->
            serverOpt.server?.takeIf { it.isNotBlank() && !it.isIpAddress() }?.let { host ->
                domainListDNSDirectForce.add("full:$host")
            }
        }
        domainListDNSDirectForce.removeAll { it.removePrefix("full:").trimEnd('.').lowercase() in privateNodeHosts }

        // sing-box 1.14 typed DNS servers (legacy address format and top-level dns.fakeip removed)
        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
        })

        val directDnsAddr = directDNS.firstOrNull()
            ?: throw Exception("No direct DNS, check your settings!")
        dns.servers.add(
            SingBoxDnsServerParser.parse(
                rawAddress = directDnsAddr,
                tag = "dns-direct",
                defaultDetour = null,
                domainResolver = "dns-local",
            )
        )

        if (!forTest) {
            val remoteDnsAddr = remoteDns.firstOrNull()
                ?: throw Exception("No remote DNS, check your settings!")
            dns.servers.add(
                SingBoxDnsServerParser.parse(
                    rawAddress = remoteDnsAddr,
                    tag = "dns-remote",
                    defaultDetour = TAG_PROXY,
                    domainResolver = "dns-direct",
                )
            )
        }

        dns.servers.addAll(groupNodeDnsPlan.servers)

        dns.strategy = autoDnsDomainStrategy(
            SingBoxOptionsUtil.domainStrategy(if (forTest) "dns-direct" else "dns-remote")
        )
        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting && !isGlobalMode) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules (insert after sniff so protocol=["dns"] can match sniffed DNS)
            val hijackInsertIndex = if (needSniff) 1 else 0
            route.rules.add(hijackInsertIndex, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(hijackInsertIndex, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast (split into two rules so dst and src are OR'd rather than AND'd)
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            route.rules.add(Rule_DefaultOptions().apply {
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS server (1.14 typed fakeip server format)
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    if (ipv6Mode != IPv6Mode.ONLY) {
                        inet4_range = "198.18.0.0/15"
                    }
                    if (ipv6Mode != IPv6Mode.DISABLE) {
                        inet6_range = "fc00::/18"
                    }
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    query_type = listOf("A", "AAAA")
                    server = "dns-fake"
                    disable_cache = true
                })
            }
            // Bootstrap and legacy direct targets keep their priority over ordinary DNS rules.
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
            // Only exact node-hostname requests are covered. Normal website and FakeIP rules stay intact.
            dns.rules.addAll(0, groupNodeDnsRules)
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson!!)
        migrateLegacyWireGuardOutbounds(configMap)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }

}

@Suppress("UNCHECKED_CAST")
internal fun migrateLegacyWireGuardOutbounds(configMap: MutableMap<String, Any>) {
    fun normalizeLegacyWireGuardMap(entry: MutableMap<String, Any?>) {
        if ((entry["type"] as? String) != "wireguard") return
        if (entry["address"] == null && entry["local_address"] != null) {
            entry["address"] = entry.remove("local_address")
        }
        if (entry["peers"] == null && entry["server"] != null) {
            val peer = LinkedHashMap<String, Any?>()
            entry.remove("server")?.let { peer["address"] = it }
            entry.remove("server_port")?.let { peer["port"] = it }
            entry.remove("peer_public_key")?.let { peer["public_key"] = it }
            entry.remove("pre_shared_key")?.let { peer["pre_shared_key"] = it }
            entry.remove("reserved")?.let { peer["reserved"] = it }
            peer["allowed_ips"] = listOf("0.0.0.0/0", "::/0")
            entry["peers"] = listOf(peer)
        }
        entry.remove("gso")
    }

    val existingEndpoints = (configMap["endpoints"] as? List<*>)?.toMutableList() ?: mutableListOf()
    var endpointsModified = false

    val outboundsList = configMap["outbounds"] as? List<*>
    if (outboundsList != null) {
        val remainingOutbounds = ArrayList<Any?>(outboundsList.size)
        var outboundsModified = false
        for (item in outboundsList) {
            val map = item as? MutableMap<String, Any?>
            if (map != null && (map["type"] as? String) == "wireguard") {
                normalizeLegacyWireGuardMap(map)
                existingEndpoints.add(map)
                outboundsModified = true
                endpointsModified = true
            } else {
                remainingOutbounds.add(item)
            }
        }
        if (outboundsModified) {
            configMap["outbounds"] = remainingOutbounds
        }
    }

    for (item in existingEndpoints) {
        val map = item as? MutableMap<String, Any?> ?: continue
        if ((map["type"] as? String) == "wireguard") {
            normalizeLegacyWireGuardMap(map)
            endpointsModified = true
        }
    }
    if (endpointsModified && existingEndpoints.isNotEmpty()) {
        configMap["endpoints"] = existingEndpoints
    }
}
