# Recording Compressor

[![CI](https://github.com/dkarapetyan-afk/android-transcoder/actions/workflows/ci.yml/badge.svg)](https://github.com/dkarapetyan-afk/android-transcoder/actions/workflows/ci.yml)

Android app that records the screen and compresses recordings on-device with FFmpeg or the device encoder (Media3 Transformer).

- Kotlin, Jetpack Compose, Material 3
- Import a video or audio file via the system file picker, or share / send one (or several) from another app
- Combine a picture or video with a separate soundtrack (FFmpeg or Media3). A still image lasts as long as the audio. Home and Share both accept several pictures/videos plus several audio files in one pick and create one job per pair.
- Clear all Recent / Library jobs and leftover cache files (gallery outputs stay)
- Audio-only output: extract a video soundtrack or transcode audio to AAC (.m4a) or Opus (.webm) with FFmpeg or Media3
- WebM output: VP8/VP9/AV1 + Opus video, or Opus-only audio, on both engines
- AV1 on both engines when the device has an AV1 encoder; FFmpeg can also use libaom-av1 / libsvtav1 if the bundled build includes them
- Screen recording via MediaProjection (microphone, internal audio, or both mixed live) with pause/resume, countdown, max length, low-storage auto-stop, live region crop, grayscale, camera inset (front or rear), tap / laser / ink overlays, floating controls, Picture-in-Picture controls, and a Quick Settings tile. Internal audio can be limited to one app; mic device, echo cancel, noise suppress, gain, and ducking apply while capturing. Capture 30 or 60 fps, pick a bitrate, and write MP4 or WebM. Record already compressed (H.264/HEVC/AV1 or VP8 WebM) to skip the second encode pass. Bookmarks can become chapters or split jobs.
- Presets plus advanced encoder settings, including a grayscale filter on FFmpeg and Device (Media3)
- Optional on-device captions: Whisper tiny via sherpa-onnx, muxed as a subtitle track plus an `.srt` sidecar. The speech model downloads once (~100 MB) and audio never leaves the phone. A Transcribing notification shows progress; Cancel there skips captions and keeps the file.
- Two encode engines: FFmpeg (default, with software fallback) or Device / Media3 (hardware MediaCodec, same approach as Compressor Edge)
- Play-oriented: target API 37, scoped storage, typed foreground services, LGPL FFmpeg
- Android 16 App Functions so a system agent can import, customize, start, wait, retry, share, and discard jobs (no media bytes)
- Optional Device library access (`READ_MEDIA_*`) so that agent can list and import files already on the device without the picker
- Settings hardware test: 1-second encodes on h264/hevc/vp9/av1_mediacodec and Media3 for max resolution, 10-bit HDR, and realtime speed

## Build

Requires JDK 17+ and the Android SDK (compile SDK 37).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Release builds sign with `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` when those env vars are set. Otherwise they read `keystore.properties` at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Plaintext `release.jks` and `keystore.properties` are gitignored. Encrypted copies in `signing/` are committed; decrypt with `SIGNING_PASSPHRASE` (see [signing/README.md](signing/README.md)). If the keystore file or passwords are missing, `assembleRelease` still succeeds and writes an unsigned APK.

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

The release APK is `app/build/outputs/apk/release/` (`app-release.apk` when signed, `app-release-unsigned.apk` otherwise). The Play upload is `app/build/outputs/bundle/release/`.

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs on `master`, pull requests, and manual dispatch. It uses JDK 17, installs Android SDK 37, then runs `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `assembleRelease`. On `master` and `workflow_dispatch` it decrypts `signing/*.enc` with the `SIGNING_PASSPHRASE` repository secret, signs the release APK/AAB, and uploads them as the `app-release` artifact. Pull requests stay unsigned so fork PRs never see the key. Device checks in [docs/manual-qa.md](docs/manual-qa.md) stay manual.

## App Functions (Android 16+)

Privileged system agents can drive imported jobs without opening the UI. The service is `com.androidcompress.app.agent.CompressAppFunctionService` (`.debug` in debug builds).

Typical tools: `describeCapabilities`, `compressNow`, `waitForJob`, `waitForQueue`, `listDeviceMedia`, `importDeviceMedia`, `importDeviceMediaBatch`, `importCombineDeviceMedia`, `updateJobSettings`, `previewEncode`, `startJob`, `retryJob`, `cloneJob`, `getProgress`, `shareOutput`, `openOutput`, `discardJob`, `requestLibraryAccess`, `getEncoderCapabilities`, `getSourceInfo`.

```bash
adb shell cmd app_function list-app-functions | grep -A 20 androidcompress
adb shell "cmd app_function execute-app-function \
  --package com.androidcompress.app.debug \
  --function 'com.androidcompress.app.agent.BaseCompressAppFunctionService#describeCapabilities' \
  --parameters '{}'"
```

Grant **Device library access** in Settings (Allow all) so `listDeviceMedia` / `compressNow` can open files already on the device. `requestLibraryAccess` opens that prompt. Without the grant, import still works from the picker or Share. Functions return job metadata, not media bytes. `waitForJob` blocks at most 180 seconds; call it again if `timedOut` is true. Gemini-as-caller is still a platform preview; you can test with `adb` or the official App Functions testing agent.

PowerShell helper (Windows). Finds `adb.exe` from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `local.properties`, or `%LOCALAPPDATA%\Android\Sdk\platform-tools`:

```powershell
# After granting library access on the phone:
.\scripts\test-app-functions.ps1
.\scripts\test-app-functions.ps1 -Query clip
.\scripts\test-app-functions.ps1 -LocalFile C:\Videos\clip.mp4
.\scripts\test-app-functions.ps1 -Path /sdcard/Download/clip.mp4 -Preset SMALLER -Container WEBM
.\scripts\test-app-functions.ps1 -RelativePath Download -Kind VIDEO
```

## Automation (Tasker / MacroDroid / adb)

Exported receiver `com.androidcompress.app.agent.AutomationReceiver`. Send an explicit broadcast to this app’s package (`com.androidcompress.app` or `com.androidcompress.app.debug`):

| Action | Effect |
|---|---|
| `com.androidcompress.app.automation.COMPRESS` | Import + start encode (`uri`, `path`, or intent data). Optional extras: `preset`, `engine`, `container`, `codec`, `output`, `requestId`, `replyPackage`, … |
| `com.androidcompress.app.automation.RECORD_STOP` | Stop the active screen recording. Does not start capture. |
| `com.androidcompress.app.automation.CANCEL_QUEUE` | Cancel the running encode and every queued job. |

When the work finishes, the app broadcasts `com.androidcompress.app.automation.COMPLETED` (`status`, `jobId`, `outputUri`, `error`, `requestId`, …). Listen for that instead of polling. Paths need Device library access.

```bash
adb shell am broadcast -p com.androidcompress.app.debug \
  -a com.androidcompress.app.automation.COMPRESS \
  --es path /sdcard/Download/clip.mp4 --es preset SMALLER --es requestId t1
adb shell am broadcast -p com.androidcompress.app.debug \
  -a com.androidcompress.app.automation.RECORD_STOP
adb shell am broadcast -p com.androidcompress.app.debug \
  -a com.androidcompress.app.automation.CANCEL_QUEUE
```

## FFmpeg

Day-to-day builds use `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (FFmpeg 8.1.2, 16 KB pages, arm64-v8a, LGPL — no x264/x265). A Play Store binary can stay on that AAR or be rebuilt from FFmpegKitNext — see [docs/ffmpeg-build.md](docs/ffmpeg-build.md).

## License

This project's source is licensed under the [MIT License](LICENSE). Bundled FFmpeg remains LGPL-3.0; see [docs/ffmpeg-build.md](docs/ffmpeg-build.md).
