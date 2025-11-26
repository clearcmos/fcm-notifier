package com.bedrosn.fcmnotifier

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver that handles notification tap (content click)
 * Stops the ringtone and clears the notification without opening the app
 */
class NotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1

        Log.d(TAG, "🔕 Notification tapped (ID: $notificationId), stopping ringtone and clearing notification...")

        // Stop the ringtone
        RingtonePlayer.stopRingtone()

        // Cancel the notification
        if (notificationId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
        }
    }

    companion object {
        private const val TAG = "NotificationTap"
        const val ACTION_NOTIFICATION_TAPPED = "com.bedrosn.fcmnotifier.NOTIFICATION_TAPPED"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
