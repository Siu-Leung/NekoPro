package moe.matsuri.nb4a.plugin

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File
import java.security.MessageDigest

object Plugins {
    const val AUTHORITIES_PREFIX_SEKAI_EXE = "io.nekohasekai.sagernet.plugin."
    const val AUTHORITIES_PREFIX_NEKO_EXE = "moe.matsuri.exe."

    const val ACTION_NATIVE_PLUGIN = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"

    const val METADATA_KEY_ID = "io.nekohasekai.sagernet.plugin.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "io.nekohasekai.sagernet.plugin.executable_path"

    private const val KEY_CERT_PIN_PREFIX = "pluginCertPin:"

    fun isExe(pkg: PackageInfo): Boolean {
        if (pkg.providers?.isEmpty() == true) return false
        val provider = pkg.providers?.get(0) ?: return false
        val auth = provider.authority ?: return false
        return auth.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)
                || auth.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)
    }

    fun preferExePrefix(): String {
        return AUTHORITIES_PREFIX_NEKO_EXE
    }

    fun isUsingMatsuriExe(pluginId: String): Boolean {
        getPlugin(pluginId)?.apply {
            if (authority.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
                return true
            }
        }
        return false;
    }

    fun displayExeProvider(pkgName: String): String {
        return if (pkgName.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)) {
            "SagerNet"
        } else if (pkgName.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
            "Matsuri"
        } else {
            "Unknown"
        }
    }

    /** Built-in executables shipped inside our own APK (nativeLibraryDir). */
    fun internalSoName(pluginId: String): String? = when (pluginId) {
        "hysteria-plugin" -> "libhysteria.so"
        "hysteria2-plugin" -> "libhysteria2.so"
        else -> null
    }

    fun hasInternalPlugin(pluginId: String): Boolean {
        val so = internalSoName(pluginId) ?: return false
        return File(SagerNet.application.applicationInfo.nativeLibraryDir, so).canExecute()
    }

    fun getPlugin(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null
        // Security: always prefer binaries shipped in our own APK over third-party providers.
        if (hasInternalPlugin(pluginId)) {
            return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
        }
        getPluginExternal(pluginId)?.let { return it }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null

        // try queryIntentContentProviders
        var providers = getExtPluginOld(pluginId)

        // try PackageCache
        if (providers.isEmpty()) providers = getExtPluginNew(pluginId)

        // Security: only trust known plugin packages whose signing certificate
        // matches the one pinned on first use.
        providers = providers.filter { isTrustedProvider(pluginId, it) }

        // not found
        if (providers.isEmpty()) return null

        if (providers.size > 1) {
            val prefer = providers.filter {
                it.authority.startsWith(preferExePrefix())
            }
            if (prefer.size == 1) providers = prefer
        }

        if (providers.size > 1) {
            // May be called from background threads: never show a Toast here.
            Logs.w("Conflicting plugins found from: ${providers.joinToString { it.packageName }}")
        }

        return providers[0]
    }

    // Known official plugin signing SHA-256 allowlist (lowercase hex) per package.
    // Packages signed by the same certificate as this app are always trusted;
    // any other recognized plugin package falls back to TOFU certificate pinning.
    private val knownOfficialCertDigests: Map<String, Set<String>> = emptyMap()

    private val selfSigningDigest: String? by lazy {
        signingDigest(SagerNet.application.packageName)
    }

    private fun isTrustedProvider(pluginId: String, provider: ProviderInfo): Boolean {
        val pkg = provider.packageName ?: return false
        val expectedPkg = PluginEntry.find(pluginId)?.packageName
        if (expectedPkg == null || pkg != expectedPkg) {
            Logs.w("Rejecting untrusted plugin provider $pkg for $pluginId")
            return false
        }
        val digest = signingDigest(pkg) ?: run {
            Logs.w("Rejecting plugin $pkg: unable to read signing certificate")
            return false
        }
        val key = KEY_CERT_PIN_PREFIX + pkg
        val pinned = DataStore.configurationStore.getString(key)
        if (!pinned.isNullOrBlank()) {
            if (pinned != digest) {
                Logs.w("Rejecting plugin $pkg: signing certificate changed")
                return false
            }
            return true
        }
        val selfDigest = selfSigningDigest
        val allowlisted = knownOfficialCertDigests[pkg]
        if ((selfDigest != null && digest == selfDigest) || (allowlisted != null && digest in allowlisted)) {
            DataStore.configurationStore.putString(key, digest)
            return true
        }
        // Trust on first use for third-party official ecosystem plugins (e.g. Matsuri/SagerNet);
        // subsequent updates must keep the exact same signing certificate.
        Logs.i("Pinning first-use signing certificate for plugin $pkg")
        DataStore.configurationStore.putString(key, digest)
        return true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signingDigest(packageName: String): String? = runCatching {
        val pm = SagerNet.application.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return@runCatching null
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val sigs = signatures?.takeIf { it.isNotEmpty() } ?: return@runCatching null
        val md = MessageDigest.getInstance("SHA-256")
        sigs.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }.getOrNull()

    private fun getExtPluginNew(pluginId: String): List<ProviderInfo> {
        PackageCache.awaitLoadSync()
        val pkgs = PackageCache.installedPluginPackages
            .map { it.value }
            .filter { it.providers?.get(0)?.loadString(METADATA_KEY_ID) == pluginId }
        return pkgs.mapNotNull { it.providers?.get(0) }
    }

    private fun buildUri(id: String, auth: String) = Uri.Builder()
        .scheme("plugin")
        .authority(auth)
        .path("/$id")
        .build()

    private fun getExtPluginOld(pluginId: String): List<ProviderInfo> {
        var flags = PackageManager.GET_META_DATA
        if (Build.VERSION.SDK_INT >= 24) {
            flags =
                flags or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        }
        val list1 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")), flags
        )
        val list2 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")), flags
        )
        return (list1 + list2).mapNotNull {
            it.providerInfo
        }.filter { it.exported }
    }
}
