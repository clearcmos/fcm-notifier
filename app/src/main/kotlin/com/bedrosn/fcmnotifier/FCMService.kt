package com.bedrosn.fcmnotifier

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 FCMService created")

        // Create notification channel when service starts
        NotificationChannelManager.createNotificationChannel(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔑 New FCM token received: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d(TAG, "📬 ========== MESSAGE RECEIVED ==========")
        Log.d(TAG, "📬 From: ${message.from}")
        Log.d(TAG, "📬 Message ID: ${message.messageId}")
        Log.d(TAG, "📬 Sent time: ${message.sentTime}")
        Log.d(TAG, "📬 Data payload: ${message.data}")
        Log.d(TAG, "📬 Notification payload: ${message.notification}")

        // Extract notification data
        val title = message.notification?.title ?: message.data["title"] ?: "New Notification"
        val body = message.notification?.body ?: message.data["body"] ?: "You have a new message"

        Log.d(TAG, "📬 Title: $title")
        Log.d(TAG, "📬 Body: $body")

        // Add to ViewModel log (will be persisted by ViewModel)
        try {
            NotificationViewModel.getInstance(applicationContext).addNotification(title, body)
            Log.d(TAG, "✅ Added notification to ViewModel history")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error adding to ViewModel: ${e.message}", e)
        }

        // Wake up screen
        Log.d(TAG, "⚡ Attempting to wake up screen...")
        wakeUpScreen()

        // Show notification
        Log.d(TAG, "🔔 Attempting to show notification...")
        showNotification(title, body)

        Log.d(TAG, "📬 ========== MESSAGE PROCESSING COMPLETE ==========")
    }

    private fun wakeUpScreen() {
        try {
            Log.d(TAG, "⚡ Getting PowerManager...")
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            Log.d(TAG, "⚡ Creating wake lock...")
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FCMNotifier::NotificationWakeLock"
            )

            Log.d(TAG, "⚡ Acquiring wake lock for 5 seconds...")
            wakeLock.acquire(5000) // 5 seconds

            Log.d(TAG, "⚡ Wake lock acquired, screen should turn on now!")
            Log.d(TAG, "⚡ Is screen on? ${powerManager.isInteractive}")

            // Release immediately since we used timeout
            wakeLock.release()
            Log.d(TAG, "⚡ Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error waking up screen: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun showNotification(title: String, body: String) {
        try {
            Log.d(TAG, "🔔 Getting NotificationManager...")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Verify notification channel exists
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel(NotificationChannelManager.CHANNEL_ID)
                if (channel == null) {
                    Log.e(TAG, "❌ Notification channel does not exist! Creating now...")
                    NotificationChannelManager.createNotificationChannel(this)
                } else {
                    Log.d(TAG, "✅ Notification channel exists with importance: ${channel.importance}")
                }
            }

            // Create intent to open app when notification is clicked (regular tap)
            Log.d(TAG, "🔔 Creating pending intent...")
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Create FULL-SCREEN intent (like Outlook calendar - takes over screen!)
            Log.d(TAG, "🔔 Creating FULL-SCREEN intent...")
            val fullScreenIntent = Intent(this, FullScreenNotificationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("title", title)
                putExtra("body", body)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                1,
                fullScreenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Get default notification sound
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            Log.d(TAG, "🔔 Sound URI: $soundUri")

            // Build notification with FULL-SCREEN intent (using CALL category for Android 15)
            Log.d(TAG, "🔔 Building FULL-SCREEN notification...")
            val notification = NotificationCompat.Builder(this, NotificationChannelManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)  // MAX priority for full-screen
                .setCategory(NotificationCompat.CATEGORY_CALL)  // CALL category - allowed for full-screen in Android 15!
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)  // Regular tap opens main app
                .setFullScreenIntent(fullScreenPendingIntent, true)  // FULL-SCREEN takeover!
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 500, 250, 500))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setTimeoutAfter(30000)  // Auto-dismiss after 30 seconds
                .setOngoing(true)  // Makes it persistent like a call
                .build()

            // Show notification with unique ID based on timestamp
            val notificationId = System.currentTimeMillis().toInt()
            Log.d(TAG, "🔔 Showing notification with ID: $notificationId")
            notificationManager.notify(notificationId, notification)

            Log.d(TAG, "✅ Notification shown successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing notification: ${e.message}", e)
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "FCMService"
    }
}
