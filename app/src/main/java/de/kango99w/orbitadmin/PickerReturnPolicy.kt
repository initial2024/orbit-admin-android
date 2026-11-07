package de.kango99w.orbitadmin

/** A system-document picker is a bounded continuation, never a session re-login. */
object PickerReturnPolicy {
    const val GRACE_MS = 3 * 60 * 1000L
    fun canResumeWithoutUnlock(launchedAtMs: Long, returnedAtMs: Long, deviceLocked: Boolean): Boolean =
        !deviceLocked && launchedAtMs > 0 && returnedAtMs >= launchedAtMs &&
            returnedAtMs - launchedAtMs <= GRACE_MS
}
