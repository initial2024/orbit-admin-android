package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGlyphClientContractTest {
    @Test fun onlyOrdinarySiteSessionTokenCanBeSentToGlyphApi() {
        assertNull(NativeGlyphClient.ordinaryCookie(null))
        assertNull(NativeGlyphClient.ordinaryCookie(
            "kango_admin_session=secret; mail_session=private"))
        assertEquals("kango_site_access=abcdefghijklmnopqrstuvwxyz123456",
            NativeGlyphClient.ordinaryCookie(
            "kango_admin_session=secret; kango_site_access=abcdefghijklmnopqrstuvwxyz123456; " +
            "mail_session=another"))
        assertNull(NativeGlyphClient.ordinaryCookie(
            "kango_site_access=a; kango_site_access=abcdefghijklmnopqrstuvwxyz123456"))
    }
    @Test fun accountGlyphRoutesAreStrictlyLimited() {
        assertTrue(NativeGlyphClient.allowed("GET","/api/lucky-glyphs/status"))
        assertTrue(NativeGlyphClient.allowed("GET","/api/lucky-glyphs/collections"))
        assertTrue(NativeGlyphClient.allowed("POST","/api/lucky-glyphs/checkin"))
        assertTrue(NativeGlyphClient.allowed("POST","/api/lucky-glyphs/draw"))
        assertTrue(NativeGlyphClient.allowed("POST","/api/lucky-glyphs/equip"))
        assertFalse(NativeGlyphClient.allowed("POST","/api/lucky-glyphs/sell"))
        assertFalse(NativeGlyphClient.allowed("GET","/api/admin/status"))
        assertFalse(NativeGlyphClient.allowed("POST","/api/account/native-device/revoke-self"))
        assertFalse(NativeGlyphClient.allowed("GET","/api/lucky-glyphs/status?debug=1"))
    }
}
