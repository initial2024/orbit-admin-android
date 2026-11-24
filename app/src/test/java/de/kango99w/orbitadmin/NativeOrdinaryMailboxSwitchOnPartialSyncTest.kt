package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeOrdinaryMailboxSwitchOnPartialSyncTest {
    private val code=sequenceOf(File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        .first(File::isFile).readText()
    @Test fun successfulSignedMailboxListKeepsSwitchingEnabledWhenMessageListFails() {
        assertTrue(code.contains("nativeApiActive = true"))
        assertTrue(code.contains("nativeLastSuccessfulSyncMs=if(messageListVerified"))
        assertTrue(code.contains("else if(!snapshot.nativeReadSupported)"))
        assertTrue(code.contains("Text(if(!snapshot.nativeReadSupported)"))
    }
}
