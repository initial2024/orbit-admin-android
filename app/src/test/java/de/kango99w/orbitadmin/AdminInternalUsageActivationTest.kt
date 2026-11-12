package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdminInternalUsageActivationTest {
    private fun read(file:String):String=listOf(
        File("src/main/java/de/kango99w/orbitadmin/$file"),
        File("app/src/main/java/de/kango99w/orbitadmin/$file")
    ).first(File::isFile).readText()
    @Test fun platformUsageCannotInventActivationIdentifiers() {
        val screens=read("AdminManagementScreens.kt")
        assertTrue(screens.contains("item.source==\"ordinary\""))
        assertTrue(screens.contains("it.ordinaryRegistrationId!=null"))
        assertTrue(screens.contains("AdminMailboxActivationPolicy.forRecord("))
        assertFalse(screens.contains("AdminMailboxActivationIntent(item.address,item.id"))
    }
    @Test fun everyUsageTabNeedsFreshAuthorizedAddressAndDomain() {
        val main=read("MainActivity.kt")
        assertTrue(main.contains("recordTab in setOf(\"external\",\"internal\")"))
        assertTrue(main.contains("secondary?.status==200 && tertiary?.status==200"))
        assertTrue(main.contains("recorded=if(adminActivationEvidenceReady)"))
        assertTrue(main.contains("onActivate=::requestAdminMailboxActivation"))
    }
}
