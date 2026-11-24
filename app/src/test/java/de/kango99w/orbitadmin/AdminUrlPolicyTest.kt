package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminUrlPolicyTest {
    @Test fun onlyExactHttpsAdminHostIsTrusted() {
        assertTrue(AdminUrlPolicy.isTrustedMainFrame("https://inbox.kango99w.de/admin/"))
        assertTrue(AdminUrlPolicy.isTrustedMainFrame("HTTPS://INBOX.KANGO99W.DE/api/admin"))
        assertTrue(AdminUrlPolicy.isTrustedMainFrame("https://inbox.kango99w.de:443/admin/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("http://inbox.kango99w.de/admin/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("https://inbox.kango99w.de.evil.invalid/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("https://x-inbox.kango99w.de/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("https://inbox.kango99w.de:8443/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("https://evil.invalid@inbox.kango99w.de/"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("javascript:alert(1)"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("file:///data/data/secrets"))
        assertFalse(AdminUrlPolicy.isTrustedMainFrame("not a url"))
    }

    @Test fun onlySafeExternalLinksMayLeaveTheApp() {
        assertTrue(AdminUrlPolicy.isExternalHttps("https://example.org/login"))
        assertFalse(AdminUrlPolicy.isExternalHttps("https://inbox.kango99w.de/admin/"))
        assertFalse(AdminUrlPolicy.isExternalHttps("http://example.org/"))
        assertFalse(AdminUrlPolicy.isExternalHttps("intent://example.org/"))
        assertFalse(AdminUrlPolicy.isExternalHttps("https://user@example.org/path"))
        assertFalse(AdminUrlPolicy.isExternalHttps("https://example.org:8080/"))
    }
}
