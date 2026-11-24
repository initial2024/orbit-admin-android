package de.kango99w.orbitadmin

/** Share unchanged immutable rows across refreshes. The incoming server
 * snapshot decides membership and order; removed rows are never retained.
 */
object NativeIncrementalMerge {
    fun mailboxes(before: List<NativeMailboxRow>, after: List<NativeMailboxRow>):List<NativeMailboxRow> {
        val prior=before.associateBy { it.id }
        return after.map { new -> prior[new.id]?.takeIf{it==new} ?: new }
    }
    fun messages(before: List<NativeMailRow>, after: List<NativeMailRow>):List<NativeMailRow> {
        val prior=before.associateBy { it.id }
        return after.map { new -> prior[new.id]?.takeIf{it==new} ?: new }
    }
    fun snapshot(before: NativeInboxSnapshot?, after: NativeInboxSnapshot):NativeInboxSnapshot {
        if(before==null || before.currentId!=after.currentId)return after
        val next=after.copy(
            mailboxes=mailboxes(before.mailboxes,after.mailboxes),
            messages=messages(before.messages,after.messages))
        return if(before==next)before else next
    }
}
