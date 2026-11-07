package de.kango99w.orbitadmin

/** Independent live WebView documents within one app-private CookieManager. */
object BrowserSessionRouting {
    fun key(section: MailSection): MailSection = when (section) {
        MailSection.INBOX, MailSection.MAILBOXES -> MailSection.INBOX
        MailSection.TRANSFER -> MailSection.TRANSFER
        MailSection.ADMIN -> MailSection.ADMIN
        MailSection.PRIVATE -> MailSection.PRIVATE
        MailSection.SETTINGS -> MailSection.SETTINGS
    }
}
