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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
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

        findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.setOnPreferenceChangeListener { _, newValue ->
            // 多端点容错：每行一个端点，第一行为主端点，其余为镜像。任一行非法即拒绝保存。
            val lines = (newValue as? String)?.trim().orEmpty()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            lines.isEmpty() || runCatching {
                lines.forEach {
                    moe.matsuri.nb4a.SingBoxDnsServerParser.parseGroupNodeDns(it, "dns-group-validate")
                }
            }.onFailure {
                // Resolver paths can contain credentials; errors must not echo the entered URL.
                Toast.makeText(this@GroupSettingsActivity, R.string.group_node_dns_invalid, Toast.LENGTH_SHORT).show()
            }.isSuccess
        }
        updateNodeDnsStatus(this)
    }

    /** Show the source of the yet-to-be-saved DNS setting, never its credential-bearing URL. */
    private fun updateNodeDnsStatus(fragment: PreferenceFragmentCompat) {
        val manual = DataStore.subscriptionNodeDns.isNotBlank()
        fragment.findPreference<EditTextPreference>(Key.SUBSCRIPTION_NODE_DNS)?.setSummary(
            if (manual) R.string.group_node_dns_manual else R.string.subscription_node_dns_sum,
        )
        val status = when {
            manual -> R.string.group_node_dns_manual
            !DataStore.subscriptionAutoAdoptNodeDns -> R.string.group_node_dns_default
            savedAdoptedDns && savedSubscriptionLink == DataStore.subscriptionLink -> R.string.group_node_dns_adopted
            else -> R.string.group_node_dns_waiting
        }
        fragment.findPreference<Preference>("subscriptionNodeDnsStatus")?.setSummary(status)
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
                    savedAdoptedDns = !sub?.adoptedNodeDns.isNullOrBlank()
                    savedSubscriptionLink = sub?.link.orEmpty()
                    (supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat)
                        ?.let { updateNodeDnsStatus(it) }
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
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
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