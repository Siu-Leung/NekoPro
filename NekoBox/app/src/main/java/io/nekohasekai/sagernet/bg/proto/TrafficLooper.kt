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

    private val stateLock = Any()
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
        val snapshots = synchronized(stateLock) {
            idMap.mapValues { (_, v) -> v.tx to v.rx }
        }
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                val (txVal, rxVal) = snapshots[ent.id] ?: continue
                ent.rx = rxVal
                ent.tx = txVal
                ProfileManager.updateTraffic(ent.id, txVal, rxVal)
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = rxVal,
                    tx = txVal,
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

    fun selectMain(id: Long) = synchronized(stateLock) {
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
                trafficUpdater = synchronized(stateLock) {
                    val startRealtime = SystemClock.elapsedRealtime()
                    itemBypass.lastUpdate = startRealtime
                    idMap.clear()
                    tagMap.clear()
                    idMap[-1L] = itemBypass

                    proxy.config.trafficMap.forEach { (tag, ents) ->
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
                    TrafficUpdater(
                        box = proxy.box,
                        items = idMap.values.toList(),
                    )
                }
                delay(delayMs)
                continue
            }

            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            var bypassTxRate = 0L
            var bypassRxRate = 0L
            var perProfileSnapshots: List<TrafficData> = emptyList()

            synchronized(stateLock) {
                trafficUpdater.updateAll()
                tagMap.forEach { (_, it) ->
                    if (!it.ignore) {
                        mainTxRate += it.txRate
                        mainRxRate += it.rxRate
                    }
                    mainTx += it.tx - it.txBase
                    mainRx += it.rx - it.rxBase
                }
                bypassTxRate = itemBypass.txRate
                bypassRxRate = itemBypass.rxRate
                if (profileTrafficStatistics) {
                    perProfileSnapshots = idMap.mapNotNull { (id, item) ->
                        if (id >= 0L) TrafficData(id = id, rx = item.rx, tx = item.tx) else null
                    }
                }
            }
            if (!sc.isActive) return

            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                bypassTxRate,
                bypassRxRate,
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
                            perProfileSnapshots.forEach(b::cbTrafficUpdate)
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