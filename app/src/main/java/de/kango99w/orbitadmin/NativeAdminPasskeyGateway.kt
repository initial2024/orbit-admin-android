package de.kango99w.orbitadmin

import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

/**
 * Pre-wired but fail-closed WebAuthn/Passkey bridge.
 * Origin is set by the Android credential provider, NEVER user input.
 * Backend endpoints must verify challenge, RP ID, origin, UV, and signature.
 */
class NativeAdminPasskeyGateway(private val activity: FragmentActivity) {
    private val manager by lazy { CredentialManager.create(activity) }

    data class Capability(val signIn:Boolean, val registration:Boolean) {
        companion object {
            fun parse(response:JSONObject?, packageId:String):Capability? {
                if(response==null ||
                    response.optString("protocol")!="webauthn-v1" ||
                    response.optString("rp_id")!="inbox.kango99w.de" ||
                    response.optString("android_package")!=packageId ||
                    response.optBoolean("enabled")!=true ||
                    response.optBoolean("assetlinks_ready")!=true ||
                    response.optBoolean("signing_ready")!=true)
                    return null
                val login=response.optBoolean("sign_in",false)
                val register=response.optBoolean("registration",false)
                return if(login||register)Capability(login,register) else null
            }
        }
    }

    data class Challenge(val id:String,val requestJson:String) {
        companion object {
            private val IDENTIFIER=Regex("^[A-Za-z0-9_-]{20,128}$")
            fun parse(reply:JSONObject?):Challenge? {
                if(reply==null)return null
                val id=reply.optString("request_id")
                val publicKey=reply.optJSONObject("public_key") ?: return null
                val challenge=publicKey.optString("challenge")
                if(!IDENTIFIER.matches(id) || challenge.length !in 22..2048 ||
                    !challenge.matches(Regex("^[A-Za-z0-9_-]+$")))
                    return null
                return Challenge(id,publicKey.toString())
            }
        }
    }

    fun signIn(challenge:Challenge,done:(JSONObject?,String?)->Unit) {
        manager.getCredentialAsync(
            context=activity,
            request=GetCredentialRequest(listOf(
                GetPublicKeyCredentialOption(requestJson=challenge.requestJson)
            )),
            cancellationSignal=null,
            executor=ContextCompat.getMainExecutor(activity),
            callback=object : CredentialManagerCallback<GetCredentialResponse,GetCredentialException> {
                override fun onResult(result:GetCredentialResponse) {
                    val credential=result.credential as? PublicKeyCredential
                    if(credential==null) {
                        done(null,"系统未返回通行密钥，请使用原有登录方式。")
                        return
                    }
                    val response=try {
                        JSONObject(credential.authenticationResponseJson)
                    } catch (_:Exception) { null }
                    if(response==null) done(null,"通行密钥响应格式不正确。")
                    else done(JSONObject().put("request_id",challenge.id)
                        .put("credential",response),null)
                }
                override fun onError(e:GetCredentialException) {
                    done(null,"未完成通行密钥验证；可继续使用原管理员登录。")
                }
            }
        )
    }

    fun register(challenge:Challenge,done:(JSONObject?,String?)->Unit) {
        manager.createCredentialAsync(
            context=activity,
            request=CreatePublicKeyCredentialRequest(requestJson=challenge.requestJson),
            cancellationSignal=null,
            executor=ContextCompat.getMainExecutor(activity),
            callback=object : CredentialManagerCallback<CreateCredentialResponse,CreateCredentialException> {
                override fun onResult(result:CreateCredentialResponse) {
                    val credential=result as? CreatePublicKeyCredentialResponse
                    val response=try {
                        credential?.registrationResponseJson?.let { JSONObject(it) }
                    } catch (_:Exception) { null }
                    if(response==null) done(null,"通行密钥注册响应格式不正确。")
                    else done(JSONObject().put("request_id",challenge.id)
                        .put("credential",response),null)
                }
                override fun onError(e:CreateCredentialException) {
                    done(null,"通行密钥尚未注册；原有管理员登录不受影响。")
                }
            }
        )
    }
}
