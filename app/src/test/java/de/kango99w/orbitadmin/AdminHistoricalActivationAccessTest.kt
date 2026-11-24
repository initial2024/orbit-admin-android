package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdminHistoricalActivationAccessTest {
    @Test fun historicalRecoveryRemainsAvailableInSeparateActivationPane() {
        val file=listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile)
        val source=file.readText()
        assertTrue(source.contains("AdminMailboxActivationPolicy.forMailbox(box,permitted)"))
        assertTrue(source.contains("historical.take(100).forEach"))
        assertTrue(source.contains("requestAdminMailboxActivation(intent)"))
        assertTrue(source.contains("onCopy=::copyMailboxAddress,activationOnly=true"))
    }
}
