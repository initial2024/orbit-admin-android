package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeMailResponseContractTest {
    @Test fun bothReadersRemainInteractiveDuringBackgroundSync() {
        val src=sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first { it.isFile }.readText()
        val handler=src.substringAfter("private fun openNativeAdminMessage(id: String)")
            .substringBefore("private fun createNativeAdminMailbox()")
        assertFalse(handler.contains("|| adminBusy ||"))
        assertTrue(handler.contains("adminReadIo.execute"))
        assertTrue(handler.contains("adminSelectedMessageId != id"))
        assertTrue(src.contains(".clickable(enabled=adminAuthenticated)"))
        assertTrue(src.contains("if (pendingNativeMessageId == id && nativeMailDetail == null) return"))
        assertTrue(src.contains("LinearProgressIndicator(modifier=Modifier.fillMaxWidth())"))
        assertTrue(src.contains("contentType={ \"mail\" }"))
    }
}
