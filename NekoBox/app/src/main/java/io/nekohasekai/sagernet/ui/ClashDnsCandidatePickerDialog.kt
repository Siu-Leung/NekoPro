package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.group.ClashDnsCandidates

/**
 * Shared dialog allowing ordered multi-selection of validated Clash DNS candidate endpoints.
 */
internal object ClashDnsCandidatePickerDialog {

    fun show(
        context: Context,
        profile: ClashDnsCandidates.Profile,
        preselected: Collection<String>,
        onApply: (List<String>) -> Unit,
    ): AlertDialog {
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun paragraph(text: CharSequence) = TextView(context).apply {
            this.text = text
            setPadding(0, padding / 4, 0, padding / 4)
        }.also { content.addView(it) }

        paragraph(context.getText(R.string.group_dns_candidates_help))
        if (profile.truncated) {
            paragraph(context.getString(R.string.group_dns_candidates_truncated, ClashDnsCandidates.MAX_ROWS))
        }

        val selectable = profile.rows.mapNotNull { it.selectable }.toSet()
        val selected = preselected.map { it.trim() }.filter { it in selectable }.toCollection(linkedSetOf())
        val boxes = mutableListOf<Pair<String, CheckBox>>()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.group_dns_candidates_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_dns_candidates_use, null)
            .create()

        fun updateOrder() {
            val order = selected.withIndex().associate { it.value to it.index + 1 }
            for ((endpoint, box) in boxes) {
                val index = order[endpoint]
                box.isChecked = index != null
                box.text = if (index == null) {
                    endpoint
                } else {
                    context.getString(R.string.group_dns_candidates_order, index, endpoint)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.isNotEmpty()
        }

        var previousCategory: ClashDnsCandidates.Category? = null
        for (row in profile.rows) {
            if (row.category != previousCategory) {
                paragraph(row.category.key).setTypeface(null, Typeface.BOLD)
                previousCategory = row.category
            }
            row.policy?.let { paragraph(context.getString(R.string.group_dns_candidates_policy, it)) }
            val endpoint = row.selectable
            if (endpoint != null) {
                val box = CheckBox(context).apply {
                    setOnClickListener {
                        if (isChecked) selected.add(endpoint) else selected.remove(endpoint)
                        updateOrder()
                    }
                }
                content.addView(box)
                boxes.add(endpoint to box)
            } else {
                val reason = when (row.status) {
                    ClashDnsCandidates.Status.SELF_REFERENCE -> R.string.group_dns_candidates_self
                    ClashDnsCandidates.Status.LOCAL -> R.string.group_dns_candidates_local
                    ClashDnsCandidates.Status.UNSUPPORTED -> R.string.group_dns_candidates_unsupported
                    ClashDnsCandidates.Status.UNSAFE -> R.string.group_dns_candidates_unsafe
                    else -> R.string.group_dns_candidates_invalid
                }
                val label = row.label
                val message = if (label == null) {
                    context.getText(reason)
                } else {
                    context.getString(R.string.group_dns_candidates_unavailable, label, context.getText(reason))
                }
                paragraph(message)
            }
        }

        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosen = selected.toList()
            dialog.dismiss()
            if (chosen.isNotEmpty()) {
                onApply(chosen)
            }
        }
        updateOrder()
        return dialog
    }
}
