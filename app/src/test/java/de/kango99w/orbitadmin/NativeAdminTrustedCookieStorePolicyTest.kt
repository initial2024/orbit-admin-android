package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class NativeAdminTrustedCookieStorePolicyTest {
    @Test fun acceptedTokenRequiresExactPathFlagsAndBoundedLifetime() {
        val token="a".repeat(43)
        val header="kango_admin_trusted_device=$token; Max-Age=2592000; Path=/api/admin; HttpOnly; Secure; SameSite=Strict"
        assertEquals(token to 2_592_001_000L,
            NativeAdminTrustedCookieStore.parseHeader(header,1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header.replace("Path=/api/admin","Path=/"),1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header.replace("Secure; ",""),1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header.replace("SameSite=Strict","SameSite=None"),1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header.replace("Max-Age=2592000","Max-Age=2592001"),1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header + "; Domain=.kango99w.de",1000L))
        assertNull(NativeAdminTrustedCookieStore.parseHeader(
            header.replace(token, "short"),1000L))
    }
    @Test fun clearingCookieIsExplicitNotAnArbitrarySetCookie() {
        assertTrue(NativeAdminTrustedCookieStore.isClearHeader(
            "kango_admin_trusted_device=; Max-Age=0; Path=/api/admin; HttpOnly; Secure; SameSite=Strict"))
        assertFalse(NativeAdminTrustedCookieStore.isClearHeader(
            "kango_admin_session=; Max-Age=0; Path=/api/admin; HttpOnly; Secure"))
    }
}
