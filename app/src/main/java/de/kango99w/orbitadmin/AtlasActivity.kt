package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.net.http.SslError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Opt-in, separate-process preview of the public AVENQIR atlas.
 *
 * This is the ONLINE preview: no claim is made that full site assets are
 * packaged in this APK yet. The separate process has its own WebView profile.
 * There are no JavaScript bridges or mailbox/admin tokens in this Activity.
 */
class AtlasActivity : Activity() {
    private lateinit var page: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "AVENQIR · 工具中心"
            textSize = 18f
            setPadding(20, 15, 12, 15)
        }
        val close = Button(this).apply {
            text = "返回邮箱"
            setOnClickListener { finish() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 4))

        status = TextView(this).apply {
            text = "应用内 AVENQIR · 首次打开需要网络"
            textSize = 12f
            setPadding(20, 4, 12, 4)
        }
        root.addView(status)

        page = WebView(this)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        // The atlas process has an independent WebView data directory; disable
        // cookies entirely because this is a public, non-authenticated tool.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)

        page.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (AtlasNavigationPolicy.isAtlas(url)) return false
                if (request.hasGesture() && AtlasNavigationPolicy.isExternalHttps(url)) {
                    confirmExternal(request.url)
                } else {
                    status.text = "已阻止非用户主动发起的外部跳转或不安全网址"
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && !AtlasNavigationPolicy.isAtlas(url)) {
                    view?.stopLoading()
                    status.text = "已阻止离开 AVENQIR 的自动导航"
                    return
                }
                progress.visibility = View.VISIBLE
                status.text = "正在加载 AVENQIR…"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url != null && AtlasNavigationPolicy.isAtlas(url))
                    status.text = "AVENQIR · 独立工具空间（不共享邮箱会话）"
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                progress.visibility = View.GONE
                status.text = "HTTPS 证书验证失败，已阻止访问"
            }
        }

        root.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || page.restoreState(savedInstanceState) == null) {
            page.loadUrl(AtlasNavigationPolicy.ENTRY)
        }
    }

    private fun confirmExternal(destination: Uri) {
        val value = destination.toString()
        if (!AtlasNavigationPolicy.isExternalHttps(value)) return
        AlertDialog.Builder(this)
            .setTitle("即将打开第三方网站")
            .setMessage("目标域名：" + destination.host +
                "\n此网站不属于 AVENQIR，安全性未获得认证。是否使用系统浏览器打开？")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, destination)
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    status.text = "未找到能打开此网址的系统浏览器"
                }
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::page.isInitialized && page.canGoBack()) page.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::page.isInitialized) page.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::page.isInitialized) page.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) page.onResume()
    }

    override fun onDestroy() {
        if (::page.isInitialized) {
            (page.parent as? ViewGroup)?.removeView(page)
            page.stopLoading()
            page.destroy()
        }
        super.onDestroy()
    }
}
