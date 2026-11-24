package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.use
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object SendLog {
    // Create full log and send
    fun sendLog(context: Context, title: String) {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(report)

        val hidePrivacy = io.nekohasekai.sagernet.database.DataStore.hideNodeAddress
        fun sanitizeChunk(raw: String): String {
            val base = moe.matsuri.nb4a.Protocols.sanitizeErrorMessage(raw)
            return if (hidePrivacy) io.nekohasekai.sagernet.ipinfo.AddressRedactor.scrub(base) else base
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            try {
                val logcatText = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                logFile.appendText(sanitizeChunk(logcatText))
                process.errorStream.close()
                process.outputStream.close()
                process.waitFor()
            } finally {
                process.destroy()
            }
            logFile.appendText("\n")
        } catch (e: IOException) {
            Logs.w(e)
            logFile.appendText("Export logcat error: " + CrashHandler.formatThrowable(e))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logs.w(e)
        }

        logFile.appendText("\n")
        val nekoLog = File(SagerNet.application.cacheDir, "neko.log")
        if (nekoLog.exists()) {
            try {
                val nekoText = String(getNekoLog(512 * 1024), Charsets.UTF_8)
                logFile.appendText(sanitizeChunk(nekoText))
            } catch (e: Exception) {
                Logs.w(e)
                logFile.appendText("Export neko.log error: " + CrashHandler.formatThrowable(e))
            }
        }

        io.nekohasekai.sagernet.ktx.runOnMainDispatcher {
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
                return@runOnMainDispatcher
            }
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            ).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        }
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            val len = file.length()
            FileInputStream(file).use { stream ->
                var skipped = false
                if (max in 1 until len) {
                    var remaining = len - max
                    while (remaining > 0) {
                        val n = stream.skip(remaining)
                        if (n <= 0) break
                        remaining -= n
                    }
                    skipped = true
                }
                val bytes = stream.readBytes()
                if (!skipped || bytes.isEmpty()) return@use bytes
                // WHY: When tailing the last `max` bytes of a larger ring log, the slice boundary
                // lands inside an arbitrary line (or mid-UTF8/ANSI sequence). Advance to the next
                // newline when one exists so the log console always starts cleanly on a whole line.
                val firstNewline = bytes.indexOf('\n'.code.toByte())
                if (firstNewline in 0 until bytes.lastIndex) {
                    return@use bytes.copyOfRange(firstNewline + 1, bytes.size)
                }
                var start = 0
                while (start < bytes.size && start < 3 && (bytes[start].toInt() and 0xC0) == 0x80) {
                    start++
                }
                if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes
            }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
