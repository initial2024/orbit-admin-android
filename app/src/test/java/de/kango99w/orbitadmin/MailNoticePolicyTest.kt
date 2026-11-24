package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailNoticePolicyTest {
    @Test fun onlyExactOriginMainFrameAndSignalAreAccepted() {
        assertTrue(MailNoticePolicy.accepts("https","inbox.kango99w.de",-1,true,"new_mail"))
        assertTrue(MailNoticePolicy.accepts("https","INBOX.KANGO99W.DE",443,true,"new_mail"))
        assertFalse(MailNoticePolicy.accepts("http","inbox.kango99w.de",-1,true,"new_mail"))
        assertFalse(MailNoticePolicy.accepts("https","inbox.kango99w.de.evil.test",-1,true,"new_mail"))
        assertFalse(MailNoticePolicy.accepts("https","inbox.kango99w.de",444,true,"new_mail"))
        assertFalse(MailNoticePolicy.accepts("https","inbox.kango99w.de",-1,false,"new_mail"))
        assertFalse(MailNoticePolicy.accepts("https","inbox.kango99w.de",-1,true,"show_token"))
        assertFalse(MailNoticePolicy.accepts("https","inbox.kango99w.de",-1,true,"new_mail:123456"))
    }
    @Test fun rapidNotificationsAreThrottled() {
        assertTrue(MailNoticePolicy.canDeliver(5000L,0L))
        assertFalse(MailNoticePolicy.canDeliver(9000L,5000L))
        assertTrue(MailNoticePolicy.canDeliver(25000L,5000L))
        assertFalse(MailNoticePolicy.canDeliver(4999L,5000L))
    }
}
