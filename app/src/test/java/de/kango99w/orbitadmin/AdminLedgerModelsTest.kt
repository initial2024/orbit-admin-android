package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdminLedgerModelsTest {
    private val firstId="11111111-1111-4111-8111-111111111111"
    private val secondId="22222222-2222-4222-8222-222222222222"

    @Test fun externalLedgerRejectsInvalidRowsAndDeduplicates() {
        val input=JSONObject("""{"external_emails":[
          {"id":"$firstId","address":"one@gmail.com","provider":"gmail",
           "status":"active","label":"AI","registration_count":2,"updated_at":2000},
          {"id":"$firstId","address":"one@gmail.com","provider":"gmail","status":"active"},
          {"id":"$secondId","address":"two@example.org","provider":"other",
           "status":"disabled","registration_count":0,"updated_at":1000},
          {"id":"33333333-3333-4333-8333-333333333333","address":"bad@foo",
            "provider":"gmail","status":"active"},
          {"id":"44444444-4444-4444-8444-444444444444","address":"valid@example.org",
            "provider":"madeup","status":"active"}]}""")
        val records=AdminExternalEmailModels.list(input)
        assertEquals(2,records.size)
        assertEquals(2,records[0].registrationCount)
        assertEquals("Gmail",AdminExternalEmailModels.providerLabel(records[0].provider))
        assertEquals("停用",AdminExternalEmailModels.statusLabel(records[1].status))
        assertEquals(1,AdminRecordFilters.external(records,"AI").size)
        assertEquals(1,AdminRecordFilters.external(records,"","gmail").size)
        assertEquals("two@example.org",
            AdminRecordFilters.external(records,"","全部","全部","地址").last().address)
    }

    @Test fun externalDetailsMustMatchClickedMailAndStayBounded() {
        val input=JSONObject("""{"email":{"id":"$firstId","address":"one@gmail.com",
          "provider":"gmail","status":"active"},"registrations":[
          {"id":"$secondId","service":"Github","lifecycle_stage":"active",
           "account_label":"userA","registered_at":1794000000000},
          {"id":"44444444-4444-4444-8444-444444444444",
           "service":"Unknown","lifecycle_stage":"invented"}]}""")
        assertNull(AdminExternalEmailModels.detail(input,secondId))
        val detail=AdminExternalEmailModels.detail(input,firstId)!!
        assertEquals(1,detail.registrations.size)
        assertEquals("Github",detail.registrations.first().service)
        assertEquals("userA",detail.registrations.first().accountLabel)
    }

    private fun row(id:String,service:String,source:String,life:String,status:String,at:Long)=
        AdminRegisteredAccount(id,service,"label",service+"@example.org",
            source,status,life,at,0,"purpose")

    @Test fun registrationFiltersMatchWebsiteLifecycleAndDoNotLoseOlderRecords() {
        val records=listOf(
            row(firstId,"GitHub","pieterj","active","active",10),
            row(secondId,"Claude","ordinary","attention","active",20),
            row("33333333-3333-4333-8333-333333333333",
                "Gitlab","ordinary","closed","closed",30))
        assertEquals(2,AdminRecordFilters.registrations(records,"Git","全部").size)
        assertEquals(firstId,
            AdminRecordFilters.registrations(records,"","pieterj").single().id)
        assertEquals(secondId,
            AdminRecordFilters.registrations(records,"","ordinary","attention").single().id)
        assertEquals("Gitlab",
            AdminRecordFilters.registrations(records,"","全部","全部","closed").single().service)
        assertEquals("GitHub",
            AdminRecordFilters.registrations(records,"","全部","全部","全部","旧到新")
                .first().service)
        assertEquals("Gitlab",
            AdminRecordFilters.registrations(records,"").first().service)
    }
}
