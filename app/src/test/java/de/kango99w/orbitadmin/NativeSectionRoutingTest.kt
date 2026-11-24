package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSectionRoutingTest {
    @Test fun allSevenDestinationsAreNativeFirst() {
        assertEquals(7, MailSection.entries.size)
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

    @Test fun missingSnapshotRefreshHasNoNavigationOrCredentialDuplication() {
        val file = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).firstOrNull { it.isFile } ?: error("Android source missing")
        val source = file.readText()
        val method = source.substringAfter("private fun requestMissingNativeSnapshot() {")
            .substringBefore("/** Only a tap on the native UI can send")
        assertTrue(method.contains("nativeInbox != null"))
        assertTrue(method.contains("15_000L"))
        assertTrue(method.contains("sendNativeMailboxCommand(\"refresh\")"))
        assertFalse(method.contains("loadUrl("))
        assertFalse(method.contains("CookieManager"))
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
        // Cached WebViews must never be mounted as invisible AndroidViews over
        // native Compose administrator screens. Only explicit web mode can.
        assertTrue(source.contains("if (transferWebVisible) {"))
        assertFalse(source.contains("if (adminWebVisible) {"))
        assertFalse(source.contains("adminWebVisible"))
        assertFalse(source.contains("factory = { obtainBrowser(MailSection.ADMIN) }"))
        assertTrue(source.contains("if (target == MailSection.ADMIN)"))
        assertTrue(source.contains("openAdvancedAdminWebsite(url)"))
        assertTrue(source.contains("Administrator must use native Compose, not WebView"))
        assertFalse(source.contains("pendingAdminUrl"))
        assertTrue(source.contains("Intent.ACTION_VIEW"))

        assertTrue(source.contains("NativeAdminContent()"))
        assertFalse(source.contains("if (transferWebVisible ||"))
        assertFalse(source.contains("if (adminWebVisible ||"))
        assertFalse(source.contains("sectionBrowsers.containsKey(MailSection.ADMIN)"))
        assertFalse(source.contains("sectionBrowsers.containsKey(MailSection.TRANSFER)"))
        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
        assertTrue(source.contains("Administrator must use native Compose, not WebView"))
        assertTrue(source.contains("openAdvancedAdminWebsite(url)"))
        assertTrue(source.contains("NativeTransferContent()"))
        assertTrue(source.contains("NativeAdminContent()"))
        assertTrue(source.contains("NativeSectionRouting.explicitWebsiteTarget(url)"))
    }
}
