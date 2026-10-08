# SAFEX - VS Code Edition (No Android Studio)

This project is designed so the SAFEX app can be edited in VS Code. Android Studio is NOT required for the normal workflow described here.

## What you edit

- `app/src/main/assets/index.html` -> SAFEX UI, JavaScript, Bluetooth commands, dashboard, SOS, water and period logic.
- `app/src/main/java/com/safex/webview/MainActivity.java` -> Android native Bluetooth SPP bridge for HC-05, permissions, SMS/dialer and WebView bridge.
- `app/src/main/AndroidManifest.xml` -> Android permissions.

## Important limitation

VS Code is the editor. To produce an Android APK, an Android SDK/JDK/Gradle build environment is still required. This project avoids installing Android Studio by using GitHub Actions to build the APK in the cloud.

## Easiest workflow

1. Install VS Code.
2. Install Git.
3. Create a GitHub account and a new repository named `SAFEX-App`.
4. Upload the contents of this project to that repository.
5. In VS Code, edit the files normally.
6. Commit and push changes.
7. GitHub Actions automatically runs `.github/workflows/build-apk.yml`.
8. Open the repository on GitHub -> Actions -> `Build SAFEX APK` -> latest successful run -> Artifacts -> `SAFEX-debug-apk`.
9. Download the ZIP artifact, extract it, and install `app-debug.apk` on the Android phone.

You can also manually start a build from GitHub: Actions -> Build SAFEX APK -> Run workflow.

## Bluetooth

The Android app uses native Classic Bluetooth SPP for the HC-05. Pair HC-05 in Android Bluetooth settings first. Then use SAFEX -> Bluetooth -> Connect.

## Arduino protocol

The app sends the existing SAFEX protocol, including:

- `APP,1`
- `TIME,YYYY,M,D,H,M,S,WEEKDAY`
- `WATER,minutes`

It receives messages such as:

- `BT,1`
- `BAT,percent,voltage`
- `BPM,bpm`
- `SOS,ACTIVE`
- `WATER,minutes`

## Platform note

The UI/JavaScript can be shared across Web and mobile. The HC-05 Classic Bluetooth connection is implemented natively for Android. A normal iPhone/iPad cannot be treated as a generic HC-05 SPP client; for true Android+iOS Bluetooth support, the hardware should use BLE instead of HC-05.
