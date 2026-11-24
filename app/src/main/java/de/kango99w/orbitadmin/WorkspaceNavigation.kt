package de.kango99w.orbitadmin

/**
 * Navigation is a visual workspace choice, never an authentication elevation.
 * Ordinary device credentials, administrator session, and private-mail
 * handoff retain separate authorization and revocation policies.
 */
enum class MailWorkspace { ORDINARY, ADMIN }

enum class AdminDestinationTab(val label: String, val pane: String?) {
    MAILBOXES("邮箱", "mailboxes"),
    DOMAINS("域名", "domains"),
    RECORDS("记录", "records"),
    PRIVATE("发信", null),
    SECURITY("安全", "security")
}

object WorkspaceNavigation {
    val ordinaryTabs = listOf(
        MailSection.INBOX, MailSection.MAILBOXES, MailSection.TRANSFER,
        MailSection.DECOR, MailSection.SETTINGS
    )
    val adminTabs = AdminDestinationTab.entries

    fun isOrdinaryTab(section: MailSection): Boolean = section in ordinaryTabs
    fun isAdminTab(section: MailSection): Boolean =
        section == MailSection.ADMIN || section == MailSection.PRIVATE
}
