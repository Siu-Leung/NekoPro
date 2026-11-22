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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

internal const val LOCAL_DASHBOARD_HOST_IP = "127.0.0.1"
internal const val LOCAL_DASHBOARD_HOST_NAME = "localhost"
internal const val LOCAL_DASHBOARD_PORT = 9090
internal const val LOCAL_DASHBOARD_BASE_URL = "http://$LOCAL_DASHBOARD_HOST_IP:$LOCAL_DASHBOARD_PORT"
internal const val LOCAL_DASHBOARD_UI_URL = "$LOCAL_DASHBOARD_BASE_URL/ui/"
private const val CONTROLLER_CONNECT_TIMEOUT_MS = 250
private const val CONTROLLER_STARTUP_POLL_STEP_MS = 150L
private const val CONTROLLER_STARTUP_MAX_ATTEMPTS = 24

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
    val scheme = parsed.scheme?.lowercase() ?: return false
    val host = parsed.host?.lowercase() ?: return false
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
    val scheme = parsed.scheme?.lowercase() ?: return false
    val host = parsed.host?.lowercase() ?: return false
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
 * Detects query parameters that attempt to hijack a local Yacd bootstrap URL
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
        }.getOrNull()?.lowercase() ?: continue
        val rawValue = pair.substringAfter('=', "")
        val value = runCatching {
            URLDecoder.decode(rawValue, Charsets.UTF_8.name())
        }.getOrNull()?.trim() ?: return true
        when (key) {
            "hostname", "host" -> {
                val lowerHost = value.lowercase()
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
 * Local dashboard URLs are canonicalized with explicit loopback bootstrap parameters
 * and the current [secret]; external dashboard URLs are returned untouched.
 */
internal fun buildDashboardUrl(configuredUrl: String, secret: String): String {
    if (!isLocalDashboardUrl(configuredUrl)) return configuredUrl
    // WHY: Yacd expects hostname and port as separate bootstrap parameters.
    // Passing a complete URL as hostname makes URL.hostname invalid and the
    // SPA renders a blank dashboard despite a successful HTTP handshake.
    return "${LOCAL_DASHBOARD_UI_URL}?hostname=$LOCAL_DASHBOARD_HOST_IP&port=$LOCAL_DASHBOARD_PORT&secret=$secret"
}

/**
 * Builds the JavaScript snippet that initializes or repairs Yacd's `localStorage`
 * backend configuration (`yacd.metacubex.one`), ensuring the local `127.0.0.1:9090`
 * controller entry carries [expectedSecret] and is actively selected.
 *
 * WHY: Previously, when `localStorage` was empty on first launch (or when a user had
 * tapped "Add" on Yacd's `/backend` modal, shifting `selectedClashAPIConfigIndex` to
 * a broken `/ui` or empty-secret entry), Yacd failed `GET /configs` with `401 Unauthorized`
 * and remained stuck on the backend login modal ("提示未登录").
 */
internal fun buildLocalSecretSyncScript(expectedSecret: String): String {
    val quotedSecret = escapeJsStringLiteral(expectedSecret)
    return """(() => {
        try {
            const key = 'yacd.metacubex.one';
            const canonicalUrl = 'http://127.0.0.1:9090';
            const secret = $quotedSecret;
            const raw = localStorage.getItem(key);
            if (!raw) {
                const initial = {
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
                };
                localStorage.setItem(key, JSON.stringify(initial));
                if (document.querySelector('input[name="secret"]')) {
                    location.reload();
                }
                return;
            }
            const state = JSON.parse(raw);
            if (!state || typeof state !== 'object') return;
            const backends = Array.isArray(state.clashAPIConfigs) ? state.clashAPIConfigs : [];
            let localIndex = -1;
            let updated = !Array.isArray(state.clashAPIConfigs);
            for (let i = 0; i < backends.length; i++) {
                const entry = backends[i];
                if (!entry || typeof entry.baseURL !== 'string') continue;
                const normalized = entry.baseURL.trim().replace(/\/+$/, '').toLowerCase();
                if (normalized === 'http://127.0.0.1:9090' ||
                    normalized === 'http://localhost:9090' ||
                    normalized === 'http://127.0.0.1:9090/ui' ||
                    normalized === 'http://localhost:9090/ui') {
                    if (localIndex === -1) localIndex = i;
                    if (entry.baseURL !== canonicalUrl) {
                        entry.baseURL = canonicalUrl;
                        updated = true;
                    }
                    if (entry.secret !== secret) {
                        entry.secret = secret;
                        updated = true;
                    }
                }
            }
            if (localIndex === -1) {
                backends.unshift({ baseURL: canonicalUrl, secret: secret, addedAt: Date.now() });
                localIndex = 0;
                updated = true;
            }
            state.clashAPIConfigs = backends;
            if (state.selectedClashAPIConfigIndex !== localIndex) {
                state.selectedClashAPIConfigIndex = localIndex;
                updated = true;
            }
            if (updated) {
                localStorage.setItem(key, JSON.stringify(state));
                location.reload();
            } else if (document.querySelector('input[name="secret"]')) {
                location.reload();
            }
        } catch (_) {}
    })()""".trimIndent()
}

private fun isAllowedDashboardUrl(url: String): Boolean {
    val scheme = runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()
        ?: Uri.parse(url.trim()).scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * Embedded Clash API / Yacd dashboard fragment with automatic secret synchronization,
 * loopback WebSocket support, and connection-readiness guarding.
 */
class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var viewBinding: LayoutWebviewBinding? = null
    private var loadJob: Job? = null

    @Volatile
    private var cachedSecret: String? = null

    /**
     * Triggered by [MainActivity] when the background proxy service transitions state so the
     * dashboard automatically loads as soon as the VPN connects.
     */
    fun onProxyServiceStateChanged(state: BaseService.State) {
        if (!isAdded || viewBinding == null) return
        if (state == BaseService.State.Connected) {
            loadDashboardUrl(DataStore.yacdURL)
        }
    }

    private fun probeLocalControllerReachable(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(LOCAL_DASHBOARD_HOST_IP, LOCAL_DASHBOARD_PORT),
                CONTROLLER_CONNECT_TIMEOUT_MS,
            )
            true
        }
    }.getOrDefault(false)

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

    private fun loadDashboardUrl(configuredUrl: String) {
        val trimmed = configuredUrl.trim()
        if (!isAllowedDashboardUrl(trimmed)) {
            Logs.w("Blocked non-HTTP(S) dashboard URL")
            return
        }
        val lifecycleOwner = viewLifecycleOwnerLiveData.value ?: return
        loadJob?.cancel()
        loadJob = lifecycleOwner.lifecycleScope.launch {
            if (isLocalDashboardUrl(trimmed)) {
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

                var isControllerReady = withContext(Dispatchers.IO) { probeLocalControllerReachable() }
                if (!isControllerReady) {
                    // WHY: If Clash API was enabled while the VPN was already running without a reload,
                    // reload the core automatically and poll loopback 9090 before opening WebView.
                    SagerNet.reloadService()
                    repeat(CONTROLLER_STARTUP_MAX_ATTEMPTS) {
                        delay(CONTROLLER_STARTUP_POLL_STEP_MS)
                        if (withContext(Dispatchers.IO) { probeLocalControllerReachable() }) {
                            isControllerReady = true
                            return@repeat
                        }
                    }
                }
                if (!isControllerReady) {
                    showStatusOverlay(
                        titleRes = R.string.dashboard_controller_unavailable_title,
                        messageRes = R.string.dashboard_controller_unavailable_message,
                        buttonTextRes = R.string.dashboard_retry_load,
                    ) {
                        loadDashboardUrl(DataStore.yacdURL)
                    }
                    return@launch
                }
            }

            val secret = withContext(Dispatchers.IO) {
                ClashApiSecret.value.also { cachedSecret = it }
            }
            hideStatusOverlay()
            if (::mWebView.isInitialized) {
                mWebView.loadUrl(buildDashboardUrl(trimmed, secret))
            }
        }
    }

    private fun syncSecretIntoActivePage(webView: WebView?, url: String?) {
        if (webView == null || url == null || !isSameLocalDashboardOrigin(url) || hasDisallowedLocalBootstrapParams(url)) {
            return
        }
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            val expectedSecret = cachedSecret ?: withContext(Dispatchers.IO) {
                ClashApiSecret.value.also { cachedSecret = it }
            }
            val currentUrl = webView.url ?: url
            if (!isSameLocalDashboardOrigin(currentUrl) || hasDisallowedLocalBootstrapParams(currentUrl)) {
                return@launch
            }
            webView.evaluateJavascript(buildLocalSecretSyncScript(expectedSecret), null)
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
                    Logs.w("Blocked non-HTTP(S) WebView navigation")
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
                val scheme = request.url?.scheme?.lowercase()
                if (scheme == "data" || scheme == "blob" || scheme == "about") {
                    return super.shouldInterceptRequest(view, request)
                }
                if (!isSameLocalDashboardSubresourceOrigin(requestUrl)) {
                    Logs.w("Blocked cross-origin subresource in local dashboard")
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
                syncSecretIntoActivePage(view, url)
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
                syncSecretIntoActivePage(view, url)
            }
        }
        mWebView.clearCache(false)
        loadDashboardUrl(DataStore.yacdURL)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reload_dashboard -> {
                cachedSecret = null
                loadDashboardUrl(DataStore.yacdURL)
            }
            R.id.action_reset_default_url -> {
                DataStore.yacdURL = LOCAL_DASHBOARD_UI_URL
                cachedSecret = null
                loadDashboardUrl(LOCAL_DASHBOARD_UI_URL)
            }
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val newUrl = view.text.toString().trim()
                        if (!isAllowedDashboardUrl(newUrl)) {
                            Toast.makeText(
                                requireContext(),
                                "Only http:// and https:// URLs are supported",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        DataStore.yacdURL = newUrl
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
