package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compile + read-only source contract; device/Keystore runtime needs Android instrumentation. */
class NativeDeviceSecurityContractTest {
    private fun source(name: String): String {
        val files = listOf(
            File("src/main/java/de/kango99w/orbitadmin/$name"),
            File("app/src/main/java/de/kango99w/orbitadmin/$name")
        )
        return (files.firstOrNull { it.isFile } ?: error("$name missing")).readText()
    }

    @Test fun nativeRequestsUseP256AndCannotReadWebCookies() {
        val code = source("NativeDeviceClient.kt")
        assertTrue(code.contains("AndroidKeyStore"))
        assertTrue(code.contains("secp256r1"))
        assertTrue(code.contains("SHA256withECDSA"))
        assertTrue(code.contains("orbit-native-request-v1"))
        assertTrue(code.contains("X-Orbit-Signature"))
        assertTrue(code.contains("instanceFollowRedirects = false"))
        assertTrue(code.contains("Looper.myLooper() != Looper.getMainLooper()"))
        assertFalse(code.contains("CookieManager"))
        assertFalse(code.contains("getCookie("))
        assertFalse(code.contains("access_token"))
        assertFalse(code.contains("setRequestProperty(\"Authorization\""))
        assertFalse(code.contains("setRequestProperty(\"Cookie\""))
    }

    @Test fun pairingRequiresExactFirstPartyOriginAndOnePendingNonce() {
        val source = source("MainActivity.kt")
        val start = source.indexOf("\"orbitNativeDeviceBinding\"")
        assertTrue(start > 0)
        val listener = source.substring(start).substringBefore("// The only website")
        assertTrue(listener.contains("NativeInboxPolicy.acceptsSource"))
        assertTrue(listener.contains("pendingPairNonce"))
        assertTrue(listener.contains("publicWebView() !== view"))
        assertTrue(listener.contains("nativeDeviceClient.completePair"))
        assertTrue(listener.contains("refreshNativeApi()"))
        assertFalse(listener.contains("CookieManager"))
    }

    @Test fun websiteBridgeCannotOverwriteBoundNativeMailData() {
        val source = source("MainActivity.kt")
        assertTrue(source.contains("if (!unlocked || nativeDeviceClient.isBound()) return@runOnUiThread"))
        assertTrue(source.contains("if (!unlocked || nativeDeviceClient.isBound() ||"))
        assertTrue(source.contains("if ((publicSelected && !nativeVisible) ||"))
        assertFalse(source.contains("if (!nativeDeviceClient.isBound() || !nativeVisible ||"))
    }

    @Test fun accountMailCreationCannotPretendNativeSuccess() {
        val source = source("MainActivity.kt")
        val method = source.substringAfter("private fun requestMailboxCreation(")
            .substringBefore("/** Stay in Compose")
        assertTrue(method.contains("if (nativeApiActive || nativeDeviceClient.isBound())"))
        assertTrue(method.contains("navigateTo(\"https://inbox.kango99w.de/?orbit_app=android#orbit-create\")"))
        assertFalse(method.contains("sendNativeMailboxCommand(\"create_custom\""))
    }

    @Test fun ordinaryMailboxRowClickUsesNativeSelector() {
        val source = source("MainActivity.kt")
        val content = source.substringAfter("private fun NativeInboxContent(")
            .substringBefore("private fun NativeMailboxLanding(")
        assertTrue(content.contains(".clickable { openNativeMailbox(mailbox.id) }"))
        assertTrue(source.contains("private fun openNativeMailbox(id: String)"))
        assertTrue(source.contains("if (nativeApiActive) {"))
    }

    @Test fun boundDeviceDoesNotCreateAHiddenWebview() {
        val source = source("MainActivity.kt")
        assertTrue(source.contains("if ((publicSelected && !nativeVisible) ||"))
        assertTrue(source.contains("NativeInboxSnapshot("))
        assertTrue(source.contains("readMessageViaNativeApi("))
        assertTrue(source.contains("nativeIo.execute"))
    }
}
