package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdminCookieCommitGateTest {
    @Test fun lockedOrClearedStateCannotAcceptAnOldLoginCookie() {
        val gate = AdminCookieCommitGate()
        val loginEpoch = gate.snapshot()
        var persisted = 0
        assertTrue(gate.commitIfCurrent(loginEpoch) { persisted++ })
        gate.invalidate()
        assertFalse(gate.commitIfCurrent(loginEpoch) { persisted++ })
        assertEquals(1, persisted)
        val fresh = gate.snapshot()
        assertTrue(gate.commitIfCurrent(fresh) { persisted++ })
        assertEquals(2, persisted)
    }

    @Test fun cookieUpdateAndLockInvalidationCannotInterleave() {
        val gate = AdminCookieCommitGate()
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val epoch = gate.snapshot()
            val cookieWriter = worker.submit<Boolean> {
                gate.commitIfCurrent(epoch) {
                    started.countDown()
                    if (!release.await(3, TimeUnit.SECONDS))
                        throw AssertionError("Timed out waiting for lock gate")
                }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            // This releases the held cookie write before invalidation can finish.
            release.countDown()
            assertTrue(cookieWriter.get(3, TimeUnit.SECONDS))
            gate.invalidate()
            assertFalse(gate.commitIfCurrent(epoch) { throw AssertionError("Stale Set-Cookie accepted") })
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
