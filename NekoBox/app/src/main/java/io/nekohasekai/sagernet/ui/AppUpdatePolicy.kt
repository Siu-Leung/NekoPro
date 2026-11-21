package io.nekohasekai.sagernet.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String = "",
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease == other.preRelease) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        private val SEMVER_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")

        fun parseOrNull(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val match = SEMVER_REGEX.find(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch, match.groupValues[4])
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val partsA = a.split('.')
            val partsB = b.split('.')
            val count = minOf(partsA.size, partsB.size)
            for (i in 0 until count) {
                val pa = partsA[i]
                val pb = partsB[i]
                if (pa == pb) continue
                val na = pa.toIntOrNull()
                val nb = pb.toIntOrNull()
                return when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1
                    nb != null -> 1
                    else -> pa.compareTo(pb)
                }
            }
            return partsA.size.compareTo(partsB.size)
        }
    }
}

data class AppReleaseInfo(
    val tagName: String,
    val displayName: String,
    val releaseUrl: String,
    val version: SemVer,
)

object AppUpdatePolicy {
    const val REPOSITORY_OWNER = "Siu-Leung"
    const val REPOSITORY_NAME = "NekoPro"
    const val RELEASES_PAGE_URL = "https://github.com/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/latest"

    private const val GITHUB_HOST = "github.com"
    private const val ALLOWED_RELEASE_PATH_PREFIX = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/"
    private val ALLOWED_ASSET_HOST_SUFFIXES = listOf(".github.com", ".githubusercontent.com")
    private val ENCODED_TRAVERSAL_REGEX = Regex("""%(?:2[eEfF]|5[cC])""")

    /**
     * Checks whether [rawPath] contains literal or percent-encoded path traversal segments.
     */
    private fun containsPathTraversal(rawPath: String): Boolean {
        if (rawPath.contains('\\') || ENCODED_TRAVERSAL_REGEX.containsMatchIn(rawPath)) {
            return true
        }
        return rawPath.split('/').any { segment -> segment == ".." || segment == "." }
    }

    /**
     * Validates that [url] strictly points to an official NekoPro GitHub release page
     * over HTTPS with no userinfo, custom port, or path traversal bypasses.
     */
    fun isAllowedReleaseUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.trim() != url || url.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != GITHUB_HOST || uri.port != -1 || uri.rawUserInfo != null) {
            return false
        }
        val rawPath = uri.rawPath ?: return false
        if (containsPathTraversal(rawPath)) return false
        val normalizedPath = uri.normalize().rawPath ?: return false
        return normalizedPath.startsWith(ALLOWED_RELEASE_PATH_PREFIX)
    }

    /**
     * Validates that a GitHub release asset `browser_download_url` uses HTTPS on a trusted
     * GitHub download host without embedded credentials or path traversal segments.
     */
    fun isAllowedAssetDownloadUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.trim() != url || url.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        val isTrustedHost = host == GITHUB_HOST || ALLOWED_ASSET_HOST_SUFFIXES.any { host.endsWith(it) }
        if (!isTrustedHost) return false
        val rawPath = uri.rawPath ?: return false
        return rawPath.startsWith("/") && !containsPathTraversal(rawPath)
    }

    fun parseLatestRelease(jsonText: String): AppReleaseInfo {
        val parsed = JsonParser.parseString(jsonText)
        if (!parsed.isJsonObject) {
            error("Invalid release response")
        }
        val root = parsed.asJsonObject
        val tagName = root.stringOrEmpty("tag_name")
        val name = root.stringOrEmpty("name")
        if (tagName.isEmpty() && name.isEmpty()) {
            val apiMessage = root.stringOrEmpty("message")
            error(apiMessage.ifEmpty { "Release response missing version info" })
        }
        if (root.booleanOrFalse("draft")) {
            error("Latest release is a draft")
        }
        val displayName = name.ifEmpty { tagName }
        val version = SemVer.parseOrNull(tagName) ?: SemVer.parseOrNull(name)
            ?: error("Unable to parse release version: $displayName")
        val rawUrl = root.stringOrEmpty("html_url")
        val releaseUrl = if (isAllowedReleaseUrl(rawUrl)) rawUrl else RELEASES_PAGE_URL
        return AppReleaseInfo(
            tagName = tagName.ifEmpty { displayName },
            displayName = displayName,
            releaseUrl = releaseUrl,
            version = version,
        )
    }

    fun isUpdateAvailable(currentVersionRaw: String, remoteVersion: SemVer): Boolean {
        val current = SemVer.parseOrNull(currentVersionRaw) ?: return false
        return remoteVersion > current
    }

    private fun JsonObject.stringOrEmpty(key: String): String {
        val element = get(key) ?: return ""
        if (element.isJsonNull || !element.isJsonPrimitive) return ""
        return element.asString.trim()
    }

    private fun JsonObject.booleanOrFalse(key: String): Boolean {
        val element = get(key) ?: return false
        if (element.isJsonNull || !element.isJsonPrimitive) return false
        return runCatching { element.asBoolean }.getOrDefault(false)
    }
}
