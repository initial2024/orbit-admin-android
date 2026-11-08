package de.kango99w.orbitadmin

/**
 * Native-first RAM policy.
 *
 * A regular tab switch retains its already-loaded first-party document.
 * A locked Activity can discard its optional WebViews without clearing
 * CookieManager, WebStorage or the native account's Keystore grant.
 */
object OrbitWebMemoryPolicy {
    private const val RUNNING_LOW = 10
    private const val UI_HIDDEN = 20

    /** Never tear down a document while Android's file chooser owns its callback. */
    fun mayReleaseOnLock(pickerPending: Boolean, validPickerReturn: Boolean): Boolean =
        !pickerPending || !validPickerReturn

    /**
     * Android 14+ generally reports UI_HIDDEN, not the deprecated running
     * levels. On older devices a running-low signal may discard only dormant
     * fallback WebViews, not the active first-party page.
     */
    fun releaseOnTrim(
        level: Int,
        unlocked: Boolean,
        pickerPending: Boolean,
        active: MailSection,
        retained: Set<MailSection>
    ): Set<MailSection> {
        if (pickerPending) return emptySet()
        if (!unlocked && level >= UI_HIDDEN) return retained
        if (unlocked && level in RUNNING_LOW until UI_HIDDEN) {
            val activeScope = BrowserSessionRouting.key(active)
            return retained.filterTo(linkedSetOf()) { it != activeScope }
        }
        return emptySet()
    }
}
