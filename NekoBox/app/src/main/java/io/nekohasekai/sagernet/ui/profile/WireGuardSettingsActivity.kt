package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.normalizeWireGuardAddresses
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val allowedIps = pbm.add(PreferenceBinding(Type.Text, "allowedIps"))
    private val persistentKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepalive"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
        persistentKeepalive = (persistentKeepalive ?: 0).coerceIn(0, 65535)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (persistentKeepalive.preference as EditTextPreference).apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            setOnPreferenceChangeListener { _, newValue ->
                val raw = (newValue as? String)?.trim().orEmpty()
                if (raw.isEmpty()) return@setOnPreferenceChangeListener true
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed !in 0..65535) {
                    Toast.makeText(
                        this@WireGuardSettingsActivity,
                        "PersistentKeepalive must be between 0 and 65535",
                        Toast.LENGTH_SHORT,
                    ).show()
                    false
                } else {
                    true
                }
            }
        }
        val cidrValidator = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
            val raw = (newValue as? String).orEmpty()
            runCatching { normalizeWireGuardAddresses(raw) }.onFailure { err ->
                Toast.makeText(
                    this@WireGuardSettingsActivity,
                    err.message ?: "Invalid CIDR",
                    Toast.LENGTH_SHORT,
                ).show()
            }.isSuccess
        }
        localAddress.preference.onPreferenceChangeListener = cidrValidator
        allowedIps.preference.onPreferenceChangeListener = cidrValidator
    }

}