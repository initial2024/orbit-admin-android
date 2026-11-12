package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminLoginTransitionTest {
    private val source=sequenceOf(
        File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
    ).first(File::isFile).readText()
    @Test fun validStatusUnlocksTheDashboardBeforeSlowMailboxCalls() {
        val refresh=source.substringAfter("private fun refreshNativeAdmin()")
            .substringBefore("private fun probeNativeAdminPasskeys()")
        val verified=refresh.indexOf("adminAuthenticated=authorized")
        val mailbox=refresh.indexOf("val privateRows=receive(privateFuture)")
        assertTrue("Do not block login on mailbox queries",verified>=0 && mailbox>verified)
        assertTrue(refresh.contains("if(!authorized)return@execute"))
        assertTrue(refresh.contains("adminMailboxListLoading=false"))
        assertTrue(refresh.contains("adminMailboxes = mailboxes"))
        assertFalse(refresh.substring(0,mailbox).contains("adminMailboxes = mailboxes"))
    }
    @Test fun codeStillRequiresServerStatusAndShowsRetryOnFailure() {
        val refresh=source.substringAfter("private fun refreshNativeAdmin()")
            .substringBefore("private fun probeNativeAdminPasskeys()")
        assertTrue(refresh.contains("status.value?.optBoolean(\"authenticated\") == true"))
        assertTrue(refresh.contains("adminSessionUnknown=status.status!=200"))
        assertTrue(source.contains("使用账号密码重新登录"))
    }
}
