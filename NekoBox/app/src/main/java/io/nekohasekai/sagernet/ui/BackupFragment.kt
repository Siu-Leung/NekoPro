package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    private fun backupFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "nekobox_backup_$timestamp.json"
    }

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(content)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, backupFileName()
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                val shareDir = File(app.cacheDir, "share_backup").apply { mkdirs() }
                shareDir.listFiles()?.forEach { it.delete() }
                // Clean any legacy backup files left in root cacheDir from earlier versions.
                app.cacheDir.listFiles { f ->
                    f.isFile && f.name.startsWith("nekobox_backup_") && f.name.endsWith(".json")
                }?.forEach { it.delete() }
                val cacheFile = File(shareDir, backupFileName())
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.lastOrNull()
            ?.substringAfterLast('/')
            ?.substringAfter(':') ?: ""

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
        }

        val content = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private inline fun <T> decodeParcelArray(
        array: JSONArray,
        creator: (Parcel) -> T
    ): List<T> {
        val result = ArrayList<T>(array.length())
        for (i in 0 until array.length()) {
            val encodedEntry = array.optString(i, "")
            require(encodedEntry.isNotEmpty()) { "Backup contains empty entry at index $i" }
            val data = Util.b64Decode(encodedEntry)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                result.add(creator(parcel))
            } finally {
                parcel.recycle()
            }
        }
        return result
    }

    /**
     * Strictly decodes a [ProxyEntity] from a backup [Parcel], rejecting truncated or
     * incompatible Kryo payloads and missing protocol beans before any database reset occurs.
     *
     * @param parcel Unmarshalled backup parcel positioned at byte 0.
     * @return Validated [ProxyEntity] with a non-null protocol bean.
     * @throws IllegalArgumentException When the payload is empty, has an unsupported version/type, or lacks a bean.
     * @throws com.esotericsoftware.kryo.KryoException When the outer or inner Kryo stream is corrupted.
     */
    private fun decodeProxyEntityStrict(parcel: Parcel): ProxyEntity {
        val kryoBytes = parcel.createByteArray()
            ?: throw IllegalArgumentException("Backup profile entry has no Kryo payload")
        require(kryoBytes.isNotEmpty()) { "Backup profile entry has empty Kryo payload" }
        val entity = ProxyEntity()
        entity.deserializeFromBufferStrict(com.esotericsoftware.kryo.io.ByteBufferInput(kryoBytes))
        entity.initializeDefaultValues()
        entity.requireBean()
        return entity
    }

    /**
     * Strictly decodes a [ProxyGroup] from a backup [Parcel] without swallowing [com.esotericsoftware.kryo.KryoException].
     *
     * @param parcel Unmarshalled backup parcel positioned at byte 0.
     * @return Validated [ProxyGroup].
     * @throws IllegalArgumentException When the payload is empty or invalid.
     * @throws com.esotericsoftware.kryo.KryoException When the Kryo stream is truncated or corrupted.
     */
    private fun decodeProxyGroupStrict(parcel: Parcel): ProxyGroup {
        val kryoBytes = parcel.createByteArray()
            ?: throw IllegalArgumentException("Backup group entry has no Kryo payload")
        return io.nekohasekai.sagernet.fmt.KryoConverters.deserializeStrict(ProxyGroup(), kryoBytes)
    }

    /**
     * Imports selected sections from a parsed backup JSON object into Room databases atomically.
     *
     * All selected sections are strictly decoded and validated in memory before any table is
     * cleared so a corrupt, truncated, or future-version backup never wipes existing user data.
     *
     * @param content Parsed backup JSON payload (`version == 1`).
     * @param profile Whether to restore profiles and groups.
     * @param rule Whether to restore routing rules.
     * @param setting Whether to restore app settings.
     * @throws IllegalArgumentException When any entry fails strict validation.
     * @throws com.esotericsoftware.kryo.KryoException When any Kryo payload is corrupted.
     */
    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        val formatVersion = content.optInt("version", 0)
        require(formatVersion == 1) { "Unsupported backup format version: $formatVersion" }

        // Parse and strictly validate all selected sections before mutating any database so a
        // malformed entry never leaves existing user data wiped or in a startup crash loop.
        var profiles: List<ProxyEntity>? = null
        var groups: List<ProxyGroup>? = null
        if (profile && content.has("profiles")) {
            require(content.has("groups")) { "Backup with profiles is missing required groups section" }
            profiles = decodeParcelArray(content.getJSONArray("profiles")) { parcel ->
                decodeProxyEntityStrict(parcel)
            }
            groups = decodeParcelArray(content.getJSONArray("groups")) { parcel ->
                decodeProxyGroupStrict(parcel)
            }
        }
        val rules = if (rule && content.has("rules")) {
            decodeParcelArray(content.getJSONArray("rules")) { parcel ->
                ParcelizeBridge.createRule(parcel)
            }
        } else null
        val settings = if (setting && content.has("settings")) {
            decodeParcelArray(content.getJSONArray("settings")) { parcel ->
                KeyValuePair.CREATOR.createFromParcel(parcel)
            }
        } else null

        if (profiles != null || rules != null) {
            SagerDatabase.instance.runInTransaction {
                if (profiles != null && groups != null) {
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.proxyDao.insert(profiles)
                    SagerDatabase.groupDao.reset()
                    SagerDatabase.groupDao.insert(groups)
                }
                if (rules != null) {
                    SagerDatabase.rulesDao.reset()
                    SagerDatabase.rulesDao.insert(rules)
                }
            }
        }
        if (settings != null) {
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.reset()
                PublicDatabase.kvPairDao.insert(settings)
            }
        }
    }

}