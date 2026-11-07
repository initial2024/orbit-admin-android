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
import org.json.JSONObject
import java.util.concurrent.Executors
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.selection.SelectionContainer
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
    private val nativeDeviceClient by lazy { NativeDeviceClient(applicationContext) }
    private val nativeIo = Executors.newSingleThreadExecutor()
    private var nativeApiActive by mutableStateOf(false)
    private var nativeApiLoading by mutableStateOf(false)
    private var nativeApiNotice by mutableStateOf("")
    private var showNativePairDialog by mutableStateOf(false)
    private var nativePairLabel by mutableStateOf("我的 Android 手机")
    private var nativePairPassword by mutableStateOf("")
    private var nativePairTotp by mutableStateOf("")
    private var pendingPairNonce: String? = null
    private var showNativeMail by mutableStateOf(true)
    // All regular tabs are Compose surfaces. Website views are explicit fallback only.
    private var showNativeToolsPanel by mutableStateOf(true)
    private var adminSearchQuery by mutableStateOf("")
    private var webLoginInProgress by mutableStateOf(false)
    private var nativeSearchQuery by mutableStateOf("")
    private var pendingNativeMessageId by mutableStateOf<String?>(null)
    private var nativeMailDetail by mutableStateOf<NativeMailDetail?>(null)
    private var nativeReadCommandUnavailable by mutableStateOf(false)
    private var showCreateMailbox by mutableStateOf(false)
    private var nativeCreateLocal by mutableStateOf("")
    private var nativeCreateDomain by mutableStateOf("")
    private var activeSection by mutableStateOf(MailSection.INBOX)
    private var previousWebSection = MailSection.INBOX
    private val sectionBrowsers = mutableMapOf<MailSection,WebView>()
    private val browser: WebView? get() = sectionBrowsers[BrowserSessionRouting.key(activeSection)]
    private fun publicWebView(): WebView? = sectionBrowsers[MailSection.INBOX]
    private var pendingAdminUrl: String? = null
    private var pendingTransferUrl: String? = null
    private var pendingPublicUrl: String? = null
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
    private var lastNativeSnapshotRequestAtMs = 0L

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
                    if (nativeDeviceClient.isBound()) refreshNativeApi()
                    else requestMissingNativeSnapshot()
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
            Text("Orbit Mail", style = MaterialTheme.typography.headlineLarge)
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
        when (section) {
            MailSection.INBOX, MailSection.MAILBOXES -> {
                showNativeMail = true
                webLoginInProgress = false
                nativeSearchQuery = ""
            }
            MailSection.ADMIN, MailSection.TRANSFER -> showNativeToolsPanel = true
            MailSection.SETTINGS -> Unit
        }
        activeSection = section
        previousWebSection = section
        if (previousKey != nextKey || section == MailSection.MAILBOXES) {
            pendingNativeMessageId = null
            nativeMailDetail = null
            nativeReadCommandUnavailable = false
        }
        if (previousKey != nextKey) old?.onPause()
        // Native tabs never loadUrl; any website state remains in its private scope.
        sectionBrowsers[nextKey]?.onResume()
        if (section == MailSection.INBOX || section == MailSection.MAILBOXES) {
            if (nativeDeviceClient.isBound()) refreshNativeApi()
            else requestMissingNativeSnapshot()
        }
    }

    /** Only a deliberate "website" action opens first-party HTML. */
    private fun navigateTo(url: String) {
        if (!unlocked) return
        val target = NativeSectionRouting.explicitWebsiteTarget(url) ?: return
        val oldKey = BrowserSessionRouting.key(activeSection)
        val nextKey = BrowserSessionRouting.key(target)
        if (oldKey != nextKey) browser?.onPause()
        activeSection = target
        previousWebSection = target
        showTools = false
        pendingNativeMessageId = null
        nativeMailDetail = null
        nativeReadCommandUnavailable = false
        if (target == MailSection.INBOX) {
            showNativeMail = false
            webLoginInProgress = true
        } else {
            showNativeToolsPanel = false
            webLoginInProgress = false
        }
        val existing = sectionBrowsers[nextKey]
        if (existing == null) {
            when (target) {
                MailSection.INBOX -> pendingPublicUrl = url
                MailSection.TRANSFER -> pendingTransferUrl = url
                MailSection.ADMIN -> pendingAdminUrl = url
                else -> Unit
            }
        } else {
            existing.onResume()
            if (existing.url != url) existing.loadUrl(url)
        }
    }

    /** Recover a lost Compose snapshot without reloading the website or launching login. */
    private fun requestMissingNativeSnapshot() {
        if (!unlocked || nativeInbox != null || !isCurrentPublicMailDocument()) return
        val stamp = SystemClock.elapsedRealtime()
        if (lastNativeSnapshotRequestAtMs != 0L &&
            stamp - lastNativeSnapshotRequestAtMs < 15_000L) return
        if (sendNativeMailboxCommand("refresh")) lastNativeSnapshotRequestAtMs = stamp
    }

    /** Signed, cookie-free native API reads. Only account-owned and unlocked data is displayed. */
    private fun refreshNativeApi() {
        if (!unlocked || !nativeDeviceClient.isBound() || nativeApiLoading) return
        nativeApiLoading = true
        nativeIo.execute {
            val mailboxes = nativeDeviceClient.fetch("/api/account/native-device/mailboxes")
            if (mailboxes.status != 200 || mailboxes.value == null) {
                runOnUiThread {
                    nativeApiLoading = false
                    if (unlocked) {
                        nativeApiNotice = when (mailboxes.status) {
                            401 -> "设备授权失效或被撤销，需要重新绑定。"
                            503 -> "服务器原生接口尚未启用，现有数据未被更改。"
                            else -> "原生邮箱同步失败：HTTP ${mailboxes.status}"
                        }
                        if (mailboxes.status == 401) {
                            nativeApiActive = false
                            nativeInbox = null
                        }
                    }
                }
                return@execute
            }
            val data = mailboxes.value.optJSONArray("mailboxes")
            val rows = mutableListOf<NativeMailboxRow>()
            if (data != null) {
                for (i in 0 until minOf(data.length(), 25)) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (!NativeInboxPolicy.isSafeId(id)) continue
                    rows.add(NativeMailboxRow(id, item.optString("address").take(254),
                        item.optBoolean("protected", false)))
                }
            }
            val prefs = getSharedPreferences("orbit_native_device_v1", Context.MODE_PRIVATE)
            val selected = rows.firstOrNull { it.id == prefs.getString("selected_mailbox_id", "") }
                ?: rows.firstOrNull()
            val locked = selected?.isProtected == true
            val messageResult = if (selected != null && !locked)
                nativeDeviceClient.fetch("/api/account/native-device/mailboxes/${selected.id}/messages")
                else null
            val mail = mutableListOf<NativeMailRow>()
            val messages = messageResult?.value?.optJSONArray("messages")
            if (messageResult?.status == 200 && messages != null) {
                for (i in 0 until minOf(messages.length(), 30)) {
                    val item = messages.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (!NativeInboxPolicy.isSafeId(id)) continue
                    mail.add(NativeMailRow(id, item.optString("sender").take(160),
                        item.optString("subject").take(160),
                        item.optString("received_at").take(48)))
                }
            }
            val snapshot = NativeInboxSnapshot(
                currentId = selected?.id.orEmpty(),
                currentAddress = selected?.address.orEmpty(),
                locked = locked,
                selectedDomain = "",
                availableDomains = emptyList(),
                canCreate = false,
                nativeReadSupported = true,
                busy = false,
                statusText = "使用 Android 设备授权 API（账号自有邮箱）",
                mailboxes = rows,
                messages = mail,
            )
            runOnUiThread {
                nativeApiLoading = false
                if (!unlocked || !nativeDeviceClient.isBound()) return@runOnUiThread
                nativeApiActive = true
                nativeApiNotice = if (selected != null && !locked && messageResult?.status != 200)
                    "邮件列表暂不可用；请刷新重试。" else ""
                if (nativeInbox?.currentId != snapshot.currentId) {
                    pendingNativeMessageId = null
                    nativeMailDetail = null
                }
                nativeInbox = snapshot
                showNativeMail = true
            }
        }
    }

    private fun readMessageViaNativeApi(id: String) {
        val mailbox = nativeInbox?.currentId ?: return
        if (!unlocked || !nativeApiActive || !NativeInboxPolicy.isSafeId(mailbox) ||
            !NativeInboxPolicy.isSafeId(id)) return
        pendingNativeMessageId = id
        nativeMailDetail = null
        nativeReadCommandUnavailable = false
        nativeIo.execute {
            val result = nativeDeviceClient.fetch("/api/account/native-device/mailboxes/$mailbox/messages/$id")
            runOnUiThread {
                if (!unlocked || pendingNativeMessageId != id ||
                    nativeInbox?.currentId != mailbox) return@runOnUiThread
                val item = result.value
                if (result.status != 200 || item == null ||
                    item.optString("id") != id || item.optString("mailbox_id") != mailbox) {
                    nativeReadCommandUnavailable = true
                    nativeApiNotice = "该邮件暂不可通过原生接口读取（HTTP ${result.status}）。"
                    return@runOnUiThread
                }
                nativeMailDetail = NativeMailDetail(mailbox, id,
                    item.optString("sender").take(160),
                    item.optString("recipient").take(254),
                    item.optString("subject").take(240),
                    item.optString("received_at").take(48),
                    item.optString("text_body").take(8000))
                showNativeMail = true
            }
        }
    }

    /** Explicit user action only: website performs session-bound password/TOTP verification. */
    private fun submitNativePair() {
        val web = publicWebView()
        if (!unlocked || web == null || !isCurrentPublicMailDocument() ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            message = "请先在普通账号登录网站完成身份验证，再返回绑定设备。"
            return
        }
        try {
            val packet = nativeDeviceClient.createPairPayload(
                nativePairLabel.trim(), nativePairPassword, nativePairTotp)
            pendingPairNonce = packet.getString("nonce")
            WebViewCompat.postWebMessage(web, WebMessageCompat(packet.toString()),
                Uri.parse("https://inbox.kango99w.de"))
            showNativePairDialog = false
            message = "设备绑定请求已发送，等待服务器验证。"
        } catch (_: Exception) {
            message = "设备密钥创建或绑定请求失败。"
        } finally {
            nativePairPassword = ""
            nativePairTotp = ""
        }
    }

    /** Only a tap on the native UI can send a bounded action to the current site. */
    private fun sendNativeMailboxCommand(action: String, value: String? = null): Boolean {
        val web = publicWebView() ?: return false
        if (!unlocked || !isCurrentPublicMailDocument() ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return false
        if (action !in setOf("create_random","create_custom","select_mailbox",
                "select_domain","refresh","show_create","read_message")) return false
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
            "read_message" -> {
                if (value == null || !NativeInboxPolicy.isSafeId(value) ||
                    nativeInbox?.locked == true ||
                    nativeInbox?.messages?.none { it.id == value } != false) return false
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

    /** Stay in Compose, even when the server-side native reader is not yet deployed. */
    private fun openNativeMessage(id: String) {
        if (!unlocked || !NativeInboxPolicy.isSafeId(id) || nativeInbox?.locked == true ||
            nativeInbox?.messages?.none { it.id == id } != false) return
        pendingNativeMessageId = id
        nativeMailDetail = null
        showNativeMail = true
        if (nativeApiActive) {
            readMessageViaNativeApi(id)
            return
        }
        nativeReadCommandUnavailable = nativeInbox?.nativeReadSupported != true ||
            !sendNativeMailboxCommand("read_message",id)
        if (nativeReadCommandUnavailable) {
            message = "原生阅读接口尚不可用；不会自动跳转网页登录。可手动选择完整网页。"
        }
    }

    /** Reuse the authorized mailbox-selection command. Never force a WebView navigation. */
    private fun openNativeMailbox(id: String) {
        if (!unlocked || !NativeInboxPolicy.isSafeId(id)) return
        if (nativeApiActive) {
            val item = nativeInbox?.mailboxes?.firstOrNull { it.id == id } ?: return
            getSharedPreferences("orbit_native_device_v1", Context.MODE_PRIVATE).edit()
                .putString("selected_mailbox_id", id).apply()
            nativeInbox = nativeInbox?.copy(currentId = id, currentAddress = item.address,
                locked = item.isProtected, messages = emptyList())
            pendingNativeMessageId = null
            nativeMailDetail = null
            showNativeMail = true
            openSection(MailSection.INBOX)
            refreshNativeApi()
            return
        }
        if (sendNativeMailboxCommand("select_mailbox",id)) {
            pendingNativeMessageId = null
            nativeMailDetail = null
            nativeReadCommandUnavailable = false
            showNativeMail = true
            openSection(MailSection.INBOX)
        } else {
            message = "无法在当前会话切换邮箱；请在登录状态有效后重试。"
        }
    }

    @Composable
    private fun NativeMailDetailContent(detail: NativeMailDetail) {
        Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=18.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(11.dp)) {
            Row(modifier=Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={
                    pendingNativeMessageId=null
                    nativeMailDetail=null
                }) { Text("‹ 返回收件箱") }
                TextButton(onClick={showNativeMail=false}) { Text("完整网页") }
            }
            Text(detail.subject.ifBlank{"（无主题）"},
                style=MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment=Alignment.CenterVertically) {
                MailAvatar(detail.sender,42)
                Spacer(Modifier.padding(horizontal=7.dp))
                Column {
                    Text(detail.sender.ifBlank{"未知发件人"},style=MaterialTheme.typography.titleSmall)
                    Text("收件人：${detail.recipient}",style=MaterialTheme.typography.bodySmall)
                    Text(detail.receivedAt.take(28),style=MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider()
            if (detail.textBody.isBlank()) {
                Text("这封邮件没有纯文本正文；请用完整网页查看 HTML 内容或附件。",
                    style=MaterialTheme.typography.bodyMedium)
                Button(onClick={showNativeMail=false}) { Text("查看原邮件及附件") }
            } else {
                SelectionContainer {
                    Text(detail.textBody,style=MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(onClick={showNativeMail=false}) {
                    Text("查看 HTML 原文及附件")
                }
            }
        }
    }

    @Composable
    private fun NativeMailLoadingContent() {
        Column(modifier=Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            TextButton(onClick={
                pendingNativeMessageId=null
                nativeMailDetail=null
            }) { Text("‹ 返回收件箱") }
            Text(if (nativeReadCommandUnavailable) "原生邮件阅读暂不可用"
                else "正在从已授权的邮箱读取正文…",
                style=MaterialTheme.typography.titleMedium)
            Text(if (nativeReadCommandUnavailable)
                "当前网站尚未开放原生正文接口，或原有会话尚未就绪。不会自动跳转登录页。"
                else "使用当前已授权会话获取正文，不会刷新邮箱页面。",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={showNativeMail=false}) { Text("完整网页阅读") }
        }
    }

    @Composable
    private fun MailAvatar(label: String, sizeDp: Int = 44) {
        val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "✉"
        Box(
            modifier = Modifier.size(sizeDp.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(initial,style=MaterialTheme.typography.titleMedium,
                color=MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    @Composable
    private fun NativeInboxContent(snapshot: NativeInboxSnapshot, showMailboxes: Boolean) {
        val query = nativeSearchQuery.trim()
        val mailboxes = if (query.isEmpty()) snapshot.mailboxes else
            snapshot.mailboxes.filter { it.address.contains(query,ignoreCase=true) }
        val mails = if (query.isEmpty()) snapshot.messages else
            snapshot.messages.filter { it.sender.contains(query,ignoreCase=true) ||
                it.subject.contains(query,ignoreCase=true) }
        Column(modifier=Modifier.fillMaxSize()) {
            // Standard inbox layout: active account, search, summary, and
            // full-width tappable rows rather than multiple web-like panels.
            Row(modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Column(modifier=Modifier.weight(1f)) {
                    Text(if (showMailboxes) "邮箱管理" else "收件箱",
                        style=MaterialTheme.typography.headlineSmall)
                    Text(snapshot.currentAddress.ifBlank { "尚未选择邮箱" },
                        maxLines=1,style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick={
                    if (nativeApiActive) refreshNativeApi()
                    else sendNativeMailboxCommand("refresh")
                }) {
                    Icon(painterResource(R.drawable.ic_refresh),contentDescription="刷新")
                    Text("刷新")
                }
                if (showMailboxes) TextButton(onClick={
                    nativeCreateDomain=snapshot.selectedDomain
                    nativeCreateLocal=""
                    showCreateMailbox=true
                }) { Text("＋ 新建") }
            }
            OutlinedTextField(
                value=nativeSearchQuery,
                onValueChange={nativeSearchQuery=it.take(80)},
                label={Text(if(showMailboxes) "搜索邮箱地址" else "搜索发件人或主题")},
                leadingIcon={Icon(painterResource(R.drawable.ic_mail),contentDescription=null)},
                singleLine=true,
                modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
            )
            Row(modifier=Modifier.fillMaxWidth().padding(start=18.dp,end=12.dp,top=7.dp,bottom=6.dp),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(
                    if (showMailboxes) "已保存邮箱 · ${mailboxes.size}"
                    else "当前邮箱 · ${mails.size} 封",
                    style=MaterialTheme.typography.labelLarge,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick={
                    if(showMailboxes) openSection(MailSection.INBOX)
                    else openSection(MailSection.MAILBOXES)
                }) { Text(if(showMailboxes) "返回收件箱" else "切换邮箱") }
            }
            HorizontalDivider()
            if (showMailboxes) {
                LazyColumn(modifier=Modifier.fillMaxSize()) {
                    items(mailboxes,key={it.id}) { mailbox ->
                        Row(modifier=Modifier.fillMaxWidth()
                            .clickable {
                                if (sendNativeMailboxCommand("select_mailbox",mailbox.id))
                                    openSection(MailSection.INBOX)
                            }.padding(horizontal=16.dp,vertical=11.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            MailAvatar(mailbox.address,44)
                            Spacer(Modifier.padding(horizontal=7.dp))
                            Column(modifier=Modifier.weight(1f)) {
                                Text(mailbox.address,maxLines=1,style=MaterialTheme.typography.titleSmall)
                                Text(when {
                                    mailbox.id==snapshot.currentId -> "当前邮箱"
                                    mailbox.isProtected -> "受保护邮箱"
                                    else -> "点击切换到此邮箱"
                                },style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick={copyMailboxAddress(mailbox.address)}) { Text("复制") }
                            TextButton(onClick={openNativeMailbox(mailbox.id)}) { Text("查看") }
                        }
                        HorizontalDivider()
                    }
                    item {
                        Column(modifier=Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={
                                nativeCreateDomain=snapshot.selectedDomain
                                nativeCreateLocal=""
                                showCreateMailbox=true
                            },modifier=Modifier.fillMaxWidth()) { Text("＋ 创建邮箱") }
                            TextButton(onClick={
                                showNativeMail=false
                                publicWebView()?.loadUrl("https://inbox.kango99w.de/?orbit_app=android#orbit-create")
                            }) { Text("受保护设置及高级管理（网页授权）") }
                        }
                    }
                }
            } else if (snapshot.locked) {
                Column(modifier=Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("此邮箱已启用额外保护",style=MaterialTheme.typography.titleMedium)
                    Text("需完成网站原有的邮箱解锁验证才能读取邮件。")
                    Button(onClick={showNativeMail=false}) { Text("验证并解锁邮箱") }
                }
            } else if (snapshot.messages.isEmpty()) {
                Column(modifier=Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Icon(painterResource(R.drawable.ic_mail),contentDescription=null,
                        tint=MaterialTheme.colorScheme.primary)
                    Text("暂无邮件",style=MaterialTheme.typography.titleLarge)
                    Text("邮件将显示在这里。可刷新或切换邮箱。",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick={
                        if (nativeApiActive) refreshNativeApi()
                        else sendNativeMailboxCommand("refresh")
                    }) {
                        Text("检查新邮件")
                    }
                }
            } else {
                LazyColumn(modifier=Modifier.fillMaxSize()) {
                    items(mails,key={it.id}) { mail ->
                        Row(modifier=Modifier.fillMaxWidth()
                            .clickable { openNativeMessage(mail.id) }
                            .padding(horizontal=16.dp,vertical=13.dp),
                            verticalAlignment=Alignment.Top) {
                            MailAvatar(mail.sender,45)
                            Spacer(Modifier.padding(horizontal=7.dp))
                            Column(modifier=Modifier.weight(1f),
                                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(modifier=Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.SpaceBetween) {
                                    Text(mail.sender.ifBlank{"未知发件人"},maxLines=1,
                                        style=MaterialTheme.typography.titleSmall,
                                        modifier=Modifier.weight(1f))
                                    Text(mail.receivedAt.take(16),
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(mail.subject.ifBlank{"（无主题）"},maxLines=2,
                                    style=MaterialTheme.typography.bodyMedium)
                                Text("点击阅读邮件",
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
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
                    Text(if (nativeDeviceClient.isBound()) "已绑定设备，等待原生接口"
                        else "尚未取得当前邮箱数据", style = MaterialTheme.typography.titleMedium)
                    if (nativeApiNotice.isNotBlank())
                        Text(nativeApiNotice, style = MaterialTheme.typography.bodySmall)
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
                    Text("普通页面优先使用 Android 原生界面；只有未接通原生接口的功能才需手动打开网站授权。", style = MaterialTheme.typography.bodySmall)
                    AdminDestination.entries.forEach { entry ->
                        val nativeSection = when (entry) {
                            AdminDestination.PUBLIC_MAIL -> MailSection.INBOX
                            AdminDestination.TRANSFER -> MailSection.TRANSFER
                            AdminDestination.OVERVIEW, AdminDestination.MAIL -> MailSection.ADMIN
                            else -> null
                        }
                        OutlinedButton(
                            onClick = {
                                if (nativeSection != null) openSection(nativeSection)
                                else navigateTo(entry.address)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(entry.title + if (nativeSection == null) " · 网站授权" else "") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTools = false }) { Text("关闭") }
            }
        )
    }

    @Composable
    private fun NativeTransferContent() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("文件", style = MaterialTheme.typography.headlineMedium)
            Text("原生文件中心", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_file), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Text("文件传输", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "文件选择和传输记录将统一放在这里。当前服务器尚未提供经过验收的原生上传接口，不能直接声称上传成功。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { navigateTo(AdminDestination.TRANSFER.address) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开现有文件传输（网站操作）") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("上传与下载", style = MaterialTheme.typography.titleMedium)
                    Text("原生 API 尚未接通。这里不会显示虚构的进度、文件或完成状态。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun NativeAdminContent() {
        val destinations = AdminDestination.entries.filter {
            it != AdminDestination.PUBLIC_MAIL && it != AdminDestination.TRANSFER
        }.filter {
            adminSearchQuery.isBlank() || it.title.contains(adminSearchQuery.trim(), ignoreCase = true)
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("管理中心", style = MaterialTheme.typography.headlineMedium)
            Text("Orbit Mail · 管理员工作台",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("管理员权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "管理员会话与普通邮箱登录分开验证。目前管理接口尚未全部原生化，操作时将明确进入受保护的网站页面。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { navigateTo(AdminDestination.OVERVIEW.address) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开管理员授权页面") }
                }
            }
            OutlinedTextField(
                value = adminSearchQuery,
                onValueChange = { adminSearchQuery = it.take(50) },
                label = { Text("搜索管理功能") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("功能入口", style = MaterialTheme.typography.titleMedium)
            destinations.forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { navigateTo(entry.address) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(
                                if (entry == AdminDestination.SECURITY) R.drawable.ic_shield
                                else R.drawable.ic_admin
                            ), contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 9.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text("网站授权操作",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Text("管理员 API 接入前，以上按钮只打开对应的真实功能，不会假装已执行管理操作。",
                style = MaterialTheme.typography.bodySmall)
        }
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
                    Text(if (nativeDeviceClient.isBound())
                        "本机已绑定原生 API · 有效期至 " +
                        java.text.DateFormat.getDateInstance().format(
                            java.util.Date(nativeDeviceClient.localExpiry()))
                        else "尚未绑定 Android 原生设备授权",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (publicWebView() == null || !isCurrentPublicMailDocument()) {
                            navigateTo(MailSection.INBOX.address!!)
                            message = "请先登录普通账号，再回到设置完成设备绑定。"
                        } else {
                            nativePairPassword = ""
                            nativePairTotp = ""
                            showNativePairDialog = true
                        }
                    }) { Text("绑定此设备 · 原生 API") }
                    if (nativeDeviceClient.isBound()) {
                        OutlinedButton(onClick = {
                            nativeDeviceClient.clearLocalBinding()
                            nativeApiActive = false
                            nativeInbox = null
                            message = "仅本机密钥已清除；须进入网站设备管理撤销服务器授权。"
                        }) { Text("清除本机设备凭据") }
                    }
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
    private fun NativePairDialog() {
        AlertDialog(
            onDismissRequest = {
                showNativePairDialog = false
                nativePairPassword = ""
                nativePairTotp = ""
            },
            title = { Text("绑定 Android 设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("先登录应用内的普通账号。开启 2FA 时输入验证码；未开启时输入当前账号密码。设备授权可由服务器撤销。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = nativePairLabel,
                        onValueChange = { nativePairLabel = it.take(60).replace("\n", "") },
                        label = { Text("设备名称") }, singleLine = true)
                    OutlinedTextField(value = nativePairTotp,
                        onValueChange = { nativePairTotp = it.take(24) },
                        label = { Text("2FA 验证码") }, singleLine = true)
                    OutlinedTextField(value = nativePairPassword,
                        onValueChange = { nativePairPassword = it.take(128) },
                        label = { Text("账号密码（无 2FA 时）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = nativePairLabel.isNotBlank() &&
                    (nativePairTotp.isNotBlank() || nativePairPassword.isNotBlank()),
                    onClick = ::submitNativePair) { Text("验证并绑定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNativePairDialog = false
                    nativePairPassword = ""
                    nativePairTotp = ""
                }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun AdminScreen() {
        BackHandler {
            when {
                showTools -> showTools = false
                activeSection == MailSection.SETTINGS -> openSection(previousWebSection)
                (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES) && showNativeMail -> {
                    if (pendingNativeMessageId != null || nativeMailDetail != null) {
                        pendingNativeMessageId=null
                        nativeMailDetail=null
                    } else if (activeSection == MailSection.MAILBOXES) openSection(MailSection.INBOX)
                    else lock()
                }
                (activeSection == MailSection.ADMIN || activeSection == MailSection.TRANSFER) &&
                    !showNativeToolsPanel -> showNativeToolsPanel = true
                (activeSection == MailSection.ADMIN || activeSection == MailSection.TRANSFER) &&
                    showNativeToolsPanel -> openSection(MailSection.INBOX)
                (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES) &&
                    !showNativeMail -> {
                        showNativeMail = true
                        webLoginInProgress = false
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
        if (showNativePairDialog) NativePairDialog()
        if (showCreateMailbox && nativeInbox != null) NativeCreateMailboxDialog(nativeInbox!!)
        Scaffold(
            topBar = {
                val nativeMailTab = (activeSection == MailSection.INBOX ||
                    activeSection == MailSection.MAILBOXES) && showNativeMail
                val nativeToolsTab = (activeSection == MailSection.TRANSFER ||
                    activeSection == MailSection.ADMIN) && showNativeToolsPanel
                val nativeTab = nativeMailTab || nativeToolsTab || activeSection == MailSection.SETTINGS
                Surface(shadowElevation=1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Box(modifier=Modifier.size(35.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer,CircleShape),
                                contentAlignment=Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_mail),contentDescription=null,
                                    tint=MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.padding(horizontal=6.dp))
                            Column {
                                Text("Orbit Mail",style=MaterialTheme.typography.titleMedium)
                                Text(if(nativeTab) "原生客户端 · " + activeSection.label else "网站授权 · " + activeSection.label,
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            if (!nativeTab && activeSection != MailSection.SETTINGS) {
                                TextButton(onClick={browser?.reload()}) {
                                    Icon(painterResource(R.drawable.ic_refresh),
                                        contentDescription="重新加载当前页面")
                                }
                            }
                            TextButton(onClick={showTools=true}) {
                                Icon(painterResource(R.drawable.ic_more),
                                    contentDescription="更多邮箱工具")
                            }
                            TextButton(onClick=::lock) { Text("锁定") }
                        }
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
                val transferWebVisible = activeSection == MailSection.TRANSFER && !showNativeToolsPanel
                val adminWebVisible = activeSection == MailSection.ADMIN && !showNativeToolsPanel
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Each WebView is mounted only after the section's first visit,
                    // then remains attached and retains its DOM/session across tabs.
                    if (!nativeDeviceClient.isBound() || !nativeVisible ||
                        sectionBrowsers.containsKey(MailSection.INBOX)) AndroidView(
                        factory = { obtainBrowser(MailSection.INBOX) },
                        update = {
                            it.visibility = if (publicSelected && !nativeVisible) View.VISIBLE else View.INVISIBLE
                        },
                        modifier = Modifier.fillMaxSize().alpha(
                            if (publicSelected && !nativeVisible) 1f else 0f
                        )
                    )
                    if (transferWebVisible ||
                        sectionBrowsers.containsKey(MailSection.TRANSFER)) {
                        AndroidView(
                            factory = { obtainBrowser(MailSection.TRANSFER) },
                            update = {
                                it.visibility = if (transferWebVisible) View.VISIBLE else View.INVISIBLE
                            },
                            modifier = Modifier.fillMaxSize().alpha(
                                if (transferWebVisible) 1f else 0f
                            )
                        )
                    }
                    if (adminWebVisible ||
                        sectionBrowsers.containsKey(MailSection.ADMIN)) {
                        AndroidView(
                            factory = { obtainBrowser(MailSection.ADMIN) },
                            update = {
                                it.visibility = if (adminWebVisible) View.VISIBLE else View.INVISIBLE
                            },
                            modifier = Modifier.fillMaxSize().alpha(
                                if (adminWebVisible) 1f else 0f
                            )
                        )
                    }
                    if (nativeVisible) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            if (nativeMailDetail != null &&
                                pendingNativeMessageId == nativeMailDetail?.id) {
                                NativeMailDetailContent(nativeMailDetail!!)
                            } else if (pendingNativeMessageId != null) {
                                NativeMailLoadingContent()
                            } else if (snapshot != null) {
                                NativeInboxContent(snapshot, activeSection == MailSection.MAILBOXES)
                            } else {
                                NativeMailboxLanding(activeSection == MailSection.MAILBOXES)
                            }
                        }
                    }
                    if (activeSection == MailSection.TRANSFER && showNativeToolsPanel) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            NativeTransferContent()
                        }
                    }
                    if (activeSection == MailSection.ADMIN && showNativeToolsPanel) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            NativeAdminContent()
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
        nativeDeviceClient.clearLocalBinding()
        nativeApiActive = false
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
        showNativePairDialog = false
        pendingPairNonce = null
        nativePairPassword = ""
        nativePairTotp = ""
        nativeApiActive = false
        nativeInbox = null
        nativeMailDetail=null
        pendingNativeMessageId=null
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
        CookieManager.getInstance().setAcceptCookie(true)
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
        // Pair response is accepted only from the exact first-party main frame
        // and for the outstanding user-initiated nonce. No cookie or password returned.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view, "orbitNativeDeviceBinding", setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender,data,origin,mainFrame,_ ->
                    if (data.type != WebMessageCompat.TYPE_STRING ||
                        !NativeInboxPolicy.acceptsSource(
                            origin.scheme,origin.host,origin.port,mainFrame,sender.url
                        ) || data.data.length > 512) return@WebMessageListener
                    val event = try { JSONObject(data.data) } catch (_: Exception) { return@WebMessageListener }
                    if (event.optInt("v") != 1 ||
                        event.keys().asSequence().toSet() !=
                            setOf("v","kind","nonce","device_id","expires_at"))
                        return@WebMessageListener
                    runOnUiThread {
                        if (!unlocked || publicWebView() !== view ||
                            pendingPairNonce == null ||
                            event.optString("nonce") != pendingPairNonce) return@runOnUiThread
                        pendingPairNonce = null
                        when (event.optString("kind")) {
                            "paired" -> {
                                if (nativeDeviceClient.completePair(
                                        event.optString("device_id"),event.optLong("expires_at",0L))) {
                                    message = "设备绑定成功。正在连接原生邮箱 API。"
                                    nativeApiActive = false
                                    refreshNativeApi()
                                } else message = "设备授权返回值无效，未保存本机凭据。"
                            }
                            "disabled" -> message = "服务器尚未开放原生设备 API，原有登录和邮件不受影响。"
                            "rejected" -> message = "设备绑定被服务器拒绝，请确认账户、密码或 2FA。"
                        }
                    }
                }
            )
        }
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
        if (key == MailSection.INBOX && WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,"orbitMailDetail",setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender,data,origin,mainFrame,_ ->
                    if (data.type != WebMessageCompat.TYPE_STRING ||
                        !NativeInboxPolicy.acceptsSource(
                            origin.scheme,origin.host,origin.port,mainFrame,sender.url
                        )) return@WebMessageListener
                    val event=NativeMailDetailPolicy.parse(data.data)
                        ?: return@WebMessageListener
                    runOnUiThread {
                        if (!unlocked || publicWebView() !== view || !isCurrentPublicMailDocument())
                            return@runOnUiThread
                        when (event) {
                            NativeMailDetailEvent.Clear -> nativeMailDetail=null
                            is NativeMailDetailEvent.Detail -> {
                                val detail=event.data
                                if (pendingNativeMessageId == detail.id &&
                                    nativeInbox?.currentId == detail.mailboxId &&
                                    nativeInbox?.locked == false) {
                                    nativeMailDetail=detail
                                    showNativeMail=true
                                }
                            }
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
                                if (nativeInbox?.currentId != event.data.currentId) {
                                    nativeMailDetail=null
                                    pendingNativeMessageId=null
                                }
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
                if (key == MailSection.INBOX) {
                    nativeInbox = null
                    nativeMailDetail = null
                    pendingNativeMessageId = null
                }
                if (url == null || !AdminUrlPolicy.isTrustedMainFrame(url)) {
                    view?.stopLoading()
                    message = "页面来源不符合管理员域名限制。"
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && AdminUrlPolicy.isTrustedMainFrame(url) &&
                    BrowserSessionRouting.key(activeSection) == key) {
                    message = ""
                    CookieManager.getInstance().flush()
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Never proceed on certificate errors.
                message = "证书校验失败，已阻止连接。"
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    if (key == MailSection.INBOX) nativeInbox = null
                    if (BrowserSessionRouting.key(activeSection) == key)
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
        val initial = when (key) {
            MailSection.ADMIN ->
                (pendingAdminUrl ?: MailSection.ADMIN.address!!).also { pendingAdminUrl = null }
            MailSection.TRANSFER ->
                (pendingTransferUrl ?: MailSection.TRANSFER.address!!).also { pendingTransferUrl = null }
            MailSection.INBOX ->
                (pendingPublicUrl ?: MailSection.INBOX.address!!).also { pendingPublicUrl = null }
            else -> MailSection.INBOX.address!!
        }
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
            if (nativeDeviceClient.isBound()) refreshNativeApi()
            else requestMissingNativeSnapshot()
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
        // Persist the website's first-party HttpOnly session cookies even if
        // Android later kills the process. This does not share cookies with
        // the user's ordinary browser or bypass server expiry/revocation.
        CookieManager.getInstance().flush()
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
            .setContentTitle("Orbit Mail · 收到新邮件")
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
            .setContentTitle("Orbit Mail · 通知测试")
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
