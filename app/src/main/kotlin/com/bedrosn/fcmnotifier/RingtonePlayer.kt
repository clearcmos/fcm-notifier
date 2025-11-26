package com.bedrosn.fcmnotifier

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * Singleton to manage ringtone playback for notifications.
 * Plays the Atomic Bell ringtone in a loop until explicitly stopped.
 */
object RingtonePlayer {
    private const val TAG = "RingtonePlayer"
    private const val ATOMIC_BELL_PATH = "/system/media/audio/ringtones/SoundTheme/Galaxy/ACH_Atomic_Bell.ogg"

    private var ringtone: Ringtone? = null

    /**
     * Start playing the Atomic Bell ringtone
     * @param shouldLoop If true, ringtone will loop continuously. If false, plays once.
     */
    fun startRingtone(context: Context, shouldLoop: Boolean = true) {
        try {
            // Stop any existing ringtone first
            stopRingtone()

            Log.d(TAG, "🔊 Starting Atomic Bell ringtone (looping: $shouldLoop)...")

            // Try to use the Atomic Bell ringtone from system
            val atomicBellUri = Uri.parse("file://$ATOMIC_BELL_PATH")

            ringtone = RingtoneManager.getRingtone(context, atomicBellUri)

            if (ringtone == null) {
                Log.w(TAG, "⚠️ Atomic Bell not found, using default ringtone")
                // Fallback to default ringtone if Atomic Bell not found
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(context, defaultUri)
            }

            ringtone?.let {
                it.isLooping = shouldLoop
                it.play()
                Log.d(TAG, "✅ Ringtone started (looping: $shouldLoop)")
            } ?: Log.e(TAG, "❌ Failed to create ringtone")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting ringtone: ${e.message}", e)
        }
    }

    /**
     * Stop the currently playing ringtone
     */
    fun stopRingtone() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    Log.d(TAG, "🔇 Stopping ringtone...")
                    it.stop()
                }
            }
            ringtone = null
            Log.d(TAG, "✅ Ringtone stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping ringtone: ${e.message}", e)
        }
    }

    /**
     * Check if ringtone is currently playing
     */
    fun isPlaying(): Boolean {
        return ringtone?.isPlaying ?: false
    }
}
