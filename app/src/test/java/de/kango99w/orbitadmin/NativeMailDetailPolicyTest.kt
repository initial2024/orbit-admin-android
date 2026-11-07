package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class NativeMailDetailPolicyTest {
    private val json = """{"v":1,"kind":"mail_detail","scope":"public",
      "mailboxId":"mb-1","id":"msg-2","sender":"team@example.test",
      "recipient":"user@example.test","subject":"Hello",
      "receivedAt":"2026-11-07T10:00:00Z","textBody":"line one\\nline two"}"""
    @Test fun plainTextDetailParsed() {
        val item=NativeMailDetailPolicy.parse(json) as NativeMailDetailEvent.Detail
        assertEquals("mb-1",item.data.mailboxId)
        assertEquals("msg-2",item.data.id)
        assertTrue(item.data.textBody.contains("line two"))
    }
    @Test fun rejectsPrivilegeEscalationAndUnboundedSensitiveData() {
        assertNull(NativeMailDetailPolicy.parse(json.replace("\"scope\":\"public\"","\"scope\":\"admin\"")))
        assertNull(NativeMailDetailPolicy.parse(json.replace("\"msg-2\"","\"../other\"")))
        assertNull(NativeMailDetailPolicy.parse(json.replace("\"subject\":","\"access_token\":\"leak\",\"subject\":")))
        assertNull(NativeMailDetailPolicy.parse(json.replace("\"textBody\":\"line one\\\\nline two\"",
            "\"textBody\":\""+ "A".repeat(8001) +"\"")))
        assertEquals(NativeMailDetailEvent.Clear,NativeMailDetailPolicy.parse("{\"v\":1,\"kind\":\"clear\"}"))
    }
}
