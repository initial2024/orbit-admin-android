package de.kango99w.orbitadmin

/**
 * Shortcuts are URL entry points to existing protected admin screens.
 * No duplicate credentials, API clients or native data caches are introduced.
 */
enum class AdminDestination(val title: String, val address: String) {
    OVERVIEW("总览", "https://inbox.kango99w.de/admin/"),
    MAIL("邮箱", "https://inbox.kango99w.de/admin/?adminTab=tempMail"),
    DOMAINS("域名", "https://inbox.kango99w.de/admin/domain-control/"),
    RECORDS("记录", "https://inbox.kango99w.de/admin/records/"),
    SECURITY("安全", "https://inbox.kango99w.de/admin/#admin-screen=admin-settings"),
    ADVANCED("更多", "https://inbox.kango99w.de/admin/?adminTab=advanced");

    companion object {
        init {
            require(entries.all { AdminUrlPolicy.isTrustedMainFrame(it.address) }) {
                "Admin navigation must be served only by the trusted website"
            }
        }
    }
}
