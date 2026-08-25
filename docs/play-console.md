# Play Console notes

Host `privacy-policy.html` at a public HTTPS URL and paste that URL into the store listing and Data safety form.

## App identity

- Name: Recording Compressor
- applicationId: `com.androidcompress.app`
- targetSdk: 37 (Play requires at least 36 for new apps from 31 August 2026)
- minSdk: 26
- Native ABI: `arm64-v8a` only (the FFmpeg 8.1 AAR does not ship 32-bit or x86)

## Data safety

Video processing is on-device. Optional Gemini extra-args generation sends text (not video) to Google if the user pastes an API key and taps Generate. Declare:

- No account
- No analytics
- Files: video files the user creates, stay on device
- Optional: text prompts sent to Google Gemini when the user uses Generate extra args
- Optional: job metadata (names, sizes, settings, progress, encode logs) may be read by a privileged on-device agent through App Functions on Android 16+. The assistant can also open the system share sheet or a viewer for a finished file; this app does not upload media. The assistant may send the user's spoken request to a server.
- Optional: an exported broadcast receiver lets on-device automation (Tasker and similar) start a compress job from a caller-granted URI or library path, stop an in-progress recording, or cancel the encode queue. A completion broadcast includes job metadata and the output content URI. It does not start screen capture or upload media.
- Sensitive permissions: microphone (optional, user-initiated), camera (optional inset), notifications, optional photo/video/audio library access, optional Bluetooth for a headset mic
- Internet: optional Gemini API only

## Foreground services

Declare the types below and attach a short screen recording that shows:

1. User starts screen capture, notification appears, user stops from the notification.
2. User starts compression, notification shows percent, user can cancel.

| Type | Why |
| --- | --- |
| `mediaProjection` | Active screen recording |
| `microphone` | Screen recording with microphone or mic + internal audio (not used for silent or internal-only capture) |
| `mediaProcessing` (API 35+) / `dataSync` (API 29–34) | User-started FFmpeg transcode that must continue if the user leaves the UI |

## Permissions justification

- `RECORD_AUDIO` — microphone, internal-audio, or mixed capture, only after the user opts in.
- `BLUETOOTH_CONNECT` / `BLUETOOTH` — optional Bluetooth microphone while recording.
- `MODIFY_AUDIO_SETTINGS` — start Bluetooth SCO for that microphone path.
- `FOREGROUND_SERVICE_MICROPHONE` — ongoing microphone capture while a screen recording continues in the background.
- `POST_NOTIFICATIONS` — ongoing record/encode status.
- `WRITE_EXTERNAL_STORAGE` maxSdk 28 — gallery export on Android 8–9 only.
- `INTERNET` — optional Gemini extra-args generator only.
- `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_IMAGES` (and `READ_EXTERNAL_STORAGE` maxSdk 32) — optional Device library access so App Functions can list and import media already on the device. Requested only from Settings. Do not add `MANAGE_EXTERNAL_STORAGE`.
- Play Photo and video permissions: this is a media-editing app. Core use case is compressing user videos/audio. The system picker remains available; library access is an optional power-user / assistant path. Declare “Media files” and show the in-app Settings toggle plus a picker-only path in the review video.

## Demo script for reviewers

1. Open the app → Compress a file → pick any short MP4 → Balanced → Start.
2. Confirm the notification and the result file in Movies/RecordingCompressor.
3. Record screen → grant capture → stop → compress the result.
