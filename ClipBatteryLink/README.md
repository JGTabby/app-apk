# 喵喵連接

Android phone-to-phone prototype for syncing clipboard text and showing the other phone's battery level.

## What this prototype does

- Uses Bluetooth Classic RFCOMM between two already-paired Android phones.
- Sends local battery percentage and charging state to the other phone.
- Refreshes battery status every 60 seconds while the phones are connected and the app is visible.
- Shows the other phone's current network type and approximate download/upload speed.
- Sends clipboard text manually with **Send Now**.
- Can auto-send clipboard changes while the app screen is open and focused.
- Copies received remote clipboard text into the local Android clipboard.

## Network speed behavior

The app estimates network speed from Android's built-in traffic counters. It does not run an internet speed test and does not create extra network traffic.

- Sampling runs only while the app is visible and the Bluetooth phone link is connected.
- The default sample interval is 5 seconds to keep battery use low.
- If the active network is mobile data, the app uses Android's mobile traffic counters.
- If the active network is Wi-Fi, VPN, Ethernet, or another transport, the app uses total traffic counters and labels the current active transport.
- The displayed speed is approximate and meant for lightweight monitoring.

## Important Android limitation

Modern Android does not allow ordinary background apps to silently watch the global clipboard. This prototype keeps clipboard sync explicit and foreground-friendly:

- Open the app on both phones.
- Keep the app visible when using automatic clipboard sync.
- Use **Send Now** when Android or the phone vendor blocks passive clipboard reads.

For a stronger future version, possible paths are:

- A foreground service with a persistent notification for connection health and battery sync.
- A custom keyboard/IME for deeper clipboard capture.
- A local Wi-Fi transport for higher speed and fewer Bluetooth pairing quirks.
- End-to-end encryption and QR-code pairing.

## How to try it

1. Open this folder in Android Studio: `ClipBatteryLink`.
2. Let Android Studio sync the Gradle project.
3. Install the app on both phones.
4. Pair the phones in Android system Bluetooth settings.
5. Open the app on both phones.
6. On phone A, tap **Wait**.
7. On phone B, choose phone A from the paired-device list and tap **Connect**.
8. Copy text on either phone while the app is visible, or tap **Send Now**.

## Files

- `app/src/main/java/dev/codex/clipbatterylink/MainActivity.java`
  Main screen, permissions, clipboard handling, battery status, and network speed sampling.
- `app/src/main/java/dev/codex/clipbatterylink/BluetoothLink.java`
  Bluetooth server/client connection and line-based JSON messages.
- `app/src/main/AndroidManifest.xml`
  Bluetooth and network-state permissions.

## Known rough edges

- The local environment used to create this project did not include an Android SDK or Gradle, so the project was not compiled here.
- This first version uses paired Bluetooth devices only; it does not scan for unpaired devices inside the app.
- Battery updates are low-frequency while the app is visible. A production version should use a foreground service if background updates are needed.
- Clipboard sync is text-only.
- Network speed is a lightweight estimate, not a full speed-test result.
