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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID
import android.util.Base64
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
    private var appearanceMode by mutableStateOf(OrbitAppearanceMode.SYSTEM)
    private fun chooseAppearanceMode(next: OrbitAppearanceMode) {
        appearanceMode=next
        getSharedPreferences("orbit_appearance_v1",MODE_PRIVATE)
            .edit().putString("mode",next.name).apply()
    }
    private var nativeInbox by mutableStateOf<NativeInboxSnapshot?>(null)
    private val nativeDeviceClient by lazy { NativeDeviceClient(applicationContext) }
    private val nativeIo = Executors.newSingleThreadExecutor()
    // Mail detail reads must not queue behind mailbox sync, transfers or FCM calls.
    private val nativeReadIo = Executors.newSingleThreadExecutor()
    private val nativeRequestGeneration = AtomicLong(0)
    private val nativeOfflineCache by lazy { NativeOfflineMailboxCache(applicationContext) }
    private var nativeLastSuccessfulSyncMs = 0L
    // Skip duplicate HTTP requests when a successful snapshot is still fresh.
    private fun refreshNativeApiIfNeeded() {
        val age=SystemClock.elapsedRealtime()-nativeLastSuccessfulSyncMs
        if(nativeApiActive && nativeInbox != null && nativeLastSuccessfulSyncMs>0L &&
            age in 0..45_000L) return
        refreshNativeApi()
    }
    private var nativeApiActive by mutableStateOf(false)
    private var nativeApiLoading by mutableStateOf(false)
    private var nativeApiNotice by mutableStateOf("")
    private var showNativePairDialog by mutableStateOf(false)
    private var nativePairLabel by mutableStateOf("我的 Android 手机")
    private var nativePairPassword by mutableStateOf("")
    private var nativePairTotp by mutableStateOf("")
    // Native credentials are transient Compose state, never a WebView Cookie.
    private var nativeLoginEmail by mutableStateOf("")
    private var nativeLoginPassword by mutableStateOf("")
    private var nativeLoginTotp by mutableStateOf("")
    private var nativeLoginBusy by mutableStateOf(false)
    private var nativeLoginError by mutableStateOf("")
    private var pendingPairNonce: String? = null
    private var showNativeMail by mutableStateOf(true)
    private var showNativeAccountDetails by mutableStateOf(false)
    private var showHumanVerificationPrompt by mutableStateOf(false)
    private var humanVerificationFlow = false
    private var humanVerificationBaseline = emptySet<String>()
    private var humanVerificationCode by mutableStateOf("")
    private var lastHumanVerificationPromptAtMs = 0L
    private var showNativeClosureInfo by mutableStateOf(false)
    private val accountOverviewClient by lazy { NativeAccountOverviewClient() }
    private val accountOverviewIo = Executors.newSingleThreadExecutor()
    private val accountOverviewGeneration = AtomicLong(0)
    private var accountOverview by mutableStateOf<NativeAccountOverview?>(null)
    private var accountOverviewBusy by mutableStateOf(false)
    private var accountOverviewError by mutableStateOf("")
    // All regular tabs are Compose surfaces. Website views are explicit fallback only.
    private var showNativeToolsPanel by mutableStateOf(true)
    private var adminSearchQuery by mutableStateOf("")
    private var adminQuickMenuOpen by mutableStateOf(false)
    // Administrator authentication never consumes ordinary-account native grants.
    // The existing first-party admin session is read by a separate native client.
    private val nativeAdminClient by lazy { NativeAdminClient() }
    private val adminIo = Executors.newSingleThreadExecutor()
    private val adminReadIo = Executors.newFixedThreadPool(4)
    private val adminGeneration = AtomicLong(0)
    private var adminBusy by mutableStateOf(false)
    private var adminMessageLoading by mutableStateOf(false)
    private var adminAuthenticated by mutableStateOf(false)
    private var adminUsername by mutableStateOf("")
    private var adminSignInUsername by mutableStateOf("")
    private var adminSignInPassword by mutableStateOf("")
    private var adminTotpCode by mutableStateOf("")
    private var adminTotpRequired by mutableStateOf(false)
    private var adminError by mutableStateOf("")
    private var adminMailboxes by mutableStateOf<List<AdminNativeMailbox>>(emptyList())
    private var adminMessages by mutableStateOf<List<AdminNativeMessage>>(emptyList())
    private var adminSelectedMailbox by mutableStateOf<AdminNativeMailbox?>(null)
    private var adminSelectedMessage by mutableStateOf<AdminNativeDetail?>(null)
    private var adminSelectedMessageId by mutableStateOf<String?>(null)
    private var adminPrivateDomainOptions by mutableStateOf<List<AdminCreateDomainOption>>(emptyList())
    private var adminOrdinaryDomainOptions by mutableStateOf<List<AdminCreateDomainOption>>(emptyList())
    private var adminMailboxFilter by mutableStateOf("全部")
    private var adminMailboxFiltersExpanded by mutableStateOf(false)
    private var adminDomainFiltersExpanded by mutableStateOf(false)
    private var adminRecordFiltersExpanded by mutableStateOf(false)
    private var adminRegistrationFiltersExpanded by mutableStateOf(false)
    private var adminRegistrationSearch by mutableStateOf("")
    private var adminRegistrationSourceFilter by mutableStateOf("全部")
    private var adminRegisteredAccounts by mutableStateOf<List<AdminRegisteredAccount>>(emptyList())
    private var adminRegistrationFetchLimit by mutableStateOf(100)
    private var adminSelectedRegistration by mutableStateOf<AdminRegisteredAccount?>(null)
    private var adminDomainSearch by mutableStateOf("")
    private var adminDomainSourceFilter by mutableStateOf("全部")
    private var adminDomainStateFilter by mutableStateOf("全部")
    private var adminRecordSearch by mutableStateOf("")
    private var adminRecordStateFilter by mutableStateOf("全部")
    private var adminRecordSourceFilter by mutableStateOf("全部")
    private var adminLedgerTab by mutableStateOf("internal")
    private var adminExternalEmails by mutableStateOf<List<AdminExternalEmail>>(emptyList())
    private var adminSelectedExternalEmail by mutableStateOf<AdminExternalEmail?>(null)
    private var adminSelectedExternalDetail by mutableStateOf<AdminExternalEmailDetail?>(null)
    private var adminExternalDetailBusy by mutableStateOf(false)
    private var adminExternalDetailError by mutableStateOf("")
    private val adminExternalDetailGeneration=AtomicLong(0)
    private val adminLoadedAt=mutableMapOf<String,Long>()
    // All administrator management panes are native Compose, never website links.
    private var adminPane by mutableStateOf("mailboxes")
    private var adminManagedDomains by mutableStateOf<List<AdminManagedDomain>>(emptyList())
    private var adminInventoryDomains by mutableStateOf<List<AdminInventoryDomain>>(emptyList())
    private var adminInventoryStatus by mutableStateOf("")
    private var adminSelectedDomain by mutableStateOf<AdminManagedDomain?>(null)
    private var adminRecordedAddresses by mutableStateOf<List<AdminRecordedAddress>>(emptyList())
    private var adminActivationTarget by mutableStateOf<AdminMailboxActivationIntent?>(null)
    private var adminActivationError by mutableStateOf("")
    private var adminActivationNotice by mutableStateOf("")
    private val adminActivationGeneration = AtomicLong(0)
    private var adminSecurityState by mutableStateOf<AdminSecurityState?>(null)
    // Administrator-owned registration policy, never shared with ordinary login.
    private var adminRegPolicy by mutableStateOf<JSONObject?>(null)
    private var adminRegMode by mutableStateOf("open")
    private var adminRegStart by mutableStateOf("")
    private var adminRegOpen by mutableStateOf("60")
    private var adminRegClosed by mutableStateOf("1380")
    private var adminRegQuota by mutableStateOf("")
    private var adminRegPassword by mutableStateOf("")
    private var adminRegTotp by mutableStateOf("")
    private var adminRegMessage by mutableStateOf("")
    private var adminRegMenuOpen by mutableStateOf(false)
    private var showAdminRegConfirm by mutableStateOf(false)
    private var adminDeleteTarget by mutableStateOf<AdminNativeMailbox?>(null)
    private var adminDeleteError by mutableStateOf("")
    private var adminRevokeTrustedTarget by mutableStateOf<AdminTrustedDevice?>(null)
    private var showAdminCreateDialog by mutableStateOf(false)
    private var adminCreatePrivate by mutableStateOf(true)
    private var adminCreateLocal by mutableStateOf("")
    private var adminCreateDomain by mutableStateOf("")
    private var adminCreateDomainQuery by mutableStateOf("")
    private var adminCreateDomainExpanded by mutableStateOf(false)
    // Private mail is a separate sender service with a separate mail_session.
    private val privateMailClient by lazy { NativePrivateMailClient() }
    private val privateMailIo = Executors.newSingleThreadExecutor()
    private val privateMailGeneration = AtomicLong(0)
    private var privateMailBusy by mutableStateOf(false)
    private var privateMailReady by mutableStateOf(false)
    private var privateMailError by mutableStateOf("")
    private var privateMailNotice by mutableStateOf("")
    private var privateMailFrom by mutableStateOf(NativePrivateMailClient.SENDERS.first())
    private var privateMailTo by mutableStateOf("")
    private var privateMailCc by mutableStateOf("")
    private var privateMailBcc by mutableStateOf("")
    private var privateMailSubject by mutableStateOf("")
    private var privateMailText by mutableStateOf("")
    private var privateMailScheduledAt by mutableStateOf("")
    private var privateMailExpandCc by mutableStateOf(false)
    private var privateMailSenderDropdown by mutableStateOf(false)
    private var privateMailRequestId by mutableStateOf(UUID.randomUUID().toString())
    private var privateMailFiles by mutableStateOf<List<NativeSelectedFile>>(emptyList())
    private var privateMailLastScheduledId by mutableStateOf("")

    private var webLoginInProgress by mutableStateOf(false)
    private var nativeSearchQuery by mutableStateOf("")
    private var pendingNativeMessageId by mutableStateOf<String?>(null)
    private var nativeMailDetail by mutableStateOf<NativeMailDetail?>(null)
    private var nativeReadCommandUnavailable by mutableStateOf(false)
    private var showCreateMailbox by mutableStateOf(false)
    private var nativeCreateLocal by mutableStateOf("")
    private var nativeCreateDomain by mutableStateOf("")
    private var nativePickedFiles by mutableStateOf<List<NativeSelectedFile>>(emptyList())
    private var activeSection by mutableStateOf(MailSection.INBOX)
    private var workspace by mutableStateOf(MailWorkspace.ORDINARY)
    // Only a presentation preference. Never treat the stored value as admin authorization.
    private val workspacePrefs by lazy { getSharedPreferences("orbit_workspace_v1",MODE_PRIVATE) }
    private val adminRestoreGeneration=AtomicLong(0)
    private var adminRestoreChecking by mutableStateOf(false)
    // Presentation-only; does not affect ordinary/admin authentication or browser sessions.
    private var workspaceSwitcherExpanded by mutableStateOf(false)
    private val glyphClient by lazy { NativeGlyphClient() }
    private val glyphIo = Executors.newSingleThreadExecutor()
    private val glyphGeneration = AtomicLong(0)
    private var glyphSnapshot by mutableStateOf<NativeGlyphSnapshot?>(null)
    private val adminGlyphGeneration=AtomicLong(0)
    private var adminGlyphSnapshot by mutableStateOf<NativeGlyphSnapshot?>(null)
    private var adminGlyphBusy by mutableStateOf(false)
    private var adminGlyphError by mutableStateOf("")
    private var adminGlyphDrawId by mutableStateOf(UUID.randomUUID().toString())
    private var adminGlyphBuyTarget by mutableStateOf<NativeGlyphItem?>(null)
    private var glyphBusy by mutableStateOf(false)
    private var glyphSiteSession by mutableStateOf(false)
    private var glyphError by mutableStateOf("")
    private var glyphNotice by mutableStateOf("")
    private var glyphTab by mutableStateOf("图鉴")
    private var glyphSearch by mutableStateOf("")
    private var glyphScope by mutableStateOf("全部")
    private var glyphDrawId by mutableStateOf(UUID.randomUUID().toString())
    private var glyphBuyTarget by mutableStateOf<NativeGlyphItem?>(null)
    private var previousWebSection = MailSection.INBOX
    private val sectionBrowsers = mutableMapOf<MailSection,WebView>()
    private val browser: WebView? get() = sectionBrowsers[BrowserSessionRouting.key(activeSection)]
    private fun publicWebView(): WebView? = sectionBrowsers[MailSection.INBOX]
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
    // Persist a pending server revocation across app restarts; never silently re-enable push.
    private var backgroundPushRevokePending by mutableStateOf(false)
    private var backgroundPushStatus by mutableStateOf("未启用")
    private var lastPushAttemptAtMs = 0L
    private var lastNativeSnapshotRequestAtMs = 0L

    private lateinit var biometricPrompt: BiometricPrompt

    // Native SAF picker is local-only until a first-party, account-authorized,
    // client-side encrypted file transfer protocol is accepted and deployed.
    private val nativeFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val safeReturn = pickerPending && pickerReturnAllowed()
            pickerPending = false
            pickerLaunchedAtMs = 0L
            if (!unlocked || !safeReturn) {
                lock()
                return@registerForActivityResult
            }
            nativePickedFiles = uris.take(10).mapNotNull { uri ->
                try {
                    if (uri.scheme != "content") return@mapNotNull null
                    var name = "未命名文件"
                    var size = -1L
                    contentResolver.query(uri, arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE
                    ), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(
                                android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(
                                android.provider.OpenableColumns.SIZE)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex)
                                ?.take(160) ?: name
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex))
                                size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                        }
                    }
                    NativeSelectedFile(uri, name, size)
                } catch (_: Exception) { null }
            }
        }

    private val privateMailFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val safeReturn = pickerPending && pickerReturnAllowed()
            pickerPending = false
            pickerLaunchedAtMs = 0L
            if (!unlocked || !safeReturn) {
                lock()
                return@registerForActivityResult
            }
            privateMailFiles = uris.take(3).mapNotNull { uri ->
                try {
                    if (uri.scheme != "content") return@mapNotNull null
                    var name = "attachment"
                    var size = -1L
                    contentResolver.query(uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (n >= 0) name = cursor.getString(n)?.take(120) ?: name
                            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                        }
                    }
                    if (size > 5L * 1024 * 1024) null
                    else NativeSelectedFile(uri,name,size)
                } catch (_: Exception) { null }
            }
        }

    private val pushPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && nativeDeviceClient.isBound() &&
                BackgroundPushConsent.mayEnable(backgroundPushRevokePending)) {
                setPushOptIn(true)
                tryRegisterPush()
            } else backgroundPushStatus = if (backgroundPushRevokePending)
                "服务器撤销尚未确认，不能重新开启推送。"
                else "未授予通知权限，设备未登记。"
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) sendTestNotification()
            else message = "系统未授权通知；不会发送任何邮件内容。"
        }

    /** Only the Android system document picker receives a bounded lock exception. */
    private fun launchNativeDocumentPicker(privateSender: Boolean) {
        if (!unlocked || pickerPending) return
        pickerPending = true
        pickerLaunchedAtMs = SystemClock.elapsedRealtime()
        try {
            if (privateSender) privateMailFilePicker.launch(arrayOf("*/*"))
            else nativeFilePicker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            pickerPending = false
            pickerLaunchedAtMs = 0L
            message = "系统文件选择器未能启动，未读取或上传任何文件。"
        }
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
        appearanceMode=OrbitAppearanceMode.parse(
            getSharedPreferences("orbit_appearance_v1",MODE_PRIVATE)
                .getString("mode",OrbitAppearanceMode.SYSTEM.name))
        if(workspacePrefs.getString("last_workspace","auto")=="admin") {
            workspace=MailWorkspace.ADMIN
            activeSection=MailSection.ADMIN
        }
        val pushPrefs = getSharedPreferences("orbit_push",MODE_PRIVATE)
        backgroundPushRevokePending = pushPrefs.getBoolean("revoke_pending",false)
        backgroundPushOptIn = pushPrefs.getBoolean("opt_in",false)
        if (backgroundPushRevokePending) setPushOptIn(false)
        backgroundPushStatus = if (backgroundPushRevokePending)
            "本机数据推送已关闭，服务器撤销尚未确认。"
            else if (backgroundPushOptIn) "已授权，等待服务器确认" else "未启用"

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    message = ""
                    browser?.resumeTimers()
                    browser?.onResume()
                    restoreWorkspaceAfterUnlock()
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
            OrbitMailTheme(appearanceMode) {
                Surface(modifier = Modifier.fillMaxSize(),
                    color=MaterialTheme.colorScheme.background) {
                    if (unlocked && adminRestoreChecking) AdminRestoreScreen()
                    else if (unlocked) AdminScreen() else LockScreen()
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

    /** On first use, probe the FIRST-PARTY admin session before choosing UI.
     * A valid cookie must be verified by the Worker; unverified devices get
     * the ordinary inbox and cannot see cached administrator data.
     */
    private fun restoreWorkspaceAfterUnlock() {
        when(workspacePrefs.getString("last_workspace","auto")) {
            "admin" -> {
                workspace=MailWorkspace.ADMIN
                activeSection=MailSection.ADMIN
                showNativeToolsPanel=true
                refreshNativeAdmin()
            }
            "ordinary" -> resumeOrdinaryAfterUnlock()
            else -> {
                adminRestoreChecking=true
                val generation=adminRestoreGeneration.incrementAndGet()
                val epoch=nativeAdminClient.requestEpoch()
                adminReadIo.execute {
                    val response=nativeAdminClient.get("/api/admin/status",epoch)
                    val verified=response.status==200 &&
                        response.value?.optBoolean("authenticated")==true
                    runOnUiThread {
                        if(!unlocked || adminRestoreGeneration.get()!=generation ||
                            !adminRestoreChecking)return@runOnUiThread
                        adminRestoreChecking=false
                        if(verified) {
                            workspace=MailWorkspace.ADMIN
                            activeSection=MailSection.ADMIN
                            showNativeToolsPanel=true
                            refreshNativeAdmin()
                        } else resumeOrdinaryAfterUnlock()
                    }
                }
            }
        }
    }

    private fun resumeOrdinaryAfterUnlock() {
        workspace=MailWorkspace.ORDINARY
        activeSection=MailSection.INBOX
        if(nativeDeviceClient.isBound())refreshNativeApiIfNeeded()
        else requestMissingNativeSnapshot()
    }

    @Composable
    private fun AdminRestoreScreen() {
        Column(modifier=Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Text("正在确认管理员工作区",style=MaterialTheme.typography.titleMedium)
            Text("只验证现有会话，不重新登录或加载普通邮箱。",
                style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                adminRestoreGeneration.incrementAndGet()
                adminRestoreChecking=false
                workspacePrefs.edit().putString("last_workspace","ordinary").apply()
                resumeOrdinaryAfterUnlock()
            }) {Text("直接进入普通邮箱")}
        }
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

    /**
     * Switching workspaces only changes navigation presentation. It NEVER
     * promotes an ordinary device grant to administrator access.
     */
    private fun refreshNativeGlyphs() {
        if (!unlocked || glyphBusy) return
        val generation = glyphGeneration.incrementAndGet()
        val epoch = glyphClient.epoch()
        glyphBusy = true
        glyphError = ""
        glyphIo.execute {
            // No CookieManager reads during Compose rendering.
            val ordinarySiteSession = glyphClient.hasOrdinarySiteSession()
            fun readGlyph(suffix: String): NativeGlyphClient.Reply {
                if(nativeDeviceClient.isBound()) {
                    // Keystore-bound P-256 account data first. Native-device
                    // glyph reads are in the guarded Worker PR, not production
                    // until its security acceptance gate is approved.
                    val native=nativeDeviceClient.fetch(
                        "/api/account/native-device/glyphs/$suffix")
                    if(native.status==200)
                        return NativeGlyphClient.Reply(native.status,native.value,native.error)
                    if(native.status !in setOf(404,503) ||
                        !ordinarySiteSession)
                        return NativeGlyphClient.Reply(native.status,native.value,native.error)
                }
                return glyphClient.get("/api/lucky-glyphs/$suffix",epoch)
            }
            val status = readGlyph("status")
            val collections = if(status.status == 200)
                readGlyph("collections") else null
            val parsed = if(status.status == 200 && status.value != null)
                try { NativeGlyphModels.read(
                    status.value,collections?.value?.takeIf{collections.status==200})
                } catch(_:Exception) { null } else null
            runOnUiThread {
                if (!unlocked || glyphGeneration.get() != generation) return@runOnUiThread
                glyphBusy = false
                glyphSiteSession = ordinarySiteSession
                if(parsed != null) {
                    glyphSnapshot = parsed
                    glyphError = if(collections?.status==200) "" else
                        "常规字符已同步，节日收藏接口暂不可用。"
                } else {
                    glyphSnapshot = null
                    glyphError = if(status.error == "ORDINARY_SITE_SESSION_REQUIRED")
                        "字符装扮目前要求普通账号的网站会话。" +
                        "已有原生设备授权不等于字符 API 已授权；需先完成服务端原生权限桥接。" 
                        else "字符状态读取失败：HTTP ${status.status} / " +
                            (status.error ?: "UNKNOWN")
                }
            }
        }
    }

    private fun performNativeGlyphAction(action: String, item: NativeGlyphItem?=null) {
        if (!unlocked || glyphBusy || glyphSnapshot==null) return
        val request: Pair<String, JSONObject> = when(action) {
            "checkin" -> "/api/lucky-glyphs/checkin" to JSONObject()
            "draw" -> "/api/lucky-glyphs/draw" to
                JSONObject().put("idempotency_key",glyphDrawId)
            "equip" -> {
                if(item==null || !item.owned || !NativeGlyphModels.validId(item.id)) return
                "/api/lucky-glyphs/equip" to JSONObject().put("glyph_id",item.id)
            }
            "unequip" -> "/api/lucky-glyphs/equip" to JSONObject().put("glyph_id",JSONObject.NULL)
            "buy" -> {
                if(item==null || !NativeGlyphModels.validId(item.id) ||
                    item.owned || item.price <= 0) return
                "/api/lucky-glyphs/shop/buy" to JSONObject().put("glyph_id",item.id)
            }
            else -> return
        }
        val generation=glyphGeneration.incrementAndGet()
        val epoch=glyphClient.epoch()
        glyphBusy=true
        glyphError=""
        glyphNotice=""
        glyphIo.execute {
            val reply=glyphClient.post(request.first,request.second,epoch)
            runOnUiThread {
                if(!unlocked || glyphGeneration.get()!=generation)return@runOnUiThread
                glyphBusy=false
                if(reply.status==200 && reply.value?.has("catalog")==true) {
                    glyphNotice = when(action) {
                        "checkin" -> "已向服务器核实今日签到状态。"
                        "draw" -> "抽取已由服务器确认，图鉴更新中。"
                        "equip","unequip" -> "佩戴已保存。"
                        else -> "今日商店兑换已确认。"
                    }
                    if(action=="draw") glyphDrawId=UUID.randomUUID().toString()
                    if(action=="buy") glyphBuyTarget=null
                    refreshNativeGlyphs()
                } else {
                    glyphError="操作未获服务器确认：HTTP ${reply.status} / " +
                        (reply.error ?: "UNKNOWN") +
                        "。未改变本地图鉴或余额。"
                    if(reply.status==401)glyphSnapshot=null
                }
            }
        }
    }

    @Composable
    private fun NativeGlyphContent() {
        val data=glyphSnapshot
        Column(modifier=Modifier.fillMaxSize()) {
            Row(modifier=Modifier.fillMaxWidth()
                .padding(horizontal=16.dp,vertical=12.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(modifier=Modifier.weight(1f)) {
                    Text("幸运字符 · 装扮",style=MaterialTheme.typography.headlineSmall)
                    Text("普通账号收藏 · 原生 Android 图鉴",
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.labelSmall)
                }
                TextButton(enabled=!glyphBusy,onClick=::refreshNativeGlyphs) {
                    Icon(painterResource(R.drawable.ic_refresh),contentDescription="刷新")
                    Text("刷新")
                }
            }
            if(glyphError.isNotBlank())
                Text(glyphError,modifier=Modifier.padding(horizontal=16.dp),
                    color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall)
            if(glyphNotice.isNotBlank())
                Text(glyphNotice,modifier=Modifier.padding(horizontal=16.dp),
                    color=MaterialTheme.colorScheme.primary,
                    style=MaterialTheme.typography.bodySmall)
            if(data!=null && !glyphSiteSession)
                Text("原生设备授权当前仅支持读取装扮图鉴；" +
                    "签到、抽取和兑换需由服务端扩展签名交易协议后开放。",
                    modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp),
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(data==null) {
                Column(modifier=Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center) {
                    Box(modifier=Modifier.size(96.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer,CircleShape),
                        contentAlignment=Alignment.Center) {
                        Text("✦",style=MaterialTheme.typography.displayMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if(glyphBusy) "正在同步字符图鉴…"
                        else "无法读取普通账号的装扮资料",
                        style=MaterialTheme.typography.titleMedium)
                    Text("账号字符、积分和装扮属于普通账号，不能借用管理员身份。",
                        style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.padding(vertical=10.dp))
                    Button(onClick=::refreshNativeGlyphs,enabled=!glyphBusy) {
                        Text("重新同步")
                    }
                }
            } else {
                Card(modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp)) {
                    Column(modifier=Modifier.padding(16.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(modifier=Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween) {
                            Column {
                                Text("当前佩戴",style=MaterialTheme.typography.labelSmall)
                                Text(data.equipped?.let{
                                    it.symbol+" "+it.name
                                } ?: "未佩戴字符",style=MaterialTheme.typography.titleLarge)
                            }
                            Box(modifier=Modifier.size(54.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer,CircleShape),
                                contentAlignment=Alignment.Center) {
                                Text(data.equipped?.symbol?.ifBlank { "✦" } ?: "✦",
                                    style=MaterialTheme.typography.headlineMedium)
                            }
                        }
                        Text("已收集 ${data.owned}/${data.total}  ·  ${data.points} 积分  ·  ${data.streak} 天连续",
                            style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={performNativeGlyphAction("checkin")},
                                enabled=!glyphBusy && !data.checkedIn &&
                                    glyphSiteSession) {
                                Text(if(data.checkedIn) "今日已签到" else "每日签到")
                            }
                            OutlinedButton(onClick={performNativeGlyphAction("draw")},
                                enabled=!glyphBusy && data.draws>0 &&
                                    glyphSiteSession) {
                                Text("抽取 · ${data.draws} 次")
                            }
                        }
                    }
                }
                Row(modifier=Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal=10.dp,vertical=7.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("图鉴","每日商店","节日收藏","主题套组").forEach { name ->
                        OutlinedButton(onClick={glyphTab=name}) {
                            Text((if(glyphTab==name) "✓ " else "")+name)
                        }
                    }
                }
                if(glyphTab=="图鉴") {
                    OutlinedTextField(value=glyphSearch,
                        onValueChange={glyphSearch=it.take(100)},
                        label={Text("搜索字符、系列或稀有度")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp))
                    Row(modifier=Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal=12.dp,vertical=5.dp),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("全部","已拥有","普通","稀有","史诗","传说").forEach{scope->
                            OutlinedButton(onClick={glyphScope=scope}) {
                                Text((if(glyphScope==scope) "✓ " else "")+scope)
                            }
                        }
                    }
                }
                val glyphItems = if(glyphTab=="每日商店") data.shop else data.catalog
                val rarityLabels=mapOf("common" to "普通","rare" to "稀有",
                    "epic" to "史诗","legendary" to "传说")
                val filtered=if(glyphTab=="图鉴")glyphItems.filter {
                    (glyphSearch.isBlank() ||
                        it.name.contains(glyphSearch,ignoreCase=true) ||
                        it.id.contains(glyphSearch,ignoreCase=true) ||
                        it.rarity.contains(glyphSearch,ignoreCase=true)) &&
                        (glyphScope=="全部" ||
                            (glyphScope=="已拥有" && it.owned) ||
                            (rarityLabels[it.rarity]==glyphScope))
                } else glyphItems
                if(glyphTab=="图鉴" || glyphTab=="每日商店") {
                    LazyColumn(modifier=Modifier.fillMaxSize(),
                        contentPadding=PaddingValues(bottom=18.dp)) {
                        items(filtered,key={it.id}) { item ->
                            Row(modifier=Modifier.fillMaxWidth()
                                .padding(horizontal=14.dp,vertical=7.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Box(modifier=Modifier.size(56.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer,CircleShape),
                                    contentAlignment=Alignment.Center) {
                                    Text(item.symbol.ifBlank{"✧"},
                                        style=MaterialTheme.typography.headlineMedium)
                                }
                                Spacer(Modifier.padding(horizontal=7.dp))
                                Column(modifier=Modifier.weight(1f)) {
                                    Text(item.name,style=MaterialTheme.typography.titleMedium)
                                    Text((rarityLabels[item.rarity] ?: item.rarity) +
                                        if(item.owned) " · 已拥有" else " · 未解锁",
                                        style=MaterialTheme.typography.labelSmall)
                                    if(item.description.isNotBlank())Text(item.description,
                                        style=MaterialTheme.typography.bodySmall,
                                        maxLines=2)
                                }
                                if(glyphTab=="每日商店") {
                                    OutlinedButton(enabled=!glyphBusy && !item.owned &&
                                        item.price>0 && data.points>=item.price &&
                                        glyphSiteSession,
                                        onClick={glyphBuyTarget=item}) {
                                        Text(if(item.owned) "已拥有" else "${item.price}积分")
                                    }
                                } else if(item.owned) {
                                    TextButton(enabled=!glyphBusy && !item.equipped &&
                                        glyphSiteSession,
                                        onClick={performNativeGlyphAction("equip",item)}) {
                                        Text(if(item.equipped) "已佩戴" else "佩戴")
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    val extras=if(glyphTab=="节日收藏")data.collections else data.sets
                    LazyColumn(modifier=Modifier.fillMaxSize()) {
                        if(extras.isEmpty())item {
                            Text("该收藏类别暂时没有可显示的条目。",
                                modifier=Modifier.padding(20.dp))
                        }
                        items(extras) { entry ->
                            Card(modifier=Modifier.fillMaxWidth()
                                .padding(horizontal=14.dp,vertical=5.dp)) {
                                Text(entry,modifier=Modifier.padding(14.dp))
                            }
                        }
                        item {
                            Text("节日兑换及主题购买仍需服务端专用授权和原生确认，" +
                                "展示资料并不代表已开启兑换。",
                                modifier=Modifier.padding(15.dp),
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeGlyphBuyDialog() {
        val item=glyphBuyTarget ?: return
        AlertDialog(
            onDismissRequest={if(!glyphBusy)glyphBuyTarget=null},
            title={Text("确认兑换装扮？")},
            text={Text("兑换「${item.name}」将消耗 ${item.price} 字符积分。" +
                "只有服务器返回成功后才更新余额，兑换不涉及现金。")},
            confirmButton={
                Button(enabled=!glyphBusy,onClick={
                    performNativeGlyphAction("buy",item)
                }) { Text(if(glyphBusy)"处理中…" else "确认兑换") }
            },
            dismissButton={
                TextButton(enabled=!glyphBusy,onClick={glyphBuyTarget=null}){Text("取消")}
            }
        )
    }

    private fun switchWorkspace(next: MailWorkspace) {
        // Clear overlays before switching security realms; never display admin
        // registration detail on an ordinary-account screen.
        adminSelectedRegistration = null
        adminSelectedDomain = null
        showNativeAccountDetails = false
        showHumanVerificationPrompt=false
        humanVerificationFlow=false
        humanVerificationBaseline=emptySet()
        showNativeClosureInfo=false
        accountOverviewGeneration.incrementAndGet()
        accountOverviewClient.invalidate()
        accountOverview=null
        accountOverviewBusy=false
        // Selecting even the active workspace closes the menu without reloading mail.
        workspaceSwitcherExpanded = false
        adminQuickMenuOpen=false
        if (workspace == next) return
        if (next == MailWorkspace.ADMIN) glyphBuyTarget = null
        else {
            // Leaving admin is a presentation change, not server logout.
            // Clear all in-memory privileged rows; a return requires checking
            // the still-valid independent admin session via /api/admin/status.
            adminGeneration.incrementAndGet()
            nativeAdminClient.invalidatePendingResponses()
            adminAuthenticated=false
            adminBusy=false
            adminLoadedAt.clear()
            adminMailboxes=emptyList()
            adminMessages=emptyList()
            adminSelectedMailbox=null
            adminSelectedMessage=null
            adminSelectedMessageId=null
            adminManagedDomains=emptyList()
            adminInventoryDomains=emptyList()
            adminRecordedAddresses=emptyList()
            adminRegisteredAccounts=emptyList()
            adminExternalEmails=emptyList()
            adminSelectedExternalEmail=null
            adminSelectedExternalDetail=null
            adminExternalDetailGeneration.incrementAndGet()
            adminExternalDetailBusy=false
            adminSecurityState=null
            adminGlyphGeneration.incrementAndGet()
            adminGlyphSnapshot=null
            adminGlyphBuyTarget=null
            adminGlyphBusy=false
            adminDeleteTarget = null
            adminRevokeTrustedTarget = null
            showAdminCreateDialog = false
        }
        workspace = next
        adminRestoreGeneration.incrementAndGet()
        adminRestoreChecking=false
        if(next==MailWorkspace.ORDINARY)
            workspacePrefs.edit().putString("last_workspace","ordinary").apply()
        else if(adminAuthenticated)
            workspacePrefs.edit().putString("last_workspace","admin").apply()
        if (next == MailWorkspace.ADMIN) {
            openSection(MailSection.ADMIN)
        } else {
            openSection(MailSection.INBOX)
        }
    }

    private fun selectAdminNavigation(tab: AdminDestinationTab) {
        if (workspace != MailWorkspace.ADMIN) return
        if (tab == AdminDestinationTab.PRIVATE) {
            openSection(MailSection.PRIVATE)
        } else {
            val target = tab.pane ?: return
            if (activeSection != MailSection.ADMIN) {
                adminPane = target
                openSection(MailSection.ADMIN)
            } else if (adminAuthenticated) {
                openAdminNativePane(target)
            } else {
                adminPane = target
            }
        }
    }

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
            MailSection.ADMIN, MailSection.TRANSFER, MailSection.PRIVATE,
            MailSection.DECOR -> showNativeToolsPanel = true
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
            if (nativeDeviceClient.isBound()) refreshNativeApiIfNeeded()
            else requestMissingNativeSnapshot()
        } else if (section == MailSection.ADMIN && showNativeToolsPanel) {
            refreshNativeAdmin()
        } else if (section == MailSection.PRIVATE) {
            privateMailReady = privateMailClient.hasLocalSession()
        } else if (section == MailSection.DECOR) {
            refreshNativeGlyphs()
        }
    }

    /** Advanced administrator functions always open OUTSIDE the native APK.
     * The in-app ADMIN tab is exclusively a Compose screen, never a WebView.
     */
    private fun openAdvancedAdminWebsite(url: String) {
        if (!unlocked || !AdminUrlPolicy.isTrustedMainFrame(url) ||
            NativeSectionRouting.explicitWebsiteTarget(url) != MailSection.ADMIN) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            message = "无法打开系统浏览器；管理员原生界面保持不变。"
        }
    }

    /** Only a deliberate website action opens first-party HTML. */
    private fun navigateTo(url: String) {
        if (!unlocked) return
        val target = NativeSectionRouting.explicitWebsiteTarget(url) ?: return
        if (target == MailSection.ADMIN) {
            openAdvancedAdminWebsite(url)
            return
        }
        adminGeneration.incrementAndGet()
        nativeAdminClient.invalidatePendingResponses()
        adminBusy = false
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

    /** Format server Unix-millisecond timestamps using the device's local time. */
    private fun formatNativeMessageTime(value: Long): String {
        if (value < 946684800000L || value > 4102444800000L) return ""
        return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
        ).format(java.util.Date(value))
    }

    /** First-party native HTTPS login; no WebView, site session Cookie or admin scope. */
    private fun submitNativeLogin() {
        if (!unlocked || nativeLoginBusy || nativeDeviceClient.isBound()) return
        val email = nativeLoginEmail.trim()
        val password = nativeLoginPassword
        val totp = nativeLoginTotp.trim()
        if (email.isBlank() || password.length !in 8..128) {
            nativeLoginError = "请输入有效邮箱及密码（至少 8 位）。"
            return
        }
        val generation = nativeRequestGeneration.incrementAndGet()
        val loginGrantEpoch = nativeDeviceClient.snapshotLoginEpoch()
        val loginLabel = nativePairLabel
        nativeLoginBusy = true
        nativeLoginError = ""
        nativeIo.execute {
            val result = nativeDeviceClient.login(email, password, totp, loginLabel, loginGrantEpoch)
            runOnUiThread {
                if (nativeRequestGeneration.get() != generation) return@runOnUiThread
                nativeLoginBusy = false
                if (!unlocked) return@runOnUiThread
                if (result.status == 201 && nativeDeviceClient.isBound()) {
                    nativeLoginPassword = ""
                    nativeLoginTotp = ""
                    nativeLoginError = ""
                    nativeInbox = null
                    nativeOfflineCache.clear()
                    nativeLastSuccessfulSyncMs=0L
                    nativeApiActive = false
                    message = "账号已安全绑定到本机，正在同步邮件。"
                    refreshNativeApi()
                } else {
                    nativeLoginError = NativeLoginFeedback.describe(result.status, result.error)
                }
            }
        }
    }

    /** Signed native API with a read-only encrypted offline metadata fallback. */
    private fun refreshNativeApi() {
        if (!unlocked || !nativeDeviceClient.isBound() || nativeApiLoading) return
        val deviceId = nativeDeviceClient.currentId() ?: return
        val generation = nativeRequestGeneration.incrementAndGet()
        val cacheEpoch = nativeOfflineCache.epoch()
        nativeApiLoading = true
        nativeIo.execute {
            val cached = nativeOfflineCache.restore(deviceId)
            if(cached != null) runOnUiThread {
                if(unlocked && nativeDeviceClient.currentId()==deviceId &&
                    nativeRequestGeneration.get()==generation && nativeInbox==null) {
                    nativeInbox=cached
                    nativeApiActive=false
                    nativeApiNotice="离线历史列表；联网验证后才可阅读正文。"
                    showNativeMail=true
                }
            }
            val mailboxes = nativeDeviceClient.fetch("/api/account/native-device/mailboxes")
            if (mailboxes.status != 200 || mailboxes.value == null) {
                runOnUiThread {
                    if (nativeRequestGeneration.get() != generation) return@runOnUiThread
                    nativeApiLoading = false
                    if (unlocked && nativeDeviceClient.currentId() == deviceId) {
                        nativeApiNotice = when (mailboxes.status) {
                            401 -> "设备授权失效或被撤销，需要重新绑定。"
                            503 -> "服务器原生接口尚未启用，现有数据未被更改。"
                            else -> "原生邮箱同步失败：HTTP ${mailboxes.status}"
                        }
                        nativeApiActive = false
                        pendingNativeMessageId = null
                        nativeMailDetail = null
                        // A revoked grant must not expose cached metadata. Transient
                        // outages preserve history but never authorize message reads.
                        val transient = mailboxes.status == 503 || mailboxes.status == 0
                        nativeInbox = if(transient)
                            (nativeInbox ?: cached)?.copy(
                                statusText="离线历史列表 · 未验证最新授权",
                                nativeReadSupported=false) else null
                        if (mailboxes.status == 401) {
                            nativeOfflineCache.clear()
                            nativeLastSuccessfulSyncMs=0L
                            nativeDeviceClient.clearLocalBinding()
                            setPushOptIn(false)
                            nativeLoginPassword = ""
                            nativeLoginTotp = ""
                            nativeLoginError = "原设备授权已撤销或过期，请在原生界面重新登录。"
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
                        formatNativeMessageTime(item.optLong("received_at", 0L))))
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
            // Only successful, unprotected metadata is persisted. UI state remains
            // guarded by a generation and the non-exportable Keystore binding.
            if(selected != null && !locked && messageResult?.status == 200 &&
                nativeRequestGeneration.get()==generation &&
                nativeDeviceClient.currentId()==deviceId) {
                nativeOfflineCache.store(deviceId,snapshot,cacheEpoch)
            }
            runOnUiThread {
                if (nativeRequestGeneration.get() != generation) return@runOnUiThread
                nativeApiLoading = false
                if (!unlocked || nativeDeviceClient.currentId() != deviceId) return@runOnUiThread
                nativeApiActive = true
                nativeLastSuccessfulSyncMs=SystemClock.elapsedRealtime()
                nativeApiNotice = if (selected != null && !locked && messageResult?.status != 200)
                    "邮件列表暂不可用；请刷新重试。" else ""
                if (nativeInbox?.currentId != snapshot.currentId) {
                    pendingNativeMessageId = null
                    nativeMailDetail = null
                }
                val prior = nativeInbox
                if (NewMailArrivalPolicy.hasArrival(prior,snapshot)) {
                    val stamp=SystemClock.elapsedRealtime()
                    if (MailNoticePolicy.canDeliver(stamp,lastNewMailNoticeAtMs) &&
                        showMailNotification()) lastNewMailNoticeAtMs=stamp
                }
                nativeInbox = NativeIncrementalMerge.snapshot(prior,snapshot)
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
        nativeReadIo.execute {
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
                    formatNativeMessageTime(item.optLong("received_at", 0L)),
                    item.optString("text_body").take(8000),
                    item.optString("html_body").take(16000),
                    item.optString("possible_code").takeIf {
                        Regex("^[A-Za-z0-9-]{4,16}$").matches(it)
                    }.orEmpty(),
                    NativeMailLinks.read(item.optJSONArray("safe_links"),
                        item.optString("text_body"),item.optString("html_body")))
                showNativeMail = true
            }
        }
    }

    /** Revocation is performed by signed request; failures never falsely claim success. */
    private fun revokeNativeDeviceOnServer() {
        if (!unlocked || !nativeDeviceClient.isBound() || nativeApiLoading) return
        nativeRequestGeneration.incrementAndGet()
        nativeApiLoading = true
        nativeApiActive = false
        nativeInbox = null
        nativeMailDetail = null
        pendingNativeMessageId = null
        nativeIo.execute {
            val response = nativeDeviceClient.revokeSelf()
            val revoked = response.status == 200 && response.value?.optBoolean("revoked") == true
            if (revoked) {
                nativeOfflineCache.clear()
                nativeLastSuccessfulSyncMs=0L
                try { nativeDeviceClient.clearLocalBinding() } catch (_: Exception) {
                    // Server revocation already succeeded. The stale device ID cannot authenticate.
                }
            }
            runOnUiThread {
                nativeApiLoading = false
                if (revoked) {
                    setPushOptIn(false)
                    setPushRevokePending(false)
                }
                if (!unlocked) return@runOnUiThread
                message = if (revoked) "服务器已撤销此 Android 设备授权，本机退出原生邮箱。"
                    else "服务器撤销失败（HTTP ${response.status}）。本机密钥保留，可重试；请勿误认为已解绑。"
                nativeApiNotice = message
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
        // Cancel deferred registrations before persisting opt-out. Never claim that
        // local cancellation alone proves server token revocation.
        if (!value) OrbitPushRetry.cancel(applicationContext)
        val stored = getSharedPreferences("orbit_push",MODE_PRIVATE)
            .edit().putBoolean("opt_in",value).commit()
        backgroundPushOptIn = value && stored
        if (value && !stored) backgroundPushStatus = "通知授权无法持久保存；未登记设备。"
    }

    private fun setPushRevokePending(value: Boolean) {
        backgroundPushRevokePending = value
        getSharedPreferences("orbit_push",MODE_PRIVATE).edit()
            .putBoolean("revoke_pending",value).apply()
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
        if (!BackgroundPushConsent.mayDeliver(backgroundPushOptIn,backgroundPushRevokePending) ||
            !OrbitPushConfig.configured || FirebaseApp.getApps(this).isEmpty() || !unlocked)
            return
        // Never open a hidden WebView to acquire a site cookie for native mail push.
        if (!nativeDeviceClient.isBound()) {
            backgroundPushStatus = "请先完成普通账号原生登录，才能登记邮件提醒。"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val stamp = SystemClock.elapsedRealtime()
        if (lastPushAttemptAtMs != 0L && stamp-lastPushAttemptAtMs < 15*60*1000L) return
        lastPushAttemptAtMs = stamp
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!BackgroundPushConsent.mayDeliver(
                    backgroundPushOptIn,backgroundPushRevokePending) || !unlocked)
                    return@addOnSuccessListener
                val installation = pushInstallationId()
                nativeIo.execute {
                    if (!BackgroundPushConsent.mayDeliver(
                        backgroundPushOptIn,backgroundPushRevokePending)) return@execute
                    val response = nativeDeviceClient.registerPush(installation,token)
                    runOnUiThread {
                        if (!BackgroundPushConsent.mayDeliver(
                            backgroundPushOptIn,backgroundPushRevokePending)) return@runOnUiThread
                        backgroundPushStatus =
                            if (response.status == 201 && response.value?.optBoolean("registered") == true)
                                "服务器已确认原生设备登记；等待真实邮件验证投递。"
                            else "后台推送尚未接通：HTTP " + response.status + "；未确认设备登记。"
                    }
                }
            }
            .addOnFailureListener {
                backgroundPushStatus = "设备令牌暂不可用；检查 Google Play 服务与网络。"
            }
    }

    private fun requestBackgroundPush() {
        if (!nativeDeviceClient.isBound()) {
            backgroundPushStatus = "先完成普通邮箱原生登录，网页会话不会代替设备授权。"
            return
        }
        if (!BackgroundPushConsent.mayEnable(backgroundPushRevokePending)) {
            backgroundPushStatus = "服务端撤销尚未确认。请在普通邮箱完成撤销，避免旧令牌继续收信。"
            return
        }
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
        // Fail closed locally before any best-effort website/server request.
        // FCM notification payloads may still be displayed by the OS until the server
        // revokes the device; a local opt-out alone is not remote revocation.
        setPushOptIn(false)
        setPushRevokePending(true)
        lastPushAttemptAtMs = 0L
        retryServerPushRevocation()
    }

    private fun retryServerPushRevocation() {
        if (!backgroundPushRevokePending) return
        if (!nativeDeviceClient.isBound()) {
            backgroundPushStatus = "本机已停用提醒；设备授权已失效，请从账号安全设置撤销旧设备。"
            return
        }
        backgroundPushStatus = "本机提醒已停用，正在通过设备签名撤销服务器登记。"
        val installation = pushInstallationId()
        nativeIo.execute {
            val response = nativeDeviceClient.revokePush(installation)
            runOnUiThread {
                if (!backgroundPushRevokePending) return@runOnUiThread
                if (response.status == 200 && response.value?.optBoolean("revoked") == true) {
                    setPushRevokePending(false)
                    backgroundPushStatus = "服务器已确认撤销；不会自动重新登记。"
                } else {
                    backgroundPushStatus = "本机提醒已停用，但服务器撤销未确认；可重试或进入账号安全设置。"
                }
            }
        }
    }

    private fun copyMailboxAddress(address: String) {
        if (!unlocked || address.isBlank() || address.length > 254) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("邮箱地址",address))
        message = "邮箱地址已复制。"
    }

    /** Visible only for real human interaction, never a solved CAPTCHA surrogate. */
    private fun requestHumanVerification(reason:String="TURNSTILE_REQUIRED") {
        if(!unlocked || workspace!=MailWorkspace.ORDINARY || humanVerificationFlow) return
        val stamp=SystemClock.elapsedRealtime()
        if(lastHumanVerificationPromptAtMs!=0L &&
            stamp-lastHumanVerificationPromptAtMs in 0..900L) return
        lastHumanVerificationPromptAtMs=stamp
        humanVerificationCode=reason
        showHumanVerificationPrompt=true
    }

    private fun openHumanVerificationWebsite() {
        if(!unlocked || workspace!=MailWorkspace.ORDINARY) return
        showHumanVerificationPrompt=false
        showCreateMailbox=false
        if(humanVerificationFlow)return
        humanVerificationFlow=true
        humanVerificationBaseline=nativeInbox?.mailboxes?.map { it.id }?.toSet().orEmpty()
        // A regular first-party WebView owns the real Turnstile iframe and
        // its single-use token. The browser will send the ordinary create POST.
        navigateTo("https://inbox.kango99w.de/?orbit_app=android#orbit-create")
    }

    @Composable
    private fun HumanVerificationDialog() {
        AlertDialog(
            onDismissRequest={showHumanVerificationPrompt=false},
            title={Text("需要人工安全验证")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(when(humanVerificationCode) {
                    "TURNSTILE_EXPIRED" -> "上次验证已过期，请重新手动完成。"
                    "TURNSTILE_FAILED" -> "安全验证未通过，可重新尝试。"
                    else -> "服务器要求完成人机验证后才能创建邮箱。"
                },style=MaterialTheme.typography.bodyMedium)
                Text("仅在本应用的第一方网页区域显示 Cloudflare 验证。请自行完成挑战，系统不会自动识别、跳过或重复提交。",
                    style=MaterialTheme.typography.bodySmall)
                Text("已加载的原生邮箱页面保持状态；完成创建后会返回原生界面。网站验证失效、断网或限流时不能保证创建成功。",
                    style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick=::openHumanVerificationWebsite) {
                Text("在应用内验证")
            }},
            dismissButton={TextButton(onClick={showHumanVerificationPrompt=false}) {
                Text("稍后")
            }}
        )
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
                Column(modifier=Modifier.heightIn(max=430.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeMailboxQuotaSummary(accountOverview,accountOverviewBusy)
                    TextButton(onClick=::refreshAccountOverview,enabled=!accountOverviewBusy) {
                        Text("重新校验额度")
                    }
                    Text("邮箱归属、最终容量和安全验证仍由网站服务器检查。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick={requestHumanVerification()},modifier=Modifier.fillMaxWidth()) {
                        Text("安全验证 / Turnstile")
                    }
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("邮箱域名：@${chosen.ifEmpty { "请选择" }}") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        domains.forEach { option ->
                            DropdownMenuItem(text = { Text("@$option") }, onClick = {
                                expanded = false
                                nativeCreateDomain = option
                                if(!nativeApiActive && !nativeDeviceClient.isBound() &&
                                    !sendNativeMailboxCommand("select_domain",option)) {
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
                    enabled = (nativeApiActive || nativeDeviceClient.isBound()) ||
                        (snapshot.canCreate && !snapshot.busy &&
                        chosen.isNotEmpty() && chosen == snapshot.selectedDomain &&
                        (nativeCreateLocal.isBlank() || validPrefix)),
                    onClick = {
                        if(nativeApiActive || nativeDeviceClient.isBound()) {
                            showCreateMailbox=false
                            navigateTo("https://inbox.kango99w.de/?orbit_app=android#orbit-create")
                            message="已打开服务端创建流程；这里展示的容量快照不代表创建成功。"
                            return@TextButton
                        }
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
                ) { Text(if(nativeApiActive || nativeDeviceClient.isBound())
                    "继续安全创建" else "创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateMailbox = false }) { Text("取消") }
            }
        )
    }

    /** Creating account-owned mailboxes still requires the existing server's CSRF/Turnstile checks. */
    private fun requestMailboxCreation(snapshot: NativeInboxSnapshot) {
        nativeCreateDomain = snapshot.selectedDomain
        nativeCreateLocal = ""
        showCreateMailbox = true
        refreshAccountOverview()
    }

    /** Stay in Compose, even when the server-side native reader is not yet deployed. */
    private fun openNativeMessage(id: String) {
        if(nativeDeviceClient.isBound() && !nativeApiActive) {
            message="离线只支持历史标题；请联网重新校验后阅读正文。"
            return
        }
        // A fast second tap must not enqueue a duplicate signed read.
        if (pendingNativeMessageId == id && nativeMailDetail == null) return
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
        if(nativeDeviceClient.isBound() && !nativeApiActive) {
            message="离线列表不可切换未授权邮箱；请联网刷新。"
            return
        }
        if (nativeApiActive) {
            val item = nativeInbox?.mailboxes?.firstOrNull { it.id == id } ?: return
            nativeRequestGeneration.incrementAndGet()
            nativeApiLoading = false
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
            if (detail.possibleCode.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("识别到的验证码", style = MaterialTheme.typography.labelMedium)
                            Text(detail.possibleCode, style = MaterialTheme.typography.headlineMedium)
                        }
                        TextButton(onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("验证码", detail.possibleCode)
                            )
                            message = "验证码已复制；请注意系统剪贴板的隐私保护。"
                        }) { Text("复制") }
                    }
                }
            }
            NativeMailReadingContent(
                messageKey="ordinary:"+detail.mailboxId+":"+detail.id,
                plainText=detail.textBody,
                htmlBody=detail.htmlBody,
                safeLinks=detail.safeLinks,
                possibleCode=detail.possibleCode
            )
            OutlinedButton(onClick={showNativeMail=false}) {
                Text("尚未原生支持的附件或高级内容（手动打开网站）")
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
            if (!nativeReadCommandUnavailable)
                LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
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

    /** Ordinary website account data is a separate identity from the native P-256 grant. */
    private fun refreshAccountOverview() {
        if (!unlocked || workspace != MailWorkspace.ORDINARY || accountOverviewBusy) return
        val generation = accountOverviewGeneration.incrementAndGet()
        val epoch = accountOverviewClient.epoch()
        accountOverviewBusy = true
        accountOverviewError = ""
        accountOverviewIo.execute {
            val status = accountOverviewClient.read("/api/account/status",epoch)
            val authenticated = status.status==200 &&
                status.value?.optBoolean("authenticated")==true
            val upgrade = if(authenticated)
                accountOverviewClient.read("/api/upgrade/status",epoch) else null
            val closure = if(authenticated)
                accountOverviewClient.read("/api/account/closure/options",epoch) else null
            val parsed = if(authenticated)
                NativeAccountOverviewModels.read(status.value,
                    upgrade?.value?.takeIf { upgrade.status==200 },
                    closure?.value?.takeIf { closure.status==200 }) else null
            runOnUiThread {
                if(!unlocked || workspace != MailWorkspace.ORDINARY ||
                    accountOverviewGeneration.get()!=generation) return@runOnUiThread
                accountOverviewBusy=false
                accountOverview=parsed
                accountOverviewError=when {
                    !authenticated -> "普通网页登录账号未验证；本机设备授权不等于会员身份。"
                    upgrade?.status != 200 -> "账号已核验；额度服务不可用，未猜测剩余。"
                    else -> ""
                }
            }
        }
    }

    private fun openAccountOverview() {
        if(!unlocked || workspace!=MailWorkspace.ORDINARY) return
        showNativeAccountDetails=true
        refreshAccountOverview()
    }

    @Composable
    private fun NativeAccountDetailDialog() {
        NativeAccountOverviewDialog(
            data=accountOverview,loading=accountOverviewBusy,
            error=accountOverviewError,
            onRefresh=::refreshAccountOverview,
            onDismiss={showNativeAccountDetails=false},
            onClosure={
                showNativeAccountDetails=false
                showNativeClosureInfo=true
            }
        )
    }

    @Composable
    private fun NativeAccountClosureInfo() {
        val current=accountOverview
        AlertDialog(onDismissRequest={showNativeClosureInfo=false},
            title={Text("注销账号 · 二次验证要求")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text("服务器账号："+(current?.closureEmail ?: "未提供"),
                        style=MaterialTheme.typography.bodySmall)
                    Text("可用验证方式："+(current?.closureMethods?.joinToString("、") ?: "未核验"),
                        style=MaterialTheme.typography.bodySmall)
                    Text("冷静期："+
                        (current?.closureCoolingMs?.let { (it/86400000L).toString()+" 天" } ?: "未核验"),
                        style=MaterialTheme.typography.bodySmall)
                    Text("申请后可关闭邮箱、失去套餐与字符奖励，且可能无法用同一邮箱重新注册。须完成密码/2FA、邮箱原文和不可逆确认。此只读页不会提交注销申请。",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.error)
                    Text("完整注销提交需要经过单独审查的原生二次验证流程；当前不借管理员权限执行。",
                        style=MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton={TextButton(onClick={showNativeClosureInfo=false}){Text("返回")}}
        )
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
                    Text("账户详情 ›",
                        modifier=Modifier.clickable { openAccountOverview() },
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick={
                    if (nativeDeviceClient.isBound()) refreshNativeApi()
                    else sendNativeMailboxCommand("refresh")
                }) {
                    Icon(painterResource(R.drawable.ic_refresh),contentDescription="刷新")
                    Text("刷新")
                }
                if (showMailboxes) TextButton(onClick={
                    requestMailboxCreation(snapshot)
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
                LazyColumn(modifier=Modifier.fillMaxSize(),
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(mailboxes,key={it.id}) { mailbox ->
                        Card(modifier=Modifier.fillMaxWidth()
                            .clickable { openNativeMailbox(mailbox.id) },
                            shape=RoundedCornerShape(16.dp),
                            colors=CardDefaults.cardColors(
                                containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.46f))) {
                            Row(modifier=Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                MailAvatar(mailbox.address,38)
                                Spacer(Modifier.padding(horizontal=6.dp))
                                Column(modifier=Modifier.weight(1f),
                                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(mailbox.address,maxLines=1,
                                        style=MaterialTheme.typography.titleSmall)
                                    Text(when {
                                        mailbox.id==snapshot.currentId -> "当前邮箱"
                                        mailbox.isProtected -> "受保护邮箱"
                                        else -> "点击打开"
                                    },style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick={copyMailboxAddress(mailbox.address)}) {
                                    Text("复制")
                                }
                            }
                        }
                    }
                    item {
                        Column(modifier=Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={ requestMailboxCreation(snapshot) },
                                modifier=Modifier.fillMaxWidth()) {
                                Text(if (nativeApiActive) "＋ 网页授权创建邮箱"
                                     else "＋ 创建邮箱")
                            }
                            TextButton(onClick={
                                navigateTo("https://inbox.kango99w.de/?orbit_app=android#orbit-create")
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
                        if (nativeDeviceClient.isBound()) refreshNativeApi()
                        else sendNativeMailboxCommand("refresh")
                    }) {
                        Text("检查新邮件")
                    }
                }
            } else {
                LazyColumn(
                    modifier=Modifier.fillMaxSize(),
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    items(mails,key={it.id},contentType={ "mail" }) { mail ->
                        Card(
                            modifier=Modifier.fillMaxWidth()
                                .clickable { openNativeMessage(mail.id) },
                            shape=RoundedCornerShape(18.dp),
                            colors=CardDefaults.cardColors(
                                containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.46f))
                        ) {
                            Row(modifier=Modifier.fillMaxWidth()
                                .padding(horizontal=14.dp,vertical=14.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                MailAvatar(mail.sender,44)
                                Spacer(Modifier.padding(horizontal=7.dp))
                                Column(modifier=Modifier.weight(1f),
                                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
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
                                    Text("查看正文、验证码与邮件链接",
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›",style=MaterialTheme.typography.titleLarge,
                                    color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeLoginContent() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Text("Orbit Mail", style = MaterialTheme.typography.headlineLarge)
            Text("普通账号登录", style = MaterialTheme.typography.titleLarge)
            Text("这是 Android 原生登录页。密码和动态验证码仅用于本次验证，后续由本机 Keystore 私钥签名读取邮件；不依赖网页登录 Cookie。",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = nativeLoginEmail,
                onValueChange = { nativeLoginEmail = it.take(320) },
                label = { Text("邮箱账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = nativeLoginPassword,
                onValueChange = { nativeLoginPassword = it.take(128) },
                label = { Text("账号密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = nativeLoginTotp,
                onValueChange = { nativeLoginTotp = it.filter(Char::isDigit).take(8) },
                label = { Text("2FA 动态码（仅开启 2FA 时填写）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (nativeLoginError.isNotBlank()) {
                Text(nativeLoginError, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = ::submitNativeLogin,
                enabled = !nativeLoginBusy && nativeLoginEmail.isNotBlank() &&
                    nativeLoginPassword.length >= 8,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (nativeLoginBusy) "正在安全验证…" else "登录并绑定这台设备")
            }
            Text("这里只允许普通账号绑定 account_mail_read 权限，不能取得管理员权限。",
                style = MaterialTheme.typography.labelSmall)
            OutlinedButton(
                enabled = !nativeLoginBusy,
                onClick = {
                    // One-time signup must retain the first-party Turnstile and
                    // invitation-capacity checks; no native bypass is offered.
                    nativeLoginPassword = ""
                    nativeLoginTotp = ""
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://inbox.kango99w.de/?register=1"))
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        nativeLoginError = "设备没有可用浏览器；请手动打开官方注册网站。"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("创建普通账号 · 首次验证注册") }
            Text("注册时会打开官方注册页完成人机验证。完成后返回本软件，使用新账号进行原生登录。",
                style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            OutlinedButton(
                onClick = {
                    nativeLoginPassword = ""
                    nativeLoginTotp = ""
                    nativeLoginError = ""
                    openSection(MailSection.ADMIN)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("进入管理员账号入口（独立认证）") }
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
                    Text("正在使用独立设备授权读取邮件。无需打开网站登录；失败时只重试服务器 API。",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::refreshNativeApi,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (nativeApiLoading) "正在同步…" else "重新同步收件箱")
                    }
                    OutlinedButton(onClick = ::revokeNativeDeviceOnServer,
                        enabled = nativeDeviceClient.isBound() && !nativeApiLoading,
                        modifier = Modifier.fillMaxWidth()) { Text("设备安全解绑") }
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
                        "使用 Android 系统文件选择器，文件仅保留在本机，不会传到网页或服务器。原生加密上传与分享链接正在接线，未上线前不声称传输完成。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { launchNativeDocumentPicker(false) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从手机选择文件") }
                    if (nativePickedFiles.isNotEmpty()) {
                        Text("本机已选择 ${nativePickedFiles.size} 个文件（均未上传）",
                            style = MaterialTheme.typography.titleSmall)
                        nativePickedFiles.forEach { file ->
                            Text(file.displayName +
                                (if(file.sizeBytes >= 0) " · ${file.sizeBytes} B" else " · 大小未知"),
                                style=MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick={nativePickedFiles = emptyList()}) {
                            Text("清空本机选择")
                        }
                    }
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

    private fun connectPrivateMail() {
        if (!unlocked || privateMailBusy || !adminAuthenticated) {
            privateMailError = "请先在管理员页完成独立管理员登录与 2FA。"
            return
        }
        val generation = privateMailGeneration.incrementAndGet()
        val nativeEpoch = privateMailClient.epoch()
        val adminEpoch = nativeAdminClient.requestEpoch()
        privateMailBusy = true
        privateMailError = ""
        privateMailNotice = ""
        privateMailIo.execute {
            val handoff = nativeAdminClient.post("/api/admin/private-mail-handoff",
                JSONObject(), adminEpoch)
            val token = if (handoff.status == 200)
                NativePrivateMailClient.extractHandoffToken(
                    handoff.value?.optString("handoff_url")) else null
            val connection = if (token != null)
                privateMailClient.handoff(token, nativeEpoch)
            else NativePrivateMailClient.Reply(handoff.status,null,
                handoff.error ?: "PRIVATE_HANDOFF_DENIED")
            runOnUiThread {
                if (!unlocked || privateMailGeneration.get() != generation)
                    return@runOnUiThread
                privateMailBusy = false
                val ok = connection.status == 200 &&
                    connection.value?.optBoolean("ok") == true &&
                    privateMailClient.hasLocalSession()
                privateMailReady = ok
                if (ok) {
                    privateMailNotice = "已建立私人发信会话（8 小时）；不使用普通账号权限。"
                } else {
                    privateMailError = "私人发信授权失败：管理员 HTTP ${handoff.status}；" +
                        "私人发信 HTTP ${connection.status} · " +
                        (connection.error ?: "UNKNOWN")
                }
            }
        }
    }

    private fun privateAttachmentPayload(files: List<NativeSelectedFile>): JSONArray {
        require(files.size <= 3) { "附件最多 3 个" }
        val items = JSONArray()
        var total = 0L
        files.forEach { file ->
            val stream = contentResolver.openInputStream(file.uri)
                ?: throw IllegalArgumentException("无法打开附件")
            val data = ByteArrayOutputStream()
            stream.use { input ->
                val chunk = ByteArray(16_384)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > 5L * 1024 * 1024)
                        throw IllegalArgumentException("附件总大小不能超过 5 MiB")
                    data.write(chunk,0,n)
                }
            }
            items.put(JSONObject()
                .put("filename", file.displayName.replace('/', '_')
                    .replace('\\', '_').take(120))
                .put("content", Base64.encodeToString(data.toByteArray(),Base64.NO_WRAP))
                .put("content_type", contentResolver.getType(file.uri)
                    ?: "application/octet-stream"))
        }
        return items
    }

    private fun sendNativePrivateMail() {
        if (!unlocked || privateMailBusy || !privateMailReady) return
        if (privateMailFrom !in NativePrivateMailClient.SENDERS ||
            !NativePrivateMailClient.validEmailList(privateMailTo,true) ||
            !NativePrivateMailClient.validEmailList(privateMailCc) ||
            !NativePrivateMailClient.validEmailList(privateMailBcc) ||
            privateMailSubject.isBlank() || privateMailSubject.length > 200 ||
            privateMailText.isBlank() || privateMailText.length > 20_000) {
            privateMailError = "请核实发件身份、收件人、主题和正文。"
            return
        }
        val schedule = privateMailScheduledAt.trim()
        if (schedule.isNotBlank()) {
            val sendAt = try { java.time.Instant.parse(schedule).toEpochMilli() }
                catch (_:Exception) { 0L }
            val now = System.currentTimeMillis()
            if (sendAt < now + 60_000 || sendAt > now + 30L*24*60*60*1000 ||
                privateMailFiles.isNotEmpty()) {
                privateMailError = "定时发送需输入未来 1 分钟到 30 天内的 UTC ISO 时间；不能同时附加文件。"
                return
            }
        }
        val epoch = privateMailClient.epoch()
        val generation = privateMailGeneration.incrementAndGet()
        val sender = privateMailFrom
        val to = privateMailTo
        val cc = privateMailCc
        val bcc = privateMailBcc
        val subject = privateMailSubject.trim()
        val body = privateMailText
        val requestId = privateMailRequestId
        val files = privateMailFiles.toList()
        privateMailBusy = true
        privateMailError = ""
        privateMailNotice = ""
        privateMailIo.execute {
            var error: String? = null
            var response: NativePrivateMailClient.Reply? = null
            try {
                val json = JSONObject().put("from",sender)
                    .put("to",to).put("cc",cc).put("bcc",bcc)
                    .put("subject",subject).put("text",body)
                    .put("request_id",requestId)
                if (schedule.isNotBlank()) json.put("scheduled_at",schedule)
                if (files.isNotEmpty()) json.put("attachments",privateAttachmentPayload(files))
                response = privateMailClient.sendMessage(json, epoch)
            } catch (e: Exception) {
                error = e.message?.take(160) ?: "附件读取失败"
            }
            runOnUiThread {
                if (!unlocked || privateMailGeneration.get() != generation)
                    return@runOnUiThread
                privateMailBusy = false
                if (response?.status == 200 && response.value?.optBoolean("ok") == true) {
                    privateMailLastScheduledId = if (schedule.isNotBlank())
                        response.value?.optString("id").orEmpty() else ""
                    privateMailNotice = if (schedule.isNotBlank())
                        "服务器已接受定时邮件 · ID: ${privateMailLastScheduledId}"
                        else "发信服务已接受邮件 · ID: " +
                            response.value?.optString("id").orEmpty()
                    privateMailTo = ""
                    privateMailCc = ""
                    privateMailBcc = ""
                    privateMailSubject = ""
                    privateMailText = ""
                    privateMailScheduledAt = ""
                    privateMailFiles = emptyList()
                    privateMailRequestId = UUID.randomUUID().toString()
                } else {
                    if (response?.status == 401) privateMailReady = false
                    // Keep the same idempotency request ID after ambiguous
                    // upstream timeouts to prevent accidental duplicate sends.
                    privateMailError = error ?: "发送未获服务器成功确认：HTTP " +
                        (response?.status ?: "UNKNOWN") + " · " +
                        (response?.error ?: "UNKNOWN")
                }
            }
        }
    }

    private fun cancelNativePrivateScheduled() {
        val id = privateMailLastScheduledId
        if (!unlocked || !privateMailReady || privateMailBusy ||
            id.isBlank()) return
        val generation = privateMailGeneration.incrementAndGet()
        val epoch = privateMailClient.epoch()
        privateMailBusy = true
        privateMailIo.execute {
            val response = privateMailClient.cancelScheduled(id,epoch)
            runOnUiThread {
                if (!unlocked || generation != privateMailGeneration.get())
                    return@runOnUiThread
                privateMailBusy = false
                if (response.status == 200 && response.value?.optBoolean("ok") == true) {
                    privateMailLastScheduledId=""
                    privateMailNotice="定时发信已被服务器确认取消。"
                } else privateMailError="取消失败：HTTP ${response.status}，原任务状态未更改。"
            }
        }
    }

    @Composable
    private fun NativePrivateMailContent() {
        Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=16.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(modifier=Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Column {
                    Text("私人邮箱 · 写信",style=MaterialTheme.typography.headlineSmall)
                    Text("Resend 发信 · 六种已验证发件身份",
                        style=MaterialTheme.typography.labelSmall)
                }
                if(privateMailReady) TextButton(enabled=!privateMailBusy,onClick={
                    privateMailClient.clearLocalSession()
                    privateMailReady=false
                    privateMailNotice="已退出私人发信会话。"
                    privateMailError=""
                }) { Text("退出") }
            }
            if (privateMailError.isNotBlank())
                Text(privateMailError,color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall)
            if (privateMailNotice.isNotBlank())
                Text(privateMailNotice,color=MaterialTheme.colorScheme.primary,
                    style=MaterialTheme.typography.bodySmall)
            if (!privateMailReady) {
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(modifier=Modifier.padding(16.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("连接私人发信",style=MaterialTheme.typography.titleLarge)
                        Text("利用已通过 2FA 的管理员会话申请一次性交接令牌，" +
                            "再建立 mail.kango99w.de 的独立 8 小时发信会话。" +
                            "无需重新输入管理员密码或打开网站。",
                            style=MaterialTheme.typography.bodySmall)
                        Button(
                            enabled=adminAuthenticated && !privateMailBusy,
                            onClick=::connectPrivateMail,
                            modifier=Modifier.fillMaxWidth()
                        ) { Text(if(privateMailBusy) "正在验证…" else "连接私人发信") }
                        if(!adminAuthenticated)
                            OutlinedButton(
                                onClick={openSection(MailSection.ADMIN)},
                                modifier=Modifier.fillMaxWidth()
                            ) { Text("先完成管理员登录 / 2FA") }
                    }
                }
            } else {
                Text("发件人",style=MaterialTheme.typography.labelLarge)
                Box(modifier=Modifier.fillMaxWidth()) {
                    OutlinedButton(enabled=!privateMailBusy,
                        onClick={privateMailSenderDropdown=true},
                        modifier=Modifier.fillMaxWidth()) {
                        Text(privateMailFrom,modifier=Modifier.weight(1f))
                        Text("⌄")
                    }
                    DropdownMenu(expanded=privateMailSenderDropdown,
                        onDismissRequest={privateMailSenderDropdown=false},
                        modifier=Modifier.fillMaxWidth()) {
                        NativePrivateMailClient.SENDERS.forEach { sender ->
                            DropdownMenuItem(text={Text(sender)},onClick={
                                privateMailFrom=sender
                                privateMailSenderDropdown=false
                            })
                        }
                    }
                }
                OutlinedTextField(value=privateMailTo,
                    onValueChange={privateMailTo=it.take(1200)},
                    label={Text("收件人（多个地址用逗号分隔）")},
                    singleLine=false,maxLines=3,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={privateMailExpandCc=!privateMailExpandCc}) {
                    Text(if(privateMailExpandCc) "收起抄送与密送" else "展开 CC / BCC")
                }
                if(privateMailExpandCc) {
                    OutlinedTextField(value=privateMailCc,
                        onValueChange={privateMailCc=it.take(1000)},
                        label={Text("CC 抄送")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=privateMailBcc,
                        onValueChange={privateMailBcc=it.take(1000)},
                        label={Text("BCC 密送")},modifier=Modifier.fillMaxWidth())
                }
                OutlinedTextField(value=privateMailSubject,
                    onValueChange={privateMailSubject=it.take(200)},
                    label={Text("主题")},singleLine=true,
                    modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=privateMailText,
                    onValueChange={privateMailText=it.take(20_000)},
                    label={Text("邮件正文")},minLines=7,maxLines=18,
                    modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(enabled=!privateMailBusy,
                        onClick={launchNativeDocumentPicker(true)}) {
                        Text("添加附件")
                    }
                    Text("最多 3 个 · 共 5 MiB",style=MaterialTheme.typography.labelSmall)
                }
                privateMailFiles.forEach { file ->
                    Row(modifier=Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically) {
                        Text(file.displayName,style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.weight(1f),maxLines=1)
                        TextButton(enabled=!privateMailBusy,
                            onClick={privateMailFiles=
                                privateMailFiles.filterNot { it.uri == file.uri }}) {
                            Text("移除")
                        }
                    }
                }
                OutlinedTextField(value=privateMailScheduledAt,
                    onValueChange={privateMailScheduledAt=it.take(40)},
                    label={Text("定时发送 UTC（选填）")},
                    placeholder={Text("2026-11-10T09:00:00Z")},
                    singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("定时邮件最多提前 30 天；不能附加文件。发送失败保留正文，" +
                    "网络超时重试沿用同一请求 ID。",
                    style=MaterialTheme.typography.labelSmall)
                Button(enabled=!privateMailBusy &&
                    privateMailTo.isNotBlank() && privateMailSubject.isNotBlank() &&
                    privateMailText.isNotBlank(),
                    onClick=::sendNativePrivateMail,
                    modifier=Modifier.fillMaxWidth()) {
                    Text(if(privateMailBusy) "正在提交…" else
                        if(privateMailScheduledAt.isNotBlank()) "安排发送" else "发送邮件")
                }
                if(privateMailLastScheduledId.isNotBlank())
                    OutlinedButton(enabled=!privateMailBusy,
                        onClick=::cancelNativePrivateScheduled) {
                        Text("取消上次定时发送")
                    }
                Text("仅成功提交给发信服务才会显示已接受；最终送达仍以邮件服务商状态为准。",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }

    /** Existing admin HttpOnly session, not the ordinary account device grant. */
    private fun refreshNativeAdmin() {
        if (!unlocked || adminBusy || workspace!=MailWorkspace.ADMIN) return
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        adminIo.execute {
            val status = nativeAdminClient.get("/api/admin/status", requestEpoch)
            val authorized = status.status == 200 &&
                status.value?.optBoolean("authenticated") == true
            // Verify the administrator once; independent read-only endpoints
            // can then be fetched in parallel instead of 4 serial round trips.
            fun asyncGet(path: String): CompletableFuture<NativeAdminClient.Reply>? =
                if (authorized) CompletableFuture.supplyAsync(
                    { nativeAdminClient.get(path, requestEpoch) }, adminReadIo
                ) else null
            val privateFuture = asyncGet("/api/admin/pieterj-mail/mailboxes")
            val ordinaryFuture = asyncGet("/api/admin/ordinary-inbox/mailboxes")
            val tempDomainFuture = asyncGet("/api/admin/ordinary-inbox/domains")
            val managedDomainFuture = asyncGet("/api/admin/pieterj-mail/status")
            fun receive(future: CompletableFuture<NativeAdminClient.Reply>?): NativeAdminClient.Reply? =
                try { future?.get(22, TimeUnit.SECONDS) } catch (_: Exception) { null }
            val privateRows = receive(privateFuture)
            val ordinaryRows = receive(ordinaryFuture)
            val domainRows = receive(tempDomainFuture)
            val managedRows = receive(managedDomainFuture)
            val valid = authorized && privateRows?.status == 200 &&
                ordinaryRows?.status == 200
            val mailboxes = if (valid)
                AdminNativeModels.mailboxes(privateRows?.value, true) +
                    AdminNativeModels.mailboxes(ordinaryRows?.value, false)
                else emptyList()
            val privateOptions = if (managedRows?.status == 200)
                AdminDomainSelection.permanent(managedRows.value) else emptyList()
            val ordinaryOptions = if (domainRows?.status == 200)
                AdminDomainSelection.temporary(domainRows.value) else emptyList()
            runOnUiThread {
                if (!unlocked || workspace!=MailWorkspace.ADMIN ||
                    adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                adminAuthenticated = authorized && privateRows?.status != 401 &&
                    ordinaryRows?.status != 401
                adminUsername = if (adminAuthenticated)
                    status.value?.optString("username").orEmpty().take(32) else ""
                if(adminAuthenticated)
                    workspacePrefs.edit().putString("last_workspace","admin").apply()
                adminMailboxes = mailboxes
                adminPrivateDomainOptions = privateOptions
                adminOrdinaryDomainOptions = ordinaryOptions
                if (!adminAuthenticated) {
                    adminSelectedMailbox = null
                    adminSelectedMessage = null
                    adminSelectedMessageId = null
                    adminMessages = emptyList()
                    // /status returns unauthenticated while a password-verified
                    // admin 2FA challenge is pending. Do not silently reset the
                    // challenge whenever the user reopens the native admin tab.
                    if (status.status != 200) adminTotpRequired = false
                    adminError = if (status.status == 200) ""
                        else "管理员状态校验失败：HTTP ${status.status}；不会调用普通账号接口。"
                } else if (!valid) {
                    adminError = "管理员身份已验证，但邮箱列表暂不可用。请刷新重试。"
                    adminMessages = emptyList()
                    adminSelectedMessage = null
                } else if (adminSelectedMailbox != null &&
                    mailboxes.none { it.id == adminSelectedMailbox?.id &&
                        it.privateMailbox == adminSelectedMailbox?.privateMailbox }) {
                    adminSelectedMailbox = null
                    adminSelectedMessage = null
                    adminSelectedMessageId = null
                    adminMessages = emptyList()
                }
            }
        }
    }

    /** Native first-party administrator username/password, followed by separate 2FA. */
    private fun submitAdminNativeLogin() {
        if (!unlocked || adminBusy) return
        val username = adminSignInUsername.trim()
        val password = adminSignInPassword
        if (!username.matches(Regex("^[A-Za-z0-9_.-]{3,32}$")) ||
            password.length !in 12..128) {
            adminError = "管理员用户名需为 3–32 位；密码至少 12 位。"
            return
        }
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        adminIo.execute {
            val result = nativeAdminClient.post("/api/admin/login",
                JSONObject().put("username", username).put("password", password), requestEpoch)
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                adminSignInPassword = ""
                when {
                    result.status == 200 && result.value?.optBoolean("authenticated") == true -> {
                        adminTotpRequired = false
                        adminTotpCode = ""
                        refreshNativeAdmin()
                    }
                    result.status == 200 && result.value?.optBoolean("totp_required") == true -> {
                        adminTotpRequired = true
                        adminError = "管理员密码已验证，请输入管理员专用 2FA 动态码。"
                    }
                    else -> adminError = "管理员登录未通过（HTTP ${result.status}；${result.error ?: "UNKNOWN"}）。"
                }
            }
        }
    }

    private fun submitAdminNativeTotp() {
        if (!unlocked || adminBusy || !adminTotpRequired) return
        val code = adminTotpCode.trim()
        if (!code.matches(Regex("^[0-9]{6}$"))) {
            adminError = "请输入当前 6 位管理员 2FA 动态码。"
            return
        }
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        adminIo.execute {
            val result = nativeAdminClient.post("/api/admin/login/totp",
                JSONObject().put("code", code).put("trust_device", false), requestEpoch)
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                adminTotpCode = ""
                if (result.status == 200 &&
                    result.value?.optBoolean("authenticated") == true) {
                    adminTotpRequired = false
                    refreshNativeAdmin()
                } else {
                    adminError = "管理员 2FA 未通过（HTTP ${result.status}；${result.error ?: "UNKNOWN"}）。"
                    if (result.error == "ADMIN_TOTP_CHALLENGE_EXPIRED")
                        adminTotpRequired = false
                }
            }
        }
    }

    private fun openNativeAdminMailbox(mailbox: AdminNativeMailbox) {
        if (!unlocked || !adminAuthenticated || adminBusy ||
            !AdminNativeModels.validId(mailbox.id) || mailbox.status != "active") return
        adminSelectedMailbox = mailbox
        adminSelectedMessage = null
        adminSelectedMessageId = null
        adminMessages = emptyList()
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        val path = if (mailbox.privateMailbox)
            "/api/admin/pieterj-mail/mailboxes/${mailbox.id}/messages"
        else "/api/admin/ordinary-inbox/mailboxes/${mailbox.id}/messages"
        adminIo.execute {
            val response = nativeAdminClient.get(path, requestEpoch)
            val rows = if (response.status == 200) AdminNativeModels.messages(response.value)
                else emptyList()
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                if (response.status == 401) {
                    adminAuthenticated = false
                    adminMailboxes = emptyList()
                    adminSelectedMailbox = null
                } else if (response.status == 200) adminMessages = rows
                else adminError = "管理员邮件列表读取失败：HTTP ${response.status}"
            }
        }
    }

    private fun openNativeAdminMessage(id: String) {
        val mailbox = adminSelectedMailbox ?: return
        if (!unlocked || !adminAuthenticated ||
            !AdminNativeModels.validId(id) ||
            adminMessages.none { it.id == id }) return
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminMessageLoading = true
        adminSelectedMessageId = id
        adminSelectedMessage = null
        adminError = ""
        val path = if (mailbox.privateMailbox) "/api/admin/pieterj-mail/messages/$id"
        else "/api/admin/ordinary-inbox/mailboxes/${mailbox.id}/messages/$id"
        adminReadIo.execute {
            val response = nativeAdminClient.get(path, requestEpoch)
            val detail = if (response.status == 200)
                AdminNativeModels.detail(response.value)?.takeIf { it.id == id } else null
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation ||
                    adminSelectedMessageId != id ||
                    adminSelectedMailbox?.id != mailbox.id ||
                    adminSelectedMailbox?.privateMailbox != mailbox.privateMailbox)
                    return@runOnUiThread
                adminMessageLoading = false
                if (response.status == 401) {
                    adminAuthenticated = false
                    adminMailboxes = emptyList()
                    adminMessages = emptyList()
                    adminSelectedMailbox = null
                    adminSelectedMessageId = null
                } else if (detail != null) adminSelectedMessage = detail
                else adminError = "管理员邮件正文读取失败：HTTP ${response.status}"
            }
        }
    }

    private fun createNativeAdminMailbox() {
        if (!unlocked || !adminAuthenticated || adminBusy) return
        val local = adminCreateLocal.trim()
        val domain = adminCreateDomain.trim().lowercase(java.util.Locale.ROOT)
        val allowed = if (adminCreatePrivate) adminPrivateDomainOptions
            else adminOrdinaryDomainOptions
        if (!local.matches(Regex("^[a-zA-Z0-9._-]{0,64}$")) ||
            allowed.none { it.domain == domain }) {
            adminError = "邮箱前缀或域名格式无效。"
            return
        }
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        val privateMailbox = adminCreatePrivate
        val path = if (privateMailbox) "/api/admin/pieterj-mail/mailboxes"
            else "/api/admin/ordinary-inbox/mailboxes"
        val payload = JSONObject().put("domain",domain)
        if (local.isNotBlank())
            payload.put(if (privateMailbox) "local_part" else "local",local)
        adminIo.execute {
            val response = nativeAdminClient.post(path,payload, requestEpoch)
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                if (response.status == 201 &&
                    response.value?.optJSONObject("mailbox") != null) {
                    showAdminCreateDialog = false
                    adminCreateLocal = ""
                    adminSelectedMailbox = null
                    adminSelectedMessage = null
                    adminMessages = emptyList()
                    refreshNativeAdmin()
                } else adminError = "管理员创建邮箱失败（HTTP ${response.status}；${response.error ?: "UNKNOWN"}）。"
            }
        }
    }

    private fun logoutNativeAdmin() {
        if (!unlocked || adminBusy || !adminAuthenticated) return
        val generation = adminGeneration.incrementAndGet()
        val requestEpoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminIo.execute {
            val response = nativeAdminClient.post("/api/admin/logout", JSONObject(), requestEpoch)
            runOnUiThread {
                if (!unlocked || adminGeneration.get() != generation) return@runOnUiThread
                adminBusy = false
                if (response.status == 200) {
                    adminAuthenticated = false
                    adminUsername = ""
                    adminMailboxes = emptyList()
                    adminMessages = emptyList()
                    adminSelectedMailbox = null
                    adminSelectedMessage = null
                    adminSelectedMessageId = null
                    adminPane = "mailboxes"
                    adminGlyphGeneration.incrementAndGet()
                    adminGlyphSnapshot=null
                    adminGlyphBuyTarget=null
                    adminGlyphBusy=false
                    adminManagedDomains = emptyList()
                    adminRecordedAddresses = emptyList()
                    adminSecurityState = null
        adminInventoryDomains = emptyList()
        adminInventoryStatus = ""
        adminSelectedDomain = null
        adminRegisteredAccounts = emptyList()
        adminSelectedRegistration = null
                    adminDeleteTarget = null
                    adminRevokeTrustedTarget = null
                    adminTotpRequired = false
                    adminRegPassword = ""
                    adminRegTotp = ""
                    adminRegPolicy = null
                    adminRegMessage = ""
                    showAdminRegConfirm = false
                    adminError = "已退出管理员会话。普通账号授权未更改。"
                    adminLoadedAt.clear()
                    workspacePrefs.edit().putString("last_workspace","ordinary").apply()
                    switchWorkspace(MailWorkspace.ORDINARY)
                } else adminError = "管理员退出失败：HTTP ${response.status}，未声称退出成功。"
            }
        }
    }

    private fun refreshAdminGlyphs() {
        if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
            adminPane!="glyphs" || adminGlyphBusy) return
        val generation=adminGlyphGeneration.incrementAndGet()
        val epoch=nativeAdminClient.requestEpoch()
        adminGlyphBusy=true
        adminGlyphError=""
        adminReadIo.execute {
            val res=nativeAdminClient.get("/api/admin/lucky-glyphs/status",epoch)
            val parsed=if(res.status==200 && res.value!=null)
                try { NativeGlyphModels.read(res.value) } catch (_:Exception) { null }
                else null
            runOnUiThread {
                if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
                    adminPane!="glyphs" || adminGlyphGeneration.get()!=generation)
                    return@runOnUiThread
                adminGlyphBusy=false
                adminGlyphSnapshot=parsed
                adminGlyphError=if(parsed!=null) "" else
                    if(res.status in setOf(404,503))
                        "管理员字符服务尚未上线或暂不可用；不会显示虚假库存。"
                    else "字符读取失败 HTTP "+res.status+" · "+(res.error ?: "UNKNOWN")
            }
        }
    }

    private fun mutateAdminGlyph(action: String, id: String?=null) {
        if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
            adminPane!="glyphs" || adminGlyphBusy) return
        val path=when(action) {
            "checkin" -> "/api/admin/lucky-glyphs/checkin"
            "draw" -> "/api/admin/lucky-glyphs/draw"
            "equip" -> "/api/admin/lucky-glyphs/equip"
            "buy" -> "/api/admin/lucky-glyphs/shop/buy"
            else -> return
        }
        if(id!=null && !NativeGlyphModels.validId(id)) return
        val body=when(action) {
            "draw" -> JSONObject().put("idempotency_key",adminGlyphDrawId)
            "equip","buy" -> JSONObject().put("glyph_id",id ?: return)
            else -> JSONObject()
        }
        val generation=adminGlyphGeneration.incrementAndGet()
        val epoch=nativeAdminClient.requestEpoch()
        adminGlyphBusy=true
        adminGlyphError=""
        adminReadIo.execute {
            val res=nativeAdminClient.post(path,body,epoch)
            val parsed=if(res.status in 200..299 && res.value!=null)
                try { NativeGlyphModels.read(res.value) } catch (_:Exception) { null }
                else null
            runOnUiThread {
                if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
                    adminPane!="glyphs" || adminGlyphGeneration.get()!=generation)
                    return@runOnUiThread
                adminGlyphBusy=false
                if(parsed!=null) {
                    adminGlyphSnapshot=parsed
                    if(action=="draw")adminGlyphDrawId=UUID.randomUUID().toString()
                } else adminGlyphError="服务器未确认 "+action+
                    "：HTTP "+res.status+"，"+(res.error ?: "UNKNOWN")
            }
        }
    }

    private fun expandAdminRegistrationHistory() {
        if(!unlocked || !adminAuthenticated || adminBusy ||
            adminRegistrationFetchLimit>=500 ||
            (adminPane!="users" && (adminPane!="records" || adminLedgerTab!="internal")))return
        adminRegistrationFetchLimit=500
        openAdminNativePane(adminPane,force=true)
    }

    private fun selectAdminLedgerTab(tab:String) {
        if(tab !in setOf("internal","external","reserved") || adminBusy ||
            !unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN)return
        if(adminLedgerTab==tab)return
        adminLedgerTab=tab
        openAdminNativePane("records")
    }

    private fun loadAdminExternalDetail(item:AdminExternalEmail) {
        if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
            adminPane!="records" || adminLedgerTab!="external" ||
            !AdminExternalEmailModels.validId(item.id) || adminExternalDetailBusy)return
        val generation=adminExternalDetailGeneration.incrementAndGet()
        val epoch=nativeAdminClient.requestEpoch()
        adminSelectedExternalEmail=item
        adminSelectedExternalDetail=null
        adminExternalDetailError=""
        adminExternalDetailBusy=true
        adminReadIo.execute {
            val result=nativeAdminClient.get("/api/admin/external-emails/"+item.id,epoch)
            val details=if(result.status==200)
                AdminExternalEmailModels.detail(result.value,item.id) else null
            runOnUiThread {
                if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
                    adminPane!="records" || adminLedgerTab!="external" ||
                    adminExternalDetailGeneration.get()!=generation ||
                    adminSelectedExternalEmail?.id!=item.id)return@runOnUiThread
                adminExternalDetailBusy=false
                adminSelectedExternalDetail=details
                adminExternalDetailError=if(details==null)
                    "外部邮箱详情未核验（HTTP "+result.status+"）" else ""
                if(result.status==401) {
                    adminAuthenticated=false
                    adminLoadedAt.clear()
                    adminSelectedExternalEmail=null
                    adminSelectedExternalDetail=null
                }
            }
        }
    }

    private fun openAdminNativePane(pane: String,force:Boolean=false) {
        if (!unlocked || !adminAuthenticated || adminBusy ||
            pane !in setOf("mailboxes", "domains", "records", "security", "users", "registration", "glyphs")) return
        val cacheKey=if(pane=="records")"records:"+adminLedgerTab else pane
        val recent=adminLoadedAt[cacheKey]?.let {
            val elapsed=SystemClock.elapsedRealtime()-it
            elapsed in 0..60_000L
        }==true
        adminPane = pane
        adminSelectedDomain = null
        adminSelectedRegistration = null
        adminSelectedExternalEmail = null
        adminSelectedExternalDetail = null
        adminExternalDetailGeneration.incrementAndGet()
        adminExternalDetailBusy=false
        adminSelectedMailbox = null
        adminSelectedMessageId = null
        adminSelectedMessage = null
        adminMessageLoading = false
        adminMessages = emptyList()
        adminError = ""
        // A fresh admin pane must never reuse a step-up secret from another pane.
        if (pane != "registration") {
            adminRegPassword = ""
            adminRegTotp = ""
            showAdminRegConfirm = false
        } else if (!recent || force) {
            // Do not display an old cached policy as current when its refresh fails.
            adminRegPolicy = null
            adminRegMessage = ""
        }
        if(recent && !force && pane !in setOf("mailboxes","glyphs"))return
        if (pane == "mailboxes") {
            refreshNativeAdmin()
            return
        }
        if(pane=="glyphs") {
            refreshAdminGlyphs()
            return
        }
        val generation = adminGeneration.incrementAndGet()
        val epoch = nativeAdminClient.requestEpoch()
        val recordTab=adminLedgerTab
        adminBusy = true
        if(pane=="domains") {
            adminInventoryStatus="正在读取独立域名托管库存…"
            adminInventoryDomains=emptyList()
            adminReadIo.execute {
                val reply=nativeAdminClient.get("/api/admin/domain-control/inventory", epoch)
                val records=if(reply.status==200)
                    AdminDomainInventoryModels.parse(reply.value) else emptyList()
                runOnUiThread {
                    if(!unlocked || !adminAuthenticated ||
                        generation!=adminGeneration.get() || adminPane!="domains")
                        return@runOnUiThread
                    adminInventoryDomains=records
                    adminInventoryStatus=when(reply.status) {
                        200 -> "托管库存已读取："+records.size+" 条 · 服务商标签不代表验证通过"
                        403 -> "该管理员没有托管库存的读取权限（HTTP 403）"
                        503 -> "独立托管库存未配置或暂不可用（HTTP 503）"
                        else -> "托管库存读取失败（HTTP "+reply.status+"）；其他域名记录保留"
                    }
                }
            }
        }
        adminIo.execute {
            val primary: NativeAdminClient.Reply
            val secondary: NativeAdminClient.Reply?
            when (pane) {
                "domains" -> {
                    primary = nativeAdminClient.get("/api/admin/pieterj-mail/status", epoch)
                    secondary = nativeAdminClient.get("/api/admin/ordinary-inbox/domains", epoch)
                }
                "records" -> {
                    val route=when(recordTab) {
                        "internal"->"/api/admin/account-registry/registrations?limit="+
                            adminRegistrationFetchLimit
                        "external"->"/api/admin/external-emails"
                        else->"/api/admin/ordinary-inbox/recorded-addresses"
                    }
                    primary = nativeAdminClient.get(route, epoch)
                    secondary = null
                }
                "registration" -> {
                    primary = nativeAdminClient.get("/api/admin/registration-control",epoch)
                    secondary = null
                }
                "users" -> {
                    primary = nativeAdminClient.get(
                        "/api/admin/account-registry/registrations?limit="+
                            adminRegistrationFetchLimit, epoch)
                    secondary = null
                }
                else -> {
                    primary = nativeAdminClient.get("/api/admin/2fa/status", epoch)
                    secondary = nativeAdminClient.get("/api/admin/trusted-devices", epoch)
                }
            }
            val valid = primary.status == 200 &&
                (secondary == null || secondary.status == 200)
            val domains = if (pane == "domains" && valid)
                AdminManagementModels.domains(primary.value, secondary?.value) else emptyList()
            val records = if (pane == "records" && recordTab=="reserved" && valid)
                AdminManagementModels.recordedAddresses(primary.value) else emptyList()
            val external = if (pane == "records" && recordTab=="external" && valid)
                AdminExternalEmailModels.list(primary.value) else emptyList()
            val recordRegistrations=if(pane=="records" && recordTab=="internal" && valid)
                AdminRegistrationModels.parse(primary.value) else emptyList()
            val security = if (pane == "security" && valid)
                AdminManagementModels.security(primary.value, secondary?.value) else null
            val registrations = if (pane == "users" && valid)
                AdminRegistrationModels.parse(primary.value) else emptyList()
            runOnUiThread {
                if (!unlocked || generation != adminGeneration.get() || adminPane != pane ||
                    (pane=="records"&&recordTab!=adminLedgerTab))
                    return@runOnUiThread
                adminBusy = false
                if (primary.status == 401 || secondary?.status == 401) {
                    adminAuthenticated = false
                    adminRegPassword = ""
                    adminRegTotp = ""
                    adminRegPolicy = null
                    showAdminRegConfirm = false
                    adminLoadedAt.clear()
                    adminExternalEmails=emptyList()
                    adminSelectedExternalEmail=null
                    adminSelectedExternalDetail=null
                    adminMailboxes = emptyList()
                    adminManagedDomains = emptyList()
                    adminRecordedAddresses = emptyList()
                    adminSecurityState = null
        adminInventoryDomains = emptyList()
        adminInventoryStatus = ""
        adminSelectedDomain = null
        adminRegisteredAccounts = emptyList()
        adminSelectedRegistration = null
                    adminPane = "mailboxes"
                    adminDeleteTarget = null
                    adminRevokeTrustedTarget = null
                    adminError = "管理员会话已过期，需要独立管理员验证。"
                    return@runOnUiThread
                }
                if (!valid) {
                    if (pane == "registration") {
                        adminRegPolicy = null
                        adminRegPassword = ""
                        adminRegTotp = ""
                        showAdminRegConfirm = false
                        adminError = when(primary.status) {
                            503 -> "注册控制服务端尚未上线或数据库尚未迁移（HTTP 503）。本机不会修改注册状态。"
                            403 -> "服务器拒绝读取注册控制（HTTP 403）；需要有效管理员权限。"
                            else -> "注册控制服务器读取失败（HTTP ${primary.status} / ${primary.error ?: "UNKNOWN"}）；未修改注册状态。"
                        }
                    } else {
                        adminError = "原生管理员${when(pane){"domains"->"域名";"records"->"记录";"users"->"登记账户";else->"安全"}}数据加载失败，HTTP ${primary.status}/${secondary?.status ?: "-"}；未回退网页。"
                    }
                    return@runOnUiThread
                }
                when (pane) {
                    "domains" -> adminManagedDomains = domains
                    "records" -> when(recordTab) {
                        "external" -> adminExternalEmails = external
                        "reserved" -> adminRecordedAddresses = records
                        else -> adminRegisteredAccounts = recordRegistrations
                    }
                    "users" -> adminRegisteredAccounts = registrations
                    "registration" -> {
                        adminRegPolicy=primary.value
                        adminRegMode=primary.value?.optString("mode","open") ?: "open"
                        val start=primary.value?.optLong("start_at",0L) ?: 0L
                        adminRegStart=if(start>0L) try {
                            java.time.Instant.ofEpochMilli(start)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                        }catch (_: Exception){""} else ""
                        adminRegOpen=(primary.value?.optInt("open_minutes",60) ?: 60)
                            .takeIf { it>0 }?.toString() ?: "60"
                        adminRegClosed=(primary.value?.optInt("closed_minutes",1380) ?: 1380)
                            .takeIf { it>0 }?.toString() ?: "1380"
                        adminRegQuota=if(primary.value?.isNull("max_accounts") == false)
                            primary.value?.optInt("max_accounts",0)?.takeIf{it>0}?.toString() ?: ""
                            else ""
                        adminRegMessage=""
                    }
                    else -> adminSecurityState = security
                }
                adminLoadedAt[cacheKey]=SystemClock.elapsedRealtime()
            }
        }
    }

    private fun updateNativeAdminMailbox(mailbox: AdminNativeMailbox, mode: String) {
        if (!unlocked || !adminAuthenticated || adminBusy ||
            !AdminNativeModels.validId(mailbox.id) ||
            adminSelectedMailbox?.id != mailbox.id) return
        if (mode !in setOf("star", "renew")) return
        if ((mode == "star") != mailbox.privateMailbox) return
        val generation = adminGeneration.incrementAndGet()
        val epoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        adminIo.execute {
            val reply = if (mode == "star") nativeAdminClient.patch(
                "/api/admin/pieterj-mail/mailboxes/${mailbox.id}",
                JSONObject().put("is_starred", !mailbox.starred), epoch
            ) else nativeAdminClient.post(
                "/api/admin/ordinary-inbox/mailboxes/${mailbox.id}/renew",
                JSONObject().put("minutes", 30), epoch
            )
            runOnUiThread {
                if (!unlocked || generation != adminGeneration.get()) return@runOnUiThread
                adminBusy = false
                if (reply.status == 200 && reply.value?.optJSONObject("mailbox") != null) {
                    adminSelectedMailbox = null
                    adminSelectedMessageId = null
                    adminSelectedMessage = null
                    adminMessages = emptyList()
                    refreshNativeAdmin()
                } else adminError = "邮箱管理操作失败：HTTP ${reply.status} / ${reply.error ?: "UNKNOWN"}"
            }
        }
    }

    private fun deleteNativeAdminMailbox() {
        val target = adminDeleteTarget ?: return
        if (!unlocked || !adminAuthenticated || adminBusy ||
            !AdminNativeModels.validId(target.id) ||
            adminMailboxes.none { it.id == target.id && it.privateMailbox == target.privateMailbox }) return
        val generation = adminGeneration.incrementAndGet()
        val epoch = nativeAdminClient.requestEpoch()
        val route = if (target.privateMailbox)
            "/api/admin/pieterj-mail/mailboxes/${target.id}" else
            "/api/admin/ordinary-inbox/mailboxes/${target.id}"
        adminBusy = true
        adminDeleteError = ""
        adminIo.execute {
            val reply = nativeAdminClient.delete(route, epoch)
            runOnUiThread {
                if (!unlocked || generation != adminGeneration.get()) return@runOnUiThread
                adminBusy = false
                if (reply.status == 200 && reply.value?.optBoolean("deleted") == true) {
                    adminDeleteTarget = null
                    adminSelectedMailbox = null
                    adminSelectedMessageId = null
                    adminSelectedMessage = null
                    adminMessages = emptyList()
                    refreshNativeAdmin()
                } else {
                    adminDeleteError = if (reply.value?.optBoolean("cleanup_pending") == true)
                        "服务器尚未完成邮箱路由清理。请稍后核实，不能声称删除成功。"
                    else "删除未获服务器确认：HTTP ${reply.status} / ${reply.error ?: "UNKNOWN"}"
                }
            }
        }
    }

    private fun revokeNativeAdminTrustedDevice() {
        val target = adminRevokeTrustedTarget ?: return
        if (!unlocked || !adminAuthenticated || adminBusy || adminPane != "security" ||
            !AdminNativeModels.validId(target.id) ||
            adminSecurityState?.trustedDevices?.none { it.id == target.id } != false) return
        val generation = adminGeneration.incrementAndGet()
        val epoch = nativeAdminClient.requestEpoch()
        adminBusy = true
        adminError = ""
        adminIo.execute {
            val reply = nativeAdminClient.post(
                "/api/admin/trusted-devices/${target.id}/revoke", JSONObject(), epoch)
            runOnUiThread {
                if (!unlocked || generation != adminGeneration.get()) return@runOnUiThread
                adminBusy = false
                if (reply.status == 200 && reply.value?.optBoolean("revoked") == true) {
                    adminRevokeTrustedTarget = null
                    openAdminNativePane("security")
                } else adminError = "可信设备撤销失败（HTTP ${reply.status} / ${reply.error ?: "UNKNOWN"}）；不会声称已撤销。"
            }
        }
    }

    @Composable
    private fun NativeAdminTrustedRevokeDialog() {
        val target = adminRevokeTrustedTarget ?: return
        AlertDialog(
            onDismissRequest = {
                if (!adminBusy) adminRevokeTrustedTarget = null
            },
            title = { Text("撤销管理员可信设备？") },
            text = {
                Text("设备：${target.label.ifBlank{"未命名设备"}}。撤销后该设备将无法继续凭可信设备令牌跳过 2FA。此操作不修改普通账号设备授权。")
            },
            confirmButton = {
                Button(enabled = !adminBusy, onClick = ::revokeNativeAdminTrustedDevice) {
                    Text(if(adminBusy) "正在等待服务器…" else "确认撤销")
                }
            },
            dismissButton = {
                TextButton(enabled = !adminBusy, onClick = {
                    adminRevokeTrustedTarget = null
                }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun AdminDomainDetailsDialog() {
        val item=adminSelectedDomain ?: return
        val inventory=adminInventoryDomains.firstOrNull { it.domain==item.domain }
        AlertDialog(
            onDismissRequest={adminSelectedDomain=null},
            title={Text("域名详情 · "+item.domain,maxLines=2)},
            text={
                Column(
                    modifier=Modifier.heightIn(max=420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(9.dp)
                ) {
                    Text("归并来源："+item.sources.joinToString(" / "))
                    Text("优先来源："+item.source+" · "+
                        (if(item.source=="托管库存")"证据状态：" else "登记状态：")+item.state)
                    Text("来源重复只算一个域名；网站绑定、邮件配置、申请记录均不等于已证实拥有。",
                        style=MaterialTheme.typography.bodySmall)
                    if(item.ingress.isNotBlank())Text("收件接入："+item.ingress)
                    if(inventory==null) {
                        Text("此域名来自邮箱或网站登记数据；独立托管库存未提供可用元数据。",
                            style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text("注册商："+inventory.registrar.ifBlank{"未登记"})
                        Text("DNS 服务商："+inventory.dnsProvider.ifBlank{"未登记"})
                        Text("网站托管："+inventory.websiteHost.ifBlank{"未登记"})
                        Text("邮箱服务商："+inventory.emailProvider.ifBlank{"未登记"})
                        Text("证据来源："+inventory.evidenceSource.ifBlank{"未知"})
                        Text("检测状态："+inventory.evidenceStatus.ifBlank{"未验证"})
                        if(inventory.pagesStatus.isNotBlank())
                            Text("Pages 状态："+inventory.pagesStatus)
                        if(inventory.zoneStatus.isNotBlank())
                            Text("DNS 区域状态："+inventory.zoneStatus)
                        Text("历史收件配置标记："+if(inventory.mailConfigured)"是" else "否")
                        if(inventory.evidenceNote.isNotBlank())
                            Text("检测说明："+inventory.evidenceNote)
                        if(inventory.notes.isNotBlank()) {
                            Text("登记备注",style=MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(inventory.notes) }
                        }
                        Text("服务商、配置标记及人工备注均非当前 DNS、注册权或真实收件验证；这里不会修改 DNS。",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton={TextButton(onClick={
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("域名",item.domain))
            }){Text("复制域名")}},
            dismissButton={TextButton(onClick={adminSelectedDomain=null}){Text("关闭")}}
        )
    }

    @Composable
    private fun AdminRegistrationDetailDialog() {
        val entry=adminSelectedRegistration ?: return
        AlertDialog(
            onDismissRequest={adminSelectedRegistration=null},
            title={Text("账户登记详情")},
            text={
                Column(
                    modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(9.dp)
                ) {
                    Text(entry.service.ifBlank{"未命名服务"},
                        style=MaterialTheme.typography.titleLarge)
                    Text("账户标签："+entry.accountLabel.ifBlank{"未设置"})
                    SelectionContainer { Text("关联邮箱："+entry.address) }
                    Text("邮箱类型："+if(entry.source=="pieterj")"固定邮箱" else "临时邮箱")
                    Text("状态："+entry.status+" · 阶段："+entry.lifecycle)
                    if(entry.registeredAt>0) Text("登记时间："+formatNativeMessageTime(entry.registeredAt),
                        style=MaterialTheme.typography.bodySmall)
                    if(entry.reviewedAt>0) Text("最近复核："+formatNativeMessageTime(entry.reviewedAt),
                        style=MaterialTheme.typography.bodySmall)
                    if(entry.note.isNotBlank()) {
                        Text("登记备注",style=MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(entry.note) }
                    }
                    Text("本页是已登记账户信息；平台用户总数、密码、令牌及恢复码不会在此展示。",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton={
                TextButton(onClick={
                    val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("登记邮箱",entry.address))
                }) { Text("复制邮箱") }
            },
            dismissButton={TextButton(onClick={adminSelectedRegistration=null}) { Text("关闭") }}
        )
    }


    private fun submitAdminRegistrationControl() {
        if (!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
            adminPane!="registration" || adminBusy || adminRegPolicy==null) return
        val revision=adminRegPolicy?.optInt("revision",0) ?: 0
        if(revision<=0 || adminRegPassword.isBlank()) {
            adminRegMessage="需先读取服务器配置，并输入当前管理员密码。"
            return
        }
        val mode=adminRegMode
        if(mode !in setOf("open","closed","once","cycle","pause_once"))return
        val timed=mode=="once" || mode=="cycle" || mode=="pause_once"
        val start=if(timed) try {
            java.time.LocalDateTime.parse(adminRegStart.trim(),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { -1L } else 0L
        val minutes=if(timed && mode!="pause_once")adminRegOpen.toIntOrNull() ?: -1 else 0
        val closed=if(mode=="cycle" || mode=="pause_once")
            adminRegClosed.toIntOrNull() ?: -1 else 0
        val quota=if(mode=="closed"||adminRegQuota.isBlank())null else adminRegQuota.toIntOrNull()
        if((timed&&(start<1577836800000L ||
            (mode!="pause_once"&&minutes !in 1..43200) ||
            ((mode=="cycle"||mode=="pause_once")&&closed !in 1..43200))) ||
            (mode!="closed" && adminRegQuota.isNotBlank() && (quota==null || quota !in 1..10000))) {
            adminRegMessage="时间、开放/关闭时长或名额无效，请检查。"
            return
        }
        val body=JSONObject().put("revision",revision).put("mode",mode)
            .put("start_at",if(timed)start else JSONObject.NULL)
            .put("open_minutes",minutes).put("closed_minutes",closed)
            .put("max_accounts",quota ?: JSONObject.NULL)
            .put("current_password",adminRegPassword)
            .put("current_totp",adminRegTotp)
        adminBusy=true
        adminRegMessage="正在验证并更新服务端注册策略…"
        val generation=adminGeneration.incrementAndGet()
        val epoch=nativeAdminClient.requestEpoch()
        adminIo.execute {
            val result=nativeAdminClient.post("/api/admin/registration-control",body,epoch)
            runOnUiThread {
                if(!unlocked || !adminAuthenticated || workspace!=MailWorkspace.ADMIN ||
                    generation!=adminGeneration.get())return@runOnUiThread
                adminBusy=false
                adminRegPassword=""
                adminRegTotp=""
                if(result.status==200 && result.value!=null) {
                    adminRegPolicy=result.value
                    adminRegMessage="服务器已保存。网页和 APK 共用本规则。"
                    adminLoadedAt.remove("registration")
                } else {
                    adminRegMessage="注册规则未保存（HTTP " + result.status +
                        " / "+(result.error?:"UNKNOWN")+"）；如其他设备修改过规则，请刷新。"
                    if(result.status==401) {
                        adminAuthenticated=false
                        adminRegPolicy=null
                        showAdminRegConfirm=false
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeRegistrationControlContent() {
        Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("新用户注册 · 统一服务端规则",
                style=MaterialTheme.typography.titleLarge)
            Text("仅影响新用户注册，包括邀请码与自助注册。已有账号的登录、邮箱和历史数据保持不变。",
                style=MaterialTheme.typography.bodySmall)
            val policy=adminRegPolicy
            if(policy==null) {
                Text("暂未取得服务器规则。可能尚未部署或迁移。",
                    style=MaterialTheme.typography.bodyMedium)
                if(adminError.isNotBlank())Text(adminError,
                    color=MaterialTheme.colorScheme.error)
                return@Column
            }
            Text(if(policy.optBoolean("open"))"当前：开放中" else "当前：已暂停",
                style=MaterialTheme.typography.titleMedium)
            Text("本轮已注册："+policy.optInt("used")+
                " · 剩余："+if(policy.isNull("remaining"))"不设额外上限"
                        else policy.optInt("remaining").toString(),
                style=MaterialTheme.typography.bodySmall)
            val next=policy.optLong("next_transition_at",0L)
            if(next>0L)Text("下次自动切换："+formatNativeMessageTime(next),
                style=MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(enabled=!adminBusy,
                    onClick={adminRegMenuOpen=true}) {
                    Text(when(adminRegMode){
                        "closed"->"立即暂停（手动恢复）"
                        "once"->"指定时间开放一次"
                        "pause_once"->"指定时间关闭，到期自动开放"
                        "cycle"->"周期性开放与关闭"
                        else->"持续开放"
                    }+" ▾")
                }
                DropdownMenu(expanded=adminRegMenuOpen,
                    onDismissRequest={adminRegMenuOpen=false}) {
                    listOf(
                        "open" to "持续开放",
                        "closed" to "立即暂停（手动恢复）",
                        "once" to "定时开放一次",
                        "pause_once" to "定时关闭一次，到期自动开放",
                        "cycle" to "周期开放/关闭"
                    ).forEach { item ->
                        DropdownMenuItem(text={Text(item.second)},
                            onClick={adminRegMode=item.first;adminRegMenuOpen=false})
                    }
                }
            }
            if(adminRegMode=="once"||adminRegMode=="cycle"||
                adminRegMode=="pause_once"){
                OutlinedTextField(value=adminRegStart,
                    onValueChange={adminRegStart=it.take(16)},
                    label={Text(if(adminRegMode=="pause_once")
                        "计划关闭时间（本机时区）" else "首次开放时间（本机时区）")},
                    placeholder={Text("2026-11-09T09:00")},
                    singleLine=true,modifier=Modifier.fillMaxWidth())
                if(adminRegMode!="pause_once"){
                    OutlinedTextField(value=adminRegOpen,
                        onValueChange={adminRegOpen=it.filter(Char::isDigit).take(5)},
                        label={Text("开放分钟数（1–43200）")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                if(adminRegMode=="cycle"||adminRegMode=="pause_once") {
                    OutlinedTextField(value=adminRegClosed,
                        onValueChange={adminRegClosed=it.filter(Char::isDigit).take(5)},
                        label={Text("每次关闭分钟数（1–43200）")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                }
            }
            if(adminRegMode!="closed"){
                OutlinedTextField(value=adminRegQuota,
                    onValueChange={adminRegQuota=it.filter(Char::isDigit).take(5)},
                    label={Text("每轮开放名额（1–10000，留空不额外限制）")},
                    singleLine=true,modifier=Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            OutlinedTextField(value=adminRegPassword,
                onValueChange={adminRegPassword=it.take(128)},
                label={Text("管理员当前密码 · 必填")},
                visualTransformation=PasswordVisualTransformation(),
                singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=adminRegTotp,
                onValueChange={adminRegTotp=it.filter(Char::isDigit).take(6)},
                label={Text("管理员 2FA（已启用时必填）")},
                singleLine=true,modifier=Modifier.fillMaxWidth())
            if(adminRegMessage.isNotBlank())Text(adminRegMessage,
                style=MaterialTheme.typography.bodySmall)
            Button(enabled=!adminBusy && adminRegPassword.isNotBlank(),
                onClick={showAdminRegConfirm=true},
                modifier=Modifier.fillMaxWidth()) {
                Text(if(adminBusy)"服务器验证中…" else "验证并保存规则")
            }
            Text("时间由服务器计算，不依赖 APK 保持运行。名额仅是额外控制，现有全站上限与风控仍然有效。",
                style=MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun NativeAdminManagementContent() {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openAdminNativePane("mailboxes") }) {
                    Text("‹ 邮箱")
                }
                Text(when(adminPane) {
                    "domains" -> "域名管理"
                    "records" -> "邮箱使用记录"
                    "users" -> "用户与登记详情"
                    "registration" -> "新用户注册控制"
                    "glyphs" -> "管理员字符"
                    else -> "管理员安全中心"
                }, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                TextButton(enabled = !adminBusy, onClick = {
                    openAdminNativePane(adminPane,force=true)
                }) {
                    Text("刷新")
                }
            }
            HorizontalDivider()
            when (adminPane) {
                "domains" -> AdminDomainManagement(
                    rows=AdminDomainInventoryModels.combine(
                        adminManagedDomains,adminInventoryDomains),
                    inventoryStatus=adminInventoryStatus,
                    inventory=adminInventoryDomains,
                    onSelect={adminSelectedDomain=it},
                    search=adminDomainSearch,setSearch={adminDomainSearch=it},
                    source=adminDomainSourceFilter,setSource={adminDomainSourceFilter=it},
                    state=adminDomainStateFilter,setState={adminDomainStateFilter=it},
                    expanded=adminDomainFiltersExpanded,
                    onExpand={adminDomainFiltersExpanded=!adminDomainFiltersExpanded},
                    busy=adminBusy
                )
                "records" -> Column(modifier=Modifier.fillMaxSize()) {
                    AdminLedgerTabStrip(selected=adminLedgerTab,onSelect=::selectAdminLedgerTab)
                    Box(modifier=Modifier.weight(1f)) {
                        when(adminLedgerTab) {
                            "external" -> AdminExternalEmailLedger(
                                rows=adminExternalEmails,busy=adminBusy,
                                onSelect=::loadAdminExternalDetail)
                            "reserved" -> AdminAddressRecords(
                                rows=adminRecordedAddresses,
                                search=adminRecordSearch,setSearch={adminRecordSearch=it},
                                source=adminRecordSourceFilter,setSource={adminRecordSourceFilter=it},
                                state=adminRecordStateFilter,setState={adminRecordStateFilter=it},
                                expanded=adminRecordFiltersExpanded,
                                onExpand={adminRecordFiltersExpanded=!adminRecordFiltersExpanded},
                                busy=adminBusy)
                            else -> AdminAccountRegistrations(
                                rows=adminRegisteredAccounts,admin=adminUsername,
                                search=adminRegistrationSearch,
                                setSearch={adminRegistrationSearch=it},
                                source=adminRegistrationSourceFilter,
                                setSource={adminRegistrationSourceFilter=it},
                                expanded=adminRegistrationFiltersExpanded,
                                onExpand={adminRegistrationFiltersExpanded=!adminRegistrationFiltersExpanded},
                                busy=adminBusy,onSelect={adminSelectedRegistration=it},
                                maxFetched=adminRegistrationFetchLimit,
                                onExpandHistory=::expandAdminRegistrationHistory)
                        }
                    }
                }
                "glyphs" -> NativeAdminGlyphGallery(
                    state=adminGlyphSnapshot,busy=adminGlyphBusy,error=adminGlyphError,
                    onRefresh=::refreshAdminGlyphs,
                    onCheckin={mutateAdminGlyph("checkin")},
                    onDraw={mutateAdminGlyph("draw")},
                    onEquip={mutateAdminGlyph("equip",it)},
                    onBuy={adminGlyphBuyTarget=it}
                )
                "registration" -> NativeRegistrationControlContent()
                "users" -> AdminAccountRegistrations(
                    rows=adminRegisteredAccounts,
                    admin=adminUsername,
                    search=adminRegistrationSearch,setSearch={adminRegistrationSearch=it},
                    source=adminRegistrationSourceFilter,setSource={adminRegistrationSourceFilter=it},
                    expanded=adminRegistrationFiltersExpanded,
                    onExpand={adminRegistrationFiltersExpanded=!adminRegistrationFiltersExpanded},
                    busy=adminBusy,onSelect={adminSelectedRegistration=it},
                    maxFetched=adminRegistrationFetchLimit,
                    onExpandHistory=::expandAdminRegistrationHistory
                )
                else -> {
                    val state = adminSecurityState
                    Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("管理员安全状态",style=MaterialTheme.typography.titleLarge)
                        if (state == null) Text("尚未取得安全中心数据；不会猜测配置。")
                        else {
                            Text("双重验证：" + if (state.totpEnabled) "已启用" else "未启用")
                            Text("剩余恢复码数量：${state.recoveryCodesRemaining}")
                            Text("可信设备数量：${state.trustedDevices.size}",
                                style=MaterialTheme.typography.titleMedium)
                            state.trustedDevices.forEach { device ->
                                Card(modifier=Modifier.fillMaxWidth()) {
                                    Column(modifier=Modifier.padding(12.dp)) {
                                        Text(device.label.ifBlank{"未命名设备"})
                                        Text("有效期：" + formatNativeMessageTime(device.expiresAt),
                                            style=MaterialTheme.typography.labelSmall)
                                        OutlinedButton(enabled=!adminBusy,
                                            onClick={adminRevokeTrustedTarget=device}) {
                                            Text("撤销此设备")
                                        }
                                    }
                                }
                            }
                            Text("2FA 重置、恢复码重签、设备撤销属于高风险操作。" +
                                "需在原生界面加入独立密码/验证码二次确认后才开放，当前不提供误导性快捷按钮。",
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeAdminContent() {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("管理员邮箱",style=MaterialTheme.typography.titleMedium)
                    Text(if(adminAuthenticated)
                        "已验证 · "+adminUsername.ifBlank{"管理员"}
                        else "独立管理员登录 · "+BuildConfig.VERSION_NAME,
                        modifier=Modifier.clickable(enabled=adminAuthenticated) {
                            openAdminNativePane("users")
                        },maxLines=1,
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.primary)
                }
                if(adminAuthenticated) {
                    TextButton(enabled=!adminBusy,onClick=::refreshNativeAdmin) {
                        Text("刷新")
                    }
                    Box {
                        TextButton(enabled=!adminBusy,
                            onClick={adminQuickMenuOpen=true}){Text("更多 ▾")}
                        DropdownMenu(expanded=adminQuickMenuOpen,
                            onDismissRequest={adminQuickMenuOpen=false}) {
                            DropdownMenuItem(text={Text("字符图鉴")},
                                onClick={adminQuickMenuOpen=false;openAdminNativePane("glyphs")})
                            DropdownMenuItem(text={Text("邮箱使用记录")},
                                onClick={adminQuickMenuOpen=false;openAdminNativePane("records")})
                            DropdownMenuItem(text={Text("域名健康与库存")},
                                onClick={adminQuickMenuOpen=false;openAdminNativePane("domains")})
                            DropdownMenuItem(text={Text("用户与登记详情")},
                                onClick={adminQuickMenuOpen=false;openAdminNativePane("users")})
                            DropdownMenuItem(text={Text("新用户注册规则")},
                                onClick={adminQuickMenuOpen=false;openAdminNativePane("registration")})
                            DropdownMenuItem(text={Text("退出管理员")},
                                onClick={adminQuickMenuOpen=false;logoutNativeAdmin()})
                        }
                    }
                }
            }
            HorizontalDivider()
            if (adminError.isNotBlank()) {
                Text(adminError.take(240),
                    modifier=Modifier.fillMaxWidth().padding(14.dp),
                    color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall)
            }
            if (!adminAuthenticated) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (adminTotpRequired) "验证管理员 2FA" else "管理员原生登录",
                        style=MaterialTheme.typography.titleLarge)
                    Text("这里直接请求管理员专用 API，不打开网站。普通账号密码和原生设备授权不能用作管理员登录。",
                        style=MaterialTheme.typography.bodyMedium)
                    if (adminTotpRequired) {
                        OutlinedTextField(
                            value=adminTotpCode,
                            onValueChange={adminTotpCode=it.filter(Char::isDigit).take(6)},
                            label={Text("管理员动态验证码")},
                            singleLine=true,modifier=Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled=!adminBusy && adminTotpCode.length==6,
                            onClick=::submitAdminNativeTotp,
                            modifier=Modifier.fillMaxWidth()
                        ) { Text(if (adminBusy) "正在验证…" else "验证并进入管理中心") }
                        TextButton(onClick={
                            adminTotpRequired=false
                            adminTotpCode=""
                            adminError=""
                        }) { Text("返回管理员密码登录") }
                    } else {
                        OutlinedTextField(
                            value=adminSignInUsername,
                            onValueChange={adminSignInUsername=it.take(32)},
                            label={Text("管理员用户名")},
                            singleLine=true,modifier=Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value=adminSignInPassword,
                            onValueChange={adminSignInPassword=it.take(128)},
                            label={Text("管理员密码")},
                            visualTransformation=PasswordVisualTransformation(),
                            singleLine=true,modifier=Modifier.fillMaxWidth()
                        )
                        Button(enabled=!adminBusy && adminSignInPassword.isNotBlank(),
                            onClick=::submitAdminNativeLogin,
                            modifier=Modifier.fillMaxWidth()) {
                            Text(if (adminBusy) "正在验证…" else "原生管理员登录")
                        }
                        TextButton(enabled=!adminBusy,onClick=::refreshNativeAdmin) {
                            Text("检测当前已登录的管理员会话")
                        }
                    }
                    Text("管理员邮箱与管理功能在 Android 原生界面执行。" +
                        "登录和 2FA 由独立的管理员 API 验证，绝不改用普通账号设备凭据。",
                        style=MaterialTheme.typography.bodySmall)
                }
                return@Column
            }
            // Administrator modules belong to the role-specific bottom bar.
            HorizontalDivider()
            if (adminPane != "mailboxes") {
                NativeAdminManagementContent()
                return@Column
            }
            val selected = adminSelectedMailbox
            val detail = adminSelectedMessage
            if (selected != null) {
                Row(modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={
                        if (adminSelectedMessageId != null) {
                            adminGeneration.incrementAndGet()
                            adminMessageLoading=false
                            adminSelectedMessageId = null
                            adminSelectedMessage = null
                        } else {
                            adminSelectedMailbox = null
                            adminMessages = emptyList()
                        }
                        adminError=""
                    }) { Text("‹ 返回") }
                    Column(modifier=Modifier.weight(1f)) {
                        Text(selected.address.take(38),
                            maxLines=1,style=MaterialTheme.typography.titleSmall)
                        Text(if (selected.privateMailbox) "管理员固定邮箱" else "管理员临时邮箱",
                            style=MaterialTheme.typography.labelSmall)
                    }
                    if (adminBusy) Text("加载中…",style=MaterialTheme.typography.labelSmall)
                }
                Row(modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),
                    horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    if (selected.privateMailbox) {
                        OutlinedButton(enabled=!adminBusy,
                            onClick={updateNativeAdminMailbox(selected,"star")}) {
                            Text(if(selected.starred) "取消标星" else "标星")
                        }
                    } else {
                        OutlinedButton(enabled=!adminBusy,
                            onClick={updateNativeAdminMailbox(selected,"renew")}) {
                            Text("续期30分钟")
                        }
                    }
                    OutlinedButton(enabled=!adminBusy,
                        onClick={
                            adminDeleteError=""
                            adminDeleteTarget=selected
                        }) { Text("删除邮箱") }
                }
                HorizontalDivider()
                if (detail != null && adminSelectedMessageId == detail.id) {
                    Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(detail.subject.ifBlank{"（无主题）"},
                            style=MaterialTheme.typography.titleLarge)
                        Text("发件人：${detail.sender}",
                            style=MaterialTheme.typography.bodyMedium)
                        Text("收件人：${detail.recipient}",
                            style=MaterialTheme.typography.bodySmall)
                        Text(formatNativeMessageTime(detail.time),
                            style=MaterialTheme.typography.labelSmall)
                        HorizontalDivider()
                        if (detail.possibleCode.matches(Regex("^[A-Za-z0-9-]{4,16}$")))
                            Card(modifier=Modifier.fillMaxWidth()) {
                                Column(modifier=Modifier.padding(14.dp)) {
                                    Text("可能的验证码",style=MaterialTheme.typography.labelSmall)
                                    SelectionContainer {
                                        Text(detail.possibleCode,
                                            style=MaterialTheme.typography.headlineMedium)
                                    }
                                }
                            }
                        NativeMailReadingContent(
                            messageKey="admin:"+selected.id+":"+detail.id,
                            plainText=detail.text.ifBlank{detail.htmlText},
                            htmlBody=detail.htmlBody,
                            safeLinks=detail.safeLinks,
                            possibleCode=detail.possibleCode
                        )
                    }
                } else if (adminSelectedMessageId != null) {
                    Column(modifier=Modifier.padding(20.dp)) {
                        if (adminMessageLoading)
                            LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                        Text(if (adminMessageLoading) "正在读取管理员邮件…" else "邮件暂不可读取",
                            style=MaterialTheme.typography.titleMedium)
                        if (!adminMessageLoading) TextButton(onClick={
                            adminGeneration.incrementAndGet()
                            adminSelectedMessageId=null
                            adminSelectedMessage=null
                        }) { Text("返回邮件列表") }
                    }
                } else {
                    val query=adminSearchQuery.trim()
                    val messages=if (query.isBlank()) adminMessages else
                        adminMessages.filter { it.subject.contains(query,ignoreCase=true) ||
                            it.sender.contains(query,ignoreCase=true) }
                    OutlinedTextField(value=adminSearchQuery,
                        onValueChange={adminSearchQuery=it.take(70)},
                        label={Text("搜索发件人或主题")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp))
                    Text("邮件 · ${messages.size}",
                        modifier=Modifier.padding(horizontal=16.dp,vertical=5.dp),
                        style=MaterialTheme.typography.labelLarge)
                    if (messages.isEmpty() && !adminBusy)
                        Text("暂无可显示邮件。",modifier=Modifier.padding(20.dp))
                    LazyColumn(
                        modifier=Modifier.fillMaxSize(),
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages,key={it.id},contentType={ "mail" }) { item ->
                            Card(
                                modifier=Modifier.fillMaxWidth()
                                    .clickable(enabled=adminAuthenticated) {
                                        openNativeAdminMessage(item.id)
                                    },
                                shape=RoundedCornerShape(18.dp),
                                colors=CardDefaults.cardColors(
                                    containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.46f))
                            ) {
                                Row(modifier=Modifier.fillMaxWidth()
                                    .padding(horizontal=14.dp,vertical=14.dp),
                                    verticalAlignment=Alignment.CenterVertically) {
                                    MailAvatar(item.sender,44)
                                    Spacer(Modifier.padding(horizontal=7.dp))
                                    Column(modifier=Modifier.weight(1f),
                                        verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            Text(item.sender.ifBlank{"未知发件人"},maxLines=1,
                                                style=if(item.read) MaterialTheme.typography.bodyMedium
                                                else MaterialTheme.typography.titleSmall,
                                                modifier=Modifier.weight(1f))
                                            if(!item.read) {
                                                Box(Modifier.size(8.dp)
                                                    .background(MaterialTheme.colorScheme.primary,CircleShape))
                                            }
                                        }
                                        Text(item.subject.ifBlank{"（无主题）"},maxLines=2,
                                            style=MaterialTheme.typography.bodyMedium)
                                        Text(formatNativeMessageTime(item.time),
                                            style=MaterialTheme.typography.labelSmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.padding(horizontal=4.dp))
                                    Text("›",style=MaterialTheme.typography.titleLarge,
                                        color=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=14.dp,vertical=8.dp),
                    horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically) {
                    Text("邮箱列表 · ${adminMailboxes.size}",
                        style=MaterialTheme.typography.titleMedium)
                    Button(enabled=!adminBusy,onClick={
                        adminCreateLocal=""
                        adminCreatePrivate=true
                        adminCreateDomain=adminPrivateDomainOptions.firstOrNull()?.domain.orEmpty()
                        adminCreateDomainQuery=""
                        adminCreateDomainExpanded=false
                        showAdminCreateDialog=true
                    }) { Text("＋ 新建邮箱") }
                }
                OutlinedTextField(
                    value=adminSearchQuery,
                    onValueChange={adminSearchQuery=it.take(70)},
                    label={Text("搜索邮箱地址")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
                )
                AdminFilterFold(
                    label="管理员邮箱筛选",expanded=adminMailboxFiltersExpanded,
                    activeSummary="类型："+adminMailboxFilter,
                    onToggle={adminMailboxFiltersExpanded=!adminMailboxFiltersExpanded}
                ) {
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("全部","固定邮箱","临时邮箱").forEach { scope ->
                            FilterChip(selected=adminMailboxFilter==scope,
                                onClick={adminMailboxFilter=scope},
                                label={Text(scope,maxLines=1)})
                        }
                    }
                }
                val query=adminSearchQuery.trim()
                val boxes=adminMailboxes.filter { mailbox ->
                    (query.isBlank() || mailbox.address.contains(query,ignoreCase=true)) &&
                        (adminMailboxFilter=="全部" ||
                            (adminMailboxFilter=="固定邮箱" && mailbox.privateMailbox) ||
                            (adminMailboxFilter=="临时邮箱" && !mailbox.privateMailbox))
                }
                Text("匹配邮箱 ${boxes.size} / ${adminMailboxes.size}",
                    modifier=Modifier.padding(horizontal=16.dp,vertical=5.dp),
                    style=MaterialTheme.typography.labelSmall)
                if (boxes.isEmpty() && !adminBusy) Text(
                    "暂无管理员邮箱，或尚未获得列表权限。",
                    modifier=Modifier.padding(20.dp))
                LazyColumn(modifier=Modifier.weight(1f)) {
                    items(boxes,key={"${if(it.privateMailbox) "p" else "o"}:${it.id}"}) { box ->
                        Row(modifier=Modifier.fillMaxWidth()
                            .clickable(enabled=!adminBusy && box.status=="active") {
                                openNativeAdminMailbox(box)
                            }
                            .padding(horizontal=16.dp,vertical=12.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            MailAvatar(box.address)
                            Spacer(Modifier.padding(horizontal=8.dp))
                            Column(modifier=Modifier.weight(1f)) {
                                Text(box.address,maxLines=1,
                                    style=MaterialTheme.typography.titleSmall)
                                Text((if(box.privateMailbox) "固定邮箱" else "临时邮箱") +
                                    " · " + (if(box.status=="active") "已启用" else box.status),
                                    style=MaterialTheme.typography.labelSmall)
                            }
                            if (box.unread > 0) Text("${box.unread} 未读",
                                style=MaterialTheme.typography.labelMedium)
                            Text("  ›")
                        }
                        HorizontalDivider()
                    }
                }

            }
        }
    }

    @Composable
    private fun NativeAdminDeleteDialog() {
        val target = adminDeleteTarget ?: return
        AlertDialog(
            onDismissRequest = {
                if (!adminBusy) {
                    adminDeleteTarget = null
                    adminDeleteError = ""
                }
            },
            title = { Text("确认删除管理员邮箱？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.address, style=MaterialTheme.typography.titleMedium)
                    Text(if (target.privateMailbox)
                        "该固定邮箱及其已存邮件可能永久删除，此操作不可撤销。"
                    else "该临时邮箱将解除管理员归属并清理收信路由。已使用的域名和地址记录不代表可恢复。")
                    if (adminDeleteError.isNotBlank())
                        Text(adminDeleteError, color=MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled=!adminBusy, onClick=::deleteNativeAdminMailbox) {
                    Text(if(adminBusy) "正在等待服务器确认…" else "确认永久删除")
                }
            },
            dismissButton = {
                TextButton(enabled=!adminBusy, onClick={
                    adminDeleteTarget=null
                    adminDeleteError=""
                }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun NativeAdminCreateDialog() {
        val options = if (adminCreatePrivate) adminPrivateDomainOptions
            else adminOrdinaryDomainOptions
        val displayed = AdminDomainSelection.filter(options, adminCreateDomainQuery)
        AlertDialog(
            onDismissRequest={ if(!adminBusy) {
                showAdminCreateDialog=false
                adminCreateDomainExpanded=false
            }},
            title={ Text("新建邮箱", style=MaterialTheme.typography.titleLarge) },
            text={
                Column(modifier=Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("选择用途", style=MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled=!adminBusy,onClick={
                            adminCreatePrivate=true
                            adminCreateDomain=adminPrivateDomainOptions.firstOrNull()?.domain.orEmpty()
                            adminCreateDomainQuery=""
                            adminCreateDomainExpanded=false
                        }) { Text(if(adminCreatePrivate) "✓ 固定邮箱" else "固定邮箱") }
                        OutlinedButton(enabled=!adminBusy,onClick={
                            adminCreatePrivate=false
                            adminCreateDomain=adminOrdinaryDomainOptions.firstOrNull()?.domain.orEmpty()
                            adminCreateDomainQuery=""
                            adminCreateDomainExpanded=false
                        }) { Text(if(!adminCreatePrivate) "✓ 临时邮箱" else "临时邮箱") }
                    }
                    Text(if(adminCreatePrivate) "使用已启用的管理员专用收信域名。"
                        else "使用服务器登记的临时邮箱域名。",
                        style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value=adminCreateLocal,
                        onValueChange={adminCreateLocal=it.take(64)},
                        label={Text("邮箱前缀（留空随机生成）")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("选择邮箱域名 · ${options.size} 个",
                        style=MaterialTheme.typography.labelMedium)
                    Box(modifier=Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier=Modifier.fillMaxWidth(),
                            enabled=options.isNotEmpty() && !adminBusy,
                            onClick={adminCreateDomainExpanded=true}
                        ) {
                            Text(adminCreateDomain.ifBlank{"暂无已验证域名"},
                                maxLines=1,modifier=Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_more),contentDescription="展开域名")
                        }
                        DropdownMenu(
                            expanded=adminCreateDomainExpanded,
                            onDismissRequest={adminCreateDomainExpanded=false},
                            modifier=Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(value=adminCreateDomainQuery,
                                onValueChange={adminCreateDomainQuery=it.take(60)},
                                label={Text("搜索或筛选域名")},
                                singleLine=true,
                                modifier=Modifier.padding(horizontal=10.dp))
                            if(displayed.isEmpty()) {
                                Text("未找到符合条件的域名",
                                    modifier=Modifier.padding(14.dp))
                            }
                            displayed.forEach { option ->
                                DropdownMenuItem(
                                    text={
                                        Column {
                                            Text(option.domain)
                                            Text(option.category + " · " +
                                                option.ingress.ifBlank{"已登记"},
                                                style=MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                    onClick={
                                        adminCreateDomain=option.domain
                                        adminCreateDomainExpanded=false
                                        adminCreateDomainQuery=""
                                    }
                                )
                            }
                        }
                    }
                    if(options.isEmpty()) Text(
                        "没有可创建的域名。需要先在服务端完成域名验证和接入，不能通过手填绕过。",
                        color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall)
                    Text("创建前核对域名。只有服务器确认创建成功后才会刷新邮箱列表。",
                        style=MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton={
                Button(enabled=!adminBusy &&
                    options.any{it.domain==adminCreateDomain},
                    onClick=::createNativeAdminMailbox) {
                    Text(if(adminBusy) "创建中…" else "创建邮箱")
                }
            },
            dismissButton={
                TextButton(enabled=!adminBusy,onClick={
                    showAdminCreateDialog=false
                    adminCreateDomainExpanded=false
                }) { Text("取消") }
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
            if(workspace==MailWorkspace.ORDINARY)
                OutlinedButton(onClick=::openAccountOverview,
                    modifier=Modifier.fillMaxWidth()) {
                    Text("账号详情 · 会员额度 · 注销")
                }
            Card(modifier=Modifier.fillMaxWidth(),
                colors=CardDefaults.cardColors(
                    containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.54f))) {
                Column(modifier=Modifier.padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("外观与阅读",style=MaterialTheme.typography.titleMedium)
                    Text("主题仅影响 Orbit Mail 原生界面；随时切换，重新打开也会保留。",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(
                        listOf(OrbitAppearanceMode.SYSTEM,OrbitAppearanceMode.DAY),
                        listOf(OrbitAppearanceMode.NIGHT,OrbitAppearanceMode.SOFT)
                    ).forEach { pair ->
                        Row(modifier=Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            pair.forEach { mode ->
                                FilterChip(
                                    selected=appearanceMode==mode,
                                    onClick={chooseAppearanceMode(mode)},
                                    label={Text(mode.title)},
                                    modifier=Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Text(appearanceMode.description,
                        style=MaterialTheme.typography.labelMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前安装的软件版本", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text("Orbit Mail " + BuildConfig.VERSION_NAME +
                            "（Build " + BuildConfig.VERSION_CODE + "）",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("包名：" + BuildConfig.APPLICATION_ID,
                        style = MaterialTheme.typography.bodySmall)
                    Text("原生界面：Android Jetpack Compose。点击底部“管理员”应显示“管理员邮箱”原生页面，" +
                        "不会自动加载网站。只有手动选择标注“网页”的高级功能才会进入网站。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("如果手机显示的版本号低于最新 GitHub APK，说明安装没有更新成功。" +
                        "不同 CI Debug 签名可能无法覆盖安装；卸载旧版会清除旧版应用本地数据和会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Text("管理员登录、2FA 与邮箱阅读使用原生客户端；高级安全设置和恢复码仍由受保护网站负责。",
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
                    if (!nativeDeviceClient.isBound()) {
                        OutlinedButton(onClick = {
                            showNativeMail = true
                            openSection(MailSection.INBOX)
                        }) { Text("原生账号登录 · 不打开网页") }
                    }
                    if (nativeDeviceClient.isBound()) {
                        OutlinedButton(
                            enabled = !nativeApiLoading,
                            onClick = ::revokeNativeDeviceOnServer
                        ) { Text(if (nativeApiLoading) "正在撤销…" else "安全解绑 · 撤销服务器授权") }
                        OutlinedButton(onClick = {
                            nativeRequestGeneration.incrementAndGet()
                            nativeOfflineCache.clear()
                            nativeLastSuccessfulSyncMs=0L
                            nativeDeviceClient.clearLocalBinding()
                            setPushOptIn(false)
                            nativeApiActive = false
                            nativeInbox = null
                            backgroundPushStatus = "本机通知已关闭；服务器设备登记仍须单独撤销。"
                            message = "仅本机密钥与通知许可已清除；须在网站设备管理撤销服务器授权。"
                        }) { Text("只清除本机设备凭据") }
                    }
                    OutlinedButton(onClick = {
                        openSection(MailSection.ADMIN)
                        if (adminAuthenticated) openAdminNativePane("security")
                    }) {
                        Icon(painterResource(R.drawable.ic_shield), contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("管理员 2FA / 可信设备（原生）")
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
                        OutlinedButton(onClick = ::revokeBackgroundPush) { Text("撤销后台推送授权") }
                    } else if (backgroundPushRevokePending) {
                        Text("服务器撤销未确认前，不会自动重新登记。若服务端使用 FCM 通知消息，仍可能由系统直接显示，必须完成服务端撤销。",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = ::retryServerPushRevocation) {
                            Text("重试服务端撤销")
                        }
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
            Text("Orbit Mail · Android 测试版。私人发信需独立授权；后台收信与推送尚未完成生产验收。",
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
                workspaceSwitcherExpanded -> workspaceSwitcherExpanded = false
                activeSection == MailSection.SETTINGS -> openSection(previousWebSection)
                (activeSection == MailSection.INBOX || activeSection == MailSection.MAILBOXES) && showNativeMail -> {
                    if (pendingNativeMessageId != null || nativeMailDetail != null) {
                        pendingNativeMessageId=null
                        nativeMailDetail=null
                    } else if (activeSection == MailSection.MAILBOXES) openSection(MailSection.INBOX)
                    else lock()
                }
                activeSection == MailSection.ADMIN && adminPane != "mailboxes" -> {
                    openAdminNativePane("mailboxes")
                }
                activeSection == MailSection.ADMIN && adminSelectedMessageId != null &&
                    showNativeToolsPanel -> {
                    adminSelectedMessageId = null
                    adminSelectedMessage = null
                }
                activeSection == MailSection.ADMIN && adminSelectedMailbox != null &&
                    showNativeToolsPanel -> {
                    adminSelectedMailbox = null
                    adminMessages = emptyList()
                }
                (activeSection == MailSection.ADMIN || activeSection == MailSection.TRANSFER) &&
                    !showNativeToolsPanel -> {
                    showNativeToolsPanel = true
                    if (activeSection == MailSection.ADMIN) refreshNativeAdmin()
                }
                (activeSection == MailSection.ADMIN || activeSection == MailSection.TRANSFER ||
                    activeSection == MailSection.PRIVATE) &&
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
                    Text("将清除本 APK 中所有网站页面（含管理员）的 Cookie 和网站存储。"
                        + "普通账号的 Android Keystore 原生设备授权保留，不会重新绑定。"
                        + "网站服务器会话与可信设备授权并未撤销；需要时请在各自安全中心单独撤销。")
                },
                confirmButton = {
                    TextButton(onClick = ::clearLocalSession) { Text("清除并锁定") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                }
            )
        }
        if(adminGlyphBuyTarget!=null && adminAuthenticated &&
            workspace==MailWorkspace.ADMIN) {
            val item=adminGlyphBuyTarget!!
            AlertDialog(onDismissRequest={adminGlyphBuyTarget=null},
                title={Text("确认兑换管理员字符？")},
                text={Text(item.name+" · 消耗 "+item.price+
                    " 积分；成功与否以管理员服务器为准。")},
                confirmButton={TextButton(enabled=!adminGlyphBusy,onClick={
                    adminGlyphBuyTarget=null
                    mutateAdminGlyph("buy",item.id)
                }) {Text("确认兑换")}},
                dismissButton={TextButton(onClick={adminGlyphBuyTarget=null}){Text("取消")}})
        }
        if (showTools) MailToolsDialog()
        if (showNativePairDialog) NativePairDialog()
        if (showAdminCreateDialog && adminAuthenticated) NativeAdminCreateDialog()
        if (adminSelectedDomain != null && adminAuthenticated && workspace==MailWorkspace.ADMIN)
            AdminDomainDetailsDialog()
        if (adminSelectedRegistration != null && adminAuthenticated && workspace==MailWorkspace.ADMIN)
            AdminRegistrationDetailDialog()
        if (adminSelectedExternalEmail != null && adminAuthenticated &&
            workspace==MailWorkspace.ADMIN && adminPane=="records" &&
            adminLedgerTab=="external") {
            val selected=adminSelectedExternalEmail!!
            AdminExternalEmailDetailDialog(
                selected=selected,detail=adminSelectedExternalDetail,
                loading=adminExternalDetailBusy,error=adminExternalDetailError,
                onDismiss={
                    adminExternalDetailGeneration.incrementAndGet()
                    adminSelectedExternalEmail=null
                    adminSelectedExternalDetail=null
                    adminExternalDetailBusy=false
                },
                onRetry={
                    if(!adminExternalDetailBusy)loadAdminExternalDetail(selected)
                })
        }
        if (adminDeleteTarget != null && adminAuthenticated) NativeAdminDeleteDialog()
        if (showAdminRegConfirm && adminAuthenticated && workspace==MailWorkspace.ADMIN)
            AlertDialog(onDismissRequest={showAdminRegConfirm=false},
                title={Text("确认更改全站注册规则？")},
                text={Text("变更会同时影响网页和 APK 的新用户注册。旧账号登录、收信不变。必须通过管理员密码与已启用的 2FA 验证。")},
                confirmButton={Button(onClick={
                    showAdminRegConfirm=false
                    submitAdminRegistrationControl()
                }){Text("确认保存")}},
                dismissButton={TextButton(onClick={showAdminRegConfirm=false}){Text("取消")}})

        if (adminRevokeTrustedTarget != null && adminAuthenticated) NativeAdminTrustedRevokeDialog()
        if(glyphBuyTarget!=null && workspace==MailWorkspace.ORDINARY)
            NativeGlyphBuyDialog()
        if (showCreateMailbox && nativeInbox != null) NativeCreateMailboxDialog(nativeInbox!!)
        if (showHumanVerificationPrompt && workspace==MailWorkspace.ORDINARY)
            HumanVerificationDialog()
        if (showNativeAccountDetails && workspace == MailWorkspace.ORDINARY)
            NativeAccountDetailDialog()
        if (showNativeClosureInfo && workspace == MailWorkspace.ORDINARY)
            NativeAccountClosureInfo()
        Scaffold(
            topBar = {
                val nativeMailTab = (activeSection == MailSection.INBOX ||
                    activeSection == MailSection.MAILBOXES) && showNativeMail
                val nativeToolsTab = (activeSection == MailSection.TRANSFER ||
                    activeSection == MailSection.DECOR ||
                    activeSection == MailSection.ADMIN ||
                    activeSection == MailSection.PRIVATE) && showNativeToolsPanel
                val nativeTab = nativeMailTab || nativeToolsTab ||
                    activeSection == MailSection.SETTINGS
                Surface(shadowElevation=2.dp) {
                    Column(modifier=Modifier.fillMaxWidth()) {
                        Row(modifier=Modifier.fillMaxWidth()
                            .padding(horizontal=14.dp,vertical=6.dp),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically) {
                            Row(verticalAlignment=Alignment.CenterVertically,
                                modifier=Modifier.weight(1f)) {
                                Box(modifier=Modifier.size(36.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer,CircleShape),
                                    contentAlignment=Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_mail),
                                        contentDescription=null,
                                        tint=MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.padding(horizontal=5.dp))
                                Column {
                                    Text("Orbit Mail",style=MaterialTheme.typography.titleMedium)
                                    Text(if(nativeTab)"原生邮箱 · 隐私优先" else "网页登录 · 独立授权",
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick={openSection(MailSection.SETTINGS)}) {
                                Icon(painterResource(R.drawable.ic_settings),
                                    contentDescription="应用设置")
                            }
                            TextButton(onClick=::lock) { Text("锁定") }
                        }
                        // A compact, collapsed-by-default workspace selector keeps the inbox tall.
                        // It is UI-only: neither account's credentials nor session are shared.
                        Surface(
                            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.55f),
                            shape=RoundedCornerShape(16.dp)
                        ) {
                        Row(modifier=Modifier.fillMaxWidth()
                            .padding(horizontal=12.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            TextButton(
                                onClick={workspaceSwitcherExpanded = !workspaceSwitcherExpanded}
                            ) {
                                Text((if(workspace==MailWorkspace.ORDINARY) "普通邮箱" else "管理员") +
                                    (if(workspaceSwitcherExpanded) "  ▴ 收起" else "  ▾ 切换"),
                                    maxLines=1)
                            }
                            Text(
                                if(workspace==MailWorkspace.ORDINARY)
                                    (if(nativeDeviceClient.isBound())"本机已绑定"
                                    else "需要登录")
                                else (if(adminAuthenticated)"管理员已验证"
                                    else "需要独立验证"),
                                modifier=Modifier.weight(1f).padding(start=8.dp),
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines=1
                            )
                        }
                        }
                        if(workspaceSwitcherExpanded) {
                            Row(modifier=Modifier.fillMaxWidth()
                                .padding(horizontal=12.dp,vertical=2.dp),
                                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={switchWorkspace(MailWorkspace.ORDINARY)},
                                    modifier=Modifier.weight(1f)) {
                                    Text((if(workspace==MailWorkspace.ORDINARY)"✓ " else "")+
                                        "普通邮箱",maxLines=1)
                                }
                                OutlinedButton(onClick={switchWorkspace(MailWorkspace.ADMIN)},
                                    modifier=Modifier.weight(1f)) {
                                    Text((if(workspace==MailWorkspace.ADMIN)"✓ " else "")+
                                        "管理员",maxLines=1)
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    if(workspace==MailWorkspace.ORDINARY) {
                        WorkspaceNavigation.ordinaryTabs.forEach { section ->
                            NavigationBarItem(
                                selected=section==activeSection,
                                onClick={openSection(section)},
                                icon={
                                    Icon(painterResource(when(section){
                                        MailSection.INBOX -> R.drawable.ic_mail
                                        MailSection.MAILBOXES -> R.drawable.ic_folder
                                        MailSection.TRANSFER -> R.drawable.ic_file
                                        MailSection.DECOR -> R.drawable.ic_admin
                                        MailSection.SETTINGS -> R.drawable.ic_settings
                                        else -> R.drawable.ic_mail
                                    }),contentDescription=null)
                                },
                                label={Text(section.label)},
                                alwaysShowLabel=true
                            )
                        }
                    } else {
                        WorkspaceNavigation.adminTabs.forEach { tab ->
                            val selected=if(tab==AdminDestinationTab.PRIVATE)
                                activeSection==MailSection.PRIVATE
                            else activeSection==MailSection.ADMIN && adminPane==tab.pane
                            NavigationBarItem(
                                selected=selected,
                                onClick={selectAdminNavigation(tab)},
                                icon={
                                    Icon(painterResource(when(tab){
                                        AdminDestinationTab.MAILBOXES -> R.drawable.ic_mail
                                        AdminDestinationTab.DOMAINS -> R.drawable.ic_folder
                                        AdminDestinationTab.RECORDS -> R.drawable.ic_file
                                        AdminDestinationTab.PRIVATE -> R.drawable.ic_mail
                                        AdminDestinationTab.SECURITY -> R.drawable.ic_shield
                                    }),contentDescription=null)
                                },
                                label={Text(tab.label)},
                                alwaysShowLabel=true
                            )
                        }
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
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Each WebView is mounted only after the section's first visit,
                    // then remains attached and retains its DOM/session across tabs.
                    if (publicSelected && !nativeVisible) AndroidView(
                        factory = { obtainBrowser(MailSection.INBOX) },
                        update = {
                            it.visibility = if (publicSelected && !nativeVisible) View.VISIBLE else View.INVISIBLE
                        },
                        modifier = Modifier.fillMaxSize().alpha(
                            if (publicSelected && !nativeVisible) 1f else 0f
                        )
                    )
                    if (transferWebVisible) {
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
                    if (nativeVisible) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            if (nativeMailDetail != null &&
                                pendingNativeMessageId == nativeMailDetail?.id) {
                                NativeMailDetailContent(nativeMailDetail!!)
                            } else if (pendingNativeMessageId != null) {
                                NativeMailLoadingContent()
                            } else if (!nativeDeviceClient.isBound()) {
                                NativeLoginContent()
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
                    if (activeSection == MailSection.PRIVATE && showNativeToolsPanel) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            NativePrivateMailContent()
                        }
                    }
                    if (activeSection == MailSection.DECOR && showNativeToolsPanel) {
                        Surface(modifier=Modifier.fillMaxSize()) { NativeGlyphContent() }
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
        privateMailGeneration.incrementAndGet()
        privateMailClient.invalidate()
        privateMailReady = false
        privateMailBusy = false
        adminGeneration.incrementAndGet()
        nativeAdminClient.invalidatePendingResponses()
        glyphGeneration.incrementAndGet()
        glyphClient.invalidate()
        glyphSnapshot = null
        glyphBusy = false
        glyphSiteSession = false
        adminBusy = false
        adminAuthenticated = false
        adminMailboxes = emptyList()
        adminMessages = emptyList()
        adminSelectedMailbox = null
        adminSelectedMessage = null
        adminSelectedMessageId = null
        adminPane = "mailboxes"
        adminManagedDomains = emptyList()
        adminRecordedAddresses = emptyList()
        adminSecurityState = null
        adminInventoryDomains = emptyList()
        adminInventoryStatus = ""
        adminSelectedDomain = null
        adminRegisteredAccounts = emptyList()
        adminSelectedRegistration = null
        adminDeleteTarget = null
        adminRevokeTrustedTarget = null
        // Website CookieManager and native Keystore grants are separate auth realms.
        // Do not revoke or erase the account grant merely because website data
        // (including the administrator WebView session) was cleared.
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
                message = "网站 Cookie 与页面存储已清除；原生账号设备授权仍保留，管理员网站需重新登录。"
            }
        }
        lock()
    }

    private fun lock() {
        unlocked = false
        adminRestoreGeneration.incrementAndGet()
        adminRestoreChecking=false
        adminQuickMenuOpen=false
        adminLoadedAt.clear()
        adminExternalDetailGeneration.incrementAndGet()
        adminExternalEmails=emptyList()
        adminRegistrationFetchLimit=100
        adminSelectedExternalEmail=null
        adminSelectedExternalDetail=null
        adminExternalDetailBusy=false
        showHumanVerificationPrompt=false
        humanVerificationFlow=false
        humanVerificationBaseline=emptySet()
        nativeDeviceClient.cancelPendingLogin()
        nativeOfflineCache.cancelPendingWrites()
        nativeLastSuccessfulSyncMs=0L
        accountOverviewGeneration.incrementAndGet()
        accountOverviewClient.invalidate()
        accountOverview = null
        accountOverviewBusy = false
        accountOverviewError = ""
        showNativeClosureInfo = false
        privateMailGeneration.incrementAndGet()
        privateMailClient.invalidate()
        privateMailBusy = false
        privateMailError = ""
        privateMailNotice = ""
        // Keep the server-bound private cookie for next biometric unlock,
        // but never retain secret compose text in the locked UI.
        privateMailText = ""
        adminGeneration.incrementAndGet()
        nativeAdminClient.invalidatePendingResponses()
        adminGlyphGeneration.incrementAndGet()
        adminGlyphSnapshot=null
        adminGlyphBusy=false
        adminGlyphError=""
        adminGlyphBuyTarget=null
        glyphGeneration.incrementAndGet()
        glyphClient.invalidate()
        glyphSnapshot = null
        glyphBusy = false
        glyphSiteSession = false
        adminBusy = false
        adminAuthenticated = false
        adminSignInPassword = ""
        adminRegPassword = ""
        adminRegTotp = ""
        adminRegPolicy = null
        adminRegMessage = ""
        showAdminRegConfirm = false
        adminTotpCode = ""
        adminTotpRequired = false
        adminMailboxes = emptyList()
        adminMessages = emptyList()
        adminSelectedMailbox = null
        adminSelectedMessage = null
        adminSelectedMessageId = null
        adminPane = "mailboxes"
        adminManagedDomains = emptyList()
        adminRecordedAddresses = emptyList()
        adminSecurityState = null
        adminInventoryDomains = emptyList()
        adminInventoryStatus = ""
        adminSelectedDomain = null
        adminRegisteredAccounts = emptyList()
        adminSelectedRegistration = null
        adminDeleteTarget = null
        adminRevokeTrustedTarget = null
        showAdminCreateDialog = false
        showNativeAccountDetails = false
        nativeRequestGeneration.incrementAndGet()
        nativeApiLoading = false
        showNativePairDialog = false
        pendingPairNonce = null
        nativePairPassword = ""
        nativePairTotp = ""
        nativeLoginPassword = ""
        nativeLoginTotp = ""
        nativeLoginBusy = false
        nativePickedFiles = emptyList()
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
        // Hard fail-closed: in-app admin content must never create a WebView,
        // even if future navigation code accidentally targets this scope.
        check(key != MailSection.ADMIN) { "Administrator must use native Compose, not WebView" }
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
                        ) || (data.data?.length ?: 513) > 512) return@WebMessageListener
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
                                    nativeRequestGeneration.incrementAndGet()
                                    nativeApiLoading = false
                                    nativeApiActive = false
                                    nativeInbox = null
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
                            "registered" -> {
                                if (BackgroundPushConsent.mayDeliver(
                                    backgroundPushOptIn,backgroundPushRevokePending))
                                    backgroundPushStatus = "服务器已登记本机后台提醒"
                            }
                            "revoked" -> {
                                if (backgroundPushRevokePending) {
                                    setPushOptIn(false)
                                    setPushRevokePending(false)
                                    backgroundPushStatus = "服务器已确认撤销本机推送授权"
                                }
                            }
                            "disabled" -> backgroundPushStatus = "服务器 FCM 推送尚未上线"
                            "error" -> backgroundPushStatus =
                                if (backgroundPushRevokePending)
                                    "本机提醒已停用，但服务器撤销失败；请重试。"
                                else "服务器拒绝登记；检查账号与配置"
                        }
                    }
                }
            )
        }
        // Only the exact public first-party document can request a human
        // challenge prompt. It cannot provide or consume CAPTCHA answers.
        if (key == MailSection.INBOX && WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,"orbitMailChallenge",setOf("https://inbox.kango99w.de"),
                WebViewCompat.WebMessageListener { sender,data,origin,mainFrame,_ ->
                    if(data.type!=WebMessageCompat.TYPE_STRING ||
                        !NativeHumanVerificationPolicy.trustedSource(
                            origin.scheme,origin.host,origin.port,mainFrame,sender.url))
                        return@WebMessageListener
                    val challenge=NativeHumanVerificationPolicy.parse(data.data)
                        ?: return@WebMessageListener
                    runOnUiThread {
                        if(!unlocked || workspace!=MailWorkspace.ORDINARY ||
                            nativeDeviceClient.isBound() || publicWebView() !== view ||
                            !isCurrentPublicMailDocument())return@runOnUiThread
                        requestHumanVerification(challenge.code)
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
                        if (!unlocked || nativeDeviceClient.isBound() ||
                            publicWebView() !== view || !isCurrentPublicMailDocument())
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
                        if (!unlocked || nativeDeviceClient.isBound()) return@runOnUiThread
                        when (event) {
                            NativeInboxEvent.Clear -> nativeInbox = null
                            is NativeInboxEvent.Snapshot -> {
                                if (nativeInbox?.currentId != event.data.currentId) {
                                    nativeMailDetail=null
                                    pendingNativeMessageId=null
                                }
                                val prior=nativeInbox
                                if (NewMailArrivalPolicy.hasArrival(prior,event.data)) {
                                    val stamp=SystemClock.elapsedRealtime()
                                    if (MailNoticePolicy.canDeliver(stamp,lastNewMailNoticeAtMs) &&
                                        showMailNotification()) lastNewMailNoticeAtMs=stamp
                                }
                                nativeInbox = event.data
                                tryRegisterPush()
                                if(humanVerificationFlow) {
                                    // Completing the challenge itself is NOT a
                                    // successful create. Only a newly returned
                                    // mailbox permits automatic Compose return.
                                    val created=event.data.mailboxes.any {
                                        it.id !in humanVerificationBaseline
                                    }
                                    if(created) {
                                        humanVerificationFlow=false
                                        humanVerificationBaseline=emptySet()
                                        showHumanVerificationPrompt=false
                                        webLoginInProgress=false
                                        showNativeMail=true
                                    }
                                } else if (webLoginInProgress &&
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
            MailSection.ADMIN -> error("Administrator WebView is disabled; use Compose")
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
            if(workspace==MailWorkspace.ORDINARY && !adminRestoreChecking) {
                if (nativeDeviceClient.isBound()) refreshNativeApiIfNeeded()
                else requestMissingNativeSnapshot()
            }
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
        adminGeneration.incrementAndGet()
        nativeAdminClient.invalidatePendingResponses()
        adminIo.shutdownNow()
        adminReadIo.shutdownNow()
        nativeReadIo.shutdownNow()
        privateMailGeneration.incrementAndGet()
        privateMailClient.invalidate()
        privateMailIo.shutdownNow()
        glyphGeneration.incrementAndGet()
        glyphClient.invalidate()
        glyphIo.shutdownNow()
        nativeRequestGeneration.incrementAndGet()
        nativeIo.shutdownNow()
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
