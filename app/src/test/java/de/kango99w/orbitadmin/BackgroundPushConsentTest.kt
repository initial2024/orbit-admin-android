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
    @Test fun localDeviceClearAndSignedOutServiceMustNeverDisplayFCMMail() {
        val sources = listOf(
            File("src/main/java/de/kango99w/orbitadmin"),
            File("app/src/main/java/de/kango99w/orbitadmin")
        ).first { it.isDirectory }
        val client = File(sources, "NativeDeviceClient.kt").readText()
        val svc = File(sources, "OrbitFcmService.kt").readText()
        val ui = File(sources, "MainActivity.kt").readText()
        val clear = client.substringAfter("fun clearLocalBinding() {")
            .substringBefore("/** Execute off the Android main thread")
        assertTrue(clear.contains("pushPrefs.edit().putBoolean(\"opt_in\", false).commit()"))
        assertTrue(clear.indexOf("putBoolean(\"opt_in\", false)") < clear.indexOf("deleteEntry(ALIAS)"))
        assertTrue(svc.contains("NativeDeviceClient(applicationContext).isBound()"))
        assertTrue(svc.contains("if (!bound) return"))
        assertTrue(svc.contains("message.data != mapOf(\"kind\" to \"new_mail\")"))
        assertTrue(ui.contains("backgroundPushStatus = \"本机通知已关闭"))
    }

}
