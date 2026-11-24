package de.kango99w.orbitadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativePickerSecurityContractTest {
    private val source by lazy {
        listOf(File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
            .first(File::isFile).readText()
    }
    @Test fun nativeFileAndPrivateAttachmentUseBoundedSystemPickerReturn() {
        val launcher=source.substringAfter("private fun launchNativeDocumentPicker(")
            .substringBefore("private fun pickerReturnAllowed()")
        assertTrue(launcher.contains("pickerPending = true"))
        assertTrue(launcher.contains("pickerLaunchedAtMs = SystemClock.elapsedRealtime()"))
        assertTrue(launcher.contains("privateMailFilePicker.launch"))
        assertTrue(launcher.contains("nativeFilePicker.launch"))
        assertTrue(source.contains("onClick = { launchNativeDocumentPicker(false) }"))
        assertTrue(source.contains("onClick={launchNativeDocumentPicker(true)}"))
        assertFalse(source.contains("onClick = { nativeFilePicker.launch"))
    }
    @Test fun fileCallbackMustConsumePickerWindowAndRejectExpiredReturn() {
        val first=source.substringAfter("private val nativeFilePicker =")
            .substringBefore("private val privateMailFilePicker =")
        val second=source.substringAfter("private val privateMailFilePicker =")
            .substringBefore("private val pushPermission =")
        for(callback in listOf(first,second)) {
            assertTrue(callback.contains("pickerPending && pickerReturnAllowed()"))
            assertTrue(callback.contains("pickerPending = false"))
            assertTrue(callback.contains("pickerLaunchedAtMs = 0L"))
            assertTrue(callback.contains("if (!unlocked || !safeReturn)"))
            assertTrue(callback.contains("lock()"))
        }
    }
}
