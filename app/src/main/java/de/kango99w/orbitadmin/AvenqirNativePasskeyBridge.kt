package de.kango99w.orbitadmin

import android.app.AlertDialog
import android.content.MutableContextWrapper
import android.widget.Toast
import android.util.Log
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

    // Consent is NOT an assertion. Only Credential Manager performs passkey
    // user verification and the server validates the signed response.
    fun canLaunchProvider(consented: Boolean, foreground: Boolean,
        allowed: Boolean, pageUrl: String?): Boolean =
        consented && foreground && allowed && pageUrl == PAGE

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
    private var dialog: AlertDialog? = null


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
            // Explicit consent from native UI, followed by ONE credential
            // provider operation. No separate biometric prompt may compete
            // with Android Credential Manager's own authentication window.
            busy = true
            var consented = false
            val confirmation = AlertDialog.Builder(activity)
                .setTitle(if (operation == "create") "登记 AVENQIR 通行密钥" else "使用通行密钥登录")
                .setMessage("继续后将打开 Android 凭证服务，由系统决定指纹或设备锁验证方式。仅用于 avenqir.bg5.net 管理员 adm。")
                .setNegativeButton("取消") { _, _ -> complete(reply, id, "CANCELLED") }
                .setPositiveButton("打开系统凭证") { _, _ -> consented = true }
                .create()
            dialog = confirmation
            confirmation.setOnDismissListener {
                dialog = null
                if (closed || !busy) return@setOnDismissListener
                if (!consented) complete(reply, id, "CANCELLED")
                else invokeSystem(operation, options.toString(), reply, id)
            }
            try { confirmation.show() }
            catch (_: RuntimeException) { complete(reply, id, "UNAVAILABLE") }
        }
    }

    private fun status(message: String) {
        if (!closed && !activity.isDestroyed)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun foreground(): Boolean =
        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            activity.hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed

    private fun invokeSystem(operation: String, options: String, reply: JavaScriptReplyProxy, id: String) {
        job = activity.lifecycleScope.launch {
            try {
                // Wait for consent dialog dismissal before starting the timed
                // provider operation. An early return belongs to this launch,
                // not to the non-inline withTimeout coroutine.
                val deadline = android.os.SystemClock.elapsedRealtime() + 8000L
                while (!foreground() && allowed() && !closed &&
                    page.url == AvenqirNativePasskeyPolicy.PAGE &&
                    android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
                if (!AvenqirNativePasskeyPolicy.canLaunchProvider(
                        true, foreground(), allowed() && !closed, page.url)) {
                    complete(reply, id, "INVALID")
                    return@launch
                }
                status("正在打开 Android 系统凭证，请选择通行密钥")
                Log.i("OrbitPasskey", "PROVIDER_LAUNCH operation=$operation")
                val response = withTimeout(85000L) {
                    val context = MutableContextWrapper(activity)
                    val manager = CredentialManager.create(activity)
                    if (operation == "create") {
                        val result = manager.createCredential(context = context,
                            request = CreatePublicKeyCredentialRequest(options))
                        (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                            ?: error("unexpected credential")
                    } else {
                        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options)))
                        val result = manager.getCredential(context = context, request = request)
                        (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                            ?: error("unexpected credential")
                    }
                }
                Log.i("OrbitPasskey", "PROVIDER_RETURNED")
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
        dialog?.dismiss()
        dialog = null
        Log.i("OrbitPasskey", "COMPLETE code=${code ?: "RESPONSE"}")
        if (code != null && code != "CANCELLED") {
            status(when (code) {
                "INVALID" -> "凭证窗口未能启动或页面状态已变更，请返回后重新执行第一步。"
                "TIMEOUT" -> "系统凭证服务超时，请检查手机默认密码管理器。"
                "UNAVAILABLE" -> "手机的系统凭证服务不可用，请检查默认密码管理器。"
                "NO_CREDENTIAL" -> "没有找到可用通行密钥，请确认之前登记的密码管理器。"
                else -> "系统凭证操作未完成，请检查网站关联及密码管理器。"
            })
        } else if (code == null) status("系统凭证已返回，等待服务器验证")
        respond(reply, id, code, credential)
        if (!closed) finished()
    }
    fun close() {
        closed = true
        dialog?.dismiss()
        dialog = null
        job?.cancel()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(page, AvenqirNativePasskeyPolicy.OBJECT)
    }
}
