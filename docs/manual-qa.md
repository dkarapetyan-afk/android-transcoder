# Manual device checks

These cannot be fully automated in this workspace.

- MediaProjection consent each session; token reuse must not crash on API 34+.
- Stop from the in-app button, from the notification, and from the Android 15 status-bar chip / lock screen.
- Single-app capture (Android 14+) produces a usable MP4.
- Microphone recording has audible mic audio.
- Internal audio (API 29+) captures media/game playback and muxes after stop.
- Compress a file opens one system picker; a video and an audio file both reach the compress screen.
- Share / Send a video or audio file from Photos, Files, or a messenger: Recording Compressor appears as Compress and opens the compress screen. Share two files at once creates two jobs; the first opens and the rest are in Recent.
- Start compress on API 35+ (Pixel 10 / Android 16): must not crash with InvalidForegroundServiceTypeException.
- Hardware encoder path and software fallback (toggle hardware off). After the FFmpeg 8.1 AAR swap, confirm FFmpeg still lists h264_mediacodec / libvpx / libopus.
- FFmpeg WebM video looks correct (not scrambled). Command should use libvpx-vp9 or libvpx and yuv420p, not vp9_mediacodec.
- Device (Media3) engine: pick it on the compress screen, confirm progress, output, and cancel still work without FFmpeg.
- Device (Media3) clip: set start/end on a known source, confirm the output is only that range and progress uses the clipped duration. Whole-video reset clears the clip.
- Audio only from a video (both engines): output is an AAC .m4a in Music/RecordingCompressor; Open/Share use audio MIME.
- WebM video (both engines): Container WebM, VP9 default; output is .webm in Movies/RecordingCompressor; Open/Share use video/webm. FFmpeg software fallback if hardware VP9 fails.
- WebM audio only (both engines): Container WebM + Audio only; output is Opus .webm in Music/RecordingCompressor.
- Pick an m4a/mp3 from the same picker; Audio only is locked if the source has no video.
- Advanced: CBR vs VBR, keyframe 2s, H.264 High, volume 150%, tone-map, and B-frames Off — try once on FFmpeg and once on Media3.
- Switching the Settings default engine applies to a newly imported or recorded job.
- Cancel mid-encode deletes the temp file and marks the job cancelled.
- After a successful compress, Delete original on the result screen; Settings default applies to new jobs.
- Recorded files in app cache delete reliably; a gallery pick may be refused by the provider.
- Cancel this job leaves later queued items running; Cancel all stops the whole queue.
- Output appears in the gallery and can be opened/shared.
- Clear all on Home or Library asks first, empties Recent, deletes import/encode/record cache files, and leaves Movies/Music outputs. A live screen recording stays.
- Low-storage import shows a clear error instead of a native crash.
- Combine audio and video: pick a photo then an m4a; FFmpeg and Media3 both produce a playable video the length of the audio. Media3 still-image jobs (including WebM VP9) must start — they used to crash in ImageAssetLoader without a frame rate. Pick a video then an audio file; the picture stays and the soundtrack is replaced. Clip start/end applies. WebM still works.
- Share pictures/videos plus audio files: one combine job for every picture-or-video × audio pair. The first opens and the rest are in Recent.
- After record → compress → result, Back (system or toolbar) returns to Home, not a blank white screen.
- App Functions (Android 16+): after install, `adb shell cmd app_function list-app-functions` includes `com.androidcompress.app` (or `.debug`). `describeCapabilities`, `listJobs`, `updateJobSettings`, `startJob`, and `getProgress` work on an imported READY job. Cancel one job leaves later queued items running.
- Device library access: Settings toggle requests READ_MEDIA_*. Choose Allow all. Then `listDeviceMedia` returns on-device videos and `importDeviceMedia` with a display name or `/sdcard/Download/clip.mp4` creates a READY job without the picker. Denying the permission makes those functions return a Settings grant error. The picker and Share still work without the grant.
- Rotation during recording does not crash the service.
- Notification tap on an in-flight encode returns to the app.
