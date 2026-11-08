package de.kango99w.orbitadmin

import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Ordinary-account *site-session* read-only center.
 * A native account_mail_read device grant is intentionally NOT upgraded into
 * website membership/billing/closure authority. Never use administrator cookies.
 */
class NativeAccountOverviewClient {
    data class Reply(val status: Int, val value: JSONObject?, val error: String? = null)
    private val gate = AdminCookieCommitGate()
    fun epoch(): Long = gate.snapshot()
    fun invalidate() = gate.invalidate()
    companion object {
        private const val ORIGIN = "https://inbox.kango99w.de"
        private val ROUTES = setOf("/api/account/status", "/api/upgrade/status",
            "/api/account/closure/options")
        fun allowed(method: String, path: String): Boolean =
            method == "GET" && path in ROUTES
    }
    fun read(path: String, epoch: Long): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Overview HTTP on UI thread" }
        if (!allowed("GET", path)) return Reply(400, null, "ACCOUNT_ROUTE_DENIED")
        if (!gate.isCurrent(epoch)) return Reply(499, null, "ACCOUNT_READ_CANCELLED")
        // Reuse ONLY the ordinary first-party site cookie; no native-device grant,
        // admin 2FA cookie or private sender credential may cross this boundary.
        val cookie = NativeGlyphClient.ordinaryCookie(
            CookieManager.getInstance().getCookie(ORIGIN))
            ?: return Reply(401, null, "ORDINARY_SITE_SESSION_REQUIRED")
        var conn: HttpsURLConnection? = null
        return try {
            conn = URL(ORIGIN + path).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Origin", ORIGIN)
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.setRequestProperty("Cookie", cookie)
            val status = conn.responseCode
            val stream = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?: return Reply(status, null, "HTTP_" + status)
            val body = ByteArrayOutputStream()
            stream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (body.size() + n > 196_608)
                        return Reply(503, null, "ACCOUNT_RESPONSE_TOO_LARGE")
                    body.write(buf, 0, n)
                }
            }
            if (!gate.isCurrent(epoch)) return Reply(499, null, "ACCOUNT_READ_CANCELLED")
            val json = JSONObject(body.toString(Charsets.UTF_8.name()))
            Reply(status, json, if (status in 200..299) null else
                json.optString("error").take(80).ifBlank { "HTTP_" + status })
        } catch (_: Exception) {
            Reply(503, null, "ACCOUNT_NETWORK_UNAVAILABLE")
        } finally {
            conn?.disconnect()
        }
    }
}

data class NativeAccountQuota(val key: String, val title: String,
    val used: Long, val limit: Long, val unit: String = "")
data class NativeAccountOverview(
    val label: String, val email: String, val sessionExpiresAt: Long,
    val planLabel: String?, val planKey: String?, val planExpiresAt: Long?,
    val quotas: List<NativeAccountQuota>, val unusedBenefits: List<String>,
    val usageCoverage: String, val expiryReminder: Boolean,
    val latestExpiredAt: Long?, val closureState: String?, val closureEmail: String?,
    val closureCoolingMs: Long?, val closureMethods: List<String>,
    val quotaAvailable: Boolean, val closureAvailable: Boolean
)

/** Server values, not a locally hardcoded plan entitlement. Null means unknown. */
object NativeAccountOverviewModels {
    private val benefitNames = mapOf(
        "second_active_mailbox" to "第二个活跃邮箱",
        "mailbox_extended_retention" to "邮箱延长保留",
        "daily_creation_boost" to "每日创建提升",
        "custom_prefix_boost" to "自定义前缀提升",
        "large_drop_plus_size" to "大文件容量提升",
        "large_drop_extended_retention" to "文件延长保留",
        "priority_admission" to "优先处理",
        "reserved_prefixes" to "保留前缀"
    )
    fun benefitLabel(key: String): String = benefitNames[key] ?: key.take(40)
    private fun validTime(value: Long): Long? =
        value.takeIf { it in 946684800000L..4102444800000L }
    fun read(account: JSONObject?, upgrade: JSONObject?, closure: JSONObject?): NativeAccountOverview? {
        if (account?.optBoolean("authenticated") != true) return null
        val verifiedUpgrade = upgrade?.takeIf { it.optBoolean("authenticated") == true }
        val plan = verifiedUpgrade?.optJSONObject("plan")
        val usage = verifiedUpgrade?.optJSONObject("usage")
        val items = usage?.optJSONObject("quotas")
        val quotas = mutableListOf<NativeAccountQuota>()
        fun count(key: String, title: String) {
            val q = items?.optJSONObject(key) ?: return
            if (!q.has("used") || !q.has("limit")) return
            val used = q.optLong("used", -1L)
            val limit = q.optLong("limit", -1L)
            if (used in 0..1_000_000_000L && limit in 0..1_000_000_000L)
                quotas.add(NativeAccountQuota(key, title, used, limit))
        }
        count("active_mailboxes", "活跃邮箱")
        count("mailbox_create_today", "今日创建")
        count("custom_prefix_today", "今日自定义")
        count("reserved_prefixes", "保留前缀")
        items?.optJSONObject("large_drop")?.let { drop ->
            val used = drop.optLong("used_bytes", -1L)
            val limit = drop.optLong("limit_bytes", -1L)
            if (used >= 0 && limit >= 0) quotas.add(NativeAccountQuota(
                "large_drop", "文件月/周期用量", used, limit, "bytes"))
        }
        val reminders = verifiedUpgrade?.optJSONObject("expiry_reminder")
        val benefitArray = usage?.optJSONArray("unused_benefits")
        val unused = mutableListOf<String>()
        if (benefitArray != null) for (i in 0 until minOf(benefitArray.length(), 16)) {
            val key = benefitArray.optString(i).take(60)
            if (key in benefitNames && key !in unused) unused.add(key)
        }
        val billing = verifiedUpgrade?.optJSONObject("billing")?.optJSONArray("records")
        var lastExpired: Long? = null
        if (billing != null) for (i in 0 until minOf(billing.length(), 12)) {
            val row = billing.optJSONObject(i) ?: continue
            if (row.optString("state") != "expired") continue
            val time = validTime(row.optLong("expires_at"))
            if (time != null && (lastExpired == null || time > lastExpired))
                lastExpired = time
        }
        val request = closure?.optJSONObject("request")
            ?: account.optJSONObject("closure_request")
        val methods = mutableListOf<String>()
        val allowed = closure?.optJSONArray("allowed_methods")
        if (allowed != null) for (i in 0 until minOf(allowed.length(), 8)) {
            val name = allowed.optString(i)
            if (name in setOf("password", "totp", "password_2fa") && name !in methods)
                methods.add(name)
        }
        return NativeAccountOverview(
            label = account.optString("account_label").take(120),
            email = verifiedUpgrade?.optString("registration_email").orEmpty().take(254),
            sessionExpiresAt = account.optLong("session_expires_at"),
            planLabel = plan?.optString("label")?.take(60),
            planKey = plan?.optString("key")?.take(60),
            planExpiresAt = plan?.optLong("expires_at")?.let(::validTime),
            quotas = quotas.take(5),
            unusedBenefits = unused,
            usageCoverage = usage?.optString("coverage").orEmpty(),
            expiryReminder = reminders?.optBoolean("enabled") == true,
            latestExpiredAt = lastExpired,
            closureState = request?.optString("state")?.take(40),
            closureEmail = closure?.optString("account_email")?.take(254),
            closureCoolingMs = closure?.optLong("cooling_period_ms")?.takeIf { it > 0 },
            closureMethods = methods,
            quotaAvailable = verifiedUpgrade != null,
            closureAvailable = closure?.optBoolean("enabled") == true
        )
    }
}
