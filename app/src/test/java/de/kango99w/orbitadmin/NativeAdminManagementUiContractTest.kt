package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminManagementUiContractTest {
    private val root: File = sequenceOf(
        File("src/main/java/de/kango99w/orbitadmin"),
        File("app/src/main/java/de/kango99w/orbitadmin")
    ).first { it.isDirectory }

    @Test fun allManagementFiltersAreCollapsedAndSearchRemainsVisible() {
        val app=File(root,"MainActivity.kt").readText()
        val pages=File(root,"AdminManagementScreens.kt").readText()
        val folding=File(root,"AdminCompactFilters.kt").readText()
        assertTrue(app.contains("adminDomainFiltersExpanded by mutableStateOf(false)"))
        assertTrue(app.contains("adminRecordFiltersExpanded by mutableStateOf(false)"))
        assertTrue(app.contains("adminMailboxFiltersExpanded by mutableStateOf(false)"))
        assertTrue(app.contains("AdminDomainManagement("))
        assertTrue(app.contains("AdminAddressRecords("))
        assertTrue(app.contains("AdminAccountRegistrations("))
        assertTrue(pages.contains("AdminFilterFold("))
        assertTrue(pages.contains("OutlinedTextField(value=search"))
        assertTrue(folding.contains("if(expanded)"))
        assertTrue(pages.contains("verticalArrangement=Arrangement.spacedBy(8.dp)"))
    }

    @Test fun administratorUserDetailNeverInventsMembershipOrGrantsWriteRoute() {
        val app=File(root,"MainActivity.kt").readText()
        val client=File(root,"NativeAdminClient.kt").readText()
        val models=File(root,"AdminRegistrationModels.kt").readText()
        assertTrue(app.contains("private fun AdminRegistrationDetailDialog()"))
        assertTrue(app.contains("openAdminNativePane(\"users\")"))
        assertTrue(app.contains("onSelect={adminSelectedRegistration=it}"))
        assertTrue(app.contains("NativeAccountDetailDialog("))
        assertTrue(app.contains("showNativeAccountDetails=true"))
        assertTrue(app.contains("adminRegisteredAccounts = emptyList()"))
        assertTrue(app.contains("\"/api/admin/account-registry/registrations?limit=100\""))
        assertTrue(client.contains("method == \"GET\" && path == \"/api/admin/account-registry/registrations?limit=100\""))
        assertFalse(client.contains("method == \"POST\" && path == \"/api/admin/account-registry/registrations?limit=100\""))
        assertTrue(models.contains("NOT the whole"))
        assertTrue(models.contains("minOf(array.length(),150)"))
    }
}
