package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.net.http.SslError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Public tools in their own Android process and WebView data directory.
 *
 * No administrator/ordinary-account cookies, no JS bridge, no native auth API,
 * no import of any Mail client, no third-party WebView navigation. The web app
 * remains maintained by the existing AVENQIR deploy pipeline.
 */
class AvenqirActivity : Activity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)

        // This activity is manifested in :avenqir. OrbitApplication assigns
        // a distinct WebView profile *before* this Activity is instantiated.
        CookieManager.getInstance().setAcceptCookie(false)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "AVENQIR"
            textSize = 17f
            setPadding(14, 0, 8, 0)
        }
        val back = Button(this).apply { text = "返回" }
        val reload = Button(this).apply { text = "刷新" }
        val close = Button(this).apply { text = "关闭" }
        toolbar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(back)
        toolbar.addView(reload)
        toolbar.addView(close)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        layout.addView(toolbar)
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4))
        val page = WebView(this)
        webView = page
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        page.settings.apply {
            javaScriptEnabled = true             // AVENQIR's own dynamic catalog.
            domStorageEnabled = true             // Explicit local language/preferences.
            cacheMode = WebSettings.LOAD_DEFAULT  // Existing PWA cache; no fresh proxy.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
        }
        page.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if(newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (AvenqirUrlPolicy.isFirstParty(url)) return false
                // Do not silently open third-party sites. AVENQIR's own leave
                // page must first handle its confirmation and safety policy.
                if (request.hasGesture() && AvenqirUrlPolicy.isExternalHttps(url) &&
                    view?.url?.startsWith("https://avenqir.bg5.net/leave.html") == true) {
                    openExternal(url)
                }
                return true
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler, error: SslError?
            ) {
                handler.cancel()  // Never bypass HTTPS verification.
                Toast.makeText(this@AvenqirActivity, "网页证书校验失败", Toast.LENGTH_SHORT).show()
            }
        }
        back.setOnClickListener {
            if (page.canGoBack()) page.goBack() else finish()
        }
        reload.setOnClickListener { page.reload() }
        close.setOnClickListener { finish() }
        layout.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        page.loadUrl(AvenqirUrlPolicy.HOME)
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的外部网页应用", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Use WebView navigation rather than closing the tools page")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }
}