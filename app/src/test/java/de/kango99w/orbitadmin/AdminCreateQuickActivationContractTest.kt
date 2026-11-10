package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdminCreateQuickActivationContractTest {
    private fun src():String=sequenceOf(
        File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
    ).first(File::isFile).readText()

    @Test fun createModalUsesServerOwnedExactAddressAndConfirmedActivation() {
        val src=src()
        assertTrue(src.contains("openNativeAdminCreateDialog()"))
        assertTrue(src.contains("adminCreateExactAddress()"))
        assertTrue(src.contains("AdminMailboxActivationPolicy.forRecord("))
        assertTrue(src.contains("AdminMailboxActivationPolicy.forMailbox("))
        assertTrue(src.contains("requestAdminMailboxActivation(intent)"))
        assertTrue(src.contains("adminCreateActivation()!=intent"))
        assertTrue(src.contains("showAdminCreateDialog=false"))
        assertTrue(src.contains("Text(\"一键激活原邮箱\")"))
        assertTrue(src.contains("Text(\"打开已有邮箱\")"))
    }

    @Test fun customAddressCreationIsBlockedUntilLookupIsVerified() {
        val src=src()
        assertTrue(src.contains("if(!adminCreatePrivate && adminCreateLocal.isNotBlank())"))
        assertTrue(src.contains("if(!adminCreateLookupReady)"))
        assertTrue(src.contains("adminCreateLookupGeneration.incrementAndGet()"))
        assertTrue(src.contains("adminCreateLookupAllowed=allowed"))
    }
}
