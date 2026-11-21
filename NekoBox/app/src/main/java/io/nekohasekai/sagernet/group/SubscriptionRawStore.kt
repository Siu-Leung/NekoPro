package io.nekohasekai.sagernet.group

import android.os.Process
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists the latest raw subscription response per group in app-private storage (`files/subscription_raw`),
 * allowing offline inspection, DNS candidate extraction, and manual editing without altering Room schemas.
 */
object SubscriptionRawStore {

    const val MAX_RAW_BYTES = 8 * 1024 * 1024
    const val SOURCE_MANUAL_EDIT = "manual-edit"
    const val SOURCE_CONTENT = "content"
    private const val DIR_NAME = "subscription_raw"
    private val CLASH_MARKER = Regex(
        """(?m)^[\t ]*(?:\{[\t ]*)?(?:proxies|dns|proxy-providers|rules|proxy-groups|"proxies"|"dns"|'proxies'|'dns')[\t ]*:"""
    )
    private val tmpCounter = AtomicLong(0L)
    private val writeLock = Any()

    enum class RawFormat {
        CLASH_YAML,
        JSON,
        PROXY_LIST,
    }

    data class RawMeta(
        val updatedAtMillis: Long,
        val userAgent: String,
        val byteSize: Int,
        val lineCount: Int,
        val format: RawFormat,
    )

    data class RawSnapshot(
        val text: String,
        val meta: RawMeta,
    )

    private fun defaultStoreDir(): File = File(app.filesDir, DIR_NAME)

    internal fun rawFile(groupId: Long, storeDir: File = defaultStoreDir()): File =
        File(storeDir, "$groupId.raw")

    internal fun metaFile(groupId: Long, storeDir: File = defaultStoreDir()): File =
        File(storeDir, "$groupId.meta")

    internal fun detectFormat(text: String): RawFormat {
        val cleaned = text.removePrefix("\uFEFF").trimStart()
        return when {
            cleaned.startsWith('{') || cleaned.startsWith('[') -> RawFormat.JSON
            CLASH_MARKER.containsMatchIn(cleaned.take(16384)) -> RawFormat.CLASH_YAML
            else -> RawFormat.PROXY_LIST
        }
    }

    internal fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 1
        for (ch in text) {
            if (ch == '\n') count++
        }
        return count
    }

    internal fun sanitizeSourceLabel(label: String): String {
        val cleaned = buildString(label.length.coerceAtMost(64)) {
            for (ch in label) {
                if (length >= 64) break
                if (!ch.isISOControl() && ch != '\n' && ch != '\r') {
                    append(ch)
                }
            }
        }.trim()
        return cleaned.ifEmpty { SOURCE_MANUAL_EDIT }
    }

    internal fun save(
        groupId: Long,
        text: String,
        sourceLabel: String,
        updatedAtMillis: Long = System.currentTimeMillis(),
        storeDir: File = defaultStoreDir(),
    ): RawMeta? {
        if (groupId <= 0L || text.isBlank()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RAW_BYTES) return null

        val meta = RawMeta(
            updatedAtMillis = updatedAtMillis,
            userAgent = sanitizeSourceLabel(sourceLabel),
            byteSize = bytes.size,
            lineCount = countLines(text),
            format = detectFormat(text),
        )
        val serializedMeta = buildString {
            append("updatedAt=").append(meta.updatedAtMillis).append('\n')
            append("userAgent=").append(meta.userAgent).append('\n')
            append("byteSize=").append(meta.byteSize).append('\n')
            append("lineCount=").append(meta.lineCount).append('\n')
            append("format=").append(meta.format.name).append('\n')
        }

        synchronized(writeLock) {
            if (!storeDir.exists()) {
                storeDir.mkdirs()
            }
            writeAtomically(rawFile(groupId, storeDir), bytes, storeDir, groupId, "raw")
            writeAtomically(
                metaFile(groupId, storeDir),
                serializedMeta.toByteArray(Charsets.UTF_8),
                storeDir,
                groupId,
                "meta",
            )
        }
        return meta
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
        storeDir: File,
        groupId: Long,
        suffix: String,
    ) {
        val pid = runCatching { Process.myPid() }.getOrDefault(0)
        val seq = tmpCounter.incrementAndGet()
        val tmp = File(storeDir, "$groupId.$suffix.tmp.$pid.$seq")
        try {
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    internal fun exists(groupId: Long, storeDir: File = defaultStoreDir()): Boolean {
        if (groupId <= 0L) return false
        val file = rawFile(groupId, storeDir)
        return file.isFile && file.length() > 0L
    }

    internal fun readMeta(groupId: Long, storeDir: File = defaultStoreDir()): RawMeta? {
        if (groupId <= 0L) return null
        val raw = rawFile(groupId, storeDir)
        if (!raw.isFile || raw.length() <= 0L) return null
        val metaFile = metaFile(groupId, storeDir)
        if (metaFile.isFile) {
            runCatching {
                val map = HashMap<String, String>(8)
                metaFile.readText(Charsets.UTF_8).lineSequence().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        map[line.substring(0, idx)] = line.substring(idx + 1)
                    }
                }
                val updatedAt = map["updatedAt"]?.toLongOrNull() ?: raw.lastModified()
                val userAgent = map["userAgent"].orEmpty()
                val byteSize = map["byteSize"]?.toIntOrNull() ?: raw.length().toInt()
                val lineCount = map["lineCount"]?.toIntOrNull() ?: 0
                val format = runCatching { RawFormat.valueOf(map["format"].orEmpty()) }
                    .getOrDefault(RawFormat.CLASH_YAML)
                return RawMeta(updatedAt, userAgent, byteSize, lineCount, format)
            }
        }
        return RawMeta(
            updatedAtMillis = raw.lastModified(),
            userAgent = "",
            byteSize = raw.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lineCount = 0,
            format = RawFormat.CLASH_YAML,
        )
    }

    internal fun read(groupId: Long, storeDir: File = defaultStoreDir()): RawSnapshot? {
        if (groupId <= 0L) return null
        val raw = rawFile(groupId, storeDir)
        if (!raw.isFile || raw.length() <= 0L || raw.length() > MAX_RAW_BYTES) return null
        val text = runCatching { raw.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val existingMeta = readMeta(groupId, storeDir)
        val meta = if (existingMeta != null && existingMeta.lineCount > 0) {
            existingMeta
        } else {
            RawMeta(
                updatedAtMillis = raw.lastModified(),
                userAgent = existingMeta?.userAgent.orEmpty(),
                byteSize = text.toByteArray(Charsets.UTF_8).size,
                lineCount = countLines(text),
                format = detectFormat(text),
            )
        }
        return RawSnapshot(text, meta)
    }

    internal fun delete(groupId: Long, storeDir: File = defaultStoreDir()) {
        if (groupId <= 0L) return
        synchronized(writeLock) {
            rawFile(groupId, storeDir).delete()
            metaFile(groupId, storeDir).delete()
        }
    }

    internal fun deleteAll(groupIds: Collection<Long>, storeDir: File = defaultStoreDir()) {
        for (id in groupIds) {
            delete(id, storeDir)
        }
    }
}
