package com.bedrosn.fcmnotifier

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
 * Broadcast receiver that handles notification dismissal (swipe away)
 * Stops the ringtone and syncs dismissal to other devices via RTDB
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "🔕 Notification dismissed, stopping ringtone...")
        RingtonePlayer.stopRingtone()

        // Extract timer_id if this is a timer notification
        val timerId = intent?.getStringExtra(EXTRA_TIMER_ID)

        if (!timerId.isNullOrEmpty() && context != null) {
            Log.d(TAG, "⏰ Timer notification dismissed: $timerId")

            // Unregister from local tracking
            TimerSyncService.getInstance(context).unregisterTimer(timerId)

            // Notify server for cross-device sync
            dismissTimerOnServer(timerId)
        } else {
            Log.d(TAG, "📱 Non-timer notification dismissed (no timer_id)")
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
        private const val TAG = "NotificationDismiss"
        const val ACTION_NOTIFICATION_DISMISSED = "com.bedrosn.fcmnotifier.NOTIFICATION_DISMISSED"
        const val EXTRA_TIMER_ID = "timer_id"

        // Cloud Function URL
        private const val DISMISS_TIMER_URL = "https://us-central1-notifications-35dd5.cloudfunctions.net/dismiss_timer"
    }
}
