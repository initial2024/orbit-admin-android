package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAccountOverviewTest {
    private val status=JSONObject("""{
      "authenticated":true,"account_label":"Regular",
      "session_expires_at":1796000000000,"closure_request":null
    }""")
    private val upgrade=JSONObject("""{
      "authenticated":true,"registration_email":"regular@example.org",
      "plan":{"key":"founding_plus","label":"Founding Plus","expires_at":1796500000000},
      "usage":{"coverage":"partial",
        "quotas":{"active_mailboxes":{"used":1,"limit":2},
          "mailbox_create_today":{"used":6,"limit":15},
          "custom_prefix_today":{"used":2,"limit":5},
          "large_drop":{"used_bytes":2097152,"limit_bytes":1073741824}},
        "unused_benefits":["daily_creation_boost","priority_admission"]},
      "mailbox_capacity":{"verified":true,"checked_at":1796000000000,"pool":"plus",
         "global":{"used":97,"limit":150,"remaining":53},
         "free_pool":{"used":60,"limit":60,"remaining":0},
         "nonpaid_pool":{"used":80,"limit":110,"remaining":30},
         "estimated_plan_pool_remaining":53},
      "billing":{"records":[]},
      "expiry_reminder":{"enabled":true}
    }""")
    @Test fun quotaAndGlobalCapsAreServerOwned() {
        val view=NativeAccountOverviewModels.read(status,upgrade,
            JSONObject("""{"enabled":true,"account_email":"regular@example.org",
                "allowed_methods":["password_2fa"],"cooling_period_ms":604800000}"""))!!
        assertEquals("Founding Plus",view.planLabel)
        assertEquals(2L,view.quotas.first{it.key=="active_mailboxes"}.limit)
        assertEquals(6L,view.quotas.first{it.key=="mailbox_create_today"}.used)
        assertEquals(97L,view.mailboxCapacity!!.globalUsed)
        assertEquals(150L,view.mailboxCapacity!!.globalLimit)
        assertEquals(53L,view.mailboxCapacity!!.estimatedAvailable)
        assertEquals("plus",view.mailboxCapacity!!.pool)
        assertTrue(view.expiryReminder)
        assertEquals("partial",view.usageCoverage)
        assertEquals(listOf("password_2fa"),view.closureMethods)
    }
    @Test fun missingOrFailedServerCapacityCannotClaimFreeSlots() {
        val copy=JSONObject(upgrade.toString())
        copy.put("mailbox_capacity",JSONObject("""{"verified":false,
            "global":{"used":0,"limit":150,"remaining":150}}"""))
        assertNull(NativeAccountOverviewModels.read(status,copy,null)!!.mailboxCapacity)
        copy.remove("mailbox_capacity")
        assertNull(NativeAccountOverviewModels.read(status,copy,null)!!.mailboxCapacity)
        assertNull(NativeAccountOverviewModels.read(
            JSONObject("""{"authenticated":false}"""),copy,null))
    }
    @Test fun overviewDoesNotGrantPrivilegedMutations() {
        assertTrue(NativeAccountOverviewClient.allowed("GET","/api/account/status"))
        assertTrue(NativeAccountOverviewClient.allowed("GET","/api/upgrade/status"))
        assertTrue(NativeAccountOverviewClient.allowed("GET","/api/account/closure/options"))
        for(route in listOf("/api/account/closure","/api/admin/status",
            "/api/upgrade/redeem","/api/account/native-device/pair",
            "/api/upgrade/status?subject=admin:1")) {
            assertFalse(NativeAccountOverviewClient.allowed("GET",route))
            assertFalse(NativeAccountOverviewClient.allowed("POST",route))
        }
    }
    @Test fun invalidCountersAreNotPresentedAsRealUsage() {
        val copy=JSONObject(upgrade.toString())
        val quotas=copy.getJSONObject("usage").getJSONObject("quotas")
        quotas.getJSONObject("active_mailboxes").put("used",-1)
        quotas.getJSONObject("mailbox_create_today").put("limit",-9)
        val view=NativeAccountOverviewModels.read(status,copy,null)!!
        assertFalse(view.quotas.any{it.key=="active_mailboxes"})
        assertFalse(view.quotas.any{it.key=="mailbox_create_today"})
    }
}
