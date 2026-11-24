package de.kango99w.orbitadmin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, encrypted AndroidKeyStore cache tests: no real account data. */
@RunWith(AndroidJUnit4::class)
class NativeOfflineMailboxCacheInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val binding="11111111-1111-4111-8111-111111111111"
    private val other="22222222-2222-4222-8222-222222222222"
    private val time=1794000000000L

    private fun sample()=NativeInboxSnapshot(
        currentId="box_1",currentAddress="offline@example.test",locked=false,
        selectedDomain="",availableDomains=emptyList(),canCreate=false,
        nativeReadSupported=true,busy=false,statusText="network",
        mailboxes=listOf(NativeMailboxRow("box_1","offline@example.test",false)),
        messages=listOf(NativeMailRow("msg_1","sender@example.test","A saved subject","2026-11-08")))
    
    @Test fun encryptedMetadataSurvivesRecreateButNotBindingSwitchOrExpiry() {
        val cache=NativeOfflineMailboxCache(context)
        cache.clear()
        try {
            assertTrue(cache.store(binding,sample(),cache.epoch(),time))
            val persisted=context.getSharedPreferences("orbit_native_mailbox_cache_v1",0)
                .getString("snapshot","").orEmpty()
            assertFalse(persisted.contains("offline@example.test"))
            assertFalse(persisted.contains("A saved subject"))
            val copy=NativeOfflineMailboxCache(context).restore(binding,time+1000L)
            assertEquals("offline@example.test",copy?.currentAddress)
            assertEquals("A saved subject",copy?.messages?.firstOrNull()?.subject)
            assertFalse(copy?.nativeReadSupported ?: true)
            assertNull(cache.restore(other,time+1000L))
            assertNull(cache.restore(binding,time+24L*60L*60L*1000L+1L))
            assertNull(cache.restore(binding,time-1L))
        } finally { cache.clear() }
    }

    @Test fun cancelledAndClearedCacheRejectsLateWrites() {
        val cache=NativeOfflineMailboxCache(context)
        cache.clear()
        val oldEpoch=cache.epoch()
        cache.cancelPendingWrites()
        assertFalse(cache.store(binding,sample(),oldEpoch,time))
        assertTrue(cache.store(binding,sample(),cache.epoch(),time))
        cache.clear()
        assertNull(cache.restore(binding,time))
        assertFalse(cache.store(binding,sample(),oldEpoch,time))
    }

    @Test fun protectedMailboxNeverWritesToPersistentCache() {
        val cache=NativeOfflineMailboxCache(context)
        cache.clear()
        try {
            val protected=sample().copy(locked=true)
            assertFalse(cache.store(binding,protected,cache.epoch(),time))
            assertNull(cache.restore(binding,time))
        } finally { cache.clear() }
    }
}
