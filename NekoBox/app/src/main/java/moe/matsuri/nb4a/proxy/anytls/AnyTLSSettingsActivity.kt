package moe.matsuri.nb4a.proxy.anytls

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certificateSha256 = pbm.add(PreferenceBinding(Type.Text, "certificateSha256"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val idleSessionCheckInterval = pbm.add(PreferenceBinding(Type.Text, "idleSessionCheckInterval"))
    private val idleSessionTimeout = pbm.add(PreferenceBinding(Type.Text, "idleSessionTimeout"))
    private val minIdleSession = pbm.add(PreferenceBinding(Type.TextToInt, "minIdleSession"))
    private val clientMetadata = pbm.add(PreferenceBinding(Type.Text, "clientMetadata"))

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun AnyTLSBean.serialize() {
        pbm.fromCacheAll(this)
        this.certificateSha256 = parseAnyTLSCertificatePins(this.certificateSha256).joinToString("\n")
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.anytls_preferences)
        pbm.setPreferenceFragment(this)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("password")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>("minIdleSession")?.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        val certPref = findPreference<EditTextPreference>(certificates.cacheName)
        val pinPref = findPreference<EditTextPreference>(certificateSha256.cacheName)!!
        certPref?.setOnPreferenceChangeListener { _, value ->
            val newPem = (value as? String).orEmpty().trim()
            if (newPem.isNotEmpty() && !pinPref.text.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    "Clear SHA-256 certificate pin before setting custom PEM certificates",
                    Toast.LENGTH_LONG,
                ).show()
                false
            } else {
                true
            }
        }
        pinPref.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setOnPreferenceChangeListener { _, value ->
                val raw = (value as? String).orEmpty()
                if (raw.isNotBlank() && !certPref?.text.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        "Clear custom PEM certificates before setting SHA-256 certificate pin",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnPreferenceChangeListener false
                }
                try {
                    parseAnyTLSCertificatePins(raw)
                    true
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, R.string.anytls_certificate_sha256_invalid, Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
    }
}
