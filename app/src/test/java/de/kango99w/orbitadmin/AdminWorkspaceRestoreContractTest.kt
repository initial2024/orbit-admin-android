package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level security regression boundary for admin session recovery. */
class AdminWorkspaceRestoreContractTest {
    private fun screen():String {
        val options=listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        return options.first{it.isFile}.readText()
    }

    @Test fun cachedPreferenceIsOnlyNavigationAndNotAuthentication() {
        val s=screen()
        val code=s.substringAfter("private fun restoreWorkspaceAfterUnlock()")
            .substringBefore("private fun resumeOrdinaryAfterUnlock()")
        assertTrue(code.contains("adminRestoreChecking=true"))
        assertTrue(code.contains("nativeAdminClient.get(\"/api/admin/status\",epoch)"))
        assertTrue(code.contains("response.status==200"))
        assertTrue(code.contains("optBoolean(\"authenticated\")==true"))
        assertTrue(code.contains("adminRestoreGeneration.get()!=generation"))
        assertTrue(code.contains("if(verified)"))
        assertTrue(code.contains("else resumeOrdinaryAfterUnlock()"))
        assertFalse(code.contains("CookieManager"))
        assertFalse(code.contains("nativeDeviceClient.login("))
        assertFalse(code.contains("adminAuthenticated=true"))
    }

    @Test fun leavingAdminClearsPrivilegedUiStateAndRequiresServerRevalidation() {
        val s=screen()
        val selection=s.substringAfter("private fun switchWorkspace(next: MailWorkspace)")
            .substringBefore("private fun selectAdminNavigation(")
        for(k in listOf("adminAuthenticated=false","adminLoadedAt.clear()",
            "adminMailboxes=emptyList()","adminRegisteredAccounts=emptyList()",
            "adminExternalEmails=emptyList()","adminInventoryDomains=emptyList()",
            "nativeAdminClient.invalidatePendingResponses()"))
            assertTrue("Missing on workspace exit: "+k,selection.contains(k))
        val refresh=s.substringAfter("private fun refreshNativeAdmin()")
            .substringBefore("private fun openNativeAdminMailbox(")
        assertTrue(refresh.contains("nativeAdminClient.get(\"/api/admin/status\", epoch)"))
        assertTrue(refresh.contains("authorized = status.status == 200"))
        assertTrue(refresh.contains("workspacePrefs.edit().putString(\"last_workspace\",\"admin\")"))
        assertFalse(selection.contains("adminAuthenticated=true"))
    }

    @Test fun logoutAndLockInvalidateRememberedPresentationWithoutSavingCredentials() {
        val s=screen()
        val logout=s.substringAfter("private fun logoutNativeAdmin()")
            .substringBefore("private fun refreshAdminGlyphs()")
        assertTrue(logout.contains("nativeAdminClient.post(\"/api/admin/logout\""))
        assertTrue(logout.contains("response.status == 200"))
        assertTrue(logout.contains("putString(\"last_workspace\",\"ordinary\")"))
        val lock=s.substringAfter("private fun lock()")
            .substringBefore("@SuppressLint")
        assertTrue(lock.contains("adminRestoreGeneration.incrementAndGet()"))
        assertTrue(lock.contains("adminLoadedAt.clear()"))
        assertTrue(lock.contains("adminAuthenticated = false"))
        assertFalse(s.contains("workspacePrefs.edit().putString(\"admin_password\""))
    }
}
