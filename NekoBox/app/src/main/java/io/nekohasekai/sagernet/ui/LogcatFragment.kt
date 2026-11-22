package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        reloadSession()
    }

    companion object {
        private val ANSI_CSI_REGEX = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        private val ORPHAN_LEADING_ANSI_REGEX = Regex("^(?:[A-Z]?\\[[0-9;]+m)+")

        /**
         * Strips VT100/ANSI CSI escape codes (including 256-color `ESC[38;5;Nm` sequences)
         * and any truncated leading CSI fragment from raw log text.
         */
        internal fun stripAnsiAndNormalize(raw: String): String {
            return raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(ANSI_CSI_REGEX, "")
                .replace(ORPHAN_LEADING_ANSI_REGEX, "")
        }
    }

    private fun resolveLineColor(
        line: String,
        infoColor: Int,
        warnColor: Int,
        errorColor: Int,
        mutedColor: Int,
    ): Int {
        return when {
            line.startsWith("ERROR") || line.startsWith("FATAL") || line.startsWith("PANIC") ||
                line.contains(" ERROR[") || line.contains(" [Error]") -> errorColor
            line.startsWith("WARN") || line.contains(" WARN[") || line.contains(" [Warning]") -> warnColor
            line.startsWith("INFO") || line.contains(" INFO[") || line.contains(" [Info]") -> infoColor
            else -> mutedColor
        }
    }

    private fun reloadSession() {
        val ctx = context ?: return
        val infoColor = ctx.getColour(R.color.surge_green)
        val warnColor = ctx.getColour(R.color.surge_amber)
        val errorColor = ctx.getColour(R.color.surge_red)
        val mutedColor = ctx.getColour(R.color.surge_text_secondary)

        runOnDefaultDispatcher {
            val cleaned = stripAnsiAndNormalize(String(SendLog.getNekoLog(64 * 1024)))
            val span = SpannableString(cleaned)
            var offset = 0
            for (line in cleaned.split('\n')) {
                if (line.isNotEmpty() && offset + line.length <= span.length) {
                    val lineColor = resolveLineColor(line, infoColor, warnColor, errorColor, mutedColor)
                    span.setSpan(
                        ForegroundColorSpan(lineColor),
                        offset,
                        offset + line.length,
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                offset += line.length + 1
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                binding.textview.text = span
                binding.textview.clearFocus()
                binding.textview.doOnLayout {
                    binding.scroolview.scrollTo(0, binding.textview.height)
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        Libcore.nekoLogClear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    SendLog.sendLog(context, "NB4A")
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}