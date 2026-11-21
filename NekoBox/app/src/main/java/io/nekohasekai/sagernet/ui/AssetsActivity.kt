package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.security.AssetImportPolicy
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val fileName = runCatching {
                contentResolver.query(file, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: (file.lastPathSegment ?: "")
                .substringAfterLast('/')
                .substringAfter(':')

            if (!fileName.endsWith(".db")) {
                alert(getString(R.string.route_not_asset, fileName)).show()
                return@registerForActivityResult
            }
            val filesDir = getExternalFilesDir(null) ?: filesDir

            // Security: confine the destination to filesDir (rejects "../", separators, etc.).
            val outFile = runCatching { AssetImportPolicy.destination(filesDir, fileName) }
                .getOrElse {
                    alert(getString(R.string.route_not_asset, fileName)).show()
                    return@registerForActivityResult
                }

            runOnDefaultDispatcher {
                runCatching {
                    val parent = outFile.parentFile ?: filesDir
                    parent.mkdirs()
                    val tmpFile = File.createTempFile("${outFile.name}.", ".tmp", parent)
                    try {
                        contentResolver.openInputStream(file)?.use { input ->
                            tmpFile.outputStream().use { input.copyTo(it) }
                        } ?: error("Unable to open selected file")
                        if (tmpFile.length() == 0L) error("Imported asset file is empty")
                        if (!tmpFile.renameTo(outFile)) {
                            tmpFile.copyTo(outFile, overwrite = true)
                            tmpFile.delete()
                        }
                    } finally {
                        if (tmpFile.exists()) tmpFile.delete()
                    }

                    File(parent, outFile.nameWithoutExtension + ".version.txt").apply {
                        if (isFile) delete()
                        FileWriter(this).use { it.write("Custom") }
                    }

                    resetAssetCache(outFile.name)
                    adapter.reloadAssets()
                    if (DataStore.serviceState.started) {
                        onMainDispatcher {
                            snackbar(R.string.need_reload)
                                .setAction(R.string.apply) { SagerNet.reloadService() }
                                .show()
                        }
                    }
                }.onFailure {
                    onMainDispatcher {
                        alert(it.readableMessage).tryToShow()
                    }
                }
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            assets.clear()
            assets.add(File(filesDir, "geoip.db"))
            assets.add(File(filesDir, "geosite.db"))
            if (files != null) assets.addAll(files)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage).show()
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateAsset(file, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "MetaCubeX/meta-rules-dat",
            "MetaCubeX/meta-rules-dat",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo"
        ),
    )

    private fun resetAssetCache(fileName: String) {
        when (fileName) {
            "geoip.db" -> Libcore.resetGeoipCache()
            "geosite.db" -> Libcore.resetGeositeCache()
        }
    }

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val fileName = file.name

        val ruleProvider = rulesProviders.getOrElse(DataStore.rulesProvider) { rulesProviders[0] }
        val repo = ruleProvider.repoByFileName[fileName]
            ?: error("Unsupported asset: $fileName")

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            if (DataStore.serviceState.connected) {
                forceSocks5(DataStore.mixedPort)
            } else {
                trySocks5(DataStore.mixedPort)
            }
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
                setHeader("Accept", "application/vnd.github+json")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name").trim()

            if (tagName.isNotEmpty() && tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release.optString("url")}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")
                ?.takeIf { it.isNotBlank() }
                ?: error("Missing download URL for $fileName")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val parentDir = file.parentFile ?: (getExternalFilesDir(null) ?: filesDir)
            parentDir.mkdirs()
            val cacheFile = File.createTempFile("${file.name}.", ".tmp", parentDir)
            try {
                response.writeTo(cacheFile.canonicalPath)
                val minBytes = if (fileName.endsWith(".xz")) 64L else 1024L
                if (cacheFile.length() < minBytes) {
                    error("Downloaded $fileName is unexpectedly small (${cacheFile.length()} bytes)")
                }

                if (fileName.endsWith(".xz")) {
                    val extractedFile = File.createTempFile("${file.name}.", ".db.tmp", parentDir)
                    try {
                        Libcore.unxz(cacheFile.absolutePath, extractedFile.absolutePath)
                        if (extractedFile.length() < 1024L) {
                            error("Extracted $fileName is unexpectedly small (${extractedFile.length()} bytes)")
                        }
                        if (!extractedFile.renameTo(file)) {
                            extractedFile.copyTo(file, overwrite = true)
                            extractedFile.delete()
                        }
                    } finally {
                        if (extractedFile.exists()) extractedFile.delete()
                    }
                } else {
                    if (!cacheFile.renameTo(file)) {
                        cacheFile.copyTo(file, overwrite = true)
                        cacheFile.delete()
                    }
                }
            } finally {
                if (cacheFile.exists()) cacheFile.delete()
            }

            if (tagName.isNotEmpty()) {
                versionFile.writeText(tagName)
            }

            resetAssetCache(fileName)
            adapter.reloadAssets()

            onMainDispatcher {
                val bar = snackbar(R.string.route_asset_updated)
                if (DataStore.serviceState.started) {
                    bar.setAction(R.string.apply) { SagerNet.reloadService() }
                }
                bar.show()
            }
        } finally {
            client.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}