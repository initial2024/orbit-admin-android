package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard against regression: leaving the app is NOT explicitly closing AVENQIR. */
class AvenqirBackgroundResumeContractTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt")
        ).first(File::isFile).readText()
    }

    @Test fun backgroundRetainsWebViewAndReauthenticatesInsteadOfFinishing() {
        val onStop = source.substringAfter("override fun onStop()")
            .substringBefore("override fun onDestroy()")
        assertTrue(onStop.contains("backgroundLocked = true"))
        assertTrue(onStop.contains("lockCurtain?.visibility = View.VISIBLE"))
        assertTrue(onStop.contains("webView?.onPause()"))
        assertTrue(onStop.contains("CookieManager.getInstance().flush()"))
        assertFalse(onStop.contains("finish()"))
        assertFalse(onStop.contains("loadUrl("))
        val onResume = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onStop()")
        assertTrue(onResume.contains("requestPageUnlock()"))
        assertFalse(onResume.contains("loadUrl("))
        assertTrue(source.contains("class AvenqirActivity : FragmentActivity()"))
        assertTrue(source.contains("BiometricPrompt.Authenticators") ||
            source.contains("BiometricManager.Authenticators.BIOMETRIC_STRONG"))
        assertTrue(source.contains("backgroundLocked = savedInstanceState != null"))
        assertTrue(source.contains("onAuthenticationSucceeded"))
        assertTrue(source.contains("lockCurtain?.visibility = View.GONE"))
        assertTrue(source.contains("FLAG_SECURE"))
    }

    @Test fun explicitExitAndSafeNavigationRemainDistinct() {
        assertTrue(source.contains("close.setOnClickListener { finish() }"))
        assertTrue(source.contains("if (page.canGoBack()) page.goBack() else finish()"))
        assertTrue(source.contains("AvenqirUrlPolicy.isFirstParty(url)"))
        assertTrue(source.contains("CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)"))
        assertTrue(source.contains("loadUrl(AvenqirUrlPolicy.HOME)"))
        assertTrue(source.contains("menuForeground"))
        assertTrue(source.contains("setTextColor(menuForeground)"))
    }
}
