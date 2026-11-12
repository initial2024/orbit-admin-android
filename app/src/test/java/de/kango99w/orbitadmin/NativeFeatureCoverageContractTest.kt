package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native means implemented Android actions, not a menu linking to a website. */
class NativeFeatureCoverageContractTest {
    private val source: String by lazy {
        listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("MainActivity source not found")
    }

    @Test fun adminManagementScreensAreComposeAndDoNotOpenWebsite() {
        val body = source.substringAfter("private fun NativeAdminManagementContent()")
            .substringBefore("@Composable\n    private fun NativeAdminContent()")
        assertTrue(body.contains("adminManagedDomains"))
        assertTrue(body.contains("adminRecordedAddresses"))
        assertTrue(body.contains("adminSecurityState"))
        assertFalse(body.contains("openAdvancedAdminWebsite"))
        assertFalse(body.contains("AndroidView("))
        assertFalse(body.contains("WebView("))
    }

    @Test fun mailboxOperationsRequireServerAndExplicitDestructiveConfirmation() {
        val update = source.substringAfter("private fun updateNativeAdminMailbox(")
            .substringBefore("private fun deleteNativeAdminMailbox()")
        assertTrue(update.contains("nativeAdminClient.patch("))
        assertTrue(update.contains("nativeAdminClient.post("))
        assertTrue(update.contains("reply.status == 200"))
        val delete = source.substringAfter("private fun deleteNativeAdminMailbox()")
            .substringBefore("@Composable\n    private fun NativeAdminManagementContent()")
        assertTrue(delete.contains("nativeAdminClient.delete("))
        assertTrue(delete.contains("optBoolean(\"deleted\") == true"))
        assertTrue(source.contains("private fun NativeAdminDeleteDialog()"))
        assertTrue(source.contains("确认永久删除"))
        assertFalse(delete.contains("navigateTo("))
    }

    @Test fun fileTabUsesNativePickerAndDoesNotMisrepresentUploadAsComplete() {
        val transfer = source.substringAfter("private fun NativeTransferContent()")
            .substringBefore("private fun refreshNativeAdmin()")
        assertTrue(transfer.contains("launchNativeDocumentPicker(false)"))
        assertTrue(source.contains("nativeFilePicker.launch(arrayOf(\"*/*\"))"))
        assertTrue(source.contains("pickerReturnAllowed()"))
        assertTrue(transfer.contains("未上传"))
        assertFalse(transfer.contains("private fun refreshNativeAdmin()"))
        assertFalse(transfer.contains("navigateTo("))
        assertFalse(transfer.contains("WebView("))
    }

    @Test fun administratorAndOrdinaryNativeApiCredentialsStayIndependent() {
        val client = listOf(
            File("src/main/java/de/kango99w/orbitadmin/NativeAdminClient.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/NativeAdminClient.kt")
        ).firstOrNull(File::isFile)?.readText() ?: error("NativeAdminClient missing")
        assertTrue(client.contains("ALLOWED_COOKIES"))
        assertTrue(client.contains("kango_admin_session"))
        assertFalse(client.contains("setRequestProperty(\"Authorization\""))
        assertFalse(client.contains("account_mail_read"))
    }
}
