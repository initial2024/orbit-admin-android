package de.kango99w.orbitadmin

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import javax.net.ssl.HttpsURLConnection

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
    private const val ENDPOINT = "https://avenqir.bg5.net/public-api.php"
    private const val MAX_BYTES = 2 * 1024 * 1024
    fun parse(raw: String): AvenqirPublicStatus? = try {
        val obj = JSONObject(raw)
        val items = obj.optJSONArray("items")
        if (obj.opt("ok") != true || items == null || items.length() > 300) null
        else AvenqirPublicStatus(items.length())
    } catch (_: Exception) { null }

    fun classifyResponse(code: Int, contentType: String?): AvenqirCheckIssue? = when {
        code in 300..399 || code == 403 || code == 429 ||
            contentType.orEmpty().startsWith("text/html", ignoreCase = true) -> AvenqirCheckIssue.BROWSER_REQUIRED
        code != 200 -> AvenqirCheckIssue.HTTP
        !contentType.orEmpty().startsWith("application/json", ignoreCase = true) -> AvenqirCheckIssue.INVALID_DATA
        else -> null
    }
    fun fetch(): AvenqirCheckResult {
        // Refuse to use a process-global Java cookie handler, if installed.
        // Never access WebView CookieManager, admin grants or mail credentials.
        if (CookieHandler.getDefault() != null) return AvenqirCheckResult(issue = AvenqirCheckIssue.LOCAL_POLICY)
        val c = try { URL(ENDPOINT).openConnection() as HttpsURLConnection }
            catch (_: Exception) { return AvenqirCheckResult(issue = AvenqirCheckIssue.NETWORK) }
        return try {
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            val issue = classifyResponse(c.responseCode, c.contentType)
            if (issue != null) return AvenqirCheckResult(issue = issue)
            if (c.contentLengthLong > MAX_BYTES) return AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
            val raw = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                val deadline = System.nanoTime() + 8_000_000_000L
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) return AvenqirCheckResult(issue = AvenqirCheckIssue.NETWORK)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            val status = parse(raw)
            if (status == null) AvenqirCheckResult(issue = AvenqirCheckIssue.INVALID_DATA)
            else AvenqirCheckResult(status = status)
        } catch (_: Exception) {
            AvenqirCheckResult(issue = AvenqirCheckIssue.NETWORK)
        } finally { c.disconnect() }
    }
}
