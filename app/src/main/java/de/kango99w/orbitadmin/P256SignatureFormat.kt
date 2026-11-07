package de.kango99w.orbitadmin

/**
 * Strict DER-encoded P-256 ECDSA to IEEE-P1363 raw (r || s) format.
 * Android Keystore exposes DER; Cloudflare WebCrypto verifies 64-byte P1363.
 */
object P256SignatureFormat {
    fun derToP1363(der: ByteArray): ByteArray {
        val raw = der.map { it.toInt() and 0xff }
        if (raw.size !in 8..74 || raw[0] != 0x30 || raw[1] != raw.size - 2) {
            throw IllegalArgumentException("Invalid DER sequence")
        }
        val result = ByteArray(64)
        var cursor = 2
        repeat(2) { component ->
            if (raw.getOrNull(cursor++) != 0x02) {
                throw IllegalArgumentException("Expected INTEGER")
            }
            val size = raw.getOrNull(cursor++) ?: throw IllegalArgumentException("Truncated")
            if (size !in 1..33 || cursor + size > raw.size) {
                throw IllegalArgumentException("Invalid INTEGER size")
            }
            val integer = der.copyOfRange(cursor, cursor + size)
            cursor += size
            if (size > 1 && integer[0].toInt() == 0 &&
                (integer[1].toInt() and 0x80) == 0) {
                throw IllegalArgumentException("Noncanonical INTEGER")
            }
            if (integer[0].toInt() and 0x80 != 0) {
                throw IllegalArgumentException("Negative INTEGER")
            }
            val value = if (size == 33) {
                if (integer[0].toInt() != 0) throw IllegalArgumentException("Missing leading zero")
                integer.copyOfRange(1, size)
            } else integer
            if (value.size > 32) throw IllegalArgumentException("P-256 integer overflow")
            System.arraycopy(
                value, 0, result, component * 32 + 32 - value.size, value.size
            )
        }
        if (cursor != der.size) throw IllegalArgumentException("Trailing DER data")
        return result
    }
}
