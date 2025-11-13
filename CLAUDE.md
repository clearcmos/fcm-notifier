# CLAUDE.md

**Last Modified:** 2025-11-11

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

### 2. Android 15 Full-Screen Intent Requirements

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

**Current token (as of 2025-11-11):**
```
fEEen7mVTqeza7BkvcxgWs:APA91bF_DWFMPeFsn1l5i2JSujQ8bWmBcZIJUc-j95xLtNMhuFyhBaxeGdfOYizufxkUDZ-dYYKEcspbuiPVROrCcZ5NywUTTT-fZInyDWIUp8JK6nCuuEQ
```

This token is hardcoded in `/etc/nixos/scripts/fcm-test.py` and displayed in the app UI.

## Project Structure

```
fcm-notifier/
├── app/
│   ├── src/main/kotlin/com/bedrosn/fcmnotifier/
│   │   ├── MainActivity.kt                    # Main UI, shows notification history
│   │   ├── FCMService.kt                      # Handles FCM messages (onMessageReceived)
│   │   ├── FullScreenNotificationActivity.kt  # Full-screen notification UI with pulsing animation
│   │   ├── NotificationViewModel.kt           # State management + DataStore persistence
│   │   ├── NotificationChannelManager.kt      # Creates high-priority notification channel
│   │   └── NotificationData.kt                # Serializable notification data model
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

    // Save to ViewModel
    NotificationViewModel.getInstance(applicationContext).addNotification(title, body)

    // Show full-screen notification
    showNotification(title, body)
}
```

**Critical notification builder configuration:**
```kotlin
val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setPriority(NotificationCompat.PRIORITY_MAX)
    .setCategory(NotificationCompat.CATEGORY_CALL)  // Required for Android 15
    .setFullScreenIntent(fullScreenPendingIntent, true)  // Triggers full-screen
    .setOngoing(true)  // Makes it persistent like a call
    .build()
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

**Purpose:** Creates notification channel with high importance.

**Called from:** `MainActivity.onCreate()` and `FCMService.onCreate()` to ensure channel exists.

```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    "FCM Notifications",
    NotificationManager.IMPORTANCE_HIGH  // Required for heads-up notifications
)
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

```bash
# Build and install
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug

# Build release APK (requires signing)
./gradlew assembleRelease
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

### Making Changes

1. **Read existing files** - Always read files before editing
2. **Test locally** - Use `./gradlew installDebug` to install to connected device
3. **Check logs** - Use `adb logcat` to verify behavior
4. **Update docs** - Update README.md and this file if needed

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

## Related Files in NixOS Config

**Server-side FCM script:**
- `/etc/nixos/scripts/fcm-test.py` - Python script to send test notifications

**Firebase secrets (agenix):**
- `/etc/nixos/secrets/firebase-config.age` - Firebase SDK config (JSON)
- `/etc/nixos/secrets/firebase-service-account.age` - Service account for server-side FCM

**Web app (for testing):**
- `/etc/nixos/apps/firebase-test/` - Web app at firebase.bedrosn.com for FCM testing

## Android 15 Compatibility Checklist

When modifying notification code, verify:

- Data-only FCM messages (no `notification` payload)
- `CATEGORY_CALL` for full-screen intents
- `USE_FULL_SCREEN_INTENT` permission granted via ADB
- WindowManager flags for Android 12+ screen wake
- High-priority notification channel
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

## Success Criteria

A successful notification test should:

1. Screen wakes from locked/off state
2. Full-screen activity appears immediately
3. Pulsing notification icon animates
4. Title and body text display correctly
5. Notification appears in app history
6. Sound and vibration trigger
7. "Dismiss" button closes full-screen activity
8. Tapping notification opens MainActivity

## Version History

- **2025-11-11** - Initial working version with data-only messages, tested on Samsung S25 Android 15
- Key fixes: Data-only FCM, USE_FULL_SCREEN_INTENT grant, CATEGORY_CALL, WindowManager flags

---

**Tested Environment:**
- Device: Samsung Galaxy S25 (SM-S931W)
- OS: Android 15 / One UI 7
- Build: Debug APK via NixOS flakes
- Status: Fully functional
