# Architecture

## Overview

Single-Activity, fully Jetpack Compose app. No navigation library — navigation is manual state in `LivePlateDetectionScreen`. No ViewModel — all state lives in composable `remember`/`rememberSaveable` blocks.

## Screen flow

```
MainActivity
  └── LivePlateDetectionScreen          (top-level coordinator)
        ├── NoModelsScreen              (no .tflite bundled/downloaded; also carries Sign in / Sign out
        │                                so a fresh install / different-user / reconfigure isn't a dead end)
        ├── AuthScreen                  (sign-up / sign-in / verify email; Cognito SRP + email code)
        ├── SettingsScreen              (model picker + sliders; "Contribute data" row ONLY when signed in,
        │                                otherwise a "Sign in" row; a "session expired" card when applicable)
        ├── ContributeScreen            (status card — "Frames waiting to upload", capture-status line,
        │                                Reset; auth row with Sign in / Sign in again / Sign out; upload
        │                                config; "Upload collected data"; "Upload activity" line + Details
        │                                dialog over the persistent UploadLog; upload history — active/failed
        │                                only, up to 10, per-row Restart, failure reason shown)
        └── LiveDetectionUi             (camera + detection + overlay)
              └── CameraPreviewWithAnalysis   (CameraX binding)
```

`LivePlateDetectionScreen` owns the routing state (`showSettings`, `showExport`, `showAuth`, `isModelEnabled`, `selectedId`). `showAuth` takes priority in the `when` block — it renders `AuthScreen` from anywhere in the flow, **including over `NoModelsScreen`**. When no model is selected on first launch it opens Settings automatically. `ContributeScreen` is shown when `showExport` is true.

On-device dataset review/editing was **removed** (REQ-026) — the generic app only captures and uploads; all box editing now lives in the separate `curation-android` app. `DatasetEditorScreen`, `FrameDetailScreen`, and `DatasetEditor` are gone.

Auth state (`isSignedIn` / `signedInEmail` / `sessionExpired`) is re-synced from `CognitoAuthManager` on `ON_START`, on a 30 s poll, when `ContributeScreen` opens, and after "Check now" — so a session a background worker expires is picked up without a manual navigation.

## Detection pipeline (per frame)

```
CameraX ImageAnalysis (background thread, ~8 fps throttle)
  │
  ├─ YUV_420_888 → NV21 → JPEG → Bitmap   (toBitmapSafe)
  ├─ rotate by imageInfo.rotationDegrees
  │
  ├─ PlateDetector.detectAll()
  │     ├─ letterbox to model input size (black padding, scale preserved)
  │     ├─ Bitmap → ByteBuffer (RGB float32 or UINT8)
  │     ├─ TFLite Interpreter.runForMultipleInputsOutputs()
  │     └─ decode output [1, N, 6] → List<Detection>
  │           (unproject letterbox coords back to original-image pixels)
  │
  ├─ PlateOCR.recognizePlate()  (if detections not empty — always enabled)
  │     ├─ crop+pad bitmap to detection bounds
  │     └─ ML Kit TextRecognizer → clean alphanumeric text
  │
  ├─ captureActive? (= collect_training_data pref AND signed in — nothing is written to
  │                   disk unless BOTH hold; REQ-026)
  │   ├── burst mode active (mutually exclusive with regular auto-save)
  │   │     └─ saveFrame() if dets present, else saveFrameManual()   (every frame; no quota check)
  │   │        mainExecutor.execute { onBurstFrameSaved() }  → completion dialog at target
  │   └── regular auto-save + detections not empty
  │         └── TrainingDataSaver.saveFrame()
  │               ├─ compute YOLO lines; skip degenerate boxes (bw≤0 or bh≤0); coerceIn [0,1]
  │               ├─ if no valid lines → return (no files written)
  │               ├─ write JPEG (quality 90) → filesDir/training_data/images/<YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg
  │               ├─ write YOLO .txt → filesDir/training_data/labels/<YYYYMMDD>_<HHmmss>_<NNNNNN>.txt
  │               │     (one line per valid detection: classId x_center y_center width height, normalized [0,1])
  │               └─ overwrite manifest.json (next_seq, total_frames, total_detections, multi_detection_frames, date range)
  │
  └─ frame copy → onLatestFrame callback  (only when captureActive; posted to main thread)
        └─ stored as latestFrame state in LiveDetectionUi for the manual capture button

Results posted to main thread → recompose overlay Canvas
```

## Model management

Three model origins (tracked in `ModelOrigin` enum):

| Origin | Storage | Deletable |
|---|---|---|
| `DEFAULT` | `assets/models/*.tflite` (bundled at build time, downloaded by Gradle from latest `model_v*` GitHub Release) | No |
| `DOWNLOADED` | `context.filesDir/models/downloaded/` (fetched at runtime via `GET /get-model-url`; one file at a time) | Superseded by a newer download; removed when a **different** account signs in (owner-sub tracked in `DownloadedModelPrefs`, REQ-029) or on a backend reconfigure — **not** on a plain sign-out |
| `CUSTOM` / `LEGACY_EXTERNAL` | legacy only — import from device storage was removed in REQ-016 | Yes |

Model selection priority at runtime: `DOWNLOADED` (if file exists) → `DEFAULT` (bundled asset) → "No models" error screen.

`ModelSpec` carries a `ModelSource` sealed class (`Asset`, `FilePath`, `ContentUri`) so `PlateDetector` loads from any of the three sources via memory-mapping with a `readBytes` fallback.

`ModelPrefs` (SharedPreferences) persists: selected model ID, per-model confidence threshold, show-labels flag, collect-training-data flag, scan interval ms, analysis resolution, storage quota.

`DownloadedModelPrefs` (SharedPreferences) persists: active model (`downloaded_model_version`, `downloaded_model_s3_key`, `downloaded_model_owner_sub`); pending update (`pending_model_version`, `pending_model_s3_key`, `pending_model_download_url`, `pending_model_description`); server info (`latest_model_version`, `model_last_check_time`).

A `.txt` sidecar file with the same base name as a `.tflite` is shown as the model description in Settings. For downloaded models the sidecar is written from the `description` field in the Lambda response.

## Camera

`CameraPreviewWithAnalysis` binds CameraX `Preview` + `ImageAnalysis` to the lifecycle inside a `DisposableEffect`. It exposes the `Camera` object and `MeteringPointFactory` via `onCameraReady` so `LiveDetectionUi` can attach gesture controls without coupling camera setup to UI logic. Additional parameters `burstModeActive` and `onBurstFrameSaved` control burst collection; both are captured via `rememberUpdatedState` so they take effect without triggering a camera rebind.

The overlay `Canvas` (sibling of the camera view in a `Box`) handles:
- Bounding box + label rendering (fit-center coordinate mapping matches `PreviewView.ScaleType.FIT_CENTER`)
- Pinch-to-zoom via `detectTransformGestures` → `camera.cameraControl.setZoomRatio()`
- Tap-to-focus via `detectTapGestures` → `FocusMeteringAction` + animated focus ring

The top bar in `LiveDetectionUi` exposes:
- **Settings button** (hamburger) — opens `SettingsScreen`
- **Stats text** — zoom ratio, detection count, inference latency; model label shows burst progress `"Burst: N / M"` in yellow when burst is active
- **Manual capture button** (`CameraAlt` icon) — visible only when `captureActive` (`collect_training_data` on **and** signed in); saves the latest analyzed frame with an empty label file via `TrainingDataSaver.saveFrameManual()`; toast "Frame saved" on success; toast "Storage quota full" when at 100% quota (no save); 1-second cooldown after each capture (button dims to 35% alpha); disabled while burst collection is active (REQ-020)
- **Burst collection button** (`BurstMode` icon) — visible only when `captureActive`; tapping while inactive opens a setup dialog (count field, default 100); tapping while active stops burst immediately; yellow tint while active; on completion shows a dialog with "Send to server" (enqueues `UploadDatasetWorker`, resets counter, starts next round) or "Stop collecting"; burst state is plain `remember` (not `rememberSaveable`) so it resets on rotation (REQ-021)
- **Torch button** — toggles `camera.cameraControl.enableTorch()`; only shown when `camera.cameraInfo.hasFlashUnit()` is true; automatically disabled when the app goes to background

`SettingsScreen` model section: below the model list card, a one-line **model activity row** shows the latest `ModelUpdateLog` entry (grey/green/red by level) and a "Details" button (opens `AlertDialog` with the full log, newest first). When signed in, a "Check now" `TextButton` is also shown in that row; tapping it runs `ModelCheckWorker.performCheck()` in a `rememberCoroutineScope()`, shows a `CircularProgressIndicator` during the request, then increments `reloadKey` to pick up any newly-stored pending update. Below that row: last check time + next scheduled check; and an incompatibility banner when `latest_model_version > compatible_model_version`.

`SettingsScreen` bottom section: when **signed in**, a **"Contribute data"** row — Switch on the right (default off; first enable shows a one-time consent dialog; `collect_training_data` + `collect_first_time_shown` prefs); tapping the row navigates to `ContributeScreen`. When **signed out** (incl. session-expired) the row is hidden and replaced by a plain **"Sign in"** row → `AuthScreen`, so authentication stays reachable from Settings (REQ-029).
- **Zoom shortcut buttons** — 1×/2×/3× pill buttons at bottom center; filtered to `camera.cameraInfo.zoomState.maxZoomRatio`; tapping calls `setZoomRatio()`; active level highlighted in white
- **EV slider** — horizontal slider above zoom buttons; range and step read from `camera.cameraInfo.exposureState`; calls `setExposureCompensationIndex()`; displays computed EV value (`index × step`); hidden when `isExposureCompensationSupported` is false; resets to 0 on model change
- **Analysis resolution** — `AnalysisResolution` enum (`DEFAULT`/`LOW`/`HD`) persisted in `ModelPrefs`; wired into `ImageAnalysis.Builder` via `ResolutionSelector` + `ResolutionStrategy`; camera is fully rebound when changed (via `key(spec.id, analysisResolution)`); picker shown in `SettingsScreen`
- **Scan interval** — time between frames analysed (5 s / 2 s / 1 s / ½ s / No delay, default 1 s) persisted in `ModelPrefs` as `scan_interval_ms`; applied per-frame via `rememberUpdatedState`; RadioButton list in `SettingsScreen`; no camera rebind needed

Processing is suppressed when the app is not in the foreground (`ON_STOP` lifecycle event) and `keepScreenOn` is tied to the same flag.

## Key types

| Type | File | Purpose |
|---|---|---|
| `Detection` | `PlateDetector.kt` | Bounding box in original-image pixels + score + classId + OCR result |
| `ModelSpec` | `ModelSpec.kt` | Per-model metadata (id, title, source, conf, origin) |
| `ModelSource` | `ModelTypes.kt` | Sealed: `Asset(path)`, `FilePath(file)`, `ContentUri(uri)` |
| `CoordFormat` | `ModelTypes.kt` | `XYXY_SCORE_CLASS` or `YXYX_SCORE_CLASS` — how model output columns map |
| `OCRResult` | `PlateOCR.kt` | Cleaned plate text + confidence estimate |
| `ModelPrefs` | `LivePlateDetectionScreen.kt` | SharedPreferences wrapper; keys: selected model, per-model conf, show-labels, `collect_training_data`, `collect_first_time_shown`, analysis resolution, `scan_interval_ms` (default 1000), storage quota |
| `UploadPrefs` | `LivePlateDetectionScreen.kt` | SharedPreferences wrapper for upload settings: `upload_service_url`, `upload_on_mobile_data` ("Use mobile data"), `auto_upload_time` (HH:mm, default 02:00), `auto_upload_last_date`, `cognito_user_pool_id`, `cognito_app_client_id`, `cognito_identity_pool_id`, `cognito_user_id` (sub), `cognito_user_email`, `cognito_session_expired` (set by `markSessionExpired()`) |
| `CognitoAuthManager` | `CognitoAuthManager.kt` | Wraps AWS Android SDK v2 Cognito callbacks into `suspend` functions via `suspendCancellableCoroutine`: `signUp`, `resendConfirmationCode`, `confirmSignUp`, `signIn`, `getIdToken`, `getAwsCredentials(forceRefresh)` (STS via Identity Pool — **serialized process-wide by a companion `Mutex`** so the concurrent worker paths don't race the shared credential cache), `signOut`, `signOutAndWipeModel`. Terminal Cognito errors (`NotAuthorized` / `UserNotFound` / `ResourceNotFound` / …) and refresh-time challenges/MFA are mapped to `SessionExpiredException` (were generic → infinite retry). `signIn()` throws on an empty `sub`; deletes the previous owner's downloaded model on a **different-user** sign-in. `signOut()` builds the pool if needed and `clear()`s the SDK's own auth SharedPreferences (`CognitoIdentityProviderCache`, `com.amazonaws.android.auth`) — but keeps the downloaded model. `markSessionExpired()` (from a real `SessionExpiredException` only) keeps the cached email + model. |
| `ApiUnauthorizedException` / `RetryableHttpException` / `AuthChallengeException` | `CognitoAuthManager.kt` | `ApiUnauthorizedException(httpCode, …)` — an authed API call got 401/403 while the session is valid (authorization/config problem, e.g. curator role without `execute-api`); the workers fail the job with a message and **do not** sign the user out. `RetryableHttpException` — 429/5xx, worth `Result.retry()`. `AuthChallengeException` — a sign-in challenge the app can't complete (MFA, `NEW_PASSWORD_REQUIRED`). |
| `AppConfig` | `AppConfig.kt` | Reads `BuildConfig` Cognito/API fields (from `local.properties`). `seedPrefsIfNeeded(context): Boolean` — seeds `UploadPrefs`, and **re-seeds** when the APK was rebuilt for a different backend (overwrites stored values, never with an empty build value); if the identity config changed it `signOutAndWipeModel()`s and returns `true`. Called at app start and at the top of all three workers. |
| `ModelUpdateLog` | `ModelUpdateLog.kt` | In-memory singleton; `MutableStateFlow<List<Entry>>` capped at 100; never persisted. `Entry` = `timeMs`, `message`, `level`. Drives the Settings model-activity row + "Details" dialog. |
| `UploadLog` | `UploadLog.kt` | **Persistent** counterpart (`filesDir/upload_log.json`, capped 100) — the upload workers run headless, so an in-memory log would be empty when the user looks. In-memory `StateFlow` seeded from disk. Records every upload lifecycle event (start / retry / fail-with-reason / complete) + `AutoUploadWorker` queued/skipped. Drives the ContributeScreen "Upload activity" line + Details dialog. |
| `UploadStatus` | `DatasetExporter.kt` | Enum `NOT_QUEUED` / `PENDING` / `UPLOADING` / `FAILED` / `UPLOADED` — in the per-ZIP `.upload.json` sidecar (`status` + `updated_at` + `detail`). `UPLOADED` is never persisted (ZIP + sidecar deleted on success). |
| `TrainingDataSaver` | `TrainingDataSaver.kt` | Saves JPEG frames + YOLO labels (the model's predicted boxes); maintains `manifest.json`; `saveFrameManual()` = frame + empty label (missed-plate); `reset()` clears. |
| `DatasetExporter` | `DatasetExporter.kt` | `exportSync()` builds the upload ZIP (train/val/test split + `data.yaml` device block) and resets `training_data/`; `trainingUsageBytes()` (buffer size, was in `DatasetEditor`); `listExports()` (active entries; sweeps orphan sidecars + `.upload.json.tmp`); `writeUploadStatus(status, detail?)` — **atomic** temp-file-then-rename, `detail=null` preserves the prior reason; `onUploadSuccess` / `deleteAllExports`. |

## Training data collection

Capture is gated on **`collect_training_data` AND signed in** (REQ-026). The generic app only
captures and uploads — no on-device review. `training_data/` is a short-lived upload buffer:
`AutoUploadWorker` / manual upload package it into a ZIP (which resets the buffer) and send it;
the ZIP is deleted on success. `curation-android` reviews the uploaded packages.

**Nothing leaves the device via OS backup** — `android:allowBackup="false"` (disables cloud Auto
Backup + `adb backup`); `res/xml/data_extraction_rules.xml` also excludes `training_data/`,
`exports/`, `models/`, `upload_log.json` and the Cognito token prefs from Android 12+ D2D transfer
(REQ-007 §5).

Collected frames and downloaded models are stored under `context.filesDir`:

```
models/
  downloaded/
    plate_numbers_v0.1.0.tflite    ← active downloaded model (one at a time)
    plate_numbers_v0.1.0.txt       ← description sidecar written from Lambda response

training_data/
  images/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg   (JPEG quality 90, rotated bitmap; date/time = capture time)
  labels/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.txt   (YOLO format: classId xc yc w h, normalized)
  manifest.json                                  (next_seq, total_frames, total_detections, multi_detection_frames, date range, app_version, model_id)
exports/
  plates_dataset_<timestamp>.zip             (one per export; frames randomly shuffled then split into
                                               train/, val/, test/ subdirs + data.yaml with device: metadata block)
  plates_dataset_<timestamp>.upload.json     (sidecar: status only; deleted along with the ZIP on
                                               successful upload — no local history is retained)
```

`TrainingDataSaver` is instantiated per `LiveDetectionUi` session (via `remember`). It reads `manifest.json` on first use to restore `next_seq`, then increments in memory and rewrites the manifest after every frame. Because `LiveDetectionUi` leaves composition when Settings opens, a fresh `TrainingDataSaver` is created on each return — reading the latest manifest, so any reset performed in `ContributeScreen` is picked up automatically.

`DatasetExporter` is instantiated per `ContributeScreen` session. The two classes never run concurrently (the camera and export screens are never on screen at the same time), so there is no shared-state conflict.

`FileProvider` authority: `com.github.yuriysemen.platesdetector.fileprovider`, serving `filesDir/exports/` (declared in `res/xml/file_paths.xml`).

## Background upload jobs (WorkManager)

Three `CoroutineWorker` classes handle background network work:

### `UploadDatasetWorker`

One instance per export ZIP. Enqueued immediately after a ZIP is created (manual, auto-upload, and burst-mode paths — see `LivePlateDetectionScreen.enqueueDatasetUpload`, REQ-021). Unique work name = ZIP file path (prevents duplicate uploads). Every request is also tagged `"dataset_upload"` (`UploadDatasetWorker.TAG_DATASET_UPLOAD`), in addition to its per-ZIP tag, so all outstanding jobs — regardless of which path enqueued them — can be cancelled together in one call.

Flow:
0. `AppConfig.seedPrefsIfNeeded()`.
1. STS credentials via `CognitoAuthManager.getAwsCredentials()`. `SessionExpiredException` → `markSessionExpired()` + `FAILED` + `Result.failure()` (no retry).
2. SigV4-sign a POST to `<upload_service_url>/get-upload-url` (`filename`, `device_id`, `user_id`) → S3 pre-signed URL. A **401/403** here → `ApiUnauthorizedException`: mint fresh credentials (`getAwsCredentials(forceRefresh = true)`) and retry once; if still 401/403 it's an **authorization/config** problem (not expiry — e.g. a curator whose token resolves to `CuratorRole` without `execute-api`), so `FAILED` with an actionable reason and `Result.failure()`, **session kept**. Non-2xx bodies are logged (`errorStream`, first 300 chars).
3. PUT the ZIP to the pre-signed URL — `setFixedLengthStreamingMode` + a manual 64 KB chunked loop that also `setProgress()`s (throttled ≥ 250 ms). Fixed-length mode avoids buffering the whole body in memory and a stale-connection `Broken pipe`.
4. S3 HTTP 200 → `onUploadSuccess()` (ZIP + sidecar deleted, `UploadLog` SUCCESS), notification if `KEY_IS_AUTO_UPLOAD`. S3 HTTP 403 (expired URL) → re-request once.
5. Other failure → `Result.retry()` up to `MAX_ATTEMPTS = 5`, then `FAILED`. Every outcome + reason goes to the persistent `UploadLog`.

`ContributeScreen` reads the reported progress from the same per-row `WorkInfo` flow it already collects (`getWorkInfosByTagFlow` → `WorkInfo.progress`) and shows a determinate progress indicator + `"Uploading… NN%"` while `UPLOADING`, falling back to an indeterminate spinner when no progress has been reported yet.

Network constraint depends on the trigger:
- **Manual upload, including a restart of a stuck/failed job**: always `CONNECTED` (any network, including mobile data) — the user explicitly requested the upload now.
- **Auto-upload** (`AutoUploadWorker`): `UNMETERED` (Wi-Fi only) unless "Upload on mobile data" is on (`CONNECTED`).

**Restart semantics (ContributeScreen).** Two levels of recovery exist:
- **Global** — the "Upload collected data" button, always tappable except for a 60-second cooldown right after each tap. Tapping it while a previous upload hasn't reached `UPLOADED` calls `WorkManager.cancelAllWorkByTag("dataset_upload")`, then `DatasetExporter.deleteAllExports()` to wipe whatever ZIPs/sidecars those cancelled jobs left behind, before exporting fresh and enqueueing a new upload. This is a genuine restart, not a resume: since `exportSync()` already reset `training_data/` when the discarded ZIP was built, the frames in a cancelled upload are not recoverable.
- **Per-item** — each Upload history row shows its own **Restart** button once it's `FAILED`, or `PENDING`/`UPLOADING` with no sidecar `updated_at` change in 30+ minutes (`DatasetExporter.STALE_UPLOAD_TIMEOUT_MS`; re-evaluated on a 15 s UI tick so it appears live). Tapping it re-enqueues only that one entry via `enqueueUniqueWork(..., ExistingWorkPolicy.REPLACE, ...)` — an atomic cancel-and-insert under that entry's unique work name, avoiding a race where a separate `cancelUniqueWork()` + `KEEP` enqueue could see the still-pending old job and silently drop the new one — leaving every other in-flight upload untouched, unlike the global button, which resets everything.

### `AutoUploadWorker`

Scheduled as a `PeriodicWorkRequest` (24 h period, ±30 min flex). Registered by `AutoUploadWorker.schedule(context)` whenever upload settings change or the app starts. Cancelled when the upload URL is cleared or contribution is disabled.

Flow:
1. `AppConfig.seedPrefsIfNeeded()`. Skip if **`!CognitoAuthManager.isSignedIn()`** (false once the session is expired — bail *before* `exportSync()`, which would otherwise wipe the frame buffer for a doomed upload), URL/pool blank, or `total_frames == 0`.
2. `exportSync()` — packages frames into a ZIP and resets `training_data/` atomically.
3. Enqueue `UploadDatasetWorker` with `KEY_IS_AUTO_UPLOAD = true`.
4. Write today's date to `auto_upload_last_date`; `UploadLog` "queued".

`runCatchUpIfNeeded()` is called once `LiveDetectionUi` (camera pipeline) has composed — not at raw process start, to avoid a same-launch export+upload landing at an arbitrary point relative to the camera's own startup allocation burst (REQ-015). It enqueues a one-shot `AutoUploadWorker` if the URL is configured, **`isSignedIn()`**, frames exist, today's date is not in `auto_upload_last_date`, and the network constraint is satisfied.

**Startup memory robustness.** `LiveDetectionUi` is only composed once `LivePlateDetectionScreen` has moved past Settings/model-picker/Auth — an unbounded, user-timed gate. A previously-enqueued `UploadDatasetWorker` job can also be auto-resumed by WorkManager at raw process start, outside app control. To keep these from compounding into an OOM alongside the camera's own allocation burst: `android:largeHeap="true"` is set in the manifest, and `DatasetExporter.exportSync()`'s `ZipOutputStream` is backed by a `BufferedOutputStream` (previously a raw, unbuffered `FileOutputStream`) to shorten how long export holds elevated memory.

**Scheduling triggers** — `schedule()` is called after any of these pref changes: upload URL, mobile-data toggle, auto-upload time, or app start.

### `ModelCheckWorker`

Scheduled as a `PeriodicWorkRequest` (1-hour repeat interval). Enqueued at sign-in; cancelled at sign-out. Network constraint: `CONNECTED`; additionally `UNMETERED` when "Use mobile data" pref is `false`. Also run once at sign-in (startup check).

The check logic lives in `companion object suspend fun performCheck(context)` so it can be called both by the WorkManager `doWork()` wrapper and directly from the "Check now" button in Settings (inline, without WorkManager scheduling).

`performCheck` flow:
0. `AppConfig.seedPrefsIfNeeded()`. Early-return if `isSessionExpired()` (don't hammer the endpoint/log hourly while the user hasn't re-authed).
1. `GET /get-model-url?app_version=…` SigV4-signed, all outcomes → `ModelUpdateLog`. `SessionExpiredException` from `getAwsCredentials()` → `markSessionExpired()` + rethrow → `Result.failure()`. **401/403** → `ApiUnauthorizedException`: forced-credential-refresh retry once; still 401/403 → log an actionable "not authorized / config" message and return (session **kept**, not expired). **429/5xx** → `RetryableHttpException` → `Result.retry()` with backoff (not swallowed for an hour).
2. Store `latest_model_version` and check time in `DownloadedModelPrefs`.
3. Compare `compatible.s3_key` with `downloaded_model_s3_key` pref. If identical: log "up to date"; exit.
4. Write pending update fields (`pending_model_version`, `pending_model_s3_key`, `pending_model_download_url`, `pending_model_description`) to `DownloadedModelPrefs`.

The foreground UI (`LivePlateDetectionScreen`) reads pending prefs in `LaunchedEffect(isSignedIn, reloadKey)` and shows a confirmation `AlertDialog`. On "Update": `applyModelUpdate()` downloads the `.tflite` to a `.download` temp file, replaces the old model on success, auto-selects and persists the new model ID, and increments `reloadKey`. On "Later": pending prefs cleared; re-prompted on next check.

If `latest.model_version > compatible.model_version` (newer model requires a higher app version), a banner is shown in Settings (display-only).

**Notification**: on auto-upload success `UploadDatasetWorker` posts to the `"Dataset"` `NotificationChannel` (created in `MainActivity.onCreate`). `POST_NOTIFICATIONS` runtime permission is requested on Android 13+ at first app launch.

## On-device dataset editing — removed (REQ-026)

The generic app no longer reviews or edits collected frames. `DatasetEditorScreen`,
`FrameDetailScreen`, `DatasetEditor`, `FrameEntry`, and `YoloBox` were deleted. All box
review/editing (accept/reject, move/resize/add/delete, vehicle-type classification) lives in the
standalone `curation-android` app, which consumes the uploaded YOLO packages from S3.

## Auth-state consistency (REQ-027 / REQ-029)

`LivePlateDetectionScreen` holds `isSignedIn` / `signedInEmail` / `sessionExpired` as `rememberSaveable`
state, re-synced from `CognitoAuthManager` on: `ON_START`, a 30 s `LaunchedEffect` poll, `showExport`
becoming true, and after "Check now". A background worker that calls `markSessionExpired()` is
therefore reflected in the UI within ~30 s without a manual navigation. `markSessionExpired()` is
reached **only** from a genuine `SessionExpiredException` — a persistent API 401/403 (authorization
/ config, e.g. a curator role missing `execute-api`) fails the job with a message and leaves the
session intact.

