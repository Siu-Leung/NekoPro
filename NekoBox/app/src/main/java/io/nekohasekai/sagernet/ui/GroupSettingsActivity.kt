package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.ClashDnsCandidates
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.SubscriptionRawStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    if (id > 0L) SubscriptionRawStore.delete(id)
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
            if (id > 0L) SubscriptionRawStore.delete(id)
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
        findPreference<Preference>("groupRawConfig")?.setOnPreferenceClickListener {
            openRawSubscriptionEditor(this)
            true
        }
        updateNodeDnsStatus(this)
    }

    private fun openRawSubscriptionEditor(fragment: PreferenceFragmentCompat) {
        val editingId = DataStore.editingId
        val link = DataStore.subscriptionLink.trim()
        if (editingId > 0L) {
            if (!SubscriptionRawStore.exists(editingId) && link.isEmpty()) {
                Toast.makeText(this, R.string.raw_sub_enter_link_first, Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(
                SubscriptionRawActivity.intent(
                    context = this,
                    groupId = editingId,
                    fetchIfEmpty = true,
                    linkOverride = link.takeIf { it.isNotEmpty() },
                )
            )
            return
        }

        if (link.isEmpty()) {
            Toast.makeText(this, R.string.raw_sub_enter_link_first, Toast.LENGTH_SHORT).show()
            return
        }

        dnsCandidatesDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.raw_sub_save_group_first_title)
            .setMessage(R.string.raw_sub_save_group_first_msg)
            .setPositiveButton(R.string.raw_sub_fetch_now) { _, _ ->
                lifecycleScope.launch {
                    val created = withContext(Dispatchers.IO) {
                        GroupManager.createGroup(ProxyGroup().apply { serialize() })
                    }
                    if (isFinishing || isDestroyed) return@launch
                    DataStore.editingId = created.id
                    DataStore.dirty = false
                    savedAdoptedDns = !created.subscription?.adoptedNodeDns.isNullOrBlank()
                    savedSubscriptionLink = created.subscription?.link.orEmpty()
                    updateNodeDnsStatus(fragment)
                    startActivity(
                        SubscriptionRawActivity.intent(
                            context = this@GroupSettingsActivity,
                            groupId = created.id,
                            fetchIfEmpty = true,
                            linkOverride = link,
                        )
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        lifecycleScope.launch {
            val profile = try {
                withContext(Dispatchers.IO) {
                    RawUpdater.inspectSubscriptionDns(
                        link = link,
                        userAgents = userAgents,
                        forceProxy = forceProxy,
                        customUserAgent = if (dnsCandidateUserAgents == null) customUserAgent else null,
                        groupId = DataStore.editingId,
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
        val builder = MaterialAlertDialogBuilder(this).setTitle(R.string.group_dns_candidates_title)
            .setMessage(message).setPositiveButton(android.R.string.ok, null)
        if (allowClientSwitch) {
            builder.setNeutralButton(R.string.group_dns_candidates_pick_client) { _, _ ->
                showDnsClientPicker()
            }
        }
        if (DataStore.editingId > 0L && (SubscriptionRawStore.exists(DataStore.editingId) || DataStore.subscriptionLink.isNotBlank())) {
            builder.setNegativeButton(R.string.raw_sub_view_raw) { _, _ ->
                dnsCandidateFragment?.let { openRawSubscriptionEditor(it) }
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
        val current = DataStore.subscriptionNodeDns.lineSequence().map { it.trim() }.toList()
        dnsCandidatesDialog = ClashDnsCandidatePickerDialog.show(
            context = this,
            profile = profile,
            preselected = current,
        ) { chosen ->
            if (DataStore.subscriptionLink == link && DataStore.groupType == GroupType.SUBSCRIPTION) {
                fragment.findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.text = chosen.joinToString("\n")
                DataStore.dirty = true
                updateNodeDnsStatus(fragment)
            }
        }
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
        updateRawConfigSummary(fragment)
    }

    private fun updateRawConfigSummary(fragment: PreferenceFragmentCompat) {
        val rawPref = fragment.findPreference<Preference>("groupRawConfig") ?: return
        val editingId = DataStore.editingId
        if (editingId <= 0L) {
            rawPref.setSummary(R.string.raw_sub_pref_empty)
            return
        }
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) {
                SubscriptionRawStore.readMeta(editingId)
            }
            if (isFinishing || isDestroyed || editingId != DataStore.editingId) return@launch
            if (meta == null) {
                rawPref.setSummary(R.string.raw_sub_pref_empty)
            } else {
                val formatLabel = when (meta.format) {
                    SubscriptionRawStore.RawFormat.CLASH_YAML -> getString(R.string.raw_sub_format_yaml)
                    SubscriptionRawStore.RawFormat.JSON -> getString(R.string.raw_sub_format_json)
                    SubscriptionRawStore.RawFormat.PROXY_LIST -> getString(R.string.raw_sub_format_list)
                }
                val sizeText = Formatter.formatFileSize(this@GroupSettingsActivity, meta.byteSize.toLong())
                val timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(meta.updatedAtMillis))
                rawPref.summary = getString(R.string.raw_sub_pref_cached, formatLabel, sizeText, timeText)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val fragment = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat
            ?: return
        updateRawConfigSummary(fragment)
        val editingId = DataStore.editingId
        if (editingId > 0L) {
            lifecycleScope.launch {
                val group = withContext(Dispatchers.IO) {
                    SagerDatabase.groupDao.getById(editingId)
                } ?: return@launch
                if (isFinishing || isDestroyed || editingId != DataStore.editingId) return@launch
                savedAdoptedDns = !group.subscription?.adoptedNodeDns.isNullOrBlank()
                if (!DataStore.dirty) {
                    val savedManual = group.manualNodeDns().orEmpty()
                    if (savedManual != DataStore.subscriptionNodeDns) {
                        val wasDirty = DataStore.dirty
                        fragment.findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.text = savedManual
                        DataStore.dirty = wasDirty
                    }
                }
                updateNodeDnsStatus(fragment)
            }
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