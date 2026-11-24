package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class NativeIncrementalMergeTest {
    private fun snapshot(boxes:List<NativeMailboxRow>, mails:List<NativeMailRow>)=
        NativeInboxSnapshot(
            currentId="box1",currentAddress="one@example.test",locked=false,
            selectedDomain="",availableDomains=emptyList(),canCreate=false,
            nativeReadSupported=true,busy=false,statusText="online",
            mailboxes=boxes,messages=mails)
    @Test fun unchangedRowsAreReferenceStableAndNewMailIsAdded() {
        val b=NativeMailboxRow("box1","one@example.test",false)
        val m=NativeMailRow("mail1","a@example.test","Subject1","2026-11-08")
        val old=snapshot(listOf(b),listOf(m))
        val refreshed=snapshot(
            listOf(NativeMailboxRow("box1","one@example.test",false)),
            listOf(NativeMailRow("mail1","a@example.test","Subject1","2026-11-08"),
                NativeMailRow("mail2","b@example.test","Subject2","2026-11-08")))
        val merged=NativeIncrementalMerge.snapshot(old,refreshed)
        assertSame(b,merged.mailboxes[0])
        assertSame(m,merged.messages[0])
        assertEquals("mail2",merged.messages[1].id)
        assertSame(old,NativeIncrementalMerge.snapshot(old,
            snapshot(listOf(NativeMailboxRow("box1","one@example.test",false)),
                listOf(NativeMailRow("mail1","a@example.test","Subject1","2026-11-08")))))
    }
    @Test fun removedAndChangedRowsAlwaysFollowServer() {
        val old=snapshot(listOf(
            NativeMailboxRow("box1","one@example.test",false),
            NativeMailboxRow("box2","two@example.test",false)),listOf(
            NativeMailRow("mail1","a@example.test","Old","date"),
            NativeMailRow("mail2","b@example.test","Gone","date")))
        val after=snapshot(listOf(NativeMailboxRow("box1","one@example.test",false)),
            listOf(NativeMailRow("mail1","a@example.test","Edited","date")))
        val merged=NativeIncrementalMerge.snapshot(old,after)
        assertEquals(1,merged.mailboxes.size)
        assertEquals(1,merged.messages.size)
        assertEquals("Edited",merged.messages[0].subject)
        assertNotSame(old.messages[0],merged.messages[0])
        assertSame(after,NativeIncrementalMerge.snapshot(old.copy(currentId="elsewhere"),after))
    }
}
