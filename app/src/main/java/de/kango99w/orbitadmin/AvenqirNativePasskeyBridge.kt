package de.kango99w.orbitadmin

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.webkit.WebView
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Only the first-party owner passkey page may invoke the Android system credential UI.
 * Never forward passwords, cookies, arbitrary origins or raw exception details.
 * The web server still verifies its one-use challenge and the original signed response.
 */
object AvenqirNativePasskeyPolicy {
    const val ORIGIN = "https://avenqir.bg5.net"
    const val PAGE = "$ORIGIN/passkey.php"
    const val RP = "avenqir.bg5.net"
    const val OBJECT = "OrbitAvenqirPasskey"

    fun allows(sourceOrigin: String, mainFrame: Boolean, pageUrl: String?): Boolean =
        mainFrame && sourceOrigin == ORIGIN && pageUrl == PAGE

    // A local verification result is never a web assertion; the actual
    // Credential Manager request must still be made from a foreground page.
    fun canLaunchAfterVerification(verified: Boolean, foreground: Boolean,
        allowed: Boolean, pageUrl: String?): Boolean =
        verified && foreground && allowed && pageUrl == PAGE

    fun validOptions(operation: String, options: JSONObject): Boolean {
        if (!options.optString("challenge").matches(Regex("[A-Za-z0-9_-]{43}"))) return false
        if (options.has("origin")) return false
        return when (operation) {
            "create" -> options.optJSONObject("rp")?.optString("id") == RP &&
                options.optJSONObject("user")?.optString("name") == "adm" &&
                options.optJSONObject("authenticatorSelection")?.optString("userVerification") == "required" &&
                options.optString("attestation") == "none"
            "get" -> options.optString("rpId") == RP &&
                options.optString("userVerification") == "required" &&
                (options.optJSONArray("allowCredentials")?.length() ?: 0) in 1..6
            else -> false
        }
    }
}

class AvenqirNativePasskeyBridge(
    private val activity: FragmentActivity,
    private val page: WebView,
    private val allowed: () -> Boolean,
    private val finished: () -> Unit
) {
    var busy = false
        private set
    private var closed = false
    private var job: Job? = null


    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(page, AvenqirNativePasskeyPolicy.OBJECT,
            setOf(AvenqirNativePasskeyPolicy.ORIGIN)) { view, message, origin, mainFrame, reply ->
            if (closed || view !== page ||
                !AvenqirNativePasskeyPolicy.allows(origin.toString(), mainFrame, page.url)) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 64000) return@addWebMessageListener
            val input = try { JSONObject(raw) } catch (_: Exception) { return@addWebMessageListener }
            val id = input.optString("id")
            if (!id.matches(Regex("[a-f0-9]{32}"))) return@addWebMessageListener
            val operation = input.optString("operation")
            val options = input.optJSONObject("options")
            if (busy) { respond(reply, id, "BUSY"); return@addWebMessageListener }
            if (!allowed() || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
                options == null || !AvenqirNativePasskeyPolicy.validOptions(operation, options)) {
                respond(reply, id, "INVALID"); return@addWebMessageListener
            }
            // Replace the ordinary confirmation dialog with the actual Android
            // system biometric UI. This is a LOCAL gate, not a Passkey assertion.
            busy = true
            invokeSystem(operation, options.toString(), reply, id)
        }
    }

    private suspend fun verifyDevice(operation: String): String? =
        suspendCancellableCoroutine { continuation ->
            val manager = activity.getSystemService(BiometricManager::class.java)
            val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
            val screenLock = BiometricManager.Authenticators.DEVICE_CREDENTIAL
            // Prefer enrolled strong biometrics; otherwise use the device screen
            // lock. Android, not the app, selects the available biometric modality.
            val authenticators = when {
                manager?.canAuthenticate(strong) == BiometricManager.BIOMETRIC_SUCCESS -> strong
                manager?.canAuthenticate(screenLock) == BiometricManager.BIOMETRIC_SUCCESS -> screenLock
                else -> {
                    continuation.resume("UNAVAILABLE")
                    return@suspendCancellableCoroutine
                }
            }
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            val executor = activity.mainExecutor
            try {
                val builder = BiometricPrompt.Builder(activity)
                    .setTitle(if (operation == "create") "验证设备 · 登记通行密钥" else "验证设备 · AVENQIR 登录")
                    .setSubtitle("avenqir.bg5.net · 管理员 adm")
                    .setDescription("验证通过后仍须由系统凭证服务完成真正的通行密钥操作。")
                    .setAllowedAuthenticators(authenticators)
                    .setConfirmationRequired(true)
                if (authenticators == strong) {
                    builder.setNegativeButton("取消", executor) { _, _ ->
                        cancellation.cancel()
                        if (continuation.isActive) continuation.resume("CANCELLED")
                    }
                }
                builder.build().authenticate(cancellation, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            val code = when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON -> "CANCELLED"
                                BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                                BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
                                BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> "UNAVAILABLE"
                                else -> "PROVIDER"
                            }
                            if (continuation.isActive) continuation.resume(code)
                        }
                    })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume("UNAVAILABLE")
            }
        }

    private fun foreground(): Boolean =
        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            activity.hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed

    private fun invokeSystem(operation: String, options: String, reply: JavaScriptReplyProxy, id: String) {
        job = activity.lifecycleScope.launch {
            try {
                val response = withTimeout(100000L) {
                    val localError = withTimeout(35000L) { verifyDevice(operation) }
                    if (localError != null) {
                        complete(reply, id, localError)
                        return@launch
                    }
                    // The system biometric window may retain focus briefly after
                    // success. Wait for its dismissal instead of launching a
                    // second credential window over it.
                    repeat(40) { if (!foreground()) delay(50) }
                    if (!AvenqirNativePasskeyPolicy.canLaunchAfterVerification(
                            true, foreground(), allowed() && !closed, page.url)) {
                        complete(reply, id, "INVALID")
                        return@launch
                    }
                    val manager = CredentialManager.create(activity)
                    if (operation == "create") {
                        val result = manager.createCredential(activity,
                            CreatePublicKeyCredentialRequest(options))
                        (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                            ?: error("unexpected credential")
                    } else {
                        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options)))
                        val result = manager.getCredential(activity, request)
                        (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                            ?: error("unexpected credential")
                    }
                }
                val credential = JSONObject(response)
                if (response.length > 120000 || credential.optString("type") != "public-key" ||
                    credential.optString("rawId").isBlank() || credential.optJSONObject("response") == null) {
                    complete(reply, id, "INVALID")
                } else complete(reply, id, null, credential)
            } catch (_: TimeoutCancellationException) {
                complete(reply, id, "TIMEOUT")
            } catch (e: CancellationException) {
                complete(reply, id, "CANCELLED")
                throw e
            } catch (e: Exception) {
                val code = when {
                    e.javaClass.simpleName.contains("Cancellation") -> "CANCELLED"
                    e.javaClass.simpleName.contains("NoCredential") -> "NO_CREDENTIAL"
                    e.javaClass.simpleName.contains("Unsupported") ||
                        e.javaClass.simpleName.contains("ProviderConfiguration") -> "UNAVAILABLE"
                    e.javaClass.simpleName.contains("Credential") -> "PROVIDER"
                    else -> "OTHER"
                }
                complete(reply, id, code)
            }
        }
    }

    private fun respond(reply: JavaScriptReplyProxy, id: String, code: String?, credential: JSONObject? = null) {
        if (closed || activity.isDestroyed || page.url != AvenqirNativePasskeyPolicy.PAGE) return
        try {
            val out = JSONObject().put("id", id).put("ok", code == null)
            if (code != null) out.put("code", code) else out.put("credential", credential)
            reply.postMessage(out.toString())
        } catch (_: RuntimeException) { /* Page was navigated away; never relay elsewhere. */ }
    }
    private fun complete(reply: JavaScriptReplyProxy, id: String, code: String?, credential: JSONObject? = null) {
        if (!busy) return
        busy = false
        respond(reply, id, code, credential)
        if (!closed) finished()
    }
    fun close() {
        closed = true
        job?.cancel()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(page, AvenqirNativePasskeyPolicy.OBJECT)
    }
}
