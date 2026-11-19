package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class MASQUESettingsActivity : ProfileSettingsActivity<MASQUEBean>() {

    override fun createEntity() = MASQUEBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val publicKey = pbm.add(PreferenceBinding(Type.Text, "publicKey"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val localAddressV6 = pbm.add(PreferenceBinding(Type.Text, "localAddressV6"))
    private val uri = pbm.add(PreferenceBinding(Type.Text, "uri"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val network = pbm.add(PreferenceBinding(Type.Text, "network"))
    private val congestionController = pbm.add(PreferenceBinding(Type.Text, "congestionController"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val remoteDnsResolve = pbm.add(PreferenceBinding(Type.Bool, "remoteDnsResolve"))
    private val udp = pbm.add(PreferenceBinding(Type.Bool, "udp"))

    override fun MASQUEBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun MASQUEBean.serialize() {
        pbm.fromCacheAll(this)
        if (mtu == null || mtu!! <= 0) mtu = 1280
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.masque_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    }
}
