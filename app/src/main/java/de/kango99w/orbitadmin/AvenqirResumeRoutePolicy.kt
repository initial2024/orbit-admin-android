package de.kango99w.orbitadmin

import java.net.URI

/**
 * Minimal, non-secret route-only restoration after Android recreates the
 * activity. In a live process we keep the actual WebView, preserving scroll,
 * forms, navigation history and JS state. On process death exact web state
 * cannot be restored safely, so resume only known public site paths and
 * admin landing pages (whose authorization is checked by AVENQIR itself).
 */
object AvenqirResumeRoutePolicy {
    private val allowedPaths = setOf(
        "/", "/index.html", "/directory.html", "/tools.html",
        "/check.html", "/projects.html", "/domains.html",
        "/logs.html", "/health.html", "/bookmarks.html",
        "/notes.html", "/transfer.html", "/daily.html", "/fun.html",
        "/appearance.html", "/privacy.html", "/disclaimer.html",
        "/deploy-status.html", "/orbit-bridge.html",
        "/admin.php", "/owner-sites.php", "/owner-projects.php", "/owner-feedback-security.php", "/feedback-admin.php", "/feedback.php", "/passkey.php"
    )

    fun publicPageWithoutState(url: String?): String? {
        if (url.isNullOrBlank() || !AvenqirUrlPolicy.isFirstParty(url)) return null
        return try {
            val uri = URI(url)
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            if (path in allowedPaths) AvenqirUrlPolicy.HOME.trimEnd('/') + path
            else null
        } catch (_: Exception) {
            null
        }
    }
}
