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

    @Test fun nativeAccountLoginNeverUsesWebViewOrCookies() {
        val client = source("NativeDeviceClient.kt")
        val activity = source("MainActivity.kt")
        val login = client.substringAfter("fun login(email: String")
            .substringBefore("fun completePair(")
        assertTrue(login.contains("/api/account/native-device/login"))
        assertTrue(login.contains("connection.requestMethod = \"POST\""))
        assertTrue(login.contains("connection.setRequestProperty(\"Origin\", ORIGIN)"))
        assertTrue(login.contains("createNativeLoginPayload("))
        assertTrue(login.contains("completePair("))
        assertFalse(login.contains("CookieManager"))
        assertFalse(login.contains("loadUrl("))
        val render = activity.substringAfter("if (nativeVisible) {")
            .substringBefore("if (activeSection == MailSection.TRANSFER")
        assertTrue(render.contains("NativeLoginContent()"))
        assertTrue(render.contains("!nativeDeviceClient.isBound()"))
        val submit = activity.substringAfter("private fun submitNativeLogin()")
            .substringBefore("/** Signed, cookie-free native API reads.")
        assertTrue(submit.contains("nativeDeviceClient.login("))
        assertFalse(submit.contains("navigateTo("))
        assertFalse(submit.contains("publicWebView("))
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

    @Test fun signedSelfRevocationRequiresRealServerAcknowledgment() {
        val client = source("NativeDeviceClient.kt")
        val screen = source("MainActivity.kt")
        assertTrue(client.contains("fun revokeSelf(): Response"))
        assertTrue(client.contains("\"/api/account/native-device/revoke-self\", \"POST\""))
        assertTrue(client.contains("instanceFollowRedirects = false"))
        val body = screen.substringAfter("private fun revokeNativeDeviceOnServer() {")
            .substringBefore("/** Explicit user action only: website performs")
        assertTrue(body.contains("nativeDeviceClient.revokeSelf()"))
        assertTrue(body.contains("response.status == 200"))
        assertTrue(body.contains("optBoolean(\"revoked\") == true"))
        assertTrue(body.contains("if (revoked)"))
        assertTrue(body.contains("else \"服务器撤销失败"))
        assertTrue(body.contains("nativeRequestGeneration.incrementAndGet()"))
        assertFalse(body.contains("loadUrl("))
    }

    @Test fun boundDeviceDoesNotCreateAHiddenWebview() {
        val source = source("MainActivity.kt")
        assertTrue(source.contains("if ((publicSelected && !nativeVisible) ||"))
        assertTrue(source.contains("NativeInboxSnapshot("))
        assertTrue(source.contains("readMessageViaNativeApi("))
        assertTrue(source.contains("nativeIo.execute"))
    }
}
