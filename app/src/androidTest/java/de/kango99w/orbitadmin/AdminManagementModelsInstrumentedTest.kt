package de.kango99w.orbitadmin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminManagementModelsInstrumentedTest {
    @Test fun adminDomainsAreNativeTypedAndDoNotInventCreationRights() {
        val privateBody=JSONObject("""{
            "domains":[{"domain":"pieterj.nl.eu.org","ingress":"cloudflare_email","enabled":true}],
            "site_domains":[{"domain":"yues.blogu.tc","state":"pending"}],
            "domain_requests":[{"domain":"new.example.net","state":"pending","ingress":"unknown"}]
        }""")
        val ordinaryBody=JSONObject("""{"domains":[{"domain":"temp.example.net","label":"Temporary"}]}""")
        val domains=AdminManagementModels.domains(privateBody,ordinaryBody)
        assertEquals(4,domains.size)
        assertEquals("管理员邮箱",domains[0].source)
        assertEquals("网站域名",domains[1].source)
        assertEquals("pending",domains[1].state)
        assertEquals("域名申请",domains[2].source)
        assertEquals("临时邮箱",domains[3].source)
        assertFalse(domains.any{it.domain.contains("http://")})
    }

    @Test fun recordedAddressesRequireValidatedEmailAndServerReservation() {
        val body=JSONObject("""{"records":[
          {"address":"known@example.org","source":"admin","reserved":true},
          {"address":"released@example.org","source":"ordinary","reserved":false},
          {"address":"../../p","source":"spoof","reserved":true},
          {"address":"bad@ host","source":"spoof","reserved":true}
        ]}""")
        val rows=AdminManagementModels.recordedAddresses(body)
        assertEquals(2,rows.size)
        assertTrue(rows[0].reserved)
        assertFalse(rows[1].reserved)
    }

    @Test fun administratorSecurityDataRemainsSeparateFromOrdinaryDeviceGrants() {
        val totp=JSONObject("""{"enabled":true,"recovery_codes_remaining":5}""")
        val devices=JSONObject("""{"devices":[
          {"id":"11111111-1111-4111-8111-111111111111","device_label":"My phone","expires_at":1796000000000},
          {"id":"../../invalid","device_label":"spoof"},
          {"id":"22222222-2222-4222-8222-222222222222","device_label":"Desktop","expires_at":1796000000000}
        ]}""")
        val state=AdminManagementModels.security(totp,devices)
        assertTrue(state.totpEnabled)
        assertEquals(5,state.recoveryCodesRemaining)
        assertEquals(2,state.trustedDevices.size)
    }
}
