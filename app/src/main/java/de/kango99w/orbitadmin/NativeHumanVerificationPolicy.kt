package de.kango99w.orbitadmin

import org.json.JSONObject

/** No challenge token, cookie, CAPTCHA solution or personal data crosses
 * the WebView-to-Compose message boundary. It is only a UI signal.
 */
data class HumanVerificationRequired(val action:String, val code:String)

object NativeHumanVerificationPolicy {
    private val codes=setOf("TURNSTILE_REQUIRED","TURNSTILE_FAILED","TURNSTILE_EXPIRED")
    fun parse(raw:String?):HumanVerificationRequired? {
        if(raw.isNullOrBlank() || raw.length>256)return null
        return try {
            val obj=JSONObject(raw)
            if(obj.keys().asSequence().toSet() != setOf("v","kind","scope","action","code") ||
                obj.optInt("v")!=1 || obj.optString("kind")!="human_verification_required" ||
                obj.optString("scope")!="public" ||
                obj.optString("action")!="mailbox_create")return null
            val code=obj.optString("code")
            if(code !in codes)return null
            HumanVerificationRequired("mailbox_create",code)
        } catch(_:Exception) { null }
    }
    fun trustedSource(
        scheme:String?,host:String?,port:Int,isMainFrame:Boolean,url:String?
    ):Boolean=NativeInboxPolicy.acceptsSource(scheme,host,port,isMainFrame,url)
}
