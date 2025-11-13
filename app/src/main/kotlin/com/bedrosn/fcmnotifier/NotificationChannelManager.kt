package com.bedrosn.fcmnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

object NotificationChannelManager {
    private const val TAG = "NotificationChannelMgr"
    const val CHANNEL_ID = "fcm_default_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Check if channel already exists
                val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                if (existingChannel != null) {
                    Log.d(TAG, "Notification channel already exists with importance: ${existingChannel.importance}")
                    return
                }

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "FCM Notifications",
                    NotificationManager.IMPORTANCE_HIGH  // HIGH for heads-up
                ).apply {
                    description = "Firebase Cloud Messaging notifications with banner and sound"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    setSound(soundUri, audioAttributes)
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setBypassDnd(false)  // Respect Do Not Disturb
                }

                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "✅ Notification channel created successfully with IMPORTANCE_HIGH")

                // Verify channel was created
                val createdChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                Log.d(TAG, "Verification - Channel importance: ${createdChannel?.importance}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creating notification channel: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Pre-Oreo device, notification channel not needed")
        }
    }
}
