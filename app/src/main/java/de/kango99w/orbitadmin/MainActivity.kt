package de.kango99w.orbitadmin

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat

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
    private var showTools by mutableStateOf(false)
    private var activeSection by mutableStateOf(MailSection.INBOX)
    private var previousWebSection = MailSection.INBOX
    private var browser: WebView? = null
    private var openedOnce = false
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var allowMultipleFiles = false
    private var deferredFileUris: Array<Uri>? = null
    private var pickerPending = false
    private var pickerLaunchedAtMs = 0L
    private val notificationChannelId = "orbit_mail_notice"
    private var lastNewMailNoticeAtMs = 0L

    private lateinit var biometricPrompt: BiometricPrompt

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) sendTestNotification()
            else message = "系统未授权通知；不会发送任何邮件内容。"
        }

    private fun pickerReturnAllowed(): Boolean {
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        return PickerReturnPolicy.canResumeWithoutUnlock(
            pickerLaunchedAtMs, SystemClock.elapsedRealtime(), locked
        )
    }

    private val singleFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
            finishFilePick(listOfNotNull(selected))
        }

    private val multipleFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
            finishFilePick(selected)
        }

    private fun finishFilePick(selected: List<Uri>) {
        val safeReturn = pickerPending && pickerReturnAllowed()
        pickerPending = false
        pickerLaunchedAtMs = 0
        if (!safeReturn) lock()
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
        createNotificationChannel()

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    message = ""
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

    private fun openSection(section: MailSection) {
        showTools = false
        if (section == MailSection.SETTINGS) {
            activeSection = section
            browser?.onPause()
            browser?.pauseTimers()
            return
        }
        previousWebSection = section
        activeSection = section
        browser?.resumeTimers()
        browser?.onResume()
        section.address?.let { safe ->
            if (unlocked && AdminUrlPolicy.isTrustedMainFrame(safe)) {
                browser?.loadUrl(safe)
            }
        }
    }

    private fun navigateTo(url: String) {
        if (!unlocked || !AdminUrlPolicy.isTrustedMainFrame(url)) return
        showTools = false
        activeSection = MailSection.ADMIN
        previousWebSection = MailSection.ADMIN
        browser?.resumeTimers()
        browser?.onResume()
        browser?.loadUrl(url)
    }

    @Composable
    private fun MailToolsDialog() {
        AlertDialog(
            onDismissRequest = { showTools = false },
            title = { Text("管理工具") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("所有功能继续使用现有网站授权和双重验证。", style = MaterialTheme.typography.bodySmall)
                    AdminDestination.entries.forEach { entry ->
                        OutlinedButton(
                            onClick = { navigateTo(entry.address) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(entry.title) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTools = false }) { Text("关闭") }
            }
        )
    }

    @Composable
    private fun SettingsContent(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text("应用设置", style = MaterialTheme.typography.headlineSmall)
            Text("管理账号登录、2FA、恢复码及可信设备均由网站负责。",
                style = MaterialTheme.typography.bodyMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("邮件提醒", style = MaterialTheme.typography.titleMedium)
                    Text("当前只支持应用使用期间的提醒；应用完全关闭后不保证送达。通知不包含邮箱、验证码或正文。",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::requestTestNotification) {
                        Icon(painterResource(R.drawable.ic_bell), contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("测试系统通知")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账户与安全", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { navigateTo(AdminDestination.SECURITY.address) }) {
                        Icon(painterResource(R.drawable.ic_shield), contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("网站 2FA / 可信设备")
                    }
                    OutlinedButton(onClick = { showClearConfirm = true }) { Text("清除 APK 本机网站数据") }
                    Text("清理本地数据不会撤销服务器上的管理员会话。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { openSection(previousWebSection) }) { Text("返回邮箱") }
            OutlinedButton(onClick = ::lock) { Text("锁定应用") }
            HorizontalDivider()
            Text("Orbit Mail / Admin · Android 测试版。无自动发信或离线后台收信能力。",
                style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun AdminScreen() {
        BackHandler {
            when {
                showTools -> showTools = false
                activeSection == MailSection.SETTINGS -> openSection(previousWebSection)
                browser?.canGoBack() == true -> browser?.goBack()
                activeSection != MailSection.INBOX -> openSection(MailSection.INBOX)
                else -> lock()
            }
        }
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("清除本机网站数据？") },
                text = {
                    Text("将清除当前 APK 中的网站 Cookie 和本地存储，并要求重新登录。"
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
        if (showTools) MailToolsDialog()
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ORBIT MAIL", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(activeSection.label, style = MaterialTheme.typography.titleLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeSection != MailSection.SETTINGS) {
                            TextButton(onClick = { browser?.reload() }) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新当前邮箱页面")
                            }
                        }
                        TextButton(onClick = { showTools = true }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = "打开管理工具")
                        }
                        TextButton(onClick = ::lock) { Text("锁定") }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    MailSection.entries.forEach { section ->
                        NavigationBarItem(
                            selected = section == activeSection,
                            onClick = { openSection(section) },
                            icon = {
                                Icon(
                                    painter = painterResource(when (section) {
                                        MailSection.INBOX -> R.drawable.ic_mail
                                        MailSection.MAILBOXES -> R.drawable.ic_folder
                                        MailSection.TRANSFER -> R.drawable.ic_file
                                        MailSection.ADMIN -> R.drawable.ic_admin
                                        MailSection.SETTINGS -> R.drawable.ic_settings
                                    }),
                                    contentDescription = null
                                )
                            },
                            label = { Text(section.label) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { inner ->
            if (activeSection == MailSection.SETTINGS) {
                SettingsContent(Modifier.fillMaxSize().padding(inner))
            } else {
                Column(Modifier.fillMaxSize().padding(inner)) {
                    AndroidView(
                        factory = { obtainBrowser() },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    if (message.isNotBlank()) {
                        Text(
                            text = message,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
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
        // The only website→Android channel carries one constant event, never
        // administrator identity, cookies, TOTP, message content or native commands.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,
                "orbitAdminMailNotice",
                setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender, data, origin, mainFrame, _ ->
                    if (data.type != WebMessageCompat.TYPE_STRING ||
                        !MailNoticePolicy.accepts(
                            origin.scheme, origin.host, origin.port, mainFrame, data.data
                        ) ||
                        !AdminUrlPolicy.isTrustedMainFrame(sender.url ?: "")
                    ) return@WebMessageListener
                    runOnUiThread {
                        if (!unlocked) return@runOnUiThread
                        val stamp = SystemClock.elapsedRealtime()
                        if (!MailNoticePolicy.canDeliver(stamp, lastNewMailNoticeAtMs)) return@runOnUiThread
                        if (showMailNotification()) lastNewMailNoticeAtMs = stamp
                    }
                }
            )
        }
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
                    message = ""
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
                pickerPending = false
                pickerLaunchedAtMs = 0
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
                    pickerPending = true
                    pickerLaunchedAtMs = SystemClock.elapsedRealtime()
                    if (allowMultipleFiles) multipleFilePicker.launch(accepted)
                    else singleFilePicker.launch(accepted)
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
                    deferredFileUris = null
                    pickerPending = false
                    pickerLaunchedAtMs = 0
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
            view.loadUrl(activeSection.address ?: MailSection.INBOX.address!!)
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        // Leaving through the trusted Android document picker is not a new login,
        // unless the device was locked or the bounded return window has expired.
        if (pickerPending && !pickerReturnAllowed()) lock()
    }

    override fun onResume() {
        super.onResume()
        if (pickerPending && !pickerReturnAllowed()) lock()
        if (unlocked) {
            browser?.resumeTimers()
            browser?.onResume()
        }
    }

    override fun onStop() {
        if (pickerPending && pickerReturnAllowed()) {
            browser?.onPause()
            browser?.pauseTimers()
        } else {
            lock()
        }
        super.onStop()
    }

    private fun showMailNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notice = Notification.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Orbit Admin · 收到新邮件")
            .setContentText("请解锁应用查看，通知不会显示邮件地址、正文或验证码。")
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1002, notice)
        return true
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId, "Orbit 邮件提醒", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "隐藏正文和验证码的邮件到达提醒"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun requestTestNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        sendTestNotification()
    }

    private fun sendTestNotification() {
        // A visible test proves system permission and notification channel only.
        // It does not claim that server push or new-mail detection is connected.
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notice = Notification.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Orbit Admin · 通知测试")
            .setContentText("系统通知通道已就绪；新邮件后台推送仍需服务器端接入。")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1001, notice)
        message = "已发送测试通知；它不是新邮件提醒。"
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        deferredFileUris = null
        pickerPending = false
        pickerLaunchedAtMs = 0
        val current = browser
        browser = null
        (current?.parent as? ViewGroup)?.removeView(current)
        current?.destroy()
        super.onDestroy()
    }
}
