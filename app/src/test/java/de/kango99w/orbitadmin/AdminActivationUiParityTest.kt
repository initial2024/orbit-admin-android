package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdminActivationUiParityTest {
    private fun text(name:String)=listOf(
        File("src/main/java/de/kango99w/orbitadmin/$name"),
        File("app/src/main/java/de/kango99w/orbitadmin/$name")
    ).first(File::isFile).readText()

    @Test fun activationHasSeparatePaneAndNoInlineMailboxRecoveryButton() {
        val main=text("MainActivity.kt")
        assertTrue(main.contains("openAdminNativePane(\"activation\")"))
        assertTrue(main.contains("\"activation\" -> AdminAddressRecords("))
        assertTrue(main.contains("activationOnly=true"))
        assertTrue(main.contains("adminActivationEvidenceReady"))
        assertFalse(main.contains("onClick={requestAdminMailboxActivation(activation)}"))
    }

    @Test fun externalLedgerActivationNeedsMatchingRecordIdAndAddress() {
        val ledger=text("AdminLedgerScreens.kt")
        assertTrue(ledger.contains("it.externalId==mail.id"))
        assertTrue(ledger.contains("it.address.equals(mail.address,ignoreCase=true)"))
        assertTrue(ledger.contains("AdminMailboxActivationPolicy.forRecord"))
        assertTrue(ledger.contains("onCopy(mail.address)"))
        val main=text("MainActivity.kt")
        assertTrue(main.contains("secondary?.status==200 && tertiary?.status==200"))
        assertTrue(main.contains("recorded=if(adminActivationEvidenceReady)"))
    }

    @Test fun compactVariantRetainsOriginalVisibleAppName() {
        val name=listOf(File("src/compactPreview/res/values/strings.xml"),
            File("app/src/compactPreview/res/values/strings.xml")).first(File::isFile).readText()
        assertTrue(name.contains(">Orbit Mail<"))
        assertFalse(name.contains("Compact Test"))
    }
}
