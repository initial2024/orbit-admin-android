package de.kango99w.orbitadmin

/** The server, not a historical ledger row, is authoritative for routing and capacity. */
enum class AdminMailboxActivationKind { EXTERNAL, ORDINARY_REGISTRATION }

data class AdminMailboxActivationIntent(
    val address: String,
    val id: String,
    val kind: AdminMailboxActivationKind
) {
    val endpoint: String
        get() = when(kind) {
            AdminMailboxActivationKind.EXTERNAL ->
                "/api/admin/ordinary-inbox/activate-recorded"
            AdminMailboxActivationKind.ORDINARY_REGISTRATION ->
                "/api/admin/account-registry/registrations/$id/revive"
        }
    val label: String
        get() = when(kind) {
            AdminMailboxActivationKind.EXTERNAL -> "一键激活"
            AdminMailboxActivationKind.ORDINARY_REGISTRATION -> "恢复邮箱"
        }
}

/** No "activation" based solely on a copied address or an editable provider label. */
object AdminMailboxActivationPolicy {
    private fun eligibleDomain(address: String, allowedDomains: Set<String>): Boolean {
        val at = address.lastIndexOf('@')
        if(at<=0 || at==address.lastIndex || address.indexOf('@')!=at)return false
        return address.substring(at+1).lowercase(java.util.Locale.ROOT) in allowedDomains
    }

    fun forRecord(row: AdminRecordedAddress, allowedDomains: Set<String>,
                  activeMailboxes: List<AdminNativeMailbox>): AdminMailboxActivationIntent? {
        if(!row.reserved || !eligibleDomain(row.address,allowedDomains) ||
            row.privateId!=null || row.privateRegistrationId!=null ||
            activeMailboxes.any { !it.privateMailbox &&
                it.address.equals(row.address,ignoreCase=true) &&
                it.status=="active" && it.expiresAt>System.currentTimeMillis() })return null
        // An existing ordinary registration must be restored via the exact
        // registration route; the external route cannot bypass that hold.
        if(row.ordinaryRegistrationId!=null) {
            return AdminMailboxActivationIntent(row.address,row.ordinaryRegistrationId,
                AdminMailboxActivationKind.ORDINARY_REGISTRATION)
        }
        if(row.externalId!=null) {
            return AdminMailboxActivationIntent(row.address,row.externalId,
                AdminMailboxActivationKind.EXTERNAL)
        }
        return null
    }

    fun forMailbox(box: AdminNativeMailbox, allowedDomains: Set<String>):
        AdminMailboxActivationIntent? {
        val id=box.registrationId ?: return null
        if(box.privateMailbox || !box.revivable || !AdminNativeModels.validId(id) ||
            !eligibleDomain(box.address,allowedDomains) ||
            (box.status=="active" && box.expiresAt>System.currentTimeMillis()))return null
        return AdminMailboxActivationIntent(box.address,id,
            AdminMailboxActivationKind.ORDINARY_REGISTRATION)
    }

    /** Reject malformed, stale or wrong-address success responses. */
    fun confirmedMailbox(intent: AdminMailboxActivationIntent, status: Int,
                         value: org.json.JSONObject?): AdminNativeMailbox? {
        if(status!=201 || value==null)return null
        if(intent.kind==AdminMailboxActivationKind.ORDINARY_REGISTRATION &&
            !value.optBoolean("revived",false))return null
        val row=value.optJSONObject("mailbox") ?: return null
        val id=row.optString("id")
        val address=row.optString("address")
        val expiry=row.optLong("expires_at",0L)
        if(!AdminNativeModels.validId(id) || !address.equals(intent.address,ignoreCase=true) ||
            expiry<=System.currentTimeMillis())return null
        if(row.has("routing_rule_status") &&
            row.optString("routing_rule_status")!="active")return null
        return AdminNativeMailbox(id,address,false,status="active",expiresAt=expiry,
            registrationId=if(intent.kind==AdminMailboxActivationKind.ORDINARY_REGISTRATION)
                intent.id else null)
    }
}
