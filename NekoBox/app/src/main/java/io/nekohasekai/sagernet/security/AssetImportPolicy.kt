package io.nekohasekai.sagernet.security

import java.io.File

object AssetImportPolicy {
    private val encodedPathSyntax = Regex("%(?:00|2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun destination(root: File, displayName: String): File {
        require(displayName.isNotBlank()) { "Asset name is empty" }
        require('\u0000' !in displayName) { "Asset name contains NUL" }
        require('/' !in displayName && '\\' !in displayName) { "Asset name contains a separator" }
        require(".." !in displayName && !encodedPathSyntax.containsMatchIn(displayName)) {
            "Asset name contains traversal syntax"
        }
        require(displayName == File(displayName).name) { "Asset name is not a basename" }
        require(displayName.endsWith(".db")) { "Asset name must end in .db" }

        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, displayName).canonicalFile
        require(destination.parentFile == canonicalRoot) { "Asset destination escapes its directory" }
        return destination
    }
}
