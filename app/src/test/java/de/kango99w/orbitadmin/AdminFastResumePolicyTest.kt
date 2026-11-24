package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdminFastResumePolicyTest {
    @Test fun recentServerVerifiedSessionCanReturnAfterBiometricWithinNinetySeconds() {
        val now = 6_000_000L
        assertTrue(AdminFastResumePolicy.allowed(now - 20_000L, now - 10_000L, now, false))
        assertTrue(AdminFastResumePolicy.allowed(now - 90_000L, now - 90_000L, now, false))
        assertFalse(AdminFastResumePolicy.allowed(now - 20_000L, now - 90_001L, now, false))
    }

    @Test fun oldVerificationDeviceLockAndProcessRestartCannotUseFastResume() {
        val now = 6_000_000L
        assertFalse(AdminFastResumePolicy.allowed(now - 300_001L, now - 5_000L, now, false))
        assertTrue(AdminFastResumePolicy.allowed(now - 300_000L, now - 5_000L, now, false))
        assertFalse(AdminFastResumePolicy.allowed(now - 2_000L, now - 1_000L, now, true))
        assertFalse(AdminFastResumePolicy.allowed(0L, now - 1_000L, now, false))
        assertFalse(AdminFastResumePolicy.allowed(now - 2_000L, 0L, now, false))
        assertFalse(AdminFastResumePolicy.allowed(now + 5_000L, now - 1_000L, now, false))
        assertFalse(AdminFastResumePolicy.allowed(now - 2_000L, now + 1_000L, now, false))
    }

    @Test fun fastResumeIsMemoryOnlyAndDoesNotSkipBackendAuthorization() {
        val base = sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        val biometric = base.substringAfter("override fun onAuthenticationSucceeded(")
            .substringBefore("override fun onAuthenticationError(")
        val onStop = base.substringAfter("override fun onStop()")
            .substringBefore("private fun showMailNotification()")
        val lock = base.substringAfter("private fun lock()")
            .substringBefore("@SuppressLint")
        val verify = base.substringAfter("private fun refreshNativeAdmin()")
            .substringBefore("private fun openNativeAdminMailbox(")
        assertTrue(biometric.contains("AdminFastResumePolicy.allowed("))
        assertTrue(biometric.contains("showProductChooser = !resumingPicker && !fastAdminReturn"))
        assertTrue(biometric.contains("if (fastAdminReturn)"))
        assertTrue(biometric.contains("refreshNativeAdmin()"))
        assertFalse(biometric.contains("putLong("))
        assertTrue(onStop.contains("workspace == MailWorkspace.ADMIN && adminAuthenticated"))
        assertTrue(onStop.contains("lock()"))
        assertTrue(onStop.indexOf("lock()") < onStop.indexOf("adminFastResumeBackgroundAtMs = now"))
        assertTrue(lock.contains("adminFastResumeBackgroundAtMs = 0L"))
        assertTrue(lock.contains("adminAuthenticated = false"))
        assertTrue(lock.contains("adminDetailCache.clear()"))
        assertTrue(verify.contains("nativeAdminClient.get(\"/api/admin/status\", epoch)"))
        assertTrue(verify.contains("adminAuthenticated=authorized"))
        assertTrue(verify.contains("adminLastServerVerifiedAtMs = if (authorized)"))
        assertTrue(verify.contains("if(privateRows?.status==401 || ordinaryRows?.status==401)"))
        assertFalse(base.contains("putLong(\"adminFastResume"))
        assertFalse(base.contains("putBoolean(\"adminAuthenticated"))
    }
}
