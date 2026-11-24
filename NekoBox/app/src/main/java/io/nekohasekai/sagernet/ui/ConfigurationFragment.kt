package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ipinfo.AddressRedactor
import io.nekohasekai.sagernet.ipinfo.IpInfoStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.SpeedTestDirection
import io.nekohasekai.sagernet.SpeedTestOutcome
import io.nekohasekai.sagernet.bg.proto.AndroidSpeedTestSession
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.proto.completedSpeedTestCount
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    /**
     * Tracks the currently running test (speed, TCPing, or URL test) so forced restarts
     * and Fragment teardown cancel any active test type without leaking dialogs, jobs,
     * or notifications.
     */
    private class ActiveTestHandle(
        val token: Int,
        @Volatile var dialog: AlertDialog? = null,
        @Volatile var notification: ConnectionTestNotification? = null,
        @Volatile var isHidden: Boolean = false,
        private val onCancel: () -> Unit,
    ) {
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun cancel(dismissDialog: Boolean = true) {
            if (!cancelled.compareAndSet(false, true)) return
            runCatching { onCancel() }.onFailure { Logs.w(it) }
            runCatching { notification?.updateNotification(0, 0, true) }.onFailure { Logs.w(it) }
            notification = null
            if (dismissDialog) {
                runCatching { dialog?.dismiss() }.onFailure { Logs.w(it) }
            }
            dialog = null
            isHidden = false
        }

        fun restoreIfHidden() {
            if (!isHidden || cancelled.get()) return
            isHidden = false
            runCatching { notification?.updateNotification(0, 0, true) }.onFailure { Logs.w(it) }
            notification = null
            runCatching { dialog?.show() }.onFailure { Logs.w(it) }
        }
    }

    @Volatile
    private var activeTestHandle: ActiveTestHandle? = null

    companion object {
        private const val GROUP_IP_PROBE_CONCURRENCY = 3
        private const val DNS_PROBE_TIMEOUT_MS = 6_000
        private const val OWNER_PROBE_TIMEOUT_MS = 8_000
        private const val EXIT_PROBE_TIMEOUT_MS = 10_000
    }

    private var ipInfoJob: Job? = null
    private var ipInfoDialog: AlertDialog? = null
    private var lastIpDisplayOptions: Pair<Boolean, Boolean>? = null

    @Volatile
    internal var probingProxyId: Long? = null

    private var groupTabDivider: View? = null
    private var surgeSearchBar: View? = null
    private var surgeSearchInput: android.widget.EditText? = null
    private var surgeSearchClose: View? = null
    private var surgeStatusCard: View? = null
    private var surgeStatusDot: View? = null
    private var surgeActiveProfile: TextView? = null
    private var surgeActiveIpSummary: TextView? = null
    private var surgeRouteModeChip: TextView? = null

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }

        override fun onPageSelected(position: Int) {
            val activeQuery = surgeSearchInput?.text?.toString().orEmpty()
            if (activeQuery.isNotEmpty()) {
                getCurrentGroupFragment()?.adapter?.filter(activeQuery)
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WHY: Earlier builds omitted app:defaultValue="true" on showNodeIpOwner in global_preferences.xml,
        // causing SettingsFragment initialization to persist false into PublicDatabase and hide all topology UI.
        if (!DataStore.topologyDisplayRestoredV1) {
            DataStore.showNodeIpOwner = true
            DataStore.topologyDisplayRestoredV1 = true
        }

        // WHY: Pre-rc3 builds could have legacy unverified exitIp strings in SQLite; scrub once
        // so every persisted exitIp/exitProvider is guaranteed to come from strict proxy probing.
        if (!DataStore.ipExitStrictMigrationDone) {
            runOnDefaultDispatcher {
                runCatching {
                    SagerDatabase.proxyDao.clearLegacyExitIpInfo()
                    DataStore.ipExitStrictMigrationDone = true
                }.onFailure { Logs.w(it) }
            }
        }

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
            updatePrivacyMaskMenuItem()
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        surgeSearchBar = view.findViewById(R.id.surge_search_bar)
        surgeSearchInput = view.findViewById(R.id.surge_search_input)
        surgeSearchClose = view.findViewById(R.id.surge_search_close)
        surgeSearchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                getCurrentGroupFragment()?.adapter?.filter(s?.toString().orEmpty())
            }
        })
        surgeSearchClose?.setOnClickListener {
            if (select && !surgeSearchInput?.text.isNullOrEmpty()) {
                surgeSearchInput?.setText("")
            } else {
                closeInlineNodeSearch()
            }
        }
        // In profile-picker mode (`select == true`), keep the inline search bar readily available.
        surgeSearchBar?.isVisible = select

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        groupTabDivider = view.findViewById(R.id.group_tab_divider)
        surgeStatusCard = view.findViewById(R.id.surge_status_card)
        surgeStatusDot = view.findViewById(R.id.surge_status_dot)
        surgeActiveProfile = view.findViewById(R.id.surge_active_profile)
        surgeActiveIpSummary = view.findViewById(R.id.surge_active_ip_summary)
        surgeRouteModeChip = view.findViewById(R.id.surge_route_mode_chip)

        surgeStatusCard?.isGone = select
        if (!select) {
            surgeRouteModeChip?.setOnClickListener {
                val nextMode = if (DataStore.routeMode == 1) 0 else 1
                (activity as? MainActivity)?.switchRouteMode(nextMode)
            }
            surgeStatusCard?.setOnClickListener {
                handleSurgeStatusCardClick()
            }
            surgeStatusCard?.setOnLongClickListener {
                val selectedId = DataStore.selectedProxy
                if (selectedId > 0L) {
                    confirmNodeIpInfo(selectedId)
                    true
                } else {
                    false
                }
            }
            refreshSurgeStatusHeader()
        }

        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            scrollToSelectedProfile()
        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    private fun scrollToSelectedProfile() {
        val fragment = getCurrentGroupFragment() ?: return
        val listAdapter = fragment.adapter ?: return
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
        val selectedProfileIndex = listAdapter.configurationIdList.indexOf(selectedProxy)
        if (selectedProfileIndex != -1) {
            val layoutManager = fragment.layoutManager
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()

            if (selectedProfileIndex !in first..last) {
                fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                return
            }
        }
        fragment.configurationListView.scrollTo(0)
    }

    private fun handleSurgeStatusCardClick() {
        val selectedId = DataStore.selectedProxy
        if (selectedId > 0L && DataStore.showNodeIpOwner) {
            val entity = SagerDatabase.proxyDao.getById(selectedId)
            if (entity != null && supportsCardIpProbe(entity) && shouldOfferHeaderQuickProbe(entity)) {
                confirmNodeIpInfo(selectedId)
                return
            }
        }
        scrollToSelectedProfile()
    }

    private fun shouldOfferHeaderQuickProbe(entity: ProxyEntity): Boolean =
        entity.exitIp.isBlank() && (!supportsCardEntry(entity) || entity.entryProvider.isBlank())

    /**
     * Refreshes the compact Surge status strip above the node list with live connection
     * state, active proxy name, Entry/Exit IP + ISP telemetry, and active route mode.
     */
    fun refreshSurgeStatusHeader() {
        if (select || !isAdded) return
        val dot = surgeStatusDot ?: return
        val titleView = surgeActiveProfile ?: return
        val ipSummaryView = surgeActiveIpSummary ?: return
        val modeChip = surgeRouteModeChip ?: return
        val ctx = context ?: return

        val state = DataStore.serviceState
        val dotColorRes = when (state) {
            BaseService.State.Connected -> R.color.surge_green
            BaseService.State.Connecting, BaseService.State.Stopping -> R.color.surge_amber
            else -> R.color.surge_status_idle
        }
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(ctx.getColour(dotColorRes))

        val isGlobal = DataStore.routeMode == 1
        modeChip.text = getString(if (isGlobal) R.string.route_mode_global else R.string.route_mode_rule)

        val selectedId = DataStore.selectedProxy
        val hideAddress = DataStore.hideNodeAddress
        val showIpOwner = DataStore.showNodeIpOwner
        val isProbingSelected = selectedId > 0L && probingProxyId == selectedId
        runOnDefaultDispatcher {
            val entity = if (selectedId > 0L) SagerDatabase.proxyDao.getById(selectedId) else null
            val displayTitle = if (entity != null) {
                val bean = entity.requireBean()
                val safeName = AddressRedactor.cardTitle(
                    entity.displayName(),
                    bean.name,
                    bean.serverAddress.orEmpty(),
                    ctx.getString(R.string.card_hidden_title),
                    hideAddress,
                )
                when (state) {
                    BaseService.State.Connected -> safeName
                    BaseService.State.Connecting -> "${ctx.getString(R.string.connecting)} · $safeName"
                    BaseService.State.Stopping -> "${ctx.getString(R.string.stopping)} · $safeName"
                    else -> "${ctx.getString(R.string.not_connected)} · $safeName"
                }
            } else {
                when (state) {
                    BaseService.State.Connected -> ctx.getString(R.string.vpn_connected)
                    BaseService.State.Connecting -> ctx.getString(R.string.connecting)
                    BaseService.State.Stopping -> ctx.getString(R.string.stopping)
                    else -> ctx.getString(R.string.surge_status_idle_hint)
                }
            }
            val headerIpText: CharSequence? = when {
                !showIpOwner || entity == null || !supportsCardIpProbe(entity) -> null
                isProbingSelected -> ctx.getString(R.string.card_ip_probe_started)
                else -> formatIpInfoSpannable(ctx, entity, hideAddress)
                    ?: ctx.getString(R.string.surge_ip_tap_to_probe)
            }
            onMainDispatcher {
                if (isAdded && surgeActiveProfile === titleView) {
                    titleView.text = displayTitle
                    ipSummaryView.text = headerIpText ?: ""
                    ipSummaryView.isVisible = !headerIpText.isNullOrBlank()
                }
            }
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        runCatching { ipInfoDialog?.dismiss() }.onFailure { Logs.w(it) }
        ipInfoDialog = null
        // Dismiss active test dialog to prevent WindowLeaked when view detaches.
        activeTestHandle?.let { handle ->
            runCatching { handle.dialog?.dismiss() }.onFailure { Logs.w(it) }
            handle.dialog = null
        }
        groupTabDivider = null
        surgeSearchBar = null
        surgeSearchInput = null
        surgeSearchClose = null
        surgeStatusCard = null
        surgeStatusDot = null
        surgeActiveProfile = null
        surgeActiveIpSummary = null
        surgeRouteModeChip = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cancelAllTests()
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val displayOptions = DataStore.hideNodeAddress to DataStore.showNodeIpOwner
        if (lastIpDisplayOptions != displayOptions && ::adapter.isInitialized) {
            lastIpDisplayOptions = displayOptions
            adapter.groupFragments.values.forEach { it.adapter?.notifyDataSetChanged() }
        }
        updatePrivacyMaskMenuItem()
        refreshSurgeStatusHeader()
        activeTestHandle?.restoreIfHidden()
    }

    /**
     * Synchronizes the top-right toolbar Eye / Eye-Off icon and active tint with [DataStore.hideNodeAddress].
     */
    private fun updatePrivacyMaskMenuItem() {
        if (select || !isAdded) return
        val item = toolbar.menu?.findItem(R.id.action_toggle_privacy_mask) ?: return
        val isHidden = DataStore.hideNodeAddress
        val ctx = context ?: return
        val iconRes = if (isHidden) {
            R.drawable.ic_baseline_visibility_off_24
        } else {
            R.drawable.ic_baseline_visibility_24
        }
        val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes)?.mutate()
        if (drawable != null) {
            val tintColor = if (isHidden) {
                ctx.getColorAttr(R.attr.selectedColorPrimary)
            } else {
                ctx.getColour(R.color.surge_text_primary)
            }
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, tintColor)
            item.icon = drawable
        } else {
            item.setIcon(iconRes)
        }
        item.setTitle(
            if (isHidden) R.string.privacy_mask_restore_addresses
            else R.string.hide_node_address
        )
    }

    private fun toggleScreenshotPrivacyMask() {
        val nextHidden = !DataStore.hideNodeAddress
        DataStore.hideNodeAddress = nextHidden
        lastIpDisplayOptions = nextHidden to DataStore.showNodeIpOwner
        updatePrivacyMaskMenuItem()
        if (::adapter.isInitialized) {
            adapter.groupFragments.values.forEach { it.adapter?.notifyDataSetChanged() }
        }
        refreshSurgeStatusHeader()
        snackbar(
            if (nextHidden) R.string.privacy_mask_enabled_toast
            else R.string.privacy_mask_disabled_toast
        ).show()
    }

    private fun toggleInlineNodeSearch() {
        val bar = surgeSearchBar ?: return
        val input = surgeSearchInput ?: return
        if (bar.isVisible) {
            closeInlineNodeSearch()
            return
        }
        bar.isVisible = true
        input.requestFocus()
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeInlineNodeSearch() {
        val bar = surgeSearchBar ?: return
        val input = surgeSearchInput
        input?.setText("")
        input?.clearFocus()
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        if (input != null) {
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        if (!select) {
            bar.isVisible = false
        }
        getCurrentGroupFragment()?.adapter?.filter("")
    }

    /**
     * Hook invoked on live foreground speed ticks from [MainActivity.cbSpeedUpdate].
     */
    fun onLiveSpeedSample(@Suppress("UNUSED_PARAMETER") stats: io.nekohasekai.sagernet.aidl.SpeedDisplayData) = Unit

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Import file exceeds size limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                val resolver = context?.contentResolver ?: return@registerForActivityResult
                runOnDefaultDispatcher {
                    try {
                        val fileName =
                            resolver.query(file, null, null, null, null)
                                ?.use { cursor ->
                                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                                }
                        val proxies = mutableListOf<AbstractBean>()
                        if (fileName != null && fileName.endsWith(".zip", ignoreCase = true)) {
                            // try parse wireguard zip (bounded against zip bombs)
                            val maxEntries = 256
                            val maxEntryBytes = 2 * 1024 * 1024
                            val maxTotalBytes = 16 * 1024 * 1024
                            val inputStream = resolver.openInputStream(file)
                                ?: throw java.io.IOException("Failed to open selected archive")
                            ZipInputStream(inputStream).use { zip ->
                                var entryCount = 0
                                var totalBytes = 0
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    try {
                                        if (entry.isDirectory) continue
                                        entryCount++
                                        if (entryCount > maxEntries) {
                                            throw IllegalArgumentException("Zip archive contains too many entries")
                                        }
                                        val remaining = (maxTotalBytes - totalBytes).coerceAtMost(maxEntryBytes)
                                        val bytes = readBoundedBytes(zip, remaining)
                                        totalBytes += bytes.size
                                        val fileText = String(bytes, Charsets.UTF_8)
                                        RawUpdater.parseRaw(fileText, entry.name)
                                            ?.let { pl -> proxies.addAll(pl) }
                                    } finally {
                                        zip.closeEntry()
                                    }
                                }
                            }
                        } else {
                            val inputStream = resolver.openInputStream(file)
                                ?: throw java.io.IOException("Failed to open selected file")
                            val fileText = inputStream.use {
                                String(readBoundedBytes(it, 16 * 1024 * 1024), Charsets.UTF_8)
                            }
                            RawUpdater.parseRaw(fileText, fileName ?: "")
                                ?.let { pl -> proxies.addAll(pl) }
                        }
                        if (proxies.isEmpty()) onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        onMainDispatcher {
                            (activity as? MainActivity)?.importSubscription(e.link.toUri())
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(Protocols.sanitizeErrorMessage(e.readableMessage)).show()
                        }
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            val ctx = context ?: return@onMainDispatcher
            if (!isAdded) return@onMainDispatcher
            snackbar(
                ctx.resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_toggle_privacy_mask -> {
                toggleScreenshotPrivacyMask()
            }

            R.id.action_search_nodes -> {
                toggleInlineNodeSearch()
            }

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        onMainDispatcher {
                            (activity as? MainActivity)?.importSubscription(e.link.toUri())
                        }
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(Protocols.sanitizeErrorMessage(e.readableMessage)).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_masque -> {
                startActivity(Intent(requireActivity(), io.nekohasekai.sagernet.ui.profile.MASQUESettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity::class.java))
            }
            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), moe.matsuri.nb4a.proxy.snell.SnellSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_group_private_dns -> {
                openDohManagerFromMainMenu()
            }

            R.id.action_edit_subscription_config -> {
                openSubscriptionConfigFromMainMenu()
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val groupId = DataStore.currentGroupId()
                    SagerDatabase.proxyDao.clearTestResults(groupId)
                    onMainDispatcher {
                        if (!isAdded || !::adapter.isInitialized) return@onMainDispatcher
                        adapter.groupFragments.values.forEach { fragment ->
                            fragment.adapter?.apply {
                                configurationList.values.forEach { profile ->
                                    if (profile.groupId == groupId) {
                                        profile.status = 0
                                        profile.ping = 0
                                        profile.error = null
                                        profile.speedTestMode = ""
                                        profile.speedTestDownloadBitsPerSecond = 0L
                                        profile.speedTestUploadBitsPerSecond = 0L
                                    }
                                }
                                notifyDataSetChanged()
                            }
                        }
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        val hasSpeedResult = profile.speedTestDownloadBitsPerSecond > 0L || profile.speedTestUploadBitsPerSecond > 0L
                        if (profile.status != 0 && profile.status != 1 && !hasSpeedResult) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            val ctx = context ?: return@onMainDispatcher
                            if (!isAdded) return@onMainDispatcher
                            MaterialAlertDialogBuilder(ctx).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    if (::adapter.isInitialized) {
                                        for (profile in toClear) {
                                            adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                                val index = configurationIdList.indexOf(profile.id)
                                                if (index >= 0) {
                                                    configurationIdList.removeAt(index)
                                                    configurationList.remove(profile.id)
                                                    notifyItemRemoved(index)
                                                }
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            val ctx = context ?: return@onMainDispatcher
                            if (!isAdded) return@onMainDispatcher
                            val hideAddress = DataStore.hideNodeAddress
                            MaterialAlertDialogBuilder(ctx).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    AddressRedactor.beanTitle(
                                                        proxyEntity.requireBean(),
                                                        hideAddress,
                                                    )
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    if (::adapter.isInitialized) {
                                        for (profile in toClear) {
                                            adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                                val index = configurationIdList.indexOf(profile.id)
                                                if (index >= 0) {
                                                    configurationIdList.removeAt(index)
                                                    configurationList.remove(profile.id)
                                                    notifyItemRemoved(index)
                                                }
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_speed_test_group -> {
                confirmSpeedTest()
            }

            R.id.action_speed_test_retry_failed -> {
                retryFailedSpeedTest()
            }

            R.id.action_probe_group_ip_info -> {
                confirmGroupIpInfo()
            }
        }
        return true
    }

    private fun confirmSpeedTest() {
        if (!isAdded) return
        if (DataStore.runningTest) {
            showForceTestDialog { speedTest(force = true) }
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_test_confirm_title)
            .setMessage(R.string.speed_test_confirm_message)
            .setPositiveButton(R.string.speed_test_group) { _, _ -> speedTest() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showForceTestDialog(onForce: () -> Unit) {
        runOnMainDispatcher {
            if (!isAdded) return@runOnMainDispatcher
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.connection_test_running_force)
                .setPositiveButton(R.string.connection_test_force) { _, _ -> onForce() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    internal fun supportsCardEntry(entity: ProxyEntity): Boolean = when (entity.type) {
        ProxyEntity.TYPE_CONFIG, ProxyEntity.TYPE_CHAIN, ProxyEntity.TYPE_NEKO -> false
        else -> true
    }

    internal fun supportsCardExit(entity: ProxyEntity): Boolean =
        entity.type != ProxyEntity.TYPE_NEKO && !entity.needExternal()

    internal fun supportsCardIpProbe(entity: ProxyEntity): Boolean =
        supportsCardEntry(entity) || supportsCardExit(entity)

    /**
     * Builds a Surge-styled two-line `CharSequence` rendering both Entry IP + ISP (`入口`)
     * and verified Exit IP + ISP (`出口`) with semantic badge colors and screenshot redaction.
     *
     * @param ctx Android context used to resolve Surge theme colors and localized badge strings.
     * @param entity Proxy profile entity whose persisted/literal Entry and Exit metadata is read.
     * @param hide When `true`, masks IP octets/hextets and scrubs raw server hostnames.
     * @return Formatted `CharSequence` when at least one Entry or Exit IP is available, or `null`.
     */
    internal fun formatIpInfoSpannable(
        ctx: android.content.Context,
        entity: ProxyEntity,
        hide: Boolean,
    ): CharSequence? {
        if (!supportsCardIpProbe(entity)) return null
        val canReadEntry = supportsCardEntry(entity)
        val rawHost = if (canReadEntry) entity.requireBean().serverAddress.orEmpty() else ""
        val literalEntry = if (canReadEntry) {
            rawHost.trim().removeSurrounding("[", "]").takeIf(IpInfoStore::isIpLiteral)
        } else {
            null
        }
        val entryIp = literalEntry ?: entity.entryIp.takeIf { canReadEntry && IpInfoStore.isIpLiteral(it) }
        val entryProviderRaw = if (entryIp != null &&
            (entity.entryIp.isBlank() || IpInfoStore.sameIp(entity.entryIp, entryIp))
        ) {
            entity.entryProvider
        } else {
            ""
        }
        val exitIp = entity.exitIp.takeIf(IpInfoStore::isIpLiteral)
        val exitProviderRaw = entity.exitProvider
        if (entryIp == null && exitIp == null) return null

        val entryBadgeColor = ctx.getColorAttr(R.attr.primaryOrTextPrimary)
        val exitBadgeColor = ctx.getColour(R.color.surge_green)
        val relayTagColor = ctx.getColour(R.color.surge_amber)
        val ipTextColor = ctx.getColour(R.color.surge_text_primary)
        val metaTextColor = ctx.getColour(R.color.surge_text_secondary)

        fun appendTelemetryRow(
            builder: SpannableStringBuilder,
            badgeText: String,
            badgeColor: Int,
            rawIp: String,
            rawProvider: String,
            fallbackHint: String?,
            topologyModeTag: String? = null,
            topologyModeColor: Int = exitBadgeColor,
        ) {
            if (builder.isNotEmpty()) builder.append('\n')
            val badgeStart = builder.length
            builder.append(badgeText)
            builder.setSpan(ForegroundColorSpan(badgeColor), badgeStart, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                badgeStart,
                builder.length,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.append("  ")

            val maskedIp = AddressRedactor.maskIp(rawIp, hide)
            val ipStart = builder.length
            builder.append(maskedIp)
            builder.setSpan(ForegroundColorSpan(ipTextColor), ipStart, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)

            val scrubbedProvider = (if (hide) AddressRedactor.scrub(rawProvider, rawHost) else rawProvider).trim()
            val detail = scrubbedProvider.ifBlank { fallbackHint.orEmpty() }
            if (detail.isNotBlank()) {
                val metaStart = builder.length
                builder.append(" · ").append(detail)
                builder.setSpan(ForegroundColorSpan(metaTextColor), metaStart, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (!topologyModeTag.isNullOrBlank()) {
                builder.append(' ')
                val modeStart = builder.length
                builder.append(topologyModeTag)
                builder.setSpan(
                    ForegroundColorSpan(topologyModeColor),
                    modeStart,
                    builder.length,
                    SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        val builder = SpannableStringBuilder()
        if (entryIp != null) {
            val unprobedLiteralHint = if (entryProviderRaw.isBlank() && exitIp == null) {
                ctx.getString(R.string.card_ip_tap_isp_hint)
            } else {
                null
            }
            appendTelemetryRow(
                builder = builder,
                badgeText = ctx.getString(R.string.card_entry_badge),
                badgeColor = entryBadgeColor,
                rawIp = entryIp,
                rawProvider = entryProviderRaw,
                fallbackHint = unprobedLiteralHint,
            )
        }
        if (exitIp != null) {
            val isDirectTopology = entryIp != null && IpInfoStore.sameIp(entryIp, exitIp)
            val modeTag = when {
                entryIp == null -> null
                isDirectTopology -> ctx.getString(R.string.topology_mode_direct)
                else -> ctx.getString(R.string.topology_mode_relay)
            }
            val modeColor = if (isDirectTopology) exitBadgeColor else relayTagColor
            appendTelemetryRow(
                builder = builder,
                badgeText = ctx.getString(R.string.card_exit_badge),
                badgeColor = exitBadgeColor,
                rawIp = exitIp,
                rawProvider = exitProviderRaw,
                fallbackHint = null,
                topologyModeTag = modeTag,
                topologyModeColor = modeColor,
            )
        }
        return builder.takeIf { it.isNotEmpty() }
    }

    private fun sameProbeProfile(a: ProxyEntity, b: ProxyEntity): Boolean =
        a.id == b.id && a.groupId == b.groupId && a.type == b.type && a.requireBean() == b.requireBean()

    private fun sameProbeGroup(a: ProxyGroup, b: ProxyGroup): Boolean =
        a.id == b.id && a.frontProxy == b.frontProxy && a.landingProxy == b.landingProxy &&
            a.effectiveNodeDnsList() == b.effectiveNodeDnsList()

    /**
     * Ensures topology panels are visible whenever the user explicitly initiates a topology test.
     */
    private fun ensureTopologyDisplayEnabled() {
        if (!DataStore.showNodeIpOwner) {
            DataStore.showNodeIpOwner = true
            lastIpDisplayOptions = DataStore.hideNodeAddress to true
            if (::adapter.isInitialized) {
                adapter.groupFragments.values.forEach { it.adapter?.notifyDataSetChanged() }
            }
        }
    }

    private fun applyTopologyOutcomeOnMain(id: Long, outcome: IpCardProbeOutcome?) {
        if (!::adapter.isInitialized) return
        adapter.groupFragments.values.forEach { fragment ->
            if (outcome != null) {
                fragment.adapter?.updateTopologyResult(
                    profileId = id,
                    entryIp = outcome.entryIp,
                    entryProvider = outcome.entryProvider,
                    exitIp = outcome.exitIp,
                    exitProvider = outcome.exitProvider,
                    updatedAt = outcome.updatedAt,
                )
            } else {
                val idx = fragment.adapter?.configurationIdList?.indexOf(id) ?: -1
                if (idx >= 0) fragment.adapter?.notifyItemChanged(idx)
            }
        }
    }

    private fun confirmNodeIpInfo(id: Long) {
        if (!isResumed || ipInfoDialog?.isShowing == true) return
        if (ipInfoJob?.isActive == true) {
            snackbar(R.string.card_ip_probe_busy).show()
            return
        }
        ensureTopologyDisplayEnabled()
        if (DataStore.ipProbeConsentGranted) {
            requestNodeIpInfo(id, isSilent = false)
            return
        }
        ipInfoDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.card_ip_probe)
            .setMessage(R.string.card_ip_probe_disclosure)
            .setPositiveButton(R.string.card_ip_probe) { _, _ ->
                DataStore.ipProbeConsentGranted = true
                requestNodeIpInfo(id, isSilent = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Automatically refreshes Entry/Exit IP & ISP for the currently connected proxy node when
     * the user has already granted one-time probe consent and the node lacks cached telemetry.
     */
    fun onActiveNodeConnectedRefreshIp() {
        if (select || !isAdded || !DataStore.showNodeIpOwner || !DataStore.ipProbeConsentGranted) return
        val selectedId = DataStore.selectedProxy
        if (selectedId <= 0L || ipInfoJob?.isActive == true || DataStore.runningTest) return
        runOnDefaultDispatcher {
            val entity = SagerDatabase.proxyDao.getById(selectedId) ?: return@runOnDefaultDispatcher
            val isMissingTelemetry = entity.exitIp.isBlank() ||
                (supportsCardEntry(entity) && entity.entryProvider.isBlank())
            if (!isMissingTelemetry || !supportsCardIpProbe(entity)) return@runOnDefaultDispatcher
            onMainDispatcher {
                if (isAdded && ipInfoJob?.isActive != true && !DataStore.runningTest) {
                    requestNodeIpInfo(selectedId, isSilent = true)
                }
            }
        }
    }

    private fun requestNodeIpInfo(id: Long, isSilent: Boolean = false) {
        if (view == null || ipInfoJob?.isActive == true) return
        if (DataStore.runningTest) {
            if (!isSilent && isResumed) {
                showForceTestDialog {
                    cancelAllTests(releaseToken = true)
                    requestNodeIpInfo(id, isSilent = false)
                }
            }
            return
        }
        probingProxyId = id
        applyTopologyOutcomeOnMain(id, null)
        refreshSurgeStatusHeader()
        if (!isSilent && isResumed) snackbar(R.string.card_ip_probe_started).show()
        ipInfoJob = viewLifecycleOwner.lifecycleScope.launch {
            var outcome: IpCardProbeOutcome? = null
            try {
                outcome = withContext(Dispatchers.IO) {
                    probeAndPersistNodeIpInfo(id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                probingProxyId = null
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    applyTopologyOutcomeOnMain(id, outcome)
                    refreshSurgeStatusHeader()
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    ProfileManager.postUpdate(id, true)
                }
            }
            if (!isSilent && isResumed) {
                snackbar(
                    getString(
                        when {
                            outcome == null -> R.string.card_ip_probe_failed
                            !outcome.hasVerifiedExit -> R.string.card_ip_probe_entry_only
                            else -> R.string.card_ip_probe_complete
                        }
                    )
                ).show()
            }
        }
    }

    private data class IpCardProbeOutcome(
        val entryIp: String,
        val entryProvider: String,
        val exitIp: String,
        val exitProvider: String,
        val updatedAt: Long,
    ) {
        val hasEntry: Boolean get() = entryIp.isNotBlank()
        val hasVerifiedExit: Boolean get() = exitIp.isNotBlank()
    }

    /**
     * Resolves a node's Entry IP + ISP and verified Exit IP + ISP inside a single isolated
     * `BoxInstance` lifecycle, then atomically persists both to Room SQLite.
     *
     * WHY: Running DNS lookup, exit verification (`probeExitViaBox`), and entry ASN lookup
     * (`ownerViaBox`) over the same in-memory `BoxInstance` supports every sing-box DNS transport
     * (`https`, `h3`, `tls`, `quic`, `udp`), supports WireGuard endpoints and proxy chains, and
     * bypasses carrier censorship against GeoIP endpoints even when the main VPN service is stopped.
     */
    private suspend fun probeAndPersistNodeIpInfo(id: Long): IpCardProbeOutcome? {
        val dao = SagerDatabase.proxyDao
        val profile = dao.getById(id) ?: return null
        val group = SagerDatabase.groupDao.getById(profile.groupId) ?: return null
        if (!supportsCardIpProbe(profile)) return null

        val canReadEntry = supportsCardEntry(profile)
        val canTestExit = supportsCardExit(profile)
        val host = if (canReadEntry) profile.requireBean().serverAddress.orEmpty().trim() else ""
        val resolvers = group.effectiveNodeDnsList()

        val generated = if (canTestExit) {
            runCatching { io.nekohasekai.sagernet.fmt.buildConfig(profile, forTest = true) }
                .onFailure { Logs.w(it) }
                .getOrNull()
        } else {
            null
        }
        val canStartBox = generated != null && generated.externalIndex.none { it.chain.isNotEmpty() }

        var resolvedEntry: String? = null
        var entryOwnerLabel = ""
        var exitOwner: io.nekohasekai.sagernet.ipinfo.IpOwner? = null

        val box = if (canStartBox) {
            runCatching {
                libcore.Libcore.newTestSingBoxInstance(
                    generated!!.config,
                    moe.matsuri.nb4a.net.LocalResolverImpl,
                )
            }.onFailure { Logs.w(it) }.getOrNull()
        } else {
            null
        }

        try {
            val isBoxStarted = if (box != null) {
                runCatching { box.start(); true }.onFailure { Logs.w(it) }.getOrDefault(false)
            } else {
                false
            }
            val activeBox = if (isBoxStarted) box else null

            if (canReadEntry && host.isNotEmpty()) {
                resolvedEntry = resolveNodeEntryIp(host, group.id, resolvers, activeBox)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            if (activeBox != null) {
                exitOwner = IpInfoStore.probeExitViaBox(activeBox, EXIT_PROBE_TIMEOUT_MS)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            if (resolvedEntry != null) {
                entryOwnerLabel = if (exitOwner != null &&
                    IpInfoStore.sameIp(resolvedEntry, exitOwner.ip) &&
                    exitOwner.label().isNotBlank()
                ) {
                    exitOwner.label()
                } else {
                    // WHY: Only reuse activeBox for entry GeoIP if exitOwner succeeded; if the node
                    // itself timed out, pass null so ownerViaBox falls back immediately without
                    // waiting for dead-tunnel timeouts.
                    IpInfoStore.ownerViaBox(
                        box = if (exitOwner != null) activeBox else null,
                        ip = resolvedEntry,
                        timeoutMs = OWNER_PROBE_TIMEOUT_MS,
                    )?.label().orEmpty()
                }
            }
        } finally {
            runCatching { box?.close() }
        }

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val hideInLogs = DataStore.hideNodeAddress
        if (resolvedEntry == null && exitOwner == null) {
            runCatching {
                val safeTitle = if (hideInLogs) "#$id" else profile.displayName()
                libcore.Libcore.nekoLogPrintln("WARN [topology] $safeTitle: no entry or exit resolved")
            }
            return null
        }

        val now = System.currentTimeMillis()
        val persisted = SagerDatabase.instance.runInTransaction<IpCardProbeOutcome?> {
            val current = dao.getById(id) ?: return@runInTransaction null
            val currentGroup = SagerDatabase.groupDao.getById(current.groupId) ?: return@runInTransaction null
            if (!sameProbeProfile(profile, current) || !sameProbeGroup(group, currentGroup)) {
                return@runInTransaction null
            }

            val finalEntryIp = resolvedEntry ?: current.entryIp
            val finalEntryProvider = when {
                entryOwnerLabel.isNotBlank() -> entryOwnerLabel
                resolvedEntry != null && IpInfoStore.sameIp(current.entryIp, resolvedEntry) -> current.entryProvider
                resolvedEntry == null -> current.entryProvider
                else -> ""
            }
            val finalExitIp = exitOwner?.ip.orEmpty()
            val finalExitProvider = exitOwner?.label().orEmpty()

            dao.updateIpInfo(
                proxyId = id,
                entryIp = finalEntryIp,
                entryProvider = finalEntryProvider,
                exitIp = finalExitIp,
                exitProvider = finalExitProvider,
                updatedAt = now,
            )
            IpCardProbeOutcome(
                entryIp = finalEntryIp,
                entryProvider = finalEntryProvider,
                exitIp = finalExitIp,
                exitProvider = finalExitProvider,
                updatedAt = now,
            )
        }
        if (persisted != null) {
            runCatching {
                val safeTitle = if (hideInLogs) "#$id" else profile.displayName()
                val inIp = AddressRedactor.maskIp(persisted.entryIp, hideInLogs).ifBlank { "-" }
                val outIp = AddressRedactor.maskIp(persisted.exitIp, hideInLogs).ifBlank { "-" }
                libcore.Libcore.nekoLogPrintln(
                    "INFO [topology] $safeTitle: IN=$inIp (${persisted.entryProvider.ifBlank { "-" }}) -> OUT=$outIp (${persisted.exitProvider.ifBlank { "-" }})"
                )
            }
        }
        return persisted
    }

    private suspend fun resolveNodeEntryIp(
        host: String,
        groupId: Long,
        resolvers: List<String>,
        activeBox: libcore.BoxInstance?,
    ): String? {
        if (IpInfoStore.isIpLiteral(host)) {
            return IpInfoStore.resolveEntry(host, emptyList())
        }
        if (activeBox != null) {
            val transportTag = if (resolvers.isNotEmpty()) "dns-group-$groupId" else "dns-direct"
            val viaBox = withContext(Dispatchers.IO) {
                runCatching {
                    activeBox.lookupDomainWithTag(host, transportTag, DNS_PROBE_TIMEOUT_MS)
                }.getOrNull()
            }?.takeIf(IpInfoStore::isUsableEntryIp)
            if (viaBox != null) return viaBox
        }
        return IpInfoStore.resolveEntry(host, resolvers)
    }

    private fun confirmGroupIpInfo() {
        if (!isAdded) return
        ensureTopologyDisplayEnabled()
        if (DataStore.ipProbeConsentGranted) {
            probeGroupIpInfo(force = false)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_probe_group_ip_info)
            .setMessage(R.string.card_ip_probe_disclosure)
            .setPositiveButton(R.string.card_ip_probe) { _, _ ->
                DataStore.ipProbeConsentGranted = true
                probeGroupIpInfo(force = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Batch-probes node topology (Entry/Exit IP, ASN/ISP, and Relay/Direct mode) across all
     * supported nodes in the active group with bounded concurrency ([GROUP_IP_PROBE_CONCURRENCY]),
     * live in-memory card updates, and minimize/cancel controls unified under [ActiveTestHandle].
     */
    fun probeGroupIpInfo(force: Boolean = false) {
        if (!isAdded) return
        ensureTopologyDisplayEnabled()
        val testToken = acquireOrPromptTestToken(force) {
            probeGroupIpInfo(force = true)
        }
        if (testToken == 0) return

        val group = DataStore.currentGroup()
        val titleText = "${group.displayName()} · ${getString(R.string.card_ip_probe_short)}"
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progressCircular.isGone = true
        binding.progressLinear.isVisible = true
        binding.progressLinear.max = 1
        binding.progressLinear.setProgressCompat(0, false)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleText)
            .setView(binding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()

        val workerJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        var coordinatorJob: Job? = null
        val handle = ActiveTestHandle(token = testToken, dialog = dialog) {
            coordinatorJob?.cancel()
            synchronized(workerJobs) { workerJobs.toList() }.forEach { it.cancel() }
        }
        activeTestHandle = handle

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!DataStore.isTestTokenActive(testToken)) return@setOnClickListener
            handle.isHidden = true
            handle.notification = ConnectionTestNotification(dialog.context, titleText)
            dialog.hide()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            handle.cancel(dismissDialog = true)
        }

        coordinatorJob = runOnDefaultDispatcher {
            var totalCount = 0
            val successCount = AtomicInteger(0)
            var wasCompletedNormally = false
            try {
                val targets = SagerDatabase.proxyDao.getByGroup(group.id).filter(::supportsCardIpProbe)
                if (targets.isEmpty()) return@runOnDefaultDispatcher
                totalCount = targets.size
                val completedCount = AtomicInteger(0)
                val queue = ConcurrentLinkedQueue(targets)

                onMainDispatcher {
                    if (!DataStore.isTestTokenActive(testToken)) return@onMainDispatcher
                    binding.progressLinear.max = totalCount
                    binding.progressLinear.setProgressCompat(0, false)
                    binding.progress.text = "0 / $totalCount"
                }

                val workerCount = minOf(GROUP_IP_PROBE_CONCURRENCY, totalCount)
                repeat(workerCount) {
                    workerJobs.add(launch(Dispatchers.IO) {
                        while (isActive && DataStore.isTestTokenActive(testToken)) {
                            val item = queue.poll() ?: break
                            val safeItemName = AddressRedactor.beanTitle(
                                item.requireBean(),
                                DataStore.hideNodeAddress,
                            )
                            onMainDispatcher {
                                if (!handle.isHidden && isAdded && DataStore.isTestTokenActive(testToken)) {
                                    binding.nowTesting.text = getString(
                                        R.string.group_ip_probe_testing,
                                        safeItemName,
                                    )
                                }
                            }
                            val outcome = runCatching { probeAndPersistNodeIpInfo(item.id) }
                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                                .getOrNull()
                            if (outcome != null) {
                                successCount.incrementAndGet()
                            }
                            if (!isActive || !DataStore.isTestTokenActive(testToken)) break
                            val done = completedCount.incrementAndGet()
                            val notifName = AddressRedactor.beanTitle(
                                item.requireBean(),
                                DataStore.hideNodeAddress,
                            )
                            onMainDispatcher {
                                if (!isAdded || !DataStore.isTestTokenActive(testToken)) return@onMainDispatcher
                                if (outcome != null) {
                                    applyTopologyOutcomeOnMain(item.id, outcome)
                                }
                                handle.notification?.updateNotification(done, totalCount, done >= totalCount, notifName)
                                if (!handle.isHidden) {
                                    binding.progress.text = "$done / $totalCount"
                                    binding.progressLinear.setProgressCompat(done, true)
                                }
                                if (item.id == DataStore.selectedProxy) {
                                    refreshSurgeStatusHeader()
                                }
                            }
                        }
                    })
                }
                synchronized(workerJobs) { workerJobs.toList() }.joinAll()
                wasCompletedNormally = DataStore.isTestTokenActive(testToken)
            } finally {
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    if (dialog.isShowing) {
                        runCatching { dialog.dismiss() }
                    }
                    handle.notification?.updateNotification(0, 0, true)
                    handle.notification = null
                    handle.dialog = null
                    handle.isHidden = false
                    if (activeTestHandle === handle) {
                        activeTestHandle = null
                    }
                    refreshSurgeStatusHeader()
                    if (wasCompletedNormally && isResumed && totalCount > 0) {
                        snackbar(getString(R.string.group_topology_complete, successCount.get(), totalCount)).show()
                    }
                }
                DataStore.releaseTestToken(testToken)
            }
        }
    }

    /**
     * Cancels whichever group/node test (speedTest, pingTest, or urlTest) is currently
     * tracked by [activeTestHandle], dismisses its dialog, and clears its notification.
     *
     * @param releaseToken When `true`, also invalidates and releases [DataStore]'s test token.
     * Pass `false` when immediately claiming a replacement token via [DataStore.forceTestToken].
     */
    private fun cancelAllTests(releaseToken: Boolean = true) {
        val previous = activeTestHandle
        activeTestHandle = null
        previous?.cancel(dismissDialog = true)
        if (releaseToken) {
            // Invalidate any stale token (even if a cancelled run stalled before finally) and release.
            DataStore.releaseTestToken(DataStore.forceTestToken())
        }
    }

    /**
     * Acquires an exclusive test token or prompts the user to force-replace a running test.
     *
     * When [force] is `true`, cancels the previous active test and atomically swaps to a new
     * token without momentarily releasing the slot to `0`.
     */
    private fun acquireOrPromptTestToken(force: Boolean, onForceConfirm: () -> Unit): Int {
        if (force) {
            cancelAllTests(releaseToken = false)
            return DataStore.forceTestToken()
        }
        val token = DataStore.acquireTestToken()
        if (token == 0) {
            showForceTestDialog(onForceConfirm)
        }
        return token
    }

    private fun retryFailedSpeedTest(force: Boolean = false) {
        if (!isAdded) return
        val preToken = acquireOrPromptTestToken(force) { retryFailedSpeedTest(force = true) }
        if (preToken == 0) return
        val group = DataStore.currentGroup()
        runOnDefaultDispatcher {
            val allProfiles = SagerDatabase.proxyDao.getByGroup(group.id)
            val groupHasSpeedResults = allProfiles.any {
                it.speedTestDownloadBitsPerSecond > 0L || it.speedTestUploadBitsPerSecond > 0L
            }
            val failedProfiles = allProfiles.filter { p ->
                val isErrorStatus = p.status != 0 && p.status != 1
                val isFailedSpeed = groupHasSpeedResults &&
                    p.speedTestDownloadBitsPerSecond <= 0L &&
                    p.speedTestUploadBitsPerSecond <= 0L
                isErrorStatus || isFailedSpeed
            }
            onMainDispatcher {
                if (!isAdded) {
                    DataStore.releaseTestToken(preToken)
                    return@onMainDispatcher
                }
                if (failedProfiles.isEmpty()) {
                    DataStore.releaseTestToken(preToken)
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.speed_test_no_failed_nodes,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    speedTest(targetProfiles = failedProfiles, force = force, preAcquiredToken = preToken)
                }
            }
        }
    }

    fun speedTest(
        targetProfile: ProxyEntity? = null,
        targetProfiles: List<ProxyEntity>? = null,
        force: Boolean = false,
        preAcquiredToken: Int = 0,
    ) {
        if (!isAdded) {
            if (preAcquiredToken != 0) DataStore.releaseTestToken(preAcquiredToken)
            return
        }
        val testToken = if (preAcquiredToken != 0) {
            preAcquiredToken
        } else {
            acquireOrPromptTestToken(force) {
                speedTest(targetProfile, targetProfiles, force = true)
            }
        }
        if (testToken == 0) return

        val group = DataStore.currentGroup()
        val isSingle = targetProfile != null
        val titleText = when {
            isSingle -> AddressRedactor.beanTitle(
                targetProfile!!.requireBean(),
                DataStore.hideNodeAddress,
            )
            targetProfiles != null -> getString(R.string.speed_test_retry_failed)
            else -> getString(R.string.speed_test_group)
        }

        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progressCircular.isGone = true
        binding.progressLinear.isVisible = true
        binding.progressLinear.max = 1
        binding.progressLinear.setProgressCompat(0, false)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleText)
            .setView(binding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()

        val runner = SpeedTestQueueRunner(
            sessionFactory = ::AndroidSpeedTestSession,
            failureSnapshot = { profile, error ->
                SpeedTestSnapshot(
                    profileId = profile.id,
                    profileName = AddressRedactor.beanTitle(
                        profile.requireBean(),
                        DataStore.hideNodeAddress,
                    ),
                    mode = DataStore.speedTestMode,
                    stage = SpeedTestQueueRunner.STAGE_ERROR,
                    error = Protocols.sanitizeErrorMessage(error.readableMessage),
                    done = true,
                )
            },
        )

        var workerJob: Job? = null
        val handle = ActiveTestHandle(token = testToken, dialog = dialog) {
            runner.cancel()
            workerJob?.cancel()
        }
        activeTestHandle = handle

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!DataStore.isTestTokenActive(testToken)) return@setOnClickListener
            handle.isHidden = true
            handle.notification = ConnectionTestNotification(
                dialog.context,
                "[$titleText] ${getString(R.string.speed_test_group)}",
            )
            dialog.hide()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            handle.cancel(dismissDialog = true)
        }

        workerJob = runOnDefaultDispatcher {
            try {
                val profiles = targetProfiles ?: if (targetProfile != null) listOf(targetProfile) else SagerDatabase.proxyDao.getByGroup(group.id)
                if (profiles.isEmpty()) {
                    return@runOnDefaultDispatcher
                }
                val profilesById = profiles.associateBy { it.id }
                onMainDispatcher {
                    if (!DataStore.isTestTokenActive(testToken)) return@onMainDispatcher
                    binding.progressLinear.max = profiles.size
                    binding.progressLinear.setProgressCompat(0, false)
                    binding.progress.text = "0 / ${profiles.size}"
                }
                runner.run(profiles) { index, total, sample ->
                    if (!DataStore.isTestTokenActive(testToken)) return@run
                    val sanitizedSampleError = if (sample.error.isNotBlank()) {
                        Protocols.sanitizeErrorMessage(sample.error)
                    } else {
                        ""
                    }
                    val outcome = SpeedTestOutcome.completedOrNull(
                        mode = sample.mode,
                        stage = sample.stage,
                        done = sample.done,
                        cancelled = sample.cancelled,
                        error = sanitizedSampleError,
                        downloadBitsPerSecond = sample.downloadBitsPerSecond,
                        uploadBitsPerSecond = sample.uploadBitsPerSecond,
                    )
                    if (outcome != null && SagerDatabase.proxyDao.updateSpeedTestResult(
                            proxyId = sample.profileId,
                            mode = outcome.mode,
                            downloadBitsPerSecond = outcome.downloadBitsPerSecond,
                            uploadBitsPerSecond = outcome.uploadBitsPerSecond,
                        ) > 0
                    ) {
                        runOnMainDispatcher {
                            if (!DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                            adapter.groupFragments.values.forEach { fragment ->
                                fragment.adapter?.updateSpeedTestResult(sample.profileId, outcome)
                            }
                        }
                    } else if (sample.done && sanitizedSampleError.isNotBlank()) {
                        // 测速失败时清空过期速度值，若原本已有延迟（status==1）则保留其可用状态
                        SagerDatabase.proxyDao.updateSpeedTestFailure(sample.profileId, sanitizedSampleError)
                        runOnMainDispatcher {
                            if (!DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                            adapter.groupFragments.values.forEach { fragment ->
                                fragment.adapter?.configurationList?.get(sample.profileId)?.let { p ->
                                    p.speedTestMode = ""
                                    p.speedTestDownloadBitsPerSecond = 0L
                                    p.speedTestUploadBitsPerSecond = 0L
                                    if (p.status != 1) {
                                        p.status = 2
                                        p.error = sanitizedSampleError
                                    }
                                    val idx = fragment.adapter?.configurationIdList?.indexOf(sample.profileId) ?: -1
                                    if (idx >= 0) fragment.adapter?.notifyItemChanged(idx)
                                }
                            }
                        }
                    }
                    runOnMainDispatcher {
                        if (!isAdded || !DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                        val hideNow = DataStore.hideNodeAddress
                        val profileEntity = profilesById[sample.profileId]
                        val safeProfileName = when {
                            profileEntity != null -> AddressRedactor.beanTitle(profileEntity.requireBean(), hideNow)
                            hideNow -> AddressRedactor.scrub(sample.profileName)
                            else -> sample.profileName
                        }
                        val stageLabel = speedTestStageLabel(sample.stage)
                        val compactLabel = "$safeProfileName · $stageLabel"
                        val detail = formatSpeedTestSnapshot(sample, safeProfileName, hideNow)
                        (activity as? MainActivity)?.binding?.stats?.updateLiveSpeedTest(
                            statusLabel = compactLabel,
                            downloadBitsPerSecond = sample.downloadBitsPerSecond,
                            uploadBitsPerSecond = sample.uploadBitsPerSecond,
                        )
                        handle.notification?.updateNotification(index + 1, total, false, compactLabel)
                        if (!handle.isHidden && isAdded) {
                            val completed = completedSpeedTestCount(index, total, sample.done)
                            binding.nowTesting.text = detail
                            binding.progress.text = "$completed / $total"
                            binding.progressLinear.setProgressCompat(completed, true)
                        }
                    }
                }
                if (DataStore.isTestTokenActive(testToken) &&
                    (group.order == GroupOrder.BY_DOWNLOAD_SPEED || group.order == GroupOrder.BY_UPLOAD_SPEED)
                ) {
                    GroupManager.postReload(group.id)
                }
                delay(800)
            } catch (_: kotlinx.coroutines.CancellationException) {
                runOnMainDispatcher {
                    if (!handle.isHidden && isAdded && DataStore.isTestTokenActive(testToken)) {
                        binding.nowTesting.text = getString(R.string.speed_test_stage_cancelled)
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    if (isAdded && DataStore.isTestTokenActive(testToken)) {
                        snackbar(Protocols.sanitizeErrorMessage(e.readableMessage)).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    (activity as? MainActivity)?.binding?.stats?.clearLiveSpeedTest()
                    if (dialog.isShowing) {
                        runCatching { dialog.dismiss() }
                    }
                    handle.notification?.updateNotification(0, 0, true)
                    handle.notification = null
                    handle.dialog = null
                    handle.isHidden = false
                    // Only clear the shared active handle if this run still owns it;
                    // never clobber a newly forced run's state.
                    if (activeTestHandle === handle) {
                        activeTestHandle = null
                    }
                }
                DataStore.releaseTestToken(testToken)
                if (isSingle && targetProfile != null && DataStore.showNodeIpOwner && DataStore.ipProbeConsentGranted) {
                    val refreshed = SagerDatabase.proxyDao.getById(targetProfile.id)
                    if (refreshed != null && refreshed.exitIp.isBlank() && supportsCardIpProbe(refreshed)) {
                        onMainDispatcher {
                            if (isAdded && ipInfoJob?.isActive != true) {
                                requestNodeIpInfo(refreshed.id, isSilent = true)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens the DoH Manager from the top-right toolbar menu.
     *
     * When the active tab is a subscription group, launches [PrivateDnsDialogHelper.showManagerForGroup]
     * directly with `autoInspectDns = true` so users land straight in subscription DoH detection/management.
     * When on a basic group (e.g. "My group") while subscription groups exist, lets the user either
     * configure manual DoH for the current group or jump straight into any subscription's DoH detector.
     */
    private fun openDohManagerFromMainMenu(targetGroupId: Long? = null) {
        val hostActivity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        if (targetGroupId != null && targetGroupId > 0L) {
            PrivateDnsDialogHelper.showManagerForGroup(
                activity = hostActivity,
                groupId = targetGroupId,
                autoInspectDns = true,
            )
            return
        }
        runOnDefaultDispatcher {
            val currentGroup = DataStore.currentGroup()
            if (currentGroup.type == GroupType.SUBSCRIPTION) {
                onMainDispatcher {
                    if (!isAdded || hostActivity.isFinishing || hostActivity.isDestroyed) return@onMainDispatcher
                    PrivateDnsDialogHelper.showManagerForGroup(
                        activity = hostActivity,
                        groupId = currentGroup.id,
                        autoInspectDns = true,
                    )
                }
                return@runOnDefaultDispatcher
            }
            val subscriptionGroups = SagerDatabase.groupDao.allGroups()
                .filter { it.type == GroupType.SUBSCRIPTION }
            onMainDispatcher {
                if (!isAdded || hostActivity.isFinishing || hostActivity.isDestroyed) return@onMainDispatcher
                if (subscriptionGroups.isEmpty()) {
                    PrivateDnsDialogHelper.showManagerForGroup(
                        activity = hostActivity,
                        groupId = currentGroup.id,
                        autoInspectDns = false,
                    )
                    return@onMainDispatcher
                }
                val targets = ArrayList<ProxyGroup>(subscriptionGroups.size + 1).apply {
                    add(currentGroup)
                    addAll(subscriptionGroups)
                }
                val labels = Array(targets.size) { index ->
                    val grp = targets[index]
                    if (index == 0) {
                        getString(R.string.doh_manager_current_group_item, grp.displayName())
                    } else {
                        getString(R.string.doh_manager_subscription_item, grp.displayName())
                    }
                }
                MaterialAlertDialogBuilder(hostActivity)
                    .setTitle(R.string.doh_manager_pick_target)
                    .setItems(labels) { _, which ->
                        val chosen = targets.getOrNull(which) ?: return@setItems
                        PrivateDnsDialogHelper.showManagerForGroup(
                            activity = hostActivity,
                            groupId = chosen.id,
                            autoInspectDns = chosen.type == GroupType.SUBSCRIPTION,
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openSubscriptionConfigFromMainMenu() {
        runOnDefaultDispatcher {
            val currentGroup = DataStore.currentGroup()
            if (currentGroup.type == GroupType.SUBSCRIPTION) {
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    startActivity(
                        Intent(requireContext(), SubscriptionConfigActivity::class.java).apply {
                            putExtra(SubscriptionConfigActivity.EXTRA_GROUP_ID, currentGroup.id)
                        }
                    )
                }
                return@runOnDefaultDispatcher
            }
            val subscriptionGroups = SagerDatabase.groupDao.allGroups()
                .filter { it.type == GroupType.SUBSCRIPTION }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                when {
                    subscriptionGroups.isEmpty() -> snackbar(R.string.group_not_subscription).show()
                    subscriptionGroups.size == 1 -> {
                        startActivity(
                            Intent(requireContext(), SubscriptionConfigActivity::class.java).apply {
                                putExtra(SubscriptionConfigActivity.EXTRA_GROUP_ID, subscriptionGroups.single().id)
                            }
                        )
                    }
                    else -> {
                        val names = subscriptionGroups.map { it.displayName() }.toTypedArray()
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.subscription_config_view_edit)
                            .setItems(names) { _, which ->
                                val chosen = subscriptionGroups.getOrNull(which) ?: return@setItems
                                startActivity(
                                    Intent(requireContext(), SubscriptionConfigActivity::class.java).apply {
                                        putExtra(SubscriptionConfigActivity.EXTRA_GROUP_ID, chosen.id)
                                    }
                                )
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun speedTestStageLabel(stage: String): String = when (stage) {
        SpeedTestQueueRunner.STAGE_DISCOVERY -> getString(R.string.speed_test_stage_discovery)
        SpeedTestQueueRunner.STAGE_LATENCY -> getString(R.string.speed_test_stage_latency)
        SpeedTestQueueRunner.STAGE_DOWNLOAD -> getString(R.string.speed_test_stage_download)
        SpeedTestQueueRunner.STAGE_UPLOAD -> getString(R.string.speed_test_stage_upload)
        SpeedTestQueueRunner.STAGE_COMPLETE -> getString(R.string.speed_test_stage_complete)
        SpeedTestQueueRunner.STAGE_CANCELLED -> getString(R.string.speed_test_stage_cancelled)
        SpeedTestQueueRunner.STAGE_ERROR -> getString(R.string.speed_test_stage_error)
        else -> getString(R.string.speed_test_stage_pending)
    }

    private fun formatSpeedTestSnapshot(
        snapshot: SpeedTestSnapshot,
        safeProfileName: String,
        hideAddress: Boolean,
    ): String {
        val stage = speedTestStageLabel(snapshot.stage)
        val ctx = context ?: app
        return buildString {
            append(safeProfileName).append(" — ").append(stage)
            if (snapshot.downloadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_download_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.downloadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(ctx, snapshot.downloadBytes),
                )
            )
            if (snapshot.uploadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_upload_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.uploadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(ctx, snapshot.uploadBytes),
                )
            )
            if (snapshot.latencyMs > 0) append('\n').append(
                getString(R.string.speed_test_latency_format, snapshot.latencyMs)
            )
            val server = listOf(snapshot.serverName, snapshot.serverCountry)
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .let { if (hideAddress) AddressRedactor.scrub(it) else it }
            if (server.isNotBlank()) append('\n').append(getString(R.string.speed_test_server_format, server))
            if (snapshot.error.isNotBlank()) {
                val safeErr = Protocols.sanitizeErrorMessage(snapshot.error)
                append('\n').append(if (hideAddress) AddressRedactor.scrub(safeErr) else safeErr)
            }
        }
    }

    inner class TestDialog(private val testToken: Int) {
        val binding = LayoutProgressListBinding.inflate(layoutInflater).apply {
            progressCircular.isGone = true
            progressLinear.isVisible = true
            progressLinear.max = 1
            progressLinear.setProgressCompat(0, false)
        }
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        @Volatile
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        @Volatile
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (!DataStore.isTestTokenActive(testToken)) return
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                if (!DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                val hideAddress = DataStore.hideNodeAddress
                val safeName = AddressRedactor.beanTitle(profile.requireBean(), hideAddress)
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2,
                    safeName,
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        val raw = Protocols.sanitizeErrorMessage(profile.error.orEmpty())
                        profileStatusText = if (hideAddress) AddressRedactor.scrub(raw) else raw
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        val raw = Protocols.sanitizeErrorMessage(profile.error.orEmpty())
                        profileStatusText = if (hideAddress) AddressRedactor.scrub(raw) else raw
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        val displayMsg = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusText = if (hideAddress) AddressRedactor.scrub(displayMsg) else displayMsg
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + safeName)
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText ?: "",
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
                if (proxyN > 0) {
                    binding.progressLinear.max = proxyN
                    binding.progressLinear.setProgressCompat(progress.coerceAtMost(proxyN), true)
                }
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean, force: Boolean = false) {
        if (!isAdded) return
        val testToken = acquireOrPromptTestToken(force) {
            pingTest(icmpPing, force = true)
        }
        if (testToken == 0) return

        val test = TestDialog(testToken)
        val dialog = test.builder.show()
        val testJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        val group = DataStore.currentGroup()
        val groupDns = group.effectiveNodeDnsList()

        var mainJob: Job? = null
        val handle = ActiveTestHandle(token = testToken, dialog = dialog) {
            test.dialogStatus.set(2)
            mainJob?.cancel()
            synchronized(testJobs) { testJobs.toList() }.forEach { it.cancel() }
        }
        activeTestHandle = handle

        test.cancel = {
            handle.cancel(dismissDialog = true)
        }
        test.minimize = {
            if (DataStore.isTestTokenActive(testToken)) {
                test.dialogStatus.set(1)
                val notif = ConnectionTestNotification(
                    dialog.context,
                    "[${group.displayName()}] ${getString(R.string.connection_test)}"
                )
                test.notification = notif
                handle.notification = notif
                handle.isHidden = true
                dialog.hide()
            }
        }

        mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                    if (icmpPing) {
                        it.requireBean().canICMPing()
                    } else {
                        it.requireBean().canTCPing()
                    }
                }
                test.proxyN = profilesList.size
                if (profilesList.isEmpty()) {
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (isAdded && DataStore.isTestTokenActive(testToken)) {
                        test.binding.progressLinear.max = profilesList.size
                        test.binding.progressLinear.setProgressCompat(0, false)
                        test.binding.progress.text = "0 / ${profilesList.size}"
                    }
                }
                val profiles = ConcurrentLinkedQueue(profilesList)
                val workerCount = minOf(DataStore.connectionTestConcurrent.coerceAtLeast(1), profilesList.size)
                repeat(workerCount) {
                    testJobs.add(launch(Dispatchers.IO) {
                        while (isActive && DataStore.isTestTokenActive(testToken)) {
                            val profile = profiles.poll() ?: break

                            profile.status = 0
                            var address = profile.requireBean().serverAddress.orEmpty()
                            if (!address.isIpAddress()) {
                                // Honor group-level DNS when configured; otherwise resolve via
                                // underlyingNetwork with FakeIP rejection to avoid TUN poisoning.
                                val resolved = if (groupDns.isNotEmpty() || !group.requiresNodeDnsAdoption()) {
                                    runCatching { IpInfoStore.resolveEntry(address, groupDns) }.getOrNull()
                                } else {
                                    null
                                }
                                if (resolved != null) {
                                    address = resolved
                                }
                            }
                            if (!isActive || !DataStore.isTestTokenActive(testToken)) break
                            if (!address.isIpAddress()) {
                                profile.status = 2
                                profile.error = app.getString(R.string.connection_test_domain_not_found)
                                test.update(profile)
                                continue
                            }
                            try {
                                if (icmpPing) {
                                    // ICMP ping disabled in favor of TCPing/URLTest.
                                } else {
                                    val socket =
                                        SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                            ?: Socket()
                                    try {
                                        socket.soTimeout = 3000
                                        socket.bind(InetSocketAddress(0))
                                        val start = SystemClock.elapsedRealtime()
                                        socket.connect(
                                            InetSocketAddress(
                                                address, profile.requireBean().serverPort!!
                                            ), 3000
                                        )
                                        if (!isActive || !DataStore.isTestTokenActive(testToken)) break
                                        profile.status = 1
                                        profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                        test.update(profile)
                                    } finally {
                                        socket.closeQuietly()
                                    }
                                }
                            } catch (e: Exception) {
                                if (!isActive || !DataStore.isTestTokenActive(testToken)) break
                                val message = Protocols.sanitizeErrorMessage(e.readableMessage)

                                if (icmpPing) {
                                    profile.status = 2
                                    profile.error = app.getString(R.string.connection_test_unreachable)
                                } else {
                                    profile.status = 2
                                    when {
                                        !message.contains("failed:") -> profile.error =
                                            app.getString(R.string.connection_test_timeout)

                                        else -> when {
                                            message.contains("ECONNREFUSED") -> {
                                                profile.error =
                                                    app.getString(R.string.connection_test_refused)
                                            }

                                            message.contains("ENETUNREACH") -> {
                                                profile.error =
                                                    app.getString(R.string.connection_test_unreachable)
                                            }

                                            else -> {
                                                profile.status = 3
                                                profile.error = message
                                            }
                                        }
                                    }
                                }
                                test.update(profile)
                            }
                        }
                    })
                }

                synchronized(testJobs) { testJobs.toList() }.joinAll()
            } finally {
                withContext(NonCancellable) {
                    // Persist results only if this test token was NOT superseded by a forced newer run.
                    if (DataStore.isTestTokenActive(testToken)) {
                        test.results.forEach {
                            try {
                                ProfileManager.updateProfile(it)
                            } catch (e: Exception) {
                                Logs.w(e)
                            }
                        }
                        GroupManager.postReload(group.id)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        test.dialogStatus.set(2)
                        runCatching { dialog.dismiss() }
                        test.notification?.updateNotification(0, 0, true)
                        test.notification = null
                        handle.notification = null
                        handle.dialog = null
                        handle.isHidden = false
                        if (activeTestHandle === handle) {
                            activeTestHandle = null
                        }
                    }
                    DataStore.releaseTestToken(testToken)
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest(force: Boolean = false) {
        if (!isAdded) return
        val testToken = acquireOrPromptTestToken(force) {
            urlTest(force = true)
        }
        if (testToken == 0) return

        val test = TestDialog(testToken)
        val dialog = test.builder.show()
        val testJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        val group = DataStore.currentGroup()

        var mainJob: Job? = null
        val handle = ActiveTestHandle(token = testToken, dialog = dialog) {
            test.dialogStatus.set(2)
            mainJob?.cancel()
            synchronized(testJobs) { testJobs.toList() }.forEach { it.cancel() }
        }
        activeTestHandle = handle

        test.cancel = {
            handle.cancel(dismissDialog = true)
        }
        test.minimize = {
            if (DataStore.isTestTokenActive(testToken)) {
                test.dialogStatus.set(1)
                val notif = ConnectionTestNotification(
                    dialog.context,
                    "[${group.displayName()}] ${getString(R.string.connection_test)}"
                )
                test.notification = notif
                handle.notification = notif
                handle.isHidden = true
                dialog.hide()
            }
        }

        mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
                test.proxyN = profilesList.size
                if (profilesList.isEmpty()) {
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (isAdded && DataStore.isTestTokenActive(testToken)) {
                        test.binding.progressLinear.max = profilesList.size
                        test.binding.progressLinear.setProgressCompat(0, false)
                        test.binding.progress.text = "0 / ${profilesList.size}"
                    }
                }
                val profiles = ConcurrentLinkedQueue(profilesList)
                val workerCount = minOf(DataStore.connectionTestConcurrent.coerceAtLeast(1), profilesList.size)
                repeat(workerCount) {
                    testJobs.add(launch(Dispatchers.IO) {
                        val urlTest = UrlTest() // note: this is NOT in bg process
                        while (isActive && DataStore.isTestTokenActive(testToken)) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0

                            try {
                                val result = urlTest.doTest(profile)
                                profile.status = 1
                                profile.ping = result
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: PluginManager.PluginNotFoundException) {
                                profile.status = 2
                                profile.error = Protocols.sanitizeErrorMessage(e.readableMessage)
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = Protocols.sanitizeErrorMessage(e.readableMessage)
                            }

                            test.update(profile)
                        }
                    })
                }

                synchronized(testJobs) { testJobs.toList() }.joinAll()
            } finally {
                withContext(NonCancellable) {
                    // Only write back results when this run still owns the active token;
                    // superseded runs must never overwrite newer test results in Room.
                    if (DataStore.isTestTokenActive(testToken)) {
                        test.results.forEach {
                            try {
                                ProfileManager.updateProfile(it)
                            } catch (e: Exception) {
                                Logs.w(e)
                            }
                        }
                        GroupManager.postReload(group.id)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        test.dialogStatus.set(2)
                        runCatching { dialog.dismiss() }
                        test.notification?.updateNotification(0, 0, true)
                        test.notification = null
                        handle.notification = null
                        handle.dialog = null
                        handle.isHidden = false
                        if (activeTestHandle === handle) {
                            activeTestHandle = null
                        }
                    }
                    DataStore.releaseTestToken(testToken)
                }
            }
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        groupTabDivider?.isGone = hideTab
                        toolbar.elevation = 0F
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index < 0) return@post
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index < 0) return@post
                groupList[index] = group
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        fun applyLayoutMode() {
            if (!::configurationListView.isInitialized) return
            val mode = DataStore.profileLayoutMode
            layoutManager = if (mode == 1) {
                androidx.recyclerview.widget.GridLayoutManager(context, 2)
            } else {
                FixedLinearLayoutManager(configurationListView)
            }
            configurationListView.layoutManager = layoutManager
            adapter?.notifyDataSetChanged()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            val byDownload = menu.findItem(R.id.action_order_by_download_speed)
            val byUpload = menu.findItem(R.id.action_order_by_upload_speed)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin?.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName?.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay?.isChecked = true
                }

                GroupOrder.BY_DOWNLOAD_SPEED -> {
                    byDownload?.isChecked = true
                }

                GroupOrder.BY_UPLOAD_SPEED -> {
                    byUpload?.isChecked = true
                }
            }

            val layoutSingle = menu.findItem(R.id.action_layout_single)
            val layoutDouble = menu.findItem(R.id.action_layout_double)
            if (DataStore.profileLayoutMode == 1) {
                layoutDouble?.isChecked = true
            } else {
                layoutSingle?.isChecked = true
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            fun updateLayout(mode: Int) {
                if (DataStore.profileLayoutMode == mode) return
                DataStore.profileLayoutMode = mode
                // Apply to all active group fragments in parent
                (parentFragment as? ConfigurationFragment)?.childFragmentManager?.fragments?.forEach { frag ->
                    (frag as? GroupFragment)?.applyLayoutMode()
                }
            }

            origin?.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName?.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay?.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
            byDownload?.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DOWNLOAD_SPEED)
                true
            }
            byUpload?.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_UPLOAD_SPEED)
                true
            }
            layoutSingle?.setOnMenuItemClickListener {
                it.isChecked = true
                updateLayout(0)
                true
            }
            layoutDouble?.setOnMenuItemClickListener {
                it.isChecked = true
                updateLayout(1)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }
            configurationListView = view.findViewById(R.id.configuration_list)
            applyLayoutMode()
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!select) {
                val hostActivity = activity as? MainActivity ?: return
                undoManager = UndoSnackbarManager(hostActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroyView() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }
            if (::undoManager.isInitialized) {
                undoManager.flush()
            }
            super.onDestroyView()
        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()

            fun updateSpeedTestResult(profileId: Long, outcome: SpeedTestOutcome) {
                val profile = configurationList[profileId] ?: return
                profile.speedTestMode = outcome.mode
                profile.speedTestDownloadBitsPerSecond = outcome.downloadBitsPerSecond
                profile.speedTestUploadBitsPerSecond = outcome.uploadBitsPerSecond
                if (profile.status != 1) {
                    profile.status = 0
                    profile.error = null
                }
                val index = configurationIdList.indexOf(profileId)
                if (index >= 0) notifyItemChanged(index)
            }

            /**
             * Updates cached topology fields in-place on the adapter's live [ProxyEntity]
             * reference and triggers an immediate item rebind so concurrent traffic ticks
             * (`cbTrafficUpdate`) never overwrite the card with stale topology state.
             */
            fun updateTopologyResult(
                profileId: Long,
                entryIp: String,
                entryProvider: String,
                exitIp: String,
                exitProvider: String,
                updatedAt: Long,
            ) {
                val profile = configurationList[profileId] ?: return
                profile.entryIp = entryIp
                profile.entryProvider = entryProvider
                profile.exitIp = exitIp
                profile.exitProvider = exitProvider
                profile.ipInfoUpdatedAt = updatedAt
                val index = configurationIdList.indexOf(profileId)
                if (index >= 0) {
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                    if (holder != null && holder.entity.id == profileId) {
                        holder.bind(profile)
                    } else {
                        notifyItemChanged(index)
                    }
                }
            }

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                val hideAddress = DataStore.hideNodeAddress
                configurationIdList.addAll(configurationList.filter { (_, entity) ->
                    if (hideAddress) {
                        val safeTitle = AddressRedactor.beanTitle(entity.requireBean(), true)
                        safeTitle.lowercase().contains(lower) ||
                            entity.displayType().lowercase().contains(lower)
                    } else {
                        entity.displayName().lowercase().contains(lower) ||
                            entity.displayType().lowercase().contains(lower) ||
                            entity.displayAddress().lowercase().contains(lower)
                    }
                }.keys)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                configurationListView.post {
                    val index = configurationIdList.indexOf(profile.id)
                    if (index < 0) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val oldProfile = configurationList[profile.id]
                    // WHY: Preserve live rx/tx counters directly on the new entity before binding
                    // instead of scheduling a second TrafficData callback that could re-bind a
                    // recycled or not-yet-swapped holder.entity with stale fields.
                    if (noTraffic && oldProfile != null) {
                        profile.rx = oldProfile.rx
                        profile.tx = oldProfile.tx
                    }
                    configurationList[profile.id] = profile
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                    if (holder != null && holder.entity.id == profile.id) {
                        holder.bind(profile)
                    } else {
                        notifyItemChanged(index)
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    onMainDispatcher {
                        if (!isAdded) return@onMainDispatcher
                        val index = configurationIdList.indexOf(data.id)
                        if (index < 0) return@onMainDispatcher
                        val latestEntity = configurationList[data.id]
                        if (latestEntity != null) {
                            latestEntity.rx = data.rx
                            latestEntity.tx = data.tx
                        }
                        val holder = layoutManager.findViewByPosition(index)
                            ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                        if (holder != null && holder.entity.id == data.id) {
                            holder.bind(latestEntity ?: holder.entity, data)
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                configurationListView.post {
                    val index = configurationIdList.indexOf(profileId)
                    if (index < 0) return@post
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                reloadProfiles()
            }

            fun reloadProfiles() {
                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }
                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles =
                            newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }

                    GroupOrder.BY_DOWNLOAD_SPEED -> {
                        newProfiles =
                            newProfiles.sortedByDescending { it.speedTestDownloadBitsPerSecond }
                    }

                    GroupOrder.BY_UPLOAD_SPEED -> {
                        newProfiles =
                            newProfiles.sortedByDescending { it.speedTestUploadBitsPerSecond }
                    }
                }

                val newProfileMap = newProfiles.associateBy { it.id }
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationList.clear()
                    configurationList.putAll(newProfileMap)
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }
                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val cardView = view as? com.google.android.material.card.MaterialCardView
            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)
            val profileIpInfo: TextView = view.findViewById(R.id.profile_ip_info)
            val profileIpProbe: TextView = view.findViewById(R.id.profile_ip_probe)
            val profileSpeedTest: TextView = view.findViewById(R.id.profile_speed_test)
            val profileActionDivider: View = view.findViewById(R.id.profile_action_divider)
            val profileActionRow: View = view.findViewById(R.id.profile_action_row)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: View = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            private fun speedTestResultText(proxyEntity: ProxyEntity): String? {
                val outcome = SpeedTestOutcome(
                    mode = proxyEntity.speedTestMode,
                    downloadBitsPerSecond = proxyEntity.speedTestDownloadBitsPerSecond,
                    uploadBitsPerSecond = proxyEntity.speedTestUploadBitsPerSecond,
                )
                val rates = outcome.rates()
                if (rates.isEmpty()) return null
                return rates.joinToString("  ") { rate ->
                    val direction = when (rate.direction) {
                        SpeedTestDirection.DOWNLOAD -> "↓"
                        SpeedTestDirection.UPLOAD -> "↑"
                    }
                    "$direction ${getString(R.string.speed_test_rate_mbps, rate.bitsPerSecond / 1_000_000.0)}"
                }
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = parentFragment as? ConfigurationFragment ?: return
                val isDualColumn = DataStore.profileLayoutMode == 1

                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (activity as? SelectCallback)?.returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    if (!isAdded) return@onMainDispatcher
                                    if (entity.id == proxyEntity.id) {
                                        selectedView.visibility = View.VISIBLE
                                        cardView?.strokeColor = requireContext().getColorAttr(R.attr.selectedColorPrimary)
                                    }
                                    pf.refreshSurgeStatusHeader()
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService(io.nekohasekai.sagernet.bg.ReloadReason.SelectorOnly(proxyEntity.id))
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }
                    }

                    // 测速改为下方显式按钮；长按不再触发，避免与入口查询/滑动冲突。
                }

                val bean = proxyEntity.requireBean()
                val hideAddress = DataStore.hideNodeAddress
                val host = bean.serverAddress.orEmpty()
                profileName.text = AddressRedactor.cardTitle(proxyEntity.displayName(), bean.name, host,
                    getString(R.string.card_hidden_title), hideAddress)
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    // use new data
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                val rawAddress = proxyEntity.displayAddress()
                val address = when {
                    bean.name.isNullOrBlank() || !pf.alwaysShowAddress || isDualColumn -> ""
                    hideAddress -> AddressRedactor.redactAddress(rawAddress)
                    showTraffic && rawAddress.length >= 30 -> rawAddress.take(27) + "..."
                    else -> rawAddress
                }
                profileAddress.text = address
                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

                val canProbeIp = !select && pf.supportsCardIpProbe(proxyEntity)
                val ipInfoText = if (DataStore.showNodeIpOwner) {
                    pf.formatIpInfoSpannable(view.context, proxyEntity, hideAddress)
                } else {
                    null
                }
                profileIpInfo.text = ipInfoText ?: ""
                profileIpInfo.isVisible = !ipInfoText.isNullOrBlank()
                profileIpInfo.setOnClickListener(if (canProbeIp && profileIpInfo.isVisible) View.OnClickListener {
                    pf.confirmNodeIpInfo(proxyEntity.id)
                } else null)

                profileSpeedTest.isVisible = !select
                profileSpeedTest.setOnClickListener(if (select) null else View.OnClickListener {
                    pf.speedTest(proxyEntity)
                })
                profileIpProbe.isVisible = canProbeIp
                val isProbingThis = pf.probingProxyId == proxyEntity.id
                profileIpProbe.text = getString(
                    if (isProbingThis) R.string.card_ip_probe_probing else R.string.card_ip_probe_short
                )
                profileIpProbe.setOnClickListener(if (canProbeIp) View.OnClickListener {
                    pf.confirmNodeIpInfo(proxyEntity.id)
                } else null)

                val showActionRow = profileSpeedTest.isVisible || profileIpProbe.isVisible
                profileActionDivider.isVisible = showActionRow
                profileActionRow.isVisible = showActionRow

                val stText = speedTestResultText(proxyEntity)

                if (proxyEntity.status <= 0) {
                    if (stText != null) {
                        profileStatus.text = stText
                        profileStatus.setTextColor(requireContext().getColour(R.color.surge_text_secondary))
                    } else if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColour(R.color.surge_text_secondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    val pingText = getString(R.string.available, proxyEntity.ping)
                    profileStatus.text = if (stText != null) "$pingText\n$stText" else pingText
                    val latencyColorRes = when {
                        proxyEntity.ping <= 150 -> R.color.surge_green
                        proxyEntity.ping <= 350 -> R.color.surge_amber
                        else -> R.color.surge_red
                    }
                    profileStatus.setTextColor(requireContext().getColour(latencyColorRes))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.surge_red))
                    val rawError = proxyEntity.error.orEmpty()
                    val sanitizedErr = Protocols.sanitizeErrorMessage(rawError.ifBlank { "<?>" })
                    val isDnsError = rawError.contains("SERVFAIL", ignoreCase = true) ||
                        rawError.contains("NXDOMAIN", ignoreCase = true) ||
                        rawError.contains("lookup ", ignoreCase = true) ||
                        rawError.contains("no such host", ignoreCase = true)
                    val baseErr = when {
                        hideAddress -> getString(R.string.unavailable)
                        isDnsError -> getString(R.string.error_dns_lookup_failed)
                        proxyEntity.status == 2 && sanitizedErr.isNotBlank() && sanitizedErr.length <= 42 -> sanitizedErr
                        else -> {
                            val friendly = Protocols.genFriendlyMsg(rawError)
                            if (friendly.isNotBlank() && friendly != sanitizedErr && friendly.length <= 48) {
                                friendly
                            } else {
                                getString(R.string.unavailable)
                            }
                        }
                    }
                    profileStatus.text = if (stText != null) "$baseErr\n$stText" else baseErr
                    profileStatus.setOnClickListener(
                        if (hideAddress || rawError.isBlank()) {
                            null
                        } else {
                            View.OnClickListener {
                                val ctx = context ?: return@OnClickListener
                                val builder = MaterialAlertDialogBuilder(ctx)
                                    .setTitle(R.string.unavailable)
                                    .setMessage(sanitizedErr)
                                    .setPositiveButton(android.R.string.ok, null)
                                if (isDnsError) {
                                    builder.setNeutralButton(R.string.error_dialog_open_doh_manager) { _, _ ->
                                        pf.openDohManagerFromMainMenu(proxyEntity.groupId)
                                    }
                                }
                                builder.show()
                            }
                        }
                    )
                }
                if (proxyEntity.status <= 1) {
                    profileStatus.setOnClickListener(null)
                }

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    adapter?.let { listAdapter ->
                        val index = listAdapter.configurationIdList.indexOf(proxyEntity.id)
                        if (index >= 0) {
                            listAdapter.remove(index)
                            undoManager.remove(index to proxyEntity)
                        }
                    }
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN || isDualColumn
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select || isDualColumn

                proxyEntity.nekoBean?.apply {
                    shareLayout.isGone = true
                }

                val boundEntityId = proxyEntity.id
                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == boundEntityId
                    val started =
                        selected && DataStore.serviceState.started && DataStore.currentProfile == boundEntityId
                    onMainDispatcher {
                        // Guard against fast-scroll ViewHolder recycling or detached Fragment before mutating state.
                        val ctx = context ?: return@onMainDispatcher
                        if (!isAdded || entity.id != boundEntityId) return@onMainDispatcher
                        editButton.isEnabled = !started
                        removeButton.isEnabled = !started
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                        cardView?.strokeColor = if (selected) {
                            ctx.getColorAttr(R.attr.selectedColorPrimary)
                        } else {
                            ctx.getColour(R.color.card_stroke)
                        }
                    }

                    fun showShare(anchor: View) {
                        val ctx = context ?: return
                        val popup = PopupMenu(ctx, anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        if (proxyEntity.nekoBean != null) {
                            popup.menu.removeItem(R.id.action_group_configuration)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            val ctx = context ?: return@onMainDispatcher
                            if (!isAdded || entity.id != boundEntityId) return@onMainDispatcher
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.setColorFilter(ctx.getColour(R.color.card_icon_tint))
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                if (isAdded && entity.id == boundEntityId) {
                                    showShare(it)
                                }
                            }
                        }
                    }
                }

            }

            /** Resolve only via the saved group DNS; never reuse card IPs or call an attribution API. */
            private fun <T> exportWithResolvedIp(
                render: (ProxyEntity, String?) -> T,
                publish: (T) -> Unit,
            ) {
                val profile = entity
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val (result, unresolved) = withContext(Dispatchers.IO) {
                            val groupDns = SagerDatabase.groupDao.getById(profile.groupId)?.effectiveNodeDnsList().orEmpty()
                            val snapshot = io.nekohasekai.sagernet.ipinfo.NodeShareExport(groupDns)
                            render(profile, snapshot.entryAddress(profile)) to snapshot.unresolvedCount
                        }
                        if (!isAdded) return@launch
                        publish(result)
                        if (unresolved != 0) {
                            (activity as? MainActivity)?.snackbar(R.string.share_entry_ip_unavailable)?.show()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        (activity as? MainActivity)?.snackbar(R.string.action_export_err)?.show()
                    }
                }
            }

            fun showCode(link: String, name: String) {
                QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as? MainActivity)?.snackbar(
                    if (success) R.string.action_export_msg else R.string.action_export_err
                )?.show()
            }

            /**
             * Confirms with the user if an exported sing-box JSON still embeds private
             * `dns-group-*` server URLs (which may contain subscription tokens/paths).
             */
            private fun confirmConfigExportIfPrivateDns(configText: String, onProceed: () -> Unit) {
                if (!configText.contains("\"dns-group-")) {
                    onProceed()
                    return
                }
                val ctx = context ?: return
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.action_export_file)
                    .setMessage(R.string.export_config_private_dns_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ -> onProceed() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    when (item.itemId) {
                        R.id.action_standard_qr, R.id.action_universal_qr -> {
                            val name = AddressRedactor.beanTitle(
                                entity.requireBean(),
                                DataStore.hideNodeAddress,
                            )
                            val universal = item.itemId == R.id.action_universal_qr
                            exportWithResolvedIp({ profile, ip -> profile.exportLink(universal, ip) }) {
                                showCode(it, name)
                            }
                        }
                        R.id.action_standard_clipboard, R.id.action_universal_clipboard -> {
                            val universal = item.itemId == R.id.action_universal_clipboard
                            exportWithResolvedIp({ profile, ip -> profile.exportLink(universal, ip) }, ::export)
                        }
                        R.id.action_config_export_clipboard -> exportWithResolvedIp(ProxyEntity::exportConfig) { config ->
                            confirmConfigExportIfPrivateDns(config.first) {
                                export(config.first)
                            }
                        }
                        R.id.action_config_export_file -> exportWithResolvedIp(ProxyEntity::exportConfig) { config ->
                            confirmConfigExportIfPrivateDns(config.first) {
                                DataStore.serverConfig = config.first
                                (parentFragment as? ConfigurationFragment)?.let { parent ->
                                    startFilesForResult(parent.exportConfig, config.second)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as? MainActivity)?.snackbar(Protocols.sanitizeErrorMessage(e.readableMessage))?.show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                val resolver = context?.contentResolver ?: return@registerForActivityResult
                runOnDefaultDispatcher {
                    try {
                        val stream = resolver.openOutputStream(data)
                            ?: throw java.io.IOException("Failed to open output stream")
                        stream.bufferedWriter().use {
                            it.write(DataStore.serverConfig)
                        }
                        onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            if (!isAdded) return@onMainDispatcher
                            snackbar(Protocols.sanitizeErrorMessage(e.readableMessage)).show()
                        }
                    }

                }
            }
        }

}
