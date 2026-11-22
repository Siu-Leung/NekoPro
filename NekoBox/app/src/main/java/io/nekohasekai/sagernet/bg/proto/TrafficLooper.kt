package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*

/**
 * Background coroutine loop that polls `v2rayapi.StatsService` counters and broadcasts
 * live proxy/direct throughput (`SpeedDisplayData`) and per-profile byte totals (`TrafficData`).
 */
class TrafficLooper(
    val data: BaseService.Data,
    private val sc: CoroutineScope,
) {

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>()
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>()

    suspend fun stop() {
        val runningJob = job
        job = null
        runningJob?.cancel()
        withTimeoutOrNull(2000L) {
            runningJob?.join()
        }
        if (!DataStore.profileTrafficStatistics) return
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                val item = idMap[ent.id] ?: continue
                ent.rx = item.rx
                ent.tx = item.tx
                ProfileManager.updateTraffic(ent.id, item.tx, item.rx)
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = ent.rx,
                    tx = item.tx,
                )
            }
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            secondaryTags = emptyList()
            ignore = true
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    val profileId = it.id
                    val finalTx = tx
                    val finalRx = rx
                    runOnDefaultDispatcher {
                        ProfileManager.updateTraffic(profileId, finalTx, finalRx)
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            // WHY: In sing-box 1.14, TCP/UDP `RoutedConnection` counts against the outer Selector
            // tag (`proxy`), whereas L3 `RoutedFlow` counts against the resolved leaf outbound tag
            // (`chain[last]`). Summing both non-overlapping counters guarantees exact speed in Selector mode.
            secondaryTags = if (selectorNowFakeTag.isNotEmpty() && selectorNowFakeTag != TAG_PROXY) {
                listOf(selectorNowFakeTag)
            } else {
                emptyList()
            }
            lastUpdate = SystemClock.elapsedRealtime()
            ignore = false
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (delayMs == 0L) return

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        val itemBypass = TrafficUpdater.TrafficLooperData(
            tag = TAG_BYPASS,
            secondaryTags = listOf(TAG_DIRECT),
        )

        while (sc.isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(delayMs)
                    continue
                }
                val startRealtime = SystemClock.elapsedRealtime()
                itemBypass.lastUpdate = startRealtime
                idMap.clear()
                tagMap.clear()
                idMap[-1L] = itemBypass

                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS, TAG_DIRECT)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            lastUpdate = startRealtime,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap[ent.id] = item
                        tagMap[tag] = item
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    selectMain(proxy.config.mainEntId)
                }
                trafficUpdater = TrafficUpdater(
                    box = proxy.box,
                    items = idMap.values.toList(),
                )
                proxy.box.setV2rayStats(tags.joinToString("\n"))
                delay(delayMs)
                continue
            }

            trafficUpdater.updateAll()
            if (!sc.isActive) return

            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // WHY: Always populate direct (`bypass` + `direct`) throughput so `StatsBar` can surface
            // domestic CN speedtest traffic in Rule mode instead of misleadingly showing `0 B/s`.
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                itemBypass.txRate,
                itemBypass.rxRate,
                mainTx,
                mainRx,
            )

            if (data.state == BaseService.State.Connected &&
                data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b.asBinder()] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            idMap.forEach { (id, item) ->
                                if (id >= 0L) {
                                    b.cbTrafficUpdate(
                                        TrafficData(id = id, rx = item.rx, tx = item.tx)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }
}