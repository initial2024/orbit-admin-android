package de.kango99w.orbitadmin

import org.json.JSONObject

/**
 * Domain options come from first-party administrator APIs at runtime.
 * Never manufacture a selectable domain from a static client-side list.
 */
data class AdminDomainChoice(
    val domain: String,
    val label: String,
    val ingress: String
) {
    val group: String get() = when (ingress) {
        "cloudflare_email" -> "Cloudflare Email"
        "forwardemail_webhook" -> "Forward Email"
        else -> "已接入的其他收信域名"
    }
}

object AdminDomainOptions {
    private val pattern = Regex("^[a-z0-9][a-z0-9.-]{1,148}[a-z0-9]$")

    private fun extract(response: JSONObject?, key: String, enabledOnly: Boolean): List<AdminDomainChoice> {
        val values = response?.optJSONArray(key) ?: return emptyList()
        val entries = (0 until minOf(values.length(), 150)).mapNotNull { i ->
            val o = values.optJSONObject(i) ?: return@mapNotNull null
            val domain = o.optString("domain").trim().lowercase()
            if (!pattern.matches(domain) || domain.contains("..") ||
                (enabledOnly && o.optBoolean("enabled") != true)) return@mapNotNull null
            AdminDomainChoice(domain, o.optString("label").take(80),
                o.optString("ingress").take(40))
        }
        return entries.distinctBy { it.domain }.take(100)
    }

    /** The protected permanent/expiring administrator mailbox namespace. */
    fun privateDomains(status: JSONObject?): List<AdminDomainChoice> =
        extract(status, "admin_domains", true)

    /** Public temporary provider registry, still accessed via the admin API. */
    fun ordinaryDomains(status: JSONObject?): List<AdminDomainChoice> =
        extract(status, "domains", false)

    fun contains(options: List<AdminDomainChoice>, domain: String): Boolean =
        options.any { it.domain == domain }
}
