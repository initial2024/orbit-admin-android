package de.kango99w.orbitadmin

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class AvenqirPublicStatus(val count: Int)

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

    fun fetch(): AvenqirPublicStatus? {
        // Refuse to use a process-global Java cookie handler, if installed.
        // Never access WebView CookieManager, admin grants or mail credentials.
        if (CookieHandler.getDefault() != null) return null
        val c = try { URL(ENDPOINT).openConnection() as HttpsURLConnection }
            catch (_: Exception) { return null }
        return try {
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200 ||
                !c.contentType.orEmpty().startsWith("application/json", ignoreCase = true) ||
                c.contentLengthLong > MAX_BYTES) return null
            val raw = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                val deadline = System.nanoTime() + 6_000_000_000L
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) return null
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            parse(raw)
        } catch (_: Exception) {
            null
        } finally { c.disconnect() }
    }
}
