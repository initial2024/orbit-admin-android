package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminPersistenceRecoveryTest {
    private val source=sequenceOf(
        File("src/main/java/de/kango99w/orbitadmin"),
        File("app/src/main/java/de/kango99w/orbitadmin")
    ).first(File::isDirectory)
    @Test fun expiredTemporaryStateCannotGrantPrivilegeAndServerStatusIsRequired() {
        val ui=File(source,"MainActivity.kt").readText()
        val client=File(source,"NativeAdminClient.kt").readText()
        assertTrue(ui.contains("adminAuthenticated=authorized"))
        assertTrue(ui.contains("status.value?.optBoolean(\"authenticated\") == true"))
        assertTrue(ui.contains("adminLoginVerificationPending=true"))
        assertTrue(ui.contains("adminMailboxListLoading"))
        assertTrue(client.contains("cookieGate.isCurrent(epoch)"))
        assertTrue(client.contains("pendingNativeSessionExpiresAt > System.currentTimeMillis()"))
        assertTrue(client.contains("fun invalidatePendingResponses()"))
        assertTrue(client.contains("pendingNativeSession=null"))
        assertFalse(ui.contains("adminAuthenticated=true"))
    }
    @Test fun passwordVaultIsOptInAndNoPasswordIsSavedInAppStorage() {
        val ui=File(source,"MainActivity.kt").readText()
        val client=File(source,"NativeAdminPasswordCredentials.kt").readText()
        assertTrue(ui.contains("adminRememberSystemPassword by mutableStateOf(false)"))
        assertTrue(ui.contains("saveVerifiedAdminPasswordToSystem()"))
        assertTrue(client.contains("CredentialManager.create(activity)"))
        assertTrue(client.contains("GetPasswordOption()"))
        assertTrue(client.contains("CreatePasswordRequest("))
        assertFalse(ui.contains("putString(\"admin_password\""))
    }
}
