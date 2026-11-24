package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AvenqirPublicStatusTest {
    @Test fun onlyValidPublicDirectoryMetadataCounts() {
        assertEquals(AvenqirPublicStatus(2), AvenqirPublicStatusClient.parse("""{"ok":true,"items":[{},{}]}"""))
        assertEquals(AvenqirPublicStatus(0), AvenqirPublicStatusClient.parse("""{"ok":true,"items":[]}"""))
        assertNull(AvenqirPublicStatusClient.parse("""{"ok":false,"items":[]}"""))
        assertNull(AvenqirPublicStatusClient.parse("""{"ok":"true","items":[]}"""))
        assertNull(AvenqirPublicStatusClient.parse("""{"ok":true}"""))
        assertNull(AvenqirPublicStatusClient.parse("<html>failure</html>"))
        assertNull(AvenqirPublicStatusClient.parse("""{"ok":true,"items":[""" +
            List(301) { "{}" }.joinToString(",") + "]}"))
    }
    @Test fun compactPublicStatusAndFallbackPolicy() {
        val p=AvenqirPublicStatusClient
        assertEquals(AvenqirPublicStatus(12),p.parse("""{"ok":true,"schema":"avenqir-directory-status-v1","count":12}"""))
        assertNull(p.parse("""{"ok":true,"schema":"avenqir-directory-status-v1","count":"12"}"""))
        assertNull(p.parse("""{"ok":true,"schema":"avenqir-directory-status-v1","count":301}"""))
        assertNull(p.parse("""{"ok":true,"schema":"unrecognized","count":12}"""))
        assertTrue(p.mayUseLegacyEndpoint(404))
        for (code in listOf(0,200,302,403,429,500,503)) assertFalse(p.mayUseLegacyEndpoint(code))
    }
    @Test fun distinguishBrowserResponseFromNetworkFailure() {
        assertNull(AvenqirPublicStatusClient.classifyResponse(200,"application/json; charset=utf-8"))
        assertEquals(AvenqirCheckIssue.BROWSER_REQUIRED,AvenqirPublicStatusClient.classifyResponse(200,"text/html"))
        assertEquals(AvenqirCheckIssue.BROWSER_REQUIRED,AvenqirPublicStatusClient.classifyResponse(403,"text/plain"))
        assertEquals(AvenqirCheckIssue.BROWSER_REQUIRED,AvenqirPublicStatusClient.classifyResponse(302,null))
        assertEquals(AvenqirCheckIssue.HTTP,AvenqirPublicStatusClient.classifyResponse(503,"application/json"))
        assertEquals(AvenqirCheckIssue.INVALID_DATA,AvenqirPublicStatusClient.classifyResponse(200,"text/plain"))
    }
    @Test fun boundedForegroundRefreshDoesNotRepeatOnResume() {
        val gate=AvenqirStatusGate()
        assertTrue(gate.tryStart(0))
        assertFalse(gate.tryStart(1))
        assertFalse(gate.tryStart(299999))
        assertTrue(gate.tryStart(300000))
        assertFalse(gate.tryStart(300001))
    }
    @Test fun normalBrowserVisitReportsOnlyPublicReadiness() {
        val path="src/main/java/de/kango99w/orbitadmin/"
        fun source(name:String)=listOf(File(path+name),File("app/"+path+name)).first(File::isFile).readText()
        val main=source("MainActivity.kt")
        val page=source("AvenqirActivity.kt")
        assertTrue(main.contains("avenqir_directory_rendered"))
        assertTrue(main.contains("本次已确认目录页面可用"))
        assertTrue(page.contains("Uri.parse(url).path != \"/directory.html\""))
        assertTrue(page.contains("n&&n.hidden&&r&&window.AVENQIR_ATLAS_CATALOG"))
        assertTrue(page.contains("serial != navigationSerial"))
        assertTrue(page.contains("attempt < 3"))
    }
    @Test fun noHiddenWebviewOrMailCredentialBridge() {
        val path="src/main/java/de/kango99w/orbitadmin/"
        fun source(name:String)=listOf(File(path+name),File("app/"+path+name)).first(File::isFile).readText()
        val client=source("AvenqirPublicStatusClient.kt")
        val main=source("MainActivity.kt")
        assertTrue(client.contains("https://avenqir.bg5.net/public-api.php"))
        assertTrue(client.contains(".followRedirects(false)"))
        assertTrue(client.contains("CookieJar.NO_COOKIES"))
        assertFalse(client.contains("CookieHandler.getDefault()"))
        assertTrue(client.contains("https://avenqir.bg5.net/directory-status.php"))
        assertTrue(client.contains(".authenticator(Authenticator.NONE)"))
        assertTrue(client.contains(".followSslRedirects(false)"))
        assertFalse(client.contains("CookieManager.getInstance"))
        assertFalse(client.contains("Authorization"))
        assertFalse(client.contains("setHostnameVerifier"))
        assertTrue(main.contains("!unlocked || !avenqirAutoCheck"))
        assertTrue(main.contains("avenqirStatusIo.shutdownNow()"))
        assertTrue(main.contains("启动时检查 AVENQIR 公开目录"))
    }
}
