package de.kango99w.orbitadmin

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * Revocable account-only mail read, separate from website cookies and admin sessions.
 * The backend feature is disabled by default; never silently substitute WebView cookies.
 */
class NativeDeviceClient(context: Context) {
    private val prefs = context.getSharedPreferences("orbit_native_device_v1", Context.MODE_PRIVATE)
    companion object {
        private const val ALIAS = "orbit_mail_device_p256_v1"
        private const val ORIGIN = "https://inbox.kango99w.de"
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val DEVICE_ID = Regex("^[a-fA-F0-9-]{36}$")
        private val ENDPOINT = Regex(
            "^/api/account/native-device/(?:mailboxes(?:/[A-Za-z0-9_-]{1,128}/messages(?:/[A-Za-z0-9_-]{1,128})?)?|glyphs/(?:status|collections))$"
        )
    }

    data class Response(val status: Int, val value: JSONObject?, val error: String? = null)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): KeyPair? {
        val key = keyStore()
        val privateKey = key.getKey(ALIAS, null) as? java.security.PrivateKey ?: return null
        val publicKey = key.getCertificate(ALIAS)?.publicKey ?: return null
        return KeyPair(publicKey, privateKey)
    }

    private fun getOrCreateKey(): KeyPair {
        existingKey()?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun base64(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun nonce(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.let(::base64)

    private fun sign(payload: String): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(getOrCreateKey().private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return base64(P256SignatureFormat.derToP1363(signer.sign()))
    }

    /**
     * Passed to the *existing first-party website only* after a user action.
     * The website issues the session-authenticated pairing API request.
     * Password/TOTP fields are never stored by the APK.
     */
    fun createPairPayload(label: String, password: String, totp: String): JSONObject {
        require(label.length in 1..60 && !label.contains('\n') && !label.contains('\r'))
        val n = nonce()
        val stamp = System.currentTimeMillis()
        val body = "orbit-native-pair-v1\n$n\n$stamp\n$label"
        return JSONObject()
            .put("v", 1)
            .put("action", "native_device_pair")
            .put("public_key", base64(getOrCreateKey().public.encoded))
            .put("signature", sign(body))
            .put("nonce", n)
            .put("stamp", stamp)
            .put("label", label)
            .put("totp_code", totp)
            .put("current_password", password)
    }

    /** Build an exact JSON credential packet. Do not transmit WebView bridge-only keys. */
    fun createNativeLoginPayload(email: String, password: String, totp: String, label: String): JSONObject =
        createPairPayload(label, password, totp).apply {
            remove("v")
            remove("action")
            put("email", email.trim().lowercase(java.util.Locale.ROOT))
        }

    /**
     * True native first-party account sign-in. One password + (if enabled) one
     * TOTP produces a revocable Keystore-bound device grant. Never opens WebView,
     * stores a password, stores an account cookie, or receives admin privileges.
     */
    fun login(email: String, password: String, totp: String, label: String): Response {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native login on UI thread" }
        val trimmed = email.trim().lowercase(java.util.Locale.ROOT)
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(trimmed) ||
            trimmed.length > 320 || password.length !in 8..128 ||
            label.isBlank() || label.length > 60 ||
            label.contains('\n') || label.contains('\r')) {
            return Response(400, null, "NATIVE_LOGIN_INPUT_INVALID")
        }
        // AndroidKeyStore can fail before any HTTPS request (e.g. secure hardware
        // unavailable). Never leave the Compose login spinner stuck or misreport
        // a local signer failure as a server authorization failure.
        val body = try {
            createNativeLoginPayload(trimmed, password, totp, label)
                .toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            return Response(503, null, "NATIVE_KEYSTORE_UNAVAILABLE")
        }
        if (body.size > 7000) return Response(400, null, "NATIVE_LOGIN_INPUT_INVALID")
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL("$ORIGIN/api/account/native-device/login")
                .openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Origin", ORIGIN)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream
                         else connection.errorStream ?: return Response(code, null, "HTTP_$code")
            val value = stream.use { input ->
                val limit = 8192
                val buffer = ByteArray(limit + 1)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                if (count > limit) throw IOException("Native login response too large")
                JSONObject(String(buffer, 0, count, Charsets.UTF_8))
            }
            if (code == 201 && value.optString("scope") == "account_mail_read" &&
                completePair(value.optString("device_id"), value.optLong("expires_at"))) {
                return Response(201, value)
            }
            Response(code, value, value.optString("error", "NATIVE_LOGIN_FAILED"))
        } catch (_: Exception) {
            Response(503, null, "NATIVE_NETWORK_UNAVAILABLE")
        } finally {
            connection?.disconnect()
        }
    }

    fun completePair(deviceId: String, expiresAt: Long): Boolean {
        if (!DEVICE_ID.matches(deviceId) || expiresAt <= System.currentTimeMillis() ||
            existingKey() == null) return false
        return prefs.edit().clear().putString("device_id", deviceId)
            .putLong("expires_at", expiresAt).commit()
    }

    fun isBound(): Boolean = currentId() != null

    fun currentId(): String? {
        val id = prefs.getString("device_id", null) ?: return null
        val valid = DEVICE_ID.matches(id) && prefs.getLong("expires_at", 0L) > System.currentTimeMillis()
        if (!valid || existingKey() == null) {
            prefs.edit().clear().apply()
            return null
        }
        return id
    }

    fun localExpiry(): Long = prefs.getLong("expires_at", 0L)

    fun clearLocalBinding() {
        prefs.edit().clear().commit()
        keyStore().deleteEntry(ALIAS)
    }

    /** Execute off the Android main thread. No redirects and no Cookie header. */
    fun fetch(path: String): Response = perform(path, "GET")

    /** Signed server-side revocation, only an explicit user action should call this. */
    fun revokeSelf(): Response = perform("/api/account/native-device/revoke-self", "POST")

    /** Native push registration is signed over the exact JSON bytes; no site cookies. */
    fun registerPush(installationId: String, token: String): Response {
        if (!Regex("^[A-Za-z0-9_-]{16,72}$").matches(installationId) ||
            !Regex("^[A-Za-z0-9:_~.\-]{40,4096}$").matches(token))
            return Response(400, null, "PUSH_INPUT_INVALID")
        return signedPushMutation("register",
            JSONObject().put("installation_id", installationId).put("token", token))
    }

    fun revokePush(installationId: String): Response {
        if (!Regex("^[A-Za-z0-9_-]{16,72}$").matches(installationId))
            return Response(400, null, "PUSH_INPUT_INVALID")
        return signedPushMutation("revoke", JSONObject().put("installation_id", installationId))
    }

    private fun signedPushMutation(action: String, payload: JSONObject): Response {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native push HTTP on UI thread" }
        if (action !in setOf("register", "revoke")) return Response(400, null, "PUSH_ACTION_INVALID")
        val id = currentId() ?: return Response(401, null, "NATIVE_DEVICE_NOT_PAIRED")
        val path = "/api/account/native-device/push/$action"
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 4800) return Response(400, null, "PUSH_BODY_TOO_LARGE")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        val stamp = System.currentTimeMillis()
        val n = nonce()
        val canonical = listOf(
            "orbit-native-request-v1", "POST", ORIGIN, path, digest, stamp.toString(), n
        ).joinToString("\n")
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(ORIGIN + path).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Origin", ORIGIN)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Orbit-Device", id)
            connection.setRequestProperty("X-Orbit-Stamp", stamp.toString())
            connection.setRequestProperty("X-Orbit-Nonce", n)
            connection.setRequestProperty("X-Orbit-Signature", sign(canonical))
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream
                         else connection.errorStream
            val result = stream?.use { input ->
                val limit = 8192
                val buffer = ByteArray(limit + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > limit) throw IOException("Native push response too large")
                JSONObject(String(buffer, 0, count, Charsets.UTF_8))
            }
            Response(code, result, if (code in 200..299) null else result?.optString("error", "HTTP_$code"))
        } catch (_: Exception) {
            Response(503, null, "NATIVE_PUSH_NETWORK_UNAVAILABLE")
        } finally {
            connection?.disconnect()
        }
    }

    private fun perform(path: String, method: String): Response {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native mail HTTP on UI thread" }
        if (!((method == "GET" && ENDPOINT.matches(path)) ||
            (method == "POST" && path == "/api/account/native-device/revoke-self")))
            return Response(400, null, "NATIVE_PATH_INVALID")
        val id = currentId() ?: return Response(401, null, "NATIVE_DEVICE_NOT_PAIRED")
        val stamp = System.currentTimeMillis()
        val n = nonce()
        val canonical = listOf(
            "orbit-native-request-v1", method, ORIGIN, path, EMPTY_SHA256,
            stamp.toString(), n
        ).joinToString("\n")
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(ORIGIN + path).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Orbit-Device", id)
            connection.setRequestProperty("X-Orbit-Stamp", stamp.toString())
            connection.setRequestProperty("X-Orbit-Nonce", n)
            connection.setRequestProperty("X-Orbit-Signature", sign(canonical))
            val code = connection.responseCode
            if (code !in 200..299) return Response(code, null, "HTTP_$code")
            val stream = connection.inputStream
            val limit = 65536
            val bytes = stream.use { input ->
                val buf = ByteArray(limit + 1)
                var count = 0
                while (count < buf.size) {
                    val read = input.read(buf, count, buf.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > limit) throw IOException("Native response too large")
                buf.copyOf(count)
            }
            Response(code, JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (_: Exception) {
            Response(503, null, "NATIVE_NETWORK_UNAVAILABLE")
        } finally {
            connection?.disconnect()
        }
    }
}
