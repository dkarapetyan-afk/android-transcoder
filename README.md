# Recording Compressor

Android app that records the screen and compresses recordings on-device with FFmpeg or the device encoder (Media3 Transformer).

- Kotlin, Jetpack Compose, Material 3
- Import via the system photo picker or Storage Access Framework
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
