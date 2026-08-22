package io.nekohasekai.sagernet.bg

sealed interface ReloadReason {
    data class SelectorOnly(val profileId: Long) : ReloadReason
    data object RouteModeChanged : ReloadReason
    data object CoreConfigChanged : ReloadReason
    data object DnsChanged : ReloadReason
    data object TunChanged : ReloadReason
    data object ClashApiChanged : ReloadReason
}

object ReloadDecision {
    fun requiresFullRebuild(reason: ReloadReason): Boolean {
        return when (reason) {
            is ReloadReason.SelectorOnly -> false
            else -> true
        }
    }

    fun shouldSelectOnly(reason: ReloadReason, currentFingerprint: String, targetFingerprint: String): Boolean {
        return reason is ReloadReason.SelectorOnly && currentFingerprint == targetFingerprint
    }
}
