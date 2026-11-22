package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.ClashDnsCandidates
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.SubscriptionConfigStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.SingBoxDnsServerParser

/**
 * Surge-styled quick manager for per-group Private Node DNS (`subscriptionNodeDns` /
 * `autoAdoptNodeDns`) and interactive DoH candidate selection, accessible directly from
 * the Main Screen `⋮` menu and `SubscriptionConfigActivity`.
 */
object PrivateDnsDialogHelper {

    private const val DIALOG_PADDING_DP = 20
    private const val INPUT_MIN_LINES = 3
    private const val INPUT_MAX_LINES = 7

    /**
     * Opens the Private DNS management dialog for [groupId] on [activity].
     *
     * @param activity Host activity providing lifecycle and UI context.
     * @param groupId Target [ProxyGroup] ID whose Private DNS settings are managed.
     * @param onSaved Optional callback invoked on the main thread after changes are persisted.
     */
    fun showManagerForGroup(
        activity: AppCompatActivity,
        groupId: Long,
        onSaved: (() -> Unit)? = null,
    ) {
        activity.lifecycleScope.launch {
            val group = withContext(Dispatchers.IO) {
                SagerDatabase.groupDao.getById(groupId)
            }
            if (group == null || activity.isFinishing || activity.isDestroyed) return@launch
            presentManagerDialog(activity, group, onSaved)
        }
    }

    /**
     * Opens the ordered multi-select DNS candidate picker dialog for a parsed [ClashDnsCandidates.Profile].
     *
     * @param context UI context for dialog construction.
     * @param profile Parsed DNS candidate profile.
     * @param initialEndpoints Previously configured DNS endpoints (newline-separated).
     * @param onSelected Callback receiving the chosen ordered DNS endpoints (newline-separated).
     */
    internal fun showCandidatePickerDialog(
        context: Context,
        profile: ClashDnsCandidates.Profile,
        initialEndpoints: String,
        onSelected: (String) -> Unit,
    ): AlertDialog {
        val pad = dp2px(DIALOG_PADDING_DP)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        fun addParagraph(text: CharSequence, isBold: Boolean = false): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(context.getColour(if (isBold) R.color.surge_text_primary else R.color.surge_text_secondary))
                textSize = if (isBold) 13.5f else 12.5f
                if (isBold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, pad / 4, 0, pad / 4)
            }.also(container::addView)

        addParagraph(context.getText(R.string.group_dns_candidates_help))
        if (profile.truncated) {
            addParagraph(context.getString(R.string.group_dns_candidates_truncated, ClashDnsCandidates.MAX_ROWS))
        }

        val selectableSet = profile.rows.mapNotNull { it.selectable }.toSet()
        val chosen = initialEndpoints.lineSequence()
            .map { it.trim() }
            .filter { it in selectableSet }
            .toCollection(linkedSetOf())
        val checkBoxes = ArrayList<Pair<String, CheckBox>>()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.group_dns_candidates_title)
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_dns_candidates_use, null)
            .create()

        fun refreshOrderLabels() {
            val orderByEndpoint = chosen.withIndex().associate { it.value to (it.index + 1) }
            for ((endpoint, checkBox) in checkBoxes) {
                val orderIndex = orderByEndpoint[endpoint]
                checkBox.isChecked = orderIndex != null
                checkBox.text = if (orderIndex == null) {
                    endpoint
                } else {
                    context.getString(R.string.group_dns_candidates_order, orderIndex, endpoint)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = chosen.isNotEmpty()
        }

        var previousCategory: ClashDnsCandidates.Category? = null
        for (row in profile.rows) {
            if (row.category != previousCategory) {
                addParagraph(row.category.key, isBold = true)
                previousCategory = row.category
            }
            row.policy?.let { addParagraph(context.getString(R.string.group_dns_candidates_policy, it)) }
            val endpoint = row.selectable
            if (endpoint != null) {
                val box = CheckBox(context).apply {
                    setTextColor(context.getColour(R.color.surge_text_primary))
                    setOnClickListener {
                        if (isChecked) chosen.add(endpoint) else chosen.remove(endpoint)
                        refreshOrderLabels()
                    }
                }
                container.addView(box)
                checkBoxes.add(endpoint to box)
            } else {
                val reasonRes = when (row.status) {
                    ClashDnsCandidates.Status.SELF_REFERENCE -> R.string.group_dns_candidates_self
                    ClashDnsCandidates.Status.LOCAL -> R.string.group_dns_candidates_local
                    ClashDnsCandidates.Status.UNSUPPORTED -> R.string.group_dns_candidates_unsupported
                    ClashDnsCandidates.Status.UNSAFE -> R.string.group_dns_candidates_unsafe
                    else -> R.string.group_dns_candidates_invalid
                }
                val unavailableText = if (row.label == null) {
                    context.getText(reasonRes)
                } else {
                    context.getString(R.string.group_dns_candidates_unavailable, row.label, context.getText(reasonRes))
                }
                addParagraph(unavailableText)
            }
        }

        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (chosen.isNotEmpty()) {
                onSelected(chosen.joinToString("\n"))
            }
            dialog.dismiss()
        }
        refreshOrderLabels()
        return dialog
    }

    private fun presentManagerDialog(
        activity: AppCompatActivity,
        group: ProxyGroup,
        onSaved: (() -> Unit)?,
    ) {
        val pad = dp2px(DIALOG_PADDING_DP)
        val isSubscription = group.type == GroupType.SUBSCRIPTION
        val subscription = group.subscription
        val initialManualDns = group.manualNodeDns().orEmpty()
        val adoptedList = subscription?.adoptedNodeDns
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }

        val statusSummary = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(activity.getColour(R.color.surge_text_secondary))
            setPadding(0, 0, 0, dp2px(8))
        }
        root.addView(statusSummary)

        val autoAdoptSwitch = SwitchCompat(activity).apply {
            text = activity.getString(R.string.subscription_auto_adopt_node_dns)
            setTextColor(activity.getColour(R.color.surge_text_primary))
            isChecked = subscription?.autoAdoptNodeDns == true
            setPadding(0, dp2px(4), 0, dp2px(8))
        }
        if (isSubscription) {
            root.addView(autoAdoptSwitch)
        }

        val manualLabel = TextView(activity).apply {
            text = activity.getString(R.string.private_dns_manual_input_label)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColour(R.color.surge_text_primary))
            setPadding(0, dp2px(4), 0, dp2px(4))
        }
        root.addView(manualLabel)

        val manualInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = INPUT_MIN_LINES
            maxLines = INPUT_MAX_LINES
            gravity = Gravity.TOP or Gravity.START
            hint = activity.getString(R.string.private_dns_manual_input_hint)
            setText(initialManualDns)
            textSize = 13f
        }
        root.addView(manualInput)

        fun refreshStatusLabel() {
            val manualCount = manualInput.text.toString()
                .lineSequence()
                .count { it.trim().isNotEmpty() }
            statusSummary.text = when {
                manualCount > 0 -> activity.getString(R.string.private_dns_status_manual, manualCount)
                isSubscription && autoAdoptSwitch.isChecked && adoptedList.isNotEmpty() ->
                    activity.getString(
                        R.string.private_dns_status_adopted_detail,
                        adoptedList.size,
                        adoptedList.joinToString(" , "),
                    )
                isSubscription && autoAdoptSwitch.isChecked ->
                    activity.getString(R.string.group_node_dns_waiting)
                else -> activity.getString(R.string.group_node_dns_default)
            }
        }
        autoAdoptSwitch.setOnCheckedChangeListener { _, _ -> refreshStatusLabel() }
        refreshStatusLabel()

        if (isSubscription) {
            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, dp2px(8), 0, 0)
            }
            val extractBtn = MaterialButton(
                activity,
                null,
                R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = activity.getString(R.string.subscription_config_extract_dns)
                isAllCaps = false
                textSize = 12f
            }
            val viewConfigBtn = MaterialButton(
                activity,
                null,
                R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = activity.getString(R.string.subscription_config_view_edit_short)
                isAllCaps = false
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp2px(8) }
                layoutParams = lp
            }
            actionsRow.addView(extractBtn)
            actionsRow.addView(viewConfigBtn)
            root.addView(actionsRow)

            extractBtn.setOnClickListener {
                loadAndPickSubscriptionDns(activity, group, manualInput.text.toString()) { chosen ->
                    manualInput.setText(chosen)
                    refreshStatusLabel()
                }
            }
            viewConfigBtn.setOnClickListener {
                activity.startActivity(
                    Intent(activity, SubscriptionConfigActivity::class.java).apply {
                        putExtra(SubscriptionConfigActivity.EXTRA_GROUP_ID, group.id)
                        putExtra(SubscriptionConfigActivity.EXTRA_FOCUS_DNS, true)
                    }
                )
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.private_dns_manager_title, group.displayName()))
            .setView(ScrollView(activity).apply { addView(root) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.private_dns_clear_manual, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            manualInput.setText("")
            refreshStatusLabel()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val rawManual = manualInput.text.toString()
            if (!validateManualDnsLines(activity, rawManual)) return@setOnClickListener
            val autoAdoptChecked = isSubscription && autoAdoptSwitch.isChecked
            savePrivateDnsSettings(activity, group.id, rawManual, autoAdoptChecked) {
                dialog.dismiss()
                onSaved?.invoke()
            }
        }
    }

    internal fun validateManualDnsLines(context: Context, rawText: String): Boolean {
        val lines = rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return true
        return runCatching {
            lines.forEach { endpoint ->
                SingBoxDnsServerParser.parseGroupNodeDns(endpoint, "dns-group-validate")
            }
        }.onFailure {
            Toast.makeText(context, R.string.group_node_dns_invalid, Toast.LENGTH_SHORT).show()
        }.isSuccess
    }

    private fun savePrivateDnsSettings(
        activity: AppCompatActivity,
        groupId: Long,
        rawManualDns: String,
        autoAdoptEnabled: Boolean,
        onComplete: () -> Unit,
    ) {
        runOnDefaultDispatcher {
            val latestGroup = SagerDatabase.groupDao.getById(groupId) ?: return@runOnDefaultDispatcher
            latestGroup.saveManualNodeDns(rawManualDns)
            if (latestGroup.type == GroupType.SUBSCRIPTION) {
                latestGroup.subscription?.let { sub ->
                    sub.autoAdoptNodeDns = autoAdoptEnabled
                    if (autoAdoptEnabled && sub.adoptedNodeDns.isNullOrBlank()) {
                        SubscriptionConfigStore.read(groupId)?.let { cached ->
                            val autoCandidates = RawUpdater.extractClashProxyServerNameservers(cached.content)
                            if (autoCandidates.isNotEmpty()) {
                                sub.adoptedNodeDns = autoCandidates.joinToString("\n")
                            }
                        }
                    }
                }
            }
            GroupManager.updateGroup(latestGroup)
            onMainDispatcher {
                if (DataStore.serviceState.connected) {
                    SagerNet.reloadService()
                }
                Toast.makeText(activity, R.string.private_dns_saved_toast, Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }

    private fun loadAndPickSubscriptionDns(
        activity: AppCompatActivity,
        group: ProxyGroup,
        currentManual: String,
        onPicked: (String) -> Unit,
    ) {
        val link = group.subscription?.link.orEmpty()
        if (link.isBlank() && !SubscriptionConfigStore.exists(group.id)) {
            Toast.makeText(activity, R.string.group_dns_candidates_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, R.string.group_dns_candidates_loading, Toast.LENGTH_SHORT).show()
        val customUa = group.subscription?.customUserAgent?.trim()?.takeIf { it.isNotEmpty() }
        val forceProxy = DataStore.serviceState.connected
        activity.lifecycleScope.launch {
            val profile = try {
                withContext(Dispatchers.IO) {
                    if (link.isNotBlank()) {
                        RawUpdater.inspectSubscriptionDns(
                            link = link,
                            userAgents = RawUpdater.DNS_CANDIDATE_USER_AGENTS,
                            forceProxy = forceProxy,
                            customUserAgent = customUa,
                            groupId = group.id,
                            preferCached = true,
                        )
                    } else {
                        val cached = SubscriptionConfigStore.read(group.id)?.content.orEmpty()
                        ClashDnsCandidates.parseOrScanInteractive(cached)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when {
                profile == null -> Toast.makeText(activity, R.string.group_dns_candidates_failed, Toast.LENGTH_SHORT).show()
                !profile.hasDns || profile.rows.isEmpty() ->
                    Toast.makeText(activity, R.string.group_dns_candidates_no_dns_meta, Toast.LENGTH_LONG).show()
                else -> showCandidatePickerDialog(activity, profile, currentManual, onPicked)
            }
        }
    }
}
