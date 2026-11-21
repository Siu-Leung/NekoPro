package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.Menu
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
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
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
import com.google.android.material.card.MaterialCardView
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
import io.nekohasekai.sagernet.widget.BottomPaddingInsetsListener
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

    // Never trust legacy persisted exitIp: it may have been the physical connection.
    // Keep proven exits only for this screen instance, bound to the tested profile/config.
    private data class ExitSample(val profile: ProxyEntity, val group: ProxyGroup, val owner: io.nekohasekai.sagernet.ipinfo.IpOwner)
    private val verifiedExits = mutableMapOf<Long, ExitSample>()
    private var ipInfoJob: Job? = null
    private var ipInfoDialog: AlertDialog? = null
    private var lastIpDisplayOptions: Pair<Boolean, Boolean>? = null

    @Volatile
    internal var probingProxyId: Long? = null

    private var groupTabDivider: View? = null
    private var surgeStatusCard: MaterialCardView? = null
    private var surgeStatusBeacon: View? = null
    private var surgeStatusDot: View? = null
    private var surgeActiveProfile: TextView? = null
    private var surgeStatusSubtitle: TextView? = null
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
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
                refreshToolbarSubscriptionMenu()
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
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        groupTabDivider = view.findViewById(R.id.group_tab_divider)
        surgeStatusCard = view.findViewById(R.id.surge_status_card)
        surgeStatusBeacon = view.findViewById(R.id.surge_status_beacon)
        surgeStatusDot = view.findViewById(R.id.surge_status_dot)
        surgeActiveProfile = view.findViewById(R.id.surge_active_profile)
        surgeStatusSubtitle = view.findViewById(R.id.surge_status_subtitle)
        surgeRouteModeChip = view.findViewById(R.id.surge_route_mode_chip)

        surgeStatusCard?.isGone = select
        if (!select) {
            surgeRouteModeChip?.setOnClickListener {
                val nextMode = if (DataStore.routeMode == 1) 0 else 1
                (activity as? MainActivity)?.switchRouteMode(nextMode)
            }
            surgeStatusCard?.setOnClickListener {
                scrollToSelectedProfile()
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

    /**
     * Shows subscription-only actions only when the active tab belongs to a subscription group.
     */
    private fun refreshToolbarSubscriptionMenu() {
        if (select || !isAdded) return
        val currentGroup = if (::adapter.isInitialized) {
            val selectedId = DataStore.selectedGroup
            adapter.groupList.firstOrNull { it.id == selectedId }
                ?: adapter.groupList.getOrNull(groupPager.currentItem)
        } else {
            null
        }
        val isSubscription = currentGroup?.type == GroupType.SUBSCRIPTION
        toolbar.menu.findItem(R.id.action_update_subscription)?.isVisible = isSubscription
        toolbar.menu.findItem(R.id.action_view_raw_subscription)?.isVisible = isSubscription
    }

    /**
     * Refreshes the compact Surge status strip above the node list with live connection
     * state, active proxy name (respecting screenshot address redaction), and route mode.
     */
    fun refreshSurgeStatusHeader() {
        if (select || !isAdded) return
        val card = surgeStatusCard
        val beacon = surgeStatusBeacon
        val dot = surgeStatusDot ?: return
        val titleView = surgeActiveProfile ?: return
        val subtitleView = surgeStatusSubtitle
        val modeChip = surgeRouteModeChip ?: return
        val ctx = context ?: return

        val state = DataStore.serviceState
        val stateColorRes = when (state) {
            BaseService.State.Connected -> R.color.surge_green
            BaseService.State.Connecting, BaseService.State.Stopping -> R.color.surge_amber
            else -> R.color.surge_status_idle
        }
        val stateColor = ctx.getColour(stateColorRes)
        dot.backgroundTintList = ColorStateList.valueOf(stateColor)
        beacon?.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(stateColor, 34)
        )

        val surfaceColor = ctx.getColour(R.color.surge_surface)
        card?.apply {
            if (state == BaseService.State.Connected) {
                strokeColor = ColorUtils.setAlphaComponent(stateColor, 140)
                setCardBackgroundColor(ColorUtils.blendARGB(surfaceColor, stateColor, 0.05f))
            } else {
                strokeColor = ctx.getColour(R.color.card_stroke)
                setCardBackgroundColor(surfaceColor)
            }
        }

        subtitleView?.setText(
            when (state) {
                BaseService.State.Connected -> R.string.surge_status_connected_sub
                BaseService.State.Connecting -> R.string.surge_status_connecting_sub
                BaseService.State.Stopping -> R.string.surge_status_stopping_sub
                else -> R.string.surge_status_idle_sub
            }
        )

        val isGlobal = DataStore.routeMode == 1
        modeChip.text = getString(if (isGlobal) R.string.route_mode_global else R.string.route_mode_rule)
        val accentColor = ctx.getColorAttr(R.attr.selectedColorPrimary)
        if (isGlobal) {
            modeChip.setTextColor(accentColor)
            modeChip.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 30))
        } else {
            modeChip.setTextColor(ctx.getColour(R.color.surge_text_primary))
            modeChip.backgroundTintList = null
        }

        val selectedId = DataStore.selectedProxy
        val hideAddress = DataStore.hideNodeAddress
        runOnDefaultDispatcher {
            val entity = if (selectedId > 0L) SagerDatabase.proxyDao.getById(selectedId) else null
            val displayTitle = if (entity != null) {
                val bean = entity.requireBean()
                AddressRedactor.cardTitle(
                    entity.displayName(),
                    bean.name,
                    bean.serverAddress.orEmpty(),
                    getString(R.string.card_hidden_title),
                    hideAddress,
                )
            } else {
                when (state) {
                    BaseService.State.Connected -> getString(R.string.vpn_connected)
                    BaseService.State.Connecting -> getString(R.string.connecting)
                    BaseService.State.Stopping -> getString(R.string.stopping)
                    else -> getString(R.string.surge_status_idle_hint)
                }
            }
            onMainDispatcher {
                if (isAdded && surgeActiveProfile === titleView) {
                    titleView.text = displayTitle
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
        surgeStatusCard = null
        surgeStatusBeacon = null
        surgeStatusDot = null
        surgeActiveProfile = null
        surgeStatusSubtitle = null
        surgeRouteModeChip = null
        verifiedExits.clear()
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
        refreshSurgeStatusHeader()
        activeTestHandle?.restoreIfHidden()
    }

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
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
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
                        ZipInputStream(requireContext().contentResolver.openInputStream(file)!!).use { zip ->
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
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                String(readBoundedBytes(it, 16 * 1024 * 1024), Charsets.UTF_8)
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
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
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
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
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
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

            R.id.action_view_raw_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                } else {
                    startActivity(
                        SubscriptionRawActivity.intent(
                            requireContext(),
                            group.id,
                            fetchIfEmpty = true,
                        )
                    )
                }
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
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
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
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
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

    private fun supportsCardEntry(entity: ProxyEntity): Boolean = when (entity.type) {
        ProxyEntity.TYPE_CONFIG, ProxyEntity.TYPE_CHAIN, ProxyEntity.TYPE_NEKO -> false
        else -> true
    }

    private fun ipInfoLabel(entity: ProxyEntity, group: ProxyGroup, hide: Boolean): String? {
        if (!supportsCardEntry(entity)) return null
        val host = entity.requireBean().serverAddress.orEmpty()
        val literal = host.trim().removeSurrounding("[", "]").takeIf(IpInfoStore::isIpLiteral)
        val entry = literal ?: entity.entryIp.takeIf(IpInfoStore::isIpLiteral)
        val exit = verifiedExits[entity.id]?.takeIf { sample ->
            sameProbeProfile(sample.profile, entity) && sameProbeGroup(sample.group, group)
        }?.owner
        fun row(title: Int, ip: String, provider: String) = listOf(
            getString(title), AddressRedactor.maskIp(ip, hide),
            if (hide) AddressRedactor.scrub(provider, host) else provider,
        ).filter { it.isNotBlank() }.joinToString(" ")
        return listOfNotNull(
            entry?.let { ip ->
                row(if (literal != null) R.string.card_entry_literal else R.string.card_entry_dns, ip,
                    entity.entryProvider.takeIf { entity.entryIp == ip }.orEmpty())
            },
            exit?.let { row(R.string.card_exit_sample, it.ip, it.label()) },
        ).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun sameProbeProfile(a: ProxyEntity, b: ProxyEntity) =
        a.id == b.id && a.groupId == b.groupId && a.type == b.type && a.requireBean() == b.requireBean()

    private fun sameProbeGroup(a: ProxyGroup, b: ProxyGroup) =
        a.id == b.id && a.frontProxy == b.frontProxy && a.landingProxy == b.landingProxy &&
            a.effectiveNodeDnsList() == b.effectiveNodeDnsList() && a.requiresNodeDnsAdoption() == b.requiresNodeDnsAdoption()

    private fun confirmNodeIpInfo(id: Long) {
        if (!isResumed || ipInfoDialog?.isShowing == true) return
        if (ipInfoJob?.isActive == true) {
            snackbar(R.string.card_ip_probe_busy).show()
            return
        }
        ipInfoDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.card_ip_probe)
            .setMessage(R.string.card_ip_probe_disclosure)
            .setPositiveButton(R.string.card_ip_probe) { _, _ -> requestNodeIpInfo(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNodeIpInfo(id: Long) {
        if (view == null || ipInfoJob?.isActive == true) return
        probingProxyId = id
        if (isResumed) snackbar(R.string.card_ip_probe_started).show()
        ipInfoJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { ProfileManager.postUpdate(id) }
            val result = try {
                withContext(Dispatchers.IO) {
                    val dao = SagerDatabase.proxyDao
                    val profile = dao.getById(id) ?: return@withContext null
                    val group = SagerDatabase.groupDao.getById(profile.groupId) ?: return@withContext null
                    val bean = profile.requireBean()
                    val host = bean.serverAddress.orEmpty()
                    val resolvers = group.effectiveNodeDnsList()
                    // Config/chain pseudo-beans do not expose a single entry hostname.
                    val supportedEntry = supportsCardEntry(profile)
                    val entry = if (supportedEntry && (resolvers.isNotEmpty() || !group.requiresNodeDnsAdoption() ||
                            IpInfoStore.isIpLiteral(host))) IpInfoStore.resolveEntry(host, resolvers) else null
                    val entryProvider = entry?.let { IpInfoStore.owner(it)?.label().orEmpty() }
                    val exit = if (supportedEntry) probeNodeExit(profile, group, entry) else null
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    SagerDatabase.instance.runInTransaction<IpCardProbeResult?> {
                        val current = dao.getById(id) ?: return@runInTransaction null
                        val currentGroup = SagerDatabase.groupDao.getById(current.groupId) ?: return@runInTransaction null
                        if (!sameProbeProfile(profile, current) || !sameProbeGroup(group, currentGroup)) return@runInTransaction null
                        if (entry != null) dao.updateIpInfo(
                            proxyId = id,
                            entryIp = entry,
                            entryProvider = entryProvider.orEmpty().ifBlank {
                                current.entryProvider.takeIf { current.entryIp == entry }.orEmpty()
                            },
                            exitIp = "", exitProvider = "", updatedAt = System.currentTimeMillis(),
                        )
                        // Allow an entry-only result to refresh the card without claiming an exit.
                        if (entry != null || exit != null) IpCardProbeResult(current, currentGroup, exit) else null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null // No raw resolver path, URL or node name in logs/errors.
            } finally {
                probingProxyId = null
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    ProfileManager.postUpdate(id)
                }
            }
            if (result?.exit != null) {
                verifiedExits[id] = ExitSample(result.profile, result.group, result.exit)
            } else {
                // Clear stale cached exit if a fresh re-probe could not verify an exit IP.
                verifiedExits.remove(id)
            }
            if (result != null) withContext(Dispatchers.IO) { ProfileManager.postUpdate(id) }
            if (isResumed) snackbar(getString(when {
                result == null -> R.string.card_ip_probe_failed
                result.exit == null -> R.string.card_ip_probe_entry_only
                else -> R.string.card_ip_probe_complete
            })).show()
        }
    }

    private data class IpCardProbeResult(val profile: ProxyEntity, val group: ProxyGroup, val exit: io.nekohasekai.sagernet.ipinfo.IpOwner?)

    private suspend fun probeNodeExit(profile: ProxyEntity, group: ProxyGroup, entry: String?): io.nekohasekai.sagernet.ipinfo.IpOwner? {
        val bean = profile.requireBean()
        val resolvers = group.effectiveNodeDnsList()
        // Allow exit probing when entry resolved, when the group uses non-HTTPS resolvers handled
        // natively by sing-box, or when no private group DNS adoption is required.
        val hasNonHttpsGroupDns = resolvers.any { !IpInfoStore.isHttpsResolver(it) }
        val canResolveInTestBox = entry != null || hasNonHttpsGroupDns ||
            (resolvers.isEmpty() && !group.requiresNodeDnsAdoption())
        if (!canResolveInTestBox || group.frontProxy > 0 || group.landingProxy > 0 || profile.needExternal() ||
            !bean.customConfigJson.isNullOrBlank() || !bean.customOutboundJson.isNullOrBlank()
        ) return null
        return try {
            val generated = io.nekohasekai.sagernet.fmt.buildConfig(profile, forTest = true)
            if (generated.externalIndex.any { it.chain.isNotEmpty() }) return null
            val port = io.nekohasekai.sagernet.ktx.mkPort()
            val password = IpInfoStore.newProbePassword()
            val config = IpInfoStore.exitProbeConfig(generated.config, port, password) ?: return null
            // Pass LocalResolverImpl so domain-based direct/bootstrap DNS (local/dhcp/DoH hostnames)
            // resolves via Android ConnectivityManager instead of failing on /etc/resolv.conf.
            val box = libcore.Libcore.newTestSingBoxInstance(config, moe.matsuri.nb4a.net.LocalResolverImpl)
            try {
                box.start()
                IpInfoStore.probeExit(port, password)
            } finally {
                runCatching { box.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
        if (DataStore.runningTest && !force) {
            showForceTestDialog { retryFailedSpeedTest(force = true) }
            return
        }
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
                if (!isAdded) return@onMainDispatcher
                if (failedProfiles.isEmpty()) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.speed_test_no_failed_nodes,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    speedTest(targetProfiles = failedProfiles, force = force)
                }
            }
        }
    }

    fun speedTest(
        targetProfile: ProxyEntity? = null,
        targetProfiles: List<ProxyEntity>? = null,
        force: Boolean = false,
    ) {
        if (!isAdded) return
        val testToken = acquireOrPromptTestToken(force) {
            speedTest(targetProfile, targetProfiles, force = true)
        }
        if (testToken == 0) return

        val group = DataStore.currentGroup()
        val isSingle = targetProfile != null
        val titleText = when {
            isSingle -> targetProfile!!.displayName()
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
                    profileName = profile.displayName(),
                    mode = DataStore.speedTestMode,
                    stage = SpeedTestQueueRunner.STAGE_ERROR,
                    error = error.readableMessage,
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
                onMainDispatcher {
                    if (!DataStore.isTestTokenActive(testToken)) return@onMainDispatcher
                    binding.progressLinear.max = profiles.size
                    binding.progressLinear.setProgressCompat(0, false)
                    binding.progress.text = "0 / ${profiles.size}"
                }
                runner.run(profiles) { index, total, sample ->
                    if (!DataStore.isTestTokenActive(testToken)) return@run
                    val outcome = SpeedTestOutcome.completedOrNull(
                        mode = sample.mode,
                        stage = sample.stage,
                        done = sample.done,
                        cancelled = sample.cancelled,
                        error = sample.error,
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
                    } else if (sample.done && sample.error.isNotBlank()) {
                        // 测速失败时清空过期速度值，若原本已有延迟（status==1）则保留其可用状态
                        SagerDatabase.proxyDao.updateSpeedTestFailure(sample.profileId, sample.error)
                        runOnMainDispatcher {
                            if (!DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                            adapter.groupFragments.values.forEach { fragment ->
                                fragment.adapter?.configurationList?.get(sample.profileId)?.let { p ->
                                    p.speedTestMode = ""
                                    p.speedTestDownloadBitsPerSecond = 0L
                                    p.speedTestUploadBitsPerSecond = 0L
                                    if (p.status != 1) {
                                        p.status = 2
                                        p.error = sample.error
                                    }
                                    val idx = fragment.adapter?.configurationIdList?.indexOf(sample.profileId) ?: -1
                                    if (idx >= 0) fragment.adapter?.notifyItemChanged(idx)
                                }
                            }
                        }
                    }
                    runOnMainDispatcher {
                        if (!isAdded || !DataStore.isTestTokenActive(testToken)) return@runOnMainDispatcher
                        val detail = formatSpeedTestSnapshot(sample)
                        handle.notification?.updateNotification(index + 1, total, false, detail)
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
                        snackbar(e.readableMessage).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate + NonCancellable) {
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
            }
        }
    }

    private fun formatSpeedTestSnapshot(snapshot: SpeedTestSnapshot): String {
        val stage = when (snapshot.stage) {
            SpeedTestQueueRunner.STAGE_DISCOVERY -> getString(R.string.speed_test_stage_discovery)
            SpeedTestQueueRunner.STAGE_LATENCY -> getString(R.string.speed_test_stage_latency)
            SpeedTestQueueRunner.STAGE_DOWNLOAD -> getString(R.string.speed_test_stage_download)
            SpeedTestQueueRunner.STAGE_UPLOAD -> getString(R.string.speed_test_stage_upload)
            SpeedTestQueueRunner.STAGE_COMPLETE -> getString(R.string.speed_test_stage_complete)
            SpeedTestQueueRunner.STAGE_CANCELLED -> getString(R.string.speed_test_stage_cancelled)
            SpeedTestQueueRunner.STAGE_ERROR -> getString(R.string.speed_test_stage_error)
            else -> getString(R.string.speed_test_stage_pending)
        }
        return buildString {
            append(snapshot.profileName).append(" — ").append(stage)
            if (snapshot.downloadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_download_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.downloadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(requireContext(), snapshot.downloadBytes),
                )
            )
            if (snapshot.uploadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_upload_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.uploadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(requireContext(), snapshot.uploadBytes),
                )
            )
            if (snapshot.latencyMs > 0) append('\n').append(
                getString(R.string.speed_test_latency_format, snapshot.latencyMs)
            )
            val server = listOf(snapshot.serverName, snapshot.serverCountry)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (server.isNotBlank()) append('\n').append(getString(R.string.speed_test_server_format, server))
            if (snapshot.error.isNotBlank()) append('\n').append(snapshot.error)
        }
    }

    inner class TestDialog(private val testToken: Int) {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
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
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
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
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
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
                val profiles = ConcurrentLinkedQueue(profilesList)
                repeat(DataStore.connectionTestConcurrent) {
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
                                val message = e.readableMessage

                                if (icmpPing) {
                                    profile.status = 2
                                    profile.error = getString(R.string.connection_test_unreachable)
                                } else {
                                    profile.status = 2
                                    when {
                                        !message.contains("failed:") -> profile.error =
                                            getString(R.string.connection_test_timeout)

                                        else -> when {
                                            message.contains("ECONNREFUSED") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_refused)
                                            }

                                            message.contains("ENETUNREACH") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_unreachable)
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
                val profiles = ConcurrentLinkedQueue(profilesList)
                repeat(DataStore.connectionTestConcurrent) {
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
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
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
                            refreshToolbarSubscriptionMenu()
                            refreshSurgeStatusHeader()
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

            configurationListView = view.findViewById(R.id.configuration_list)
            val baseBottom = if (select) dp2px(24) else configurationListView.paddingBottom
            configurationListView.setPadding(
                configurationListView.paddingLeft,
                configurationListView.paddingTop,
                configurationListView.paddingRight,
                baseBottom,
            )
            ViewCompat.setOnApplyWindowInsetsListener(
                configurationListView,
                BottomPaddingInsetsListener(baseBottom),
            )
            applyLayoutMode()
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!select) {

                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                    ItemTouchHelper.START,
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
                    ): Int {
                        if (!isEnabled) return 0
                        val dirs = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                        return if (DataStore.profileLayoutMode == 1) {
                            dirs or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        } else {
                            dirs
                        }
                    }

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
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
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
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    onMainDispatcher {
                        if (!isAdded) return@onMainDispatcher
                        val index = configurationIdList.indexOf(data.id)
                        if (index < 0) return@onMainDispatcher
                        val holder = layoutManager.findViewByPosition(index)
                            ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                        if (holder != null && holder.entity.id == data.id) {
                            holder.bind(holder.entity, data)
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

            val cardView = view as? MaterialCardView
            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileMetaRow: View = view.findViewById(R.id.profile_meta_row)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)
            val profileIpInfo: TextView = view.findViewById(R.id.profile_ip_info)
            val profileIpProbe: TextView = view.findViewById(R.id.profile_ip_probe)
            val profileSpeedTest: TextView = view.findViewById(R.id.profile_speed_test)
            val profileActionDivider: View = view.findViewById(R.id.profile_action_divider)
            val profileActionRow: View = view.findViewById(R.id.profile_action_row)
            val profileActionGap: View = view.findViewById(R.id.profile_action_gap)
            val profileBottomSpacer: View = view.findViewById(R.id.profile_bottom_spacer)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: View = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)
            val profileMore: ImageView = view.findViewById(R.id.profile_more)

            private var boundStarted = false
            private var boundCanProbeIp = false

            private fun applySelectionVisual(selected: Boolean) {
                val ctx = view.context
                selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                val surfaceColor = ctx.getColour(R.color.surge_surface)
                val accentColor = ctx.getColorAttr(R.attr.selectedColorPrimary)
                cardView?.apply {
                    strokeWidth = dp2px(if (selected) 2 else 1)
                    strokeColor = if (selected) accentColor else ctx.getColour(R.color.card_stroke)
                    setCardBackgroundColor(
                        if (selected) ColorUtils.blendARGB(surfaceColor, accentColor, 0.08f) else surfaceColor
                    )
                }
            }

            private fun filterShareMenu(menu: Menu, proxyEntity: ProxyEntity) {
                when {
                    !proxyEntity.haveStandardLink() -> {
                        menu.findItem(R.id.action_group_qr)?.subMenu?.removeItem(R.id.action_standard_qr)
                        menu.findItem(R.id.action_group_clipboard)?.subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }
                    !proxyEntity.haveLink() -> {
                        menu.removeItem(R.id.action_group_qr)
                        menu.removeItem(R.id.action_group_clipboard)
                    }
                }
                if (proxyEntity.nekoBean != null) {
                    menu.removeItem(R.id.action_group_configuration)
                }
            }

            private fun showShare(anchor: View) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
                filterShareMenu(popup.menu, entity)
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            private fun showMoreMenu(anchor: View) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menu.add(Menu.NONE, -101, 0, R.string.edit).isEnabled = !boundStarted
                popup.menu.add(Menu.NONE, -102, 1, R.string.card_speed_test)
                if (boundCanProbeIp) {
                    popup.menu.add(Menu.NONE, -103, 2, R.string.card_ip_probe_short)
                }
                if (entity.type != ProxyEntity.TYPE_CHAIN && entity.nekoBean == null) {
                    val shareSub = popup.menu.addSubMenu(Menu.NONE, -105, 3, R.string.share)
                    popup.menuInflater.inflate(R.menu.profile_share_menu, shareSub)
                    filterShareMenu(shareSub, entity)
                }
                popup.menu.add(Menu.NONE, -104, 4, R.string.delete).isEnabled = !boundStarted
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            private fun triggerEdit() {
                view.context.startActivity(
                    entity.settingIntent(
                        view.context, proxyGroup.type == GroupType.SUBSCRIPTION
                    )
                )
            }

            private fun triggerRemove() {
                adapter?.let { listAdapter ->
                    val index = listAdapter.configurationIdList.indexOf(entity.id)
                    if (index >= 0) {
                        listAdapter.remove(index)
                        undoManager.remove(index to entity)
                    }
                }
            }

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
                val ctx = requireContext()

                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
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
                                    if (entity.id == proxyEntity.id) {
                                        applySelectionVisual(true)
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

                    // 测速改为下方显式按钮；长按保留给 ItemTouchHelper 拖拽排序。
                }

                val bean = proxyEntity.requireBean()
                val hideAddress = DataStore.hideNodeAddress
                val host = bean.serverAddress.orEmpty()
                profileName.maxLines = if (isDualColumn) 2 else 1
                profileName.text = AddressRedactor.cardTitle(proxyEntity.displayName(), bean.name, host,
                    getString(R.string.card_hidden_title), hideAddress)
                val protoColor = ctx.getProtocolColor(proxyEntity.type)
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(protoColor)
                profileType.backgroundTintList = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(protoColor, 26)
                )

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
                profileAddress.isVisible = address.isNotBlank()
                profileMetaRow.isVisible = showTraffic || address.isNotBlank()

                val canProbeIp = !select && DataStore.showNodeIpOwner && pf.supportsCardEntry(proxyEntity)
                boundCanProbeIp = canProbeIp
                val ipInfoText = if (DataStore.showNodeIpOwner) pf.ipInfoLabel(proxyEntity, proxyGroup, hideAddress) else null
                profileIpInfo.text = ipInfoText.orEmpty()
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
                profileActionGap.isVisible = profileSpeedTest.isVisible && profileIpProbe.isVisible
                profileBottomSpacer.isVisible = !showActionRow

                val stText = speedTestResultText(proxyEntity)

                if (proxyEntity.status <= 0) {
                    if (stText != null) {
                        profileStatus.text = stText
                        profileStatus.setTextColor(ctx.getColour(R.color.surge_text_secondary))
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
                    profileStatus.setTextColor(ctx.getColour(latencyColorRes))
                } else {
                    profileStatus.setTextColor(ctx.getColour(R.color.surge_red))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = if (hideAddress) getString(R.string.unavailable)
                            else if (stText != null) "${proxyEntity.error}\n$stText" else proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: "<?>"
                    val sanitizedErr = Protocols.sanitizeErrorMessage(err)
                    val msg = Protocols.genFriendlyMsg(err)
                    val baseErr = if (!hideAddress && msg != sanitizedErr) msg else getString(R.string.unavailable)
                    profileStatus.text = if (stText != null) "$baseErr\n$stText" else baseErr
                    profileStatus.setOnClickListener(if (hideAddress) null else View.OnClickListener { alert(sanitizedErr).tryToShow() })
                } else {
                    profileStatus.setOnClickListener(null)
                }

                val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val started =
                    selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                boundStarted = started
                applySelectionVisual(selected)

                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                editButton.setOnClickListener { triggerEdit() }
                removeButton.setOnClickListener { triggerRemove() }

                val hideShare = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN || proxyEntity.nekoBean != null || isDualColumn
                shareLayout.isGone = hideShare
                editButton.isGone = select || isDualColumn
                removeButton.isGone = select || isDualColumn
                profileMore.isVisible = !select && isDualColumn
                profileMore.setOnClickListener(if (!select && isDualColumn) View.OnClickListener {
                    showMoreMenu(it)
                } else null)

                if (!hideShare) {
                    shareLayer.setBackgroundColor(Color.TRANSPARENT)
                    shareButton.setImageResource(R.drawable.ic_social_share)
                    shareButton.setColorFilter(ctx.getColour(R.color.card_icon_tint))
                    shareButton.isVisible = true
                    shareLayout.setOnClickListener { showShare(it) }
                } else {
                    shareLayout.setOnClickListener(null)
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
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
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
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.action_export_file)
                    .setMessage(R.string.export_config_private_dns_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ -> onProceed() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    when (item.itemId) {
                        -101 -> triggerEdit()
                        -102 -> (parentFragment as? ConfigurationFragment)?.speedTest(entity)
                        -103 -> (parentFragment as? ConfigurationFragment)?.confirmNodeIpInfo(entity.id)
                        -104 -> triggerRemove()
                        -105 -> return true
                        R.id.action_standard_qr, R.id.action_universal_qr -> {
                            val name = entity.displayName().orEmpty()
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
                                startFilesForResult(
                                    (parentFragment as ConfigurationFragment).exportConfig, config.second
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

}
