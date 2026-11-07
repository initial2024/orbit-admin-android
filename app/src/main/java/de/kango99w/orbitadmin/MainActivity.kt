package de.kango99w.orbitadmin

import android.annotation.SuppressLint
import android.app.ActivityNotFoundException
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    private var browser: WebView? = null
    private var openedOnce = false
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var allowMultipleFiles = false
    private lateinit var biometricPrompt: BiometricPrompt

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(
                selected.take(if (allowMultipleFiles) selected.size else 1)
                    .takeIf { it.isNotEmpty() }?.toTypedArray()
            )
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
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Orbit Admin", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { browser?.loadUrl(AdminUrlPolicy.HOME) }) {
                    Text("管理首页")
                }
                OutlinedButton(onClick = ::lock) { Text("锁定") }
            }
            AndroidView(
                factory = { obtainBrowser() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
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
                    filePicker.launch(accepted)
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
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
        val current = browser
        browser = null
        (current?.parent as? ViewGroup)?.removeView(current)
        current?.destroy()
        super.onDestroy()
    }
}
