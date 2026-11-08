package de.kango99w.orbitadmin

import org.json.JSONObject

/**
 * Account-registration records already exposed by the authenticated first-party
 * admin account registry. These are service-account registrations, NOT the whole
 * ordinary-user population and never authorization grants.
 */
data class AdminRegisteredAccount(
    val id: String,
    val service: String,
    val accountLabel: String,
    val address: String,
    val source: String,
    val status: String,
    val lifecycle: String,
    val registeredAt: Long,
    val reviewedAt: Long,
    val note: String
)

object AdminRegistrationModels {
    private val idPattern=Regex("^[A-Za-z0-9-]{8,64}$")
    private val addressPattern=Regex("^[^@\\s]{1,64}@[A-Za-z0-9.-]{3,150}$")
    fun parse(value: JSONObject?): List<AdminRegisteredAccount> {
        val array=value?.optJSONArray("registrations") ?: return emptyList()
        val result=ArrayList<AdminRegisteredAccount>(minOf(array.length(),500))
        val seen=HashSet<String>()
        for(i in 0 until minOf(array.length(),500)){
            val row=array.optJSONObject(i) ?: continue
            val id=row.optString("id")
            val address=row.optString("mailbox_address").trim().lowercase()
            if(!idPattern.matches(id)||!addressPattern.matches(address)||!seen.add(id))continue
            val source=row.optString("mailbox_source").take(24)
            if(source!="pieterj" && source!="ordinary")continue
            result.add(AdminRegisteredAccount(
                id=id,
                service=row.optString("service").take(120),
                accountLabel=row.optString("account_label").take(160),
                address=address.take(254),
                source=source,
                status=row.optString("status").take(32),
                lifecycle=row.optString("lifecycle_stage").take(32),
                registeredAt=row.optLong("registered_at"),
                reviewedAt=row.optLong("last_reviewed_at"),
                note=row.optString("note").take(500)
            ))
        }
        return result
    }
}
