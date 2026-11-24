package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AvenqirResumeRoutePolicyTest {
    @Test fun resumeOnlyExactAllowlistedFirstPartyPathsWithoutQueryOrFragment() {
        assertEquals("https://avenqir.bg5.net/directory.html",
            AvenqirResumeRoutePolicy.publicPageWithoutState(
                "https://avenqir.bg5.net/directory.html?q=secret#private"))
        assertEquals("https://avenqir.bg5.net/owner-projects.php",
            AvenqirResumeRoutePolicy.publicPageWithoutState(
                "https://avenqir.bg5.net/owner-projects.php?temporary=session#private"))
        assertEquals("https://avenqir.bg5.net/owner-sites.php",
            AvenqirResumeRoutePolicy.publicPageWithoutState(
                "https://avenqir.bg5.net/owner-sites.php?temporary=session#private"))
        assertEquals("https://avenqir.bg5.net/admin.php",
            AvenqirResumeRoutePolicy.publicPageWithoutState(
                "https://avenqir.bg5.net/admin.php?token=do-not-persist"))
        assertEquals("https://avenqir.bg5.net/",
            AvenqirResumeRoutePolicy.publicPageWithoutState("https://avenqir.bg5.net/"))
        assertNull(AvenqirResumeRoutePolicy.publicPageWithoutState(
            "https://avenqir.bg5.net/public-api.php?token=x"))
        assertNull(AvenqirResumeRoutePolicy.publicPageWithoutState(
            "https://avenqir.bg5.net/../../admin.php"))
        assertNull(AvenqirResumeRoutePolicy.publicPageWithoutState(
            "https://avenqir.bg5.net.evil.example/admin.php"))
        assertNull(AvenqirResumeRoutePolicy.publicPageWithoutState(
            "http://avenqir.bg5.net/directory.html"))
        assertNull(AvenqirResumeRoutePolicy.publicPageWithoutState(
            "javascript:alert(1)"))
    }

    @Test fun liveWebViewNeverReloadsOnReturnAndRestoredPageWaitsForBiometric() {
        val src = sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt")
        ).first(File::isFile).readText()
        val resume = src.substringAfter("override fun onResume()")
            .substringBefore("override fun onStop()")
        val stop = src.substringAfter("override fun onStop()")
            .substringBefore("override fun onDestroy()")
        assertTrue(resume.contains("requestPageUnlock()"))
        assertFalse(resume.contains("loadUrl("))
        assertFalse(stop.contains("finish()"))
        assertFalse(stop.contains("loadUrl("))
        assertTrue(src.contains("if (safeRestore != null) webView?.loadUrl(safeRestore)"))
        assertTrue(src.contains("onSaveInstanceState(outState: Bundle)"))
        assertTrue(src.contains("outState.putString(\"avenqir_public_page\", lastSafePageUrl)"))
        assertTrue(src.contains("if (savedInstanceState == null)"))
        assertTrue(src.contains("backgroundLocked = savedInstanceState != null"))
    }
}
