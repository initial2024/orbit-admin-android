package de.kango99w.orbitadmin
import org.junit.Assert.*
import org.junit.Test
class BrowserSessionRoutingTest {
    @Test fun publicInboxAndMailboxTabsShareLiveDocument() {
        assertEquals(BrowserSessionRouting.key(MailSection.INBOX),
            BrowserSessionRouting.key(MailSection.MAILBOXES))
    }
    @Test fun adminAndTransferKeepSeparateDocuments() {
        assertNotEquals(BrowserSessionRouting.key(MailSection.INBOX),
            BrowserSessionRouting.key(MailSection.ADMIN))
        assertNotEquals(BrowserSessionRouting.key(MailSection.INBOX),
            BrowserSessionRouting.key(MailSection.TRANSFER))
        assertNotEquals(BrowserSessionRouting.key(MailSection.ADMIN),
            BrowserSessionRouting.key(MailSection.TRANSFER))
    }
}
