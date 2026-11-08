package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static fail-closed contract. Actual FCM delivery needs a real Android device. */
class OrbitPushRetryContractTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/de/kango99w/orbitadmin/$name"),
        File("app/src/main/java/de/kango99w/orbitadmin/$name")
    ).first(File::isFile).readText()

    @Test fun rotatedTokenSchedulesBoundedNetworkWorkWithoutPersistingRawToken() {
        val service = source("OrbitFcmService.kt")
        val worker = source("OrbitPushRetryWorker.kt")
        assertTrue(service.contains("OrbitPushRetry.enqueue(applicationContext)"))
        assertTrue(worker.contains("NetworkType.CONNECTED"))
        assertTrue(worker.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(worker.contains("Tasks.await(FirebaseMessaging.getInstance().token"))
        assertTrue(worker.contains("BackgroundPushConsent.mayDeliver"))
        assertTrue(worker.contains("client.isBound()"))
        assertTrue(worker.contains("client.registerPush(installation, token)"))
        assertFalse(worker.contains("CookieManager"))
        assertFalse(worker.contains("putString(\"token\""))
        assertFalse(worker.contains("WebView"))
        assertTrue(worker.contains("runAttemptCount < 3"))
    }

    @Test fun explicitOptOutCancelsDeferredRegistrationAndIsDurablyStored() {
        val main = source("MainActivity.kt")
        val method = main.substringAfter("private fun setPushOptIn(value: Boolean)")
            .substringBefore("private fun setPushRevokePending")
        assertTrue(method.contains("if (!value) OrbitPushRetry.cancel(applicationContext)"))
        assertTrue(method.contains("putBoolean(\"opt_in\",value).commit()"))
        assertTrue(method.contains("backgroundPushOptIn = value && stored"))
    }
}
