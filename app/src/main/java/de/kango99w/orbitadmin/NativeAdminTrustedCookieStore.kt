package de.kango99w.orbitadmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Dedicated, native-only administrator trusted-device credential.
 * NEVER publish this token to WebView CookieManager, the ordinary-account
 * device client, or arbitrary URLs. Token is encrypted by Android Keystore.
 * This does not represent a server session or a passkey.
 */
class NativeAdminTrustedCookieStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "orbit_admin_native_trust_v1", Context.MODE_PRIVATE
    )

    companion object {
        const val COOKIE_NAME = "kango_admin_trusted_device"
        const val NATIVE_USER_AGENT = "OrbitMailAndroidNative/1"
        private const val KEY_ALIAS = "orbit_admin_native_trusted_cookie_aes_v1"
        private const val MAX_AGE_SEC = 30L * 24L * 60L * 60L
        private val VALUE = Regex("^[A-Za-z0-9_-]{43}$")

        /** Strictly first-party cookie syntax; no Domain or weaker attributes. */
        fun parseHeader(raw: String, nowMs: Long): Pair<String, Long>? {
            if (raw.length !in 55..1024 || raw.contains('\r') ||
                raw.contains('\n') || nowMs <= 0L) return null
            val parts = raw.split(';').map { it.trim() }
            val first = parts.firstOrNull()?.split('=', limit = 2) ?: return null
            if (first.size != 2 || first[0] != COOKIE_NAME ||
                !VALUE.matches(first[1])) return null
            val flags = parts.drop(1).map { it.lowercase() }
            if ("httponly" !in flags || "secure" !in flags ||
                "samesite=strict" !in flags || "path=/api/admin" !in flags ||
                flags.any { it.startsWith("domain=") }) return null
            val ageValues = flags.filter { it.startsWith("max-age=") }
            if (ageValues.size != 1) return null
            val seconds = ageValues.single().substringAfter('=').toLongOrNull()
                ?: return null
            if (seconds !in 1..MAX_AGE_SEC) return null
            return first[1] to (nowMs + seconds * 1000L)
        }

        fun isClearHeader(raw: String): Boolean {
            val parts = raw.split(';').map { it.trim().lowercase() }
            return parts.firstOrNull() == "${COOKIE_NAME}=" &&
                "max-age=0" in parts && "path=/api/admin" in parts &&
                "secure" in parts && "httponly" in parts
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val found = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (found != null) return found
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
    }

    @Synchronized fun receiveSetCookie(raw: String): Boolean {
        if (isClearHeader(raw)) {
            clear()
            return true
        }
        val parsed = parseHeader(raw, System.currentTimeMillis()) ?: return false
        return try {
            val data = JSONObject().put("token", parsed.first)
                .put("expires_at", parsed.second).toString()
                .toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(COOKIE_NAME.toByteArray(Charsets.UTF_8))
            val payload = cipher.doFinal(data)
            val encrypted = JSONObject()
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("ciphertext", Base64.encodeToString(payload, Base64.NO_WRAP))
            prefs.edit().putString("credential", encrypted.toString()).commit()
        } catch (_: Exception) { false }
    }

    @Synchronized fun cookieForLogin(): String? {
        val raw = prefs.getString("credential", null) ?: return null
        return try {
            val body = JSONObject(raw)
            val iv = Base64.decode(body.getString("iv"), Base64.NO_WRAP)
            val bytes = Base64.decode(body.getString("ciphertext"), Base64.NO_WRAP)
            if (iv.size != 12 || bytes.size !in 16..1024) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(COOKIE_NAME.toByteArray(Charsets.UTF_8))
            val decrypted = JSONObject(String(cipher.doFinal(bytes), Charsets.UTF_8))
            val token = decrypted.optString("token")
            val expiry = decrypted.optLong("expires_at")
            if (!VALUE.matches(token) || expiry <= System.currentTimeMillis()) {
                clear()
                null
            } else "${COOKIE_NAME}=$token"
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized fun clear() {
        prefs.edit().remove("credential").commit()
    }
}
