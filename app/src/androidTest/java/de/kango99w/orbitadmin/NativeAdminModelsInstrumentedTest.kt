package de.kango99w.orbitadmin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic DTOs only; this suite cannot read a real administrator's mail. */
@RunWith(AndroidJUnit4::class)
class NativeAdminModelsInstrumentedTest {
    @Test fun privateAndOrdinaryMailboxesAreTwoDistinctNativeSections() {
        val privateInbox=JSONObject(
            """{"mailboxes":[{"id":"11111111-1111-4111-8111-111111111111",
                "address":"alice@pieterj.nl.eu.org","unread_count":3,"message_count":9,"status":"active"},
                {"id":"../admin","address":"bad@invalid"}]}"""
        )
        val ordinary=JSONObject(
            """{"mailboxes":[{"id":"22222222-2222-4222-8222-222222222222",
                "address":"temp@tmp.kango99w.de","status":"active"}]}"""
        )
        val first=AdminNativeModels.mailboxes(privateInbox,true)
        val second=AdminNativeModels.mailboxes(ordinary,false)
        assertEquals(1,first.size)
        assertEquals(1,second.size)
        assertTrue(first[0].privateMailbox)
        assertFalse(second[0].privateMailbox)
        assertEquals(3,first[0].unread)
        assertFalse(AdminNativeModels.validId("../admin"))
    }

    @Test fun messageDetailReturnsOnlyPlainTextFieldsForCompose() {
        val item=JSONObject(
            """{"message":{"id":"11111111-1111-4111-8111-111111111111",
              "sender":"verify@example.com","recipient":"a@pieterj.nl.eu.org",
              "subject":"OTP","text_body":"","html_text_body":"Your code 482619",
              "html_body":"<script>alert('unsafe')</script>",
              "possible_code":"482619","received_at":1794000000000}}"""
        )
        val decoded=AdminNativeModels.detail(item)
        assertNotNull(decoded)
        assertEquals("Your code 482619",decoded?.htmlText)
        assertEquals("482619",decoded?.possibleCode)
        assertFalse(decoded?.htmlText.orEmpty().contains("<script>"))
        assertNull(AdminNativeModels.detail(JSONObject("""{"message":{"id":"../invalid"}}""")))
    }

    @Test fun ordinaryAdminHtmlOnlyEmailShowsSafeNativeText() {
        val item=JSONObject(
            """{"message":{"id":"55555555-5555-4555-8555-555555555555",
            "sender":"verification@example.net","recipient":"native@temp.example.net",
            "subject":"Account security",
            "text_body":"",
            "html_body":"<html><body><p>Your <b>verification code</b> is 123456.</p><img src='https://tracker.invalid/pixel'></body></html>"}}"""
        )
        val detail=AdminNativeModels.detail(item)
        assertNotNull(detail)
        assertTrue(detail?.htmlText.orEmpty().contains("verification code"))
        assertTrue(detail?.htmlText.orEmpty().contains("123456"))
        assertFalse(detail?.htmlText.orEmpty().contains("https://tracker.invalid"))
        assertFalse(detail?.htmlText.orEmpty().contains("<img"))
        assertEquals("",detail?.text)
    }

    @Test fun adminReadsNeverAllowUnboundedMessageIdOrPayloadLength() {
        val malformed=JSONObject("""{"messages":[
          {"id":"../../credential","sender":"x"},
          {"id":"33333333-3333-4333-8333-333333333333",
           "sender":"valid@example.com","subject":"Test","received_at":1794000000000}]}""")
        val rows=AdminNativeModels.messages(malformed)
        assertEquals(1,rows.size)
        assertEquals("valid@example.com",rows[0].sender)
    }
}
