package de.kango99w.orbitadmin

/**
 * Native-first Android destinations; site-only operations use explicit website fallback.
 * The existing server remains authoritative for account and admin permission checks.
 */
enum class MailSection(val label: String, val address: String?) {
    INBOX("收件箱", "https://inbox.kango99w.de/?orbit_app=android#orbit-inbox"),
    MAILBOXES("邮箱", "https://inbox.kango99w.de/?orbit_app=android#orbit-mailboxes"),
    TRANSFER("文件", "https://inbox.kango99w.de/transfer/"),
    DECOR("装扮", null),
    ADMIN("管理员", "https://inbox.kango99w.de/admin/?adminTab=tempMail"),
    PRIVATE("发信", null),
    SETTINGS("设置", null);

    companion object {
        init {
            require(entries.filter { it.address != null }.all {
                AdminUrlPolicy.isTrustedMainFrame(it.address!!)
            })
        }
    }
}
