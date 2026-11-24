package de.kango99w.orbitadmin

import java.net.URI

/**
 * The six primary destinations are native Android UI. First-party HTML opens
 * only on an explicit user action for a feature with no reviewed native API.
 */
object NativeSectionRouting {
    fun explicitWebsiteTarget(url: String): MailSection? {
        if (!AdminUrlPolicy.isTrustedMainFrame(url)) return null
        val path = try { URI(url).path } catch (_: Exception) { return null }
        return when (path) {
            "/", "" -> MailSection.INBOX
            "/transfer", "/transfer/" -> MailSection.TRANSFER
            else -> MailSection.ADMIN
        }
    }

    fun isNativePrimaryTab(section: MailSection): Boolean =
        section in setOf(
            MailSection.INBOX, MailSection.MAILBOXES, MailSection.TRANSFER, MailSection.DECOR,
            MailSection.ADMIN, MailSection.PRIVATE, MailSection.SETTINGS
        )
}
