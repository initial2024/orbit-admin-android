package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSnapshotExportPolicyTest {
    private fun packet(name:String,html:String,count:Int=2)=
        JSONObject().put("v",1).put("kind","save_mail_snapshot")
            .put("scope","public").put("filename",name)
            .put("selected_count",count).put("html",html).toString()
    private val name="demo@tmp.example-snapshot-2026-11-08T01-30-00-000Z.html"
    private val html="<!doctype html><html lang=\"zh-CN\"><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'none'; connect-src 'none'; frame-src 'none'\"><meta name=\"referrer\" content=\"no-referrer\"><title>Temp Mail Snapshot</title></head><body>"+
        "Safe escaped mail &lt;not active&gt; ".repeat(35)+"</body></html>"

    @Test fun exactOwnedExportAndNoActiveContent() {
        assertEquals(2,NativeSnapshotExportPolicy.parse(packet(name,html))?.count)
        assertNull(NativeSnapshotExportPolicy.parse(packet("../escape.html",html)))
        assertNull(NativeSnapshotExportPolicy.parse(packet(name,html,21)))
        assertNull(NativeSnapshotExportPolicy.parse(packet(name,html+"<script>alert(1)</script>")))
        assertNull(NativeSnapshotExportPolicy.parse(packet(name,html.replace("default-src 'none'","default-src *"))))
        assertNull(NativeSnapshotExportPolicy.parse(packet(name,html.replace("no-referrer","unsafe-url"))))
        assertNull(NativeSnapshotExportPolicy.parse(packet(name,html.repeat(2000))))
        assertNull(NativeSnapshotExportPolicy.parse(JSONObject(packet(name,html))
            .put("access_token","secret").toString()))
        assertNull(NativeSnapshotExportPolicy.parse(JSONObject(packet(name,html))
            .put("kind","save_private_mail").toString()))
    }
    @Test fun sourceAndHumanGestureMustMatch() {
        assertTrue(NativeSnapshotExportPolicy.trustedSource(
            "https","inbox.kango99w.de",-1,true,
            "https://inbox.kango99w.de/?orbit_app=android#orbit-snapshot"))
        assertFalse(NativeSnapshotExportPolicy.trustedSource(
            "https","inbox.kango99w.de",-1,false,
            "https://inbox.kango99w.de/?orbit_app=android"))
        assertFalse(NativeSnapshotExportPolicy.trustedSource(
            "https","attacker.invalid",-1,true,
            "https://attacker.invalid/?orbit_app=android"))
        assertTrue(NativeSnapshotExportPolicy.armed(480_000L,1000L))
        assertFalse(NativeSnapshotExportPolicy.armed(481_001L,1000L))
        assertFalse(NativeSnapshotExportPolicy.armed(1000L,0L))
        assertFalse(NativeSnapshotExportPolicy.armed(900L,1000L))
    }
}
