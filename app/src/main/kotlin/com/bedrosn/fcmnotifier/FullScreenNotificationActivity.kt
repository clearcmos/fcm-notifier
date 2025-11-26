package com.bedrosn.fcmnotifier

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class FullScreenNotificationActivity : ComponentActivity() {

    private var currentTimerId: String? = null

    // BroadcastReceiver for remote timer dismissal (when dismissed from KDE)
    private val remoteDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dismissedTimerId = intent?.getStringExtra(TimerSyncService.EXTRA_TIMER_ID)
            Log.d(TAG, "Received remote dismiss broadcast for timer: $dismissedTimerId (current: $currentTimerId)")

            // Only dismiss if this is our timer
            if (dismissedTimerId != null && dismissedTimerId == currentTimerId) {
                Log.d(TAG, "Timer matches - finishing FullScreenNotificationActivity")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 12+ (API 31+): Use WindowManager flags for screen wake
        // This is the ONLY way that works on Android 15
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Dismiss keyguard (lock screen)
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Get notification data from intent
        val title = intent.getStringExtra("title") ?: "New Notification"
        val body = intent.getStringExtra("body") ?: "You have a new message"
        val notificationId = intent.getIntExtra("notificationId", -1)
        val timerId = intent.getStringExtra("timerId")

        // Store timerId for remote dismissal matching
        currentTimerId = timerId
        Log.d(TAG, "FullScreenNotificationActivity created for timer: $timerId")

        // Register receiver for remote timer dismissal (when dismissed from KDE)
        if (!timerId.isNullOrEmpty()) {
            val filter = IntentFilter(TimerSyncService.ACTION_TIMER_DISMISSED_REMOTELY)
            ContextCompat.registerReceiver(
                this,
                remoteDismissReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Registered remote dismiss receiver for timer: $timerId")
        }

        setContent {
            MaterialTheme {
                FullScreenNotificationContent(
                    title = title,
                    body = body,
                    onDismiss = {
                        // Stop the looping ringtone
                        RingtonePlayer.stopRingtone()

                        // Cancel the notification from the notification center
                        if (notificationId != -1) {
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(notificationId)
                        }

                        // Handle cross-device sync for timer notifications
                        if (!timerId.isNullOrEmpty()) {
                            // Unregister from local tracking
                            TimerSyncService.getInstance(applicationContext).unregisterTimer(timerId)
                            // Trigger cross-device sync via broadcast
                            val dismissIntent = android.content.Intent(NotificationDismissReceiver.ACTION_NOTIFICATION_DISMISSED).apply {
                                setPackage(packageName)
                                putExtra(NotificationDismissReceiver.EXTRA_TIMER_ID, timerId)
                            }
                            sendBroadcast(dismissIntent)
                        }

                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister broadcast receiver if it was registered
        if (!currentTimerId.isNullOrEmpty()) {
            try {
                unregisterReceiver(remoteDismissReceiver)
                Log.d(TAG, "Unregistered remote dismiss receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered: ${e.message}")
            }
        }
        // Ensure ringtone is stopped when activity is destroyed
        RingtonePlayer.stopRingtone()
    }

    companion object {
        private const val TAG = "FullScreenNotification"
    }
}

@Composable
fun FullScreenNotificationContent(
    title: String,
    body: String,
    onDismiss: () -> Unit
) {
    // Pulsing animation like Outlook
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Pulsing notification icon
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Title
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Body
            Text(
                text = body,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Dismiss button
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp)
            ) {
                Text(
                    text = "Dismiss",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
