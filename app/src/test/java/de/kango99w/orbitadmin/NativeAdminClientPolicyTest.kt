package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdminClientPolicyTest {
    @Test fun administratorApiAllowsOnlyKnownReadWriteRoutes() {
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/status"))
        assertTrue(NativeAdminClient.allows("POST", "/api/admin/login"))
        assertTrue(NativeAdminClient.allows("POST", "/api/admin/login/totp"))
        assertTrue(NativeAdminClient.allows("POST", "/api/admin/pieterj-mail/mailboxes"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/ordinary-inbox/mailboxes"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/ordinary-inbox/recorded-addresses"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/pieterj-mail/status"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/2fa/status"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/trusted-devices"))
        assertTrue(NativeAdminClient.allows("GET", "/api/admin/account-registry/registrations"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/account-registry/registrations"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/account-registry/registrations?limit=500"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/account-registry/registrations/secret"))
        assertTrue(NativeAdminClient.allows("POST", "/api/admin/trusted-devices/11111111-1111-4111-8111-111111111111/revoke"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/trusted-devices/11111111-1111-4111-8111-111111111111/revoke"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/trusted-devices/%2e%2e/revoke"))
        assertTrue(NativeAdminClient.allows("PATCH", "/api/admin/pieterj-mail/mailboxes/11111111-1111-4111-8111-111111111111"))
        assertTrue(NativeAdminClient.allows("POST", "/api/admin/ordinary-inbox/mailboxes/11111111-1111-4111-8111-111111111111/renew"))
        assertTrue(NativeAdminClient.allows("DELETE", "/api/admin/ordinary-inbox/mailboxes/11111111-1111-4111-8111-111111111111"))
        assertTrue(NativeAdminClient.allows("DELETE", "/api/admin/pieterj-mail/mailboxes/11111111-1111-4111-8111-111111111111"))
        assertTrue(NativeAdminClient.allows("GET",
            "/api/admin/pieterj-mail/mailboxes/11111111-1111-4111-8111-111111111111/messages"))
        assertTrue(NativeAdminClient.allows("GET",
            "/api/admin/pieterj-mail/messages/11111111-1111-4111-8111-111111111111"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/credentials"))
        assertFalse(NativeAdminClient.allows("GET", "/api/account/native-device/mailboxes"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/users"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/status?x=1"))
        assertFalse(NativeAdminClient.allows("GET", "/api/admin/%2e%2e/status"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/login/anything"))
        assertFalse(NativeAdminClient.allows("DELETE", "/api/admin/pieterj-mail/mailboxes"))
        assertFalse(NativeAdminClient.allows("DELETE", "/api/admin/2fa/status"))
        assertFalse(NativeAdminClient.allows("DELETE", "/api/account/native-device/revoke-self"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/credentials"))
        assertFalse(NativeAdminClient.allows("PATCH", "/api/admin/pieterj-mail/mailboxes/%2e%2e"))
        assertFalse(NativeAdminClient.allows("POST", "/api/admin/ordinary-inbox/mailboxes/11111111-1111-4111-8111-111111111111/renew?minutes=999"))
    }

    @Test fun ordinaryAccountCookiesCannotEnterAdminRequests() {
        val raw = "kango_site_access=ordinary; foo=bar; " +
            "kango_admin_session=admin-token; " +
            "kango_admin_2fa_challenge=challenge; " +
            "kango_admin_trusted_device=trusted"
        val selected=NativeAdminClient.adminCookies(raw)
        assertEquals("kango_admin_session=admin-token; " +
            "kango_admin_2fa_challenge=challenge",selected)
        assertFalse(selected.contains("kango_site_access"))
        assertFalse(selected.contains("foo=bar"))
        assertFalse(selected.contains("kango_admin_trusted_device"))
        assertEquals("", NativeAdminClient.adminCookies("kango_site_access=user"))
    }

    @Test fun responseCanOnlyStoreKnownAdministratorCookies() {
        assertTrue(NativeAdminClient.mayPersistCookie(
            "kango_admin_session=abc; Path=/; HttpOnly; Secure; SameSite=Strict"))
        assertTrue(NativeAdminClient.mayPersistCookie(
            "kango_admin_2fa_challenge=abc; Path=/api/admin; HttpOnly; Secure; SameSite=Strict"))
        assertFalse(NativeAdminClient.mayPersistCookie("kango_site_access=x; Path=/"))
        assertFalse(NativeAdminClient.mayPersistCookie(
            "kango_admin_trusted_device=x; Path=/api/admin; HttpOnly; Secure; SameSite=Strict"))
        assertFalse(NativeAdminClient.mayPersistCookie("evil=x; Path=/"))
        assertFalse(NativeAdminClient.mayPersistCookie(
            "kango_admin_session=x; Domain=.kango99w.de; Path=/; HttpOnly; Secure; SameSite=Strict"))
        assertFalse(NativeAdminClient.mayPersistCookie(
            "kango_admin_session=x; Path=/; Secure; SameSite=Strict"))
        assertFalse(NativeAdminClient.adminCookies(
            "kango_admin_session=bad" + 13.toChar() + 10.toChar() + "injected=1")
            .contains("injected"))
        assertFalse(NativeAdminClient.mayPersistCookie(
            "kango_admin_session=x\\r\\nX-Fake: yes"))
    }
}
