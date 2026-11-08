package de.kango99w.orbitadmin

import org.json.JSONObject
import org.json.JSONArray

/** Typed, bounded administrative data for Compose. API responses remain the source of truth. */
data class AdminManagedDomain(
    val domain: String,
    val source: String,
    val state: String,
    val ingress: String,
    // Multiple registries may refer to one hostname. Count it once, but
    // retain each source so filters and details do not lose provenance.
    val sources: List<String> = listOf(source)
)
data class AdminRecordedAddress(
    val address: String,
    val source: String,
    val reserved: Boolean
)
data class AdminTrustedDevice(
    val id: String,
    val label: String,
    val expiresAt: Long
)
data class AdminSecurityState(
    val totpEnabled: Boolean,
    val recoveryCodesRemaining: Int,
    val trustedDevices: List<AdminTrustedDevice>
)

object AdminManagementModels {
    /**
     * Domain inventory is a set of unique FQDNs, not a sum of registry rows.
     * Preserve the first provenance's state/ingress; later sources are retained
     * as evidence labels and are not automatically elevated to ownership.
     */
    fun uniqueDomains(rows: List<AdminManagedDomain>): List<AdminManagedDomain> {
        val grouped = LinkedHashMap<String, AdminManagedDomain>()
        for (row in rows) {
            val key = row.domain.trim().lowercase(java.util.Locale.ROOT)
            if (!domainPattern.matches(key)) continue
            val previous = grouped[key]
            val labels = ((previous?.sources ?: emptyList()) + row.sources)
                .filter { it.isNotBlank() }.distinct().take(8)
            grouped[key] = (previous ?: row).copy(domain = key, sources = labels)
        }
        return grouped.values.sortedBy { it.domain }
    }

    private val domainPattern = Regex("^[a-z0-9][a-z0-9.-]{1,148}[a-z0-9]$")
    private val mailPattern = Regex("^[^@\\s]{1,64}@[a-z0-9.-]{3,150}$")

    private fun domainRows(array: JSONArray?, source: String): List<AdminManagedDomain> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), 100)).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val domain = row.optString("domain").trim().lowercase()
            if (!domainPattern.matches(domain)) return@mapNotNull null
            AdminManagedDomain(
                domain, source,
                row.optString("state").ifBlank {
                    if (row.optBoolean("enabled", false)) "active" else "disabled"
                }.take(32),
                row.optString("ingress").take(64)
            )
        }
    }

    fun domains(privateStatus: JSONObject?, ordinaryStatus: JSONObject?): List<AdminManagedDomain> {
        return uniqueDomains(
            domainRows(privateStatus?.optJSONArray("domains"), "管理员邮箱") +
                domainRows(privateStatus?.optJSONArray("site_domains"), "网站域名") +
                domainRows(privateStatus?.optJSONArray("domain_requests"), "域名申请") +
                domainRows(ordinaryStatus?.optJSONArray("domains"), "临时邮箱")
        ).take(250)
    }

    fun recordedAddresses(response: JSONObject?): List<AdminRecordedAddress> {
        val rows = response?.optJSONArray("records") ?: return emptyList()
        return (0 until minOf(rows.length(), 250)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val address = row.optString("address").take(254)
            if (!mailPattern.matches(address)) return@mapNotNull null
            AdminRecordedAddress(
                address,
                row.optString("source").take(48),
                row.optBoolean("reserved", false)
            )
        }
    }

    fun security(totp: JSONObject?, devices: JSONObject?): AdminSecurityState {
        val rows = devices?.optJSONArray("devices")
        val list = if (rows == null) emptyList() else (0 until minOf(rows.length(), 50))
            .mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id")
                if (!AdminNativeModels.validId(id)) return@mapNotNull null
                AdminTrustedDevice(id, row.optString("device_label").take(64),
                    row.optLong("expires_at"))
            }
        return AdminSecurityState(totp?.optBoolean("enabled") == true,
            (totp?.optInt("recovery_codes_remaining") ?: 0).coerceIn(0, 100),
            list)
    }
}


/** Provider labels and evidence are informational only, not mail/DNS authorization. */
data class AdminInventoryDomain(
    val domain:String,val registrar:String,val dnsProvider:String,
    val websiteHost:String,val emailProvider:String,val notes:String,
    val evidenceSource:String,val evidenceStatus:String,val evidenceNote:String,
    val pagesStatus:String,val zoneStatus:String,val mailConfigured:Boolean,
    val observedAt:Long
)
object AdminDomainInventoryModels {
    private val domainPattern=Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}$")
    private fun safe(o:JSONObject,k:String,max:Int):String=
        o.optString(k).trim().filter { !it.isISOControl() }.take(max)
    fun parse(json:JSONObject?):List<AdminInventoryDomain>{
        val array=json?.optJSONArray("domains") ?: return emptyList()
        val seen=HashSet<String>()
        return (0 until minOf(array.length(),500)).mapNotNull { i ->
            val o=array.optJSONObject(i) ?: return@mapNotNull null
            val domain=safe(o,"domain",253).lowercase()
            if(!domainPattern.matches(domain)||!seen.add(domain))return@mapNotNull null
            AdminInventoryDomain(domain,safe(o,"registrar_provider",60),
                safe(o,"dns_provider",60),safe(o,"website_host",60),
                safe(o,"email_provider",60),safe(o,"notes",250),
                safe(o,"evidence_source",48),safe(o,"evidence_status",48),
                safe(o,"evidence_note",160),safe(o,"pages_status",48),
                safe(o,"zone_status",48),o.optBoolean("mail_configured",false),
                o.optLong("observed_at"))
        }
    }
    fun combine(known:List<AdminManagedDomain>,inventory:List<AdminInventoryDomain>):List<AdminManagedDomain>{
        val providerRows=inventory.map {
            AdminManagedDomain(it.domain,"托管库存",
                it.evidenceStatus.ifBlank{"unverified"},
                if(it.dnsProvider.isNotBlank())"DNS："+it.dnsProvider else "服务商未登记")
        }
        return AdminManagementModels.uniqueDomains(known + providerRows).take(750)
    }
}
