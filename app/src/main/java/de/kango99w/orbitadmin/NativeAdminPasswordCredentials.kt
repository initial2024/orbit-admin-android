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
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
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
                    val advice=when(e) {
                        is NoCredentialException ->
                            "系统没有找到本 APK 可用的账号密码。先手动登录，并勾选保存到系统密码管理器；网站密码需要经过域名和正式签名关联才能共享。"
                        is GetCredentialProviderConfigurationException ->
                            "Android 未配置可用的密码提供方；请在系统设置中启用 Google 密码管理器或其他可信凭据提供方。"
                        is GetCredentialUnsupportedException ->
                            "当前设备或系统服务不支持此凭据选择器，请手动输入管理员密码。"
                        is GetCredentialCancellationException ->
                            "已取消系统凭据选择，管理员账号不会退出。"
                        else ->
                            "系统凭据服务未能提供密码（${e.javaClass.simpleName}）；可以继续手动登录。"
                    }
                    onResult(null,null,advice)
                }
            }
        )
    }

    fun saveAfterVerifiedLogin(username: String, password: String,
                               onResult: (Boolean, String?) -> Unit) {
        credentials.createCredentialAsync(
            context = activity,
            request = CreatePasswordRequest(id = username, password = password),
            cancellationSignal = null,
            executor = ContextCompat.getMainExecutor(activity),
            callback = object : CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException> {
                override fun onResult(result: CreateCredentialResponse) { onResult(true,null) }
                override fun onError(e: CreateCredentialException) {
                    val detail=when(e) {
                        is CreateCredentialCancellationException ->
                            "已取消保存，未在应用内存储密码。"
                        is CreateCredentialProviderConfigurationException ->
                            "无法保存：系统未启用密码凭据提供方。"
                        is CreateCredentialUnsupportedException ->
                            "无法保存：当前系统不支持密码保存。"
                        else -> "密码保存未完成（${e.javaClass.simpleName}）。"
                    }
                    onResult(false,detail)
                }
            }
        )
    }
}
