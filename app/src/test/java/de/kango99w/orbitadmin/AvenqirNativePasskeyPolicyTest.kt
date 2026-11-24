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
