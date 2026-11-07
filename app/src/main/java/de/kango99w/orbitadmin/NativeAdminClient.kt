package de.kango99w.orbitadmin

import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * First-party administrator HTTPS client for real Compose screens.
 *
 * Administrator and ordinary account authentication are DISTINCT realms:
 * only administrator HttpOnly cookies are presented to the /api/admin/ path, never the
 * ordinary kango_site_access cookie or ordinary native P-256 device grant.
 *
 * Cookies stay in the existing first-party Android WebView CookieManager so
 * an already-authenticated admin does not need to sign in repeatedly. This is
 * not a claim of independent WebView cookie-store/process isolation.
 */
class NativeAdminClient {
    data class Reply(val status: Int, val value: JSONObject?, val error: String? = null)
    companion object {
        private const val ORIGIN = "https://inbox.kango99w.de"
        private val ALLOWED_COOKIES = setOf(
            "kango_admin_session", "kango_admin_2fa_challenge", "kango_admin_trusted_device"
        )
        private val UUID = "[A-Za-z0-9-]{8,64}"
        private val MAILBOX_MESSAGES = Regex(
            "^/api/admin/(?:pieterj-mail|ordinary-inbox)/mailboxes/$UUID/messages(?:/$UUID)?$"
        )
        private val PRIVATE_MESSAGE = Regex("^/api/admin/pieterj-mail/messages/$UUID$")
        private val GET_PATHS = setOf(
            "/api/admin/status",
            "/api/admin/pieterj-mail/mailboxes",
            "/api/admin/ordinary-inbox/mailboxes",
            "/api/admin/ordinary-inbox/domains"
        )
        private val POST_PATHS = setOf(
            "/api/admin/login", "/api/admin/login/totp", "/api/admin/logout",
            "/api/admin/pieterj-mail/mailboxes", "/api/admin/ordinary-inbox/mailboxes"
        )

        fun allows(method: String, path: String): Boolean {
            if (path.any { it == '?' || it == '#' || it == '%' || it == '\\' ||
                    it == '\n' || it == '\r' }) return false
            return when (method) {
                "GET" -> path in GET_PATHS || MAILBOX_MESSAGES.matches(path) ||
                    PRIVATE_MESSAGE.matches(path)
                "POST" -> path in POST_PATHS
                else -> false
            }
        }

        /** Only exact administrator cookies may cross the WebView/HTTPS boundary. */
        fun adminCookies(raw: String?): String =
            raw.orEmpty().split(';').mapNotNull { item ->
                val cut = item.indexOf('=')
                if (cut <= 0) null else {
                    val key = item.substring(0, cut).trim()
                    val value = item.substring(cut + 1).trim()
                    if (key in ALLOWED_COOKIES &&
                        value.matches(Regex("^[A-Za-z0-9_%.-]{1,256}$")))
                        "$key=$value"
                    else null
                }
            }.distinctBy { it.substringBefore('=') }.joinToString("; ")

        fun mayPersistCookie(header: String): Boolean {
            val key = header.substringBefore('=').trim()
            val attrs = header.split(';').drop(1).map { it.trim().lowercase() }
            val path = if (key == "kango_admin_session") "path=/" else "path=/api/admin"
            return key in ALLOWED_COOKIES &&
                !header.contains('\n') && !header.contains('\r') &&
                attrs.contains("secure") && attrs.contains("httponly") &&
                attrs.contains("samesite=strict") && attrs.contains(path) &&
                attrs.none { it.startsWith("domain=") }
        }
    }

    fun get(path: String): Reply = send("GET", path, null)
    fun post(path: String, body: JSONObject = JSONObject()): Reply = send("POST", path, body)

    private fun send(method: String, path: String, body: JSONObject?): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Admin HTTPS on main thread" }
        if (!allows(method, path)) return Reply(400, null, "ADMIN_NATIVE_ROUTE_DENIED")
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > 16_384)
            return Reply(400, null, "ADMIN_NATIVE_PAYLOAD_TOO_LARGE")
        var connection: HttpsURLConnection? = null
        try {
            val address = ORIGIN + path
            val cookies = adminCookies(CookieManager.getInstance().getCookie(address))
            connection = URL(address).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            if (method == "POST") {
                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val payload = bytes ?: "{}".toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            // Accept only the three administrator cookie names from the exact
            // pinned first-party HTTPS response; never copy arbitrary cookies.
            var changed = false
            for ((name, values) in connection.headerFields) {
                if (!name.equals("Set-Cookie", ignoreCase = true)) continue
                for (header in values.orEmpty()) {
                    if (mayPersistCookie(header)) {
                        CookieManager.getInstance().setCookie(address, header)
                        changed = true
                    }
                }
            }
            if (changed) CookieManager.getInstance().flush()
            val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?: return Reply(code, null, "HTTP_$code")
            val output = ByteArrayOutputStream()
            stream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (output.size() + n > 262_144)
                        return Reply(503, null, "ADMIN_NATIVE_RESPONSE_TOO_LARGE")
                    output.write(chunk, 0, n)
                }
            }
            val json = JSONObject(output.toString(Charsets.UTF_8.name()))
            return Reply(code, json, json.optString("error").ifBlank { null })
        } catch (_: Exception) {
            return Reply(503, null, "ADMIN_NATIVE_NETWORK_UNAVAILABLE")
        } finally {
            connection?.disconnect()
        }
    }
}
