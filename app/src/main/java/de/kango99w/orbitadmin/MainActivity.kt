package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Local device authentication gates visibility. The existing website, and only the
 * existing website, issues admin sessions and enforces its password and TOTP policies.
 */
class MainActivity : FragmentActivity() {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private var unlocked by mutableStateOf(false)
    private var message by mutableStateOf("使用设备生物识别或锁屏凭据解锁。")
    private var showClearConfirm by mutableStateOf(false)
    private var browser: WebView? = null
    private var openedOnce = false
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var allowMultipleFiles = false
    private var deferredFileUris: Array<Uri>? = null
    private lateinit var biometricPrompt: BiometricPrompt

    private val singleFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
            finishFilePick(listOfNotNull(selected))
        }

    private val multipleFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
            finishFilePick(selected)
        }

    private fun finishFilePick(selected: List<Uri>) {
        val callback = pendingFileCallback ?: return
        val accepted = selected.take(if (allowMultipleFiles) selected.size else 1).toTypedArray()
        if (accepted.isEmpty()) {
            pendingFileCallback = null
            deferredFileUris = null
            callback.onReceiveValue(null)
        } else if (unlocked) {
            pendingFileCallback = null
            deferredFileUris = null
            callback.onReceiveValue(accepted)
        } else {
            // A document picker can background the app. The file is only sent
            // to the website after the device has been unlocked again.
            deferredFileUris = accepted
            message = "已选择文件；请重新解锁以继续上传。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    message = "设备解锁完成；管理员身份仍由网站验证。"
                    browser?.resumeTimers()
                    browser?.onResume()
                    deferredFileUris?.let { selection ->
                        val callback = pendingFileCallback
                        deferredFileUris = null
                        pendingFileCallback = null
                        callback?.onReceiveValue(selection)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    unlocked = false
                    message = "未解锁：请重新验证设备身份。"
                }

                override fun onAuthenticationFailed() {
                    message = "验证未通过，请使用已录入的生物识别或锁屏凭据。"
                }
            }
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (unlocked) AdminScreen() else LockScreen()
                }
            }
        }
    }

    private fun requestUnlock() {
        val available = BiometricManager.from(this).canAuthenticate(authenticators)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            message = "当前设备尚不能安全验证身份。请先设置屏幕锁定。"
            return
        }
        val prompt = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 Orbit Admin")
            .setSubtitle("指纹、其他强生物识别或设备锁屏凭据")
            .setAllowedAuthenticators(authenticators)
            .build()
        biometricPrompt.authenticate(prompt)
    }

    @Composable
    private fun LockScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Orbit Admin", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.padding(8.dp))
            Text("本机解锁不代替网站的账号密码和双重验证。")
            Spacer(modifier = Modifier.padding(10.dp))
            Button(onClick = ::requestUnlock) { Text("使用指纹 / 锁屏密码解锁") }
            OutlinedButton(onClick = {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    message = "无法打开系统安全设置。"
                }
            }) { Text("系统锁屏设置") }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun AdminScreen() {
        BackHandler {
            val current = browser
            if (current?.canGoBack() == true) current.goBack()
            else lock()
        }
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("清除本机网站数据？") },
                text = {
                    Text("将清除 APK 内的网站 Cookie 和本地存储，并要求重新登录。"
                        + "这不会撤销服务器上的管理员会话或可信设备授权；请在网站安全中心撤销。")
                },
                confirmButton = {
                    TextButton(onClick = ::clearLocalSession) { Text("清除并锁定") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                }
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Orbit Admin", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    browser?.loadUrl(AdminUrlPolicy.HOME)
                }) { Text("首页") }
                OutlinedButton(onClick = ::lock) { Text("锁定") }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdminDestination.entries.forEach { entry ->
                    OutlinedButton(onClick = {
                        if (unlocked && AdminUrlPolicy.isTrustedMainFrame(entry.address)) {
                            browser?.loadUrl(entry.address)
                        }
                    }) { Text(entry.title) }
                }
            }
            AndroidView(
                factory = { obtainBrowser() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = {
                    browser?.let { if (it.canGoBack()) it.goBack() }
                }) { Text("返回") }
                TextButton(onClick = {
                    browser?.let { if (it.canGoForward()) it.goForward() }
                }) { Text("前进") }
                TextButton(onClick = { browser?.reload() }) { Text("刷新") }
                TextButton(onClick = { showClearConfirm = true }) { Text("清理本机") }
            }
            Text(
                text = message,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    private fun clearLocalSession() {
        showClearConfirm = false
        val current = browser
        current?.stopLoading()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            runOnUiThread {
                if (browser === current) {
                    current?.clearHistory()
                    current?.loadUrl(AdminUrlPolicy.HOME)
                }
                message = "本机网站数据已清除。重新登录仍需要服务器授权与 2FA。"
            }
        }
        lock()
    }

    private fun lock() {
        unlocked = false
        browser?.onPause()
        browser?.pauseTimers()
        message = "已锁定。需要再次通过设备身份验证。"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainBrowser(): WebView {
        browser?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        val view = WebView(this)
        WebView.setWebContentsDebuggingEnabled(false)
        view.settings.apply {
            javaScriptEnabled = true // Required by this first-party admin website.
            domStorageEnabled = true
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (AdminUrlPolicy.isTrustedMainFrame(url)) return false
                if (request.hasGesture() && AdminUrlPolicy.isExternalHttps(url)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url)
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        message = "系统浏览器不可用。"
                    }
                } else {
                    message = "已拦截非管理员网站的自动跳转。"
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url == null || !AdminUrlPolicy.isTrustedMainFrame(url)) {
                    view?.stopLoading()
                    message = "页面来源不符合管理员域名限制。"
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && AdminUrlPolicy.isTrustedMainFrame(url)) {
                    message = "网站会话由服务器验证；2FA 和可信设备与网页版共用。"
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Never proceed on certificate errors.
                message = "证书校验失败，已阻止连接。"
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) message = "管理员页面加载失败，请检查网络后重试。"
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                deferredFileUris = null
                browser = null
                openedOnce = false
                unlocked = false
                message = "网页进程意外退出，重新解锁后可恢复管理页面。"
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny() // No camera/microphone/location access from web content.
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (!unlocked || filePathCallback == null) return false
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                allowMultipleFiles = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val accepted = fileChooserParams?.acceptTypes.orEmpty().map { it.trim() }
                    .filter { it.matches(Regex("^[A-Za-z0-9.+*-]+/[A-Za-z0-9.+*-]+$")) }
                    .ifEmpty { listOf("*/*") }
                    .toTypedArray()
                return try {
                    if (allowMultipleFiles) multipleFilePicker.launch(accepted)
                    else singleFilePicker.launch(accepted)
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
                    deferredFileUris = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        view.setDownloadListener { _, _, _, _, _ ->
            message = "下载暂未开放；请通过网页版完成敏感附件下载。"
        }
        browser = view
        if (!openedOnce) {
            openedOnce = true
            view.loadUrl(AdminUrlPolicy.HOME)
        }
        return view
    }

    override fun onStop() {
        lock() // Lock even when device/app backgrounding; cookies remain website-managed.
        super.onStop()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        deferredFileUris = null
        val current = browser
        browser = null
        (current?.parent as? ViewGroup)?.removeView(current)
        current?.destroy()
        super.onDestroy()
    }
}
