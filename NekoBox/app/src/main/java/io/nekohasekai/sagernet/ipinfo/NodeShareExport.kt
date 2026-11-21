package io.nekohasekai.sagernet.ipinfo

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.canExportEntryAddress
import java.util.Locale

/**
 * Creates a single export snapshot for profiles that use dedicated entry DNS.
 * Cache only within this user action: a card's address might be stale, or from system DNS.
 */
internal class NodeShareExport(
    private val endpoints: List<String>,
    private val resolve: suspend (String, List<String>) -> String? = IpInfoStore::resolveEntry,
) {
    private val answers = mutableMapOf<String, String?>()
    var unresolvedCount = 0
        private set

    /** Return a node-scoped IP answer, without changing the node, its group, or its meta data. */
    suspend fun entryAddress(profile: ProxyEntity): String? {
        if (endpoints.isEmpty()) return null
        val bean = profile.requireBean()
        val host = bean.serverAddress.orEmpty().trim()
        if (host.isBlank() || IpInfoStore.isIpLiteral(host)) return null
        if (!bean.canExportEntryAddress()) {
            unresolvedCount++
            return null
        }
        val key = host.lowercase(Locale.ROOT).trimEnd('.')
        if (!answers.containsKey(key)) answers[key] = resolve(host, endpoints)
        return answers[key]?.takeIf(IpInfoStore::isIpLiteral).also {
            if (it == null) unresolvedCount++
        }
    }

    /** Encode the original profile when no suitable group DNS result is available. */
    suspend fun link(profile: ProxyEntity, universal: Boolean = false): String =
        profile.exportLink(universal, entryAddress(profile))
}
