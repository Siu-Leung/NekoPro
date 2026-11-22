package io.nekohasekai.sagernet.security

import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Stores one installation-scoped secret in Android's no-backup directory. */
class InstallSecretStore(
    private val directory: File,
    private val random: SecureRandom = SecureRandom(),
) {
    fun get(): String {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create no-backup directory" }
        val lockFile = File(directory, "$SECRET_FILE.lock")
        restrictToOwner(lockFile)
        return RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
            randomAccessFile.channel.lock().use {
                val secretFile = File(directory, SECRET_FILE)
                readValidSecret(secretFile) ?: createSecret(secretFile)
            }
        }
    }

    private fun createSecret(secretFile: File): String {
        val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        // java.util.Base64 is unavailable on API < 26 (not desugared); okio works everywhere.
        val secret = bytes.toByteString().base64Url().trimEnd('=')
        val temporary = File.createTempFile(".$SECRET_FILE.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(secret.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            restrictToOwner(temporary)
            check(!secretFile.exists() || secretFile.delete()) { "Unable to replace Clash API secret" }
            check(temporary.renameTo(secretFile)) { "Unable to install Clash API secret" }
            restrictToOwner(secretFile)
            return secret
        } finally {
            temporary.delete()
        }
    }

    private fun readValidSecret(file: File): String? {
        if (!file.isFile) return null
        val secret = file.readText(Charsets.US_ASCII).trim()
        val decoded = runCatching { secret.decodeBase64()?.toByteArray() }.getOrNull()
        return secret.takeIf { decoded?.size == SECRET_BYTES }
    }

    private fun restrictToOwner(file: File) {
        if (!file.exists()) return
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SECRET_FILE = "clash_api.secret"
    }
}

object ClashApiSecret {
    private val cacheLock = Any()

    @Volatile
    private var cachedSignature: Pair<Long, Long>? = null

    @Volatile
    private var cachedValue: String? = null

    /**
     * Returns the current installation-scoped Clash API secret, re-reading from disk
     * whenever the underlying file's modification time or size changes across processes.
     */
    val value: String
        get() = read(forceRefresh = false)

    /** Forces a fresh locked read from disk to synchronize UI and `:bg` processes. */
    fun refresh(): String = read(forceRefresh = true)

    private fun read(forceRefresh: Boolean): String {
        val dir = SagerNet.application.noBackupFilesDir
        val file = File(dir, "clash_api.secret")
        if (!forceRefresh && file.isFile) {
            val sig = file.lastModified() to file.length()
            val existing = cachedValue
            if (existing != null && cachedSignature == sig) {
                return existing
            }
        }
        return synchronized(cacheLock) {
            val fresh = InstallSecretStore(dir).get()
            cachedSignature = if (file.isFile) file.lastModified() to file.length() else null
            cachedValue = fresh
            fresh
        }
    }
}