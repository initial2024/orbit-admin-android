package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class AdminNativeSessionHandoffTest {
    @Test fun validFirstPartyAdminSessionCanBeUsedForImmediateStatusRead() {
        val header="kango_admin_session=abcd_1234; Path=/; Max-Age=86400; HttpOnly; Secure; SameSite=Strict"
        assertEquals("kango_admin_session=abcd_1234" to 86_400_000L,
            NativeAdminClient.immediateAdminSession(header))
    }
    @Test fun doNotAcceptChallengeTrustThirdPartyOrInfiniteCookie() {
        val good="kango_admin_session=abc123; Path=/; Max-Age=604800; HttpOnly; Secure; SameSite=Strict"
        assertNotNull(NativeAdminClient.immediateAdminSession(good))
        for(bad in listOf(
            good.replace("kango_admin_session","kango_admin_trusted_device"),
            good.replace("Path=/;","Path=/api/admin;"),
            good.replace("Max-Age=604800","Max-Age=604801"),
            good.replace("SameSite=Strict","SameSite=None"),
            good+"; Domain=.kango99w.de",
            good.replace("Max-Age=604800","Max-Age=0"),
            good.replace("Secure; ","")
        ))assertNull(NativeAdminClient.immediateAdminSession(bad))
    }
}
