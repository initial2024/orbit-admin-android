package de.kango99w.orbitadmin

/**
 * Native mail-first navigation on top of the single website-owned login session.
 * Only the website owns inbox state, storage and all administrator permissions.
 */
enum class MailSection(val label: String, val address: String?) {
    INBOX("收件箱", "https://inbox.kango99w.de/?orbit_app=android#orbit-inbox"),
    MAILBOXES("邮箱", "https://inbox.kango99w.de/?orbit_app=android#orbit-mailboxes"),
    TRANSFER("文件", "https://inbox.kango99w.de/transfer/"),
    ADMIN("管理", "https://inbox.kango99w.de/admin/?adminTab=tempMail"),
    SETTINGS("设置", null);

    companion object {
        init {
            require(entries.filter { it.address != null }.all {
                AdminUrlPolicy.isTrustedMainFrame(it.address!!)
            })
        }
    }
}
