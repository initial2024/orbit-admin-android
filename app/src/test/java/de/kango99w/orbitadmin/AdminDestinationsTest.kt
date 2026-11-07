package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDestinationsTest {
    @Test fun everyShortcutUsesTheExactAdminOriginAndPath() {
        assertEquals(6, AdminDestination.entries.size)
        for (item in AdminDestination.entries) {
            assertTrue(AdminUrlPolicy.isTrustedMainFrame(item.address))
            assertTrue(item.address.startsWith("https://inbox.kango99w.de/admin/"))
            assertFalse(item.address.contains("@"))
        }
    }

    @Test fun shortcutsMapToRealWebsiteScreens() {
        assertTrue(AdminDestination.MAIL.address.contains("adminTab=tempMail"))
        assertTrue(AdminDestination.DOMAINS.address.endsWith("/domain-control/"))
        assertTrue(AdminDestination.RECORDS.address.endsWith("/records/"))
        assertTrue(AdminDestination.SECURITY.address.contains("#admin-screen=admin-settings"))
    }
}
