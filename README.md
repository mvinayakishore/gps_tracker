# GPS Tracker — Telegram Alerts

A native Android app (Kotlin + AndroidX) that tracks your GPS location and sends Telegram messages with a Google Maps link every 5 minutes whenever you're more than 50 meters from your starting point.

---

## Features

| Feature | Details |
|---|---|
| Background tracking | Foreground service — survives screen-off and app switch |
| Boot persistence | `BootReceiver` auto-restarts tracking after device reboot |
| Telegram alerts | Sent via Bot API every 5 min when >50 m from start |
| Google Maps link | Each alert includes a tappable maps link |
| Simple UI | Enter Bot Token + Chat ID, press Start |

---

## How to Build

### Requirements
- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- A physical device or emulator with Google Play Services

### Steps

1. **Open the project**
   - Open Android Studio → "Open" → select the `android-app/` folder

2. **Let Gradle sync**
   - Android Studio downloads all dependencies automatically (~1–2 min on first sync)

3. **Generate a Gradle wrapper** (if missing)
   ```
   File → Project Structure → (dismiss dialog)
   # or run from terminal:
   gradle wrapper --gradle-version 8.2
   ```

4. **Build & install**
   - Connect your Android device (USB debugging on) or start an emulator
   - Click ▶ Run (Shift+F10)

5. **Grant permissions when prompted**
   - Location → "Allow all the time" (required for background tracking)
   - Notifications → Allow (for the persistent foreground service notification)

---

## Telegram Setup

### 1. Create a Bot
1. Open Telegram and message **@BotFather**
2. Send `/newbot` and follow the prompts
3. Copy the **Bot Token** (looks like `123456789:ABCdef...`)

### 2. Get your Chat ID
1. Message **@userinfobot** on Telegram
2. It replies with your user ID — that's your Chat ID
3. For group chats: add the bot to the group, then use @RawDataBot to find the group's ID (starts with `-100`)

### 3. Start the bot
- Send `/start` to your bot once so it can message you

---

## App Usage

1. Launch **GPS Tracker**
2. Enter your **Bot Token** and **Chat ID**
3. Tap **Send Test Message** to confirm the connection works
4. Tap **Start Tracking**
5. Grant location permissions → choose **"Allow all the time"**
6. The app locks your current position as the starting point
7. Walk more than **50 meters** away — you'll receive a Telegram message every **5 minutes**

---

## Permissions Explained

| Permission | Why it's needed |
|---|---|
| `ACCESS_FINE_LOCATION` | Precise GPS coordinates |
| `ACCESS_BACKGROUND_LOCATION` | Tracking while screen is off |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Keeps the service alive in background |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot |
| `INTERNET` | Telegram Bot API calls |
| `WAKE_LOCK` | Prevents CPU sleep during updates |
| `POST_NOTIFICATIONS` | Android 13+ foreground notification |

---

## Project Structure

```
android-app/
├── app/
│   ├── build.gradle                        # App-level Gradle config
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions + components
│       ├── kotlin/com/gpstracker/telegram/
│       │   ├── MainActivity.kt             # UI, permission flow
│       │   ├── LocationTrackingService.kt  # Foreground service, GPS, alerts
│       │   ├── BootReceiver.kt             # Restarts after reboot
│       │   ├── TelegramApi.kt              # Bot API + message builder
│       │   └── Prefs.kt                    # SharedPreferences key constants
│       └── res/
│           ├── layout/activity_main.xml    # Main screen layout
│           ├── values/strings.xml
│           ├── values/colors.xml
│           ├── values/themes.xml
│           └── drawable/                   # Icons + shapes
├── build.gradle                            # Project-level Gradle config
├── settings.gradle
└── gradle.properties
```

---

## Customization

Edit these constants at the top of `LocationTrackingService.kt`:

```kotlin
private const val ALERT_DISTANCE_METERS   = 50f          // meters before alerts fire
private const val TELEGRAM_INTERVAL_MS    = 5 * 60 * 1000L  // ms between messages
private const val LOCATION_UPDATE_INTERVAL_MS = 30_000L  // GPS poll interval
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| No Telegram messages | Tap "Send Test Message" to verify credentials; check that you've sent `/start` to your bot |
| Tracking stops in background | Disable battery optimization for this app: Settings → Apps → GPS Tracker → Battery → Unrestricted |
| Location not updating | Make sure "Allow all the time" was granted, not just "While using" |
| App doesn't restart after reboot | Some OEM ROMs (MIUI, OneUI) block `BOOT_COMPLETED`; disable "Auto-start restrictions" in settings |
