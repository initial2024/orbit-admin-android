package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordinary account grants and administrator website credentials remain separate. */
class AccountAdminEntryBoundaryTest {
    @Test fun adminIsAnExplicitSeparateDestinationWithoutOrdinaryDeviceBinding() {
        assertEquals("管理员", MailSection.ADMIN.label)
        assertEquals(MailSection.ADMIN,
            NativeSectionRouting.explicitWebsiteTarget(AdminDestination.OVERVIEW.address))
        assertEquals(MailSection.INBOX,
            NativeSectionRouting.explicitWebsiteTarget(AdminDestination.PUBLIC_MAIL.address))
        assertTrue(NativeSectionRouting.isNativePrimaryTab(MailSection.ADMIN))
        assertTrue(NativeSectionRouting.isNativePrimaryTab(MailSection.INBOX))
    }

    @Test fun ordinaryAccountAndAdminWebsiteEntryDoNotAlias() {
        assertFalse(AdminDestination.OVERVIEW.address == MailSection.INBOX.address)
        assertFalse(AdminDestination.SECURITY.address == MailSection.INBOX.address)
        assertTrue(AdminDestination.OVERVIEW.address.contains("/admin/"))
        assertTrue(NativeLoginFeedback.describe(401, "NATIVE_LOGIN_REJECTED")
            .contains("不会尝试管理员登录"))
    }
}
