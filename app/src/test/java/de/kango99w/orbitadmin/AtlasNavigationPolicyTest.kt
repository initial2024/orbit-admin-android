package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class AtlasNavigationPolicyTest {
    @Test fun atlasMainFrameIsExactOriginOnly() {
        assertTrue(AtlasNavigationPolicy.isAtlas(AtlasNavigationPolicy.ENTRY))
        assertTrue(AtlasNavigationPolicy.isAtlas("https://avenqir.bg5.net/leave.html?url=https%3A%2F%2Fexample.org"))
        assertFalse(AtlasNavigationPolicy.isAtlas("http://avenqir.bg5.net/"))
        assertFalse(AtlasNavigationPolicy.isAtlas("https://avenqir.bg5.net.evil.test/"))
        assertFalse(AtlasNavigationPolicy.isAtlas("https://avenqir.bg5.net@evil.test/"))
        assertFalse(AtlasNavigationPolicy.isAtlas("https://avenqir.bg5.net:444/"))
        assertFalse(AtlasNavigationPolicy.isAtlas("javascript:alert(1)"))
        assertFalse(AtlasNavigationPolicy.isAtlas("file:///sdcard/Download/test.html"))
    }
    @Test fun externalDestinationRequiresExplicitSafeHttps() {
        assertTrue(AtlasNavigationPolicy.isExternalHttps("https://www.singpost.com/"))
        assertFalse(AtlasNavigationPolicy.isExternalHttps(AtlasNavigationPolicy.ENTRY))
        assertFalse(AtlasNavigationPolicy.isExternalHttps("http://www.singpost.com/"))
        assertFalse(AtlasNavigationPolicy.isExternalHttps("intent://settings/"))
        assertFalse(AtlasNavigationPolicy.isExternalHttps("https://user@example.org/"))
        assertFalse(AtlasNavigationPolicy.isExternalHttps("https://example.org:8443/"))
    }
}
