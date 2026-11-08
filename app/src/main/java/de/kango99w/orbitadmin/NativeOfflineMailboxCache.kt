package de.kango99w.orbitadmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Offline-only, encrypted native mailbox *metadata* snapshot.
 *
 * AES-GCM key remains non-exportable in AndroidKeyStore. The device binding
 * ID is authenticated as additional data (AAD). No message bodies, OTP fields,
 * safe_links, mail access tokens, or admin session cookies are serialized.
 * UI biometric/device lock remains mandatory independently of this cache.
 *
 * Offline is never an authorization success: readers must render in read-only,
 * unverified mode and force server revalidation before any sensitive mutation.
 */
class NativeOfflineMailboxCache(private val context: Context) {
    private val prefs = context.getSharedPreferences("orbit_native_mailbox_cache_v1",Context.MODE_PRIVATE)
    companion object {
        private const val ALIAS="orbit_native_metadata_aes_gcm_v1"
        private const val MAX_CACHE_BYTES=32_768
        private const val MAX_OFFLINE_AGE_MS=24L*60L*60L*1000L
        private const val CACHE_VERSION=1
        fun eligibleAge(ageMs:Long):Boolean = ageMs in 0..MAX_OFFLINE_AGE_MS
    }
    private fun secret():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val present=ks.getKey(ALIAS,null) as? SecretKey
        if(present!=null)return present
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
    @Synchronized fun store(bindingId:String, data:NativeInboxSnapshot, now:Long=System.currentTimeMillis()):Boolean {
        if(!NativeInboxPolicy.isSafeId(bindingId) || !NativeInboxPolicy.isSafeId(data.currentId) ||
            now < 946684800000L || data.locked) return false
        val obj=JSONObject().put("version",CACHE_VERSION).put("binding",bindingId)
            .put("captured_at",now).put("current_id",data.currentId)
            .put("address",data.currentAddress.take(254))
        val box=JSONArray()
        data.mailboxes.take(25).forEach {
            if(NativeInboxPolicy.isSafeId(it.id))
                box.put(JSONObject().put("id",it.id).put("address",it.address.take(254))
                    .put("protected",it.isProtected))
        }
        obj.put("mailboxes",box)
        val mail=JSONArray()
        data.messages.take(30).forEach {
            if(NativeInboxPolicy.isSafeId(it.id))
                mail.put(JSONObject().put("id",it.id).put("from",it.sender.take(160))
                    .put("subject",it.subject.take(160)).put("time",it.receivedAt.take(40)))
        }
        obj.put("messages",mail)
        val payload=obj.toString().toByteArray(Charsets.UTF_8)
        if(payload.size>MAX_CACHE_BYTES)return false
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,secret())
            cipher.updateAAD(bindingId.toByteArray(Charsets.UTF_8))
            val ciphertext=cipher.doFinal(payload)
            val output=JSONObject()
                .put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
                .put("data",Base64.encodeToString(ciphertext,Base64.NO_WRAP))
                .toString()
            if(output.length>MAX_CACHE_BYTES*2)return false
            prefs.edit().putString("snapshot",output).commit()
        } catch (_:Exception) { false }
    }

    @Synchronized fun restore(bindingId:String, now:Long=System.currentTimeMillis()):NativeInboxSnapshot? {
        if(!NativeInboxPolicy.isSafeId(bindingId))return null
        val raw=prefs.getString("snapshot",null) ?: return null
        if(raw.length>MAX_CACHE_BYTES*2)return null
        return try {
            val envelope=JSONObject(raw)
            val iv=Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)
            val ciphertext=Base64.decode(envelope.getString("data"),Base64.NO_WRAP)
            if(iv.size!=12 || ciphertext.size>MAX_CACHE_BYTES+32)return null
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,iv))
            cipher.updateAAD(bindingId.toByteArray(Charsets.UTF_8))
            val bytes=cipher.doFinal(ciphertext)
            if(bytes.size>MAX_CACHE_BYTES)return null
            val body=JSONObject(String(bytes,Charsets.UTF_8))
            if(body.optInt("version")!=CACHE_VERSION || body.optString("binding")!=bindingId ||
                !eligibleAge(now-body.optLong("captured_at",-1L)))return null
            val id=body.optString("current_id")
            if(!NativeInboxPolicy.isSafeId(id))return null
            val box=body.optJSONArray("mailboxes") ?: return null
            val mail=body.optJSONArray("messages") ?: return null
            if(box.length()>25 || mail.length()>30)return null
            val boxes=(0 until box.length()).mapNotNull { i ->
                val item=box.optJSONObject(i) ?: return@mapNotNull null
                val rowId=item.optString("id")
                if(!NativeInboxPolicy.isSafeId(rowId))return@mapNotNull null
                NativeMailboxRow(rowId,item.optString("address").take(254),
                    item.optBoolean("protected"))
            }
            val selected=boxes.firstOrNull { it.id==id }
            if(selected==null || selected.isProtected)return null
            val messages=(0 until mail.length()).mapNotNull { i ->
                val row=mail.optJSONObject(i) ?: return@mapNotNull null
                val rowId=row.optString("id")
                if(!NativeInboxPolicy.isSafeId(rowId))return@mapNotNull null
                NativeMailRow(rowId,row.optString("from").take(160),
                    row.optString("subject").take(160),row.optString("time").take(40))
            }
            NativeInboxSnapshot(
                currentId=id,currentAddress=selected.address,locked=false,
                selectedDomain="",availableDomains=emptyList(),
                canCreate=false,nativeReadSupported=false,busy=false,
                statusText="离线缓存 · 仅历史邮件列表，未确认最新授权或新邮件",
                mailboxes=boxes,messages=messages)
        } catch (_:Exception) { null }
    }
    @Synchronized fun clear():Boolean = prefs.edit().remove("snapshot").commit()
}
