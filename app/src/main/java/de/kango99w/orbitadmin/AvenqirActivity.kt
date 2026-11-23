package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Full AVENQIR first-party website, INCLUDING its separately authenticated PHP
 * administration and feedback administration pages. This process only owns
 * the independent "avenqir" WebView profile; it never handles Orbit credentials.
 *
 * PHP upload and export use the Android document picker. External websites
 * remain in the user's system browser, not in the trusted AVENQIR WebView.
 */
class AvenqirActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingUpload: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null
    private var navigatingToExternal = false

    private val uploadPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingUpload
        pendingUpload = null
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination ->
        val url = pendingExport
        pendingExport = null
        if (destination != null && url != null) saveAuthenticatedJson(url, destination)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        WebView.setWebContentsDebuggingEnabled(false)

        // OrbitApplication.setDataDirectorySuffix("avenqir") has already run
        // before CookieManager or WebView initialization in this process.
        CookieManager.getInstance().setAcceptCookie(true)
        val darkMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val headerBackground = if (darkMode) Color.rgb(26, 30, 40)
            else Color.rgb(247, 249, 252)
        val foreground = if (darkMode) Color.rgb(242, 245, 250)
            else Color.rgb(27, 42, 64)
        val actionBackground = if (darkMode) Color.rgb(48, 55, 68)
            else Color.rgb(232, 237, 244)
        fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBackground)
            setPadding(dp(12), dp(5), dp(8), dp(5))
        }
        val title = TextView(this).apply {
            text = "⬡ AVENQIR"
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(foreground)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun toolbarAction(glyph: String, description: String): TextView =
            TextView(this).apply {
                text = glyph
                textSize = 23f
                contentDescription = description
                gravity = Gravity.CENTER
                setTextColor(foreground)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(actionBackground)
                }
            }
        // Four accessible 48dp controls rather than full-width text buttons.
        // Remains on one row at 320dp, with no horizontal scrolling.
        val navigation = toolbarAction("☰", "打开导航菜单")
        val back = toolbarAction("‹", "返回上一页")
        val reload = toolbarAction("⟳", "刷新当前页面")
        val close = toolbarAction("×", "关闭工具中心")
        toolbar.addView(title, LinearLayout.LayoutParams(
            0, dp(48), 1f))
        listOf(navigation, back, reload, close).forEach { action ->
            val layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            layoutParams.marginStart = dp(3)
            toolbar.addView(action, layoutParams)
        }
        layout.addView(toolbar)

        val progress = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply { max = 100; progress = 0 }
        layout.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 4))

        val page = WebView(this)
        webView = page
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
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
        page.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                pendingUpload?.onReceiveValue(null)
                pendingUpload = filePathCallback
                return try {
                    uploadPicker.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    pendingUpload = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@AvenqirActivity,
                        "无法打开系统文件选择器", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (AvenqirUrlPolicy.isFirstParty(url)) return false
                if (AvenqirUrlPolicy.isExternalHttps(url)) {
                    if (request.hasGesture()) openExternal(url)
                    else AlertDialog.Builder(this@AvenqirActivity)
                        .setTitle("打开第三方网站？")
                        .setMessage("将离开 AVENQIR，前往 " + request.url.host +
                            "。无法保证该网站安全。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("继续") { _, _ -> openExternal(url) }
                        .show()
                }
                return true
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler, error: SslError?
            ) {
                handler.cancel()
                Toast.makeText(this@AvenqirActivity,
                    "HTTPS 证书验证失败", Toast.LENGTH_SHORT).show()
            }

            override fun onRenderProcessGone(
                view: WebView?, detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                webView = null
                finish()
                return true
            }
        }
        page.setDownloadListener { url, _, _, mimeType, _ ->
            // Only JSON backups from AVENQIR's existing authenticated PHP pages.
            // Never send its Cookie to a foreign host or arbitrary downloader.
            if (!AvenqirUrlPolicy.isFirstParty(url) ||
                !(url.contains("/admin.php") || url.contains("/feedback-admin.php")) ||
                !url.contains("export") || !mimeType.orEmpty().contains("json", true)) {
                Toast.makeText(this, "此下载不属于 AVENQIR JSON 备份",
                    Toast.LENGTH_SHORT).show()
            } else {
                pendingExport = url
                exportPicker.launch("avenqir-backup-${System.currentTimeMillis()}.json")
            }
        }
        navigation.setOnClickListener {
            val destinations = arrayOf(
                "网站首页", "网址目录", "实用工具",
                "网址管理后台", "留言管理后台"
            )
            val paths = arrayOf(
                "", "directory.html", "tools.html", "admin.php", "feedback-admin.php"
            )
            AlertDialog.Builder(this)
                .setTitle("AVENQIR · 导航")
                .setItems(destinations) { _, which ->
                    page.loadUrl(AvenqirUrlPolicy.HOME + paths[which])
                }
                .setNegativeButton("取消", null)
                .show()
        }
        back.setOnClickListener {
            if (page.canGoBack()) page.goBack() else finish()
        }
        reload.setOnClickListener { page.reload() }
        close.setOnClickListener { finish() }

        layout.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        page.loadUrl(AvenqirUrlPolicy.HOME)
    }

    /**
     * Authenticated JSON export is explicitly user initiated, HTTPS only,
     * bounded to 20 MiB, without redirects, and kept in this isolated process.
     */
    private fun saveAuthenticatedJson(url: String, destination: Uri) {
        Thread({
            var connection: HttpsURLConnection? = null
            try {
                if (!AvenqirUrlPolicy.isFirstParty(url)) error("Unexpected export host")
                val conn = URL(url).openConnection() as HttpsURLConnection
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                CookieManager.getInstance().getCookie(url)?.let {
                    conn.setRequestProperty("Cookie", it)
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK ||
                    !conn.contentType.orEmpty().contains("application/json", true))
                    error("Not an authenticated JSON export")
                if (conn.contentLengthLong > 20L * 1024 * 1024)
                    error("Backup exceeds permitted size")
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var transferred = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            transferred += count
                            if (transferred > 20L * 1024 * 1024)
                                error("Backup exceeds permitted size")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Cannot write chosen document")
                runOnUiThread {
                    Toast.makeText(this, "已保存 AVENQIR JSON 备份",
                        Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                try { contentResolver.delete(destination, null, null) } catch (_: Exception) {}
                runOnUiThread {
                    Toast.makeText(this, "备份未成功导出；检查后台登录状态",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }, "avenqir-export").start()
    }

    private fun openExternal(url: String) {
        try {
            navigatingToExternal = true
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            navigatingToExternal = false
            Toast.makeText(this, "没有可用的系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Use WebView navigation rather than closing the tools page")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack()
        else super.onBackPressed()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        navigatingToExternal = false
    }

    override fun onStop() {
        // Never leave a privileged AVENQIR admin page visible on recents.
        // Document pickers are transient system flows; other departures require
        // fresh Orbit device unlock to enter the tools again.
        webView?.onPause()
        CookieManager.getInstance().flush()
        if (pendingUpload == null && pendingExport == null) finish()
        super.onStop()
    }

    override fun onDestroy() {
        pendingUpload?.onReceiveValue(null)
        pendingUpload = null
        pendingExport = null
        webView?.stopLoading()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
