package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.language.json.JsonLanguage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionRawBinding
import io.nekohasekai.sagernet.group.ClashDnsCandidates
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.SubscriptionRawStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays and edits the raw pulled subscription payload (Clash YAML, JSON, or URI list)
 * for a subscription group, with live DNS inspection and safe local re-application.
 */
class SubscriptionRawActivity : ThemedActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_FETCH_IF_EMPTY = "fetchIfEmpty"
        const val EXTRA_LINK_OVERRIDE = "linkOverride"
        private const val HIGHLIGHT_LIMIT_BYTES = 512 * 1024

        fun intent(
            context: Context,
            groupId: Long,
            fetchIfEmpty: Boolean = false,
            linkOverride: String? = null,
        ): Intent = Intent(context, SubscriptionRawActivity::class.java).apply {
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_FETCH_IF_EMPTY, fetchIfEmpty)
            if (!linkOverride.isNullOrBlank()) {
                putExtra(EXTRA_LINK_OVERRIDE, linkOverride)
            }
        }
    }

    private lateinit var binding: LayoutSubscriptionRawBinding
    private var groupId: Long = 0L
    private var proxyGroup: ProxyGroup? = null
    private var dirty = false
    private var suppressDirty = false
    private var busy = false
    private var currentSourceLabel: String = ""
    private var activeDialog: AlertDialog? = null
    private var liveInspectJob: Job? = null
    private var pendingExportText: String = ""

    private val exportRawLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            val content = pendingExportText
            pendingExportText = ""
            if (uri == null || content.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                            it.write(content)
                        }
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    snackbar(
                        if (result.isSuccess) R.string.action_export_msg else R.string.action_export_err
                    ).show()
                }
            }
        }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Subscription YAML files often embed tokens, credentials, and private DoH URLs.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        groupId = intent?.getLongExtra(EXTRA_GROUP_ID, 0L) ?: 0L
        if (groupId <= 0L) {
            finish()
            return
        }

        binding = LayoutSubscriptionRawBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.raw_sub_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        setupEditor()
        setupInsets()

        onBackPressedDispatcher.addCallback(this) {
            handleExitRequest()
        }

        val fetchIfEmpty = intent?.getBooleanExtra(EXTRA_FETCH_IF_EMPTY, false) == true
        val linkOverride = intent?.getStringExtra(EXTRA_LINK_OVERRIDE)
        loadInitialContent(fetchIfEmpty, linkOverride)
    }

    private fun setupEditor() {
        binding.editor.apply {
            setHorizontallyScrolling(true)
            addTextChangedListener {
                if (suppressDirty) return@addTextChangedListener
                dirty = true
                scheduleLiveInspect()
            }
        }

        binding.actionTab.setOnClickListener {
            runCatching { binding.editor.insert(binding.editor.tab()) }
        }
        binding.actionUndo.setOnClickListener {
            runCatching { binding.editor.undo() }
        }
        binding.actionRedo.setOnClickListener {
            runCatching { binding.editor.redo() }
        }

        binding.extendedKeyboard.setKeyListener { ch ->
            runCatching { binding.editor.insert(ch) }
        }
        binding.extendedKeyboard.setHasFixedSize(true)
        binding.extendedKeyboard.submitList(listOf(":", "-", "{", "}", "[", "]", "#", "\"", "/"))

        binding.rawDnsChip.setOnClickListener { detectAndPickDns() }
        binding.btnDetectDns.setOnClickListener { detectAndPickDns() }
        binding.btnRefetch.setOnClickListener { showClientRefetchPicker() }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = if (imeVisible) {
                    (imeInsets.bottom - systemBarInsets.bottom).coerceAtLeast(0)
                } else {
                    0
                }
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    private fun loadInitialContent(fetchIfEmpty: Boolean, linkOverride: String?) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val group = SagerDatabase.groupDao.getById(groupId)
                val snapshot = SubscriptionRawStore.read(groupId)
                group to snapshot
            }
            if (isFinishing || isDestroyed) return@launch
            val (group, snapshot) = loaded
            if (group == null || group.type != GroupType.SUBSCRIPTION) {
                finish()
                return@launch
            }
            proxyGroup = group
            supportActionBar?.title = "${getString(R.string.raw_sub_title)} · ${group.displayName()}"

            if (snapshot != null) {
                applyEditorSnapshot(snapshot.text, snapshot.meta.userAgent, isInitial = true)
            } else if (fetchIfEmpty) {
                confirmAndFetchRaw(userAgents = null, linkOverride = linkOverride)
            } else {
                applyEditorSnapshot("", "", isInitial = true)
            }
        }
    }

    private fun applyEditorSnapshot(text: String, sourceLabel: String, isInitial: Boolean) {
        currentSourceLabel = sourceLabel
        val byteSize = text.toByteArray(Charsets.UTF_8).size
        val largeJson = text.trimStart().startsWith('{') && byteSize <= HIGHLIGHT_LIMIT_BYTES
        binding.editor.language = if (largeJson) JsonLanguage() else null

        if (byteSize > HIGHLIGHT_LIMIT_BYTES) {
            binding.rawNoticeBanner.isVisible = true
            binding.rawNoticeBanner.text = getString(
                R.string.raw_sub_large_readonly,
                Formatter.formatFileSize(this, byteSize.toLong()),
            )
        } else {
            binding.rawNoticeBanner.isVisible = false
        }

        suppressDirty = true
        binding.editor.setTextContent(text)
        suppressDirty = false
        dirty = !isInitial

        updateSummaryAndInspectNow(text)
    }

    private fun scheduleLiveInspect() {
        liveInspectJob?.cancel()
        val currentText = binding.editor.text.toString()
        liveInspectJob = lifecycleScope.launch {
            delay(400L)
            updateSummaryAndInspectNow(currentText)
        }
    }

    private fun updateSummaryAndInspectNow(text: String) {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.Default) {
                val bytes = text.toByteArray(Charsets.UTF_8).size
                val lines = SubscriptionRawStore.countLines(text)
                val format = SubscriptionRawStore.detectFormat(text)
                val profile = if (text.isNotBlank()) ClashDnsCandidates.parse(text) else null
                val selectableCount = profile?.rows?.count { it.selectable != null } ?: 0
                Quad(bytes, lines, format, selectableCount)
            }
            if (isFinishing || isDestroyed) return@launch
            val (bytes, lines, format, selectableCount) = stats
            val formatLabel = when (format) {
                SubscriptionRawStore.RawFormat.CLASH_YAML -> getString(R.string.raw_sub_format_yaml)
                SubscriptionRawStore.RawFormat.JSON -> getString(R.string.raw_sub_format_json)
                SubscriptionRawStore.RawFormat.PROXY_LIST -> getString(R.string.raw_sub_format_list)
            }
            val sizeText = Formatter.formatFileSize(this@SubscriptionRawActivity, bytes.toLong())
            val sourceDisplay = currentSourceLabel.ifBlank { SubscriptionRawStore.SOURCE_MANUAL_EDIT }
            binding.rawFormatBadge.text = formatLabel
            binding.rawMetaSummary.text = getString(
                R.string.raw_sub_meta_summary,
                formatLabel,
                sizeText,
                lines,
                sourceDisplay,
            )

            val chipColor = ContextCompat.getColor(
                this@SubscriptionRawActivity,
                if (selectableCount > 0) R.color.surge_green else R.color.surge_amber,
            )
            binding.rawDnsChip.text = if (selectableCount > 0) {
                getString(R.string.raw_sub_dns_found_chip, selectableCount)
            } else {
                getString(R.string.raw_sub_dns_none_chip)
            }
            binding.rawDnsChip.setTextColor(chipColor)
            binding.rawDnsChip.backgroundTintList = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(chipColor, 28)
            )
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun setBusy(loading: Boolean) {
        busy = loading
        binding.rawProgress.isVisible = loading
    }

    private fun detectAndPickDns() {
        val text = binding.editor.text.toString()
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.Default) {
                ClashDnsCandidates.parse(text)
            }
            if (isFinishing || isDestroyed) return@launch
            val group = proxyGroup ?: return@launch
            if (profile.malformed || !profile.hasDns || profile.rows.none { it.selectable != null }) {
                activeDialog = MaterialAlertDialogBuilder(this@SubscriptionRawActivity)
                    .setTitle(R.string.group_dns_candidates_title)
                    .setMessage(R.string.raw_sub_dns_none_dialog)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.raw_sub_refetch) { _, _ ->
                        showClientRefetchPicker()
                    }
                    .show()
                return@launch
            }

            showInlineDnsPicker(group, profile)
        }
    }

    private fun showInlineDnsPicker(group: ProxyGroup, profile: ClashDnsCandidates.Profile) {
        activeDialog = ClashDnsCandidatePickerDialog.show(
            context = this,
            profile = profile,
            preselected = group.effectiveNodeDnsList(),
        ) { chosen ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val fresh = SagerDatabase.groupDao.getById(group.id) ?: return@withContext
                    fresh.saveManualNodeDns(chosen.joinToString("\n"))
                    GroupManager.updateGroup(fresh)
                    proxyGroup = fresh
                }
                if (!isFinishing && !isDestroyed) {
                    snackbar(getString(R.string.raw_sub_dns_saved, chosen.size)).show()
                }
            }
        }
    }

    private fun showClientRefetchPicker() {
        val labels = arrayOf(
            getString(R.string.raw_sub_client_default_chain),
            getString(R.string.group_dns_client_flclash),
            getString(R.string.group_dns_client_verge),
            getString(R.string.group_dns_client_meta),
            getString(R.string.group_dns_client_nekopro),
        )
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.raw_sub_fetch_pick_client)
            .setItems(labels) { _, which ->
                val agents = if (which == 0) null else listOfNotNull(RawUpdater.DNS_CANDIDATE_USER_AGENTS.getOrNull(which - 1))
                confirmAndFetchRaw(userAgents = agents, linkOverride = intent?.getStringExtra(EXTRA_LINK_OVERRIDE))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmAndFetchRaw(userAgents: List<String>?, linkOverride: String?) {
        val group = proxyGroup ?: return
        val link = (linkOverride ?: group.subscription?.link).orEmpty().trim()
        if (link.isEmpty()) {
            snackbar(R.string.raw_sub_enter_link_first).show()
            return
        }
        val connected = DataStore.serviceState.connected
        val needsWarning = !connected && (
            link.startsWith("http://", ignoreCase = true) ||
                group.subscription?.updateWhenConnectedOnly == true
            )
        if (needsWarning) {
            activeDialog = MaterialAlertDialogBuilder(this)
                .setMessage(R.string.update_subscription_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    executeFetchRaw(group, link, userAgents, forceProxy = DataStore.serviceState.connected)
                }
                .show()
        } else {
            executeFetchRaw(group, link, userAgents, forceProxy = connected)
        }
    }

    private fun executeFetchRaw(
        group: ProxyGroup,
        link: String,
        userAgents: List<String>?,
        forceProxy: Boolean,
    ) {
        if (busy) return
        setBusy(true)
        snackbar(R.string.raw_sub_fetching).show()
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    RawUpdater.fetchAndSaveRawSubscription(
                        proxyGroup = group,
                        linkOverride = link,
                        userAgents = userAgents,
                        forceProxy = forceProxy,
                    )
                }
                if (isFinishing || isDestroyed) return@launch
                applyEditorSnapshot(snapshot.text, snapshot.meta.userAgent, isInitial = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                if (!isFinishing && !isDestroyed) {
                    activeDialog = MaterialAlertDialogBuilder(this@SubscriptionRawActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(getString(R.string.raw_sub_fetch_failed, e.readableMessage))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private fun saveRawOnly(finishAfter: Boolean = false) {
        val text = binding.editor.text.toString()
        val label = SubscriptionRawStore.SOURCE_MANUAL_EDIT
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                SubscriptionRawStore.save(groupId, text, label)
            }
            if (isFinishing || isDestroyed) return@launch
            if (saved == null) {
                snackbar(R.string.action_export_err).show()
            } else {
                dirty = false
                currentSourceLabel = saved.userAgent
                updateSummaryAndInspectNow(text)
                if (finishAfter) {
                    finish()
                } else {
                    snackbar(R.string.raw_sub_saved_only).show()
                }
            }
        }
    }

    private fun applyEditedConfig() {
        if (busy) return
        val text = binding.editor.text.toString()
        setBusy(true)
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val fresh = SagerDatabase.groupDao.getById(groupId)
                        ?: error(getString(R.string.group_not_subscription))
                    proxyGroup = fresh
                    RawUpdater.applyRawSubscriptionText(
                        proxyGroup = fresh,
                        rawText = text,
                        sourceLabel = SubscriptionRawStore.SOURCE_MANUAL_EDIT,
                    )
                }
                if (isFinishing || isDestroyed) return@launch
                dirty = false
                currentSourceLabel = SubscriptionRawStore.SOURCE_MANUAL_EDIT
                snackbar(getString(R.string.raw_sub_applied, summary.proxyCount, summary.changed)).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                if (!isFinishing && !isDestroyed) {
                    activeDialog = MaterialAlertDialogBuilder(this@SubscriptionRawActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(e.readableMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.raw_sub_extract_dns) { _, _ ->
                            detectAndPickDns()
                        }
                        .show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private fun handleExitRequest() {
        if (!dirty) {
            finish()
            return
        }
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.raw_sub_apply) { _, _ -> applyEditedConfig() }
            .setNeutralButton(R.string.raw_sub_save_only) { _, _ -> saveRawOnly(finishAfter = true) }
            .setNegativeButton(R.string.no) { _, _ -> finish() }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.subscription_raw_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleExitRequest()
                true
            }
            R.id.action_apply -> {
                applyEditedConfig()
                true
            }
            R.id.action_save_only -> {
                saveRawOnly(finishAfter = false)
                true
            }
            R.id.action_extract_dns -> {
                detectAndPickDns()
                true
            }
            R.id.action_refetch -> {
                showClientRefetchPicker()
                true
            }
            R.id.action_copy -> {
                val ok = SagerNet.trySetPrimaryClip(binding.editor.text.toString())
                snackbar(if (ok) R.string.raw_sub_copied else R.string.action_export_err).show()
                true
            }
            R.id.action_export_file -> {
                val name = proxyGroup?.displayName()?.replace(Regex("[\\\\/:*?\"<>|]"), "_").orEmpty().ifBlank { "subscription" }
                val ext = if (SubscriptionRawStore.detectFormat(binding.editor.text.toString()) == SubscriptionRawStore.RawFormat.JSON) "json" else "yaml"
                pendingExportText = binding.editor.text.toString()
                startFilesForResult(exportRawLauncher, "${name}_raw.$ext")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        liveInspectJob?.cancel()
        activeDialog?.dismiss()
        super.onDestroy()
    }
}
