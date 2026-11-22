package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.ClashDnsCandidates
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference
    private var savedAdoptedDns: Boolean = false
    private var savedSubscriptionLink: String = ""
    private var loadingDnsCandidates = false
    private var dnsCandidatesDialog: AlertDialog? = null

    /** Populate the editor from Room, including manual DNS saved by older releases. */
    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        DataStore.landingProxyTmp = if (landingProxy >= 0) 3 else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link!!
        DataStore.subscriptionForceResolve = subscription.forceResolve!!
        DataStore.subscriptionDeduplication = subscription.deduplication!!
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly!!
        DataStore.subscriptionUserAgent = subscription.customUserAgent!!
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate!!
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay!!
        DataStore.subscriptionNodeDns = manualNodeDns().orEmpty()
        DataStore.subscriptionAutoAdoptNodeDns = subscription.autoAdoptNodeDns == true
        savedAdoptedDns = !subscription.adoptedNodeDns.isNullOrBlank()
        savedSubscriptionLink = subscription.link.orEmpty()
    }

    /** Store the group preference independently of the subscription source. */
    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector

        frontProxy = if (DataStore.frontProxyTmp == 3) DataStore.frontProxy else -1
        landingProxy = if (DataStore.landingProxyTmp == 3) DataStore.landingProxy else -1

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                if (link != DataStore.subscriptionLink) {
                    // An adopted resolver belongs to the exact old source, not the group name.
                    adoptedNodeDns = ""
                    subscriptionUserinfo = ""
                }
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                autoAdoptNodeDns = DataStore.subscriptionAutoAdoptNodeDns
            }
        } else {
            // A basic group has no subscription DNS state; its manual setting still applies.
            subscription = null
        }
        saveManualNodeDns(DataStore.subscriptionNodeDns)
    }

    fun needSave(): Boolean {
        if (!DataStore.dirty) return false
        return true
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddFront.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddLanding.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }

        // 多端点输入框需要多行：AndroidX 的 EditTextPreference 对话框默认单行，
        // 多行内容会被压成一行显示，看起来像"只有一个端点"。
        findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine(false)
            editText.minLines = 3
            editText.maxLines = 8
            editText.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            // Protect credential-bearing DoH URLs against screenshots/recents thumbnails.
            editText.post {
                val dialogFragment = supportFragmentManager.findFragmentByTag(
                    "androidx.preference.PreferenceFragment.DIALOG"
                ) as? androidx.fragment.app.DialogFragment
                dialogFragment?.dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.setOnPreferenceChangeListener { _, newValue ->
            // 多端点容错：每行一个端点，第一行为主端点，其余为镜像。任一行非法即拒绝保存。
            val lines = (newValue as? String).orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            lines.isEmpty() || runCatching {
                lines.forEach {
                    moe.matsuri.nb4a.SingBoxDnsServerParser.parseGroupNodeDns(it, "dns-group-validate")
                }
            }.onFailure {
                // Resolver paths can contain credentials; errors must not echo the entered URL.
                Toast.makeText(this@GroupSettingsActivity, R.string.group_node_dns_invalid, Toast.LENGTH_SHORT).show()
            }.isSuccess
        }
        findPreference<Preference>("groupDnsCandidates")?.setOnPreferenceClickListener {
            // Reset any prior single-client UA override when starting a fresh inspection.
            dnsCandidateUserAgents = null
            loadDnsCandidates(this)
            true
        }
        findPreference<Preference>("editSubscriptionConfig")?.setOnPreferenceClickListener {
            val currentGroupId = DataStore.editingId
            if (currentGroupId <= 0L) {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    R.string.subscription_config_save_group_first,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                startActivity(
                    Intent(this@GroupSettingsActivity, SubscriptionConfigActivity::class.java).apply {
                        putExtra(SubscriptionConfigActivity.EXTRA_GROUP_ID, currentGroupId)
                    }
                )
            }
            true
        }
        updateNodeDnsStatus(this)
    }

    private fun loadDnsCandidates(fragment: PreferenceFragmentCompat) {
        if (loadingDnsCandidates) return
        val link = DataStore.subscriptionLink
        if (link.isBlank()) return
        val isConnected = DataStore.serviceState.connected
        val confirm = !isConnected &&
            (link.startsWith("http://", ignoreCase = true) || DataStore.subscriptionUpdateWhenConnectedOnly)
        if (confirm) {
            dnsCandidatesDialog = AlertDialog.Builder(this).setMessage(R.string.update_subscription_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.group_dns_candidates_load) { _, _ ->
                    if (link == DataStore.subscriptionLink) {
                        fetchDnsCandidates(fragment, link, forceProxy = DataStore.serviceState.connected)
                    }
                }.show()
        } else {
            // Lock forceProxy when connected so a mid-loop VPN drop fails closed instead of leaking direct.
            fetchDnsCandidates(fragment, link, forceProxy = isConnected)
        }
    }

    /** Populated when the user picks a specific client UA for one inspection. */
    private var dnsCandidateUserAgents: List<String>? = null

    /** The preferences fragment that owns the DNS row, reused by follow-up dialogs. */
    private var dnsCandidateFragment: PreferenceFragmentCompat? = null

    private fun fetchDnsCandidates(
        fragment: PreferenceFragmentCompat,
        link: String,
        forceProxy: Boolean,
    ) {
        dnsCandidateFragment = fragment
        loadingDnsCandidates = true
        updateNodeDnsStatus(fragment)
        val customUserAgent = DataStore.subscriptionUserAgent.trim().takeIf { it.isNotEmpty() }
        val userAgents = dnsCandidateUserAgents ?: RawUpdater.DNS_CANDIDATE_USER_AGENTS
        val currentGroupId = DataStore.editingId
        lifecycleScope.launch {
            val profile = try {
                withContext(Dispatchers.IO) {
                    RawUpdater.inspectSubscriptionDns(
                        link = link,
                        userAgents = userAgents,
                        forceProxy = forceProxy,
                        customUserAgent = if (dnsCandidateUserAgents == null) customUserAgent else null,
                        groupId = currentGroupId,
                        preferCached = dnsCandidateUserAgents == null && link == savedSubscriptionLink,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Don't expose the request URL or a credential-bearing YAML exception.
            } finally {
                loadingDnsCandidates = false
                if (!isFinishing && !isDestroyed) updateNodeDnsStatus(fragment)
            }
            if (isFinishing || isDestroyed || link != DataStore.subscriptionLink ||
                DataStore.groupType != GroupType.SUBSCRIPTION) return@launch
            val isRemoteSubscription = !link.startsWith("content://")
            when {
                profile == null -> showDnsCandidatesMessage(
                    R.string.group_dns_candidates_failed,
                    allowClientSwitch = isRemoteSubscription,
                )
                profile.malformed -> showDnsCandidatesMessage(R.string.group_dns_candidates_malformed)
                !profile.hasDns -> showDnsCandidatesMessage(
                    if (!isRemoteSubscription) R.string.group_dns_candidates_no_dns_file
                    else R.string.group_dns_candidates_no_dns_meta,
                    allowClientSwitch = isRemoteSubscription,
                )
                profile.rows.isEmpty() -> showDnsCandidatesMessage(R.string.group_dns_candidates_empty)
                else -> showDnsCandidatesPicker(fragment, link, profile)
            }
        }
    }

    /**
     * Show an inspection result. When the provider answered with a nodes-only body, offer a
     * client switch: some providers only serialize the DNS section for specific clients, and
     * guessing on their behalf is never safe.
     */
    private fun showDnsCandidatesMessage(message: Int, allowClientSwitch: Boolean = false) {
        val builder = AlertDialog.Builder(this).setTitle(R.string.group_dns_candidates_title)
            .setMessage(message).setPositiveButton(android.R.string.ok, null)
        if (allowClientSwitch) {
            builder.setNeutralButton(R.string.group_dns_candidates_pick_client) { _, _ ->
                showDnsClientPicker()
            }
        }
        dnsCandidatesDialog = builder.show()
    }

    /** Let the user choose which client the subscription should think is downloading it. */
    private fun showDnsClientPicker() {
        val labels = arrayOf(
            getString(R.string.group_dns_client_flclash),
            getString(R.string.group_dns_client_verge),
            getString(R.string.group_dns_client_meta),
            getString(R.string.group_dns_client_nekopro),
        )
        val agents = RawUpdater.DNS_CANDIDATE_USER_AGENTS
        dnsCandidatesDialog = AlertDialog.Builder(this)
            .setTitle(R.string.group_dns_candidates_pick_client)
            .setItems(labels) { _, which ->
                dnsCandidateUserAgents = listOfNotNull(agents.getOrNull(which))
                showCandidatesAfterClientPick()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCandidatesAfterClientPick() {
        val fragment = dnsCandidateFragment ?: return
        // Route through loadDnsCandidates so updateWhenConnectedOnly, http:// warnings,
        // and the loadingDnsCandidates re-entrancy guard are enforced consistently.
        loadDnsCandidates(fragment)
    }

    private fun showDnsCandidatesPicker(
        fragment: PreferenceFragmentCompat, link: String, profile: ClashDnsCandidates.Profile,
    ) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun paragraph(text: CharSequence) = TextView(this).apply {
            this.text = text
            setPadding(0, padding / 4, 0, padding / 4)
        }.also { content.addView(it) }
        paragraph(getText(R.string.group_dns_candidates_help))
        if (profile.truncated) paragraph(getString(R.string.group_dns_candidates_truncated, ClashDnsCandidates.MAX_ROWS))

        val selectable = profile.rows.mapNotNull { it.selectable }.toSet()
        val selected = DataStore.subscriptionNodeDns.lineSequence().map { it.trim() }
            .filter { it in selectable }.toCollection(linkedSetOf())
        val boxes = mutableListOf<Pair<String, CheckBox>>()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.group_dns_candidates_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_dns_candidates_use, null)
            .create()
        fun updateOrder() {
            val order = selected.withIndex().associate { it.value to it.index + 1 }
            for ((endpoint, box) in boxes) {
                val index = order[endpoint]
                box.isChecked = index != null
                box.text = if (index == null) endpoint else getString(R.string.group_dns_candidates_order, index, endpoint)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.isNotEmpty()
        }

        var previousCategory: ClashDnsCandidates.Category? = null
        for (row in profile.rows) {
            if (row.category != previousCategory) {
                paragraph(row.category.key).setTypeface(null, android.graphics.Typeface.BOLD)
                previousCategory = row.category
            }
            row.policy?.let { paragraph(getString(R.string.group_dns_candidates_policy, it)) }
            val endpoint = row.selectable
            if (endpoint != null) {
                val box = CheckBox(this).apply {
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
                paragraph(if (label == null) getText(reason) else getString(R.string.group_dns_candidates_unavailable, label, getText(reason)))
            }
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected.isNotEmpty() && DataStore.subscriptionLink == link && DataStore.groupType == GroupType.SUBSCRIPTION) {
                fragment.findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.text = selected.joinToString("\n")
                DataStore.dirty = true
                updateNodeDnsStatus(fragment)
            }
            dialog.dismiss()
        }
        updateOrder()
        dnsCandidatesDialog = dialog
    }

    /** Show the source of the yet-to-be-saved DNS setting, never its credential-bearing URL. */
    private fun updateNodeDnsStatus(fragment: PreferenceFragmentCompat) {
        val manual = DataStore.subscriptionNodeDns.isNotBlank()
        fragment.findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.apply {
            // 摘要显示端点数，让用户直接看出主备是否都保存了。
            val count = DataStore.subscriptionNodeDns.lineSequence().count { it.trim().isNotEmpty() }
            setSummary(
                if (manual) resources.getQuantityString(
                    R.plurals.group_node_dns_count, count, count,
                ) else getString(R.string.group_dns_multi_hint)
            )
        }
        val status = when {
            manual -> R.string.group_node_dns_manual
            !DataStore.subscriptionAutoAdoptNodeDns -> R.string.group_node_dns_default
            savedAdoptedDns && savedSubscriptionLink == DataStore.subscriptionLink -> R.string.group_node_dns_adopted
            else -> R.string.group_node_dns_waiting
        }
        fragment.findPreference<Preference>("subscriptionNodeDnsStatus")?.setSummary(status)
        fragment.findPreference<Preference>("groupDnsCandidates")?.apply {
            isEnabled = !loadingDnsCandidates && DataStore.subscriptionLink.isNotBlank()
            setSummary(if (loadingDnsCandidates) R.string.group_dns_candidates_loading else R.string.group_dns_candidates_summary)
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                        return@onMainDispatcher
                    }
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }

        } else {
            DataStore.profileCacheStore.registerChangeListener(this)
            runOnDefaultDispatcher {
                val sub = SagerDatabase.groupDao.getById(DataStore.editingId)?.subscription
                onMainDispatcher {
                    if (isFinishing || isDestroyed) return@onMainDispatcher
                    savedAdoptedDns = !sub?.adoptedNodeDns.isNullOrBlank()
                    savedSubscriptionLink = sub?.link.orEmpty()
                    val existing = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat
                    if (existing != null) {
                        updateNodeDnsStatus(existing)
                    } else if (!supportFragmentManager.isStateSaved) {
                        // Recover if Activity rotated before the initial async Room load committed the fragment.
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.settings, MyPreferenceFragmentCompat())
                            .commit()
                    }
                }
            }
        }
    }

    /** Save the user's explicit resolver without performing a subscription refresh. */
    suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(editingId)
            if (entity != null) GroupManager.updateGroup(entity.apply { serialize() })
        }
        finish()
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        dnsCandidatesDialog?.dismiss()
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
        if (key == Key.SUBSCRIPTION_LINK) {
            // Reset any manually chosen inspection UA when the subscription URL changes.
            dnsCandidateUserAgents = null
        }
        if (key == Key.SUBSCRIPTION_NODE_DNS || key == Key.SUBSCRIPTION_AUTO_ADOPT_NODE_DNS || key == Key.SUBSCRIPTION_LINK) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    val fragment = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat
                    if (fragment != null) updateNodeDnsStatus(fragment)
                }
            }
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            SurgePreferenceStyler.applyTo(this)
            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxy = profile.id
            onMainDispatcher {
                frontProxyPreference.value = "3"
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = "3"
            }
        }
    }

}