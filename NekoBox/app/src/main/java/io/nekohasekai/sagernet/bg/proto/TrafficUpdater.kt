package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock

/**
 * Periodically samples atomic byte deltas from `libcore.BoxInstance.queryStats` using a
 * monotonic clock (`SystemClock.elapsedRealtime`) so NTP wall-clock jumps never skew rates.
 */
class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>,
) {

    /**
     * Mutable traffic accumulator for one logical outbound bucket.
     *
     * @property tag Primary sing-box outbound tag queried on each tick.
     * @property secondaryTags Additional tags belonging to the same logical bucket (for example,
     * the selected child tag under a `selector` group for L3 `RoutedFlow` counters, or `direct`
     * paired with `bypass`).
     */
    class TrafficLooperData(
        var tag: String,
        var secondaryTags: List<String> = emptyList(),
        var tx: Long = 0L,
        var rx: Long = 0L,
        var txBase: Long = 0L,
        var rxBase: Long = 0L,
        var txRate: Long = 0L,
        var rxRate: Long = 0L,
        var lastUpdate: Long = SystemClock.elapsedRealtime(),
        var ignore: Boolean = false,
    )

    private fun queryTagPair(tag: String): Pair<Long, Long> {
        val uplinkBytes = box.queryStats(tag, "uplink")
        val downlinkBytes = box.queryStats(tag, "downlink")
        return uplinkBytes to downlinkBytes
    }

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        val now = SystemClock.elapsedRealtime()
        val intervalMs = now - item.lastUpdate
        item.lastUpdate = now
        if (intervalMs <= 0L) {
            return item.apply {
                rxRate = 0L
                txRate = 0L
            }
        }

        var (deltaTx, deltaRx) = queryTagPair(item.tag)
        for (extraTag in item.secondaryTags) {
            if (extraTag.isNotEmpty() && extraTag != item.tag) {
                val (extraTx, extraRx) = queryTagPair(extraTag)
                deltaTx += extraTx
                deltaRx += extraRx
            }
        }

        item.rx += deltaRx
        item.tx += deltaTx
        item.rxRate = deltaRx * 1000L / intervalMs
        item.txRate = deltaTx * 1000L / intervalMs

        return TrafficLooperData(
            tag = item.tag,
            rx = deltaRx,
            tx = deltaTx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    suspend fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>()
        items.forEach { item ->
            if (item.ignore) return@forEach
            val cacheKey = if (item.secondaryTags.isEmpty()) {
                item.tag
            } else {
                buildString {
                    append(item.tag)
                    item.secondaryTags.forEach {
                        append('|')
                        append(it)
                    }
                }
            }
            var diff = updated[cacheKey]
            if (diff == null) {
                diff = updateOne(item)
                updated[cacheKey] = diff
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
    }
}
