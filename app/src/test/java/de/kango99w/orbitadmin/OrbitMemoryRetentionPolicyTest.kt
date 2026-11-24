package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OrbitMemoryRetentionPolicyTest {
    private val both = setOf(MailSection.INBOX, MailSection.TRANSFER)

    @Test fun normalLockAndUiHiddenKeepAlreadyLoadedWebDocuments() {
        assertTrue(OrbitWebMemoryPolicy.releaseOnTrim(20,false,false,MailSection.INBOX,both).isEmpty())
        assertTrue(OrbitWebMemoryPolicy.releaseOnTrim(40,false,false,MailSection.INBOX,both).isEmpty())
        assertTrue(OrbitWebMemoryPolicy.releaseOnTrim(5,true,false,MailSection.INBOX,both).isEmpty())
        val source = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        val lock = source.substringAfter("private fun lock() {")
            .substringBefore("@SuppressLint(\"SetJavaScriptEnabled\")")
        assertFalse(lock.contains("disposeRetainedBrowsers("))
        assertTrue(source.contains("if(nativeApiActive && nativeInbox != null"))
        assertTrue(source.contains("age in 0..45_000L"))
    }

    @Test fun realMemoryPressureDropsDormantOnlyAndSafeguardsPicker() {
        assertEquals(setOf(MailSection.TRANSFER),
            OrbitWebMemoryPolicy.releaseOnTrim(10,true,false,MailSection.MAILBOXES,both))
        assertEquals(setOf(MailSection.TRANSFER),
            OrbitWebMemoryPolicy.releaseOnTrim(60,true,false,MailSection.INBOX,both))
        assertEquals(both,OrbitWebMemoryPolicy.releaseOnTrim(60,false,false,MailSection.INBOX,both))
        assertEquals(emptySet<MailSection>(),
            OrbitWebMemoryPolicy.releaseOnTrim(80,false,true,MailSection.INBOX,both))
    }

    @Test fun optionalPushSDKDoesNotStartForUnsubscribedOrRevokingAccounts() {
        assertFalse(OrbitPushConfig.shouldInitialize(false,false))
        assertFalse(OrbitPushConfig.shouldInitialize(true,true))
        assertFalse(OrbitPushConfig.shouldInitialize(false,true))
        assertEquals(OrbitPushConfig.configured,OrbitPushConfig.shouldInitialize(true,false))
    }

    @Test fun durableNativeOfflineMetadataRemainsEncryptedAndWithoutMessageBody() {
        val source = listOf(
            File("src/main/java/de/kango99w/orbitadmin/NativeOfflineMailboxCache.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/NativeOfflineMailboxCache.kt")
        ).first(File::isFile).readText()
        assertTrue(source.contains("AES/GCM/NoPadding"))
        assertTrue(source.contains("MAX_CACHE_BYTES=32_768"))
        assertTrue(source.contains("MAX_OFFLINE_AGE_MS=24L*60L*60L*1000L"))
        assertTrue(source.contains("data.messages.take(30)"))
        assertFalse(source.contains("obj.put(\"html_body\""))
    }
}
