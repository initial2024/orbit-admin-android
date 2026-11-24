package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AvenqirBrowserModeContractTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/"+path))
        .first(File::isFile).readText()

    @Test fun browserModeIsDefaultAndNativeWebViewEntryIsExplicitlyGuarded() {
        val gradle = source("build.gradle.kts")
        val activity = source("src/main/java/de/kango99w/orbitadmin/AvenqirActivity.kt")
        val bridge = source("src/main/java/de/kango99w/orbitadmin/AvenqirNativePasskeyBridge.kt")
        assertTrue(gradle.contains("AVENQIR_NATIVE_PASSKEY_ENABLED"))
        assertTrue(gradle.contains("providers.gradleProperty(\"orbit.avenqirNativePasskey\").orNull == \"true\""))
        assertTrue(activity.contains("if (BuildConfig.AVENQIR_NATIVE_PASSKEY_ENABLED &&"))
        assertTrue(bridge.indexOf("AvenqirNativePasskeyPolicy.BROWSER_OBJECT") <
            bridge.indexOf("if (!BuildConfig.AVENQIR_NATIVE_PASSKEY_ENABLED)"))
        assertTrue(bridge.indexOf("if (!BuildConfig.AVENQIR_NATIVE_PASSKEY_ENABLED)") <
            bridge.indexOf("WebViewCompat.addWebMessageListener(page, AvenqirNativePasskeyPolicy.OBJECT"))
    }

    @Test fun browserEntryUsesFixedHttpsDestinationWithoutSharingSessions() {
        val tab = source("src/main/java/de/kango99w/orbitadmin/AvenqirPasskeyBrowserTab.kt")
        assertTrue(tab.contains("Uri.parse(AvenqirNativePasskeyPolicy.PAGE)"))
        assertTrue(tab.contains("CustomTabsIntent.Builder()"))
        assertTrue(tab.contains("view().setPackage(pkg)"))
        for (forbidden in listOf("CookieManager", "setCookie(", "addJavascriptInterface", "EXTRA_HEADERS"))
            assertFalse(tab.contains(forbidden))
    }
}
