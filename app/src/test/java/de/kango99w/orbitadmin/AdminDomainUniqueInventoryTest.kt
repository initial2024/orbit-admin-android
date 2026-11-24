package de.kango99w.orbitadmin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression: one hostname may occur in five admin/domain registries. */
class AdminDomainUniqueInventoryTest {
    @Test fun threeRegistriesAndInventoryNeverInflateCounts() {
        val privateStatus = JSONObject(
            """{"domains":[{"domain":"YUES.BLOGU.TC","enabled":true},
            {"domain":"yues.blogu.tc","enabled":false}],
            "site_domains":[{"domain":"yues.blogu.tc","site_connected":true},
            {"domain":"yues.moe.page","site_connected":true}],
            "domain_requests":[{"domain":"YUES.BLOGU.TC","state":"pending"}]}"""
        )
        val ordinary = JSONObject(
            """{"domains":[{"domain":"yues.blogu.tc","enabled":true},
            {"domain":"note.kango99w.de","enabled":true}]}"""
        )
        val known=AdminManagementModels.domains(privateStatus,ordinary)
        assertEquals(3,known.size)
        val primary=known.single { it.domain=="yues.blogu.tc" }
        assertEquals(listOf("管理员邮箱","网站域名","域名申请","临时邮箱"),primary.sources)
        assertEquals("active",primary.state) // primary source only; not a DNS proof

        val inventory=AdminDomainInventoryModels.parse(JSONObject(
            """{"domains":[{"domain":"yues.blogu.tc","evidence_source":"cloudflare_pages_api",
            "evidence_status":"pages_pending","pages_status":"pending"},
            {"domain":"yues.moe.page","evidence_source":"cloudflare_pages_api",
            "evidence_status":"pages_active","pages_status":"active"},
            {"domain":"new.example.org","evidence_source":"manual",
            "evidence_status":"unverified"}]}"""
        ))
        val combined=AdminDomainInventoryModels.combine(known,inventory)
        assertEquals(4,combined.size)
        assertEquals(4,combined.map { it.domain }.distinct().size)
        val merged=combined.single{it.domain=="yues.blogu.tc"}
        assertTrue("托管库存" in merged.sources)
        assertTrue("管理员邮箱" in merged.sources)
        assertEquals("pages_pending",inventory.single{it.domain=="yues.blogu.tc"}.evidenceStatus)
        assertEquals("unverified",inventory.single{it.domain=="new.example.org"}.evidenceStatus)
    }

    @Test fun sixtySixSourceRowsCannotBecomeSixtySixClaimedDomains() {
        val rows=(0 until 66).map { i ->
            AdminManagedDomain(
                domain="d${i % 24}.example.org",
                source=listOf("管理员邮箱","网站域名","临时邮箱","域名申请","托管库存")[i % 5],
                state=if(i % 2==0)"active" else "pending",
                ingress=""
            )
        }
        val unique=AdminManagementModels.uniqueDomains(rows)
        assertEquals(24,unique.size)
        assertTrue(unique.all { it.sources.isNotEmpty() })
        assertTrue(unique.any { it.sources.size>1 })
    }

    @Test fun nativeFilteringRetainsSourcesAndDisclaimsOwnership() {
        val file=listOf(
            File("src/main/java/de/kango99w/orbitadmin/AdminManagementScreens.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/AdminManagementScreens.kt")
        ).first(File::isFile).readText()
        assertTrue(file.contains("source in item.sources"))
        assertTrue(file.contains("不重复域名"))
        assertTrue(file.contains("未核实记录不代表拥有"))
    }
}
