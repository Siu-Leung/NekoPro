package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Application
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.regex.Matcher
import java.util.regex.Pattern

object JavaUtil {

    // The encoded character of each character escape.
    private val ENCODED_ESCAPES = charArrayOf('"', '\'', '\\', 'b', 'f', 'n', 'r', 't')
    private val DECODED_ESCAPES = charArrayOf('"', '\'', '\\', '\b', '\u000C', '\n', '\r', '\t')
    private val PATTERN: Pattern =
        Pattern.compile("\\\\(?:(b|t|n|f|r|\\\"|'|\\\\)|((?:[0-3]?[0-7])?[0-7])|u+(\\p{XDigit}{4}))")

    @JvmStatic
    fun unescapeString(encodedString: CharSequence): String {
        val matcher = PATTERN.matcher(encodedString)
        val decodedString = StringBuffer()
        while (matcher.find()) {
            val ch: Char = when {
                matcher.start(1) >= 0 -> DECODED_ESCAPES[Arrays.binarySearch(ENCODED_ESCAPES, matcher.group(1)!![0])]
                matcher.start(2) >= 0 -> Integer.parseInt(matcher.group(2)!!, 8).toChar()
                else -> Integer.parseInt(matcher.group(3)!!, 16).toChar()
            }
            matcher.appendReplacement(decodedString, Matcher.quoteReplacement(ch.toString()))
        }
        matcher.appendTail(decodedString)
        return decodedString.toString()
    }

    // Webview Utils

    @JvmStatic
    fun handleWebviewDir(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val pathSet = mutableSetOf<String>()
            val dataPath = context.dataDir.absolutePath
            val webViewDir = "/app_webview"
            val huaweiWebViewDir = "/app_hws_webview"
            val lockFile = "/webview_data.lock"
            val processName = Application.getProcessName()
            if (BuildConfig.APPLICATION_ID != processName) {
                val suffix = if (TextUtils.isEmpty(processName)) context.packageName else processName
                WebView.setDataDirectorySuffix(suffix)
                val dirSuffix = "_$suffix"
                pathSet.add("$dataPath$webViewDir${dirSuffix}$lockFile")
                if (checkIsHuaweiRom()) {
                    pathSet.add("$dataPath$huaweiWebViewDir${dirSuffix}$lockFile")
                }
            } else {
                val dirSuffix = "_$processName"
                pathSet.add("$dataPath$webViewDir$lockFile")
                pathSet.add("$dataPath$webViewDir${dirSuffix}$lockFile")
                if (checkIsHuaweiRom()) {
                    pathSet.add("$dataPath$huaweiWebViewDir$lockFile")
                    pathSet.add("$dataPath$huaweiWebViewDir${dirSuffix}$lockFile")
                }
            }
            for (path in pathSet) {
                val file = File(path)
                if (file.exists()) {
                    tryLockOrRecreateFile(file)
                    break
                }
            }
        } catch (e: Exception) {
            Logs.e(e)
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun tryLockOrRecreateFile(file: File) {
        try {
            val tryLock = RandomAccessFile(file, "rw").channel.tryLock()
            if (tryLock != null) {
                tryLock.close()
            } else {
                createFile(file, file.delete())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            var deleted = false
            if (file.exists()) {
                deleted = file.delete()
            }
            createFile(file, deleted)
        }
    }

    private fun createFile(file: File, deleted: Boolean) {
        try {
            if (deleted && !file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkIsHuaweiRom(): Boolean = Build.MANUFACTURER.contains("HUAWEI")

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val getProcessName = activityThread.getDeclaredMethod("currentProcessName")
            getProcessName.invoke(null) as String
        } catch (e: Exception) {
            BuildConfig.APPLICATION_ID
        }
    }

    // Old hutool Utils

    @JvmStatic
    fun isNullOrBlank(str: String?): Boolean = str.isNullOrBlank()

    @JvmStatic
    fun isNotBlank(str: String?): Boolean = !str.isNullOrBlank()

    private val HEX_ARRAY = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    @JvmStatic
    fun isEmpty(array: ByteArray?): Boolean = array == null || array.isEmpty()

    // gson

    @JvmField
    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setLenient()
        .disableHtmlEscaping()
        .create()
}
