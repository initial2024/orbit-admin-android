package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerReturnPolicyTest {
    @Test fun aQuickUninterruptedPickerReturnKeepsLocalUnlock() {
        assertTrue(PickerReturnPolicy.canResumeWithoutUnlock(1000L, 2000L, false))
        assertTrue(PickerReturnPolicy.canResumeWithoutUnlock(1000L, 1000L + PickerReturnPolicy.GRACE_MS, false))
    }
    @Test fun lockingTheDeviceOrExpiredPickerMustRelock() {
        assertFalse(PickerReturnPolicy.canResumeWithoutUnlock(1000L, 2000L, true))
        assertFalse(PickerReturnPolicy.canResumeWithoutUnlock(1000L, 1001L + PickerReturnPolicy.GRACE_MS, false))
        assertFalse(PickerReturnPolicy.canResumeWithoutUnlock(0L, 1000L, false))
        assertFalse(PickerReturnPolicy.canResumeWithoutUnlock(5000L, 4999L, false))
    }
}
