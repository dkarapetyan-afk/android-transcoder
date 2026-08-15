# FFmpeg Play Store build

The debug/dev Gradle dependency is:

```
io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.1.7
```

That package is LGPL-3.0 and 16 KB aligned. For a store listing you should ship a binary you built yourself so you control enabled libraries and can fulfill the LGPL source offer.

## Recommended production AAR

Build [FFmpegKitNext](https://github.com/arthenica/ffmpeg-kit-next) 8.1.x with Nix and NDK r27d:

```bash
./nix-android.sh -p android-r27d --enable-mediacodec --disable-arm-v7a-neon
```

Do **not** pass `--enable-gpl`. That keeps `libx264` / `libx265` out of the Play binary.

Consume the generated local Maven repo:

```kotlin
maven { url = uri("<ffmpeg-kit-next>/prebuilt/bundle-android-aar-24-maven") }
implementation("com.arthenica:ffmpeg-kit-next:8.1.1")
```

The app talks to FFmpeg only through `FfmpegGateway`, so swapping the AAR should not require UI changes.

## LGPL

- About screen already includes an FFmpeg / LGPL notice.
- Host the corresponding FFmpeg and FFmpegKitNext source (or a written offer) with the Play listing.
- Keep `usesCleartextTraffic` false. `INTERNET` is used only for the optional Gemini extra-args helper (HTTPS to `generativelanguage.googleapis.com`).

## 16 KB page size

Confirm the shipped `.so` files are 16 KB aligned (`llvm-objdump -p` / Play pre-launch report) before uploading an app that targets API 35+.
