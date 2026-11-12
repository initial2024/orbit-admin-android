package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test

class NativeHumanVerificationPolicyTest {
    private val valid="""{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"TURNSTILE_REQUIRED"}"""
    @Test fun acceptsOnlyFirstPartyManualChallengeSignal() {
        val result=NativeHumanVerificationPolicy.parse(valid)
        assertEquals("mailbox_create",result?.action)
        assertEquals("TURNSTILE_REQUIRED",result?.code)
        assertTrue(NativeHumanVerificationPolicy.trustedSource(
            "https","inbox.kango99w.de",-1,true,
            "https://inbox.kango99w.de/?orbit_app=android#orbit-create"))
        assertFalse(NativeHumanVerificationPolicy.trustedSource(
            "https","inbox.kango99w.de",-1,false,
            "https://inbox.kango99w.de/?orbit_app=android"))
        assertFalse(NativeHumanVerificationPolicy.trustedSource(
            "https","evil.example",-1,true,
            "https://evil.example/?orbit_app=android"))
        assertFalse(NativeHumanVerificationPolicy.trustedSource(
            "https","inbox.kango99w.de",-1,true,
            "https://inbox.kango99w.de/admin/?orbit_app=android"))
    }
    @Test fun rejectsInjectedTokensAndUnknownVerificationScopes() {
        for(raw in listOf(
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"TURNSTILE_REQUIRED","token":"secret"}""",
            """{"v":1,"kind":"human_verification_required","scope":"admin","action":"mailbox_create","code":"TURNSTILE_REQUIRED"}""",
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"native_login","code":"TURNSTILE_REQUIRED"}""",
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"TURNSTILE_CONFIGURATION_MISSING"}""",
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"429"}""",
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"TURNSTILE_REQUIRED","cookie":"admin"}""",
            """{"v":1,"kind":"human_verification_required","scope":"public","action":"mailbox_create","code":"TURNSTILE_REQUIRED"}""".repeat(60),
            "not-json"
        )) assertNull(NativeHumanVerificationPolicy.parse(raw))
        assertNotNull(NativeHumanVerificationPolicy.parse(
            valid.replace("TURNSTILE_REQUIRED","TURNSTILE_EXPIRED")))
        assertNotNull(NativeHumanVerificationPolicy.parse(
            valid.replace("TURNSTILE_REQUIRED","TURNSTILE_FAILED")))
    }
}
