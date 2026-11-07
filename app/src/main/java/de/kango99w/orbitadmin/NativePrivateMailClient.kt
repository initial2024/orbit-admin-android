package de.kango99w.orbitadmin

import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Real private sender: distinct mail.kango99w.de session and Resend-backed
 * first-party Worker. Never proxy through the administrator inbox endpoints,
 * reuse account_mail_read, or embed Resend credentials in the APK.
 */
class NativePrivateMailClient {
    data class Reply(val status: Int, val value: JSONObject?, val error: String? = null)
    private val epochGate = AdminCookieCommitGate()
    private val origin = "https://mail.kango99w.de"

    fun epoch(): Long = epochGate.snapshot()
    fun invalidate() = epochGate.invalidate()

    companion object {
        val SENDERS = listOf("hello", "support", "contact", "ai", "dev", "study")
            .map { "$it@kango99w.de" }
        private val tokenRegex = Regex("^pmh1\\.[A-Za-z0-9_-]{32,120}$")
        private val emailRegex = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")

        fun extractHandoffToken(value: String?): String? {
            if (value == null) return null
            return try {
                val url = URI(value)
                if (url.scheme != "https" || url.host != "mail.kango99w.de" ||
                    (url.path != "/" && url.path != "") || url.port != -1 ||
                    url.rawQuery != null) null else {
                    val fragment = url.rawFragment
                    if (fragment == null || !fragment.startsWith("handoff=") ||
                        fragment.contains('&')) null
                    else URLDecoder.decode(fragment.removePrefix("handoff="),
                        StandardCharsets.UTF_8).takeIf { tokenRegex.matches(it) }
                }
            } catch (_: Exception) { null }
        }

        fun validEmailList(value: String, required: Boolean = false): Boolean {
            val parts = value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }
            return (parts.isNotEmpty() || !required) &&
                parts.size <= 20 && parts.all { it.length <= 254 && emailRegex.matches(it) }
        }

        fun filterSessionCookie(value: String?): String? =
            value.orEmpty().split(';').map { it.trim() }
                .firstOrNull { it.startsWith("mail_session=") &&
                    it.removePrefix("mail_session=").matches(Regex("^[A-Za-z0-9_.-]{12,512}$")) }
                ?.substringBefore(';')

        fun validSessionSetCookie(value: String): Boolean {
            val attrs = value.split(';').map { it.trim().lowercase() }
            return attrs.firstOrNull()?.startsWith("mail_session=") == true &&
                attrs.contains("secure") && attrs.contains("httponly") &&
                attrs.contains("samesite=lax") && attrs.contains("path=/") &&
                attrs.none { it.startsWith("domain=") } &&
                !value.contains('\n') && !value.contains('\r')
        }
    }

    fun hasLocalSession(): Boolean =
        filterSessionCookie(CookieManager.getInstance().getCookie(origin)) != null

    /** Drops ONLY the private sender cookie, not the administrator or ordinary sessions. */
    fun clearLocalSession() {
        epochGate.invalidate()
        CookieManager.getInstance().setCookie(origin,
            "mail_session=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0")
        CookieManager.getInstance().flush()
    }

    fun handoff(token: String, requestEpoch: Long): Reply {
        if (!tokenRegex.matches(token)) return Reply(400, null, "PRIVATE_HANDOFF_INVALID")
        return send("/handoff", JSONObject().put("token", token), requestEpoch, true)
    }

    fun sendMessage(body: JSONObject, requestEpoch: Long): Reply {
        if (body.optString("from") !in SENDERS)
            return Reply(400, null, "PRIVATE_SENDER_NOT_ALLOWED")
        if (!validEmailList(body.optString("to"), true) ||
            !validEmailList(body.optString("cc")) ||
            !validEmailList(body.optString("bcc")))
            return Reply(400, null, "PRIVATE_RECIPIENT_INVALID")
        if (body.optString("subject").isBlank() ||
            body.optString("subject").length > 200 ||
            body.optString("text").isBlank() ||
            body.optString("text").length > 20_000)
            return Reply(400, null, "PRIVATE_MESSAGE_INVALID")
        return send("/api/send", body, requestEpoch, false)
    }

    fun cancelScheduled(id: String, requestEpoch: Long): Reply {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(id))
            return Reply(400, null, "PRIVATE_SCHEDULE_ID_INVALID")
        return send("/api/scheduled/cancel", JSONObject().put("id", id), requestEpoch, false)
    }

    private fun send(path: String, body: JSONObject, requestEpoch: Long,
                     acceptHandoffCookie: Boolean): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Private mail network on UI thread" }
        if (!epochGate.isCurrent(requestEpoch))
            return Reply(499, null, "PRIVATE_REQUEST_CANCELLED")
        if (path !in setOf("/handoff", "/api/send", "/api/scheduled/cancel"))
            return Reply(400, null, "PRIVATE_ROUTE_DENIED")
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        // 5 MiB of real file bytes expand to ~7 MiB of Base64 JSON.
        // The Worker independently enforces the same 3-file/5-MiB policy.
        if (payload.size > 8_000_000)
            return Reply(413, null, "PRIVATE_PAYLOAD_TOO_LARGE")
        val cookie = if (acceptHandoffCookie) null else
            filterSessionCookie(CookieManager.getInstance().getCookie(origin))
        if (!acceptHandoffCookie && cookie == null)
            return Reply(401, null, "PRIVATE_LOGIN_REQUIRED")
        var connection: HttpsURLConnection? = null
        try {
            connection = URL(origin + path).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 9_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Origin", origin)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (acceptHandoffCookie && status == 200) {
                val headers = connection.headerFields
                epochGate.commitIfCurrent(requestEpoch) {
                    for ((name, values) in headers) {
                        if (!name.equals("set-cookie", ignoreCase = true)) continue
                        for (header in values.orEmpty()) {
                            if (validSessionSetCookie(header))
                                CookieManager.getInstance().setCookie(origin, header)
                        }
                    }
                    CookieManager.getInstance().flush()
                }
            }
            val input = (if (status in 200..299) connection.inputStream
                else connection.errorStream) ?: return Reply(status, null, "HTTP_$status")
            val output = ByteArrayOutputStream()
            input.use { stream ->
                val chunk = ByteArray(4096)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (output.size() + read > 65_536)
                        return Reply(503, null, "PRIVATE_RESPONSE_TOO_LARGE")
                    output.write(chunk, 0, read)
                }
            }
            val data = JSONObject(output.toString(StandardCharsets.UTF_8.name()))
            return Reply(status, data, data.optString("error").ifBlank { null })
        } catch (_: Exception) {
            return Reply(503, null, "PRIVATE_NETWORK_UNAVAILABLE")
        } finally {
            connection?.disconnect()
        }
    }
}
