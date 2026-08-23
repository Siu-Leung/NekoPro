package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardUrlTest {
    @Test
    fun localDashboardInjectsControllerAndSecret() {
        assertEquals(
            "http://127.0.0.1:9090/ui/?hostname=http%3A%2F%2F127.0.0.1%3A9090&port=9090&secret=a%2Bb%2Fc%3D",
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
}