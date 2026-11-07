package de.kango99w.orbitadmin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Foreground FCM delivery. Background notification messages are displayed by
 * Firebase SDK/Android without exposing the original message to the app.
 */
class OrbitFcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Never log/store an FCM token here. It is re-read on next opt-in unlock
        // and registered through the existing HttpOnly-cookie website session.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!OrbitPushConfig.configured) return
        val consent = getSharedPreferences("orbit_push",MODE_PRIVATE)
        if (!BackgroundPushConsent.mayDeliver(
                consent.getBoolean("opt_in",false),
                consent.getBoolean("revoke_pending",false))) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("orbit_mail_notice","Orbit 邮件提醒",NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open=Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice=Notification.Builder(this,"orbit_mail_notice")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Orbit Mail · 收到新邮件")
            .setContentText("解锁应用查看邮件。")
            .setContentIntent(intent)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        manager.notify(1010,notice)
    }
}
