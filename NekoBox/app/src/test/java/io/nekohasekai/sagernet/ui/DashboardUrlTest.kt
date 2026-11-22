package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUrlTest {
    @Test
    fun localDashboardInjectsControllerAndSecret() {
        assertEquals(
            "http://127.0.0.1:9090/ui/?hostname=127.0.0.1&port=9090&secret=a+b/c=",
            buildDashboardUrl("http://127.0.0.1:9090/ui/", "a+b/c="),
        )
    }

    @Test
    fun customDashboardUrlIsUntouched() {
        assertEquals(
            "https://example.com/dashboard",
            buildDashboardUrl("https://example.com/dashboard", "secret"),
        )
    }

    @Test
    fun localDashboardOriginChecksMatchStrictly() {
        assertTrue(isSameLocalDashboardOrigin("http://127.0.0.1:9090/ui/#/proxies"))
        assertTrue(isSameLocalDashboardOrigin("HTTP://127.0.0.1:9090/"))
        assertTrue(isSameLocalDashboardOrigin("http://localhost:9090/ui/"))
        assertTrue(isLocalDashboardUrl("HTTP://LOCALHOST:9090/"))
        assertFalse(isSameLocalDashboardOrigin("http://127.0.0.1:8080/ui/"))
        assertFalse(isSameLocalDashboardOrigin("https://127.0.0.1:9090/ui/"))
        assertFalse(isSameLocalDashboardOrigin("https://example.com/steal"))
        assertFalse(isSameLocalDashboardOrigin("javascript:alert(1)"))
    }

    @Test
    fun localDashboardBootstrapQueryDetectsRemoteHijack() {
        assertFalse(
            hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/?hostname=127.0.0.1&port=9090&secret=abc"),
        )
        assertFalse(hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/"))
        assertTrue(
            hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/?hostname=attacker.example&port=443"),
        )
        assertTrue(
            hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/?hostname=127.0.0.1&port=8080"),
        )
        assertTrue(
            hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/?baseURL=https://attacker.example"),
        )
    }

    @Test
    fun secretSyncScriptChecksLocalBaseUrlAndSeedsInitialConfig() {
        val script = buildLocalSecretSyncScript("test-secret")
        assertTrue(script.contains("http://127.0.0.1:9090"))
        assertTrue(script.contains("http://localhost:9090"))
        assertTrue(script.contains("selectedClashAPIConfigIndex: 0"))
        assertFalse(script.contains("backends[0].secret"))
    }

    @Test
    fun subresourceOriginCheckPermitsLoopbackHttpAndWebSocketOnly() {
        assertTrue(isSameLocalDashboardSubresourceOrigin("http://127.0.0.1:9090/configs"))
        assertTrue(isSameLocalDashboardSubresourceOrigin("ws://127.0.0.1:9090/traffic?token=abc"))
        assertTrue(isSameLocalDashboardSubresourceOrigin("ws://localhost:9090/connections?token=abc"))
        assertFalse(isSameLocalDashboardSubresourceOrigin("wss://127.0.0.1:9090/traffic"))
        assertFalse(isSameLocalDashboardSubresourceOrigin("ws://attacker.example:9090/traffic"))
        assertFalse(isSameLocalDashboardSubresourceOrigin("ws://127.0.0.1:8080/traffic"))
    }
}
