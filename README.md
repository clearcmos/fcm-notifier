# FCM Notifier

**Last Modified:** 2025-11-14

A native Android app that receives Firebase Cloud Messaging (FCM) push notifications and displays them as **full-screen notifications** with pulsing animations, similar to incoming call screens or Outlook calendar reminders.

## Features

- **Full-Screen Notifications** - Wakes screen and displays notifications in full-screen mode like incoming calls
- **Custom Ringtone** - Uses Samsung's "Atomic Bell" ringtone with intelligent looping behavior
- **Smart Dismissal** - Tap or swipe banner to dismiss without opening the app
- **Persistent History** - Notifications are stored locally until manually cleared
- **Material Design 3** - Modern UI with Jetpack Compose
- **Android 15 Compatible** - Fully tested on Samsung Galaxy S25 with Android 15
- **Proper Permissions** - Handles all Android 15 permission requirements
- **DataStore Persistence** - Notification history survives app restarts

## Technical Details

- **Language:** Kotlin 2.1.0
- **UI Framework:** Jetpack Compose (Material Design 3)
- **Build System:** Gradle 8.7.3 with NixOS flakes
- **Target SDK:** 35 (Android 15)
- **Min SDK:** 24 (Android 7.0)
- **Firebase:** Firebase Cloud Messaging (FCM) with BOM 33.7.0

## Project Structure

```
fcm-notifier/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/bedrosn/fcmnotifier/
│   │   │   ├── MainActivity.kt                    # Main UI with notification history
│   │   │   ├── FCMService.kt                      # Handles incoming FCM messages
│   │   │   ├── FullScreenNotificationActivity.kt  # Full-screen notification UI
│   │   │   ├── NotificationViewModel.kt           # State management with DataStore
│   │   │   ├── NotificationChannelManager.kt      # Notification channel setup
│   │   │   ├── NotificationData.kt                # Data models
│   │   │   ├── RingtonePlayer.kt                  # Atomic Bell ringtone manager
│   │   │   ├── NotificationDismissReceiver.kt     # Handles notification swipe dismissal
│   │   │   └── NotificationTapReceiver.kt         # Handles notification tap dismissal
│   │   ├── res/                                   # Resources (layouts, strings, icons)
│   │   └── AndroidManifest.xml                    # Permissions and components
│   ├── build.gradle.kts                           # App-level Gradle config
│   └── google-services.json                       # Firebase configuration (not in git)
├── build.gradle.kts                               # Project-level Gradle config
├── flake.nix                                      # NixOS development environment
├── README.md                                      # This file
└── CLAUDE.md                                      # Development guide for Claude Code
```

## Setup

### Prerequisites

- NixOS (for development environment) or Android SDK with JDK 17
- Firebase project with FCM enabled
- Android device running Android 7.0+ (tested on Android 15)

### 1. Firebase Configuration

1. Create a Firebase project at https://console.firebase.google.com
2. Add an Android app with package name `com.bedrosn.fcmnotifier`
3. Download `google-services.json` and place it in `app/` directory
4. Enable Cloud Messaging in Firebase Console

### 2. Build and Install

**Using NixOS (recommended):**

```bash
cd /home/nicholas/git/fcm-notifier
nix develop
./gradlew installDebug
```

**Using standard Android SDK:**

```bash
./gradlew installDebug
```

### 3. Configure Android 15 Permissions

FCM Notifier requires special permissions on Android 15 to show full-screen notifications:

```bash
# Grant full-screen intent permission (CRITICAL for Android 15)
adb shell appops set com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT allow

# Verify permission was granted
adb shell appops get com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT
# Should show: USE_FULL_SCREEN_INTENT: allow
```

**Manual Settings:**

1. **Settings → Apps → Special access → Turn screen on → FCM Notifier** → Enable
2. **Settings → Battery → Background usage limits → Never sleeping apps** → Add FCM Notifier (Samsung only)

### 4. Get FCM Token

1. Open the FCM Notifier app
2. Grant notification permission when prompted
3. The app will show a battery optimization warning - tap "Disable Battery Optimization" if needed
4. Copy the FCM token displayed in the app

## Sending Notifications

### ⚠️ CRITICAL: Use Data-Only Messages

You **MUST** send **data-only messages** (no `notification` payload). If you include a `notification` field, Firebase handles it automatically and your custom full-screen notification won't show when the app is in the background.

### Python Example (firebase-admin)

```python
import firebase_admin
from firebase_admin import credentials, messaging

# Initialize Firebase Admin SDK
cred = credentials.Certificate("path/to/serviceAccountKey.json")
firebase_admin.initialize_app(cred)

# ✅ CORRECT - Data-only message
message = messaging.Message(
    data={
        'title': 'Test Notification',
        'body': 'Hello from FCM!',
    },
    android=messaging.AndroidConfig(
        priority='high',  # HIGH priority for immediate delivery
    ),
    token='YOUR_FCM_TOKEN_HERE',
)

response = messaging.send(message)
print(f"Message sent: {response}")
```

### NixOS Script (misc host)

```bash
fcm-test --title "Test Notification" --body "Hello from FCM!"
```

## How It Works

1. **Server sends data-only FCM message** with high priority
2. **Google Play Services delivers** the message to your device (even in Doze mode)
3. **FCMService.onMessageReceived()** is called (because it's data-only)
4. **Custom notification is created** with full-screen intent and `CATEGORY_CALL`
5. **Atomic Bell ringtone plays** (loops continuously until dismissed)
6. **Screen wakes up** (if locked) and shows full-screen pulsing notification
7. **Notification is saved** to local DataStore for history
8. **User can dismiss** by:
   - Tapping the banner notification (clears notification, stops ringtone, stays in current app)
   - Swiping the banner notification (clears notification, stops ringtone)
   - Tapping "Dismiss" on full-screen (clears notification, stops ringtone)

## Troubleshooting

### Screen Stays Black When Notification Arrives

**Problem:** Android 15 has revoked the `USE_FULL_SCREEN_INTENT` permission.

**Check permission:**
```bash
adb shell appops get com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT
```

**Solution:**
```bash
adb shell appops set com.bedrosn.fcmnotifier USE_FULL_SCREEN_INTENT allow
```

### Notifications Arrive But Don't Show Full-Screen

**Problem:** You're sending messages with `notification` payload instead of data-only.

**Check logs:**
```bash
adb logcat | grep "FCMService.*MESSAGE RECEIVED"
```

If you see **NO logs**, your messages have a `notification` field.

**Solution:** Remove the `notification` field and only use `data` field in your FCM messages.

### Notifications Not Arriving During Deep Sleep

**Problem:** Messages don't have high priority or battery optimization is blocking them.

**Check battery whitelist:**
```bash
adb shell dumpsys deviceidle whitelist | grep fcmnotifier
```

**Solution:**
1. Set `android=messaging.AndroidConfig(priority='high')` in your messages
2. Add app to "Never sleeping apps" in Samsung battery settings
3. Disable battery optimization in app settings

### App Crashes or Notification Doesn't Show

**View logs:**
```bash
# View FCM service logs
adb logcat | grep FCMService

# Check notification creation
adb logcat | grep NotificationManager

# Check full-screen activity
adb logcat | grep FullScreenNotificationActivity
```

## Key Differences from Standard FCM Notifications

| Standard FCM | FCM Notifier |
|--------------|--------------|
| System handles notifications automatically | App handles notifications with custom UI |
| Basic notification tray icon | Full-screen takeover like incoming calls |
| Uses `notification` payload | Uses `data` payload only |
| Works with default permissions | Requires `USE_FULL_SCREEN_INTENT` grant |
| Standard notification sound | Custom Atomic Bell ringtone (loops until dismissed) |
| Tap opens app | Tap dismisses notification without opening app |
| No persistent history | Saves all notifications until cleared |

## Android 15 Compatibility Notes

Android 15 introduced strict restrictions on full-screen intents:

- **Permission revoked by default** - Must manually grant `USE_FULL_SCREEN_INTENT` via ADB
- **Category requirement** - Notification must use `CATEGORY_CALL` or `CATEGORY_ALARM`
- **WindowManager flags** - Android 12+ requires WindowManager flags instead of deprecated `setTurnScreenOn()`
- **Battery optimization** - More aggressive Doze mode, requires high-priority messages and battery exemption

See [/home/nicholas/git/devdocs/android/fcm-full-screen-notifications.md](../devdocs/android/fcm-full-screen-notifications.md) for complete technical documentation.

## Use Cases

- Home automation alerts that need immediate attention
- Critical system monitoring notifications
- Time-sensitive reminders that must wake the screen
- IoT device alerts (security cameras, sensors, etc.)
- Custom calling/video chat apps
- Server downtime alerts
- Security breach notifications

## Development

### Build Commands

```bash
# Enter nix shell
nix develop

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Install to device
./gradlew installDebug

# View connected devices
adb devices

# View app logs
adb logcat | grep FCMService
```

### Testing FCM Messages

**Check if device can receive messages:**
```bash
adb logcat | grep "com.google.firebase"
```

**Monitor message delivery:**
```bash
adb logcat | grep -E "FCMService|GCM|FCM"
```

## License

MIT License - See LICENSE file for details.

## Author

Nicholas Bedros (nicbedros@gmail.com)

## Related Documentation

- [Full FCM Implementation Guide](../devdocs/android/fcm-full-screen-notifications.md) - Complete technical guide
- [Firebase Console](https://console.firebase.google.com)
- [Android Full-Screen Intent Documentation](https://source.android.com/docs/core/permissions/fsi-limits)

## Tested On

- **Device:** Samsung Galaxy S25 (SM-S931W)
- **OS:** Android 15 / One UI 7
- **Date:** 2025-11-11
- **Status:** Fully working with data-only messages
