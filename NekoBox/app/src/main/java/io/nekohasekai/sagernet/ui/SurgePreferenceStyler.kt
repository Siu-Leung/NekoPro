package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R

/**
 * Applies Surge-style inset grouped card styling to [PreferenceFragmentCompat] screens
 * (Global Settings, Group Settings, Route Settings, and all Protocol Profile Editors).
 *
 * Contiguous non-category [androidx.preference.Preference] rows are rendered inside rounded
 * elevated surface cards (`14dp` radius, `1dp` hairline border) over the grouped canvas
 * background, with indented `1dp` hairline dividers between sibling rows.
 */
object SurgePreferenceStyler {

    private const val DECORATION_TAG_KEY = R.id.settings

    /**
     * Styles [fragment]'s underlying [RecyclerView] as a Surge inset grouped table view.
     *
     * @param fragment Target [PreferenceFragmentCompat] whose list view has been created.
     */
    fun applyTo(fragment: PreferenceFragmentCompat) {
        fragment.setDivider(null)
        fragment.setDividerHeight(0)
        val recyclerView = fragment.listView ?: return
        val context = recyclerView.context
        val horizontalPadding = dp(context, 12f).toInt()
        val topPadding = dp(context, 8f).toInt()
        val minBottomPadding = dp(context, 80f).toInt()

        recyclerView.setBackgroundColor(ContextCompat.getColor(context, R.color.surge_canvas))
        recyclerView.clipToPadding = false
        recyclerView.setPadding(
            horizontalPadding,
            topPadding,
            horizontalPadding,
            recyclerView.paddingBottom.coerceAtLeast(minBottomPadding),
        )

        if (recyclerView.getTag(DECORATION_TAG_KEY) != true) {
            recyclerView.addItemDecoration(SurgeGroupedPreferenceDecoration(context))
            recyclerView.setTag(DECORATION_TAG_KEY, true)
        }
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private class SurgeGroupedPreferenceDecoration(context: Context) : RecyclerView.ItemDecoration() {

        private val cornerRadius = dp(context, 14f)
        private val strokeWidth = dp(context, 1f)
        private val dividerHeight = dp(context, 1f).coerceAtLeast(1f)
        private val dividerInset = dp(context, 16f)
        private val categoryTopSpace = dp(context, 14f).toInt()
        private val categoryBottomSpace = dp(context, 4f).toInt()
        private val categoryHorizontalInset = dp(context, 6f).toInt()
        private val groupTopSpace = dp(context, 4f).toInt()
        private val groupBottomSpace = dp(context, 8f).toInt()
        private val rowHorizontalInset = dp(context, 2f).toInt()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.surge_surface)
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = this@SurgeGroupedPreferenceDecoration.strokeWidth
            color = ContextCompat.getColor(context, R.color.card_stroke)
        }

        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.surge_divider)
        }

        private val cardRect = RectF()

        private fun isCardRow(adapter: RecyclerView.Adapter<*>?, position: Int): Boolean {
            if (adapter !is PreferenceGroupAdapter) return false
            if (position < 0 || position >= adapter.itemCount) return false
            val item = adapter.getItem(position) ?: return false
            return item !is PreferenceCategory
        }

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            val adapter = parent.adapter
            if (position == RecyclerView.NO_POSITION || adapter !is PreferenceGroupAdapter) {
                outRect.setEmpty()
                return
            }
            val item = adapter.getItem(position)
            if (item is PreferenceCategory) {
                outRect.set(
                    categoryHorizontalInset,
                    if (position == 0) groupTopSpace else categoryTopSpace,
                    categoryHorizontalInset,
                    categoryBottomSpace,
                )
                return
            }
            val isFirstInCard = !isCardRow(adapter, position - 1)
            val isLastInCard = !isCardRow(adapter, position + 1)
            val tailBottomSpace = if (position == adapter.itemCount - 1) dp(parent.context, 40f).toInt() else 0
            outRect.set(
                rowHorizontalInset,
                if (isFirstInCard) groupTopSpace else 0,
                rowHorizontalInset,
                (if (isLastInCard) groupBottomSpace else 0) + tailBottomSpace,
            )
        }

        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter ?: return
            val childCount = parent.childCount
            if (childCount == 0) return

            val cardLeft = parent.paddingLeft + rowHorizontalInset.toFloat()
            val cardRight = (parent.width - parent.paddingRight - rowHorizontalInset).toFloat()
            if (cardRight <= cardLeft) return

            var index = 0
            while (index < childCount) {
                val firstChild = parent.getChildAt(index)
                val firstPos = parent.getChildAdapterPosition(firstChild)
                if (!isCardRow(adapter, firstPos)) {
                    index++
                    continue
                }

                var lastChild = firstChild
                var lastPos = firstPos
                var nextIndex = index + 1
                while (nextIndex < childCount) {
                    val candidate = parent.getChildAt(nextIndex)
                    val candidatePos = parent.getChildAdapterPosition(candidate)
                    if (candidatePos == lastPos + 1 && isCardRow(adapter, candidatePos)) {
                        lastChild = candidate
                        lastPos = candidatePos
                        nextIndex++
                    } else {
                        break
                    }
                }

                // WHY: When a grouped card extends beyond the top or bottom viewport edge during
                // scrolling, push the off-screen edge out by cornerRadius so rounded corners never
                // appear prematurely in the middle of an ongoing group.
                val startsAtGroupHead = !isCardRow(adapter, firstPos - 1)
                val endsAtGroupTail = !isCardRow(adapter, lastPos + 1)
                val top = firstChild.top.toFloat() - if (startsAtGroupHead) 0f else cornerRadius * 2f
                val bottom = lastChild.bottom.toFloat() + if (endsAtGroupTail) 0f else cornerRadius * 2f

                cardRect.set(cardLeft, top, cardRight, bottom)
                canvas.save()
                canvas.clipRect(
                    cardLeft - strokeWidth,
                    if (startsAtGroupHead) top - strokeWidth else 0f,
                    cardRight + strokeWidth,
                    if (endsAtGroupTail) bottom + strokeWidth else parent.height.toFloat(),
                )
                canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, fillPaint)
                val halfStroke = strokeWidth / 2f
                cardRect.inset(halfStroke, halfStroke)
                canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, strokePaint)
                canvas.restore()

                index = nextIndex
            }
        }

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter ?: return
            val left = parent.paddingLeft + rowHorizontalInset + dividerInset
            val right = parent.width - parent.paddingRight - rowHorizontalInset - dividerInset
            if (right <= left) return

            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (isCardRow(adapter, pos) && isCardRow(adapter, pos + 1)) {
                    val top = child.bottom.toFloat() - dividerHeight
                    canvas.drawRect(left, top, right, child.bottom.toFloat(), dividerPaint)
                }
            }
        }
    }
}
