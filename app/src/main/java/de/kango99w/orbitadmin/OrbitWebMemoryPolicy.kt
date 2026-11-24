package de.kango99w.orbitadmin

/**
 * Native-first RAM policy with cache continuity.
 *
 * Normal tab switches, screen lock and UI_HIDDEN preserve the already loaded
 * first-party fallback pages. Only genuine memory pressure evicts WebViews.
 * Native encrypted mailbox metadata, account grants and login cookies are
 * stored elsewhere and must never be wiped by renderer eviction.
 */
object OrbitWebMemoryPolicy {
    private const val RUNNING_LOW = 10
    private const val UI_HIDDEN = 20
    private const val MODERATE = 60

    /**
     * A memory-pressure callback may evict off-screen fallback documents.
     * A locked app may release all optional HTML only under real pressure.
     * No WebView is evicted simply because the app became invisible.
     */
    fun releaseOnTrim(
        level: Int,
        unlocked: Boolean,
        pickerPending: Boolean,
        active: MailSection,
        retained: Set<MailSection>
    ): Set<MailSection> {
        if (pickerPending || level == UI_HIDDEN) return emptySet()
        if (!unlocked && level >= MODERATE) return retained
        if (level in RUNNING_LOW until UI_HIDDEN || level >= MODERATE) {
            val activeScope = BrowserSessionRouting.key(active)
            return retained.filterTo(linkedSetOf()) { it != activeScope }
        }
        return emptySet()
    }
}
