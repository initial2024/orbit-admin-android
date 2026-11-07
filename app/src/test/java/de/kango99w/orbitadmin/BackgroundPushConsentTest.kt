package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundPushConsentTest {
    @Test fun pendingRevokeAlwaysBlocksLocalDataPushAndReEnrollment() {
        assertTrue(BackgroundPushConsent.mayDeliver(true,false))
        assertFalse(BackgroundPushConsent.mayDeliver(false,false))
        assertFalse(BackgroundPushConsent.mayDeliver(true,true))
        assertFalse(BackgroundPushConsent.mayDeliver(false,true))
        assertFalse(BackgroundPushConsent.mayEnable(true))
        assertTrue(BackgroundPushConsent.mayEnable(false))
    }

    @Test fun uiRevocationIsFailClosedAndServerAcknowledgmentIsRequired() {
        val source = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        val revoke = source.substringAfter("private fun revokeBackgroundPush() {")
            .substringBefore("private fun retryServerPushRevocation()")
        assertTrue(revoke.contains("setPushOptIn(false)"))
        assertTrue(revoke.contains("setPushRevokePending(true)"))
        assertTrue(revoke.indexOf("setPushOptIn(false)") <
            revoke.indexOf("retryServerPushRevocation()"))
        val ack = source.substringAfter("\"revoked\" -> {")
            .substringBefore("\"disabled\" ->")
        assertTrue(ack.contains("if (backgroundPushRevokePending)"))
        assertTrue(ack.contains("setPushRevokePending(false)"))
        val service = listOf(
            File("src/main/java/de/kango99w/orbitadmin/OrbitFcmService.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/OrbitFcmService.kt")
        ).first(File::isFile).readText()
        assertTrue(service.contains("BackgroundPushConsent.mayDeliver"))
        assertTrue(service.contains("getBoolean(\"revoke_pending\",false)"))
    }
}
