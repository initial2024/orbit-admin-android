package de.kango99w.orbitadmin

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeMailLinksTest {
    @Test fun onlyHTTPSAndPublicDomainMayBeOpened() {
        assertNotNull(NativeMailLinks.approved("https://accounts.example.com/verify?token=a%2Bb"))
        for (url in listOf("javascript:alert(1)","http://accounts.example.com",
            "https://user:pass@example.com/x","https://localhost/x",
            "https://192.168.1.1/x","https://host.internal/","https://host.local/",
            "https://example.com:8080/x","https://example.com/a b")) {
            assertNull(url,NativeMailLinks.approved(url))
        }
    }
    @Test fun parsesDeduplicatesAndBoundsServerLinks() {
        val json=JSONArray("""[
          {"url":"https://accounts.example.com/verify?a=1","label":"确认邮箱"},
          {"url":"https://accounts.example.com/verify?a=1","label":"重复项"},
          {"url":"javascript:alert(1)","label":"危险"},
          {"url":"https://google.example.com/","label":"Google"}]""")
        val links=NativeMailLinks.read(json,"https://accounts.example.com/verify?a=1","")
        assertEquals(2,links.size)
        assertEquals("确认邮箱",links[0].label)
    }
    @Test fun oldBackendPlainTextFallbackIsBounded() {
        val links=NativeMailLinks.read(null,
            "登录 https://portal.example.com/sign-in?t=123。 和 javascript:alert(1)","")
        assertEquals(1,links.size)
        assertTrue(links[0].url.startsWith("https://portal.example.com/sign-in"))
    }
    @Test fun priorityShowsVerificationLinkFirstWithoutClaimingItIsTrusted() {
        val links=listOf(
            NativeSafeMailLink("https://news.example.com/article","news.example.com","新闻"),
            NativeSafeMailLink("https://accounts.example.com/confirm?t=123",
                "accounts.example.com","确认您的账户"),
            NativeSafeMailLink("javascript:alert(1)","evil.test","验证")
        )
        val target=NativeMailLinks.priority(links)
        assertEquals("accounts.example.com",target?.host)
        assertNull(NativeMailLinks.priority(listOf(links.last())))
        assertNull(NativeMailLinks.priority(emptyList()))
    }

}
