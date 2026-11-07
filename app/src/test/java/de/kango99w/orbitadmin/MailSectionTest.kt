package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailSectionTest {
    @Test fun everyRemoteSectionUsesTheTrustedOrigin() {
        assertEquals(5, MailSection.entries.size)
        for (section in MailSection.entries) {
            if (section.address != null) assertTrue(AdminUrlPolicy.isTrustedMainFrame(section.address))
        }
    }
    @Test fun nativeMailboxViewsUseExistingWebDataAndFragments() {
        assertTrue(MailSection.INBOX.address!!.contains("#orbit-inbox"))
        assertTrue(MailSection.MAILBOXES.address!!.contains("#orbit-mailboxes"))
        assertTrue(MailSection.INBOX.address!!.contains("orbit_app=android"))
        assertEquals(null, MailSection.SETTINGS.address)
        assertTrue(MailSection.ADMIN.address!!.contains("adminTab=tempMail"))
    }
}
