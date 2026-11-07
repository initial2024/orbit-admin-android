package de.kango99w.orbitadmin

import org.json.JSONObject
import android.text.Html

data class AdminNativeMailbox(
    val id: String,
    val address: String,
    val privateMailbox: Boolean,
    val unread: Int = 0,
    val messageCount: Int = 0,
    val status: String = "active"
)

data class AdminNativeMessage(
    val id: String,
    val sender: String,
    val subject: String,
    val time: Long,
    val read: Boolean
)

data class AdminNativeDetail(
    val id: String,
    val sender: String,
    val recipient: String,
    val subject: String,
    val text: String,
    val htmlText: String,
    val possibleCode: String,
    val time: Long
)

/** Decode only bounded, server-authorized DTOs, never render HTML as WebView. */
object AdminNativeModels {
    private val safeId = Regex("^[A-Za-z0-9-]{8,64}$")

    // Html.fromHtml with no ImageGetter only extracts inert text; it does not
    // load remote images, execute JavaScript, or mount a WebView.
    private fun htmlAsPlainText(value: String): String =
        if (value.isBlank()) "" else try {
            Html.fromHtml(value.take(24_000), Html.FROM_HTML_MODE_COMPACT)
                .toString().replace('\uFFFC', ' ').trim().take(16_000)
        } catch (_: Exception) { "" }
    fun validId(id: String): Boolean = safeId.matches(id)

    fun mailboxes(body: JSONObject?, privateMailbox: Boolean): List<AdminNativeMailbox> {
        val rows = body?.optJSONArray("mailboxes") ?: return emptyList()
        return (0 until minOf(rows.length(), 100)).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val address = o.optString("address")
            if (!validId(id) || address.isBlank() || address.length > 254)
                return@mapNotNull null
            AdminNativeMailbox(
                id, address, privateMailbox,
                o.optInt("unread_count").coerceIn(0, 100_000),
                o.optInt("message_count").coerceIn(0, 100_000),
                o.optString("status").take(24)
            )
        }
    }

    fun messages(body: JSONObject?): List<AdminNativeMessage> {
        val rows = body?.optJSONArray("messages") ?: return emptyList()
        return (0 until minOf(rows.length(), 100)).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!validId(id)) return@mapNotNull null
            AdminNativeMessage(id, o.optString("sender").take(180),
                o.optString("subject").take(300),
                o.optLong("received_at"), o.optBoolean("is_read"))
        }
    }

    fun detail(body: JSONObject?): AdminNativeDetail? {
        val o = body?.optJSONObject("message") ?: return null
        val id = o.optString("id")
        if (!validId(id)) return null
        val plain = o.optString("text_body").take(16_000)
        val htmlText = o.optString("html_text_body").take(16_000).ifBlank {
            if (plain.isBlank()) htmlAsPlainText(o.optString("html_body")) else ""
        }
        return AdminNativeDetail(
            id, o.optString("sender").take(180),
            o.optString("recipient").take(254),
            o.optString("subject").take(300),
            plain, htmlText,
            o.optString("possible_code").take(32),
            o.optLong("received_at")
        )
    }
}
