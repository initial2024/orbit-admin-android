package de.kango99w.orbitadmin

import org.json.JSONObject

data class NativeMailDetail(
    val mailboxId: String,
    val id: String,
    val sender: String,
    val recipient: String,
    val subject: String,
    val receivedAt: String,
    val textBody: String,
    // HTML-only signed API response is already sanitized. Display as inert native text.
    val htmlBody: String = ""
)

sealed interface NativeMailDetailEvent {
    data object Clear : NativeMailDetailEvent
    data class Detail(val data: NativeMailDetail) : NativeMailDetailEvent
}

/** Memory-only, plain-text reader; does not persist HTML, OTPs or auth data. */
object NativeMailDetailPolicy {
    private val keys = setOf("v","kind","scope","mailboxId","id","sender",
        "recipient","subject","receivedAt","textBody")
    private fun hasExactKeys(obj: JSONObject, keys: Set<String>): Boolean =
        obj.keys().asSequence().toSet() == keys

    private fun text(value: String, max: Int, multiline: Boolean = false): String =
        value.filter { ch ->
            (!ch.isISOControl() || (multiline && (ch == '\n' || ch == '\t'))) &&
                Character.getType(ch) != Character.FORMAT.toInt()
        }.take(max)

    fun parse(input: String?): NativeMailDetailEvent? {
        if (input.isNullOrBlank() || input.length > 11000) return null
        return try {
            val obj=JSONObject(input)
            if (obj.optInt("v") != 1) return null
            if (obj.optString("kind") == "clear") {
                return if (hasExactKeys(obj,setOf("v","kind")))
                    NativeMailDetailEvent.Clear else null
            }
            if (obj.optString("kind") != "mail_detail" ||
                obj.optString("scope") != "public" ||
                !hasExactKeys(obj,keys)) return null
            val id=obj.optString("id")
            val mailbox=obj.optString("mailboxId")
            if (!NativeInboxPolicy.isSafeId(id) || !NativeInboxPolicy.isSafeId(mailbox)) return null
            if (obj.optString("sender").length > 180 ||
                obj.optString("recipient").length > 254 ||
                obj.optString("subject").length > 240 ||
                obj.optString("receivedAt").length > 48 ||
                obj.optString("textBody").length > 8000) return null
            NativeMailDetailEvent.Detail(NativeMailDetail(
                mailbox,id,
                text(obj.optString("sender"),180),
                text(obj.optString("recipient"),254),
                text(obj.optString("subject"),240),
                text(obj.optString("receivedAt"),48),
                text(obj.optString("textBody"),8000,true)
            ))
        } catch (_: Exception) {
            null
        }
    }
}
