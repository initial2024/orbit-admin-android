package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvenqirUrlPolicyTest {
    @Test fun onlyExactHttpsAvenqirOriginIsInternal() {
        assertTrue(AvenqirUrlPolicy.isFirstParty("https://avenqir.bg5.net/"))
        assertTrue(AvenqirUrlPolicy.isFirstParty("https://avenqir.bg5.net/directory.html?lang=ja"))
        assertTrue(AvenqirUrlPolicy.isFirstParty("https://avenqir.bg5.net:443/leave.html"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("http://avenqir.bg5.net/"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("https://avenqir.bg5.net.evil.example/"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("https://evil.example@avenqir.bg5.net/"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("https://avenqir.bg5.net:8443/"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("https://inbox.kango99w.de/"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("javascript:alert(1)"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("file:///data/local/tmp/a"))
        assertFalse(AvenqirUrlPolicy.isFirstParty("intent://somewhere"))
    }
    @Test fun onlyHttpsWithoutCredentialsCanOpenExternally() {
        assertTrue(AvenqirUrlPolicy.isExternalHttps("https://example.org/"))
        assertFalse(AvenqirUrlPolicy.isExternalHttps("https://avenqir.bg5.net/"))
        assertFalse(AvenqirUrlPolicy.isExternalHttps("https://user@example.org/"))
        assertFalse(AvenqirUrlPolicy.isExternalHttps("http://example.org/"))
        assertFalse(AvenqirUrlPolicy.isExternalHttps("https://example.org:8443/"))
        assertFalse(AvenqirUrlPolicy.isExternalHttps("data:text/html,hello"))
    }
}