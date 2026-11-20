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
        assertFalse(isSameLocalDashboardOrigin("http://127.0.0.1:8080/ui/"))
        assertFalse(isSameLocalDashboardOrigin("https://127.0.0.1:9090/ui/"))
        assertFalse(isSameLocalDashboardOrigin("https://example.com/steal"))
        assertFalse(isSameLocalDashboardOrigin("javascript:alert(1)"))
    }
}
