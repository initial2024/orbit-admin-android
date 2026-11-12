package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdminPasskeyCapabilityTest {
    @Test fun capabilityMustMatchExactProtocolOriginAndCurrentPackage() {
        val good=JSONObject()
            .put("enabled",true).put("protocol","webauthn-v1")
            .put("rp_id","inbox.kango99w.de")
            .put("android_package","de.kango99w.orbitadmin")
            .put("assetlinks_ready",true).put("signing_ready",true)
            .put("sign_in",true).put("registration",true)
        assertTrue(NativeAdminPasskeyGateway.Capability
            .parse(good,"de.kango99w.orbitadmin")!!.signIn)
        assertNull(NativeAdminPasskeyGateway.Capability.parse(good,"de.kango99w.orbitadmin.compactpreview"))
        for(key in listOf("assetlinks_ready","signing_ready","enabled")) {
            val bad=JSONObject(good.toString()).put(key,false)
            assertNull(NativeAdminPasskeyGateway.Capability.parse(bad,"de.kango99w.orbitadmin"))
        }
        assertNull(NativeAdminPasskeyGateway.Capability.parse(JSONObject(good.toString())
            .put("rp_id","kango99w.de"),"de.kango99w.orbitadmin"))
    }

    @Test fun challengeMustCarryServerRequestIdAndPublicKey() {
        val good=JSONObject()
            .put("request_id","a".repeat(24))
            .put("public_key",JSONObject().put("challenge","b".repeat(32))
                .put("rpId","inbox.kango99w.de"))
        assertNotNull(NativeAdminPasskeyGateway.Challenge.parse(good))
        assertNull(NativeAdminPasskeyGateway.Challenge.parse(
            JSONObject(good.toString()).put("request_id","short")))
        assertNull(NativeAdminPasskeyGateway.Challenge.parse(
            JSONObject(good.toString()).put("public_key",JSONObject())))
    }
}
