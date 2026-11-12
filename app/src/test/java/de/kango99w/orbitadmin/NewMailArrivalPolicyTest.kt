package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMailArrivalPolicyTest {
    private fun snapshot(mailbox:String="box1", ids:List<String> = emptyList(),
                         locked:Boolean=false): NativeInboxSnapshot =
        NativeInboxSnapshot(mailbox,"test@example.org",locked,"",emptyList(),false,
            true,false,"",emptyList(),ids.map { NativeMailRow(it,"","","") })

    @Test fun noNotificationOnInitialLoadOrRefreshWithSameIds() {
        val first=snapshot(ids=listOf("a","b"))
        assertFalse(NewMailArrivalPolicy.hasArrival(null,first))
        assertFalse(NewMailArrivalPolicy.hasArrival(first,first))
        assertFalse(NewMailArrivalPolicy.hasArrival(first,snapshot(ids=listOf("b","a"))))
    }
    @Test fun announceOnlyNewMessageInSameUnlockedMailbox() {
        val before=snapshot(ids=listOf("a"))
        assertTrue(NewMailArrivalPolicy.hasArrival(before,snapshot(ids=listOf("a","b"))))
        assertFalse(NewMailArrivalPolicy.hasArrival(before,snapshot(mailbox="box2",ids=listOf("b"))))
        assertFalse(NewMailArrivalPolicy.hasArrival(before,snapshot(ids=listOf("a","b"),locked=true)))
        assertFalse(NewMailArrivalPolicy.hasArrival(snapshot(locked=true,ids=listOf("a")),
            snapshot(ids=listOf("a","b"))))
    }
    @Test fun adminReadDoesNotPretendFirstListIsIncomingNotification() {
        assertFalse(NewMailArrivalPolicy.hasAdminArrival(null,"a",emptyList(),listOf("x")))
        assertFalse(NewMailArrivalPolicy.hasAdminArrival("a","b",listOf("x"),listOf("x","y")))
        assertFalse(NewMailArrivalPolicy.hasAdminArrival("a","a",listOf("x"),listOf("x")))
        assertTrue(NewMailArrivalPolicy.hasAdminArrival("a","a",listOf("x"),listOf("x","y")))
    }
}
