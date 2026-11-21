package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object ListListener : OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
    }
}

/**
 * Preserves a base bottom clearance (for overlay docks/FABs) on top of the system navigation bar inset.
 */
class BottomPaddingInsetsListener(private val baseBottomPx: Int) : OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.updatePadding(bottom = baseBottomPx + navBottom)
    }
}
