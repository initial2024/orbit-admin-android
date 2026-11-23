package de.kango99w.orbitadmin

import java.net.URI

/**
 * Public AVENQIR is an independent trust realm, not an Orbit authentication scope.
 * Only its exact HTTPS origin is allowed inside the isolated WebView.
 */
object AvenqirUrlPolicy {
    const val HOME = "https://avenqir.bg5.net/"

    fun isFirstParty(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("avenqir.bg5.net", ignoreCase = true) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    } catch (_: Exception) {
        false
    }

    fun isExternalHttps(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && !isFirstParty(url)
    } catch (_: Exception) {
        false
    }
}