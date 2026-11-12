package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdminMailboxActivationTest {
    private val externalId = "11111111-1111-4111-8111-111111111111"
    private val registrationId = "22222222-2222-4222-8222-222222222222"
    private val address = "original@haha.int.yt"
    private val allowed = setOf("haha.int.yt")

    private fun record(
        external: String? = externalId, registered: String? = null,
        reserved: Boolean = true, privateId: String? = null
    ) = AdminRecordedAddress(
        address=address, source="external", reserved=reserved,
        externalId=external, privateId=privateId,
        ordinaryRegistrationId=registered
    )

    @Test fun activationReadsRealServerRecordIdentifiersNotCopyPastedAddresses() {
        val rows=AdminManagementModels.recordedAddresses(JSONObject(
            """{"records":[{"address":"original@haha.int.yt","source":"external",
            "reserved":true,"external_id":"$externalId"},
            {"address":"ordinary@haha.int.yt","reserved":true,
            "ordinary_registration_id":"$registrationId"},
            {"address":"unowned@haha.int.yt","reserved":true,
            "external_id":"bad/../id"}]}"""
        ))
        assertEquals(3,rows.size)
        assertEquals(externalId,rows[0].externalId)
        assertNull(rows[2].externalId)
        val external=AdminMailboxActivationPolicy.forRecord(rows[0],allowed,emptyList())!!
        assertEquals(AdminMailboxActivationKind.EXTERNAL,external.kind)
        assertEquals("/api/admin/ordinary-inbox/activate-recorded",external.endpoint)
        val ordinary=AdminMailboxActivationPolicy.forRecord(rows[1],allowed,emptyList())!!
        assertEquals(AdminMailboxActivationKind.ORDINARY_REGISTRATION,ordinary.kind)
        assertEquals("/api/admin/account-registry/registrations/$registrationId/revive",ordinary.endpoint)
        assertNull(AdminMailboxActivationPolicy.forRecord(rows[2],allowed,emptyList()))
    }

    @Test fun blockersNeverBecomeClientSideRoutedMailboxes() {
        assertNull(AdminMailboxActivationPolicy.forRecord(record(),emptySet(),emptyList()))
        assertNull(AdminMailboxActivationPolicy.forRecord(record(reserved=false),allowed,emptyList()))
        assertNull(AdminMailboxActivationPolicy.forRecord(record(privateId=registrationId),allowed,emptyList()))
        assertNull(AdminMailboxActivationPolicy.forRecord(
            record(),allowed,listOf(AdminNativeMailbox(
                externalId,address,false,status="active",
                expiresAt=System.currentTimeMillis()+60000))))
        val ordinaryWins=AdminMailboxActivationPolicy.forRecord(
            record(registered=registrationId),allowed,emptyList())!!
        assertEquals(AdminMailboxActivationKind.ORDINARY_REGISTRATION,ordinaryWins.kind)
    }

    @Test fun syntheticExpiredRegistrationIsVisibleButNeverUsedAsMailboxRoute() {
        val body=JSONObject(
            """{"mailboxes":[{"id":"registration:$registrationId",
            "address":"ordinary@haha.int.yt","status":"expired",
            "registration_id":"$registrationId","revivable":true,
            "expires_at":100},{"id":"bad/../id",
            "address":"ignored@haha.int.yt","status":"expired",
            "registration_id":"$registrationId","revivable":true}]}"""
        )
        val mailboxes=AdminNativeModels.mailboxes(body,false)
        assertEquals(1,mailboxes.size)
        val item=mailboxes.single()
        assertEquals("registration:$registrationId",item.id)
        assertTrue(item.revivable)
        val intent=AdminMailboxActivationPolicy.forMailbox(item,allowed)!!
        assertEquals(registrationId,intent.id)
        assertEquals("/api/admin/account-registry/registrations/$registrationId/revive",intent.endpoint)
        assertFalse(AdminNativeModels.validId(item.id)) // old mailbox ID must never be used for a read or DELETE
        assertNull(AdminMailboxActivationPolicy.forMailbox(item,emptySet()))
    }

    @Test fun successRequiresNewLiveIdSameExactAddressAndExpectedReceipt() {
        val intent=AdminMailboxActivationIntent(address,externalId,AdminMailboxActivationKind.EXTERNAL)
        val newId="33333333-3333-4333-8333-333333333333"
        fun receipt(addr:String=address, expiry:Long=System.currentTimeMillis()+120000)=
            JSONObject().put("mailbox",JSONObject()
                .put("id",newId).put("address",addr)
                .put("expires_at",expiry).put("routing_rule_status","active"))
        assertNotNull(AdminMailboxActivationPolicy.confirmedMailbox(intent,201,receipt()))
        assertNull(AdminMailboxActivationPolicy.confirmedMailbox(intent,200,receipt()))
        assertNull(AdminMailboxActivationPolicy.confirmedMailbox(intent,201,receipt("other@haha.int.yt")))
        assertNull(AdminMailboxActivationPolicy.confirmedMailbox(intent,201,receipt(expiry=100)))
        assertNull(AdminMailboxActivationPolicy.confirmedMailbox(intent,201,
            JSONObject().put("mailbox",JSONObject().put("id","bad/../id")
                .put("address",address).put("expires_at",System.currentTimeMillis()+120000))))
        val restore=AdminMailboxActivationIntent(address,registrationId,
            AdminMailboxActivationKind.ORDINARY_REGISTRATION)
        assertNull(AdminMailboxActivationPolicy.confirmedMailbox(restore,201,receipt()))
        assertNotNull(AdminMailboxActivationPolicy.confirmedMailbox(restore,201,
            receipt().put("revived",true)))
    }

    @Test fun nativeAdminTransportAllowlistIsNarrow() {
        assertTrue(NativeAdminClient.allows("POST","/api/admin/ordinary-inbox/activate-recorded"))
        assertTrue(NativeAdminClient.allows("POST",
            "/api/admin/account-registry/registrations/$registrationId/revive"))
        for(path in listOf("/api/admin/account-registry/registrations/$registrationId",
            "/api/admin/account-registry/registrations/../revive",
            "/api/admin/account-registry/registrations/$registrationId/revive?force=true",
            "/api/admin/ordinary-inbox/activate-recorded?address=other")) {
            assertFalse(NativeAdminClient.allows("POST",path))
        }
        assertFalse(NativeAdminClient.allows("GET","/api/admin/ordinary-inbox/activate-recorded"))
        assertFalse(NativeAdminClient.allows("DELETE",
            "/api/admin/account-registry/registrations/$registrationId/revive"))
    }

    @Test fun presentationUsesNativeActionsNotWebsiteFallback() {
        val candidates=listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        val main=candidates.first(File::isFile).readText()
        assertTrue(main.contains("AdminMailboxActivationDialog()"))
        assertTrue(main.contains("onActivate=::requestAdminMailboxActivation"))
        assertTrue(main.contains("AdminMailboxActivationPolicy.forMailbox"))
        assertTrue(main.contains("nativeAdminClient.post(intent.endpoint,body,epoch)"))
        assertTrue(main.contains("adminActivationGeneration.incrementAndGet()"))
        assertTrue(main.contains("旧邮件不会恢复"))
    }
}
