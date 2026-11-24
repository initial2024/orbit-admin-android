package de.kango99w.orbitadmin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AvenqirNativePasskeyPolicyTest {
    @Test fun onlyExactMainFramePasskeyPage() {
        val p=AvenqirNativePasskeyPolicy
        assertTrue(p.allows(p.ORIGIN,true,p.PAGE))
        assertFalse(p.allows(p.ORIGIN,false,p.PAGE))
        assertFalse(p.allows("https://evil.example",true,p.PAGE))
        assertFalse(p.allows(p.ORIGIN,true,p.ORIGIN+"/admin.php"))
        assertFalse(p.allows(p.ORIGIN,true,p.PAGE+"?other=1"))
    }
    @Test fun browserEntryAllowsOnlyLiteralCommandFromUnlockedForegroundFirstPartyPage() {
        val p=AvenqirNativePasskeyPolicy
        val cmd="OPEN_PASSKEY_PAGE"
        assertTrue(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE,cmd,true,true))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,false,p.PAGE,cmd,true,true))
        assertFalse(p.allowsBrowserOpen("https://example.org",true,p.PAGE,cmd,true,true))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE+"?next=1",cmd,true,true))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE,"https://example.org",true,true))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE,cmd,false,true))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE,cmd,true,false))
        assertFalse(p.allowsBrowserOpen(p.ORIGIN,true,p.PAGE,null,true,true))
    }
    @Test fun consentCannotBypassForegroundOrOrigin() {
        val p=AvenqirNativePasskeyPolicy
        assertTrue(p.canLaunchProvider(true,true,true,p.PAGE))
        assertFalse(p.canLaunchProvider(false,true,true,p.PAGE))
        assertFalse(p.canLaunchProvider(true,false,true,p.PAGE))
        assertFalse(p.canLaunchProvider(true,true,false,p.PAGE))
        assertFalse(p.canLaunchProvider(true,true,true,p.ORIGIN+"/admin.php"))
    }
    @Test fun frameworkAndAndroidXCancellationAreRecognized() {
        val p=AvenqirNativePasskeyPolicy
        assertTrue(p.providerCancelled("android.credentials.CreateCredentialException.TYPE_USER_CANCELED"))
        assertTrue(p.providerCancelled("androidx.credentials.TYPE_CREATE_CREDENTIAL_CANCELLATION_EXCEPTION"))
        assertFalse(p.providerCancelled("android.credentials.CreateCredentialException.TYPE_UNKNOWN"))
    }
    @Test fun actualVivoSecurityErrorIsNotCancellation() {
        val p=AvenqirNativePasskeyPolicy
        val error="androidx.credentials.TYPE_GET_PUBLIC_KEY_CREDENTIAL_DOM_EXCEPTION/androidx.credentials.TYPE_SECURITY_ERROR"
        assertTrue(p.providerSecurityRejected(error))
        assertFalse(p.providerCancelled(error))
        assertTrue(p.providerSecurityRejected("SECURITY"))
        assertFalse(p.providerSecurityRejected("androidx.credentials.TYPE_GET_CREDENTIAL_CANCELLATION_EXCEPTION"))
        assertFalse(p.providerSecurityRejected("androidx.credentials.TYPE_NO_CREDENTIAL"))
    }
    @Test fun nativeCreationCompatibilityPreservesServerSecurityFields() {
        val p=AvenqirNativePasskeyPolicy
        val source=JSONObject("""{"challenge":"${"A".repeat(43)}","rp":{"id":"avenqir.bg5.net","name":"AVENQIR"},"user":{"name":"adm","id":"AQID","displayName":"adm"},"authenticatorSelection":{"userVerification":"required","residentKey":"required","requireResidentKey":true},"attestation":"none","pubKeyCredParams":[{"type":"public-key","alg":-8},{"type":"public-key","alg":-7},{"type":"public-key","alg":-257}],"extensions":{"exts":true,"credProps":true},"excludeCredentials":[{"id":"AQIDBA","type":"public-key","transports":["internal","hybrid"]}]}""")
        val out=p.providerOptions("create",source)
        assertEquals(-7,out.getJSONArray("pubKeyCredParams").getJSONObject(0).getInt("alg"))
        assertEquals(-257,out.getJSONArray("pubKeyCredParams").getJSONObject(1).getInt("alg"))
        assertEquals(-8,out.getJSONArray("pubKeyCredParams").getJSONObject(2).getInt("alg"))
        for (name in listOf("challenge","rp","user","authenticatorSelection","attestation"))
            assertEquals(source.get(name).toString(),out.get(name).toString())
        assertEquals("AQIDBA",out.getJSONArray("excludeCredentials").getJSONObject(0).getString("id"))
        assertFalse(out.getJSONArray("excludeCredentials").getJSONObject(0).has("transports"))
        assertTrue(source.getJSONObject("extensions").has("exts"))
        assertFalse(out.getJSONObject("extensions").has("exts"))
        assertTrue(out.getJSONObject("extensions").getBoolean("credProps"))
        val get=JSONObject().put("challenge","A".repeat(43)).put("rpId",p.RP)
            .put("userVerification","required").put("allowCredentials",org.json.JSONArray().put(JSONObject().put("id","abc")))
        assertEquals(get.toString(),p.providerOptions("get",get).toString())
    }
    @Test fun onlyPinnedRelyingPartyAndRequiredVerification() {
        val p=AvenqirNativePasskeyPolicy
        val challenge="A".repeat(43)
        val get=JSONObject().put("challenge",challenge).put("rpId",p.RP)
            .put("userVerification","required").put("allowCredentials",org.json.JSONArray().put(JSONObject().put("id","abc")))
        assertTrue(p.validOptions("get",get))
        assertFalse(p.validOptions("get",JSONObject(get.toString()).put("rpId","evil.example")))
        assertFalse(p.validOptions("get",JSONObject(get.toString()).put("origin","https://evil.example")))
        assertFalse(p.validOptions("get",JSONObject(get.toString()).put("userVerification","discouraged")))
        val create=JSONObject().put("challenge",challenge).put("rp",JSONObject().put("id",p.RP))
            .put("user",JSONObject().put("name","adm"))
            .put("authenticatorSelection",JSONObject().put("userVerification","required")).put("attestation","none")
        assertTrue(p.validOptions("create",create))
        assertFalse(p.validOptions("create",JSONObject(create.toString()).put("user",JSONObject().put("name","other"))))
        assertFalse(p.validOptions("password",create))
    }
}
