# Manual device checks

These cannot be fully automated in this workspace.

- MediaProjection consent each session; token reuse must not crash on API 34+.
- Stop from the in-app button, from the notification, and from the Android 15 status-bar chip / lock screen.
- Single-app capture (Android 14+) produces a usable MP4.
- Microphone recording has audible mic audio.
- Internal audio (API 29+) captures media/game playback and muxes after stop.
- Photo picker and SAF both reach the compress screen.
- Start compress on API 35+ (Pixel 10 / Android 16): must not crash with InvalidForegroundServiceTypeException.
- Hardware encoder path and software fallback (toggle hardware off).
- Device (Media3) engine: pick it on the compress screen, confirm progress, output, and cancel still work without FFmpeg.
- Advanced: CBR vs VBR, keyframe 2s, H.264 High, volume 150%, tone-map, and B-frames Off — try once on FFmpeg and once on Media3.
- Switching the Settings default engine applies to a newly imported or recorded job.
- Cancel mid-encode deletes the temp file and marks the job cancelled.
- After a successful compress, Delete original on the result screen; Settings default applies to new jobs.
- Recorded files in app cache delete reliably; a gallery pick may be refused by the provider.
- Cancel this job leaves later queued items running; Cancel all stops the whole queue.
- Output appears in the gallery and can be opened/shared.
- Low-storage import shows a clear error instead of a native crash.
- Rotation during recording does not crash the service.
- Notification tap on an in-flight encode returns to the app.
