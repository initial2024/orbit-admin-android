package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInboxPolicyTest {
    private val json = """
        {"v":1,"kind":"mail_snapshot","scope":"public",
          "current":{"id":"a-123","address":"reader@example.test","locked":false},
          "selectedDomain":"example.test","availableDomains":["example.test"],"canCreate":true,
          "busy":false,"statusText":"",
          "mailboxes":[{"id":"a-123","address":"reader@example.test","protected":false}],
          "messages":[{"id":"m-11","sender":"Team","subject":"Test notice","receivedAt":"2026-11-07T09:00:00Z"}]}
    """.trimIndent()

    @Test fun exactMainframeOriginAndOptInRequired() {
        val good = "https://inbox.kango99w.de/?orbit_app=android#orbit-inbox"
        assertTrue(NativeInboxPolicy.acceptsSource("https","inbox.kango99w.de",-1,true,good))
        assertFalse(NativeInboxPolicy.acceptsSource("https","inbox.kango99w.de",-1,false,good))
        assertFalse(NativeInboxPolicy.acceptsSource("https","inbox.kango99w.de",8443,true,good))
        assertFalse(NativeInboxPolicy.acceptsSource("https","inbox.kango99w.de",-1,true,
            "https://inbox.kango99w.de/admin/?orbit_app=android"))
        assertFalse(NativeInboxPolicy.acceptsSource("https","evil.test",-1,true,good))
        assertFalse(NativeInboxPolicy.acceptsSource("http","inbox.kango99w.de",-1,true,good))
        assertFalse(NativeInboxPolicy.acceptsSource("https","inbox.kango99w.de",-1,true,
            "https://inbox.kango99w.de/"))
    }

    @Test fun strictDisplayMetadataParsing() {
        val event = NativeInboxPolicy.parse(json) as NativeInboxEvent.Snapshot
        assertEquals("reader@example.test",event.data.currentAddress)
        assertEquals("m-11",event.data.messages.first().id)
        assertEquals("Test notice",event.data.messages.first().subject)
        assertEquals(1,event.data.mailboxes.size)
        assertEquals("example.test",event.data.selectedDomain)
        assertTrue(event.data.canCreate)
    }

    @Test fun rejectsCommandsUnboundedDataAndCredentialFields() {
        assertNull(NativeInboxPolicy.parse(json.replace("mail_snapshot","delete_mail")))
        assertNull(NativeInboxPolicy.parse(json.replace("\"scope\":\"public\"","\"scope\":\"admin\"")))
        assertNull(NativeInboxPolicy.parse(json.replace("\"subject\":", "\"access_token\":\"secret\",\"subject\":")))
        assertNull(NativeInboxPolicy.parse(json.replace("\"m-11\"","\"../evil\"")))
        assertNull(NativeInboxPolicy.parse("x".repeat(20001)))
        assertNull(NativeInboxPolicy.parse("{\"v\":1,\"kind\":\"clear\",\"token\":\"bad\"}"))
        assertEquals(NativeInboxEvent.Clear,NativeInboxPolicy.parse("{\"v\":1,\"kind\":\"clear\"}"))
    }
    @Test fun emptyAccountCanCreateFirstMailbox() {
        val empty = json.replace("\"id\":\"a-123\"","\"id\":\"\"")
            .replace("\"mailboxes\":[{\"id\":\"a-123\",\"address\":\"reader@example.test\",\"protected\":false}]",
                "\"mailboxes\":[]")
        assertTrue(NativeInboxPolicy.parse(empty) is NativeInboxEvent.Snapshot)
    }
    @Test fun rejectsInvalidCreationMetadata() {
        assertNull(NativeInboxPolicy.parse(json.replace("\"canCreate\":true","\"canCreate\":\"yes\"")))
        assertNull(NativeInboxPolicy.parse(json.replace("\"selectedDomain\":\"example.test\"",
            "\"selectedDomain\":\"evil.test\"")))
        assertNull(NativeInboxPolicy.parse(json.replace("\"availableDomains\":[\"example.test\"]",
            "\"availableDomains\":[\"example.test\",\"example.test\"]")))
    }

}
