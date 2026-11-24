package de.kango99w.orbitadmin

import org.json.JSONObject
import java.net.URI

data class NativeMailboxRow(val id: String, val address: String, val isProtected: Boolean)
data class NativeMailRow(val id: String, val sender: String, val subject: String, val receivedAt: String)
data class NativeInboxSnapshot(
    val currentId: String,
    val currentAddress: String,
    val locked: Boolean,
    val selectedDomain: String,
    val availableDomains: List<String>,
    val canCreate: Boolean,
    val nativeReadSupported: Boolean,
    val busy: Boolean,
    val statusText: String,
    val mailboxes: List<NativeMailboxRow>,
    val messages: List<NativeMailRow>
)

sealed interface NativeInboxEvent {
    data object Clear : NativeInboxEvent
    data class Snapshot(val data: NativeInboxSnapshot) : NativeInboxEvent
}

/** A display-only, memory-only website-to-Android view model. No server credentials. */
object NativeInboxPolicy {
    private val safeId = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val currentKeys = setOf("id", "address", "locked")
    private val mailboxKeys = setOf("id", "address", "protected")
    private val mailKeys = setOf("id", "sender", "subject", "receivedAt")
    private val snapshotKeys = setOf(
        "v", "kind", "scope", "current", "mailboxes", "messages",
        "selectedDomain", "availableDomains", "canCreate", "busy", "statusText", "nativeReadSupported"
    )
    private val domainPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,86}[a-z0-9])?$")

    fun isSafeId(id: String): Boolean = safeId.matches(id)

    fun acceptsSource(scheme: String?, host: String?, port: Int, isMainFrame: Boolean, url: String?): Boolean {
        if (!isMainFrame || !scheme.equals("https", true) ||
            !host.equals("inbox.kango99w.de", true) || (port != -1 && port != 443)
        ) return false
        if (url == null || !AdminUrlPolicy.isTrustedMainFrame(url)) return false
        return try {
            val parsed = URI(url)
            parsed.path == "/" && parsed.rawQuery.orEmpty().split("&").contains("orbit_app=android")
        } catch (_: Exception) {
            false
        }
    }

    private fun exactFields(obj: JSONObject, expected: Set<String>): Boolean =
        obj.keys().asSequence().toSet() == expected

    private fun safeText(value: String, max: Int): String =
        value.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .take(max)

    fun parse(raw: String?): NativeInboxEvent? {
        if (raw.isNullOrBlank() || raw.length > 20_000) return null
        return try {
            val obj = JSONObject(raw)
            if (obj.optInt("v") != 1) return null
            if (obj.optString("kind") == "clear") {
                return if (exactFields(obj, setOf("v", "kind"))) NativeInboxEvent.Clear else null
            }
            if (obj.optString("kind") != "mail_snapshot" || obj.optString("scope") != "public" ||
                !(exactFields(obj, snapshotKeys) ||
                  exactFields(obj, snapshotKeys - "nativeReadSupported"))
            ) return null
            if (obj.has("nativeReadSupported") && obj.opt("nativeReadSupported") !is Boolean) return null
            val active = obj.optJSONObject("current") ?: return null
            if (!exactFields(active, currentKeys)) return null
            val activeId = active.optString("id")
            if (activeId.isNotEmpty() && !isSafeId(activeId)) return null
            val availableDomainJson = obj.optJSONArray("availableDomains") ?: return null
            if (availableDomainJson.length() > 24) return null
            val availableDomains = (0 until availableDomainJson.length()).map { i ->
                val domain = availableDomainJson.optString(i)
                if (!domainPattern.matches(domain)) return null
                domain
            }
            if (availableDomains.distinct().size != availableDomains.size) return null
            val selectedDomain = obj.optString("selectedDomain")
            if (selectedDomain.isNotEmpty() && selectedDomain !in availableDomains) return null
            if (obj.opt("canCreate") !is Boolean || obj.opt("busy") !is Boolean) return null
            if (obj.optString("statusText").length > 160) return null
            val mailboxJson = obj.optJSONArray("mailboxes") ?: return null
            val messagesJson = obj.optJSONArray("messages") ?: return null
            if (mailboxJson.length() > 25 || messagesJson.length() > 30) return null
            val mailboxes = (0 until mailboxJson.length()).map { i ->
                val item = mailboxJson.optJSONObject(i) ?: return null
                if (!exactFields(item, mailboxKeys) || !isSafeId(item.optString("id"))) return null
                NativeMailboxRow(
                    item.getString("id"),
                    safeText(item.optString("address"), 254),
                    item.optBoolean("protected")
                )
            }
            val messages = (0 until messagesJson.length()).map { i ->
                val item = messagesJson.optJSONObject(i) ?: return null
                if (!exactFields(item, mailKeys) || !isSafeId(item.optString("id"))) return null
                NativeMailRow(
                    item.getString("id"),
                    safeText(item.optString("sender"), 160),
                    safeText(item.optString("subject"), 160),
                    safeText(item.optString("receivedAt"), 48)
                )
            }
            NativeInboxEvent.Snapshot(
                NativeInboxSnapshot(
                    activeId,
                    safeText(active.optString("address"), 254),
                    active.optBoolean("locked"),
                    selectedDomain,
                    availableDomains,
                    obj.optBoolean("canCreate"),
                    obj.optBoolean("nativeReadSupported", false),
                    obj.optBoolean("busy"),
                    safeText(obj.optString("statusText"),160),
                    mailboxes,
                    messages
                )
            )
        } catch (_: Exception) {
            null
        }
    }
}
