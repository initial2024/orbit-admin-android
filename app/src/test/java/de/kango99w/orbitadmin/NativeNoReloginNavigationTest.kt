package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards: ordinary mailbox operations must not trigger implicit website login. */
class NativeNoReloginNavigationTest {
    private val source: String by lazy {
        val path = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).firstOrNull { it.isFile }
        requireNotNull(path) { "MainActivity.kt not found in Android source tree" }.readText()
    }

    private fun method(from: String, until: String): String {
        val start = source.indexOf(from)
        val end = source.indexOf(until, start + from.length)
        assertTrue("method start should exist: $from", start >= 0)
        assertTrue("method end should exist: $until", end > start)
        return source.substring(start, end)
    }

    @Test fun mailboxSelectionStaysInNativeUi() {
        val section = method("private fun openNativeMailbox(", "@Composable")
        assertTrue(section.contains("sendNativeMailboxCommand(\"select_mailbox\",id)"))
        assertTrue(section.contains("showNativeMail = true"))
        assertTrue(section.contains("openSection(MailSection.INBOX)"))
        assertFalse(section.contains("loadUrl("))
        assertFalse(section.contains("orbit-mailbox-"))
    }

    @Test fun readingAnUnavailableMessageDoesNotRedirectToLogin() {
        val section = method("private fun openNativeMessage(", "private fun openNativeMailbox(")
        assertTrue(section.contains("nativeReadCommandUnavailable"))
        assertTrue(section.contains("showNativeMail = true"))
        assertFalse(section.contains("loadUrl("))
        assertFalse(section.contains("showNativeMail = false"))
        assertFalse(section.contains("orbit-message-"))
    }

    @Test fun clearingWebDataNeverErasesOrdinaryAccountKeystoreGrant() {
        val section = method("private fun clearLocalSession()", "private fun lock()")
        assertTrue(section.contains("CookieManager.getInstance().removeAllCookies"))
        assertTrue(section.contains("WebStorage.getInstance().deleteAllData()"))
        assertFalse(section.contains("nativeDeviceClient.clearLocalBinding()"))
        assertFalse(section.contains("nativeDeviceClient.revokeSelf()"))
    }

    @Test fun failuresHaveExplicitUserControlledWebsiteFallback() {
        val loading = method("private fun NativeMailLoadingContent(", "private fun MailAvatar(")
        assertTrue(loading.contains("nativeReadCommandUnavailable"))
        assertTrue(loading.contains("onClick={showNativeMail=false}"))
    }
}
