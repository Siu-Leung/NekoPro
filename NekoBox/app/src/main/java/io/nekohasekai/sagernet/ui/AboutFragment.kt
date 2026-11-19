package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings

import android.view.View
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import java.util.concurrent.atomic.AtomicBoolean

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        childFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()


    }

    class AboutContent : MaterialAboutFragment() {

        private val checkingUpdate = AtomicBoolean(false)

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    openReleaseUrl(requireContext(), AppUpdatePolicy.RELEASES_PAGE_URL)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    checkUpdate()
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "mihomo"))
                                .subText(Libcore.mihomoVersion())
                                .build())
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.branch_info)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_action_dns)
                                .text(R.string.branch_desc)
                                .subText(R.string.branch_desc_detail)
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(R.string.supported_protocols)
                                .subText(R.string.supported_protocols_detail)
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
        }

        private fun openReleaseUrl(context: Context, url: String) {
            val safeUrl = if (
                url == AppUpdatePolicy.RELEASES_PAGE_URL ||
                AppUpdatePolicy.isAllowedReleaseUrl(url)
            ) {
                url
            } else {
                AppUpdatePolicy.RELEASES_PAGE_URL
            }
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
            }.onFailure {
                Logs.w(it)
                Toast.makeText(app, it.readableMessage, Toast.LENGTH_SHORT).show()
            }
        }

        fun checkUpdate() {
            if (!checkingUpdate.compareAndSet(false, true)) return
            runOnIoDispatcher {
                val client = Libcore.newHttpClient().apply {
                    modernTLS()
                    trySocks5(DataStore.mixedPort)
                }
                try {
                    val response = client.newRequest().apply {
                        setURL(AppUpdatePolicy.LATEST_RELEASE_API_URL)
                        setUserAgent("NekoPro/${BuildConfig.VERSION_NAME}")
                        setHeader("Accept", "application/vnd.github+json")
                    }.execute()
                    val release = AppUpdatePolicy.parseLatestRelease(
                        Util.getStringBox(response.contentString)
                    )
                    val haveUpdate = AppUpdatePolicy.isUpdateAvailable(
                        BuildConfig.VERSION_NAME,
                        release.version,
                    )
                    runOnMainDispatcher {
                        if (!isAdded) return@runOnMainDispatcher
                        if (haveUpdate) {
                            val context = requireContext()
                            MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.update_dialog_title)
                                .setMessage(
                                    context.getString(
                                        R.string.update_dialog_message,
                                        SagerNet.appVersionNameForDisplay,
                                        release.displayName,
                                    )
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    openReleaseUrl(context, release.releaseUrl)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        } else {
                            Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    runOnMainDispatcher {
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    client.close()
                    checkingUpdate.set(false)
                }
            }
        }

    }

}