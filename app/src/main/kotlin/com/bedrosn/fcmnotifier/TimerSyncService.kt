package com.bedrosn.fcmnotifier

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Service to sync timer notifications across devices.
 * Handles dismissal of notifications when triggered by FCM push from other devices.
 */
class TimerSyncService private constructor(private val context: Context) {

    // Thread-safe map: timer_id -> Android notification ID
    private val activeTimers = ConcurrentHashMap<String, Int>()

    /**
     * Register a timer notification mapping
     * Called when FCM notification is shown
     */
    fun registerTimer(timerId: String, notificationId: Int) {
        activeTimers[timerId] = notificationId
        Log.d(TAG, "Registered timer: $timerId -> notification $notificationId")
        Log.d(TAG, "Active timers: ${activeTimers.size}")
    }

    /**
     * Unregister a timer (when dismissed locally)
     */
    fun unregisterTimer(timerId: String) {
        val notificationId = activeTimers.remove(timerId)
        if (notificationId != null) {
            Log.d(TAG, "Unregistered timer: $timerId (was notification $notificationId)")
        }
    }

    /**
     * Dismiss notification locally when dismissed from another device (via FCM push)
     */
    fun dismissTimerNotification(timerId: String) {
        val notificationId = activeTimers.remove(timerId)

        if (notificationId == null) {
            Log.d(TAG, "Timer $timerId not found in active timers (already dismissed or not shown)")
            return
        }

        try {
            // Cancel the notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)

            // Stop the ringtone
            RingtonePlayer.stopRingtone()

            // Broadcast to dismiss FullScreenNotificationActivity if it's showing
            val dismissIntent = Intent(ACTION_TIMER_DISMISSED_REMOTELY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TIMER_ID, timerId)
            }
            context.sendBroadcast(dismissIntent)

            Log.d(TAG, "Auto-dismissed notification $notificationId for timer $timerId")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "TimerSyncService"

        // Broadcast action sent when a timer is auto-dismissed from another device
        const val ACTION_TIMER_DISMISSED_REMOTELY = "com.bedrosn.fcmnotifier.TIMER_DISMISSED_REMOTELY"
        const val EXTRA_TIMER_ID = "timer_id"

        @Volatile
        private var instance: TimerSyncService? = null

        /**
         * Get singleton instance
         */
        fun getInstance(context: Context): TimerSyncService {
            return instance ?: synchronized(this) {
                instance ?: TimerSyncService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
