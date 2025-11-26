# CLAUDE.md

**Last Modified:** 2025-11-26

This file provides guidance to Claude Code when working with the FCM Notifier Android app.

## Project Overview

**FCM Notifier** is a native Android app that receives Firebase Cloud Messaging (FCM) push notifications and displays them as full-screen notifications with pulsing animations, similar to incoming call screens.

**Package:** `com.bedrosn.fcmnotifier`
**Language:** Kotlin
**Framework:** Jetpack Compose (Material Design 3)
**Build:** Gradle + NixOS flakes
**Target SDK:** 35 (Android 15)

## Critical Implementation Details

### 1. Data-Only FCM Messages (CRITICAL!)

**The most important rule:** FCM messages MUST be **data-only** (no `notification` payload).

**Why:** When FCM messages include a `notification` payload, Firebase automatically handles them when the app is in the background and **NEVER calls `onMessageReceived()`**. This breaks the custom full-screen notification functionality.

**Server-side (Python):**
```python
# CORRECT - Always calls onMessageReceived()
message = messaging.Message(
    data={'title': 'Test', 'body': 'Message'},
    android=messaging.AndroidConfig(priority='high'),
    token=device_token,
)

# WRONG - Won't call onMessageReceived() in background
message = messaging.Message(
    notification=messaging.Notification(title='Test', body='Message'),
    token=device_token,
)
```

**Test script location:** `/etc/nixos/scripts/fcm-test.py` (on misc host)

### 2. Cross-Device Timer Notification Sync

**Purpose:** Enable bidirectional notification dismissal sync between Android phone and KDE desktop for timer notifications.

**How it works:**
- Timer notifications include `timer_id` and `type: "timer_complete"` in FCM data payload
- Phone dismissal: `NotificationDismissReceiver` calls `dismiss_timer` Cloud Function -> updates RTDB -> KDE listener auto-dismisses
- Desktop dismissal: KDE calls `dismiss_timer` Cloud Function -> updates RTDB + sends FCM push with `type: "timer_dismissed"` -> phone auto-dismisses

**Key components:**
1. **FCMService.kt** - Handles FCM messages:
   - `type: "timer_complete"` -> shows timer notification, registers with TimerSyncService
   - `type: "timer_dismissed"` -> calls `TimerSyncService.dismissTimerNotification()` to auto-dismiss
2. **TimerSyncService.kt** - Singleton service that:
   - Maintains mapping of `timer_id` -> `notificationId`
   - `registerTimer()` - called when timer notification is shown
   - `unregisterTimer()` - called when timer is dismissed locally
   - `dismissTimerNotification()` - cancels notification, stops ringtone, broadcasts to close FullScreenActivity
3. **NotificationDismissReceiver.kt** - Calls `dismiss_timer` Cloud Function when phone dismisses
4. **FullScreenNotificationActivity.kt** - Listens for `ACTION_TIMER_DISMISSED_REMOTELY` broadcast to auto-close

**Cloud Function Endpoints:**
- `https://us-central1-notifications-35dd5.cloudfunctions.net/schedule_timer`
- `https://us-central1-notifications-35dd5.cloudfunctions.net/send_notification`
- `https://us-central1-notifications-35dd5.cloudfunctions.net/dismiss_timer` - Updates RTDB + sends FCM push to phone if `dismissed_by == "desktop"`

### 3. Android 15 Full-Screen Intent Requirements

Android 15 has strict restrictions on full-screen intents:

**Required Permission Grant (via ADB):**
```bash
adb shell appops set com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT allow
```

**Required Notification Category:**
```kotlin
.setCategory(NotificationCompat.CATEGORY_CALL)  // Must be CALL or ALARM
```

**Required WindowManager Flags (Android 12+):**
```kotlin
window.addFlags(
    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
)
```

### 3. FCM Token

The FCM token is displayed in the app UI and can be copied for use in server-side scripts.

**Token location:**
- Displayed in MainActivity on app startup
- Can be updated in `/etc/nixos/scripts/fcm-test.py` for testing
- Token changes when app is uninstalled/reinstalled (see Important Notes section)

## Project Structure

```
fcm-notifier/
├── app/
│   ├── src/main/kotlin/com/bedrosn/fcmnotifier/
│   │   ├── MainActivity.kt                    # Main UI, shows notification history
│   │   ├── FCMService.kt                      # Handles FCM messages (onMessageReceived)
│   │   ├── FullScreenNotificationActivity.kt  # Full-screen notification UI with pulsing animation
│   │   ├── NotificationViewModel.kt           # State management + DataStore persistence
│   │   ├── NotificationChannelManager.kt      # Creates silent notification channel
│   │   ├── NotificationData.kt                # Serializable notification data model
│   │   ├── RingtonePlayer.kt                  # Manages Atomic Bell ringtone playback
│   │   ├── NotificationDismissReceiver.kt     # Handles notification swipe dismissal + cross-device sync
│   │   ├── NotificationTapReceiver.kt         # Handles notification tap dismissal
│   │   └── TimerSyncService.kt                # Timer notification mapping and FCM-based dismissal
│   ├── src/main/res/                          # Resources (strings, icons, colors)
│   ├── src/main/AndroidManifest.xml           # Permissions and component declarations
│   ├── build.gradle.kts                       # App dependencies and config
│   └── google-services.json                   # Firebase config (NOT in git, agenix secret)
├── build.gradle.kts                           # Root Gradle config
├── settings.gradle.kts                        # Gradle settings
├── gradle.properties                          # Gradle properties
├── flake.nix                                  # NixOS development environment
├── README.md                                  # User-facing documentation
└── CLAUDE.md                                  # This file
```

## Key Files

### FCMService.kt

**Purpose:** Receives FCM messages and creates custom notifications.

**Key method:**
```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    // Extract from data payload (NOT notification payload!)
    val title = message.data["title"] ?: "New Notification"
    val body = message.data["body"] ?: "You have a new message"
    val timerId = message.data["timer_id"] ?: ""  // NEW: For cross-device sync
    val notificationType = message.data["type"] ?: ""  // NEW: "timer_complete"

    // Save to ViewModel
    NotificationViewModel.getInstance(applicationContext).addNotification(title, body)

    // Show notification (timer-aware for cross-device sync)
    if (notificationType == "timer_complete" && timerId.isNotEmpty()) {
        showTimerNotification(title, body, timerId)
    } else {
        showNotification(title, body, null)
    }
}
```

**Critical notification builder configuration:**
```kotlin
// Generate unique notification ID and pass to full-screen activity
val notificationId = System.currentTimeMillis().toInt()

// NEW: Register timer for cross-device sync (if this is a timer notification)
if (!timerId.isNullOrEmpty()) {
    TimerSyncService.getInstance(this).registerTimer(timerId, notificationId)
}

val fullScreenIntent = Intent(this, FullScreenNotificationActivity::class.java).apply {
    putExtra("notificationId", notificationId)
    // ... other extras
}

// Pass timer_id to delete intent for cross-device sync
val deleteIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
    action = NotificationDismissReceiver.ACTION_NOTIFICATION_DISMISSED
    if (!timerId.isNullOrEmpty()) {
        putExtra(NotificationDismissReceiver.EXTRA_TIMER_ID, timerId)
    }
}

val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setPriority(NotificationCompat.PRIORITY_MAX)
    .setCategory(NotificationCompat.CATEGORY_CALL)  // Required for Android 15
    .setFullScreenIntent(fullScreenPendingIntent, true)  // Triggers full-screen
    .setDeleteIntent(deletePendingIntent)  // Fires on swipe
    .setOngoing(true)  // Makes it persistent like a call
    .build()

notificationManager.notify(notificationId, notification)
```

### FullScreenNotificationActivity.kt

**Purpose:** Shows full-screen UI when notification arrives (like incoming call screen).

**Key configuration:**
```kotlin
// Android 12+ requires WindowManager flags
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
    )
}
```

**Dismiss functionality:**
```kotlin
// Receives notification ID from FCMService via intent extras
val notificationId = intent.getIntExtra("notificationId", -1)

// Cancels notification when user taps dismiss button
onDismiss = {
    if (notificationId != -1) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
    finish()
}
```

**UI:** Jetpack Compose with pulsing animation using `rememberInfiniteTransition`.

### NotificationViewModel.kt

**Purpose:** Manages notification state and DataStore persistence.

**Pattern:** Singleton accessible from `FCMService` (which can't use `by viewModels()`).

```kotlin
companion object {
    @Volatile
    private var INSTANCE: NotificationViewModel? = null

    fun getInstance(context: Context): NotificationViewModel { ... }
    fun setInstance(viewModel: NotificationViewModel) { ... }
}
```

**Persistence:** Uses Jetpack DataStore with kotlinx.serialization for JSON storage.

### NotificationChannelManager.kt

**Purpose:** Creates silent notification channel (sound handled by RingtonePlayer).

**Called from:** `MainActivity.onCreate()` and `FCMService.onCreate()` to ensure channel exists.

**Channel ID:** `fcm_default_channel_v2` (v2 to create fresh channel without default sound)

```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    "FCM Notifications",
    NotificationManager.IMPORTANCE_HIGH  // Required for heads-up notifications
).apply {
    setSound(null, null)  // No sound - we handle it manually via RingtonePlayer
    enableVibration(true)
    vibrationPattern = longArrayOf(0, 500, 250, 500)
}
```

### RingtonePlayer.kt

**Purpose:** Manages Atomic Bell ringtone playback with intelligent looping.

**Pattern:** Singleton object accessible from anywhere in the app.

**Ringtone location:** `/system/media/audio/ringtones/SoundTheme/Galaxy/ACH_Atomic_Bell.ogg`

**Key methods:**
```kotlin
// Start ringtone with optional looping
fun startRingtone(context: Context, shouldLoop: Boolean = true)

// Stop currently playing ringtone
fun stopRingtone()
```

**Usage in FCMService:**
```kotlin
// Detect if phone is locked/screen off
val isScreenOff = !powerManager.isInteractive
val isLocked = keyguardManager.isKeyguardLocked
val shouldLoop = isScreenOff || isLocked

// Start Atomic Bell (loops if locked, plays once if unlocked)
RingtonePlayer.startRingtone(this, shouldLoop)
```

### NotificationDismissReceiver.kt

**Purpose:** Broadcast receiver that handles notification swipe dismissal and cross-device sync.

**Action:** `com.bedrosn.fcmnotifier.NOTIFICATION_DISMISSED`

**Behavior:**
- Stops ringtone when notification is swiped away
- **NEW:** Extracts `timer_id` from intent extras
- **NEW:** Calls `dismiss_timer` Cloud Function to update RTDB for cross-device sync
- **NEW:** Unregisters timer from `TimerSyncService`

```kotlin
override fun onReceive(context: Context?, intent: Intent?) {
    RingtonePlayer.stopRingtone()

    // Extract timer_id if this is a timer notification
    val timerId = intent?.getStringExtra(EXTRA_TIMER_ID)

    if (!timerId.isNullOrEmpty() && context != null) {
        // Unregister from local tracking
        TimerSyncService.getInstance(context).unregisterTimer(timerId)

        // Notify server for cross-device sync
        dismissTimerOnServer(timerId)
    }
}
```

**HTTP Request to Cloud Function:**
```kotlin
POST https://us-central1-notifications-35dd5.cloudfunctions.net/dismiss_timer
Content-Type: application/json

{
  "timer_id": "5351444442100453875",
  "dismissed_by": "phone"
}
```

### TimerSyncService.kt

**Purpose:** Singleton service that manages timer notification state and handles cross-device dismissal via FCM push.

**Pattern:** Singleton with thread-safe `getInstance(context)` method.

**Key data structure:**
```kotlin
// Thread-safe map: timer_id -> Android notification ID
private val activeTimers = ConcurrentHashMap<String, Int>()
```

**Key methods:**
```kotlin
// Register timer when FCM notification is shown
fun registerTimer(timerId: String, notificationId: Int)

// Unregister timer when dismissed locally
fun unregisterTimer(timerId: String)

// Dismiss timer notification (called by FCMService when receiving timer_dismissed FCM)
fun dismissTimerNotification(timerId: String)
```

**Dismissal logic:**
```kotlin
fun dismissTimerNotification(timerId: String) {
    val notificationId = activeTimers.remove(timerId) ?: return

    // Cancel the notification
    notificationManager.cancel(notificationId)

    // Stop the ringtone
    RingtonePlayer.stopRingtone()

    // Broadcast to close FullScreenNotificationActivity if showing
    val dismissIntent = Intent(ACTION_TIMER_DISMISSED_REMOTELY)
    context.sendBroadcast(dismissIntent)
}
```

### NotificationTapReceiver.kt

**Purpose:** Broadcast receiver that handles notification tap.

**Action:** `com.bedrosn.fcmnotifier.NOTIFICATION_TAPPED`

**Behavior:**
- Stops ringtone
- Cancels notification
- Does NOT open the app (user stays in current app)

```kotlin
override fun onReceive(context: Context?, intent: Intent?) {
    val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
    RingtonePlayer.stopRingtone()
    notificationManager.cancel(notificationId)
}
```

## Build System

### NixOS Development Environment

**flake.nix** provides:
- Android SDK (platform-tools, build-tools-35-0-0, platforms-android-35)
- JDK 17
- Gradle wrapper

**Enter environment:**
```bash
cd /home/nicholas/git/fcm-notifier
nix develop
```

### Gradle Commands

**Always prefix with `nix develop --command` to get the Android SDK:**

```bash
# Build and install to connected phone (preferred)
nix develop --command ./gradlew installDebug

# Build only (no install)
nix develop --command ./gradlew assembleDebug

# Clean build
nix develop --command ./gradlew clean assembleDebug

# Build release APK (requires signing)
nix develop --command ./gradlew assembleRelease
```

### Firebase Configuration

**File:** `app/google-services.json`
**Source:** Stored in agenix secrets
**Location on misc host:** `/run/agenix/firebase-config`

**Contains:**
- API key
- Project ID
- Application ID
- Messaging sender ID

## Testing

### Device Connection

**Samsung Galaxy S25 IP Address:** `192.168.1.13`

**Connect via ADB:**
```bash
# Pair (get pairing port and code from phone's wireless debugging settings)
adb pair 192.168.1.13:[PAIRING_PORT] [PAIRING_CODE]

# Connect (get connection port from phone's wireless debugging settings)
adb connect 192.168.1.13:[CONNECTION_PORT]

# Verify connection
adb devices
```

### Send Test Notification

**From misc host:**
```bash
fcm-test --title "Test" --body "Message"
```

**Script location:** `/etc/nixos/scripts/fcm-test.py`

### View Logs

```bash
# View FCM service logs
adb logcat | grep FCMService

# Check if onMessageReceived is called
adb logcat | grep "MESSAGE RECEIVED"

# Check notification creation
adb logcat | grep NotificationManager

# Check full-screen activity launch
adb logcat | grep FullScreenNotificationActivity
```

### Verify Permissions

```bash
# Check USE_FULL_SCREEN_INTENT permission
adb shell appops get com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT
# Should show: USE_FULL_SCREEN_INTENT: allow

# Check battery optimization whitelist
adb shell dumpsys deviceidle whitelist | grep fcmnotifier
# Should show: user,com.bedrosn.fcmnotifier,10502
```

## Common Issues and Solutions

### Issue: Screen Stays Black

**Symptom:** Notification sound plays but screen doesn't wake up.

**Debug:**
```bash
adb shell appops get com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT
```

**Solution:**
```bash
adb shell appops set com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT allow
```

### Issue: No "MESSAGE RECEIVED" Logs

**Symptom:** FCM messages arrive but `onMessageReceived()` not called.

**Cause:** Server is sending messages with `notification` payload.

**Solution:** Update server to send data-only messages (see section 1 above).

### Issue: Notification Shows But Not Full-Screen

**Symptom:** Regular notification appears, no full-screen activity.

**Debug:** Check notification category and full-screen intent configuration in `FCMService.kt:146-149`.

**Solution:** Ensure `CATEGORY_CALL` is set and `setFullScreenIntent()` is called.

### Issue: Messages Not Delivered in Deep Sleep

**Symptom:** Notifications only appear after unlocking device.

**Cause:** Low priority messages or battery optimization blocking delivery.

**Solution:**
1. Ensure server sends `android=messaging.AndroidConfig(priority='high')`
2. Add app to battery whitelist (should already be done via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

## Development Workflow

### Documentation Style

- **Never use emojis in documentation files** - Keep all .md files professional and emoji-free
- Use clear, concise language
- Include code examples where helpful

### Making Changes

1. **Read existing files** - Always read files before editing
2. **Build and install automatically** - After completing code changes, always build and install to the phone:
   ```bash
   nix develop --command ./gradlew installDebug
   ```
   The phone (Samsung S25) is connected via wireless ADB and ready to receive updates.
3. **Check logs** - Use `adb logcat` to verify behavior if needed
4. **Update docs** - Update README.md and this file if needed (no emojis)

**Important:** Always use `nix develop --command` prefix for gradle commands - it provides the Android SDK environment.

### Documenting Changes in HISTORY.md

**IMPORTANT:** When the user invokes the instruction "update-this", you must:

1. **Create or update HISTORY.md** in the project root
2. **Append** the latest change (never overwrite existing history)
3. **Always include timestamp** in format: `YYYY-MM-DD HH:MM:SS`
4. **Format:**
   ```markdown
   ## [YYYY-MM-DD HH:MM:SS]

   ### Changes
   - Brief description of what changed
   - List of modified files
   - Any breaking changes or important notes

   ### Files Modified
   - `path/to/file1.kt`
   - `path/to/file2.md`
   ```

**Only document changes when explicitly requested with "update-this" - do not do this automatically.**

### Adding New Features

**Before adding features that require permissions:**
1. Add permission to `AndroidManifest.xml`
2. Request permission at runtime if needed (Android 6+)
3. Handle permission denial gracefully
4. Update README.md troubleshooting section

**Before adding FCM message fields:**
1. Update server-side script (`/etc/nixos/scripts/fcm-test.py`)
2. Update `FCMService.onMessageReceived()` to handle new fields
3. Test with data-only messages
4. Update README.md with new field documentation

### Testing on Device

**Always test these scenarios:**
1. App in foreground - Notification should show full-screen
2. App in background - Notification should show full-screen
3. App terminated - Notification should show full-screen
4. Screen locked - Screen should wake and show full-screen
5. Deep sleep - Notification should arrive and wake screen
6. **Cross-device timer sync** - Timer notifications dismissed on desktop should auto-dismiss on phone
7. **Cross-device timer sync** - Timer notifications dismissed on phone should auto-dismiss on desktop

## Related Files in NixOS Config

**Server-side FCM script:**
- `/etc/nixos/scripts/fcm-test.py` - Python script to send test notifications

**Cross-device timer system:**
- `/etc/nixos/apps/timer-cli/timer.py` - CLI timer (`t` command) with Cloud Functions integration
- `/etc/nixos/apps/kde-timer-notifications/timer_notifier.py` - KDE desktop notification listener
- `/etc/nixos/docs/services/timer-notifications.md` - Complete timer system documentation
- `/etc/nixos/docs/services/timer-notifications-cloud-functions.md` - Cloud Functions source code

**Firebase secrets (agenix):**
- `/etc/nixos/secrets/firebase-config.age` - Firebase SDK config (JSON)
- `/etc/nixos/secrets/firebase-service-account.age` - Service account for server-side FCM
- `/etc/nixos/secrets/fcm-device-phone-token.age` - Phone FCM token for timer notifications

**Web app (for testing):**
- `/etc/nixos/apps/firebase-test/` - Web app at firebase.bedrosn.com for FCM testing

## Android 15 Compatibility Checklist

When modifying notification code, verify:

- Data-only FCM messages (no `notification` payload)
- `CATEGORY_CALL` for full-screen intents
- `USE_FULL_SCREEN_INTENT` permission granted via ADB
- WindowManager flags for Android 12+ screen wake
- Silent notification channel (sound handled by RingtonePlayer)
- Atomic Bell ringtone plays via RingtonePlayer (not notification channel)
- Tap and swipe intents properly stop ringtone
- Battery optimization exemption
- "Turn screen on" permission enabled

## Documentation

**User-facing documentation:**
- `README.md` - Setup, usage, troubleshooting
- `/home/nicholas/git/devdocs/android/fcm-full-screen-notifications.md` - Complete technical guide

**Developer documentation:**
- This file (`CLAUDE.md`) - Claude Code guidance
- Inline code comments in Kotlin files

## Debugging Tips

**Enable verbose FCM logging:**
```bash
adb shell setprop log.tag.FCM VERBOSE
adb shell setprop log.tag.FirebaseMessaging VERBOSE
adb logcat | grep -E "FCM|Firebase"
```

**Check notification channel importance:**
```bash
adb shell dumpsys notification_manager | grep -A 20 "fcm_default_channel"
```

**Monitor battery optimization changes:**
```bash
adb shell dumpsys deviceidle | grep fcmnotifier
```

## Important Notes

- **Never commit `google-services.json`** - It's in `.gitignore` and should stay encrypted in agenix
- **Always test on real device** - Emulators don't reliably support full-screen intents
- **Samsung-specific settings** - "Never sleeping apps" setting only exists on Samsung devices
- **Data-only messages** - This is the #1 cause of issues, always verify server sends data-only
- **Permission persistence** - `USE_FULL_SCREEN_INTENT` permission must be re-granted after app reinstall
- **FCM token stability** - Token changes when app is uninstalled/reinstalled. To keep token stable, use `./gradlew installDebug` to update the app instead of uninstalling. Avoid clearing app data.

## Success Criteria

A successful notification test should:

1. Screen wakes from locked/off state (if phone is locked)
2. Full-screen activity appears immediately (if phone is locked)
3. Pulsing notification icon animates
4. Title and body text display correctly
5. Notification appears in app history
6. Atomic Bell ringtone plays (loops continuously until dismissed)
7. Vibration triggers on arrival
8. **Dismissal works correctly:**
   - Tapping banner: Stops ringtone, clears notification, stays in current app
   - Swiping banner: Stops ringtone, clears notification
   - Tapping "Dismiss" on full-screen: Stops ringtone, clears notification, closes activity
9. **Cross-device timer sync works correctly (for timer notifications):**
   - Desktop dismissal → phone auto-dismisses notification and stops ringtone (via FCM push)
   - Phone dismissal → desktop auto-dismisses KDE notification (via Cloud Function)
   - Multiple concurrent timers sync independently

## Version History

- **2025-11-26** - Switched cross-device sync from RTDB listener to FCM push:
  - RTDB direct listener was blocked by Firebase security rules
  - Cloud Function `dismiss_timer` now sends FCM push with `type: timer_dismissed` when KDE dismisses
  - Simplified `TimerSyncService.kt` - removed RTDB listener code, now only manages timer mapping
  - `FCMService.kt` handles `timer_dismissed` FCM type to auto-dismiss notifications
  - More battery efficient (no persistent RTDB connection)
- **2025-11-25** - Implemented bidirectional cross-device timer notification sync:
  - Added `TimerSyncService.kt` for cross-device sync
  - Timer notifications now sync dismissals between Android phone and KDE desktop
  - Desktop dismissal → phone auto-dismisses notification and stops ringtone
  - Phone dismissal → desktop auto-dismisses KDE notification
  - Updated `FCMService.kt` to extract `timer_id` and register with sync service
  - Updated `NotificationDismissReceiver.kt` to call Cloud Function for cross-device sync
  - Backward compatible - non-timer FCM notifications continue to work as before
- **2025-11-14** - Added custom Atomic Bell ringtone and smart dismissal behavior:
  - Atomic Bell ringtone loops continuously until dismissed
  - Ringtone stops when tapping or swiping notification banner
  - Tapping banner dismisses notification WITHOUT opening the app
  - No generic notification sound (channel is silent, only Atomic Bell plays)
  - Full notification dismissal clears from notification center completely
- **2025-11-11** - Initial working version with data-only messages, tested on Samsung S25 Android 15
  - Key fixes: Data-only FCM, USE_FULL_SCREEN_INTENT grant, CATEGORY_CALL, WindowManager flags

---

**Tested Environment:**
- Device: Samsung Galaxy S25 (SM-S931W)
- OS: Android 15 / One UI 7
- Build: Debug APK via NixOS flakes
- Status: Fully functional
