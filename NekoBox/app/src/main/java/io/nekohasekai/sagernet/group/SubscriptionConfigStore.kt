package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SagerNet
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.Properties

/**
 * Metadata and raw body of a locally persisted subscription configuration snapshot,
 * modeled after FlClash's local profile configuration storage.
 *
 * @property groupId Owning [io.nekohasekai.sagernet.database.ProxyGroup] ID.
 * @property content Raw downloaded or user-edited subscription configuration text.
 * @property userAgent HTTP `User-Agent` that produced this snapshot (or `"local-edit"`).
 * @property updatedAtMs Epoch timestamp in milliseconds when the snapshot was written.
 */
data class StoredSubscriptionConfig(
    val groupId: Long,
    val content: String,
    val userAgent: String,
    val updatedAtMs: Long,
) {
    val sizeBytes: Int get() = content.toByteArray(Charsets.UTF_8).size
}

/**
 * Manages owner-only (`0600`) local storage of downloaded subscription configuration files
 * inside Android's `noBackupFilesDir` so private DoH tokens and node credentials are never
 * exported to cloud backups while remaining inspectable and editable by the user.
 */
class SubscriptionConfigFileStore(private val baseDirectory: File) {

    /**
     * Atomically writes [content] and its metadata for [groupId].
     *
     * @throws IllegalArgumentException if [groupId] is non-positive or [content] exceeds [MAX_CONFIG_BYTES].
     */
    fun save(
        groupId: Long,
        content: String,
        userAgent: String,
        updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        require(groupId > 0L) { "groupId must be positive" }
        val encoded = content.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_CONFIG_BYTES) {
            "Subscription configuration exceeds 8 MiB limit"
        }
        ensureDirectory()
        writeAtomically(configFile(groupId), encoded)
        val props = Properties().apply {
            setProperty("userAgent", userAgent)
            setProperty("updatedAtMs", updatedAtMs.toString())
        }
        val metaBytes = ByteArrayOutputStream().also { out ->
            props.store(out, null)
        }.toByteArray()
        writeAtomically(metaFile(groupId), metaBytes)
    }

    /**
     * Reads the persisted subscription configuration for [groupId], or returns `null` if none exists.
     */
    fun read(groupId: Long): StoredSubscriptionConfig? {
        if (groupId <= 0L) return null
        val file = configFile(groupId)
        if (!file.isFile || file.length() > MAX_CONFIG_BYTES) return null
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val props = metaFile(groupId).takeIf { it.isFile }?.let { mf ->
            runCatching {
                Properties().apply { load(StringReader(mf.readText(Charsets.UTF_8))) }
            }.getOrNull()
        }
        val userAgent = props?.getProperty("userAgent").orEmpty()
        val updatedAtMs = props?.getProperty("updatedAtMs")?.toLongOrNull()?.takeIf { it > 0L }
            ?: file.lastModified()
        return StoredSubscriptionConfig(
            groupId = groupId,
            content = content,
            userAgent = userAgent,
            updatedAtMs = updatedAtMs,
        )
    }

    /**
     * Returns `true` if a local configuration file exists for [groupId].
     */
    fun exists(groupId: Long): Boolean = groupId > 0L && configFile(groupId).isFile

    /**
     * Deletes any persisted configuration and metadata files for [groupId].
     */
    fun delete(groupId: Long) {
        if (groupId <= 0L) return
        runCatching { configFile(groupId).delete() }
        runCatching { metaFile(groupId).delete() }
    }

    private fun configFile(groupId: Long): File = File(baseDirectory, "group_${groupId}.conf")

    private fun metaFile(groupId: Long): File = File(baseDirectory, "group_${groupId}.meta.json")

    private fun ensureDirectory() {
        check(baseDirectory.isDirectory || baseDirectory.mkdirs()) {
            "Unable to create subscription_configs directory"
        }
        restrictToOwner(baseDirectory, allowExecute = true)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tempFile = File.createTempFile(".${target.name}.", ".tmp", baseDirectory)
        try {
            FileOutputStream(tempFile).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            restrictToOwner(tempFile, allowExecute = false)
            if (target.exists() && !target.delete()) {
                error("Unable to overwrite ${target.name}")
            }
            check(tempFile.renameTo(target)) { "Unable to commit ${target.name}" }
            restrictToOwner(target, allowExecute = false)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun restrictToOwner(file: File, allowExecute: Boolean) {
        if (!file.exists()) return
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (allowExecute) {
            file.setExecutable(true, true)
        }
    }

    companion object {
        const val MAX_CONFIG_BYTES = 8 * 1024 * 1024
    }
}

/**
 * Process-wide accessor and DNS-section locator for persisted subscription configurations.
 */
object SubscriptionConfigStore {
    private val yamlDnsHeaderRegex = Regex("""(?m)^[\t ]*(?:dns|"dns"|'dns')[\t ]*:""")
    private val surgeGeneralRegex = Regex("""(?im)^[\t ]*(?:doh-server|dns-server)[\t ]*=""")

    private val delegate by lazy {
        SubscriptionConfigFileStore(File(SagerNet.application.noBackupFilesDir, "subscription_configs"))
    }

    fun save(groupId: Long, content: String, userAgent: String) {
        delegate.save(groupId, content, userAgent)
    }

    fun read(groupId: Long): StoredSubscriptionConfig? = delegate.read(groupId)

    fun exists(groupId: Long): Boolean = delegate.exists(groupId)

    fun delete(groupId: Long) {
        delegate.delete(groupId)
    }

    /**
     * Finds the character offset of the `dns:` block (YAML/JSON) or `doh-server`/`dns-server`
     * directive (Surge `.conf`) inside [content], or `-1` if not present.
     */
    fun findDnsSectionOffset(content: String): Int {
        yamlDnsHeaderRegex.find(content)?.let { return it.range.first }
        surgeGeneralRegex.find(content)?.let { return it.range.first }
        return -1
    }

    /**
     * Extracts a human-readable snippet of the `dns:` section (up to 60 lines) from [content]
     * for quick inspection inside the Private DNS manager dialog.
     */
    fun extractDnsSectionSnippet(content: String): String? {
        val cleaned = content.removePrefix("\uFEFF")
        val yamlMatch = Regex("""(?m)^([\t ]*)(?:dns|"dns"|'dns')[\t ]*:""").find(cleaned)
        if (yamlMatch != null) {
            val baseIndent = yamlMatch.groupValues[1].length
            val lines = cleaned.substring(yamlMatch.range.first).lineSequence().toList()
            val collected = ArrayList<String>()
            for ((idx, line) in lines.withIndex()) {
                if (idx == 0) {
                    collected.add(line)
                    continue
                }
                if (collected.size >= 60) {
                    collected.add("  # ...")
                    break
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    collected.add(line)
                    continue
                }
                val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                if (indent <= baseIndent && !trimmed.startsWith("-") && !trimmed.startsWith("}")) {
                    break
                }
                collected.add(line)
                if (indent <= baseIndent && trimmed.startsWith("}")) {
                    break
                }
            }
            return collected.joinToString("\n").trim().takeIf { it.isNotEmpty() }
        }
        val surgeLines = cleaned.lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("doh-server", ignoreCase = true) ||
                    it.startsWith("dns-server", ignoreCase = true) ||
                    it.startsWith("encrypted-dns-server", ignoreCase = true)
            }
            .take(20)
            .toList()
        return surgeLines.joinToString("\n").takeIf { it.isNotEmpty() }
    }
}
