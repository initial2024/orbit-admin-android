package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-contract tests for progressive inbox first paint and auth boundaries. */
class NativeProgressiveInboxContractTest {
    private val source = sequenceOf(
        File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        .first(File::isFile).readText()

    @Test fun serverVerifiedMailboxRowsPaintBeforeMessagesRequest() {
        val sync = source.substringAfter("private fun refreshNativeApi()")
            .substringBefore("private fun readMessageViaNativeApi(")
        val verified = sync.indexOf("mailboxes.status != 200")
        val early = sync.indexOf("val earlySnapshot = NativeInboxSnapshot(")
        val ui = sync.indexOf("nativeInbox = earlySnapshot.copy(")
        val messages = sync.indexOf("val messageResult = if")
        assertTrue(verified >= 0 && verified < early)
        assertTrue(early < ui && ui < messages)
        assertTrue(sync.contains("nativeRequestGeneration.get() != generation"))
        assertTrue(sync.contains("nativeDeviceClient.currentId() != deviceId"))
    }

    @Test fun earlyPaintCannotExposeUnverifiedContentsOrAnotherMailbox() {
        val phase = source.substringAfter("val earlySnapshot = NativeInboxSnapshot(")
            .substringBefore("val messageResult = if")
        assertTrue(phase.contains("nativeReadSupported = false"))
        assertTrue(phase.contains("messages = emptyList()"))
        assertTrue(phase.contains("previous?.currentId == selected?.id"))
        assertTrue(phase.contains("!locked"))
        assertTrue(phase.contains("nativeApiActive = false"))
        assertFalse(phase.contains("nativeOfflineCache.store("))
        assertFalse(phase.contains("readMessageViaNativeApi("))
    }

    @Test fun successfulFullSyncStillControlsAuthorizationAndPersistentCache() {
        val sync = source.substringAfter("val messageResult = if")
            .substringBefore("private fun readMessageViaNativeApi(")
        assertTrue(sync.contains("val messageListVerified=messageResult?.status==200"))
        assertTrue(sync.contains("messageResult?.status == 200"))
        assertTrue(sync.contains("nativeOfflineCache.store(deviceId,snapshot,cacheEpoch)"))
        assertTrue(sync.contains("if (!unlocked || nativeDeviceClient.currentId() != deviceId)"))
    }

    @Test fun refreshButtonsShowProgressInsteadOfRepeatedRequests() {
        assertTrue(source.contains("Text(if (nativeApiLoading) \"同步中…\" else \"刷新\")"))
        assertTrue(source.contains("Text(if (nativeApiLoading) \"正在同步…\" else \"检查新邮件\")"))
        assertTrue(source.contains("TextButton(enabled = !nativeApiLoading"))
        assertTrue(source.contains("OutlinedButton(enabled = !nativeApiLoading"))
    }
}
