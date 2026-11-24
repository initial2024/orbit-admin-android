package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AvenqirLoadFailurePolicyTest {
    @Test fun onlyFirstPartyMainFrameErrorsAffectPage() {
        assertTrue(AvenqirLoadFailurePolicy.shouldHandle(
            true, "https://avenqir.bg5.net/"))
        assertFalse(AvenqirLoadFailurePolicy.shouldHandle(
            false, "https://avenqir.bg5.net/"))
        assertFalse(AvenqirLoadFailurePolicy.shouldHandle(
            true, "https://evil.example/"))
        assertFalse(AvenqirLoadFailurePolicy.shouldHandle(
            true, "http://avenqir.bg5.net/"))
    }

    @Test fun retryTargetNeverCarriesCredentialsOrForeignOrigin() {
        assertEquals("https://avenqir.bg5.net/directory.html",
            AvenqirLoadFailurePolicy.safeRetryUrl(
                "https://avenqir.bg5.net/directory.html?token=private#secret"))
        assertNull(AvenqirLoadFailurePolicy.safeRetryUrl(
            "https://avenqir.bg5.net/api/export?token=private"))
        assertNull(AvenqirLoadFailurePolicy.safeRetryUrl(
            "https://avenqir.bg5.net.evil.example/"))
    }

    @Test fun automaticRetryOnlyOnceForGetHomepageAndSafeTransientErrors() {
        val home = AvenqirUrlPolicy.HOME
        assertTrue(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "GET", -1, false))
        assertTrue(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "GET", -2, false))
        assertFalse(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "GET", -2, true))
        assertFalse(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "POST", -2, false))
        assertFalse(AvenqirLoadFailurePolicy.shouldAutoRetryHome(
            home + "admin.php", "GET", -1, false))
        assertFalse(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "GET", -11, false))
        assertFalse(AvenqirLoadFailurePolicy.shouldAutoRetryHome(home, "GET", -16, false))
    }

    @Test fun httpErrorsNeverConfuseLoginAndServiceOutage() {
        assertFalse(AvenqirLoadFailurePolicy.isServerError(401))
        assertFalse(AvenqirLoadFailurePolicy.isServerError(403))
        assertTrue(AvenqirLoadFailurePolicy.isServerError(500))
        assertTrue(AvenqirLoadFailurePolicy.isServerError(503))
    }

    @Test fun webViewRetainsSecureProcessAndExplicitRetryControls() {
        val src = sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt")
        ).first(File::isFile).readText()
        assertTrue(src.contains("override fun onReceivedError("))
        assertTrue(src.contains("override fun onReceivedHttpError("))
        assertTrue(src.contains("override fun onPageStarted("))
        assertTrue(src.contains("handler.cancel()"))
        assertFalse(src.contains("handler.proceed()"))
        assertTrue(src.contains("reportMainFrameFailure("))
        assertTrue(src.contains("setAcceptThirdPartyCookies(page, false)"))
        assertTrue(src.contains("backgroundLocked = savedInstanceState != null"))
        assertTrue(src.contains("page.loadUrl(AvenqirUrlPolicy.HOME)"))
    }
}
