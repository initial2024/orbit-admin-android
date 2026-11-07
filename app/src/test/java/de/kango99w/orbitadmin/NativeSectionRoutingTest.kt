package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSectionRoutingTest {
    @Test fun allFiveTabsAreNativeFirst() {
        assertEquals(5, MailSection.entries.size)
        MailSection.entries.forEach { assertTrue(NativeSectionRouting.isNativePrimaryTab(it)) }
    }

    @Test fun websiteFallbackRoutesToCorrectScopedBrowser() {
        assertEquals(MailSection.INBOX, NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de/?orbit_app=android"))
        assertEquals(MailSection.TRANSFER, NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de/transfer/"))
        assertEquals(MailSection.ADMIN, NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de/admin/"))
        assertEquals(MailSection.ADMIN, NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de/admin/records/"))
    }

    @Test fun websiteFallbackRejectsUntrustedOrigins() {
        assertNull(NativeSectionRouting.explicitWebsiteTarget("http://inbox.kango99w.de/admin/"))
        assertNull(NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de.attacker.tld/admin/"))
        assertNull(NativeSectionRouting.explicitWebsiteTarget("https://attacker@inbox.kango99w.de/admin/"))
        assertNull(NativeSectionRouting.explicitWebsiteTarget("https://inbox.kango99w.de:444/admin/"))
    }

    @Test fun tabSwitchesCannotLoadHtmlAndAdminTransferWebviewsAreLazy() {
        val file = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).firstOrNull { it.isFile } ?: error("Android source missing")
        val source = file.readText()
        val open = source.substringAfter("private fun openSection(section: MailSection) {")
            .substringBefore("private fun navigateTo(url: String)")
        assertFalse(open.contains("loadUrl("))
        assertTrue(open.contains("showNativeToolsPanel = true"))
        assertTrue(source.contains("if (transferWebVisible ||"))
        assertTrue(source.contains("if (adminWebVisible ||"))
        assertTrue(source.contains("NativeTransferContent()"))
        assertTrue(source.contains("NativeAdminContent()"))
        assertTrue(source.contains("NativeSectionRouting.explicitWebsiteTarget(url)"))
    }
}
