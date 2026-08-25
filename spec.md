# Recording Compressor — implementation spec

This document describes **what the app currently does**, as implemented in this repository. It is not a roadmap.

- **App name:** Recording Compressor
- **Application ID:** `com.androidcompress.app` (debug: `com.androidcompress.app.debug`)
- **Version:** 1.2.0 / versionCode 3
- **License:** MIT for app source. Bundled FFmpeg remains LGPL-3.0.

The app records the screen and compresses video/audio **on the device**. It does not upload recordings. Optional HTTPS is used for the Gemini extra-args helper and to download the Whisper tiny / Silero VAD models on first captions use. Audio is never uploaded.

---

## 1. Platform and build

| Item | Value |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| minSdk | 26 |
| compileSdk / targetSdk | 37 |
| ABI | `arm64-v8a` only |
| JDK | 17 |
| AGP | 9.3.1 |
| Kotlin | 2.4.10 |
| KSP | 2.3.11 |
| Compose BOM | 2026.08.00 |
| Room | 2.8.4 |
| DataStore | 1.2.1 |
| Media3 | 1.11.0 (transformer, effect, common, muxer) |
| FFmpeg | `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (FFmpeg 8.1.x, 16 KB pages, LGPL) |
| Captions | `com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.6` (GitHub Releases AAR) + Whisper tiny int8 + Silero VAD, downloaded on first use into app files |
| App Functions | `androidx.appfunctions` 1.0.0-alpha10 |
| CI | GitHub Actions (`.github/workflows/ci.yml`): debug APK, unit tests, lint, release APK. Signed APK/AAB on `master` / `workflow_dispatch` via encrypted `signing/*.enc` and the `SIGNING_PASSPHRASE` secret. |

**Release signing.** Env vars `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` win; otherwise `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Missing keystore still produces an unsigned APK. Plaintext `release.jks` and `keystore.properties` are gitignored. AES-256-CBC ciphertext is committed as `signing/release.jks.enc` and `signing/keystore.properties.enc`; decrypt with `SIGNING_PASSPHRASE`.

**Packaging.** R8 minify + shrink resources on release. `usesCleartextTraffic` is false. JNI uses non-legacy packaging.

**Debug vs release.** Debug appends `.debug` to the application ID and `-debug` to the version name.

---

## 2. Product constraints (enforced)

- Encode and record stay on-device. No analytics or ads SDKs.
- No GPL encoders (`libx264` / `libx265`). FFmpeg H.264 uses `libopenh264` or `mpeg4`.
- FFmpeg never uses `h264_mediacodec` for job encodes (scrambled frames). Device hardware H.264 is the Media3 engine.
- FFmpeg prefers `libvpx` / `libvpx-vp9` over `vp8_mediacodec` / `vp9_mediacodec` (same class of garbage-frame bug).
- HEVC and AV1 hardware only, unless the FFmpeg build lists `libaom-av1` or `libsvtav1`.
- No `MANAGE_EXTERNAL_STORAGE`. Gallery write is MediaStore (`Movies/RecordingCompressor` or `Music/RecordingCompressor`). Optional `READ_MEDIA_*` is for agent/library listing only.
- App Functions return job **metadata**, never media bytes. They do not start/stop screen recording, delete gallery files, clear all history, or get/set the Gemini API key. The exported automation receiver can stop an in-progress recording and cancel the encode queue; it cannot start capture (MediaProjection consent still has to happen on the device).
- Extra FFmpeg args and command templates cannot add extra file paths, URLs, or extra inputs beyond the job’s source (and optional combine soundtrack).

---

## 3. Architecture

```
UI (Compose screens)
  → ViewModels → AppContainer
      → JobRepository (Room)
      → PreferencesRepository (DataStore)
      → JobImporter / InputResolver / MediaProbe / MediaStoreExporter
      → ScreenRecordService / CompressService
      → FfmpegKitGateway / Media3Transcoder
      → JobAgent (App Functions + automation receiver)
```

**Services**

| Component | Role |
|---|---|
| `ScreenRecordService` | MediaProjection capture. FGS types: `mediaProjection\|microphone\|camera` |
| `CompressService` | FIFO encode queue. FGS types: `mediaProcessing\|dataSync` |
| `RecordTileService` | Quick Settings tile |
| `TapHighlightService` | Accessibility overlay (taps / laser / ink) |
| `CompressAppFunctionService` | Android 16 App Functions (`BIND_APP_FUNCTION_SERVICE`) |
| `AutomationReceiver` | Exported Tasker/MacroDroid/`am broadcast` entry (`COMPRESS`, `RECORD_STOP`, `CANCEL_QUEUE`). Completion is `…COMPLETED`. |
| `RecordConsentActivity` | Transparent MediaProjection consent |
| `MainActivity` | Single-top, PiP-capable, share/shortcut/VIEW entry |

**Local storage**

- Room table `compress_jobs`
- DataStore `settings`
- Cache dirs: `imports/`, `encode/`, `record/`, `hwtest/`
- Encode logs on disk (last ~40 files)
- FileProvider `${applicationId}.files`

**History policy**

- Keep at most 40 jobs; drop rows older than 30 days.
- Never auto-delete `RUNNING`, `QUEUED`, or `RECORDING`.
- Clear-all on Home/Library deletes history + cache, leaves gallery outputs, and keeps an in-progress recording job.

---

## 4. Data model

### 4.1 Jobs

`CompressJob`

| Field | Meaning |
|---|---|
| `id` | UUID string |
| `type` | `RECORD`, `IMPORT`, `COMPRESS`, `COMBINE` |
| `status` | `DRAFT`, `RECORDING`, `READY`, `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `sourceUri` / `audioUri` | Content or file URI; `audioUri` set for combine |
| `stillImage` | Picture + soundtrack combine |
| `outputUri` / `outputBytes` | Published MediaStore result |
| `settingsJson` | Serialized `EncodeSettings` |
| `deleteSourceAfter` / `sourceDeleted` | Post-success source delete |

### 4.2 Encode settings

`EncodeSettings`

| Field | Default / notes |
|---|---|
| `preset` | `SMALLER`, `BALANCED` (default), `HIGHER` |
| `engine` | `FFMPEG` (default) or `MEDIA3` |
| `output` | `VIDEO` or `AUDIO` |
| `container` | `MP4` or `WEBM` |
| `codec` | `H264`, `HEVC`, `VP8`, `VP9`, `AV1` — coerced to a container-legal default |
| `maxHeight` | 720 / 1080 / 1440 from preset; UI also Off / 2160 / 480 / 360 |
| `fpsCap` | 30 on SMALLER/BALANCED; none on HIGHER; UI Off / 60 / 30 / 24 |
| `preferHardware` | true |
| `videoBitrateKbps` | 1500 / 2500 / 6000 by preset; UI slider 400–20000 when fit-to-size is off |
| `audio` | `COPY`, `AAC_64`, `AAC_96`, `AAC_128`, `AAC_192`, `MUTE` |
| `bitrateMode` | `CBR` or `VBR` (fit-to-size forces CBR) |
| `keyframeInterval` | `AUTO`, `SEC_1`, `SEC_2`, `SEC_5` |
| `h264Profile` | `AUTO`, `BASELINE`, `MAIN`, `HIGH` |
| `hdrMode` | `KEEP` or `TONE_MAP` |
| `audioVolumePercent` | 10–400, default 100 |
| `fastStart` | MP4 `+faststart` |
| `bFrames` | `AUTO`, `NONE`, `ONE`, `TWO` |
| `ffmpegExtraArgs` | Sanitized extra flags |
| `ffmpegCommandOverride` | Template with `INPUT` / `AUDIO` / `OUTPUT` / `PASSLOG` |
| `clipStartMs` / `clipEndMs` | Applied for FFmpeg, Media3, audio-only, and combine |
| `targetSizePreset` / `targetSizeBytes` | Fit-to-size |
| `twoPass` | FFmpeg 2-pass VBR (ignored by Media3 and audio-only) |
| `grayscale` | Black-and-white. FFmpeg `format=gray` (YUV imports and RGB stills). Combine jobs put it on `[0:v]…[v]` via `-filter_complex` so the soundtrack input cannot bypass it. Media3 `RgbFilter` (including still+audio). Ignored by audio-only. Also merged into extra `-vf` and command overrides. |
| `captions` | Off by default. After a successful encode, extract 16 kHz mono PCM (MediaCodec first, FFmpeg fallback), run Silero VAD + Whisper tiny (sherpa-onnx) on-device, write SRT, mux `mov_text` (MP4) or `webvtt` (WebM), and publish an `.srt` sidecar. MUTE skips it. A captions failure leaves the video and does not fail the job. First use downloads ~100 MB of models over HTTPS. Direct-encode recordings run the same pass at stop. A separate Transcribing notification tracks download / extract / Whisper / mux with Cancel; that skip keeps the video. Encode Cancel still cancels the job. Media3 WebM Opus CodecPrivate is rewritten from Android’s `AOPUSHDR` blob to an RFC 7845 OpusHead so FFmpeg can mux subtitles; PCM extract still prefers MediaCodec. |

**Preset table**

| Preset | Height cap | FPS cap | Video kbps | Audio |
|---|---|---|---|---|
| SMALLER | 720 | 30 | 1500 | AAC 96 |
| BALANCED | 1080 | 30 | 2500 | AAC 128 |
| HIGHER | 1440 | none | 6000 | AAC 192 |

All presets start as H.264 + hardware-preferred + FFmpeg unless the Settings default engine is Media3.

**Container / codec**

- MP4: H.264, HEVC, AV1. Audio-only → `.m4a` AAC.
- WebM: VP8, VP9 (default), AV1. Audio-only → Opus `.webm`.
- Switching container remaps an incompatible codec (WebM → VP9, MP4 → H.264).

**Audio COPY**

- MP4/M4A: copy if source is AAC.
- WebM: copy if source is Opus or Vorbis.
- Otherwise re-encode.

---

## 5. Screens and navigation

Routes: `home`, `record`, `compress/{jobId}`, `progress/{jobId}`, `result/{jobId}`, `library`, `log/{jobId}` (`log/last` for latest), `settings`, `settings/library`, `settings/hardware`, `about`.

| Screen | Implemented behavior |
|---|---|
| **Home** | Record; pick one video/audio; multi-pick combine; Recent list; batch recipe chips when ≥1 READY/QUEUED job; Last log; Library; Settings; About; Clear all (confirm) |
| **Record** | Capture options, live timer, pause/resume, bookmarks, stop |
| **Compress** | Presets, fit-to-size, engine, output, container, clip, advanced, Start |
| **Progress** | Current + queued jobs, pass label, cancel this / cancel all, batch chips for **queued** jobs only |
| **Result** | Open, Share, Delete original, View log; sizes + bytes saved. Recordings write a job log (region crop pixels, live vs software crop). |
| **Library** | Full job list, open by status, discard one, clear all |
| **Log** | Encode or recording log text |
| **Settings** | Engine, preset, remember advanced, auto-compress, delete original, stall timeouts, library access, hardware test, Gemini key |
| **Hardware test** | 1-second encodes per listed encoder |
| **About** | Privacy blurb, FFmpeg LGPL, Media3, HEVC notice |

Share / SEND / SEND_MULTIPLE / VIEW land on Home, import, then open the first job’s compress screen. Back from Progress/Result pops to Home.

---

## 6. Import, share, combine

**Single import.** System picker (`OpenDocument`) or share of `video/*` or `audio/*`. Probed into a READY job with Settings default preset/engine (or remembered advanced settings).

**Combine.** Home multi-select and Share accept pictures, videos, and audio together.

Pairing (`CombinePairing`):

1. Every picture-or-video × every audio file → one COMBINE job per pair.
2. If there is no audio but both an image and a video, first image + first video (video used as soundtrack).
3. A still image lasts as long as the audio. A video + audio keeps the picture and replaces the soundtrack; duration is `min(video, audio)`.
4. First job opens Compress; the rest sit in Recent.
5. Picking only one side is an error (no second picker).

**Storage on import.** Copying a content URI into cache requires `sourceBytes + 50 MB` free. Encode later reserves `2 × estimated output + 50 MB` (falls back to `2 × source` only when duration is unknown), so a large 4K file that compresses small is not rejected solely because 2× source would not fit.

**Clone.** App Functions / internal copy: new READY job, cache files remapped to the new id.

---

## 7. Screen recording

Capture is MediaProjection + MediaRecorder. Consent is requested every session (`RecordConsentActivity`). Options persist in DataStore.

### 7.1 Capture options

| Option | Behavior |
|---|---|
| Audio | None, Microphone, Internal (API 29+), Both mixed live |
| Internal → Choose app | Limit internal audio to one package (Android 14+ single-app capture) |
| Mic device | Auto, built-in, Bluetooth (API 23+) |
| Echo cancel / noise suppress | On by default while capturing |
| Mic / app gain | 0–200%, live |
| Duck app audio | Lower internal while speaking |
| Isolate tracks | Mic + internal as two audio streams (Voice then System) instead of a mix |
| Resolution | 720p, 1080p, display size |
| Frame rate | 30 or 60 |
| Bitrate | Auto from pixels, or 1000–50000 kbps |
| Container | MP4 or WebM (WebM records VP8) |
| Codec (direct encode) | H.264, HEVC, AV1 (API 33+ when available) |
| Record already compressed | Writes a gallery file and opens Result; skips compress / auto-compress |
| Countdown | 0, 3, 5, 10 (clamped 0–15); overlay number is not in the file |
| Max length | 0–180 minutes; auto-stop leaves a usable file |
| Low-storage auto-stop | Default on; fires after ≥3 s and ≥1 KB written when free space < 200 MB |
| Region crop | Overlay box after consent; live crop aligned to 16 px; software crop at stop if live crop fails. Result → View log lists overlay size, normalized region, live/software crop pixels, whether live GL crop ran, and the FFmpeg command if a fallback crop ran. |
| Camera inset | Front/rear, rect/round, S/M/L, draggable; optional hide while paused |
| Floating bubble | Overlay pause/stop |
| PiP controls | Pause / mark / stop in a system PiP window |
| Cover status bar | Black bar burned into captured frames (live GL, FFmpeg `drawbox` if that path fails). Overlay windows cannot sit above system UI, so this does **not** use “Display over other apps.” OS indicators still show on the phone. |
| Grayscale | Rec.709 luma in the live GL pipe (same path as region crop / status-bar cover; the pipe also runs for grayscale alone). FFmpeg `format=gray` at stop if live GL fails. RECORD jobs stamp `EncodeSettings.grayscale` so Compress starts with the same toggle. Direct encode writes gray to the gallery file. Result → View log lists `grayscale`, `liveGray`, and `softwareGray`. |
| Quiet notification | FGS notice at `IMPORTANCE_MIN` (cannot be removed) |
| Taps / laser / ink | Accessibility service; ripples, red laser while pointer down, yellow ink until stop |
| Bookmarks | Off, Chapters (`FFMETADATA1`), or Split jobs (extra READY/SUCCEEDED rows, min segment 400 ms) |

Pause/resume works from Record, notification, QS tile, bubble, and PiP. Paused time is omitted from the file.

**Direct encode bitrate.** Auto ≈ `pixels × factor × fpsMul`, factor 4 (H.264 / non-direct) or 2 (HEVC/AV1), doubled at ≥50 fps, clamped 2–20 Mbps (16 Mbps when not direct).

### 7.2 Quick Settings tile

- Inactive tap: start capture using last options (consent still appears).
- Active tap: pause/resume.
- Long-press: Record screen.

### 7.3 Isolated dual-track audio

When audio is Both **and** Isolate tracks is on:

- Capture writes separate mic and internal PCM.
- Post-process maps mic then internal as two AAC/Opus streams (`-map` both).
- Later FFmpeg video encodes map **all** audio streams (`-map 0:a`).
- Media3 Transformer muxes one audio stream natively; if the source has more than one audio track, a separate extract → encode → mux pass keeps every track.

If isolate is off, mic + internal are mixed live into one WAV and muxed without re-encoding video (unless a software region crop, status-bar cover, or grayscale needs a video re-encode).

### 7.4 Auto-compress after record

Settings toggle. After a normal (non-direct) recording, the job opens Compress with Balanced or remembered settings, or starts encode immediately when the toggle is on. Direct-encode recordings skip this and go to Result.

---

## 8. Encode engines

### 8.1 FFmpeg (default)

Command built by `FfmpegCommandBuilder`. Inputs resolved through FFmpeg-Kit SAF (`saf:N.ext`) or a cache copy.

**Video encoder selection**

| Codec | Encoder |
|---|---|
| H.264 | `libopenh264` if listed, else `mpeg4`. Never `h264_mediacodec`. |
| HEVC | `hevc_mediacodec` if preferred + present, else OpenH264 / mpeg4 |
| VP9 | `libvpx-vp9` preferred; `vp9_mediacodec` only if libvpx missing |
| VP8 | `libvpx` preferred |
| AV1 | `av1_mediacodec` if preferred + present, else `libaom-av1`, else `libsvtav1`, else VP9 (WebM) or H.264 (MP4) |

**Other FFmpeg behavior**

- Clip start/end become input `-ss` / `-t` on video jobs (before `-i`, both 2-pass legs). Audio-only puts the same flags after `-i`. Combine already seeks each input.
- Software paths force CFR `-r` (screen recordings at 90k tbr otherwise drop/duplicate). Hardware mediacodec does not get `-r` unless an FPS cap requires it (avoids a hang after the first stats tick).
- Scale uses even dimensions, bt709 TV range. Grayscale adds `format=gray` after scale. Tone-map / WebM / yuv420p add `format=yuv420p`. Combine (picture or video + soundtrack) uses `-filter_complex [0:v]…[v]` and maps `[v]`; a lone `-vf` plus `-map 0:v:0` can leave the picture unfiltered. Extra `-vf` and command overrides get grayscale merged into the last video filter or the `[0:v]` chain.
- CBR (and fit-to-size) sets min/maxrate on libvpx/libaom, or maxrate+bufsize on other encoders. Skipped on 2-pass.
- HEVC tagged `hvc1`; AV1 in MP4 tagged `av01`.
- Audio: AAC in MP4, `libopus` in WebM. Volume filter when not 100%. Mute drops audio. Combine maps `[v]` (filtered picture) or `0:v:0` plus `1:a:0`.
- Faststart on MP4.
- Extra args inserted before the output path.

**SAF + multi-session.** FFmpeg-Kit `safClose` deletes the `saf:N` mapping when a session ends. Reusing it on pass 2 / retry yields an empty pipe (`EBML header parsing failed`). The encoder copies content URIs to cache before 2-pass, and `refreshFfmpegInput` mints a new SAF id between sessions otherwise.

**Fallbacks after failure** (not cancelled):

- 2-pass fail → retry same encoder one-pass, then encoder/pix_fmt ladder.
- WebM: AV1 hardware → libaom/SVT → libvpx-vp9 → libvpx; vp9_mediacodec → libvpx-vp9; vp8_mediacodec → libvpx.
- MP4: nv12 → yuv420p; AV1 hardware → libaom/SVT; other mediacodec → libopenh264 → mpeg4.

**Pass 1 empty output.** Pass 1 uses `-f null /dev/null` (not stdout `-`; FFmpeg-Kit owns stdout). If logs say nothing was encoded / `video:0kB`, pass 1 is treated as failure even if FFmpeg exits 0.

### 8.2 Device / Media3

Hardware MediaCodec via Transformer (same idea as Compressor Edge).

- Video MIME: AVC, HEVC, VP8, VP9, AV1.
- Audio MIME: AAC or Opus.
- Scale, frame-rate cap, clip, volume, CBR preference, I-frame interval, H.264 profile, HDR tone-map, B-frames, grayscale (`RgbFilter.createGrayscaleFilter()`), still-image (frame rate required so ImageAssetLoader does not crash).
- WebM uses a wrapped `WebmMuxer` that supplies missing language, rewrites Android `AOPUSHDR` Opus CSD to an RFC 7845 OpusHead, and swallows unsupported metadata.
- Fallback: WebM → VP9; MP4 → H.264. AV1 Media3 failure falls back that way.
- 2-pass is ignored. Grayscale is applied.

### 8.3 Clip window

Start/end in ms; minimum 100 ms. Applied on FFmpeg video (`-ss` / `-t` before `-i`, including both 2-pass legs), Media3 (`ClippingConfiguration`), audio-only, and combine. Clip controls show on the Compress screen for every job.

### 8.4 Bitrate scaling (non-fit-to-size)

Requested kbps is treated as the bitrate at the preset’s reference height (720 / 1080 / 1440). Actual bitrate scales with `outH² / refH²`, clamped 200–40000 kbps.

---

## 9. Fit-to-size

Presets (binary MiB, 1024²):

| Name | Bytes |
|---|---|
| OFF | — |
| DISCORD | 10 MiB |
| WHATSAPP | 16 MiB |
| WHATSAPP_64 | 64 MiB |
| GMAIL | 25 MiB |
| CUSTOM | 256 KiB–2 GiB |

Named video presets also force MP4 + H.264 + CBR, FPS cap at least 30, height 720 (WhatsApp) or 1080, WhatsApp audio AAC 96 and H.264 Baseline. COPY becomes AAC 128. MUTE stays MUTE.

Formula:

```
video_bps = (targetBytes × 8 / duration_sec) − audio_bps − muxer_overhead_bps
```

Muxer overhead = 64 KB header **plus** 4% encoder-overshoot, so Discord / WhatsApp / Gmail still accept the file.

- Video kbps clamped 200–40000.
- Audio-only fit uses the same budget minus overhead, audio 32–512 kbps.
- COPY counts as 128 kbps in the budget.
- UI shows calculated bitrate and warns if the estimate still exceeds the cap.

---

## 10. FFmpeg 2-pass VBR

- Advanced toggle on FFmpeg video jobs. Turns bitrate mode to VBR unless fit-to-size is on (fit stays CBR and 2-pass is not used for the ladder the same way).
- **Supported encoders:** `mpeg4`, `libvpx`, `libvpx-vp9`, `libaom-av1`, `libsvtav1`. Not `libopenh264` or `*_mediacodec`.
- Skipped for audio-only and still-image combines (`twoPassActive` false; UI explains skip).
- Pass 1: `-pass 1 -an -f null /dev/null`, `fps=` in the filter graph (libvpx otherwise dup/drops every frame), `-stats_period 0.25`.
- Pass 2: `-pass 2` with audio.
- Progress 0–48% pass 1, then 48–99% pass 2.
- Stats files under `cache/encode/{jobId}.2pass*`. Deleted after the job.

---

## 11. Progress, notifications, stall timeout

### 11.1 Progress sources (FFmpeg)

Null muxer reports `time=N/A`. FFmpeg `\r` stderr often never reaches FFmpeg-Kit’s LogCallback until the process ends.

Implemented sources, polled every 250 ms:

1. Injected `-progress {jobId}.ffprogress` (`out_time`, `out_time_us`, `frame=`, `progress=continue`).
2. `EncodeStats.videoFrameNumber` and log `frame=` / `time=`.
3. Wall-clock fallback while `progress=continue` and no media time, capped at 95% of duration.

### 11.2 Stall watchdog

Inactivity timeout, **not** a maximum encode duration. A long video is fine if the progress file keeps updating.

| Pref | Default | Used when |
|---|---|---|
| `stallTimeoutSec` | 20 | One-pass FFmpeg |
| `twoPassStallTimeoutSec` | 120 | Any FFmpeg session with a pass message (pass 1 and pass 2) |

- Written by Settings fields and `setAppDefaults`. Same DataStore.
- Null App Function fields leave the value unchanged.
- **Not clamped.** The stored integer is used as `seconds * 1000` ms.
- Settings UI accepts digits (up to 9). Unparseable input reverts.
- Unused on Media3.
- Distinct from App Function `waitForJob` / `compressNow` `timeoutSec` (5–180, default 45), which only bounds how long the agent waits.

### 11.3 Live updates

On Android 16, the encode FGS notification uses `NotificationCompat.ProgressStyle` plus a short status-bar chip (`{n}%`, max 7 chars). Queue progress is `0..100` units per slot (3 jobs → 0..300). Two-pass splits the current slot at 50%.

Record notification: elapsed time, Stop, Pause/Resume, optional Mark; quiet channel optional.

Cancel this job vs cancel all: later queued items keep running unless cancel-all.

Wake lock held while the encode service drains the queue.

---

## 12. Queue and batch recipes

FIFO by `queuedAt` then `createdAt`. One encode at a time.

**Batch recipes** (Home: READY+QUEUED; Progress: QUEUED only; running job unchanged):

| Chip | Preset | Container |
|---|---|---|
| Smaller | SMALLER | keep |
| Balanced | BALANCED | keep |
| Higher | HIGHER | keep |
| 720p WebM | SMALLER | WEBM |
| 1080p WebM | BALANCED | WEBM |

Apply keeps clip, extra args, command override, two-pass, volume, and similar advanced fields; clears fit-to-size; forces VIDEO on combine and AUDIO on audio-only sources.

`startReadyJobs` queues READY jobs oldest-first (limit 1–40).

---

## 13. Extra FFmpeg args, command template, Gemini

**Extra args** (`ExtraArgsSanitizer`)

- Max 800 characters, 40 tokens.
- Flags only; cannot include `-i`, `-map`, filter_complex/lavfi, paths, URLs, concat, output filenames, `-pass` / `-progress`, help/version listings, etc.

**Command override** (`FfmpegCommandTemplate`)

- Placeholders `INPUT`, `AUDIO`, `OUTPUT`, `PASSLOG`.
- At most two `-i`. Paths injected only at encode start.
- Max 2500 characters, 80 tokens.
- Overrides skip 2-pass (runs the template as a single session).

**Gemini helper** (Compress advanced, optional)

- API key in Settings (password field). Not exposed via App Functions.
- User describes an extra encode change; the app posts settings + prompt (not the media file) to `generativelanguage.googleapis.com`.
- Models tried in order: `gemini-3.7-flash`, `gemini-flash-latest`, `gemini-3.6-flash`, `gemini-3.5-flash`, `gemini-3-flash-preview`, `gemini-2.5-flash`, then lite variants.
- Reply is parsed as extra args and run through the same sanitizer.

`INTERNET` exists only for this.

---

## 14. Output and source deletion

Successful encodes are published to MediaStore:

- Video → `Movies/RecordingCompressor` (`video/mp4` or `video/webm`)
- Audio → `Music/RecordingCompressor` (`audio/mp4` / `.m4a` or `audio/webm`)

Temp encode files are deleted after publish. Import copies deleted after the job.

**Delete original**

- Settings default applies to **new UI jobs**.
- Result screen can delete after success.
- App Function `startJob` / `compressNow` default `deleteSourceAfter=false` even if Settings is on.
- Recordings this app created can be deleted; gallery picks may be refused by the provider.
- Combine tries both visual and soundtrack URIs, never the published output.

Cancel mid-encode deletes the temp file and marks `CANCELLED`.

---

## 15. Settings (app-wide)

| Setting | Default | Notes |
|---|---|---|
| Default engine | FFmpeg | New imports/recordings |
| Default preset | Balanced | |
| Remember advanced | off | Restore last compress advanced JSON |
| Auto-compress after record | off | |
| Delete original after compress | off | New jobs only |
| One-pass stall timeout | 20 s | Unclamped integer seconds |
| 2-pass stall timeout | 120 s | Unclamped integer seconds |
| Device library access | off | `READ_MEDIA_VIDEO/AUDIO/IMAGES`; user must Allow all |
| Gemini API key | empty | Local only |
| Last record options | RecordOptions() | Last audio mode, resolution, and full option JSON |
| Encoder caps cache | probed | |
| Last hardware profile | — | Re-shown on Hardware test |

Settings and `getAppDefaults` / `setAppDefaults` share this DataStore. Changing defaults does not rewrite existing jobs.

---

## 16. Hardware test

Settings → Test device hardware.

For each available target, encode a generated 1-second clip and report advertised max size, verified size, 10-bit/HDR flags, and realtime speed (`speedX`). Stop cancels. `cache/hwtest` is deleted afterward.

Targets (when the encoder exists): `h264_mediacodec`, `hevc_mediacodec`, `vp9_mediacodec`, `av1_mediacodec`, plus Media3 H.264/HEVC/AV1 as `media3` / `media3_av1`. Media3 is always listed.

---

## 17. App shortcuts

Static (`res/xml/shortcuts.xml`):

| ID | Action |
|---|---|
| `record` | Open Record |
| `compress_latest` | Open Compress on the latest job source, else latest MediaStore video (needs library grant) |
| `extract_audio` | Same source, audio-only |

Dynamic shortcut `dynamic_latest_video` shows the latest display name (short ≤10 chars, long ≤25). Usage is reported to `ShortcutManager`.

---

## 18. App Functions (Android 16+)

Service: `com.androidcompress.app.agent.CompressAppFunctionService` (`.debug` in debug builds). Schema via KSP (`appfunctions:aggregateAppFunctions=true`). Requires a privileged caller (`BIND_APP_FUNCTION_SERVICE`).

Functions return metadata. Share/open use the system sheet/viewer. Gemini-as-caller is a platform preview; `adb shell cmd app_function` works.

### 18.1 Tools

| Function | What it does |
|---|---|
| `describeCapabilities` | Summary, workflow, enum lists, restrictions, library note |
| `listPresets` | SMALLER / BALANCED / HIGHER snapshots |
| `listJobs` | Status filter, limit 1–40 (default 20); no URIs |
| `getJob` | Full settings snapshot |
| `getQueue` | Running first, then FIFO queued, READY count |
| `getProgress` | Job or current running |
| `getEncodeLog` | Tail 256–16000 chars (default 4000) |
| `applyPreset` | Reset to preset; not while queued/running/recording |
| `updateJobSettings` | Partial patch; preset first then overlay |
| `previewEncode` | FFmpeg command or Media3 label + size estimate; does not start |
| `startJob` | Optional patch; `deleteSourceAfter` default false |
| `startReadyJobs` | Limit 1–40, optional patch each |
| `applyToQueue` | Batch recipe on READY+QUEUED or queued-only |
| `cancelJob` / `cancelQueue` | One job vs entire queue |
| `listDeviceMedia` | VIDEO/AUDIO/IMAGE/ANY, query, relativePath, date, duration; needs grant |
| `importDeviceMedia` | URI, `/sdcard/...`, `file://`, or exact display name |
| `importFile` | Content URI (picker/Share works without grant) |
| `importCombine` | Picture/video + soundtrack URIs |
| `importDeviceMediaBatch` | Up to 40 paths; partial success |
| `importCombineDeviceMedia` | Paths/URIs; needs grant |
| `getAppDefaults` / `setAppDefaults` | Engine, preset, auto-compress, remember advanced, delete original, stall timeouts |
| `compressNow` | Import + optional patch + start; optional wait |
| `waitForJob` / `waitForQueue` | Block 5–180 s (default 45); `timedOut` → call again |
| `retryJob` | FAILED / CANCELLED / SUCCEEDED; does not delete gallery |
| `cloneJob` | Second READY job, optional different settings |
| `discardJob` | History + cache; gallery stays |
| `shareOutput` / `openOutput` | System sheet / viewer |
| `requestLibraryAccess` | Opens Settings + permission prompt |
| `getEncoderCapabilities` | Hardware/software encoder flags |
| `getSourceInfo` | Duration, size, fps, audio; no URIs |

**Not implemented as App Functions:** screen record start/stop, delete gallery, clear-all history, Gemini key get/set, returning media bytes.

### 18.2 Settings patch (`JobSettingsUpdate`)

Null/omitted fields unchanged. Agent-side sanitizers:

| Field | Range / rule |
|---|---|
| `maxHeight` | 144–4320, or `clearMaxHeight` |
| `fpsCap` | 1–120, or `clearFpsCap` |
| `videoBitrateKbps` | 100–40000; also clears fit-to-size |
| `audioVolumePercent` | 10–400 |
| `targetSizeBytes` | 262144–2147483648 for CUSTOM |
| `clipEndMs` | Must be > start; `clearClip` / `clearClipEnd` |
| Extra args / command | Same sanitizers as the UI |

Cannot edit `QUEUED`, `RUNNING`, or `RECORDING`. Cannot start those either. Retry requires FAILED/CANCELLED/SUCCEEDED and an existing source.

### 18.3 Agent wait vs encode stall

| Timeout | Bounds | Meaning |
|---|---|---|
| `timeoutSec` on wait/compressNow | 5–180, default 45 | How long the **function call** blocks |
| `stallTimeoutSec` / `twoPassStallTimeoutSec` | stored as given | How long FFmpeg may sit with **no progress** before the encode is cancelled |

### 18.4 Automation broadcasts (Tasker / MacroDroid / adb)

Exported receiver `com.androidcompress.app.agent.AutomationReceiver`. Actions are the same in debug and release; set the broadcast package to `com.androidcompress.app` or `com.androidcompress.app.debug`.

Inbound (manifest, `android:exported="true"`):

| Action | What it does |
|---|---|
| `com.androidcompress.app.automation.COMPRESS` | Same as App Function `compressNow` (import + start). Does not block. Source: extra `uri` / `path` / `file`, `Intent` data, `EXTRA_STREAM`, or clip data. Settings extras match `JobSettingsUpdate` field names (`preset`, `engine`, `container`, `codec`, `output`, `clipStartMs`, `twoPass`, `grayscale`, `captions`, …). `deleteSourceAfter` defaults false. Optional `requestId`, `replyPackage`. Paths need Device library access. |
| `com.androidcompress.app.automation.RECORD_STOP` | Stops the active screen recording (same as the notification Stop). Does not start capture. |
| `com.androidcompress.app.automation.CANCEL_QUEUE` | Same as App Function `cancelQueue`. |

Outbound: `com.androidcompress.app.automation.COMPLETED` with extras `action`, `requestId`, `jobId`, `status`, `displayName`, `message`, `error`, `outputUri`, `outputBytes`, `durationMs`, `type`, `count`. If `replyPackage` was set on the command, the completion is explicit to that package and a content `outputUri` is granted read. COMPRESS waits until SUCCEEDED/FAILED/CANCELLED. RECORD_STOP waits until the recording job is READY (auto-compress off) or terminal (auto-compress on, including the encode). CANCEL_QUEUE completes immediately. Does not return media bytes.

---

## 19. Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE*` (projection, mic, camera, media processing, data sync) | Record + encode FGS |
| `POST_NOTIFICATIONS` / `POST_PROMOTED_NOTIFICATIONS` | Progress; Android 16 live chip |
| `RECORD_AUDIO` | Mic / internal / both |
| `CAMERA` | Facecam inset (optional feature) |
| `SYSTEM_ALERT_WINDOW` | Region, facecam, bubble |
| `WAKE_LOCK` | Encode |
| `MODIFY_AUDIO_SETTINGS` | Mix / duck / AEC |
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | Optional BT mic |
| `INTERNET` | Gemini extra args only |
| `READ_MEDIA_VIDEO/AUDIO/IMAGES` | Optional library listing |
| `READ_EXTERNAL_STORAGE` maxSdk 32 / `WRITE_EXTERNAL_STORAGE` maxSdk 28 | Legacy MediaStore |

Camera hardware is `required=false`.

---

## 20. Privacy

Documented in `privacy-policy.html` and About:

- No accounts, ads, or analytics.
- Recordings never leave the device except via the user-picked share sheet.
- Gemini (optional) receives prompt + encode settings, not the file.
- App Functions return metadata; a system assistant may process the user’s spoken request off-device.
- Accessibility service draws overlays during recording only; it does not read screen text.

---

## 21. FFmpeg binary policy

Day-to-day Gradle dependency: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (LGPL, arm64, 16 KB aligned). Play listing guidance in `docs/ffmpeg-build.md`: prefer a self-built FFmpegKitNext **without** `--enable-gpl`. The app talks to FFmpeg only through `FfmpegGateway`.

About includes FFmpeg/LGPL, Media3, and HEVC notices.

---

## 22. Unit tests (implemented)

Covered areas include: FFmpeg command builder (including `/dev/null` pass 1, fps in vf, stats_period, grayscale `format=gray`, combine `filter_complex`), 2-pass support, extra-args and command-template sanitizers, fit-to-size bitrate, stall `toMs`, progress dump parsing, session “nothing encoded”, batch recipes, encode queue, Media3 planner and multi-audio survival, WebM muxer workarounds, combine pairing, storage reserve, job cache/SAF names, record options JSON, live mixer, bookmarks, capture log (crop/grayscale), agent wait clamp, Gemini reply parse, live-update chip math, share MIME filters, history policy, hardware target list.

Device-only checks live in `docs/manual-qa.md` (MediaProjection, QS tile, PiP, dual-track, App Functions on device, etc.).

---

## 23. Intents the app handles

- `MAIN` / `LAUNCHER`
- `SEND` video or audio
- `SEND_MULTIPLE` video, audio, or image
- `VIEW` video or audio
- `SHORTCUT_RECORD`, `SHORTCUT_COMPRESS_LATEST`, `SHORTCUT_EXTRACT_AUDIO`
- QS tile preferences

Share label: **Compress**.
