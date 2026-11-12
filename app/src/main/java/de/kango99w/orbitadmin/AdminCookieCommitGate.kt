package de.kango99w.orbitadmin

/**
 * Prevents a response started before an app lock / data reset from
 * restoring administrator session cookies after that security boundary.
 */
internal class AdminCookieCommitGate {
    private val guard = Any()
    private var current = 0L

    fun snapshot(): Long = synchronized(guard) { current }

    fun invalidate() = synchronized(guard) {
        current += 1
    }

    fun isCurrent(epoch: Long): Boolean = synchronized(guard) { epoch == current }

    fun commitIfCurrent(epoch: Long, block: () -> Unit): Boolean =
        synchronized(guard) {
            if (epoch != current) false
            else {
                block()
                true
            }
        }
}
