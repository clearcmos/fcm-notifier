# Change History

## [2025-11-25 19:53:00]

### Changes
- **Implemented bidirectional cross-device timer notification sync**
  - Android phone and KDE desktop now sync timer notification dismissals in real-time
  - When timer is dismissed on desktop → phone auto-dismisses notification and stops ringtone
  - When timer is dismissed on phone → desktop auto-dismisses notification
  - Uses Firebase Realtime Database for state synchronization across devices
- Added `TimerSyncService.kt` - Singleton service that listens to RTDB for timer status changes
- Updated `FCMService.kt` to extract `timer_id` from FCM data payload and register with sync service
- Updated `NotificationDismissReceiver.kt` to call Cloud Function endpoint for cross-device sync
- Updated `MainActivity.kt` to initialize timer sync listener on app start
- Added `firebase-database-ktx` dependency for RTDB support

### Files Modified
- `app/build.gradle.kts` - Added Firebase Realtime Database dependency
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/TimerSyncService.kt` - NEW FILE
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/FCMService.kt` - Extract timer_id, register with sync service
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/NotificationDismissReceiver.kt` - Call dismiss_timer Cloud Function
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/MainActivity.kt` - Initialize TimerSyncService
- `CLAUDE.md` - Documented cross-device sync architecture and implementation
- `UPDATE.md` - Comprehensive implementation guide created
- `HISTORY.md` - NEW FILE (this file)

### Architecture
- **RTDB URL**: `https://notifications-35dd5-default-rtdb.firebaseio.com`
- **Cloud Function**: `https://us-central1-notifications-35dd5.cloudfunctions.net/dismiss_timer`
- **Timer ID Mapping**: `ConcurrentHashMap<String, Int>` maps RTDB timer_id to Android notificationId
- **Sync Protocol**: RTDB `ChildEventListener` detects status changes and auto-dismisses locally

### Important Notes
- **Not built yet** - Code changes complete but app not compiled/installed
- **Backward compatible** - Non-timer FCM notifications continue to work as before
- **Requires RTDB listener** - Service must be running for sync to work (started in MainActivity.onCreate)
- **Network required** - HTTP POST to Cloud Function when dismissing timer

### Testing Required
1. Build and install updated APK
2. Test desktop → phone dismissal sync
3. Test phone → desktop dismissal sync (regression)
4. Test multiple concurrent timers
5. Verify non-timer notifications still work

### Related Documentation
- `/home/nicholas/git/fcm-notifier/UPDATE.md` - Complete implementation planning document
- `/etc/nixos/docs/services/timer-notifications.md` - Server-side timer system architecture
- `/etc/nixos/apps/kde-timer-notifications/timer_notifier.py` - Desktop KDE notification listener

---

## [2025-11-26 01:55:00]

### Changes
- **Added custom Atomic Bell ringtone with intelligent looping**
  - Uses Samsung's "Atomic Bell" ringtone (`/system/media/audio/ringtones/SoundTheme/Galaxy/ACH_Atomic_Bell.ogg`)
  - Loops continuously until notification is dismissed
  - Created `RingtonePlayer.kt` singleton to manage playback

- **Smart notification dismissal without opening app**
  - Tapping banner notification: Stops ringtone, clears notification, stays in current app
  - Swiping banner notification: Stops ringtone, clears notification
  - Created `NotificationTapReceiver.kt` to handle tap-to-dismiss behavior
  - Created `NotificationDismissReceiver.kt` to handle swipe dismissal

- **Silent notification channel**
  - Changed channel ID to `fcm_default_channel_v2` (fresh channel without default sound)
  - Set `setSound(null, null)` - all sound handled via RingtonePlayer
  - Eliminated duplicate notification sound (was playing both default + Atomic Bell)

- **Fixed full-screen dismiss button for cross-device timer sync**
  - `FCMService.kt` now passes `timerId` to `FullScreenNotificationActivity` intent
  - `FullScreenNotificationActivity` extracts `timerId` and triggers cross-device sync when Dismiss button is tapped
  - Sends broadcast to `NotificationDismissReceiver` which calls Cloud Function

- **Updated documentation**
  - `CLAUDE.md` - Added RingtonePlayer, NotificationTapReceiver, NotificationDismissReceiver documentation
  - `README.md` - Updated features, project structure, and behavior descriptions

### Files Modified
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/RingtonePlayer.kt` - NEW FILE: Manages Atomic Bell playback
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/NotificationTapReceiver.kt` - NEW FILE: Handles tap-to-dismiss
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/NotificationDismissReceiver.kt` - Added timer sync logic
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/FCMService.kt` - Added RingtonePlayer, tap/swipe intents, timerId to full-screen intent
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/FullScreenNotificationActivity.kt` - Added timer sync on Dismiss
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/NotificationChannelManager.kt` - Silent channel (v2)
- `app/src/main/AndroidManifest.xml` - Registered NotificationTapReceiver and NotificationDismissReceiver
- `CLAUDE.md` - Comprehensive documentation updates
- `README.md` - Updated features and behavior

### Testing Completed
- Atomic Bell ringtone plays and loops until dismissed
- Tap banner dismisses without opening app
- Swipe banner dismisses and stops ringtone
- Full-screen Dismiss button stops ringtone and clears notification
- Phone to KDE cross-device timer sync works
- KDE to Phone cross-device timer sync works
- No duplicate notification sounds

### Important Notes
- Ringtone file location: `/system/media/audio/ringtones/SoundTheme/Galaxy/ACH_Atomic_Bell.ogg`
- Falls back to default ringtone if Atomic Bell not found on device
- Cross-device sync requires app to be running (TimerSyncService listener active)

---

## [2025-11-26 02:45:00]

### Changes
- **Switched from RTDB listener to FCM push for cross-device timer dismissal**
  - RTDB direct listener was blocked by Firebase security rules
  - Cloud Function `dismiss_timer` now sends FCM push to phone when KDE dismisses
  - More battery efficient (no persistent RTDB connection)
  - Works with locked-down security configuration

- **Simplified TimerSyncService.kt**
  - Removed all RTDB listener code (Firebase database imports, `startListening()`, `stopListening()`, `ChildEventListener`)
  - Renamed `dismissLocalNotification()` to `dismissTimerNotification()` and made it public
  - Now only handles: `registerTimer()`, `unregisterTimer()`, `dismissTimerNotification()`

- **Updated FCMService.kt to handle `timer_dismissed` notification type**
  - Added `when` block to route messages by type
  - `timer_dismissed` -> calls `TimerSyncService.dismissTimerNotification()`
  - `timer_complete` -> shows timer notification
  - Default -> shows regular notification

- **Removed unused RTDB listener initialization**
  - Removed `startListening()` calls from `FCMService.onCreate()` and `MainActivity.onCreate()`

### Files Modified
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/TimerSyncService.kt` - Removed RTDB listener, simplified to FCM-based dismissal
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/FCMService.kt` - Handle `timer_dismissed` FCM type, removed `startListening()` call
- `app/src/main/kotlin/com/bedrosn/fcmnotifier/MainActivity.kt` - Removed `startListening()` call

### Architecture Change
**Before (RTDB Listener)**:
1. KDE dismisses -> Cloud Function updates RTDB
2. Phone RTDB listener detects change -> auto-dismisses

**After (FCM Push)**:
1. KDE dismisses -> Cloud Function updates RTDB + sends FCM push with `type: timer_dismissed`
2. Phone receives FCM -> `FCMService.onMessageReceived()` -> `dismissTimerNotification()`

### Testing Completed
- Phone to KDE timer dismissal sync works
- KDE to Phone timer dismissal sync works (via FCM push)
- Full-screen activity dismissed when KDE dismisses
- Ringtone stops when KDE dismisses
