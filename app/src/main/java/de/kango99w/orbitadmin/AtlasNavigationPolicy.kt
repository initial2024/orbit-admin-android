package de.kango99w.orbitadmin

import java.net.URI
import java.util.Locale

/**
 * Explicit AVENQIR-only WebView main-frame boundary.
 * The public atlas must never inherit mail, admin, or private-mail URLs.
 */
object AtlasNavigationPolicy {
    const val ENTRY = "https://avenqir.bg5.net/directory.html"
    const val HOST = "avenqir.bg5.net"

    private fun safeHttps(raw: String): URI? = try {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443)) null else uri
    } catch (_: Exception) { null }

    fun isAtlas(raw: String): Boolean =
        safeHttps(raw)?.host?.equals(HOST, ignoreCase = true) == true

    /** Only a user-initiated HTTPS destination may leave the atlas. */
    fun isExternalHttps(raw: String): Boolean =
        safeHttps(raw)?.host?.equals(HOST, ignoreCase = true) == false
}
