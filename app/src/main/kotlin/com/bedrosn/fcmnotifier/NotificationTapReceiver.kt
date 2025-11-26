package com.bedrosn.fcmnotifier

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Broadcast receiver that handles notification tap (content click)
 * Stops the ringtone and clears the notification without opening the app
 */
class NotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
        val timerId = intent?.getStringExtra(EXTRA_TIMER_ID)

        Log.d(TAG, "🔕 Notification tapped (ID: $notificationId, timerId: $timerId), stopping ringtone and clearing notification...")

        // Stop the ringtone
        RingtonePlayer.stopRingtone()

        // Cancel the notification
        if (notificationId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
        }

        // Cross-device sync for timer notifications
        if (!timerId.isNullOrEmpty()) {
            Log.d(TAG, "⏰ Timer notification tapped: $timerId")

            // Unregister from local tracking
            TimerSyncService.getInstance(context).unregisterTimer(timerId)

            // Notify server for cross-device sync
            dismissTimerOnServer(timerId)
        }
    }

    /**
     * Call dismiss_timer Cloud Function to update RTDB for cross-device sync
     */
    private fun dismissTimerOnServer(timerId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🌐 Calling dismiss_timer Cloud Function for $timerId...")

                val url = URL(DISMISS_TIMER_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                // Build JSON payload
                val payload = JSONObject().apply {
                    put("timer_id", timerId)
                    put("dismissed_by", "phone")
                }

                // Send request
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "✅ Dismiss timer response: $responseCode")

                if (responseCode == 200) {
                    Log.d(TAG, "✅ Timer dismissed on server - other devices will sync")
                } else {
                    Log.w(TAG, "⚠️ Unexpected response code: $responseCode")
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error calling dismiss_timer: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "NotificationTap"
        const val ACTION_NOTIFICATION_TAPPED = "com.bedrosn.fcmnotifier.NOTIFICATION_TAPPED"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TIMER_ID = "timer_id"

        // Cloud Function URL
        private const val DISMISS_TIMER_URL = "https://us-central1-notifications-35dd5.cloudfunctions.net/dismiss_timer"
    }
}
