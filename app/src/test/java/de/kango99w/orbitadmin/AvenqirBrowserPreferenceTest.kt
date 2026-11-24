package de.kango99w.orbitadmin
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AvenqirBrowserPreferenceTest {
    @Test fun missingOrRemovedBrowserFallsBackToAsk() {
        val p=AvenqirBrowserPreference
        assertEquals(p.ASK,p.normalize(null,emptySet()))
        assertEquals(p.ASK,p.normalize("package:old.browser",emptySet()))
        assertEquals("package:org.mozilla.firefox",p.normalize("package:org.mozilla.firefox",setOf("org.mozilla.firefox")))
        assertEquals(p.BUILTIN,p.normalize(p.BUILTIN,emptySet()))
        assertEquals(p.SYSTEM,p.normalize(p.SYSTEM,emptySet()))
        assertEquals(p.ASK,p.normalize("unknown",emptySet()))
    }
    @Test fun publicViewerIsNotThePrivilegedWebview() {
        fun source(path:String)=listOf(File("src/main/"+path),File("app/src/main/"+path)).first(File::isFile).readText()
        val d="java/de/kango99w/orbitadmin/"
        val manifest=source("AndroidManifest.xml")
        val app=source(d+"OrbitApplication.kt")
        val viewer=source(d+"AvenqirPublicBrowserActivity.kt")
        val trusted=source(d+"AvenqirActivity.kt")
        assertTrue(manifest.contains("android:process=\":publicbrowser\""))
        assertTrue(app.contains("setDataDirectorySuffix(\"avenqir_public_browser\")"))
        assertTrue(viewer.contains("setAcceptCookie(false)"))
        assertTrue(viewer.contains("handler.cancel()"))
        assertFalse(viewer.contains("handler.proceed()"))
        assertFalse(viewer.contains("addJavascriptInterface"))
        assertTrue(viewer.contains("allowFileAccess = false"))
        assertTrue(viewer.contains("AvenqirUrlPolicy.isExternalHttps"))
        assertTrue(trusted.contains("AvenqirBrowserLauncher(this) { !backgroundLocked }.open(url)"))
        assertTrue(trusted.contains("打开第三方网站？"))
    }
}
