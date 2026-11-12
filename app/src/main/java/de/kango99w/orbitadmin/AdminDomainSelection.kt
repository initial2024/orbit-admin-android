package de.kango99w.orbitadmin

import org.json.JSONObject

/**
 * Only first-party, server-declared ADMIN-ONLY mail domains may be selected
 * for a permanent administrator mailbox. Ordinary mailbox choices come from
 * the ordinary administrator registry and remain a different creation scope.
 */
data class AdminCreateDomainOption(
    val domain: String,
    val label: String,
    val category: String,
    val ingress: String
)

object AdminDomainSelection {
    private val domainPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{1,148}[a-z0-9])?$")

    private fun valid(value: String): Boolean =
        value.length in 3..150 && domainPattern.matches(value) &&
            !value.contains("..") && value.contains('.')

    fun permanent(status: JSONObject?): List<AdminCreateDomainOption> {
        val rows = status?.optJSONArray("admin_domains") ?: return emptyList()
        val found = HashSet<String>()
        return (0 until minOf(rows.length(), 150)).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val domain = item.optString("domain").lowercase().trim()
            val ingress = item.optString("ingress").take(40)
            if (!valid(domain) || !item.optBoolean("enabled", false) ||
                item.optBoolean("public_selectable", false) ||
                !found.add(domain)) return@mapNotNull null
            AdminCreateDomainOption(domain, domain, "管理员固定邮箱", ingress)
        }
    }

    fun temporary(status: JSONObject?): List<AdminCreateDomainOption> {
        val rows = status?.optJSONArray("domains") ?: return emptyList()
        val found = HashSet<String>()
        return (0 until minOf(rows.length(), 150)).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val domain = item.optString("domain").lowercase().trim()
            if (!valid(domain) || (item.has("enabled") && !item.optBoolean("enabled")) || !found.add(domain)) return@mapNotNull null
            AdminCreateDomainOption(
                domain, item.optString("label").ifBlank { domain }.take(70),
                "管理员临时邮箱", "ordinary"
            )
        }
    }

    fun filter(items: List<AdminCreateDomainOption>, query: String): List<AdminCreateDomainOption> {
        val q = query.trim()
        return if (q.isEmpty()) items else items.filter {
            it.domain.contains(q, ignoreCase = true) ||
                it.label.contains(q, ignoreCase = true)
        }
    }
}
