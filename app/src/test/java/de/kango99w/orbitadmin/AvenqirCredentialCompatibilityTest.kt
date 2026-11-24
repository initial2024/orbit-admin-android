package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AvenqirCredentialCompatibilityTest {
    @Test fun enableOnlyIfSupportedWithoutReplacingExistingLogin() {
        val file = sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt")
        ).first(File::isFile).readText()
        assertTrue(file.contains("WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)"))
        assertTrue(file.contains("WebSettingsCompat.setWebAuthenticationSupport("))
        assertTrue(file.contains("WEB_AUTHENTICATION_SUPPORT_FOR_APP"))
        assertTrue(file.contains("page.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES"))
        assertTrue(file.contains("setAcceptThirdPartyCookies(page, false)"))
        assertFalse(file.contains("handler.proceed()"))
        assertFalse(file.contains("ignoreSslErrors"))
        assertFalse(file.contains("addJavascriptInterface("))
        assertTrue(file.contains("WEB_AUTHENTICATION"))
    }
}
