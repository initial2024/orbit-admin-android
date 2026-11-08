package de.kango99w.orbitadmin

import org.json.JSONObject

/** These are administrator-owned external-email ledger rows, not inboxes. */
data class AdminExternalEmail(
    val id:String,val address:String,val provider:String,val status:String,
    val label:String,val purpose:String,val note:String,
    val registrationCount:Int,val updatedAt:Long
)
data class AdminExternalRegistration(
    val id:String,val service:String,val accountLabel:String,
    val lifecycle:String,val status:String,val note:String,val registeredAt:Long
)
data class AdminExternalEmailDetail(
    val email:AdminExternalEmail,val registrations:List<AdminExternalRegistration>
)

object AdminExternalEmailModels {
    private val providers=setOf("apple_hide_my_email","apple_private_relay",
        "icloud","gmail","outlook","qq","other")
    private val states=setOf("active","disabled","unknown")
    private val stages=setOf("registered","active","attention","closed")
    private val emailPattern=Regex("^[^@\\s<>]{1,64}@[A-Za-z0-9.-]{3,150}$")
    fun validId(id:String)=AdminNativeModels.validId(id)
    fun providerLabel(provider:String)=when(provider) {
        "apple_hide_my_email"->"Apple 隐藏邮箱"
        "apple_private_relay"->"Apple Relay"
        "icloud"->"iCloud"
        "gmail"->"Gmail"
        "outlook"->"Outlook"
        "qq"->"QQ 邮箱"
        else->"其他服务商"
    }
    fun statusLabel(status:String)=when(status) {
        "active"->"有效"
        "disabled"->"停用"
        else->"未核验"
    }
    private fun clean(raw:String,max:Int):String =
        raw.filter { !it.isISOControl() }.take(max)
    private fun row(json:JSONObject?):AdminExternalEmail? {
        if(json==null)return null
        val id=json.optString("id")
        val address=clean(json.optString("address").trim(),254)
        val provider=json.optString("provider")
        val status=json.optString("status")
        if(!validId(id)||!emailPattern.matches(address) ||
            provider !in providers || status !in states)return null
        return AdminExternalEmail(id,address,provider,status,
            clean(json.optString("label"),160),
            clean(json.optString("purpose"),240),
            clean(json.optString("note"),500),
            json.optInt("registration_count").coerceIn(0,100_000),
            json.optLong("updated_at").coerceAtLeast(0L))
    }
    fun list(body:JSONObject?):List<AdminExternalEmail> {
        val data=body?.optJSONArray("external_emails")?:return emptyList()
        val seen=HashSet<String>()
        return (0 until minOf(data.length(),300)).mapNotNull { index ->
            row(data.optJSONObject(index))?.takeIf { seen.add(it.id) }
        }
    }
    fun detail(body:JSONObject?,expectedId:String):AdminExternalEmailDetail? {
        if(!validId(expectedId))return null
        val email=row(body?.optJSONObject("email"))?.takeIf { it.id==expectedId }
            ?:return null
        val arr=body.optJSONArray("registrations")?:return null
        if(arr.length()>200)return null
        val seen=HashSet<String>()
        val records=(0 until arr.length()).mapNotNull { index ->
            val obj=arr.optJSONObject(index)?:return@mapNotNull null
            val id=obj.optString("id")
            val stage=obj.optString("lifecycle_stage")
            if(!validId(id)||stage !in stages||!seen.add(id))return@mapNotNull null
            AdminExternalRegistration(id,clean(obj.optString("service"),120),
                clean(obj.optString("account_label"),160),stage,
                clean(obj.optString("status"),24),
                clean(obj.optString("note"),500),obj.optLong("registered_at"))
        }
        return AdminExternalEmailDetail(email,records)
    }
}
