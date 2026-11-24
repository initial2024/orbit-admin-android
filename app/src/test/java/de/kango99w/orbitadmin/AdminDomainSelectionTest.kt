package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDomainSelectionTest {
    @Test fun permanentCreateOptionsRequireEnabledPrivateAdministratorDomains() {
        val response=JSONObject("""{"admin_domains":[
          {"domain":"pieterj.nl.eu.org","enabled":true,"public_selectable":false,"ingress":"cloudflare_email"},
          {"domain":"haha.int.yt","enabled":true,"public_selectable":false,"ingress":"forwardemail_webhook"},
          {"domain":"public.example.org","enabled":true,"public_selectable":true},
          {"domain":"pending.example.org","enabled":false,"public_selectable":false},
          {"domain":"evil..domain","enabled":true,"public_selectable":false},
          {"domain":"pieterj.nl.eu.org","enabled":true,"public_selectable":false}
        ]}""")
        val domains=AdminDomainSelection.permanent(response)
        assertEquals(2,domains.size)
        assertEquals("pieterj.nl.eu.org",domains[0].domain)
        assertEquals("haha.int.yt",domains[1].domain)
        assertFalse(domains.any { it.domain.startsWith("public.") })
        assertTrue(AdminDomainSelection.filter(domains,"haha").any { it.domain=="haha.int.yt" })
    }
    @Test fun temporaryOptionsAreIndependentAndSearchable() {
        val response=JSONObject("""{"domains":[
          {"domain":"temp.example.org","label":"Mailbox One"},
          {"domain":"second.test.org","label":"Mailbox Two"}
        ]}""")
        val options=AdminDomainSelection.temporary(response)
        assertEquals(2,options.size)
        assertEquals(1,AdminDomainSelection.filter(options,"second").size)
        assertEquals(1,AdminDomainSelection.filter(options,"Mailbox One").size)
        assertEquals(2,AdminDomainSelection.filter(options,"").size)
    }
}
