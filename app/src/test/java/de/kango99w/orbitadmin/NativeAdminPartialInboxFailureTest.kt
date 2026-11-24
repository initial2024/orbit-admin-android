package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminPartialInboxFailureTest {
    @Test fun oneFailedMailboxSourceCannotEraseVerifiedSource() {
        val src=sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        assertTrue(src.contains("val privateVerified = authorized"))
        assertTrue(src.contains("val ordinaryVerified = authorized"))
        assertTrue(src.contains("if(privateVerified) AdminNativeModels.mailboxes"))
        assertTrue(src.contains("if(ordinaryVerified) AdminNativeModels.mailboxes"))
        assertTrue(src.contains("只显示本次已核验的邮箱"))
        assertTrue(src.contains("val selectedScopeVerified"))
    }
}
