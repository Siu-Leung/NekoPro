package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadDecisionTest {
    @Test
    fun onlyExplicitSelectorReloadCanSkipFullRebuild() {
        assertFalse(ReloadDecision.requiresFullRebuild(ReloadReason.SelectorOnly(7)))
        assertTrue(ReloadDecision.requiresFullRebuild(ReloadReason.RouteModeChanged))
        assertTrue(ReloadDecision.requiresFullRebuild(ReloadReason.CoreConfigChanged))
        assertTrue(ReloadDecision.requiresFullRebuild(ReloadReason.DnsChanged))
        assertTrue(ReloadDecision.requiresFullRebuild(ReloadReason.TunChanged))
        assertTrue(ReloadDecision.requiresFullRebuild(ReloadReason.ClashApiChanged))
    }

    @Test
    fun selectorSkipRequiresMatchingFingerprint() {
        assertTrue(ReloadDecision.shouldSelectOnly(ReloadReason.SelectorOnly(7), "same", "same"))
        assertFalse(ReloadDecision.shouldSelectOnly(ReloadReason.SelectorOnly(7), "old", "new"))
        assertFalse(ReloadDecision.shouldSelectOnly(ReloadReason.RouteModeChanged, "same", "same"))
    }
}
