package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import libcore.Libcore
import moe.matsuri.nb4a.ui.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreference

    private lateinit var globalCustomConfig: EditConfigPreference

    private var customRootCaPref: Preference? = null

    private fun customRootCaFile(): File = File(SagerNet.application.filesDir, "ca.pem")

    private fun updateCustomRootCaSummary() {
        val file = customRootCaFile()
        customRootCaPref?.summary = if (file.isFile && file.length() > 0) {
            getString(R.string.custom_root_ca_installed, file.length())
        } else {
            getString(R.string.custom_root_ca_empty)
        }
    }

    private val importCustomRootCa =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                runCatching {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val maxBytes = 256 * 1024
                        val buf = input.readBytes()
                        require(buf.isNotEmpty() && buf.size <= maxBytes) {
                            "Certificate file must be between 1 byte and 256 KiB"
                        }
                        buf
                    } ?: error("Unable to read selected file")
                    val certs = CertificateFactory.getInstance("X.509")
                        .generateCertificates(ByteArrayInputStream(bytes))
                    require(certs.isNotEmpty()) { "No valid X.509 PEM certificate found" }
                    val target = customRootCaFile()
                    val tmp = File(target.parentFile, "ca.pem.tmp")
                    tmp.writeBytes(bytes)
                    check(tmp.renameTo(target)) { "Failed to save ca.pem" }
                    check(Libcore.updateRootCACerts(bytes)) { "Core rejected PEM certificates" }
                    onMainDispatcher {
                        updateCustomRootCaSummary()
                        Toast.makeText(requireContext(), R.string.custom_root_ca_imported, Toast.LENGTH_SHORT).show()
                        needRestart()
                    }
                }.onFailure { err ->
                    Logs.w(err)
                    onMainDispatcher {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.custom_root_ca_invalid, err.readableMessage),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        SurgePreferenceStyler.applyTo(this)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val parsed = view.text.toString().trim().toIntOrNull()
                    DataStore.logBufSize = if (parsed == null || parsed <= 0) 50 else parsed
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        } else {
            metedNetwork.onPreferenceChangeListener = reloadListener
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        profileTrafficStatistics.onPreferenceChangeListener = reloadListener
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            (activity as MainActivity?)?.refreshNavMenu(enabled)
            if (DataStore.serviceState.connected) {
                SagerNet.reloadService()
            } else {
                needReload()
            }
            true
        }

        mixedPort.onPreferenceChangeListener = reloadListener
        appendHttpProxy.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("domain_strategy_for_remote")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("domain_strategy_for_direct")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("domain_strategy_for_server")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.GLOBAL_ALLOW_INSECURE)?.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener

        customRootCaPref = findPreference<Preference>(Key.CUSTOM_ROOT_CA)?.apply {
            updateCustomRootCaSummary()
            setOnPreferenceClickListener {
                val file = customRootCaFile()
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.custom_root_ca)
                    .setMessage(R.string.custom_root_ca_dialog_msg)
                    .setPositiveButton(R.string.custom_root_ca_import) { _, _ ->
                        startFilesForResult(importCustomRootCa, "*/*")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                if (file.isFile) {
                    builder.setNeutralButton(R.string.custom_root_ca_clear) { _, _ ->
                        file.delete()
                        Libcore.updateRootCACerts(ByteArray(0))
                        updateCustomRootCaSummary()
                        Toast.makeText(requireContext(), R.string.custom_root_ca_cleared, Toast.LENGTH_SHORT).show()
                        needRestart()
                    }
                }
                builder.show()
                true
            }
        }

        val speedTestMode = findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)
        val speedTestTimeout = findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)
        val simpleDownloadURL = findPreference<EditTextPreference>(Key.SIMPLE_DOWNLOAD_URL)

        speedTestMode?.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }
        speedTestTimeout?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        speedTestTimeout?.setOnPreferenceChangeListener { _, newValue ->
            val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_timeout_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }
        simpleDownloadURL?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        simpleDownloadURL?.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue.toString().trim()
            val valid = SpeedTestSettings.isValidHttpUrl(value)
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_url_invalid, Toast.LENGTH_SHORT).show()
            } else if (value != newValue) {
                (preference as EditTextPreference).text = value
                return@setOnPreferenceChangeListener false
            }
            valid
        }
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        updateCustomRootCaSummary()
    }

}