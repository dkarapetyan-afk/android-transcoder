# Recording Compressor

Android app that records the screen and compresses recordings on-device with FFmpeg or the device encoder (Media3 Transformer).

- Kotlin, Jetpack Compose, Material 3
- Import a video or audio file via the system file picker, or share / send one (or several) from another app
- Combine a picture or video with a separate soundtrack (FFmpeg or Media3). A still image lasts as long as the audio. Sharing several pictures/videos with several audio files creates one job per pair.
- Clear all Recent / Library jobs and leftover cache files (gallery outputs stay)
- Audio-only output: extract a video soundtrack or transcode audio to AAC (.m4a) or Opus (.webm) with FFmpeg or Media3
- WebM output: VP8/VP9 + Opus video, or Opus-only audio, on both engines
- Screen recording via MediaProjection (microphone or internal audio)
- Presets plus advanced encoder settings
- Two encode engines: FFmpeg (default, with software fallback) or Device / Media3 (hardware MediaCodec, same approach as Compressor Edge)
- Play-oriented: target API 37, scoped storage, typed foreground services, LGPL FFmpeg
- Android 16 App Functions so a system agent can list jobs, change encode settings, start or cancel the queue, and read progress (no media bytes)
- Optional Device library access (`READ_MEDIA_*`) so that agent can list and import files already on the device without the picker

## Build

Requires JDK 17+ and the Android SDK (compile SDK 37).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## App Functions (Android 16+)

Privileged system agents can drive imported jobs without opening the UI. The service is `com.androidcompress.app.agent.CompressAppFunctionService` (`.debug` in debug builds).

Typical tools: `describeCapabilities`, `listJobs`, `getJob`, `updateJobSettings`, `applyPreset`, `previewEncode`, `startJob`, `startReadyJobs`, `getQueue`, `getProgress`, `getEncodeLog`, `cancelJob`, `cancelQueue`, `listDeviceMedia`, `importDeviceMedia`, `importFile`, `importCombine`, `getAppDefaults`, `setAppDefaults`.

```bash
adb shell cmd app_function list-app-functions | grep -A 20 androidcompress
adb shell "cmd app_function execute-app-function \
  --package com.androidcompress.app.debug \
  --function 'com.androidcompress.app.agent.BaseCompressAppFunctionService#describeCapabilities' \
  --parameters '{}'"
```

Grant **Device library access** in Settings (Allow all) so `listDeviceMedia` / `importDeviceMedia` can open files already on the device. Without that grant, import still works from the picker or Share. Functions return job metadata, not media bytes. Gemini-as-caller is still a platform preview; you can test with `adb` or the official App Functions testing agent.

PowerShell helper (Windows). Finds `adb.exe` from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `local.properties`, or `%LOCALAPPDATA%\Android\Sdk\platform-tools`:

```powershell
# After granting library access on the phone:
.\scripts\test-app-functions.ps1
.\scripts\test-app-functions.ps1 -Query clip
.\scripts\test-app-functions.ps1 -LocalFile C:\Videos\clip.mp4
.\scripts\test-app-functions.ps1 -Path /sdcard/Download/clip.mp4 -Preset SMALLER -Container WEBM
```

## FFmpeg

Day-to-day builds use `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (FFmpeg 8.1.2, 16 KB pages, arm64-v8a, LGPL — no x264/x265). A Play Store binary can stay on that AAR or be rebuilt from FFmpegKitNext — see [docs/ffmpeg-build.md](docs/ffmpeg-build.md).
