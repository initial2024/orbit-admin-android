package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeAdminResumeSyncTest {
    private val text=sequenceOf(File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        .first(File::isFile).readText()
    @Test fun adminResumesWithBoundedMessageRefresh() {
        assertTrue(text.contains("workspace==MailWorkspace.ADMIN && adminAuthenticated && !adminBusy"))
        assertTrue(text.contains("now-adminLastMessageSyncMs>30_000L"))
        assertTrue(text.contains("openNativeAdminMailbox(selected)"))
        assertTrue(text.contains("now-adminLastMailboxSyncMs>30_000L"))
    }
    @Test fun refreshSameMailboxRetainsExistingRowsUntilValidReplacement() {
        assertTrue(text.contains("val sameMailbox=adminSelectedMailbox?.id==mailbox.id"))
        assertTrue(text.contains("if(!sameMailbox) {"))
        assertTrue(text.contains("adminLastMessageSyncMs=SystemClock.elapsedRealtime()"))
        assertTrue(text.contains("正在重新同步"))
    }
}
