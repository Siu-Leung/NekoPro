package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException

object LocalResolverImpl : LocalDNSTransport {

    // new local

    private const val RCODE_NXDOMAIN = 3

    /**
     * Returns an active non-VPN physical [Network] for bypassing TUN DNS loops.
     * Prefers [SagerNet.underlyingNetwork] when non-VPN, otherwise scans active
     * physical interfaces so ephemeral test boxes in the UI process never fall back
     * to `networkHandle = 0` (the active VPN TUN) when `DefaultNetworkListener` is
     * transitioning or in fallback mode.
     */
    @Suppress("DEPRECATION")
    internal fun resolvePhysicalNetwork(): Network? {
        val connectivity = runCatching { SagerNet.connectivity }.getOrNull() ?: return SagerNet.underlyingNetwork
        val cached = SagerNet.underlyingNetwork
        if (cached != null) {
            val caps = runCatching { connectivity.getNetworkCapabilities(cached) }.getOrNull()
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return cached
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return cached
        var candidate: Network? = null
        val networks = runCatching { connectivity.allNetworks }.getOrNull().orEmpty()
        for (net in networks) {
            val caps = runCatching { connectivity.getNetworkCapabilities(net) }.getOrNull() ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return net
            }
            if (candidate == null) candidate = net
        }
        return candidate
    }

    override fun raw(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun networkHandle(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolvePhysicalNetwork()?.networkHandle ?: 0
        }
        return 0
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                ctx.rawSuccess(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) {
                    ctx.errnoCode(cause.errno)
                } else {
                    Logs.w(error)
                    ctx.errnoCode(114514)
                }
            }
        }

        DnsResolver.getInstance().rawQuery(
            resolvePhysicalNetwork(),
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    try {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(114514)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    try {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            ctx.errnoCode(cause.errno)
                        } else {
                            Logs.w(error)
                            ctx.errnoCode(114514)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(114514)
                    }
                }
            }

            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            val physicalNetwork = resolvePhysicalNetwork()
            if (type != null) {
                DnsResolver.getInstance().query(
                    physicalNetwork,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                DnsResolver.getInstance().query(
                    physicalNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        } else {
            runOnIoDispatcher {
                // 老版本系统，继续用阻塞的 InetAddress
                try {
                    val physicalNetwork = resolvePhysicalNetwork()
                    val answer = if (physicalNetwork != null) {
                        physicalNetwork.getAllByName(domain)
                    } else {
                        InetAddress.getAllByName(domain)
                    }
                    if (answer != null) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errnoCode(114514)
                    }
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(114514)
                }
            }
        }
    }

}