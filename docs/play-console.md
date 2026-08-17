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
- Sensitive permissions: microphone (optional, user-initiated), notifications
- Internet: optional Gemini API only

## Foreground services

Declare both types and attach a short screen recording that shows:

1. User starts screen capture, notification appears, user stops from the notification.
2. User starts compression, notification shows percent, user can cancel.

| Type | Why |
| --- | --- |
| `mediaProjection` | Active screen recording |
| `mediaProcessing` (API 35+) / `dataSync` (API 29–34) | User-started FFmpeg transcode that must continue if the user leaves the UI |

## Permissions justification

- `RECORD_AUDIO` — microphone or internal-audio capture, only after the user opts in.
- `POST_NOTIFICATIONS` — ongoing record/encode status.
- `WRITE_EXTERNAL_STORAGE` maxSdk 28 — gallery export on Android 8–9 only.
- `INTERNET` — optional Gemini extra-args generator only.
- Do not add `READ_MEDIA_VIDEO` or `MANAGE_EXTERNAL_STORAGE`.

## Demo script for reviewers

1. Open the app → Compress a file → pick any short MP4 → Balanced → Start.
2. Confirm the notification and the result file in Movies/RecordingCompressor.
3. Record screen → grant capture → stop → compress the result.
