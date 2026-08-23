package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.security.ClashApiSecret
import moe.matsuri.nb4a.utils.WebViewUtil

internal fun buildDashboardUrl(configuredUrl: String, secret: String): String {
    if (!configuredUrl.startsWith("http://127.0.0.1:9090/ui")) return configuredUrl
    val base = "http://127.0.0.1:9090/ui/"
    // Yacd expects hostname and port as separate bootstrap parameters.
    // Passing a complete URL as hostname makes URL.hostname invalid and the
    // SPA renders a blank dashboard despite a successful HTTP handshake.
    return "${base}?hostname=127.0.0.1&port=9090&secret=$secret"
}

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView

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
                    Logs.d("WebView Console [${consoleMessage.messageLevel()}]: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        mWebView.webViewClient = object : WebViewClient() {
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
                    // A previous Yacd session may have persisted an empty
                    // secret in localStorage. Remove only that Yacd state once
                    // per WebView session, then reload with our authenticated
                    // bootstrap URL.
                    view?.evaluateJavascript(
                        """(() => {
                            const marker = '__neko_dashboard_reset_v1';
                            if (sessionStorage.getItem(marker) === '1') return;
                            sessionStorage.setItem(marker, '1');
                            localStorage.removeItem('yacd.metacubex.one');
                            location.reload();
                        })()""".trimIndent(),
                        null,
                    )
                }
            }
        }
        mWebView.clearCache(false)
        mWebView.loadUrl(buildDashboardUrl(DataStore.yacdURL, ClashApiSecret.value))
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
                        DataStore.yacdURL = view.text.toString()
                        mWebView.loadUrl(buildDashboardUrl(DataStore.yacdURL, ClashApiSecret.value))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                mWebView.onPause()
                mWebView.removeAllViews()
                mWebView.destroy()
            }
        }
        return true
    }
}
