package de.kango99w.orbitadmin

import org.json.JSONObject

data class NativeSnapshotExport(val filename:String, val html:String, val count:Int)

/** First-party HTML archive to Android SAF; not a mailbox read authorization.
 * The activity additionally requires a recent, explicit "Save snapshot" action.
 * An archive may contain secrets; never log, index, auto-share or persist it here.
 */
object NativeSnapshotExportPolicy {
    private const val MAX_HTML_BYTES=512_000
    private val filename=Regex("^[a-z0-9@._-]{1,80}-snapshot-[0-9TZ-]{10,44}\\.html$")
    private val activeTags=Regex("<\\s*(?:script|iframe|object|embed|form|svg|math|base|meta\\s+http-equiv\\s*=\\s*['\\\"]?refresh)\\b",
        RegexOption.IGNORE_CASE)
    fun parse(raw:String?):NativeSnapshotExport? {
        if(raw.isNullOrEmpty() || raw.length>1_100_000)return null
        return try {
            val obj=JSONObject(raw)
            if(obj.keys().asSequence().toSet() !=
                setOf("v","kind","scope","filename","selected_count","html") ||
                obj.optInt("v")!=1 || obj.optString("kind")!="save_mail_snapshot" ||
                obj.optString("scope")!="public")return null
            val name=obj.optString("filename")
            val content=obj.optString("html")
            val count=obj.optInt("selected_count")
            if(!filename.matches(name) || count !in 1..20 ||
                content.length<500 || content.toByteArray(Charsets.UTF_8).size>MAX_HTML_BYTES ||
                !content.startsWith("<!doctype html>") ||
                !content.contains("Content-Security-Policy") ||
                !content.contains("default-src 'none'") ||
                !content.contains("frame-src 'none'") ||
                !content.contains("img-src 'none'") ||
                !content.contains("connect-src 'none'") ||
                !content.contains("referrer") ||
                !content.contains("no-referrer") ||
                !content.contains("Temp Mail Snapshot") ||
                activeTags.containsMatchIn(content))return null
            NativeSnapshotExport(name,content,count)
        } catch (_:Exception) { null }
    }
    fun trustedSource(scheme:String?,host:String?,port:Int,
        isMainFrame:Boolean,url:String?):Boolean=
        NativeInboxPolicy.acceptsSource(scheme,host,port,isMainFrame,url)
    fun armed(nowMs:Long, gestureAtMs:Long):Boolean=
        gestureAtMs>0L && nowMs>=gestureAtMs && nowMs-gestureAtMs<=8*60_000L
}
