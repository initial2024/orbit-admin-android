package de.kango99w.orbitadmin

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Firebase initialization is lazy unless the user already opted into push. */
object OrbitPushConfig {
    val configured: Boolean
        get() = listOf(
            BuildConfig.ORBIT_FIREBASE_APP_ID,
            BuildConfig.ORBIT_FIREBASE_API_KEY,
            BuildConfig.ORBIT_FIREBASE_SENDER_ID,
            BuildConfig.ORBIT_FIREBASE_PROJECT_ID
        ).all { it.isNotBlank() }

    fun shouldInitialize(optIn: Boolean, revokePending: Boolean): Boolean =
        configured && BackgroundPushConsent.mayDeliver(optIn, revokePending)

    /** Never initialize Firebase for ordinary mailbox polling or admin browsing. */
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (!configured) return false
        return try {
            val appContext = context.applicationContext
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(
                    appContext,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.ORBIT_FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.ORBIT_FIREBASE_API_KEY)
                        .setGcmSenderId(BuildConfig.ORBIT_FIREBASE_SENDER_ID)
                        .setProjectId(BuildConfig.ORBIT_FIREBASE_PROJECT_ID)
                        .build()
                )
            }
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Exception) {
            // Fail closed. No token, credentials or SDK error is logged.
            false
        }
    }
}

class OrbitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Preserve delivery after process death only for explicit consent.
        // Disabled/pending-revoke users do not pay Firebase startup overhead.
        val prefs = getSharedPreferences("orbit_push", Context.MODE_PRIVATE)
        if (OrbitPushConfig.shouldInitialize(
                prefs.getBoolean("opt_in", false),
                prefs.getBoolean("revoke_pending", false))) {
            OrbitPushConfig.ensureInitialized(this)
        }
    }
}
