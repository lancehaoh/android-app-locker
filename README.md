# App Locker

Android app locker targeting Android 16 / Samsung Galaxy S26+.

## Features
- Lock any installed app (messaging apps pre-highlighted: Telegram, WhatsApp, Samsung Messages, etc.)
- Unlock methods: **PIN**, **Password**, **Fingerprint**, **Face** (BiometricPrompt)
- Optional biometric fallback alongside PIN/Password
- Dual detection: `UsageStatsManager` + Accessibility Service (Samsung One UI compatible)
- Persists across reboots
- Encrypted credential storage (AES-256)
- 3-second grace period — won't re-lock if you briefly leave and return

## Project Structure

```
app/src/main/java/com/applocker/
├── AppLockerApp.kt                     # Application class, notification channel
├── data/
│   ├── AppLockDatabase.kt             # Room database
│   ├── LockedApp.kt                   # Entity
│   ├── LockedAppDao.kt                # DAO
│   └── PreferencesManager.kt         # Encrypted prefs (PIN/password hashes)
├── service/
│   ├── AppMonitorService.kt           # Foreground service, polls UsageStats
│   └── AppLockAccessibilityService.kt # Accessibility fallback
├── receiver/
│   └── BootReceiver.kt               # Auto-start on boot
└── ui/
    ├── MainActivity.kt               # Settings / permissions dashboard
    ├── AppListActivity.kt            # App picker with search
    ├── LockActivity.kt               # The lock screen (PIN / password / biometric)
    ├── SetupLockActivity.kt          # Configure unlock method
    └── adapter/
        └── AppListAdapter.kt
```

## Build

### Requirements
- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK with API 36 (Android 16)

### Steps
1. Open the `android-app-locker` folder in Android Studio
2. Let Gradle sync finish
3. **Run on device** (Samsung Galaxy S26+ preferred — some features need hardware biometrics)

## First-Time Setup on Device

1. Open the app
2. Tap **Grant** next to **Usage Access** → find "App Locker" → enable it
3. Tap **Grant** next to **Display Over Other Apps** → enable it
4. (Recommended) Tap **Grant** next to **Accessibility Service** → enable "App Locker Monitor"
5. Tap **Setup Lock** → choose PIN, Password, or Biometric
6. Tap **Select Apps to Lock** → toggle apps on
7. Toggle **App Lock Enabled** → the service starts

## How It Works

- `AppMonitorService` runs as a foreground service and queries `UsageStatsManager` every 300 ms
- When it detects a locked app in the foreground, it launches `LockActivity`
- `LockActivity` shows the configured unlock UI (numpad PIN / password field / biometric prompt)
- On success it calls `AppMonitorService.notifyUnlocked()` which sets a 3-second grace window
- `AppLockAccessibilityService` listens to `TYPE_WINDOW_STATE_CHANGED` events as a fallback — Samsung One UI sometimes delays `UsageStats` events

## Permissions Explained

| Permission | Why needed |
|---|---|
| `PACKAGE_USAGE_STATS` | Detect which app is in foreground |
| `SYSTEM_ALERT_WINDOW` | Draw lock screen over other apps |
| `FOREGROUND_SERVICE` | Keep monitor running in background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `USE_BIOMETRIC` | Fingerprint / face unlock |
| `QUERY_ALL_PACKAGES` | List installed apps to lock |

## Notes for Samsung One UI
- Samsung restricts background app detection more aggressively than stock Android. **Enable the Accessibility Service** for the most reliable detection.
- In Battery settings → App Locker → set to **Unrestricted** to prevent the OS from killing the monitor service.
- On Android 16, `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` is required — already declared in the manifest.
