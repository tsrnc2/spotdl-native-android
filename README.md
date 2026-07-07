# spotDL Native Android

Native Android wrapper for spotDL using Chaquopy, embedded Python packages, and bundled arm64 FFmpeg libraries.

## Features

- Queue Spotify tracks, albums, playlists, playlist searches, and shared URLs.
- Search Spotify playlists from the Android UI.
- Download in a foreground Android service with notification status and Stop action.
- Optional Wi-Fi-only download gate.
- Optional background mode and Android Music publishing.
- In-app help and runtime health check.

## Build

This project is configured for an Android/Termux-oriented build environment.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

The release APK is produced at:

```text
app/build/outputs/apk/release/app-release.apk
```

Release signing is loaded from `SPOTDL_NATIVE_KEYSTORE_PROPERTIES` or `~/.android/spotdl-native/release.properties` when available.

## APK Archive

Built APKs are organized on Google Drive under:

```text
APK Releases/
```

Current spotDL Native release:

```text
APK Releases/release/spotdl-native-app-release.apk
```
