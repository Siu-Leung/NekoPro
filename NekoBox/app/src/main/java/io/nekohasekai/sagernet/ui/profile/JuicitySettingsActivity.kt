package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class JuicitySettingsActivity : ProfileSettingsActivity<JuicityBean>() {

    override fun createEntity() = JuicityBean().applyDefaultValues()

    override fun JuicityBean.init() {
        DataStore.profileName = name ?: ""
        DataStore.serverAddress = serverAddress ?: ""
        DataStore.serverPort = serverPort ?: 443
        DataStore.serverUserId = uuid ?: ""
        DataStore.serverPassword = password ?: ""
        DataStore.serverSNI = sni ?: ""
        DataStore.serverPinnedCertChainSha256 = pinnedCertchainSha256 ?: ""
        DataStore.serverAllowInsecure = java.lang.Boolean.TRUE == allowInsecure
        DataStore.serverCongestionController = congestionControl ?: "bbr"
    }

    override fun JuicityBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUserId
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        pinnedCertchainSha256 = DataStore.serverPinnedCertChainSha256.trim()
        allowInsecure = DataStore.serverAllowInsecure
        congestionControl = DataStore.serverCongestionController
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.juicity_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}