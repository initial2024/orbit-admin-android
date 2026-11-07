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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

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
    private var nativeInbox by mutableStateOf<NativeInboxSnapshot?>(null)
    private var showNativeMail by mutableStateOf(true)
    private var webLoginInProgress by mutableStateOf(false)
    private var nativeSearchQuery by mutableStateOf("")
    private var showCreateMailbox by mutableStateOf(false)
    private var nativeCreateLocal by mutableStateOf("")
    private var nativeCreateDomain by mutableStateOf("")
    private var activeSection by mutableStateOf(MailSection.INBOX)
    private var previousWebSection = MailSection.INBOX
    private val sectionBrowsers = mutableMapOf<MailSection,WebView>()
    private val browser: WebView? get() = sectionBrowsers[BrowserSessionRouting.key(activeSection)]
    private fun publicWebView(): WebView? = sectionBrowsers[MailSection.INBOX]
    private var pendingAdminUrl: String? = null
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var allowMultipleFiles = false
    private var deferredFileUris: Array<Uri>? = null
    private var pickerPending = false
    private var pickerLaunchedAtMs = 0L
    private val notificationChannelId = "orbit_mail_notice"
    private var lastNewMailNoticeAtMs = 0L
    private var backgroundPushOptIn by mutableStateOf(false)
    private var backgroundPushStatus by mutableStateOf("未启用")
    private var lastPushAttemptAtMs = 0L

    private lateinit var biometricPrompt: BiometricPrompt

    private val pushPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setPushOptIn(true)
                tryRegisterPush()
            } else backgroundPushStatus = "未授予通知权限，设备未登记。"
        }

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
        backgroundPushOptIn = getSharedPreferences("orbit_push",MODE_PRIVATE)
            .getBoolean("opt_in",false)
        backgroundPushStatus = if (backgroundPushOptIn) "已授权，等待服务器确认" else "未启用"

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    message = ""
                    browser?.resumeTimers()
                    browser?.onResume()
                    tryRegisterPush()
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

    private fun isCurrentPublicMailDocument(): Boolean =
        publicWebView()?.url?.let { url ->
            try {
                val parsed = java.net.URI(url)
                AdminUrlPolicy.isTrustedMainFrame(url) &&
                    parsed.path == "/" && parsed.rawQuery.orEmpty().split("&").contains("orbit_app=android")
            } catch (_: Exception) { false }
        } ?: false

    private fun openSection(section: MailSection) {
        showTools = false
        if (section == MailSection.SETTINGS) {
            if (activeSection != MailSection.SETTINGS) previousWebSection = activeSection
            browser?.onPause()
            activeSection = MailSection.SETTINGS
            return
        }
        val old = browser
        val previousKey = BrowserSessionRouting.key(activeSection)
        val nextKey = BrowserSessionRouting.key(section)
        if (section == MailSection.INBOX || section == MailSection.MAILBOXES) {
            showNativeMail = true
            webLoginInProgress = false
            nativeSearchQuery = ""
        } else showNativeMail = false
        activeSection = section
        previousWebSection = section
        if (previousKey != nextKey) old?.onPause()
        // No tab-triggered loadUrl: every scope keeps its own live DOM/history.
        sectionBrowsers[nextKey]?.onResume()
    }

    private fun navigateTo(url: String) {
        if (!unlocked || !AdminUrlPolicy.isTrustedMainFrame(url)) return
        showTools = false
        webLoginInProgress = false
        if (BrowserSessionRouting.key(activeSection) != MailSection.ADMIN) browser?.onPause()
        activeSection = MailSection.ADMIN
        previousWebSection = MailSection.ADMIN
        showNativeMail = false
        val admin = sectionBrowsers[MailSection.ADMIN]
        if (admin == null) pendingAdminUrl = url
        else {
            admin.onResume()
            if (admin.url != url) admin.loadUrl(url)
        }
    }

    /** Only a tap on the native UI can send a bounded action to the current site. */
    private fun sendNativeMailboxCommand(action: String, value: String? = null): Boolean {
        val web = publicWebView() ?: return false
        if (!unlocked || !isCurrentPublicMailDocument() ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return false
        if (action !in setOf("create_random","create_custom","select_mailbox",
                "select_domain","refresh","show_create")) return false
        val packet = org.json.JSONObject().put("v",1).put("action",action)
        when (action) {
            "create_custom" -> {
                if (value == null || !Regex("^[a-z0-9](?:[a-z0-9-]{0,29}[a-z0-9])?$")
                        .matches(value)) return false
                packet.put("local",value)
            }
            "select_mailbox" -> {
                if (value == null || !NativeInboxPolicy.isSafeId(value) ||
                    nativeInbox?.mailboxes?.none { it.id == value } != false) return false
                packet.put("id",value)
            }
            "select_domain" -> {
                if (value == null || value !in nativeInbox?.availableDomains.orEmpty()) return false
                packet.put("domain",value)
            }
        }
        WebViewCompat.postWebMessage(
            web, WebMessageCompat(packet.toString()), Uri.parse("https://inbox.kango99w.de")
        )
        return true
    }

    private fun setPushOptIn(value: Boolean) {
        backgroundPushOptIn = value
        getSharedPreferences("orbit_push",MODE_PRIVATE).edit().putBoolean("opt_in",value).apply()
    }

    private fun pushInstallationId(): String {
        val prefs = getSharedPreferences("orbit_push",MODE_PRIVATE)
        val current = prefs.getString("installation_id",null)
        if (current != null && Regex("^[A-Za-z0-9_-]{16,72}$").matches(current)) return current
        val generated = java.util.UUID.randomUUID().toString().replace("-","")
        prefs.edit().putString("installation_id",generated).apply()
        return generated
    }

    private fun sendPushAction(action: String, token: String? = null): Boolean {
        val web = publicWebView() ?: return false
        if (!unlocked || !isCurrentPublicMailDocument() ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return false
        if (action !in setOf("push_register","push_revoke")) return false
        val packet = org.json.JSONObject().put("v",1).put("action",action)
            .put("installation_id",pushInstallationId())
        if (action == "push_register") {
            if (token == null || token.length !in 40..4096 ||
                !Regex("^[A-Za-z0-9:_~.\\-]{40,4096}$").matches(token)) return false
            packet.put("token",token)
        }
        WebViewCompat.postWebMessage(
            web,WebMessageCompat(packet.toString()),Uri.parse("https://inbox.kango99w.de")
        )
        return true
    }

    private fun tryRegisterPush() {
        if (!backgroundPushOptIn || !OrbitPushConfig.configured ||
            FirebaseApp.getApps(this).isEmpty() || !unlocked || !isCurrentPublicMailDocument()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val stamp = SystemClock.elapsedRealtime()
        if (lastPushAttemptAtMs != 0L && stamp-lastPushAttemptAtMs < 15*60*1000L) return
        lastPushAttemptAtMs = stamp
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!backgroundPushOptIn || !unlocked) return@addOnSuccessListener
                backgroundPushStatus = if (sendPushAction("push_register",token))
                    "设备登记已提交，等待服务器确认" else "需在已登录的普通收件箱登记设备"
            }
            .addOnFailureListener {
                backgroundPushStatus = "设备令牌暂不可用；检查 Google Play 服务与网络。"
            }
    }

    private fun requestBackgroundPush() {
        if (!OrbitPushConfig.configured || FirebaseApp.getApps(this).isEmpty()) {
            backgroundPushStatus = "Firebase 项目未配置，暂不能启用后台通知。"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setPushOptIn(true)
            tryRegisterPush()
        }
    }

    private fun revokeBackgroundPush() {
        if (!sendPushAction("push_revoke")) {
            backgroundPushStatus = "请先进入已登录的普通收件箱，再撤销设备登记。"
            return
        }
        backgroundPushStatus = "已请求服务器撤销，等待确认"
    }

    private fun copyMailboxAddress(address: String) {
        if (!unlocked || address.isBlank() || address.length > 254) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("邮箱地址",address))
        message = "邮箱地址已复制。"
    }

    @Composable
    private fun NativeCreateMailboxDialog(snapshot: NativeInboxSnapshot) {
        val domains = snapshot.availableDomains
        val chosen = nativeCreateDomain.takeIf { it in domains } ?: snapshot.selectedDomain
        val validPrefix = Regex("^[a-z0-9](?:[a-z0-9-]{0,29}[a-z0-9])?$")
            .matches(nativeCreateLocal.trim().lowercase())
        var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCreateMailbox = false },
            title = { Text("创建邮箱") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("邮箱归属、配额和安全验证仍由网站服务器检查。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("邮箱域名：@${chosen.ifEmpty { "请选择" }}") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        domains.forEach { option ->
                            DropdownMenuItem(text = { Text("@$option") }, onClick = {
                                expanded = false
                                nativeCreateDomain = option
                                if (!sendNativeMailboxCommand("select_domain",option)) {
                                    message = "网页尚未准备好，请使用完整网页创建。"
                                }
                            })
                        }
                    }
                    OutlinedTextField(
                        value = nativeCreateLocal,
                        onValueChange = { nativeCreateLocal = it.take(32) },
                        label = { Text("自定义前缀（留空随机创建）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (snapshot.statusText.isNotBlank()) {
                        Text(snapshot.statusText,style = MaterialTheme.typography.bodySmall)
                    }
                    if (!snapshot.canCreate) {
                        Text("请到网页完成验证码、容量检查或受保护邮箱设置。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = snapshot.canCreate && !snapshot.busy &&
                        chosen.isNotEmpty() && chosen == snapshot.selectedDomain &&
                        (nativeCreateLocal.isBlank() || validPrefix),
                    onClick = {
                        val prefix = nativeCreateLocal.trim().lowercase()
                        val success = sendNativeMailboxCommand(
                            if (prefix.isEmpty()) "create_random" else "create_custom",
                            prefix.takeIf { it.isNotEmpty() }
                        )
                        if (success) {
                            showCreateMailbox = false
                            nativeCreateLocal = ""
                            message = "请求已提交；只有邮箱列表更新才代表创建成功。"
                        } else message = "当前网页未准备好，请使用完整网页。"
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateMailbox = false }) { Text("取消") }
            }
        )
    }

    /** Native list items never perform deletes, sends or privileged API calls. */
    private fun openNativeMessage(id: String) {
        if (!NativeInboxPolicy.isSafeId(id)) return
        showNativeMail = false
        val base = MailSection.INBOX.address?.substringBefore("#") ?: return
        val url = "${base}#orbit-message-${id}"
        if (unlocked && AdminUrlPolicy.isTrustedMainFrame(url)) publicWebView()?.loadUrl(url)
    }

    private fun openNativeMailbox(id: String) {
        if (!NativeInboxPolicy.isSafeId(id)) return
        showNativeMail = false
        val base = MailSection.MAILBOXES.address?.substringBefore("#") ?: return
        val url = "${base}#orbit-mailbox-${id}"
        if (unlocked && AdminUrlPolicy.isTrustedMainFrame(url)) publicWebView()?.loadUrl(url)
    }

    @Composable
    private fun NativeInboxContent(snapshot: NativeInboxSnapshot, showMailboxes: Boolean) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showMailboxes) "我的邮箱 · ${snapshot.mailboxes.size}" else "收件箱 · ${snapshot.messages.size}",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showMailboxes) {
                        TextButton(onClick = {
                            nativeCreateDomain = snapshot.selectedDomain
                            nativeCreateLocal = ""
                            showCreateMailbox = true
                        }) { Text("＋新建") }
                    }
                    TextButton(onClick = { sendNativeMailboxCommand("refresh") }) { Text("刷新") }
                    TextButton(onClick = { webLoginInProgress = false; showNativeMail = false }) {
                        Text("网页")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(13.dp)) {
                    Text(snapshot.currentAddress, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (snapshot.locked) "受保护邮箱：请在网页中完成二次解锁"
                        else "只读原生列表 · 内容来自当前已认证的网站",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            OutlinedTextField(
                value = nativeSearchQuery,
                onValueChange = { nativeSearchQuery = it.take(80) },
                label = { Text(if (showMailboxes) "搜索当前邮箱列表" else "搜索当前加载的邮件") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val query = nativeSearchQuery.trim()
            val visibleMailboxes = if (query.isEmpty()) snapshot.mailboxes else
                snapshot.mailboxes.filter { it.address.contains(query, ignoreCase = true) }
            val visibleMessages = if (query.isEmpty()) snapshot.messages else
                snapshot.messages.filter {
                    it.sender.contains(query, ignoreCase = true) ||
                        it.subject.contains(query, ignoreCase = true)
                }
            if (showMailboxes) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleMailboxes, key = { it.id }) { mailbox ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mailbox.address, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (mailbox.id == snapshot.currentId) "当前使用"
                                        else if (mailbox.isProtected) "受保护邮箱" else "已保存邮箱",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Column {
                                    TextButton(onClick = {
                                        if (sendNativeMailboxCommand("select_mailbox",mailbox.id))
                                            openSection(MailSection.INBOX)
                                    }) { Text("收件箱") }
                                    TextButton(onClick = { copyMailboxAddress(mailbox.address) }) { Text("复制") }
                                    TextButton(onClick = { openNativeMailbox(mailbox.id) }) { Text("管理") }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = {
                            nativeCreateDomain = snapshot.selectedDomain
                            nativeCreateLocal = ""
                            showCreateMailbox = true
                        }, modifier = Modifier.fillMaxWidth()) { Text("＋ 原生创建邮箱") }
                        TextButton(onClick = {
                            webLoginInProgress = false
                            showNativeMail = false
                            publicWebView()?.loadUrl("https://inbox.kango99w.de/?orbit_app=android#orbit-create")
                        }) { Text("受保护邮箱 / 高级设置") }
                    }
                }
            } else if (snapshot.locked) {
                Button(onClick = { showNativeMail = false }) { Text("解锁受保护邮箱") }
            } else if (snapshot.messages.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂无已加载邮件。应用在网页成功读取后更新原生列表。")
                    Row {
                        TextButton(onClick = {
                            nativeCreateDomain = snapshot.selectedDomain
                            nativeCreateLocal = ""
                            showCreateMailbox = true
                        }) { Text("创建邮箱") }
                        OutlinedButton(onClick = { showNativeMail = false }) { Text("完整网页") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(visibleMessages, key = { it.id }) { messageItem ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(messageItem.sender.ifBlank { "未知发件人" },
                                    style = MaterialTheme.typography.titleSmall)
                                Text(messageItem.subject.ifBlank { "（无主题）" },
                                    style = MaterialTheme.typography.bodyMedium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(messageItem.receivedAt.take(19),
                                        style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { openNativeMessage(messageItem.id) }) {
                                        Text("阅读")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeMailboxLanding(showMailboxes: Boolean) {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                painterResource(if (showMailboxes) R.drawable.ic_folder else R.drawable.ic_mail),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(if (showMailboxes) "我的邮箱" else "收件箱",
                style = MaterialTheme.typography.headlineSmall)
            Text("使用已绑定的账号管理邮件。登录后这里会显示原生邮箱列表，"
                + "无需在收件箱与邮箱之间重复登录。",
                style = MaterialTheme.typography.bodyMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("尚未取得当前邮箱数据", style = MaterialTheme.typography.titleMedium)
                    Text("需要先在安全的官网登录页面完成账号和 2FA 验证；"
                        + "若已经登录，可刷新页面重新同步。",
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            webLoginInProgress = true
                            showNativeMail = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("登录 / 同步现有邮箱") }
                    OutlinedButton(
                        onClick = {
                            val home = MailSection.INBOX.address ?: return@OutlinedButton
                            val public = publicWebView()
                            if (public?.url != home) public?.loadUrl(home) else public.reload()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新检查邮箱") }
                }
            }
            Text("管理员管理会话与普通邮箱账号会话独立；登录一个不代表自动获得另一个的权限。",
                style = MaterialTheme.typography.labelSmall)
        }
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("后台邮件推送（可选）",style = MaterialTheme.typography.titleMedium)
                    Text("仅限绑定登录账号的邮箱，需 Firebase、服务器和通知权限。",
                        style = MaterialTheme.typography.bodySmall)
                    Text(backgroundPushStatus,style = MaterialTheme.typography.bodySmall)
                    if (backgroundPushOptIn) {
                        OutlinedButton(onClick = ::revokeBackgroundPush) { Text("撤销本机推送授权") }
                    } else {
                        Button(onClick = ::requestBackgroundPush,enabled = OrbitPushConfig.configured) {
                            Text(if (OrbitPushConfig.configured) "开启后台邮件提醒" else "Firebase 尚未配置")
                        }
                    }
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
                (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES) && showNativeMail -> {
                    if (activeSection == MailSection.MAILBOXES) openSection(MailSection.INBOX)
                    else lock()
                }
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
        if (showCreateMailbox && nativeInbox != null) NativeCreateMailboxDialog(nativeInbox!!)
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
                        if (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES) {
                            TextButton(onClick = { showNativeMail = !showNativeMail; webLoginInProgress = false }) {
                                Text(if (showNativeMail) "网页" else "原生")
                            }
                        }
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
            Column(Modifier.fillMaxSize().padding(inner)) {
                val snapshot = nativeInbox
                val publicSelected = activeSection == MailSection.INBOX ||
                    activeSection == MailSection.MAILBOXES
                val nativeVisible = publicSelected && showNativeMail
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Each WebView is mounted only after the section's first visit,
                    // then remains attached and retains its DOM/session across tabs.
                    AndroidView(
                        factory = { obtainBrowser(MailSection.INBOX) },
                        update = {
                            it.visibility = if (publicSelected) View.VISIBLE else View.INVISIBLE
                        },
                        modifier = Modifier.fillMaxSize().alpha(
                            if (publicSelected && !nativeVisible) 1f else 0f
                        )
                    )
                    if (activeSection == MailSection.TRANSFER ||
                        sectionBrowsers.containsKey(MailSection.TRANSFER)) {
                        AndroidView(
                            factory = { obtainBrowser(MailSection.TRANSFER) },
                            update = {
                                it.visibility = if (activeSection == MailSection.TRANSFER)
                                    View.VISIBLE else View.INVISIBLE
                            },
                            modifier = Modifier.fillMaxSize().alpha(
                                if (activeSection == MailSection.TRANSFER) 1f else 0f
                            )
                        )
                    }
                    if (activeSection == MailSection.ADMIN ||
                        sectionBrowsers.containsKey(MailSection.ADMIN)) {
                        AndroidView(
                            factory = { obtainBrowser(MailSection.ADMIN) },
                            update = {
                                it.visibility = if (activeSection == MailSection.ADMIN)
                                    View.VISIBLE else View.INVISIBLE
                            },
                            modifier = Modifier.fillMaxSize().alpha(
                                if (activeSection == MailSection.ADMIN) 1f else 0f
                            )
                        )
                    }
                    if (nativeVisible) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            if (snapshot != null) {
                                NativeInboxContent(snapshot, activeSection == MailSection.MAILBOXES)
                            } else {
                                NativeMailboxLanding(activeSection == MailSection.MAILBOXES)
                            }
                        }
                    }
                    if (activeSection == MailSection.SETTINGS) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            SettingsContent(Modifier.fillMaxSize())
                        }
                    }
                }
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

    private fun clearLocalSession() {
        showClearConfirm = false
        nativeInbox = null
        sectionBrowsers.values.forEach { it.stopLoading() }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            runOnUiThread {
                sectionBrowsers.forEach { (scope, web) ->
                    web.clearHistory()
                    web.loadUrl(scope.address ?: MailSection.INBOX.address!!)
                }
                nativeInbox = null
                message = "仅 APK 本机数据已清除。重新登录需要服务器授权和 2FA。"
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
    private fun obtainBrowser(section: MailSection): WebView {
        val key = BrowserSessionRouting.key(section)
        sectionBrowsers[key]?.let { existing ->
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
        if (key == MailSection.INBOX) {
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,"orbitMobilePushStatus",setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender, data, origin, mainFrame, _ ->
                    if (data.type != WebMessageCompat.TYPE_STRING ||
                        !NativeInboxPolicy.acceptsSource(
                            origin.scheme,origin.host,origin.port,mainFrame,sender.url
                        ) || data.data !in setOf("registered","revoked","disabled","error"))
                        return@WebMessageListener
                    runOnUiThread {
                        when (data.data) {
                            "registered" -> backgroundPushStatus = "服务器已登记本机后台提醒"
                            "revoked" -> {
                                setPushOptIn(false)
                                backgroundPushStatus = "服务器已撤销本机设备授权"
                            }
                            "disabled" -> backgroundPushStatus = "服务器 FCM 推送尚未上线"
                            "error" -> backgroundPushStatus = "服务器拒绝登记；检查账号与配置"
                        }
                    }
                }
            )
        }
        // Separate read-only mirror: main-frame, exact origin, exact first-party
        // public mailbox route. No cookie/token exposure or native command handler.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view, "orbitMailSnapshot", setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender, data, origin, mainFrame, _ ->
                    if (data.type != WebMessageCompat.TYPE_STRING ||
                        !NativeInboxPolicy.acceptsSource(
                            origin.scheme, origin.host, origin.port, mainFrame, sender.url
                        )
                    ) return@WebMessageListener
                    val event = NativeInboxPolicy.parse(data.data) ?: return@WebMessageListener
                    runOnUiThread {
                        if (!unlocked) return@runOnUiThread
                        when (event) {
                            NativeInboxEvent.Clear -> nativeInbox = null
                            is NativeInboxEvent.Snapshot -> {
                                nativeInbox = event.data
                                tryRegisterPush()
                                if (webLoginInProgress &&
                                    (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES)) {
                                    webLoginInProgress = false
                                    showNativeMail = true
                                }
                            }
                        }
                    }
                }
            )
        }
        } // First-party inbox/notification bridge belongs only to public mail.
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
                // Do not display a stale native inbox during any new full-page navigation.
                if (key == MailSection.INBOX) nativeInbox = null
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
                if (request.isForMainFrame) {
                    if (key == MailSection.INBOX) nativeInbox = null
                    message = "页面加载失败，请检查网络后重试。"
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                deferredFileUris = null
                pickerPending = false
                pickerLaunchedAtMs = 0
                sectionBrowsers.remove(key)
                if (key == MailSection.INBOX) nativeInbox = null
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
        sectionBrowsers[key] = view
        val initial = if (key == MailSection.ADMIN)
            (pendingAdminUrl ?: MailSection.ADMIN.address!!).also { pendingAdminUrl = null }
        else key.address ?: MailSection.INBOX.address!!
        view.loadUrl(initial)
        if (BrowserSessionRouting.key(activeSection) != key) view.onPause()
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
            tryRegisterPush()
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
        nativeInbox = null
        pickerPending = false
        pickerLaunchedAtMs = 0
        sectionBrowsers.values.toList().forEach { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        sectionBrowsers.clear()
        super.onDestroy()
    }
}
