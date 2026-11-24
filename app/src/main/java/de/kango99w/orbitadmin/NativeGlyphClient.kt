package de.kango99w.orbitadmin

import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Glyph inventory is currently protected by the ordinary SITE session.
 * It cannot be read with an administrator session or a native-device
 * grant until the Worker offers a reviewed account-bound native endpoint.
 */
class NativeGlyphClient {
    data class Reply(val status: Int, val value: JSONObject?, val error: String? = null)
    private val gate=AdminCookieCommitGate()
    private val origin="https://inbox.kango99w.de"

    fun epoch(): Long=gate.snapshot()
    fun invalidate()=gate.invalidate()

    companion object {
        private val sessionValue=Regex("^[A-Za-z0-9_.%-]{8,640}$")
        private val allowedReads=setOf("/api/lucky-glyphs/status",
            "/api/lucky-glyphs/collections")
        private val allowedWrites=setOf("/api/lucky-glyphs/checkin",
            "/api/lucky-glyphs/draw","/api/lucky-glyphs/equip",
            "/api/lucky-glyphs/shop/buy")

        fun ordinaryCookie(raw: String?): String? {
            val entries=raw.orEmpty().split(';').map { it.trim() }
            val found=entries.filter { it.startsWith("kango_site_access=") }
            if (found.size!=1) return null
            val token=found[0].removePrefix("kango_site_access=")
            return if(sessionValue.matches(token)) "kango_site_access=$token" else null
        }
        fun allowed(method: String, route: String): Boolean =
            (method=="GET" && route in allowedReads) ||
            (method=="POST" && route in allowedWrites)
    }

    fun hasOrdinarySiteSession(): Boolean =
        ordinaryCookie(CookieManager.getInstance().getCookie(origin)) != null

    fun get(route: String, epoch: Long): Reply=request("GET",route,null,epoch)
    fun post(route: String, body: JSONObject, epoch: Long): Reply=
        request("POST",route,body,epoch)

    private fun request(method: String, route: String,
                        body: JSONObject?, requestEpoch: Long): Reply {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Glyph I/O on UI thread" }
        if(!allowed(method,route))return Reply(400,null,"GLYPH_ROUTE_DENIED")
        if(!gate.isCurrent(requestEpoch))return Reply(499,null,"GLYPH_REQUEST_CANCELLED")
        val cookie=ordinaryCookie(CookieManager.getInstance().getCookie(origin))
            ?: return Reply(401,null,"ORDINARY_SITE_SESSION_REQUIRED")
        val bytes=(body?.toString() ?: "{}").toByteArray(Charsets.UTF_8)
        if(bytes.size>2048)return Reply(413,null,"GLYPH_REQUEST_TOO_LARGE")
        var conn: HttpsURLConnection?=null
        try{
            conn=URL(origin+route).openConnection() as HttpsURLConnection
            conn.requestMethod=method
            conn.instanceFollowRedirects=false
            conn.connectTimeout=9000
            conn.readTimeout=14000
            conn.useCaches=false
            conn.setRequestProperty("Cookie",cookie)
            conn.setRequestProperty("Origin",origin)
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Cache-Control","no-store")
            if(method=="POST"){
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status=conn.responseCode
            val input=if(status in 200..299) conn.inputStream else conn.errorStream
            if(input==null)return Reply(status,null,"HTTP_$status")
            val result=ByteArrayOutputStream()
            input.use { stream ->
                val buffer=ByteArray(4096)
                while(true){
                    val n=stream.read(buffer)
                    if(n<0)break
                    if(result.size()+n>256_000)return Reply(503,null,"GLYPH_RESPONSE_TOO_LARGE")
                    result.write(buffer,0,n)
                }
            }
            if(!gate.isCurrent(requestEpoch))
                return Reply(499,null,"GLYPH_REQUEST_CANCELLED")
            val json=JSONObject(result.toString("UTF-8"))
            return Reply(status,json,json.optString("error").ifBlank{null})
        }catch(_:Exception){return Reply(503,null,"GLYPH_NETWORK_UNAVAILABLE")}
        finally{conn?.disconnect()}
    }
}
