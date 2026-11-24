package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ReloadReason
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.security.ClashApiSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.WebViewUtil
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal const val LOCAL_DASHBOARD_HOST_IP = "127.0.0.1"
internal const val LOCAL_DASHBOARD_HOST_NAME = "localhost"
internal const val LOCAL_DASHBOARD_PORT = 9090
internal const val LOCAL_DASHBOARD_BASE_URL = "http://$LOCAL_DASHBOARD_HOST_IP:$LOCAL_DASHBOARD_PORT"
internal const val LOCAL_DASHBOARD_ALT_BASE_URL = "http://$LOCAL_DASHBOARD_HOST_NAME:$LOCAL_DASHBOARD_PORT"
internal const val LOCAL_DASHBOARD_UI_URL = "$LOCAL_DASHBOARD_BASE_URL/ui/"
internal const val CLASH_AUTH_COOKIE_NAME = "nekopro_clash_auth"
internal const val DASHBOARD_PANEL_METACUBEXD = "metacubexd"
internal const val DASHBOARD_PANEL_YACD = "yacd"

private const val CONTROLLER_CONNECT_TIMEOUT_MS = 250
private const val CONTROLLER_STARTUP_POLL_STEP_MS = 150L
private const val CONTROLLER_STARTUP_MAX_ATTEMPTS = 24
private val WORKBOX_SCRIPT_REGEX = Regex("""^.*/workbox-[0-9a-zA-Z_-]+\.js$""")

/**
 * Encodes [value] as a safely quoted JavaScript string literal without relying on
 * Android's stubbed `org.json.JSONObject.quote` so it works cleanly in JVM tests.
 */
private fun escapeJsStringLiteral(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (ch.code < 0x20) {
                append("\\u%04x".format(ch.code))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

/**
 * Checks whether [targetUrl] points to the local Clash API HTTP origin
 * (`http://127.0.0.1:9090` or `http://localhost:9090`, case-insensitive).
 *
 * @param targetUrl Raw URL string to inspect.
 * @return `true` when [targetUrl] uses HTTP on loopback port 9090.
 */
internal fun isSameLocalDashboardOrigin(targetUrl: String): Boolean {
    val parsed = runCatching { URI(targetUrl.trim()) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
    return scheme == "http" &&
        (host == LOCAL_DASHBOARD_HOST_IP || host == LOCAL_DASHBOARD_HOST_NAME) &&
        parsed.port == LOCAL_DASHBOARD_PORT
}

/**
 * Checks whether a subresource request (HTTP REST or WebSocket stream) targets the
 * local Clash API controller (`http://` or `ws://` on loopback port 9090).
 *
 * @param targetUrl Candidate subresource URL intercepted by WebView.
 * @return `true` when [targetUrl] uses `http` or `ws` on `127.0.0.1:9090` / `localhost:9090`.
 */
internal fun isSameLocalDashboardSubresourceOrigin(targetUrl: String): Boolean {
    val parsed = runCatching { URI(targetUrl.trim()) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
    return (scheme == "http" || scheme == "ws") &&
        (host == LOCAL_DASHBOARD_HOST_IP || host == LOCAL_DASHBOARD_HOST_NAME) &&
        parsed.port == LOCAL_DASHBOARD_PORT
}

/**
 * Returns `true` if the configured dashboard URL targets the local embedded Clash UI.
 * Matches any path on the local Clash API origin (including `/` and `/ui/`).
 */
internal fun isLocalDashboardUrl(url: String): Boolean = isSameLocalDashboardOrigin(url)

/**
 * Validates whether [url] is permitted in the embedded dashboard WebView.
 * Only the local loopback Clash UI (`http://127.0.0.1:9090` / `http://localhost:9090`) or
 * `https://` external dashboards are allowed, matching `network_security_config.xml`.
 */
internal fun isAllowedDashboardUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (isLocalDashboardUrl(trimmed)) return true
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
    return scheme == "https" && !parsed.host.isNullOrBlank()
}

/**
 * Identifies PWA ServiceWorker scripts (`/sw.js`, `/registerSW.js`, `/workbox-*.js`) so WebView
 * can neutralize them and prevent cached HTML from bypassing fresh bootstrap state.
 */
internal fun isServiceWorkerAssetPath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return path.endsWith("/sw.js") ||
        path.endsWith("/registerSW.js") ||
        WORKBOX_SCRIPT_REGEX.matches(path)
}

/**
 * Extracts `WebViewCompat.addDocumentStartJavaScript` origin rules (`scheme://host[:port]`)
 * covering the local Clash controller plus any explicitly configured `https://` custom panel.
 */
internal fun buildDocumentStartOriginRules(configuredUrl: String): Set<String> = buildSet {
    add(LOCAL_DASHBOARD_BASE_URL)
    add(LOCAL_DASHBOARD_ALT_BASE_URL)
    val trimmed = configuredUrl.trim()
    if (!isLocalDashboardUrl(trimmed) && isAllowedDashboardUrl(trimmed)) {
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        val host = parsed?.host?.lowercase(Locale.ROOT)
        if (parsed != null && !host.isNullOrBlank()) {
            val portSuffix = if (parsed.port > 0) ":${parsed.port}" else ""
            add("https://$host$portSuffix")
        }
    }
}

/**
 * Detects public hosted Clash WebUI demo URLs configured as a workaround in earlier versions so
 * one-time upgrade migration can switch seamlessly to the built-in offline MetaCubeXD bundle.
 */
internal fun shouldMigratePublicDemoDashboardUrl(configuredUrl: String): Boolean {
    val parsed = runCatching { URI(configuredUrl.trim()) }.getOrNull() ?: return false
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
    return host.contains("metacubex") || host.contains("zash.run") || host.endsWith("haishan.me")
}

/**
 * Detects query parameters that attempt to hijack a local dashboard bootstrap URL
 * toward a remote Clash backend (e.g. `?hostname=attacker.example&port=443`).
 *
 * @param targetUrl Candidate URL targeting the local dashboard origin.
 * @return `true` if any bootstrap parameter points away from the local controller.
 */
internal fun hasDisallowedLocalBootstrapParams(targetUrl: String): Boolean {
    val parsed = runCatching { URI(targetUrl.trim()) }.getOrNull() ?: return true
    val rawQuery = parsed.rawQuery ?: return false
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val key = runCatching {
            URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
        }.getOrNull()?.lowercase(Locale.ROOT) ?: continue
        val rawValue = pair.substringAfter('=', "")
        val value = runCatching {
            URLDecoder.decode(rawValue, Charsets.UTF_8.name())
        }.getOrNull()?.trim() ?: return true
        when (key) {
            "hostname", "host" -> {
                val lowerHost = value.lowercase(Locale.ROOT)
                if (lowerHost != LOCAL_DASHBOARD_HOST_IP && lowerHost != LOCAL_DASHBOARD_HOST_NAME) {
                    return true
                }
            }
            "port" -> {
                if (value != LOCAL_DASHBOARD_PORT.toString()) {
                    return true
                }
            }
            "baseurl", "url" -> {
                if (!isSameLocalDashboardOrigin(value)) {
                    return true
                }
            }
        }
    }
    return false
}

/**
 * Builds the effective WebView URL for the Clash dashboard.
 * Local dashboard URLs are canonicalized with URL-encoded loopback bootstrap parameters
 * (`hostname`, `port`, `secret`, `http=true`) while preserving hash routes (`#/proxies`).
 */
internal fun buildDashboardUrl(configuredUrl: String, secret: String): String {
    if (!isLocalDashboardUrl(configuredUrl)) return configuredUrl
    val rawFragment = runCatching { URI(configuredUrl.trim()).rawFragment }.getOrNull()
    val fragmentSuffix = if (!rawFragment.isNullOrEmpty()) "#$rawFragment" else ""
    val encodedSecret = URLEncoder.encode(secret, Charsets.UTF_8.name()).replace("+", "%20")
    return "${LOCAL_DASHBOARD_UI_URL}?hostname=$LOCAL_DASHBOARD_HOST_IP&port=$LOCAL_DASHBOARD_PORT&secret=$encodedSecret&http=true$fragmentSuffix"
}

/**
 * Builds the JavaScript bootstrap snippet executed at `DOCUMENT_START` (before any page `<script>`
 * runs) and as an `onPageStarted`/`onPageFinished` fallback on pre-M84 WebViews.
 *
 * Responsibilities:
 * 1. Seeds or repairs `localStorage` backend credentials even when `localStorage` is empty on first
 *    launch for MetaCubeXD (`endpointList` + raw unquoted `selectedEndpoint`), Yacd-meta
 *    (`yacd.metacubex.one` with malformed-URL sanitization), and Zashboard (`setup/api-list` + `setup/active-uuid`).
 * 2. Wraps `window.fetch`, `window.XMLHttpRequest`, and `window.WebSocket` strictly for loopback
 *    `127.0.0.1:9090` / `localhost:9090` targets so Bearer headers and WS `?token=` are always present
 *    even if the SPA resets its state.
 * 3. Unregisters any stale ServiceWorkers.
 */
internal fun buildLocalSecretSyncScript(
    expectedSecret: String,
    isPostFinishFallback: Boolean = false,
): String {
    val quotedSecret = escapeJsStringLiteral(expectedSecret)
    val postFinishFlag = if (isPostFinishFallback) "true" else "false"
    return """(() => {
        try {
            const canonicalUrl = 'http://127.0.0.1:9090';
            const defaultEndpointId = 'neko-local-9090';
            const secret = $quotedSecret;
            const isPostFinish = $postFinishFlag;
            const isLocalBase = (rawUrl) => {
                if (typeof rawUrl !== 'string') return false;
                const normalized = rawUrl.trim().replace(/\/+$/, '').toLowerCase();
                return normalized === 'http://127.0.0.1:9090' ||
                    normalized === 'http://localhost:9090' ||
                    normalized === 'http://127.0.0.1:9090/ui' ||
                    normalized === 'http://localhost:9090/ui' ||
                    normalized === '127.0.0.1:9090' ||
                    normalized === 'localhost:9090';
            };
            const isValidHttpUrl = (rawUrl) => {
                if (typeof rawUrl !== 'string' || !rawUrl) return false;
                try {
                    const u = new URL(rawUrl);
                    return u.protocol === 'http:' || u.protocol === 'https:';
                } catch (_) {
                    return false;
                }
            };
            const isLoopbackControllerTarget = (rawTarget) => {
                if (!rawTarget) return false;
                try {
                    const str = typeof rawTarget === 'string'
                        ? rawTarget
                        : (typeof URL !== 'undefined' && rawTarget instanceof URL)
                            ? rawTarget.href
                            : (rawTarget && typeof rawTarget.url === 'string' ? rawTarget.url : '');
                    if (!str) return false;
                    const u = new URL(str, window.location.href);
                    return (u.protocol === 'http:' || u.protocol === 'ws:') &&
                        (u.hostname === '127.0.0.1' || u.hostname === 'localhost') &&
                        u.port === '9090';
                } catch (_) {
                    return false;
                }
            };

            // 1. MetaCubeXD (`endpointList` + raw VueUse string `selectedEndpoint`)
            try {
                const rawMcxd = localStorage.getItem('endpointList');
                let list = [];
                try {
                    const parsed = rawMcxd ? JSON.parse(rawMcxd) : null;
                    if (Array.isArray(parsed)) list = parsed;
                } catch (_) {}
                let targetId = null;
                let mcxdChanged = !rawMcxd;
                for (let i = 0; i < list.length; i++) {
                    const item = list[i];
                    if (item && typeof item === 'object' && isLocalBase(item.url)) {
                        if (!item.id || typeof item.id !== 'string') {
                            item.id = defaultEndpointId;
                            mcxdChanged = true;
                        }
                        if (item.url !== canonicalUrl) {
                            item.url = canonicalUrl;
                            mcxdChanged = true;
                        }
                        if (item.secret !== secret) {
                            item.secret = secret;
                            mcxdChanged = true;
                        }
                        if (!targetId) targetId = item.id;
                    }
                }
                if (!targetId) {
                    targetId = defaultEndpointId;
                    list.unshift({ id: targetId, url: canonicalUrl, secret: secret });
                    mcxdChanged = true;
                }
                if (mcxdChanged) {
                    localStorage.setItem('endpointList', JSON.stringify(list));
                }
                // WHY: MetaCubeXD uses VueUse `useLocalStorage('selectedEndpoint', '')`, which stores
                // raw strings without extra JSON quotes. Strip any legacy quoted value and select loopback.
                const currentSelected = localStorage.getItem('selectedEndpoint');
                if (currentSelected !== targetId) {
                    localStorage.setItem('selectedEndpoint', targetId);
                }
            } catch (_) {}

            // 2. Yacd-meta (`yacd.metacubex.one`)
            try {
                const yacdKey = 'yacd.metacubex.one';
                const rawYacd = localStorage.getItem(yacdKey);
                if (!rawYacd) {
                    localStorage.setItem(yacdKey, JSON.stringify({
                        selectedClashAPIConfigIndex: 0,
                        clashAPIConfigs: [{ baseURL: canonicalUrl, secret: secret, addedAt: Date.now() }],
                        latencyTestUrl: 'https://www.gstatic.com/generate_204',
                        selectedChartStyleIndex: 0,
                        theme: 'dark',
                        collapsibleIsOpen: {},
                        proxySortBy: 'Natural',
                        hideUnavailableProxies: false,
                        autoCloseOldConns: false,
                        logStreamingPaused: false
                    }));
                } else {
                    const state = JSON.parse(rawYacd);
                    if (state && typeof state === 'object') {
                        const rawBackends = Array.isArray(state.clashAPIConfigs) ? state.clashAPIConfigs : [];
                        const backends = [];
                        let localIndex = -1;
                        let yacdChanged = !Array.isArray(state.clashAPIConfigs);
                        for (let i = 0; i < rawBackends.length; i++) {
                            const entry = rawBackends[i];
                            if (!entry || typeof entry !== 'object') {
                                yacdChanged = true;
                                continue;
                            }
                            if (isLocalBase(entry.baseURL) || !isValidHttpUrl(entry.baseURL)) {
                                if (localIndex === -1) {
                                    localIndex = backends.length;
                                    if (entry.baseURL !== canonicalUrl || entry.secret !== secret) {
                                        yacdChanged = true;
                                    }
                                    backends.push({ ...entry, baseURL: canonicalUrl, secret: secret });
                                } else {
                                    yacdChanged = true;
                                }
                            } else {
                                backends.push(entry);
                            }
                        }
                        if (localIndex === -1) {
                            backends.unshift({ baseURL: canonicalUrl, secret: secret, addedAt: Date.now() });
                            localIndex = 0;
                            yacdChanged = true;
                        }
                        state.clashAPIConfigs = backends;
                        if (state.selectedClashAPIConfigIndex !== localIndex) {
                            state.selectedClashAPIConfigIndex = localIndex;
                            yacdChanged = true;
                        }
                        if (yacdChanged) {
                            localStorage.setItem(yacdKey, JSON.stringify(state));
                        }
                    }
                }
            } catch (_) {}

            // 3. Zashboard (`setup/api-list` + `setup/active-uuid`)
            try {
                const rawZash = localStorage.getItem('setup/api-list');
                let list = [];
                try {
                    const parsed = rawZash ? JSON.parse(rawZash) : null;
                    if (Array.isArray(parsed)) list = parsed;
                } catch (_) {}
                let targetUuid = null;
                let zashChanged = !rawZash;
                for (let i = 0; i < list.length; i++) {
                    const item = list[i];
                    if (!item || typeof item !== 'object') continue;
                    const host = typeof item.hostname === 'string' ? item.hostname.toLowerCase() : '';
                    const port = String(item.port || '');
                    if ((host === '127.0.0.1' || host === 'localhost') && port === '9090') {
                        if (!item.uuid || typeof item.uuid !== 'string') {
                            item.uuid = defaultEndpointId;
                            zashChanged = true;
                        }
                        if (item.protocol !== 'http:') {
                            item.protocol = 'http:';
                            zashChanged = true;
                        }
                        if (item.secret !== secret) {
                            item.secret = secret;
                            zashChanged = true;
                        }
                        if (!targetUuid) targetUuid = item.uuid;
                    }
                }
                if (!targetUuid) {
                    targetUuid = defaultEndpointId;
                    list.unshift({
                        uuid: targetUuid,
                        protocol: 'http:',
                        hostname: '127.0.0.1',
                        port: '9090',
                        secret: secret,
                        label: 'NekoPro'
                    });
                    zashChanged = true;
                }
                if (zashChanged) {
                    localStorage.setItem('setup/api-list', JSON.stringify(list));
                }
                if (localStorage.getItem('setup/active-uuid') !== targetUuid) {
                    localStorage.setItem('setup/active-uuid', targetUuid);
                }
            } catch (_) {}

            // 4. Transparent loopback 127.0.0.1:9090 auth wrapper for fetch / XHR / WebSocket
            window.__nekoLoopbackAuthSecret__ = secret;
            if (!window.__nekoLoopbackAuthPatched__) {
                window.__nekoLoopbackAuthPatched__ = true;
                if (typeof window.fetch === 'function') {
                    const nativeFetch = window.fetch.bind(window);
                    window.fetch = function(input, init) {
                        try {
                            if (isLoopbackControllerTarget(input)) {
                                const activeSecret = window.__nekoLoopbackAuthSecret__;
                                if (activeSecret) {
                                    const headers = new Headers(
                                        (init && init.headers) ||
                                        (typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined)
                                    );
                                    headers.set('Authorization', 'Bearer ' + activeSecret);
                                    return nativeFetch(input, Object.assign({}, init || {}, { headers: headers }));
                                }
                            }
                        } catch (_) {}
                        return nativeFetch(input, init);
                    };
                }
                if (typeof window.XMLHttpRequest === 'function') {
                    const origOpen = window.XMLHttpRequest.prototype.open;
                    const origSend = window.XMLHttpRequest.prototype.send;
                    window.XMLHttpRequest.prototype.open = function(method, url) {
                        this.__nekoIsLoopback = isLoopbackControllerTarget(url);
                        return origOpen.apply(this, arguments);
                    };
                    window.XMLHttpRequest.prototype.send = function() {
                        try {
                            if (this.__nekoIsLoopback && window.__nekoLoopbackAuthSecret__) {
                                this.setRequestHeader('Authorization', 'Bearer ' + window.__nekoLoopbackAuthSecret__);
                            }
                        } catch (_) {}
                        return origSend.apply(this, arguments);
                    };
                }
                if (typeof window.WebSocket === 'function') {
                    const NativeWebSocket = window.WebSocket;
                    window.WebSocket = new Proxy(NativeWebSocket, {
                        construct(Target, args, newTarget) {
                            try {
                                if (args.length > 0 && isLoopbackControllerTarget(args[0]) && window.__nekoLoopbackAuthSecret__) {
                                    const wsUrl = new URL(String(args[0]), window.location.href);
                                    if (wsUrl.searchParams.get('token') !== window.__nekoLoopbackAuthSecret__) {
                                        wsUrl.searchParams.set('token', window.__nekoLoopbackAuthSecret__);
                                        const patchedArgs = [wsUrl.toString()].concat(Array.prototype.slice.call(args, 1));
                                        return Reflect.construct(Target, patchedArgs, newTarget);
                                    }
                                }
                            } catch (_) {}
                            return Reflect.construct(Target, args, newTarget);
                        }
                    });
                }
            }

            if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
                navigator.serviceWorker.getRegistrations().then((regs) => {
                    regs.forEach((reg) => { try { reg.unregister(); } catch (_) {} });
                }).catch(() => {});
            }

            if (isPostFinish) {
                const hasSecretPrompt = !!document.querySelector('input#secret, input[name="secret"]');
                const counterKey = '__neko_reload_count__';
                if (!hasSecretPrompt) {
                    sessionStorage.setItem(counterKey, '0');
                } else {
                    const count = Number(sessionStorage.getItem(counterKey) || '0');
                    if (count < 2) {
                        sessionStorage.setItem(counterKey, String(count + 1));
                        location.reload();
                    }
                }
            }
        } catch (_) {}
    })()""".trimIndent()
}

internal enum class ControllerAuthState {
    READY,
    UNAUTHORIZED,
    UNREACHABLE,
}

/**
 * Verifies that the local Clash API controller on `127.0.0.1:9090` is both reachable
 * and accepts [secret] (`GET /version` with `Authorization: Bearer <secret>` returning HTTP 200).
 */
internal fun probeLocalControllerAuth(
    secret: String,
    host: String = LOCAL_DASHBOARD_HOST_IP,
    port: Int = LOCAL_DASHBOARD_PORT,
    timeoutMs: Int = CONTROLLER_CONNECT_TIMEOUT_MS,
): ControllerAuthState = runCatching {
    val connection = (URL("http://$host:$port/version")
        .openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs * 2
        useCaches = false
        setRequestProperty("Authorization", "Bearer $secret")
    }
    try {
        when (connection.responseCode) {
            in 200..299 -> ControllerAuthState.READY
            401, 403 -> ControllerAuthState.UNAUTHORIZED
            else -> ControllerAuthState.UNREACHABLE
        }
    } finally {
        connection.disconnect()
    }
}.getOrDefault(ControllerAuthState.UNREACHABLE)

/**
 * Embedded Clash API dashboard fragment (built-in MetaCubeXD / Yacd + optional custom HTTPS panel)
 * featuring 3-layer zero-password authentication:
 * 1. Process-private `HttpOnly; SameSite=Strict` cookie (`nekopro_clash_auth`) verified by `sing-box`.
 * 2. `WebViewCompat.addDocumentStartJavaScript` pre-seeding `localStorage` and wrapping loopback
 *    `127.0.0.1:9090` `fetch`/`XHR`/`WebSocket` before any page script executes.
 * 3. Live `GET /version` Bearer verification with automatic controller reload on demand.
 */
class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var viewBinding: LayoutWebviewBinding? = null
    private var loadJob: Job? = null
    private var documentStartScriptHandler: ScriptHandler? = null

    @Volatile
    private var cachedSecret: String? = null

    @Volatile
    private var isPollingControllerReload = false

    /**
     * Triggered by [MainActivity] when the background proxy service transitions state so the
     * dashboard automatically loads once the VPN connects without duplicating active poll loads.
     */
    fun onProxyServiceStateChanged(state: BaseService.State) {
        if (!isAdded || viewBinding == null) return
        if (state == BaseService.State.Connected && !isPollingControllerReload) {
            loadDashboardUrl(DataStore.yacdURL)
        }
    }

    private fun showStatusOverlay(
        titleRes: Int,
        messageRes: Int,
        buttonTextRes: Int,
        onAction: () -> Unit,
    ) {
        val binding = viewBinding ?: return
        binding.webview.isVisible = false
        binding.dashboardStatusContainer.isVisible = true
        binding.dashboardStatusTitle.setText(titleRes)
        binding.dashboardStatusMessage.setText(messageRes)
        binding.dashboardActionButton.setText(buttonTextRes)
        binding.dashboardActionButton.setOnClickListener { onAction() }
    }

    private fun hideStatusOverlay() {
        val binding = viewBinding ?: return
        binding.dashboardStatusContainer.isVisible = false
        binding.webview.isVisible = true
    }

    private fun resolveEffectiveConfiguredUrl(rawConfiguredUrl: String): String {
        val trimmed = rawConfiguredUrl.trim()
        if (!DataStore.dashboardBuiltInMcxdMigrated) {
            DataStore.dashboardBuiltInMcxdMigrated = true
            if (shouldMigratePublicDemoDashboardUrl(trimmed)) {
                DataStore.dashboardPanel = DASHBOARD_PANEL_METACUBEXD
                DataStore.yacdURL = LOCAL_DASHBOARD_UI_URL
                return LOCAL_DASHBOARD_UI_URL
            }
        }
        return trimmed
    }

    /**
     * Seeds process-private `HttpOnly` auth cookies on loopback origins before WebView navigation
     * so every HTTP REST request and WebSocket handshake carries credentials on packet #1.
     */
    private fun seedLocalAuthCookies(secret: String) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (::mWebView.isInitialized) {
                cookieManager.setAcceptThirdPartyCookies(mWebView, false)
            }
            val authCookie = "$CLASH_AUTH_COOKIE_NAME=$secret; Path=/; HttpOnly; SameSite=Strict"
            cookieManager.setCookie(LOCAL_DASHBOARD_BASE_URL, authCookie)
            cookieManager.setCookie(LOCAL_DASHBOARD_ALT_BASE_URL, authCookie)

            val existingCookies = cookieManager.getCookie(LOCAL_DASHBOARD_BASE_URL).orEmpty()
            if (!existingCookies.contains("metacubexd_lang=") &&
                Locale.getDefault().language.startsWith("zh", ignoreCase = true)
            ) {
                cookieManager.setCookie(
                    LOCAL_DASHBOARD_BASE_URL,
                    "metacubexd_lang=zh; Path=/; SameSite=Lax",
                )
            }
            cookieManager.flush()
        }.onFailure { Logs.w(it) }
    }

    /**
     * Registers `DOCUMENT_START_SCRIPT` scoped strictly to local loopback and any user-configured
     * `https://` dashboard origin so `localStorage` and loopback `fetch`/`WebSocket` wrappers run
     * before the SPA's ES modules execute.
     */
    private fun installDocumentStartScript(configuredUrl: String, secret: String) {
        if (!::mWebView.isInitialized) return
        runCatching {
            documentStartScriptHandler?.remove()
            documentStartScriptHandler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val origins = buildDocumentStartOriginRules(configuredUrl)
                val script = buildLocalSecretSyncScript(secret, isPostFinishFallback = false)
                documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    mWebView,
                    script,
                    origins,
                )
            }
        }.onFailure { Logs.w(it) }
    }

    private fun loadDashboardUrl(
        configuredUrl: String,
        forceControllerReload: Boolean = false,
    ) {
        val effectiveUrl = resolveEffectiveConfiguredUrl(configuredUrl)
        if (!isAllowedDashboardUrl(effectiveUrl)) {
            Logs.w("Blocked disallowed dashboard URL")
            return
        }
        val lifecycleOwner = viewLifecycleOwnerLiveData.value ?: return
        loadJob?.cancel()
        loadJob = lifecycleOwner.lifecycleScope.launch {
            var secret = withContext(Dispatchers.IO) {
                ClashApiSecret.refresh().also { cachedSecret = it }
            }

            // WHY: Both built-in local panels and external HTTPS panels connect to `127.0.0.1:9090`.
            // Always auto-enable Clash API and verify the controller is live and accepting `secret`.
            if (!DataStore.enableClashAPI) {
                DataStore.enableClashAPI = true
                (activity as? MainActivity)?.refreshNavMenu(true)
            }
            if (!DataStore.serviceState.connected) {
                showStatusOverlay(
                    titleRes = R.string.dashboard_not_connected_title,
                    messageRes = R.string.dashboard_not_connected_message,
                    buttonTextRes = R.string.dashboard_go_connect,
                ) {
                    (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
                }
                return@launch
            }

            var authState = if (forceControllerReload) {
                ControllerAuthState.UNREACHABLE
            } else {
                withContext(Dispatchers.IO) { probeLocalControllerAuth(secret) }
            }
            if (authState != ControllerAuthState.READY) {
                if (authState == ControllerAuthState.UNAUTHORIZED) {
                    Logs.w("Local Clash API returned 401 for current secret; reloading core to resync secret")
                }
                isPollingControllerReload = true
                try {
                    SagerNet.reloadService(ReloadReason.ClashApiChanged)
                    repeat(CONTROLLER_STARTUP_MAX_ATTEMPTS) {
                        delay(CONTROLLER_STARTUP_POLL_STEP_MS)
                        secret = withContext(Dispatchers.IO) {
                            ClashApiSecret.refresh().also { cachedSecret = it }
                        }
                        authState = withContext(Dispatchers.IO) { probeLocalControllerAuth(secret) }
                        if (authState == ControllerAuthState.READY) {
                            return@repeat
                        }
                    }
                } finally {
                    isPollingControllerReload = false
                }
            }
            if (authState != ControllerAuthState.READY) {
                showStatusOverlay(
                    titleRes = R.string.dashboard_controller_unavailable_title,
                    messageRes = R.string.dashboard_controller_unavailable_message,
                    buttonTextRes = R.string.dashboard_retry_load,
                ) {
                    loadDashboardUrl(DataStore.yacdURL)
                }
                return@launch
            }

            hideStatusOverlay()
            if (::mWebView.isInitialized) {
                mWebView.settings.mixedContentMode = if (isLocalDashboardUrl(effectiveUrl)) {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                } else {
                    // WHY: Hosted HTTPS dashboards (e.g. custom MetaCubeXD / Zashboard URLs) must be
                    // allowed to open loopback `http://127.0.0.1:9090` and `ws://127.0.0.1:9090` streams.
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                seedLocalAuthCookies(secret)
                installDocumentStartScript(effectiveUrl, secret)
                mWebView.loadUrl(buildDashboardUrl(effectiveUrl, secret))
            }
        }
    }

    private fun isTrustedActivePageUrl(pageUrl: String): Boolean {
        if (isSameLocalDashboardOrigin(pageUrl)) {
            return !hasDisallowedLocalBootstrapParams(pageUrl)
        }
        val parsed = runCatching { URI(pageUrl.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") return false
        val origin = "https://$host${if (parsed.port > 0) ":${parsed.port}" else ""}"
        return origin in buildDocumentStartOriginRules(DataStore.yacdURL)
    }

    private fun syncSecretIntoActivePage(
        webView: WebView?,
        pageUrl: String?,
        isPostFinishFallback: Boolean,
    ) {
        if (webView == null || pageUrl.isNullOrBlank() || !isTrustedActivePageUrl(pageUrl)) {
            return
        }
        val immediateSecret = cachedSecret
        if (!immediateSecret.isNullOrEmpty()) {
            webView.evaluateJavascript(
                buildLocalSecretSyncScript(immediateSecret, isPostFinishFallback = isPostFinishFallback),
                null,
            )
            return
        }
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            val freshSecret = withContext(Dispatchers.IO) {
                ClashApiSecret.refresh().also { cachedSecret = it }
            }
            if (!isAdded) return@launch
            webView.evaluateJavascript(
                buildLocalSecretSyncScript(freshSecret, isPostFinishFallback = isPostFinishFallback),
                null,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)
        viewBinding = binding

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
        }
        mWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val sanitizedSource = consoleMessage.sourceId()?.substringBefore('?')
                    val secret = cachedSecret
                    val sanitizedMsg = if (!secret.isNullOrEmpty()) {
                        consoleMessage.message()?.replace(secret, "[REDACTED]")
                    } else {
                        consoleMessage.message()
                    }
                    Logs.d("WebView Console [${consoleMessage.messageLevel()}]: $sanitizedMsg ($sanitizedSource:${consoleMessage.lineNumber()})")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        mWebView.webViewClient = object : WebViewClient() {
            private fun handleNavigation(
                targetUrl: String?,
                isForMainFrame: Boolean,
                hasGesture: Boolean,
            ): Boolean {
                if (targetUrl.isNullOrBlank() || !isAllowedDashboardUrl(targetUrl)) {
                    if (!targetUrl.isNullOrBlank() && (isForMainFrame || hasGesture)) {
                        val scheme = runCatching { Uri.parse(targetUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
                        if (scheme == "http" || scheme == "https") {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                            }.onFailure { Logs.w(it) }
                        }
                    }
                    return true
                }
                if (isLocalDashboardUrl(DataStore.yacdURL)) {
                    if (isSameLocalDashboardOrigin(targetUrl) && hasDisallowedLocalBootstrapParams(targetUrl)) {
                        Logs.w("Blocked local dashboard URL with non-local bootstrap parameters")
                        return true
                    }
                    if (!isSameLocalDashboardOrigin(targetUrl)) {
                        if (isForMainFrame || hasGesture) {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                            }.onFailure { Logs.w(it) }
                        }
                        return true
                    }
                }
                return false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                return handleNavigation(
                    targetUrl = request?.url?.toString(),
                    isForMainFrame = request?.isForMainFrame == true,
                    hasGesture = request?.hasGesture() == true,
                )
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigation(targetUrl = url, isForMainFrame = true, hasGesture = false)
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null || !isLocalDashboardUrl(DataStore.yacdURL)) {
                    return super.shouldInterceptRequest(view, request)
                }
                val requestUrl = request.url?.toString().orEmpty()
                val scheme = request.url?.scheme?.lowercase(Locale.ROOT)
                if (scheme == "data" || scheme == "blob" || scheme == "about") {
                    return super.shouldInterceptRequest(view, request)
                }
                if (isSameLocalDashboardOrigin(requestUrl) && isServiceWorkerAssetPath(request.url?.path)) {
                    return WebResourceResponse(
                        "application/javascript",
                        "utf-8",
                        200,
                        "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream("/* sw disabled in WebView */".toByteArray(Charsets.UTF_8)),
                    )
                }
                if (!isSameLocalDashboardSubresourceOrigin(requestUrl)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Forbidden",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                syncSecretIntoActivePage(view, url, isPostFinishFallback = false)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    Logs.e("Dashboard HTTP error ${errorResponse?.statusCode} ${request.url.path}")
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncSecretIntoActivePage(view, url, isPostFinishFallback = true)
            }
        }
        mWebView.clearCache(false)
        loadDashboardUrl(DataStore.yacdURL)
    }

    private fun switchBuiltInPanel(panelKey: String, panelDisplayName: String) {
        val panelChanged = DataStore.dashboardPanel != panelKey
        DataStore.dashboardPanel = panelKey
        DataStore.yacdURL = LOCAL_DASHBOARD_UI_URL
        cachedSecret = null
        if (isAdded) {
            Toast.makeText(
                requireContext(),
                getString(R.string.dashboard_switched_toast, panelDisplayName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        loadDashboardUrl(LOCAL_DASHBOARD_UI_URL, forceControllerReload = panelChanged)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reload_dashboard -> {
                cachedSecret = null
                loadDashboardUrl(DataStore.yacdURL)
            }
            R.id.action_use_metacubexd -> {
                switchBuiltInPanel(DASHBOARD_PANEL_METACUBEXD, "MetaCubeXD")
            }
            R.id.action_use_yacd -> {
                switchBuiltInPanel(DASHBOARD_PANEL_YACD, "Yacd")
            }
            R.id.action_copy_api_secret -> {
                viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
                    val secret = withContext(Dispatchers.IO) {
                        ClashApiSecret.refresh().also { cachedSecret = it }
                    }
                    if (!isAdded) return@launch
                    val copied = SagerNet.trySetPrimaryClip(secret)
                    Toast.makeText(
                        requireContext(),
                        if (copied) R.string.dashboard_api_secret_copied else R.string.copy_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            R.id.action_set_url -> {
                val inputView = EditText(requireContext()).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(inputView)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (!isAdded) return@setPositiveButton
                        val newUrl = inputView.text.toString().trim()
                        if (!isAllowedDashboardUrl(newUrl)) {
                            Toast.makeText(
                                requireContext(),
                                R.string.dashboard_https_or_local_only,
                                Toast.LENGTH_LONG,
                            ).show()
                            return@setPositiveButton
                        }
                        DataStore.yacdURL = newUrl
                        if (!isLocalDashboardUrl(newUrl)) {
                            Toast.makeText(
                                requireContext(),
                                R.string.dashboard_custom_url_secret_hint,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        loadDashboardUrl(newUrl)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                mWebView.loadUrl("about:blank")
            }
        }
        return true
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
        viewBinding = null
        if (::mWebView.isInitialized) {
            mWebView.onPause()
            mWebView.stopLoading()
            mWebView.loadUrl("about:blank")
            mWebView.removeAllViews()
            mWebView.destroy()
        }
        super.onDestroyView()
    }
}
