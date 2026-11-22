package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = 1024.0 * 1024.0
private const val GIBIBYTE = 1024.0 * 1024.0 * 1024.0
private const val BITS_PER_MEGABIT = 1_000_000.0
private const val SIGNIFICANT_DIRECT_RATE_BYTES = 4096L
private const val IDLE_PROXY_RATE_BYTES = 2048L

/**
 * Formats a byte-per-second throughput using binary (`1024`) units (`B/s`, `KB/s`, `MB/s`, `GB/s`)
 * independent of Android locale strings so speed displays match standard network monitors.
 */
internal fun formatBinaryByteRate(bytesPerSecond: Long): String {
    val safeBytes = bytesPerSecond.coerceAtLeast(0L)
    return when {
        safeBytes < 1024L -> "$safeBytes B/s"
        safeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", safeBytes / KIBIBYTE)
        safeBytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", safeBytes / MEBIBYTE)
        else -> String.format(Locale.US, "%.2f GB/s", safeBytes / GIBIBYTE)
    }
}

/**
 * Bottom Surge-styled live throughput and connection status bar.
 *
 * Supports both live VPN session throughput (`updateSpeed`) and in-app node/group SpeedTest
 * telemetry (`updateLiveSpeedTest`) so the bar always reflects real active bandwidth.
 */
class StatsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var statusDot: View? = null
    private lateinit var behavior: YourBehavior

    var allowShow = true

    @Volatile
    private var isSpeedTestActive = false

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
            type: Int,
            consumed: IntArray,
        ) {
            // WHY: Keep StatsBar pinned while connected instead of sliding off-screen during list scroll,
            // addressing user feedback that the bottom speed bar disappeared during everyday browsing.
            if (!getAllowShow()) return
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }

    private fun ensureViewsBound() {
        if (!this::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            statusDot = findViewById(R.id.stats_status_dot)
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        ensureViewsBound()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        ensureViewsBound()
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateStatusDotColor(state: BaseService.State) {
        ensureViewsBound()
        val colorRes = when {
            isSpeedTestActive -> R.color.surge_amber
            state == BaseService.State.Connected -> R.color.surge_green
            state == BaseService.State.Connecting || state == BaseService.State.Stopping -> R.color.surge_amber
            else -> R.color.surge_status_idle
        }
        statusDot?.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColour(colorRes))
    }

    fun changeState(state: BaseService.State) {
        ensureViewsBound()
        hideOnScroll = false
        val activity = context as MainActivity
        updateStatusDotColor(state)
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if (state == BaseService.State.Connected || isSpeedTestActive) {
            postWhenStarted {
                if (allowShow) performShow()
                if (!isSpeedTestActive) {
                    setStatus(app.getText(R.string.vpn_connected))
                }
            }
        } else {
            postWhenStarted {
                if (!isSpeedTestActive) performHide()
            }
            updateSpeed(0L, 0L, 0L, 0L)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    /**
     * Updates live VPN throughput on the bottom bar.
     *
     * WHY: In Rule routing mode (`geosite:cn` / `geoip:cn` -> `bypass`), domestic speedtest servers
     * transfer exclusively over `direct`/`bypass`. Surfacing direct throughput when `showDirectSpeed`
     * is enabled OR when direct traffic is active while proxy is idle ensures the bottom bar matches
     * real network speed instead of displaying `0 B/s`.
     */
    @SuppressLint("SetTextI18n")
    fun updateSpeed(
        txRateProxy: Long,
        rxRateProxy: Long,
        txRateDirect: Long = 0L,
        rxRateDirect: Long = 0L,
    ) {
        if (isSpeedTestActive) return
        ensureViewsBound()
        val shouldShowDirect = DataStore.showDirectSpeed ||
            ((txRateDirect + rxRateDirect) >= SIGNIFICANT_DIRECT_RATE_BYTES &&
                (txRateProxy + rxRateProxy) < IDLE_PROXY_RATE_BYTES)

        if (shouldShowDirect && (txRateDirect > 0L || rxRateDirect > 0L)) {
            val totalTx = txRateProxy + txRateDirect
            val totalRx = rxRateProxy + rxRateDirect
            txText.text = "▲ ${formatBinaryByteRate(totalTx)}"
            val directHint = if (rxRateProxy < IDLE_PROXY_RATE_BYTES && rxRateDirect >= SIGNIFICANT_DIRECT_RATE_BYTES) {
                " (${context.getString(R.string.stats_direct_tag)})"
            } else {
                ""
            }
            rxText.text = "▼ ${formatBinaryByteRate(totalRx)}$directHint"
        } else {
            txText.text = "▲ ${formatBinaryByteRate(txRateProxy)}"
            rxText.text = "▼ ${formatBinaryByteRate(rxRateProxy)}"
        }
    }

    /**
     * Mirrors real-time throughput from an in-app node/group SpeedTest onto the bottom bar,
     * displaying both `MB/s` and `Mbps` so the bottom bar matches the SpeedTest dialog 1:1.
     */
    @SuppressLint("SetTextI18n")
    fun updateLiveSpeedTest(
        statusLabel: CharSequence,
        downloadBitsPerSecond: Long,
        uploadBitsPerSecond: Long,
    ) {
        ensureViewsBound()
        isSpeedTestActive = true
        hideOnScroll = false
        updateStatusDotColor(DataStore.serviceState)
        if (allowShow) performShow()
        setStatus(statusLabel)

        val txBytesPerSec = (uploadBitsPerSecond.coerceAtLeast(0L)) / 8L
        val rxBytesPerSec = (downloadBitsPerSecond.coerceAtLeast(0L)) / 8L
        val txMbps = if (uploadBitsPerSecond > 0L) {
            String.format(Locale.US, " (%.1f Mbps)", uploadBitsPerSecond / BITS_PER_MEGABIT)
        } else {
            ""
        }
        val rxMbps = if (downloadBitsPerSecond > 0L) {
            String.format(Locale.US, " (%.1f Mbps)", downloadBitsPerSecond / BITS_PER_MEGABIT)
        } else {
            ""
        }
        txText.text = "▲ ${formatBinaryByteRate(txBytesPerSec)}$txMbps"
        rxText.text = "▼ ${formatBinaryByteRate(rxBytesPerSec)}$rxMbps"
    }

    /**
     * Clears the in-app SpeedTest override and restores live VPN status/speed state.
     */
    fun clearLiveSpeedTest() {
        if (!isSpeedTestActive) return
        isSpeedTestActive = false
        changeState(DataStore.serviceState)
    }

    fun testConnection() {
        if (isSpeedTestActive) return
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            },
                            elapsed,
                        )
                    )
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.vpn_connected))
                    activity.snackbar(
                        app.getString(R.string.connection_test_error, e.readableMessage)
                    ).show()
                }
            }
        }
    }
}
