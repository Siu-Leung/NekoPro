package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.security.ClashApiSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.WebViewUtil
import java.net.URI
import org.json.JSONObject

internal fun buildDashboardUrl(configuredUrl: String, secret: String): String {
    if (!configuredUrl.startsWith("http://127.0.0.1:9090/ui")) return configuredUrl
    val base = "http://127.0.0.1:9090/ui/"
    // Yacd expects hostname and port as separate bootstrap parameters.
    // Passing a complete URL as hostname makes URL.hostname invalid and the
    // SPA renders a blank dashboard despite a successful HTTP handshake.
    return "${base}?hostname=127.0.0.1&port=9090&secret=$secret"
}

internal fun isLocalDashboardUrl(url: String): Boolean {
    return url.trim().startsWith("http://127.0.0.1:9090/ui")
}

internal fun isSameLocalDashboardOrigin(targetUrl: String): Boolean {
    val parsed = runCatching { URI(targetUrl.trim()) }.getOrNull() ?: return false
    return parsed.scheme?.equals("http", ignoreCase = true) == true &&
        parsed.host == "127.0.0.1" &&
        parsed.port == 9090
}

private fun isAllowedDashboardUrl(url: String): Boolean {
    val scheme = Uri.parse(url.trim()).scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    @Volatile
    private var cachedSecret: String? = null

    private fun loadDashboardUrl(configuredUrl: String) {
        val trimmed = configuredUrl.trim()
        if (!isAllowedDashboardUrl(trimmed)) {
            Logs.w("Blocked non-HTTP(S) dashboard URL")
            return
        }
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            val secret = cachedSecret ?: withContext(Dispatchers.IO) {
                ClashApiSecret.value.also { cachedSecret = it }
            }
            if (::mWebView.isInitialized) {
                mWebView.loadUrl(buildDashboardUrl(trimmed, secret))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
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
            private fun handleNavigation(targetUrl: String?): Boolean {
                if (targetUrl.isNullOrBlank() || !isAllowedDashboardUrl(targetUrl)) {
                    Logs.w("Blocked non-HTTP(S) WebView navigation")
                    return true
                }
                if (isLocalDashboardUrl(DataStore.yacdURL) && !isSameLocalDashboardOrigin(targetUrl)) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                    }.onFailure { Logs.w(it) }
                    return true
                }
                return false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                return handleNavigation(request?.url?.toString())
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigation(url)
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
                if (url?.startsWith("http://127.0.0.1:9090/ui") == true) {
                    val webView = view ?: return
                    viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
                        val expectedSecret = cachedSecret ?: withContext(Dispatchers.IO) {
                            ClashApiSecret.value.also { cachedSecret = it }
                        }
                        val quotedSecret = JSONObject.quote(expectedSecret)
                        webView.evaluateJavascript(
                            """(() => {
                                try {
                                    const key = 'yacd.metacubex.one';
                                    const raw = localStorage.getItem(key);
                                    if (!raw) return;
                                    const state = JSON.parse(raw);
                                    const backends = state && state.clashAPIConfigs;
                                    if (Array.isArray(backends) && backends.length > 0 && backends[0].secret !== $quotedSecret) {
                                        backends[0].secret = $quotedSecret;
                                        localStorage.setItem(key, JSON.stringify(state));
                                        location.reload();
                                    }
                                } catch (_) {}
                            })()""".trimIndent(),
                            null,
                        )
                    }
                }
            }
        }
        mWebView.clearCache(false)
        loadDashboardUrl(DataStore.yacdURL)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
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
