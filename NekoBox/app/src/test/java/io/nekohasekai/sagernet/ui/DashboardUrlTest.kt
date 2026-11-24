package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUrlTest {
    @Test
    fun localDashboardUrlEncodesSecretAndPreservesFragment() {
        assertEquals(
            "http://127.0.0.1:9090/ui/?hostname=127.0.0.1&port=9090&secret=a%2Bb%2Fc%3D&http=true#/proxies",
            buildDashboardUrl("http://127.0.0.1:9090/ui/#/proxies", "a+b/c="),
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
    fun allowedDashboardUrlRequiresLocalOrHttps() {
        assertTrue(isAllowedDashboardUrl("http://127.0.0.1:9090/ui/"))
        assertTrue(isAllowedDashboardUrl("http://localhost:9090/ui/"))
        assertTrue(isAllowedDashboardUrl("https://d.metacubex.one/"))
        assertFalse(isAllowedDashboardUrl("http://yacd.haishan.me/"))
        assertFalse(isAllowedDashboardUrl("javascript:alert(1)"))
        assertFalse(isAllowedDashboardUrl("file:///etc/hosts"))
    }

    @Test
    fun documentStartOriginRulesIncludeLoopbackAndCustomHttpsOrigin() {
        assertEquals(
            setOf("http://127.0.0.1:9090", "http://localhost:9090"),
            buildDocumentStartOriginRules("http://127.0.0.1:9090/ui/"),
        )
        assertEquals(
            setOf("http://127.0.0.1:9090", "http://localhost:9090", "https://d.metacubex.one:8443"),
            buildDocumentStartOriginRules("https://D.MetaCubeX.one:8443/#/overview"),
        )
    }

    @Test
    fun serviceWorkerAssetPathsMatchAccurately() {
        assertTrue(isServiceWorkerAssetPath("/ui/sw.js"))
        assertTrue(isServiceWorkerAssetPath("/ui/registerSW.js"))
        assertTrue(isServiceWorkerAssetPath("/ui/workbox-38b98e80.js"))
        assertFalse(isServiceWorkerAssetPath("/ui/_nuxt/DCrN1QU3.js"))
        assertFalse(isServiceWorkerAssetPath("/ui/index.html"))
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
            hasDisallowedLocalBootstrapParams("http://127.0.0.1:9090/ui/?hostname=127.0.0.1&port=9090&secret=abc&http=true"),
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
    fun secretSyncScriptSeedsEmptyLocalStorageAndWrapsLoopbackFetchAndWebSocket() {
        val script = buildLocalSecretSyncScript("test-secret", isPostFinishFallback = false)
        assertTrue(script.contains("http://127.0.0.1:9090"))
        assertTrue(script.contains("http://localhost:9090"))
        assertTrue(script.contains("yacd.metacubex.one"))
        assertTrue(script.contains("endpointList"))
        assertTrue(script.contains("localStorage.setItem('selectedEndpoint', targetId)"))
        assertFalse(script.contains("JSON.stringify(matchedId)"))
        assertTrue(script.contains("setup/api-list"))
        assertTrue(script.contains("__nekoLoopbackAuthPatched__"))
        assertTrue(script.contains("isLoopbackControllerTarget"))
        assertTrue(script.contains("api.github.com"))
        assertTrue(script.contains("const isPostFinish = false;"))
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

    @Test
    fun probeLocalControllerAuthDetectsReadyUnauthorizedAndUnreachable() {
        val expectedSecret = "valid-secret-123"
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    var authHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authHeader = line.substringAfter(':').trim()
                        }
                    }
                    val ok = authHeader == "Bearer $expectedSecret"
                    val status = if (ok) "200 OK" else "401 Unauthorized"
                    val body = if (ok) """{"version":"1.14.4"}""" else """{"message":"Unauthorized"}"""
                    val response = "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.length}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            assertEquals(
                ControllerAuthState.READY,
                probeLocalControllerAuth(expectedSecret, host = "127.0.0.1", port = port, timeoutMs = 1000),
            )
            assertEquals(
                ControllerAuthState.UNAUTHORIZED,
                probeLocalControllerAuth("wrong-secret", host = "127.0.0.1", port = port, timeoutMs = 1000),
            )
        } finally {
            server.close()
            worker.join(1000)
        }
        assertEquals(
            ControllerAuthState.UNREACHABLE,
            probeLocalControllerAuth(expectedSecret, host = "127.0.0.1", port = port, timeoutMs = 200),
        )
    }
}
