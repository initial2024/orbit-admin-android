package de.kango99w.orbitadmin

import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat

/** User-initiated Android Credential Manager. App never persists passwords.
 * Saved passwords belong to the platform's credential provider for THIS APK.
 * Not a FIDO2/passkey or a substitute for server-side password/2FA validation.
 */
class NativeAdminPasswordCredentials(private val activity: FragmentActivity) {
    private val credentials by lazy { CredentialManager.create(activity) }

    fun choose(onResult: (String?, String?, String?) -> Unit) {
        credentials.getCredentialAsync(
            context = activity,
            request = GetCredentialRequest(listOf(GetPasswordOption())),
            cancellationSignal = null,
            executor = ContextCompat.getMainExecutor(activity),
            callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val password = result.credential as? PasswordCredential
                    if (password == null) onResult(null, null, "系统未返回密码凭据。")
                    else onResult(password.id, password.password, null)
                }
                override fun onError(e: GetCredentialException) {
                    onResult(null, null, "未选取系统凭据；可以使用管理员密码登录。")
                }
            }
        )
    }

    fun saveAfterVerifiedLogin(username: String, password: String, onResult: (Boolean) -> Unit) {
        credentials.createCredentialAsync(
            context = activity,
            request = CreatePasswordRequest(id = username, password = password),
            cancellationSignal = null,
            executor = ContextCompat.getMainExecutor(activity),
            callback = object : CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException> {
                override fun onResult(result: CreateCredentialResponse) { onResult(true) }
                override fun onError(e: CreateCredentialException) { onResult(false) }
            }
        )
    }
}
