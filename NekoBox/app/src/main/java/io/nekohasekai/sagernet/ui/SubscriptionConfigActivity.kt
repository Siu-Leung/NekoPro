package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.insert
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionConfigBinding
import io.nekohasekai.sagernet.group.ClashDnsCandidates
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.StoredSubscriptionConfig
import io.nekohasekai.sagernet.group.SubscriptionConfigStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.ExtendedKeyboard
import moe.matsuri.nb4a.utils.Util

/**
 * FlClash-inspired viewer and editor for a subscription group's locally persisted raw
 * configuration file (`YAML` / `JSON` / `Surge .conf` / `URI list`).
 *
 * Allows users to:
 * 1. View or edit the downloaded subscription configuration file directly on device.
 * 2. Jump straight to the `dns:` section or preview only the `dns:` snippet.
 * 3. Extract and adopt Private DoH / DNS endpoints into the group's Private DNS setting.
 * 4. Re-fetch the raw configuration with selectable Clash/FlClash User-Agents, or save
 *    edits and re-parse the group's proxy nodes atomically.
 */
class SubscriptionConfigActivity : ThemedActivity() {

    private lateinit var binding: LayoutSubscriptionConfigBinding
    private var groupId: Long = 0L
    private var focusDnsOnLoad: Boolean = false
    private var hasUnsavedEdits: Boolean = false
    private var isSuppressingDirtyTracking: Boolean = false
    private var isBusy: Boolean = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as? SubscriptionConfigActivity)?.saveAndApplyEditedConfig()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Protect credential-bearing subscription URLs, tokens, and private DoH paths.
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        focusDnsOnLoad = intent.getBooleanExtra(EXTRA_FOCUS_DNS, false)
        if (groupId <= 0L) {
            finish()
            return
        }

        binding = LayoutSubscriptionConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.subscription_config_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        setupEditorAndKeyboard()
        setupQuickActions()
        loadInitialSubscriptionConfig()
    }

    private fun setupEditorAndKeyboard() {
        binding.editor.apply {
            setHorizontallyScrolling(true)
            addTextChangedListener {
                if (!isSuppressingDirtyTracking) {
                    hasUnsavedEdits = true
                }
            }
        }

        binding.actionTab.setOnClickListener {
            runCatching { binding.editor.insert("  ") }.onFailure { Logs.w(it) }
        }
        binding.actionUndo.setOnClickListener {
            runCatching { binding.editor.undo() }.onFailure { Logs.w(it) }
        }
        binding.actionRedo.setOnClickListener {
            runCatching { binding.editor.redo() }.onFailure { Logs.w(it) }
        }

        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { symbol ->
            runCatching { binding.editor.insert(symbol) }.onFailure { Logs.w(it) }
        }
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList(listOf(":", "-", "#", "/", ".", "_", "\"", "[", "]", "{", "}"))
        extendedKeyboard.setBackgroundColor(getColour(R.color.surge_canvas))

        val keyboardContainer = findViewById<LinearLayout>(R.id.keyboard_container)
        ViewCompat.setOnApplyWindowInsetsListener(keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = if (isImeVisible) {
                    (imeInsets.bottom - systemBarInsets.bottom).coerceAtLeast(0)
                } else {
                    0
                }
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    private fun setupQuickActions() {
        binding.btnJumpDns.setOnClickListener {
            jumpToDnsSection(showToastWhenMissing = true)
        }
        binding.btnExtractDns.setOnClickListener {
            extractPrivateDnsFromEditor()
        }
        binding.btnPreviewDnsSnippet.setOnClickListener {
            showDnsSnippetDialog()
        }
    }

    private fun setEditorTextCleanly(content: String) {
        isSuppressingDirtyTracking = true
        try {
            binding.editor.setTextContent(content)
            hasUnsavedEdits = false
        } finally {
            isSuppressingDirtyTracking = false
        }
    }

    private fun updateMetaBanner(group: ProxyGroup, stored: StoredSubscriptionConfig?) {
        supportActionBar?.subtitle = group.displayName()
        binding.subscriptionConfigMeta.text = if (stored != null) {
            val formattedSize = Formatter.formatFileSize(this, stored.sizeBytes.toLong())
            val formattedTime = Util.timeStamp2Text(stored.updatedAtMs)
            val uaLabel = stored.userAgent.ifBlank { "auto" }
            getString(R.string.subscription_config_meta_cached, formattedSize, uaLabel, formattedTime)
        } else {
            getString(R.string.subscription_config_meta_uncached)
        }
    }

    private fun loadInitialSubscriptionConfig() {
        lifecycleScope.launch {
            val (group, cached) = withContext(Dispatchers.IO) {
                val grp = SagerDatabase.groupDao.getById(groupId)
                val file = SubscriptionConfigStore.read(groupId)
                grp to file
            }
            if (group == null || group.type != GroupType.SUBSCRIPTION) {
                Toast.makeText(this@SubscriptionConfigActivity, R.string.group_not_subscription, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            updateMetaBanner(group, cached)
            if (cached != null) {
                setEditorTextCleanly(cached.content)
                if (focusDnsOnLoad) {
                    binding.editor.post { jumpToDnsSection(showToastWhenMissing = false) }
                }
            } else {
                // Automatically download the raw configuration on first open if not yet cached on disk.
                fetchRemoteSubscriptionConfig(preferredUserAgents = null)
            }
        }
    }

    private fun jumpToDnsSection(showToastWhenMissing: Boolean) {
        val currentText = binding.editor.text?.toString().orEmpty()
        val offset = SubscriptionConfigStore.findDnsSectionOffset(currentText)
        if (offset < 0) {
            if (showToastWhenMissing) {
                Toast.makeText(this, R.string.subscription_config_dns_not_found, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val safeOffset = offset.coerceIn(0, currentText.length)
        binding.editor.requestFocus()
        binding.editor.setSelection(safeOffset)
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val line = layout.getLineForOffset(safeOffset)
            val targetY = (layout.getLineTop(line) - binding.editor.height / 5).coerceAtLeast(0)
            binding.editor.scrollTo(0, targetY)
        }
    }

    private fun showDnsSnippetDialog() {
        val currentText = binding.editor.text?.toString().orEmpty()
        val snippet = SubscriptionConfigStore.extractDnsSectionSnippet(currentText)
        if (snippet.isNullOrBlank()) {
            Toast.makeText(this, R.string.subscription_config_dns_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subscription_config_view_dns_only)
            .setMessage(snippet)
            .setPositiveButton(R.string.subscription_config_extract_dns) { _, _ ->
                extractPrivateDnsFromEditor()
            }
            .setNeutralButton(R.string.action_copy) { _, _ ->
                val copied = SagerNet.trySetPrimaryClip(snippet)
                Toast.makeText(
                    this,
                    if (copied) R.string.action_export_msg else R.string.action_export_err,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun extractPrivateDnsFromEditor() {
        val currentText = binding.editor.text?.toString().orEmpty()
        if (currentText.isBlank()) {
            Toast.makeText(this, R.string.subscription_config_dns_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val (group, profile) = withContext(Dispatchers.IO) {
                val grp = SagerDatabase.groupDao.getById(groupId)
                val parsed = ClashDnsCandidates.parseOrScanInteractive(currentText)
                grp to parsed
            }
            if (group == null || isFinishing || isDestroyed) return@launch
            if (!profile.hasDns || profile.rows.isEmpty()) {
                Toast.makeText(this@SubscriptionConfigActivity, R.string.group_dns_candidates_no_dns_meta, Toast.LENGTH_LONG).show()
                return@launch
            }
            PrivateDnsDialogHelper.showCandidatePickerDialog(
                context = this@SubscriptionConfigActivity,
                profile = profile,
                initialEndpoints = group.manualNodeDns().orEmpty(),
            ) { selectedEndpoints ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val freshGroup = SagerDatabase.groupDao.getById(groupId) ?: return@withContext
                        freshGroup.saveManualNodeDns(selectedEndpoints)
                        GroupManager.updateGroup(freshGroup)
                    }
                    if (DataStore.serviceState.connected) {
                        SagerNet.reloadService()
                    }
                    Toast.makeText(
                        this@SubscriptionConfigActivity,
                        R.string.private_dns_saved_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun fetchRemoteSubscriptionConfig(preferredUserAgents: List<String>?) {
        if (isBusy) return
        lifecycleScope.launch {
            val group = withContext(Dispatchers.IO) { SagerDatabase.groupDao.getById(groupId) }
            val link = group?.subscription?.link.orEmpty()
            if (group == null || link.isBlank()) {
                Toast.makeText(this@SubscriptionConfigActivity, R.string.group_dns_candidates_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            isBusy = true
            binding.configLoadingProgress.isVisible = true
            val customUa = group.subscription?.customUserAgent?.trim()?.takeIf { it.isNotEmpty() }
            val forceProxy = DataStore.serviceState.connected
            try {
                val stored = withContext(Dispatchers.IO) {
                    val (body, winningUa) = RawUpdater.fetchSubscriptionConfigText(
                        link = link,
                        userAgents = preferredUserAgents ?: RawUpdater.DNS_CANDIDATE_USER_AGENTS,
                        forceProxy = forceProxy,
                        customUserAgent = if (preferredUserAgents == null) customUa else null,
                    )
                    SubscriptionConfigStore.save(groupId, body, winningUa)
                    SubscriptionConfigStore.read(groupId)
                }
                if (stored != null && !isFinishing && !isDestroyed) {
                    updateMetaBanner(group, stored)
                    setEditorTextCleanly(stored.content)
                    if (focusDnsOnLoad) {
                        binding.editor.post { jumpToDnsSection(showToastWhenMissing = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@SubscriptionConfigActivity, R.string.group_dns_candidates_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                isBusy = false
                if (!isFinishing && !isDestroyed) {
                    binding.configLoadingProgress.isVisible = false
                }
            }
        }
    }

    private fun showUserAgentPickerForFetch() {
        val labels = arrayOf(
            getString(R.string.group_dns_client_flclash),
            getString(R.string.group_dns_client_verge),
            getString(R.string.group_dns_client_meta),
            getString(R.string.group_dns_client_nekopro),
        )
        val agents = RawUpdater.DNS_CANDIDATE_USER_AGENTS
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_dns_candidates_pick_client)
            .setItems(labels) { _, which ->
                val chosen = listOfNotNull(agents.getOrNull(which))
                fetchRemoteSubscriptionConfig(preferredUserAgents = chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun saveAndApplyEditedConfig() {
        if (isBusy) return
        val editedText = binding.editor.text?.toString().orEmpty()
        if (editedText.isBlank()) {
            Toast.makeText(this, R.string.no_proxies_found_in_subscription, Toast.LENGTH_SHORT).show()
            return
        }
        isBusy = true
        binding.configLoadingProgress.isVisible = true
        lifecycleScope.launch {
            try {
                val committedCount = withContext(Dispatchers.IO) {
                    val group = SagerDatabase.groupDao.getById(groupId)
                        ?: error("Subscription group no longer exists")
                    RawUpdater.applyEditedSubscriptionConfig(group, editedText, byUser = true)
                }
                hasUnsavedEdits = false
                if (DataStore.serviceState.connected) {
                    SagerNet.reloadService()
                }
                Toast.makeText(
                    this@SubscriptionConfigActivity,
                    getString(R.string.subscription_config_applied_toast, committedCount),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                MaterialAlertDialogBuilder(this@SubscriptionConfigActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(e.readableMessage)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                isBusy = false
                if (!isFinishing && !isDestroyed) {
                    binding.configLoadingProgress.isVisible = false
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.subscription_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_fetch_remote_config -> {
                fetchRemoteSubscriptionConfig(preferredUserAgents = null)
                return true
            }
            R.id.action_switch_fetch_ua -> {
                showUserAgentPickerForFetch()
                return true
            }
            R.id.action_apply -> {
                saveAndApplyEditedConfig()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (hasUnsavedEdits) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else {
            super.onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (hasUnsavedEdits) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            return true
        }
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_FOCUS_DNS = "focus_dns"
    }
}
