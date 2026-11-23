package de.kango99w.orbitadmin

/**
 * A strictly process-memory-only UI recovery hint. This is NOT an administrator
 * credential, session token, or substitute for the backend's per-request checks.
 *
 * A short background visit may skip the blocking administrator status screen
 * after fresh device biometric verification. The app still starts a normal
 * asynchronous server validation; revoked/expired sessions must be rejected.
 */
object AdminFastResumePolicy {
    const val MAX_AWAY_MS = 90_000L
    const val MAX_VERIFIED_AGE_MS = 5L * 60L * 1000L

    fun allowed(
        verifiedAtMs: Long,
        wentBackgroundAtMs: Long,
        nowMs: Long,
        deviceLocked: Boolean
    ): Boolean {
        if (deviceLocked || verifiedAtMs <= 0L || wentBackgroundAtMs <= 0L ||
            nowMs < verifiedAtMs || nowMs < wentBackgroundAtMs) return false
        val away = nowMs - wentBackgroundAtMs
        val verifiedAge = nowMs - verifiedAtMs
        return away <= MAX_AWAY_MS && verifiedAge <= MAX_VERIFIED_AGE_MS
    }
}
