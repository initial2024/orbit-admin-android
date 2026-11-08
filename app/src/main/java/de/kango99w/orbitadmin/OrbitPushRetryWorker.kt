package de.kango99w.orbitadmin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * Reconcile a rotated FCM registration without opening any mailbox WebView.
 * Only a previously opted-in, live native account grant may register. No FCM
 * token or account credentials are persisted in WorkManager input/output.
 */
internal object OrbitPushRetry {
    private const val NAME = "orbit_mail_native_fcm_rotation_v1"

    fun enqueue(context: Context) {
        if (!OrbitPushConfig.configured) return
        val prefs = context.getSharedPreferences("orbit_push", Context.MODE_PRIVATE)
        if (!BackgroundPushConsent.mayDeliver(
                prefs.getBoolean("opt_in", false),
                prefs.getBoolean("revoke_pending", false))) return
        val request = OneTimeWorkRequest.Builder(OrbitPushRetryWorker::class.java)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
    }
}

internal class OrbitPushRetryWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    private fun permitted(): Boolean {
        val prefs = applicationContext.getSharedPreferences("orbit_push", Context.MODE_PRIVATE)
        if (!BackgroundPushConsent.mayDeliver(
                prefs.getBoolean("opt_in", false),
                prefs.getBoolean("revoke_pending", false))) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    override fun doWork(): Result {
        if (!OrbitPushConfig.configured || !permitted()) return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        val prefs = applicationContext.getSharedPreferences("orbit_push", Context.MODE_PRIVATE)
        // A missing installation ID means the app has not finished explicit opt-in.
        val installation = prefs.getString("installation_id", null) ?: return Result.success()
        if (!Regex("^[A-Za-z0-9_-]{16,72}$").matches(installation)) return Result.success()
        val client = NativeDeviceClient(applicationContext)
        if (!client.isBound()) return Result.success()

        val token = try {
            Tasks.await(FirebaseMessaging.getInstance().token, 12L, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        if (token.isNullOrBlank() || !permitted() || !client.isBound()) return Result.success()

        val result = try { client.registerPush(installation, token) }
                     catch (_: Exception) { return if (runAttemptCount < 3) Result.retry() else Result.success() }
        // 401/403/409 and invalid input are terminal: no account switch, hidden
        // web login, or server-side authorization bypass is attempted.
        return if (result.status in 500..599 && runAttemptCount < 3) Result.retry()
               else Result.success()
    }
}
