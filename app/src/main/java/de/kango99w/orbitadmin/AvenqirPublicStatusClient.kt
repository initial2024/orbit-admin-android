package de.kango99w.orbitadmin

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AvenqirPublicStatus(val count: Int)
enum class AvenqirCheckIssue { LOCAL_POLICY, BROWSER_REQUIRED, HTTP, NETWORK, INVALID_DATA }
data class AvenqirCheckResult(val status: AvenqirPublicStatus? = null, val issue: AvenqirCheckIssue? = null)

/** One bounded, user-visible refresh per five minutes per activity.
 * Never periodically schedule or perform traffic while the app is locked.
 */
class AvenqirStatusGate {
    private var lastAttempt: Long? = null
    fun tryStart(now: Long): Boolean {
        val last = lastAttempt
        if (last != null && now >= last && now - last < 300_000L) return false
        lastAttempt = now
        return true
    }
}

object AvenqirPublicStatusClient {
    private const val ENDPOINT = "https://avenqir.bg5.net/directory-status.php"
    private const val LEGACY_ENDPOINT = "https://avenqir.bg5.net/public-api.php"
    private const val MAX_STATUS_BYTES = 4096
    private const val MAX_LEGACY_BYTES = 2 * 1024 * 1024
    // This client owns its transport. It neither uses java.net.CookieHandler
    // nor accesses WebView, account grants, cookies or authentication headers.
    private val client by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun parse(raw: String): AvenqirPublicStatus? = try {
        val obj = JSONObject(raw)
        if (obj.opt("ok") != true) null
        else if (obj.has("schema")) {
            val count = obj.opt("count")
            if (obj.opt("schema") != "avenqir-directory-status-v1" ||
                count !is Int || count !in 0..300) null
            else AvenqirPublicStatus(count)
        } else {
            val items = obj.optJSONArray("items")
            if (items == null || items.length() > 300) null
            else AvenqirPublicStatus(items.length())
        }
    } catch (_: Exception) { null }

    fun classifyResponse(code: Int, contentType: String?): AvenqirCheckIssue? = when {
        code in 300..399 || code == 403 || code == 429 -> AvenqirCheckIssue.BROWSER_REQUIRED
        code != 200 -> AvenqirCheckIssue.HTTP
        contentType.orEmpty().startsWith("text/html", ignoreCase = true) -> AvenqirCheckIssue.BROWSER_REQUIRED
        !contentType.orEmpty().startsWith("application/json", ignoreCase = true) -> AvenqirCheckIssue.INVALID_DATA
        else -> null
    }
    fun mayUseLegacyEndpoint(code: Int): Boolean = code == 404

    fun fetch(): AvenqirCheckResult {
        // Only a missing endpoint permits a compatibility request. Never retry
        // access-denied, verification, rate-limit or network errors automatically.
        val first = fetchEndpoint(ENDPOINT, MAX_STATUS_BYTES)
        return if (mayUseLegacyEndpoint(first.first)) fetchEndpoint(LEGACY_ENDPOINT, MAX_LEGACY_BYTES).second
        else first.second
    }

    private fun fetchEndpoint(url: String, limit: Int): Pair<Int, AvenqirCheckResult> {
        return try {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            val issue = classifyResponse(code, response.header("Content-Type"))
            if (issue != null) return code to AvenqirCheckResult(issue = issue)
            val body = response.body ?: return code to AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
            if (body.contentLength() > limit) return code to AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
            val raw = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) return code to AvenqirCheckResult(issue = AvenqirCheckIssue.NETWORK)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > limit) return code to AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            val status = parse(raw)
            code to if (status == null) AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
                else AvenqirCheckResult(status = status)
        }
    } catch (_: Exception) {
        0 to AvenqirCheckResult(issue = AvenqirCheckIssue.NETWORK)
        }
    }
}
