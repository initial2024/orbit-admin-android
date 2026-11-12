package de.kango99w.orbitadmin

/**
 * Foreground notification only after a real first-party mailbox refresh sees
 * a new message ID in the SAME unlocked mailbox. Never announce the initial
 * snapshot, a mailbox switch, an already-seen item, or a failed API read.
 */
object NewMailArrivalPolicy {
    fun hasArrival(previous: NativeInboxSnapshot?, current: NativeInboxSnapshot): Boolean {
        if (previous == null || previous.locked || current.locked ||
            current.currentId.isBlank() || previous.currentId != current.currentId ||
            previous.currentAddress != current.currentAddress) return false
        if (current.messages.isEmpty()) return false
        val oldIds=previous.messages.map { it.id }.toHashSet()
        return current.messages.any { it.id.isNotBlank() && it.id !in oldIds }
    }

    fun hasAdminArrival(previousId: String?, currentId: String,
                        oldIds: List<String>, freshIds: List<String>): Boolean {
        if (previousId == null || previousId != currentId ||
            oldIds.isEmpty() || freshIds.isEmpty()) return false
        val before=oldIds.toHashSet()
        return freshIds.any { it.isNotBlank() && it !in before }
    }
}
