package de.kango99w.orbitadmin

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** No Firebase bootstrap when the repo has not been configured for a real project. */
object OrbitPushConfig {
    val configured: Boolean
        get() = listOf(
            BuildConfig.ORBIT_FIREBASE_APP_ID,
            BuildConfig.ORBIT_FIREBASE_API_KEY,
            BuildConfig.ORBIT_FIREBASE_SENDER_ID,
            BuildConfig.ORBIT_FIREBASE_PROJECT_ID
        ).all { it.isNotBlank() }
}

class OrbitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OrbitPushConfig.configured) return
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.ORBIT_FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.ORBIT_FIREBASE_API_KEY)
                        .setGcmSenderId(BuildConfig.ORBIT_FIREBASE_SENDER_ID)
                        .setProjectId(BuildConfig.ORBIT_FIREBASE_PROJECT_ID)
                        .build()
                )
            }
        } catch (_: Exception) {
            // Fail closed: do not register tokens, crash startup or log credentials.
        }
    }
}
