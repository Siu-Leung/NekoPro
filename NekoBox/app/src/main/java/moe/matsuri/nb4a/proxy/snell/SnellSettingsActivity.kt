package moe.matsuri.nb4a.proxy.snell

import android.os.Bundle
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

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {
    override fun createEntity() = SnellBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val psk = pbm.add(PreferenceBinding(Type.Text, "psk"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val mode = pbm.add(PreferenceBinding(Type.Text, "mode"))
    private val obfsMode = pbm.add(PreferenceBinding(Type.Text, "obfsMode"))
    private val obfsHost = pbm.add(PreferenceBinding(Type.Text, "obfsHost"))

    override fun SnellBean.init() {
        version = (version ?: 4).coerceIn(4, 6)
        if (mode.isNullOrBlank()) mode = "default"
        pbm.writeToCacheAll(this)
    }

    override fun SnellBean.serialize() {
        pbm.fromCacheAll(this)
        version = (version ?: 4).coerceIn(4, 6)
        if (mode.isNullOrBlank()) mode = "default"
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)
        pbm.setPreferenceFragment(this)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("psk")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        mode.preference.isVisible = version.readStringToIntFromCache() == 6
        version.preference.setOnPreferenceChangeListener { _, newValue ->
            mode.preference.isVisible = newValue?.toString()?.toIntOrNull() == 6
            true
        }
    }
}
