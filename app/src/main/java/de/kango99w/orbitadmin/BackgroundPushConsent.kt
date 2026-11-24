package de.kango99w.orbitadmin

/**
 * Pure, testable consent gates. A revoke request must disable local data-message
 * delivery immediately and block re-enrollment until a trusted server acknowledgement.
 *
 * Firebase notification payloads can be displayed by Android without entering
 * OrbitFcmService, so remote token revocation remains mandatory.
 */
object BackgroundPushConsent {
    fun mayDeliver(optedIn: Boolean, revokePending: Boolean): Boolean =
        optedIn && !revokePending

    fun mayEnable(revokePending: Boolean): Boolean = !revokePending
}
