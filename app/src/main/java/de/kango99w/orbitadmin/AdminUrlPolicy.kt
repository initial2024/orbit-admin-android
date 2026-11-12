package de.kango99w.orbitadmin

import java.net.URI

/** A deliberately strict *top-level navigation* allowlist; never trust URL substring matches. */
object AdminUrlPolicy {
    const val HOME = "https://inbox.kango99w.de/admin/"

    fun isTrustedMainFrame(url: String): Boolean = try {
        val parsed = URI(url)
        parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host.equals("inbox.kango99w.de", ignoreCase = true) &&
            parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port == 443)
    } catch (_: Exception) {
        false
    }

    fun isExternalHttps(url: String): Boolean = try {
        val parsed = URI(url)
        parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.host.isNullOrEmpty() &&
            parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port == 443) &&
            !isTrustedMainFrame(url)
    } catch (_: Exception) {
        false
    }
}
