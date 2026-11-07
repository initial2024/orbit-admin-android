package de.kango99w.orbitadmin

import org.json.JSONArray
import java.net.URI
import java.util.Locale

/** Safe, bounded HTTPS links for the native reader; nothing is opened automatically. */
data class NativeSafeMailLink(val url: String, val host: String, val label: String)

object NativeMailLinks {
    /** A candidate verification action, not proof that the sending site is trustworthy. */
    fun priority(links: List<NativeSafeMailLink>): NativeSafeMailLink? {
        val valid=links.filter { approved(it.url)!=null }
        val actions=Regex("验证|确认|激活|重置|登录|verify|confirm|activate|reset|sign.?in|log.?in|authorize|authentication",
            RegexOption.IGNORE_CASE)
        return valid.withIndex().maxByOrNull { pair ->
            val item=pair.value
            val path=try { URI(item.url).path.orEmpty() } catch(_: Exception) { "" }
            (if(actions.containsMatchIn(item.label)) 100 else 0) +
                (if(actions.containsMatchIn(path)) 20 else 0) -
                pair.index
        }?.value
    }

    private val naked = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    private val hostName = Regex("""^[a-z0-9.-]+$""")
    private val trailing = ".,;:!?)。 ，；：！？"

    fun approved(url: String): NativeSafeMailLink? {
        if (url.isBlank() || url.length > 2048 ||
            url.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null ||
                (uri.rawFragment?.length ?: 0) > 512 ||
                uri.port !in listOf(-1, 443) ||
                host.length !in 4..253 || !hostName.matches(host) ||
                host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal") ||
                !host.contains('.') || host.startsWith('.') || host.endsWith('.') ||
                ipv4.matches(host) || host.split('.').any { it.isBlank() })
                null
            else NativeSafeMailLink(url, host, host)
        } catch (_: Exception) { null }
    }

    fun read(value: JSONArray?, text: String, html: String): List<NativeSafeMailLink> {
        val result = LinkedHashMap<String,NativeSafeMailLink>()
        fun add(candidate: String, label: String = "") {
            if (result.size >= 12) return
            val safe = approved(candidate) ?: return
            val display = label.trim().replace(Regex("""\s+""")," ").take(120)
            result.putIfAbsent(safe.url, safe.copy(label = display.ifBlank { safe.host }))
        }
        if (value != null) for (i in 0 until minOf(value.length(),24)) {
            val link = value.optJSONObject(i) ?: continue
            add(link.optString("url"),link.optString("label"))
        }
        // Backward compatibility for a service that has not deployed safe_links.
        // HTML anchor-only links require the trusted server to expose that DTO.
        if (result.size < 12) {
            val visibleHtml = html.take(16000).replace(Regex("""<[^>]*>""")," ")
            for (match in naked.findAll((text.take(16000)+"\n"+visibleHtml).take(32000))) {
                add(match.value.trimEnd { it in trailing })
                if (result.size >= 12) break
            }
        }
        return result.values.toList()
    }
}
