package de.kango99w.orbitadmin

import android.content.Context
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
 * only the administrator session and TOTP challenge cookies are presented to the /api/admin/ path, never the
 * ordinary kango_site_access cookie or ordinary native P-256 device grant.
 *
 * Cookies stay in the existing first-party Android WebView CookieManager so
 * an already-authenticated admin does not need to sign in repeatedly. This is
 * not a claim of independent WebView cookie-store/process isolation.
 */
class NativeAdminClient(context: Context) {
    private val cookieGate = AdminCookieCommitGate()
    private val nativeTrust = NativeAdminTrustedCookieStore(context.applicationContext)
    fun clearNativeTrustedDevice() = nativeTrust.clear()
    fun requestEpoch(): Long = cookieGate.snapshot()
    fun invalidatePendingResponses() = cookieGate.invalidate()
    data class Reply(val status: Int, val value: JSONObject?, val error: String? = null)
    companion object {
        private const val ORIGIN = "https://inbox.kango99w.de"
        // Trusted-device tokens are bound to User-Agent by the Worker.
        // Native HTTPS and the website WebView have DIFFERENT User-Agent values;
        // never submit or clear the website's trusted-device token here.
        private val ALLOWED_COOKIES = setOf(
            "kango_admin_session", "kango_admin_2fa_challenge"
        )
        private val UUID = "[A-Za-z0-9-]{8,64}"
        private val MAILBOX_MESSAGES = Regex(
            "^/api/admin/(?:pieterj-mail|ordinary-inbox)/mailboxes/$UUID/messages(?:/$UUID)?$"
        )
        private val PRIVATE_MESSAGE = Regex("^/api/admin/pieterj-mail/messages/$UUID$")
        private val EXTERNAL_EMAIL_DETAIL = Regex("^/api/admin/external-emails/$UUID$")
        private val PRIVATE_MAILBOX = Regex("^/api/admin/pieterj-mail/mailboxes/$UUID$")
        private val ORDINARY_RENEW = Regex("^/api/admin/ordinary-inbox/mailboxes/$UUID/renew$")
        private val REGISTERED_REVIVE = Regex("^/api/admin/account-registry/registrations/$UUID/revive$")
        private val ORDINARY_MAILBOX = Regex("^/api/admin/ordinary-inbox/mailboxes/$UUID$")
        private val TRUSTED_REVOKE = Regex("^/api/admin/trusted-devices/$UUID/revoke$")
        private val GET_PATHS = setOf(
            "/api/admin/status",
            "/api/admin/passkeys/capabilities",
            "/api/admin/pieterj-mail/mailboxes",
            "/api/admin/ordinary-inbox/mailboxes",
            "/api/admin/ordinary-inbox/domains",
            "/api/admin/ordinary-inbox/recorded-addresses",
            "/api/admin/pieterj-mail/status",
            "/api/admin/domain-control/inventory",
            "/api/admin/trusted-devices",
            "/api/admin/2fa/status",
            "/api/admin/registration-control",
            "/api/admin/external-emails",
            "/api/admin/lucky-glyphs/status"
        )
        private val POST_PATHS = setOf(
            "/api/admin/login", "/api/admin/login/totp", "/api/admin/logout",
            "/api/admin/passkeys/authentication/options",
            "/api/admin/passkeys/authentication/verify",
            "/api/admin/passkeys/registration/options",
            "/api/admin/passkeys/registration/verify",
            "/api/admin/private-mail-handoff",
            "/api/admin/registration-control",
            "/api/admin/lucky-glyphs/checkin", "/api/admin/lucky-glyphs/draw",
            "/api/admin/lucky-glyphs/equip", "/api/admin/lucky-glyphs/shop/buy",
            "/api/admin/pieterj-mail/mailboxes", "/api/admin/ordinary-inbox/mailboxes",
            "/api/admin/ordinary-inbox/activate-recorded"
        )

        fun allows(method: String, path: String): Boolean {
            // An exact, bounded, read-only registry query; never expose arbitrary
            // user-supplied URLs or open-ended list sizes to the admin cookie.
            if (method == "GET" && path in setOf(
                "/api/admin/account-registry/registrations?limit=100",
                "/api/admin/account-registry/registrations?limit=500"))
                return true
            if (path.any { it == '?' || it == '#' || it == '%' || it == '\\' ||
                    it == '\n' || it == '\r' }) return false
            return when (method) {
                "GET" -> path in GET_PATHS || MAILBOX_MESSAGES.matches(path) ||
                    PRIVATE_MESSAGE.matches(path) || EXTERNAL_EMAIL_DETAIL.matches(path)
                "POST" -> path in POST_PATHS || ORDINARY_RENEW.matches(path) ||
                    REGISTERED_REVIVE.matches(path) ||
                    TRUSTED_REVOKE.matches(path)
                "PATCH" -> PRIVATE_MAILBOX.matches(path)
                "DELETE" -> PRIVATE_MAILBOX.matches(path) || ORDINARY_MAILBOX.matches(path)
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

    fun get(path: String, epoch: Long): Reply = send("GET", path, null, epoch)
    fun post(path: String, body: JSONObject = JSONObject(), epoch: Long): Reply =
        send("POST", path, body, epoch)
    fun patch(path: String, body: JSONObject, epoch: Long): Reply =
        send("PATCH", path, body, epoch)
    fun delete(path: String, epoch: Long): Reply = send("DELETE", path, null, epoch)

    private fun send(method: String, path: String, body: JSONObject?, epoch: Long): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Admin HTTPS on main thread" }
        if (!cookieGate.isCurrent(epoch))
            return Reply(499, null, "ADMIN_NATIVE_REQUEST_CANCELLED")
        if (!allows(method, path)) return Reply(400, null, "ADMIN_NATIVE_ROUTE_DENIED")
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > 16_384)
            return Reply(400, null, "ADMIN_NATIVE_PAYLOAD_TOO_LARGE")
        var connection: HttpsURLConnection? = null
        try {
            val address = ORIGIN + path
            val sessionCookies = adminCookies(CookieManager.getInstance().getCookie(address))
            // Send native Keystore-encrypted trust only to the original password
            // login endpoint, never to WebView, ordinary accounts, or inbox reads.
            val nativeTrustCookie = if(method=="POST" && path=="/api/admin/login")
                nativeTrust.cookieForLogin() else null
            val cookies = listOfNotNull(sessionCookies.takeIf { it.isNotBlank() },
                nativeTrustCookie).joinToString("; ")
            connection = URL(address).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            // The Worker binds native trusted-device rotation to the exact UA.
            connection.setRequestProperty("User-Agent",
                NativeAdminTrustedCookieStore.NATIVE_USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-store")
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            if (method != "GET") {
                connection.setRequestProperty("Origin", ORIGIN)
                if (method == "POST" || method == "PATCH") {
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    val payload = bytes ?: "{}".toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                }
            }
            val code = connection.responseCode
            // Accept only the three administrator cookie names from the exact
            // pinned first-party HTTPS response; never copy arbitrary cookies.
            val headers = connection.headerFields
            cookieGate.commitIfCurrent(epoch) {
                var changed = false
                for ((name, values) in headers) {
                    if (!name.equals("Set-Cookie", ignoreCase = true)) continue
                    for (header in values.orEmpty()) {
                        if (mayPersistCookie(header)) {
                            CookieManager.getInstance().setCookie(address, header)
                            changed = true
                        } else if ((path=="/api/admin/login" ||
                                path=="/api/admin/login/totp") &&
                            header.substringBefore('=').trim() ==
                                NativeAdminTrustedCookieStore.COOKIE_NAME) {
                            // Trusted credentials stay encrypted and native-only.
                            nativeTrust.receiveSetCookie(header)
                        }
                    }
                }
                if (changed) CookieManager.getInstance().flush()
            }
            val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?: return Reply(code, null, "HTTP_$code")
            val output = ByteArrayOutputStream()
            stream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    val maxResponse = if(path=="/api/admin/account-registry/registrations?limit=500")
                        1_048_576 else 262_144
                    if (output.size() + n > maxResponse)
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
