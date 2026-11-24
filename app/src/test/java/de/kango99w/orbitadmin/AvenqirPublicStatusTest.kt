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
    @Test fun boundedForegroundRefreshDoesNotRepeatOnResume() {
        val gate=AvenqirStatusGate()
        assertTrue(gate.tryStart(0))
        assertFalse(gate.tryStart(1))
        assertFalse(gate.tryStart(299999))
        assertTrue(gate.tryStart(300000))
        assertFalse(gate.tryStart(300001))
    }
    @Test fun noHiddenWebviewOrMailCredentialBridge() {
        val path="src/main/java/de/kango99w/orbitadmin/"
        fun source(name:String)=listOf(File(path+name),File("app/"+path+name)).first(File::isFile).readText()
        val client=source("AvenqirPublicStatusClient.kt")
        val main=source("MainActivity.kt")
        assertTrue(client.contains("https://avenqir.bg5.net/public-api.php"))
        assertTrue(client.contains("instanceFollowRedirects = false"))
        assertTrue(client.contains("CookieHandler.getDefault() != null"))
        assertFalse(client.contains("CookieManager.getInstance"))
        assertFalse(client.contains("Authorization"))
        assertFalse(client.contains("setHostnameVerifier"))
        assertTrue(main.contains("!unlocked || !avenqirAutoCheck"))
        assertTrue(main.contains("avenqirStatusIo.shutdownNow()"))
        assertTrue(main.contains("启动时检查 AVENQIR 公开目录"))
    }
}
