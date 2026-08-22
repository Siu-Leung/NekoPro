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
    private val obfsMode = pbm.add(PreferenceBinding(Type.Text, "obfsMode"))
    private val obfsHost = pbm.add(PreferenceBinding(Type.Text, "obfsHost"))
    private val clientFingerprint = pbm.add(PreferenceBinding(Type.Text, "clientFingerprint"))

    override fun SnellBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun SnellBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("psk")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
