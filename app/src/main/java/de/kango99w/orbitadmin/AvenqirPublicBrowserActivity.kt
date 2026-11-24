package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Public-only viewer: separate process and WebView data suffix; no stored site cookies.
 * No JS/native bridge, file access, uploads, downloads, permissions or SSL exceptions.
 */
class AvenqirPublicBrowserActivity : Activity() {
    private var page: WebView? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do not restore browsing state or automatically reload after process death.
        val url = intent.getStringExtra("url").orEmpty()
        if (savedInstanceState != null || !AvenqirUrlPolicy.isExternalHttps(url)) {
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val address = TextView(this).apply { text = "第三方网站 · " + Uri.parse(url).host; setPadding(16,12,16,12) }
        root.addView(address)
        val row = LinearLayout(this)
        val web = WebView(this)
        page = web
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(false)
        cookies.setAcceptThirdPartyCookies(web, false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                !AvenqirUrlPolicy.isExternalHttps(request.url.toString())
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url == null || !AvenqirUrlPolicy.isExternalHttps(url)) {
                    view?.stopLoading()
                    return
                }
                address.text = "第三方网站 · " + Uri.parse(url).host
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
                Toast.makeText(this@AvenqirPublicBrowserActivity, "HTTPS 证书验证失败", Toast.LENGTH_SHORT).show()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
                page = null
                finish()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
        }
        web.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "下载请使用系统浏览器", Toast.LENGTH_SHORT).show()
        }
        fun button(label: String, action: () -> Unit) {
            row.addView(Button(this).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button("返回") { if (web.canGoBack()) web.goBack() else finish() }
        button("刷新") { web.reload() }
        button("外部打开") {
            val current = web.url ?: url
            if (AvenqirUrlPolicy.isExternalHttps(current)) {
                try { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(current))
                    .addCategory(Intent.CATEGORY_BROWSABLE), "选择浏览器")) }
                catch (_: Exception) { Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show() }
            }
        }
        button("关闭") { finish() }
        root.addView(row)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl(url)
    }
    override fun onPause() { page?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); page?.onResume() }
    override fun onStop() {
        // Public viewer does not retain pages on background; parent remains locked.
        super.onStop()
        finish()
    }
    @Deprecated("Web navigation")
    override fun onBackPressed() {
        val web = page
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        page?.let {
            it.stopLoading()
            it.clearHistory()
            it.clearCache(true)
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        page = null
        super.onDestroy()
    }
}
