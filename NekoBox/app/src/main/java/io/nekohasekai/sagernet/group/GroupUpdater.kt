package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet6Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    /**
     * Resolves profile hostnames to IP literals using structured concurrency, honoring
     * [groupDns] when configured and filtering out FakeIP/sinkhole answers.
     */
    protected suspend fun forceResolve(
        profiles: List<AbstractBean>,
        groupId: Long?,
        groupDns: List<String> = emptyList(),
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupPool = newFixedThreadPoolContext(5, "DNS Lookup")
        try {
            val progress = Progress(profiles.size)
            if (groupId != null) {
                GroupUpdater.progress[groupId] = progress
                GroupManager.postReload(groupId)
            }
            val ipv6First = ipv6Mode >= IPv6Mode.PREFER

            coroutineScope {
                val lookupJobs = mutableListOf<Job>()
                for (profile in profiles) {
                    when (profile) {
                        // SNI rewrite unsupported
                        is NaiveBean -> continue
                    }

                    val serverAddress = profile.serverAddress
                    if (serverAddress.isNullOrBlank() || serverAddress.isIpAddress()) continue

                    lookupJobs.add(launch(lookupPool) {
                        try {
                            val resolvedAddresses = if (groupDns.isNotEmpty()) {
                                val resolved = io.nekohasekai.sagernet.ipinfo.IpInfoStore.resolveEntry(
                                    serverAddress,
                                    groupDns,
                                )
                                listOfNotNull(resolved?.let { InetAddress.getByName(it) })
                            } else {
                                val underlyingNetwork = SagerNet.underlyingNetwork
                                val raw = if (
                                    underlyingNetwork != null &&
                                    DataStore.enableFakeDns &&
                                    DataStore.serviceState.started &&
                                    DataStore.serviceMode == Key.MODE_VPN
                                ) {
                                    underlyingNetwork.getAllByName(serverAddress).filterNotNull()
                                } else {
                                    InetAddress.getAllByName(serverAddress).filterNotNull()
                                }
                                raw.filter { addr ->
                                    addr.hostAddress?.let(io.nekohasekai.sagernet.ipinfo.IpInfoStore::isUsableEntryIp) == true
                                }
                            }
                            if (resolvedAddresses.isEmpty()) error("empty response")
                            rewriteAddress(profile, resolvedAddresses, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
                        }
                        if (groupId != null) {
                            progress.progress++
                            GroupManager.postReload(groupId)
                        }
                    })
                }
                lookupJobs.joinAll()
            }
        } finally {
            lookupPool.close()
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet6Address) xor ipv6First }[0].hostAddress
        val originalHost = bean.serverAddress.orEmpty()
        val hostWithPort = "$originalHost:${bean.serverPort}"

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isNullOrBlank()) sni = originalHost
                }
                is StandardV2RayBean -> {
                    if (security == "tls" && sni.isNullOrBlank()) sni = originalHost
                    if (host.isNullOrBlank()) {
                        host = when (type) {
                            "ws" -> hostWithPort
                            "httpupgrade" -> if (security == "tls") {
                                sni?.takeIf { it.isNotBlank() } ?: originalHost
                            } else {
                                hostWithPort
                            }
                            "http", "xhttp", "splithttp" -> originalHost
                            else -> host
                        }
                    }
                }
                is TrojanBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                }
                is TrojanGoBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                    if (type == "ws" && host.isNullOrBlank()) host = originalHost
                }
                is HysteriaBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                }
                is io.nekohasekai.sagernet.fmt.tuic.TuicBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                }
                is io.nekohasekai.sagernet.fmt.juicity.JuicityBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                }
                is io.nekohasekai.sagernet.fmt.masque.MASQUEBean -> {
                    // Preserve empty SNI so Cloudflare WARP/MASQUE uses its default SNI.
                }
                is moe.matsuri.nb4a.proxy.anytls.AnyTLSBean -> {
                    if (sni.isNullOrBlank()) sni = originalHost
                }
                is moe.matsuri.nb4a.proxy.snell.SnellBean -> {
                    val mode = obfsMode?.trim()?.lowercase().orEmpty()
                    if (mode.isNotEmpty() && mode != "none" && obfsHost.isNullOrBlank()) {
                        obfsHost = originalHost
                    }
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) return@coroutineScope false
                try {
                    GroupManager.postReload(proxyGroup.id)

                    val subscription = proxyGroup.subscription!!
                    val connected = DataStore.serviceState.connected
                    val userInterface = GroupManager.userInterface

                    if (!byUser && subscription.updateWhenConnectedOnly == true && !connected) {
                        return@coroutineScope false
                    }

                    if (byUser && (subscription.link?.startsWith("http://", ignoreCase = true) == true || subscription.updateWhenConnectedOnly == true) && !connected) {
                        if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            return@coroutineScope false
                        }
                    }

                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logs.w(e)
                    GroupManager.userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    false
                } finally {
                    withContext(NonCancellable) {
                        finishUpdate(proxyGroup)
                    }
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}