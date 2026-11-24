package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
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
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Full AVENQIR first-party website, INCLUDING its separately authenticated PHP
 * administration and feedback administration pages. This process only owns
 * the independent "avenqir" WebView profile; it never handles Orbit credentials.
 *
 * PHP upload and export use the Android document picker. External websites
 * open via a user-chosen external browser or a separate public-only WebView profile.
 */
class AvenqirActivity : FragmentActivity() {
    private var webView: WebView? = null
    private var lockCurtain: LinearLayout? = null
    private lateinit var unlockPrompt: BiometricPrompt
    private var backgroundLocked = false
    private var promptActive = false
    private var lastSafePageUrl = AvenqirUrlPolicy.HOME
    private var restoredPageUrl: String? = null
    private var pendingUpload: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null
    private var navigatingToExternal = false
    private var lastFailedMainFrameUrl: String? = null
    private var initialHomeRetryUsed = false

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
        // Activity recreation after process death requires reauthentication.
        // The first launch is initiated only by the freshly unlocked MainActivity.
        backgroundLocked = savedInstanceState != null
        restoredPageUrl = savedInstanceState?.getString("avenqir_public_page")
            ?.let(AvenqirResumeRoutePolicy::publicPageWithoutState)
            ?: if (savedInstanceState != null) AvenqirUrlPolicy.HOME else null
        unlockPrompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptActive = false
                    backgroundLocked = false
                    lockCurtain?.visibility = View.GONE
                    val safeRestore = restoredPageUrl
                    restoredPageUrl = null
                    if (safeRestore != null) webView?.loadUrl(safeRestore)
                    else webView?.onResume()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptActive = false
                    // Keep the underlying page obscured after cancellation.
                }
                override fun onAuthenticationFailed() {
                    // Android may retry while the biometric prompt remains open.
                }
            }
        )

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

        // Only the main-frame error is surfaced. No hidden subresource errors,
        // no cookies/query strings in diagnostics, and no SSL bypass.
        val failureBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(if (darkMode) Color.rgb(59, 43, 42)
                else Color.rgb(255, 244, 226))
            visibility = View.GONE
        }
        val failureLabel = TextView(this).apply {
            setTextColor(foreground)
            textSize = 14f
            maxLines = 5
        }
        failureBar.addView(failureLabel)
        val failureActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(9), 0, 0)
        }
        val retryFailure = toolbarAction("⟳", "重试 AVENQIR 网页")
        val openInBrowser = toolbarAction("↗", "在系统浏览器打开 AVENQIR")
        failureActions.addView(retryFailure,
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val browserLayout = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(8)
        }
        failureActions.addView(openInBrowser, browserLayout)
        failureBar.addView(failureActions)
        layout.addView(failureBar)
        fun reportMainFrameFailure(url: String, description: String) {
            val safeUrl = AvenqirLoadFailurePolicy.safeRetryUrl(url) ?: return
            lastFailedMainFrameUrl = safeUrl
            failureLabel.text = "AVENQIR 页面加载失败：$description。请检查网络、DNS 或 VPN 分应用设置；" +
                "可点 ⟳ 重试，或点 ↗ 使用系统浏览器确认网站是否可访问。"
            failureBar.visibility = View.VISIBLE
            // Only a static classification is logged, never URL query, cookies or forms.
            android.util.Log.w("OrbitAvenqir", "first-party main-frame load failure: $description")
        }
        retryFailure.setOnClickListener {
            val retryTarget = lastFailedMainFrameUrl ?: AvenqirUrlPolicy.HOME
            failureBar.visibility = View.GONE
            page.loadUrl(retryTarget)
        }
        openInBrowser.setOnClickListener {
            val publicTarget = lastFailedMainFrameUrl ?: AvenqirUrlPolicy.HOME
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(publicTarget))
                    .addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "无法找到可用的系统浏览器",
                    Toast.LENGTH_SHORT).show()
            }
        }

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

            override fun onPageStarted(
                view: WebView?, url: String?, favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                if (url != null && AvenqirUrlPolicy.isFirstParty(url)) {
                    lastFailedMainFrameUrl = null
                    failureBar.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // A WebView error page may also trigger onPageFinished.
                // Never save a failed navigation as a successful resume page.
                if (lastFailedMainFrameUrl == null) {
                    AvenqirResumeRoutePolicy.publicPageWithoutState(url)?.let {
                        lastSafePageUrl = it
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request == null ||
                    !AvenqirLoadFailurePolicy.shouldHandle(
                        request.isForMainFrame, request.url.toString())) return
                val failedUrl = request.url.toString()
                val errorCode = error?.errorCode ?: -1
                reportMainFrameFailure(
                    failedUrl, AvenqirLoadFailurePolicy.networkFailureText(errorCode))
                // Exactly one bounded startup retry, restricted to the public GET homepage.
                // Never retry authenticated POST/admin operations automatically.
                if (AvenqirLoadFailurePolicy.shouldAutoRetryHome(
                        failedUrl, request.method, errorCode, initialHomeRetryUsed)) {
                    initialHomeRetryUsed = true
                    view?.postDelayed({
                        if (!isFinishing && !isDestroyed && !backgroundLocked &&
                            webView === view &&
                            lastFailedMainFrameUrl == AvenqirUrlPolicy.HOME) {
                            failureBar.visibility = View.GONE
                            view.loadUrl(AvenqirUrlPolicy.HOME)
                        }
                    }, 1400L)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request == null || errorResponse == null ||
                    !AvenqirLoadFailurePolicy.shouldHandle(
                        request.isForMainFrame, request.url.toString())) return
                if (AvenqirLoadFailurePolicy.isServerError(errorResponse.statusCode)) {
                    reportMainFrameFailure(request.url.toString(),
                        "服务器 HTTP ${errorResponse.statusCode}")
                }
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler, error: SslError?
            ) {
                handler.cancel()
                if (error != null && AvenqirUrlPolicy.isFirstParty(error.url)) {
                    reportMainFrameFailure(error.url, "HTTPS 证书验证失败（不会绕过证书检查）")
                }
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
            // Some OEMs make the stock AlertDialog background black without
            // switching its text to white. Explicit foreground and background
            // colors are necessary for a readable menu in either appearance.
            val menuBackground = if (darkMode) Color.rgb(26, 31, 43)
                else Color.rgb(251, 252, 254)
            val menuForeground = if (darkMode) Color.rgb(244, 247, 252)
                else Color.rgb(27, 38, 57)
            val menuMuted = if (darkMode) Color.rgb(186, 198, 216)
                else Color.rgb(85, 100, 120)
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(menuBackground)
                }
            }
            panel.addView(TextView(this).apply {
                text = "AVENQIR · 导航"
                textSize = 19f
                setTextColor(menuForeground)
                setPadding(dp(8), dp(4), dp(8), dp(10))
            })
            val dialog = AlertDialog.Builder(this).setView(panel).create()
            val destinations = listOf(
                "⌂  网站首页" to "",
                "▦  网址目录" to "directory.html",
                "⚙  实用工具" to "tools.html",
                "▣  网址管理后台" to "admin.php",
                "✉  留言管理后台" to "feedback-admin.php"
            )
            destinations.forEach { (label, route) ->
                panel.addView(TextView(this).apply {
                    text = label
                    textSize = 16f
                    setTextColor(menuForeground)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        page.loadUrl(AvenqirUrlPolicy.HOME + route)
                    }
                })
            }
            panel.addView(TextView(this).apply {
                text = "↗  第三方网址打开方式"
                textSize = 16f
                setTextColor(menuForeground)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    AvenqirBrowserLauncher(this@AvenqirActivity).settings()
                }
            })
            panel.addView(TextView(this).apply {
                text = "网站管理后台使用独立的 AVENQIR 登录会话"
                textSize = 12f
                setTextColor(menuMuted)
                setPadding(dp(8), dp(12), dp(8), dp(2))
            })
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            dialog.show()
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        back.setOnClickListener {
            if (page.canGoBack()) page.goBack() else finish()
        }
        reload.setOnClickListener { page.reload() }
        close.setOnClickListener { finish() }

        layout.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val root = FrameLayout(this)
        root.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val curtain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (darkMode) Color.rgb(16, 21, 30) else Color.rgb(246, 248, 252))
            isClickable = true
            isFocusable = true
        }
        curtain.addView(TextView(this).apply {
            text = "AVENQIR 已锁定"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(foreground)
        })
        curtain.addView(TextView(this).apply {
            text = "解锁后继续刚才的网页，不会重新加载"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(20))
            setTextColor(foreground)
        })
        val unlockButton = TextView(this).apply {
            text = "使用指纹或锁屏凭据解锁"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(actionBackground)
            }
            isClickable = true
            isFocusable = true
        }
        unlockButton.setOnClickListener { requestPageUnlock() }
        curtain.addView(unlockButton)
        curtain.visibility = if (backgroundLocked) View.VISIBLE else View.GONE
        root.addView(curtain, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lockCurtain = curtain
        setContentView(root)
        if (savedInstanceState == null) {
            page.loadUrl(AvenqirUrlPolicy.HOME)
        } // On process recreation, reload only the safe page AFTER biometric.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("avenqir_public_page", lastSafePageUrl)
        super.onSaveInstanceState(outState)
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
        if (backgroundLocked || isFinishing || !AvenqirUrlPolicy.isExternalHttps(url)) return
        AvenqirBrowserLauncher(this).open(url)
    }

    private fun requestPageUnlock() {
        if (!backgroundLocked || promptActive || isFinishing) return
        promptActive = true
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 AVENQIR")
            .setSubtitle("验证后继续刚才的页面")
            .setAllowedAuthenticators(authenticators)
            .build()
        try {
            unlockPrompt.authenticate(info)
        } catch (_: Exception) {
            promptActive = false
            Toast.makeText(this, "无法启动设备验证", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Use WebView navigation rather than closing the tools page")
    override fun onBackPressed() {
        if (backgroundLocked) {
            requestPageUnlock()
            return
        }
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
        navigatingToExternal = false
        if (backgroundLocked) {
            // Keep the previous WebView, page scroll, forms, navigation and
            // separate AVENQIR admin cookies. Never restore by reloading URL.
            lockCurtain?.visibility = View.VISIBLE
            webView?.onPause()
            lockCurtain?.post { requestPageUnlock() }
        } else {
            webView?.onResume()
        }
    }

    override fun onStop() {
        // Backgrounding is NOT closing: preserve the same WebView instance.
        // Cover sensitive admin content before recent-apps/task snapshots and
        // require biometric/device credential before revealing it on return.
        backgroundLocked = true
        promptActive = false
        lockCurtain?.visibility = View.VISIBLE
        webView?.onPause()
        CookieManager.getInstance().flush()
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
