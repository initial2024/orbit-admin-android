package de.kango99w.orbitadmin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android framework + AndroidKeyStore. This does not use or modify any real account. */
@RunWith(AndroidJUnit4::class)
class NativeDeviceClientInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asn1Integer(integer: ByteArray): ByteArray {
        val unpadded = integer.dropWhile { it == 0.toByte() }
        val raw = if (unpadded.isEmpty()) byteArrayOf(0) else unpadded.toByteArray()
        val positive = if ((raw[0].toInt() and 0x80) != 0)
            byteArrayOf(0) + raw else raw
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }

    private fun asDer(signature: ByteArray): ByteArray {
        require(signature.size == 64)
        val a = asn1Integer(signature.copyOfRange(0,32))
        val b = asn1Integer(signature.copyOfRange(32,64))
        val body = a + b
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test fun realKeystoreKeyProducesVerifiableServerCompatibleP256Proof() {
        val native = NativeDeviceClient(context)
        native.clearLocalBinding()
        try {
            val packet = native.createPairPayload("Orbit Emulator","synthetic-test-only","123456")
            val decode = Base64.getUrlDecoder()
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(decode.decode(packet.getString("public_key")))
            )
            val stamp = packet.getLong("stamp")
            val payload = "orbit-native-pair-v1\n${packet.getString("nonce")}\n$stamp\nOrbit Emulator"
            val signature = decode.decode(packet.getString("signature"))
            assertEquals(64,signature.size)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            assertTrue(verifier.verify(asDer(signature)))
            assertFalse(native.isBound())
            assertEquals("native_device_pair",packet.getString("action"))
        } finally { native.clearLocalBinding() }
    }

    @Test fun nativeLoginPacketIsAnExactSignedCookieFreeCredentialExchange() {
        val client = NativeDeviceClient(context)
        client.clearLocalBinding()
        try {
            val packet = client.createNativeLoginPayload(
                "  User@Example.org  ", "synthetic-password", "123456", "Pixel"
            )
            assertEquals(
                setOf("email", "public_key", "signature", "nonce", "stamp", "label",
                    "totp_code", "current_password"),
                packet.keys().asSequence().toSet()
            )
            assertEquals("user@example.org", packet.getString("email"))
            assertFalse(packet.has("action"))
            assertFalse(packet.has("v"))
            assertFalse(packet.has("cookie"))
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getUrlDecoder().decode(packet.getString("public_key")))
            )
            val canonical = "orbit-native-pair-v1\\n${packet.getString("nonce")}\\n${packet.getLong("stamp")}\\nPixel"
                .replace("\\n", "\n")
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(canonical.toByteArray(Charsets.UTF_8))
            val signature = Base64.getUrlDecoder().decode(packet.getString("signature"))
            assertTrue(verifier.verify(asDer(signature)))
            assertFalse(client.isBound())
        } finally {
            client.clearLocalBinding()
        }
    }

    @Test fun deviceGrantSurvivesClientRecreationButNotLocalUnbind() {
        val client = NativeDeviceClient(context)
        client.clearLocalBinding()
        try {
            client.createPairPayload("Orbit Emulator","","")
            assertTrue(client.completePair("11111111-1111-4111-8111-111111111111",
                System.currentTimeMillis() + 600_000L))
            val restored = NativeDeviceClient(context)
            assertTrue(restored.isBound())
            assertEquals("11111111-1111-4111-8111-111111111111",restored.currentId())
            restored.clearLocalBinding()
            assertFalse(NativeDeviceClient(context).isBound())
        } finally { client.clearLocalBinding() }
    }

    @Test fun expiredDeviceGrantCannotRemainBound() {
        val client=NativeDeviceClient(context)
        client.clearLocalBinding()
        try {
            client.createPairPayload("Orbit Emulator","","")
            assertFalse(client.completePair("11111111-1111-4111-8111-111111111111",
                System.currentTimeMillis()-10_000L))
            assertFalse(client.isBound())
        } finally { client.clearLocalBinding() }
    }
}
