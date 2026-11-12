package de.kango99w.orbitadmin

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JCA ECDSA round-trips, not just source-string assertions. */
class P256SignatureFormatTest {
    @Test fun convertsHundredsOfRandomDerSignaturesToValidP1363() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        repeat(128) { index ->
            val payload = "orbit-native-device-v1|$index".toByteArray(Charsets.UTF_8)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(payload)
            val der = signer.sign()
            val p1363 = P256SignatureFormat.derToP1363(der)
            assertEquals(64, p1363.size)
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(pair.public)
            verifier.update(payload)
            assertTrue("P1363 verification should succeed", verifier.verify(p1363))
        }
    }

    @Test fun invalidAndNoncanonicalDerIsRejected() {
        val invalid = listOf(
            byteArrayOf(),
            byteArrayOf(0x30, 0x00),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0xff.toByte(), 0x02, 0x01, 0x01),
            byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x01),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0xff.toByte())
        )
        for (encoded in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                P256SignatureFormat.derToP1363(encoded)
            }
        }
    }
}
