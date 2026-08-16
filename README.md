# Recording Compressor

Android app that records the screen and compresses recordings on-device with FFmpeg or the device encoder (Media3 Transformer).

- Kotlin, Jetpack Compose, Material 3
- Import a video or audio file via the system file picker, or share / send one (or several) from another app
- Clear all Recent / Library jobs and leftover cache files (gallery outputs stay)
- Audio-only output: extract a video soundtrack or transcode audio to AAC (.m4a) or Opus (.webm) with FFmpeg or Media3
- WebM output: VP8/VP9 + Opus video, or Opus-only audio, on both engines
- Screen recording via MediaProjection (microphone or internal audio)
- Presets plus advanced encoder settings
- Two encode engines: FFmpeg (default, with software fallback) or Device / Media3 (hardware MediaCodec, same approach as Compressor Edge)
- Play-oriented: target API 36, scoped storage, typed foreground services, LGPL FFmpeg

## Build

Requires JDK 17+ and the Android SDK (compile SDK 36).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## FFmpeg

Day-to-day builds use a 16 KB–aligned FFmpegKit LTS AAR from Maven Central. A Play Store binary should be rebuilt from FFmpegKitNext without GPL encoders — see [docs/ffmpeg-build.md](docs/ffmpeg-build.md).
