package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDestinationsTest {
    @Test fun everyShortcutUsesTheExactAdminOriginAndPath() {
        assertEquals(8, AdminDestination.entries.size)
        for (item in AdminDestination.entries) {
            assertTrue(AdminUrlPolicy.isTrustedMainFrame(item.address))
            assertTrue(item.address.startsWith("https://inbox.kango99w.de/"))
            assertFalse(item.address.contains("@"))
        }
    }

    @Test fun shortcutsMapToRealWebsiteScreens() {
        assertEquals("https://inbox.kango99w.de/", AdminDestination.PUBLIC_MAIL.address)
        assertTrue(AdminDestination.TRANSFER.address.contains("/transfer/"))
        assertTrue(AdminDestination.MAIL.address.contains("adminTab=tempMail"))
        assertTrue(AdminDestination.DOMAINS.address.endsWith("/domain-control/"))
        assertTrue(AdminDestination.RECORDS.address.endsWith("/records/"))
        assertTrue(AdminDestination.SECURITY.address.contains("#admin-screen=admin-settings"))
    }
}
