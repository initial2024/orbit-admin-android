package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePrivateMailClientTest {
    private val handoff="pmh1." + "a".repeat(43)
    @Test fun handoffIsOnlyFromExactPrivateMailOrigin() {
        assertEquals(handoff,NativePrivateMailClient.extractHandoffToken(
            "https://mail.kango99w.de/#handoff=$handoff"))
        for (url in listOf(
            "http://mail.kango99w.de/#handoff=$handoff",
            "https://mail.kango99w.de.evil.test/#handoff=$handoff",
            "https://attacker@mail.kango99w.de/#handoff=$handoff",
            "https://mail.kango99w.de:444/#handoff=$handoff",
            "https://mail.kango99w.de/other#handoff=$handoff",
            "https://mail.kango99w.de/?state=2#handoff=$handoff",
            "https://mail.kango99w.de/#handoff=pmh1.invalid",
            "https://mail.kango99w.de/#handoff=$handoff&other=x"
        )) assertNull(url,NativePrivateMailClient.extractHandoffToken(url))
    }

    @Test fun ordinaryCredentialsAndDisallowedSendersNeverBecomeSenders() {
        assertEquals(6,NativePrivateMailClient.SENDERS.size)
        assertTrue(NativePrivateMailClient.SENDERS.contains("study@kango99w.de"))
        assertFalse(NativePrivateMailClient.SENDERS.contains("anything@kango99w.de"))
        assertTrue(NativePrivateMailClient.validEmailList("a@example.org, b@example.org",true))
        assertFalse(NativePrivateMailClient.validEmailList("",true))
        assertFalse(NativePrivateMailClient.validEmailList("bad@@example.com",true))
        assertFalse(NativePrivateMailClient.validEmailList(
            (1..21).joinToString(",") { "user$it@example.org" },true))
    }

    @Test fun privateSenderCookieMustRemainHostOnlyAndHttpOnly() {
        assertTrue(NativePrivateMailClient.validSessionSetCookie(
            "mail_session=v1.12345.signature; HttpOnly; Secure; SameSite=Lax; Path=/"))
        assertFalse(NativePrivateMailClient.validSessionSetCookie(
            "kango_admin_session=admin; HttpOnly; Secure; SameSite=Lax; Path=/"))
        assertFalse(NativePrivateMailClient.validSessionSetCookie(
            "mail_session=x; Domain=.kango99w.de; HttpOnly; Secure; SameSite=Lax; Path=/"))
        assertFalse(NativePrivateMailClient.validSessionSetCookie(
            "mail_session=x; Secure; SameSite=Lax; Path=/"))
        assertNull(NativePrivateMailClient.filterSessionCookie("kango_admin_session=x; kango_site_access=y"))
    }
}
