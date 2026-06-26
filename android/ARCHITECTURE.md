# Architecture

## Overview

Single-Activity, fully Jetpack Compose app. No navigation library — navigation is manual state in `LivePlateDetectionScreen`. No ViewModel — all state lives in composable `remember`/`rememberSaveable` blocks.

## Screen flow

```
MainActivity
  └── LivePlateDetectionScreen          (top-level coordinator)
        ├── NoModelsScreen              (no .tflite assets found)
        ├── AuthScreen                  (sign-up / sign-in / verify email; Cognito SRP + email code)
        ├── SettingsScreen              (model picker + sliders + Contribute data row)
        ├── ContributeScreen            (stats card; auth status row + sign-in/out; upload config — mobile data toggle, daily time, last upload; manual upload button; session in progress)
        │     └── DatasetEditorScreen   (frame grid; multi-select delete)
        │           └── FrameDetailScreen  (full-res image; box draw/move/resize/delete)
        └── LiveDetectionUi             (camera + detection + overlay)
              └── CameraPreviewWithAnalysis   (CameraX binding)
```

`LivePlateDetectionScreen` owns the routing state (`showSettings`, `showExport`, `showAuth`, `isModelEnabled`, `selectedId`). `showAuth` takes priority in the `when` block — it renders `AuthScreen` from anywhere in the flow. When no model is selected on first launch it opens Settings automatically. `ContributeScreen` is shown when `showExport` is true.

`ContributeScreen` owns the sub-navigation to `DatasetEditorScreen` via a local state flag. `DatasetEditorScreen` owns the sub-navigation to `FrameDetailScreen` via a `openFrame: FrameEntry?` state — when non-null the detail screen renders in place of the grid.

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
  └─ TrainingDataSaver.saveFrame()  (if collectTrainingData && detections not empty)
        ├─ compute YOLO lines; skip degenerate boxes (bw≤0 or bh≤0); coerceIn [0,1]
        ├─ if no valid lines → return (no files written)
        ├─ write JPEG (quality 90) → filesDir/training_data/images/<YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg
        ├─ write YOLO .txt → filesDir/training_data/labels/<YYYYMMDD>_<HHmmss>_<NNNNNN>.txt
        │     (one line per valid detection: classId x_center y_center width height, all normalized to [0,1])
        │     (date/time = capture wall-clock time in local timezone; NNNNNN = 6-digit monotonic counter)
        └─ overwrite manifest.json (next_seq, total_frames, total_detections=valid annotations, multi_detection_frames, date range)

Results posted to main thread → recompose overlay Canvas
```

## Model management

Three model origins (tracked in `ModelOrigin` enum):

| Origin | Storage | Deletable |
|---|---|---|
| `DEFAULT` | `assets/models/*.tflite` (bundled at build time, downloaded by Gradle) | No |
| `CUSTOM` | `context.filesDir/models/custom/` (imported by user) | Yes |
| `LEGACY_EXTERNAL` | Content URI (old approach, kept for migration) | Yes (removes from prefs) |

`ModelSpec` carries a `ModelSource` sealed class (`Asset`, `FilePath`, `ContentUri`) so `PlateDetector` loads from any of the three sources via memory-mapping with a `readBytes` fallback.

`ModelPrefs` (SharedPreferences) persists: selected model ID, per-model confidence threshold, show-labels flag, collect-training-data flag, scan interval ms, analysis resolution, storage quota.

A `.txt` sidecar file with the same base name as a `.tflite` is shown as the model description in Settings.

## Camera

`CameraPreviewWithAnalysis` binds CameraX `Preview` + `ImageAnalysis` to the lifecycle inside a `DisposableEffect`. It exposes the `Camera` object and `MeteringPointFactory` via `onCameraReady` so `LiveDetectionUi` can attach gesture controls without coupling camera setup to UI logic.

The overlay `Canvas` (sibling of the camera view in a `Box`) handles:
- Bounding box + label rendering (fit-center coordinate mapping matches `PreviewView.ScaleType.FIT_CENTER`)
- Pinch-to-zoom via `detectTransformGestures` → `camera.cameraControl.setZoomRatio()`
- Tap-to-focus via `detectTapGestures` → `FocusMeteringAction` + animated focus ring

The top bar in `LiveDetectionUi` exposes:
- **Settings button** (hamburger) — opens `SettingsScreen`
- **Stats text** — zoom ratio, detection count, inference latency
- **Torch button** — toggles `camera.cameraControl.enableTorch()`; only shown when `camera.cameraInfo.hasFlashUnit()` is true; automatically disabled when the app goes to background

`SettingsScreen` bottom section: **"Contribute data"** row — Switch on the right (default off; first enable shows a one-time consent dialog; `collect_training_data` + `collect_first_time_shown` prefs); tapping the row navigates to `ContributeScreen`.
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
| `UploadPrefs` | `LivePlateDetectionScreen.kt` | SharedPreferences wrapper for upload settings: `upload_service_url`, `upload_on_mobile_data`, `auto_upload_time` (HH:mm, default 02:00), `auto_upload_last_date` (ISO date), `cognito_user_pool_id`, `cognito_app_client_id`, `cognito_identity_pool_id`, `cognito_user_id` (sub), `cognito_user_email` |
| `CognitoAuthManager` | `CognitoAuthManager.kt` | Wraps AWS Android SDK v2 Cognito callbacks into `suspend` functions: `signUp`, `resendConfirmationCode`, `confirmSignUp`, `signIn`, `getIdToken`, `getAwsCredentials` (STS via Identity Pool), `signOut`. Throws `SessionExpiredException` when the refresh token has expired. |
| `AppConfig` | `AppConfig.kt` | Reads `BuildConfig` fields baked in at compile time from `local.properties` (`COGNITO_USER_POOL_ID`, `COGNITO_APP_CLIENT_ID`, `COGNITO_IDENTITY_POOL_ID`, `UPLOAD_SERVICE_URL`). `seedPrefsIfNeeded()` seeds `UploadPrefs` on first app launch. |
| `UploadStatus` | `DatasetExporter.kt` | Enum: `NOT_QUEUED`, `PENDING`, `UPLOADING`, `FAILED`, `UPLOADED` — written to per-ZIP `.upload.json` sidecar |
| `TrainingDataSaver` | `TrainingDataSaver.kt` | Saves JPEG frames + YOLO labels; maintains `manifest.json`; `reset()` clears collected files |
| `DatasetExporter` | `DatasetExporter.kt` | Builds export ZIP with train/val/test split (`SplitConfig`); generates `data.yaml` with `device:` metadata block; reads stats; lists/deletes export files; reads/writes per-ZIP upload status sidecar; `exportSync()` for WorkManager callers |
| `DatasetEditor` | `DatasetEditor.kt` | Loads `FrameEntry` list from disk; saves edited `YoloBox` lists back to `.txt`; deletes frame pairs; recalculates and rewrites `manifest.json` |
| `FrameEntry` | `DatasetEditor.kt` | Frame metadata: name, imageFile, labelFile, `List<YoloBox>` |
| `YoloBox` | `DatasetEditor.kt` | Single bounding box in YOLO normalized space: classId, xCenter, yCenter, width, height |

## Training data collection

Collected frames are stored under `context.filesDir`:

```
training_data/
  images/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg   (JPEG quality 90, rotated bitmap; date/time = capture time)
  labels/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.txt   (YOLO format: classId xc yc w h, normalized)
  manifest.json                                  (next_seq, total_frames, total_detections, multi_detection_frames, date range, app_version, model_id)
exports/
  plates_dataset_<timestamp>.zip  (one per export; frames randomly shuffled then split into
                                    train/, val/, test/ subdirs + data.yaml with device: metadata block)
```

`TrainingDataSaver` is instantiated per `LiveDetectionUi` session (via `remember`). It reads `manifest.json` on first use to restore `next_seq`, then increments in memory and rewrites the manifest after every frame. Because `LiveDetectionUi` leaves composition when Settings opens, a fresh `TrainingDataSaver` is created on each return — reading the latest manifest, so any reset performed in `ContributeScreen` is picked up automatically.

`DatasetExporter` is instantiated per `ContributeScreen` session. The two classes never run concurrently (the camera and export screens are never on screen at the same time), so there is no shared-state conflict.

`FileProvider` authority: `com.github.yuriysemen.platesdetector.fileprovider`, serving `filesDir/exports/` (declared in `res/xml/file_paths.xml`).

## Background upload jobs (WorkManager)

Two `CoroutineWorker` classes handle dataset upload off the main thread:

### `UploadDatasetWorker`

One instance per export ZIP. Enqueued immediately after a ZIP is created (both manual and auto-upload paths). Unique work name = ZIP file path (prevents duplicate uploads).

Flow:
1. Obtain short-lived STS credentials via `CognitoAuthManager.getAwsCredentials()` (exchanges current ID token via the Identity Pool). Throws `SessionExpiredException` → immediate `Result.failure()` if refresh token expired.
2. SigV4-sign a POST to `<upload_service_url>/get-upload-url` with `filename`, `device_id`, and `user_id` (Cognito sub) → receives S3 pre-signed URL. Signing uses `AWS4Signer` from `aws-android-sdk-core`.
3. PUT the ZIP binary to the pre-signed URL.
3. On S3 HTTP 200: delete the local ZIP, update sidecar to `UPLOADED`, post a notification if `KEY_IS_AUTO_UPLOAD == true`.
4. On S3 HTTP 403 (expired URL): re-request a fresh URL and retry the PUT once.
5. On failure: `Result.retry()` up to `MAX_ATTEMPTS = 5` with exponential backoff, then `Result.failure()` (sidecar set to `FAILED`).

Network constraint: `UNMETERED` (Wi-Fi) unless "Upload on mobile data" is on (`CONNECTED`).

### `AutoUploadWorker`

Scheduled as a `PeriodicWorkRequest` (24 h period, ±30 min flex). Registered by `AutoUploadWorker.schedule(context)` whenever upload settings change or the app starts. Cancelled when the upload URL is cleared or contribution is disabled.

Flow:
1. Skip if URL is blank, `user_id` is empty, or `total_frames == 0`.
2. Call `exportSync()` — packages frames into a ZIP and resets collected frames atomically.
3. Enqueue `UploadDatasetWorker` with `KEY_IS_AUTO_UPLOAD = true` and the three auth keys (`KEY_USER_ID`, `KEY_USER_POOL_ID`, `KEY_IDENTITY_POOL_ID`).
4. Write today's date to `auto_upload_last_date`.

`runCatchUpIfNeeded()` is called in `LaunchedEffect(Unit)` on every app start. It enqueues a one-shot `AutoUploadWorker` if the URL is configured, frames exist, today's date is not in `auto_upload_last_date`, and the network constraint is currently satisfied — covering the case where the device was offline at the scheduled time.

**Scheduling triggers** — `schedule()` is called after any of these pref changes: upload URL, mobile-data toggle, auto-upload time, or app start.

**Notification**: on auto-upload success `UploadDatasetWorker` posts to the `"Dataset"` `NotificationChannel` (created in `MainActivity.onCreate`). `POST_NOTIFICATIONS` runtime permission is requested on Android 13+ at first app launch.

## Dataset editor

`DatasetEditorScreen` is a 2-column lazy grid of all collected frames. Each cell asynchronously decodes the JPEG thumbnail and overlays its bounding boxes via a `Canvas`, using the same four-colour cycle (`0xFF00E676` / `0xFF40C4FF` / `0xFFFF6E40` / `0xFFEA80FC`) as the detail editor. Long-press enters multi-select mode; tapping a cell in normal mode opens `FrameDetailScreen`. `LazyGridState` is hoisted before the early `return` that renders `FrameDetailScreen`, so the grid scroll position is preserved in memory across the navigation and restored when the user navigates back.

`FrameDetailScreen` has two explicit modes controlled by `isEditMode: Boolean` state (default `false`):

- **View mode** — read-only; top bar shows Back + ✏ Edit; no handles drawn; tap/drag interactions disabled; Back navigates to the grid without a dialog.
- **Edit mode** — entered via ✏; top bar shows Cancel + frame name + × (delete selected box) + ✓ Save + ⋮ (delete frame); `+` FAB always visible while not drawing; Cancel / Back show a discard dialog if `hasUnsavedChanges`, then return to view mode (not the grid). `savedBoxes` captures the box state when edit mode is entered so discard can restore it without re-reading disk.

The screen displays the full-resolution frame inside a `BoxWithConstraints` (black letterbox, fit-center scaling). Bounding boxes are stored in **canvas-pixel space** (offset + scaled to the composable's display area) while the screen is open. The coordinate lifecycle is:

```
YoloBox (normalized 0–1)
  → yoloToCanvas()     on first layout (LaunchedEffect, runs once per frame open)
  → DisplayBox         in-memory during editing (drag / resize / add / delete)
  → canvasToYolo()     on Save (converts back before writing .txt)
```

When the canvas geometry changes (e.g. a box is selected and the FAB row collapses), a `SideEffect` detects the change and re-projects all `DisplayBox` values to the new geometry before the next draw, keeping boxes aligned with the image.

**Zoom and pan (REQ-012):** `FrameDetailScreen` maintains `zoomScale` (1×–8×), `panOffsetX`, and `panOffsetY` as `remember` state. All Canvas drawing is wrapped in `withTransform { translate(pan); scale(zoom) }` so zoom/pan is a pure transform with no bitmap re-decode. The Canvas carries `Modifier.clipToBounds()` to prevent zoomed content from overflowing into adjacent UI. A fourth `pointerInput(Unit)` block runs an `awaitEachGesture` loop that waits within each touch sequence until ≥ 2 fingers are detected, then reads `PointerEvent.calculateZoom/Pan/Centroid` for pinch-to-zoom. All single-finger gesture coordinates (tap, drag start, drag delta) are inverse-transformed from screen space to canvas space before use. Handle circles are drawn at `handleRadius / zoomScale` so they remain 14 dp on screen at any zoom level. Zoom state resets when a new frame is opened (`LaunchedEffect(frame.name)`) and implicitly on device rotation (Activity recreation resets all `remember` state). A semi-transparent zoom label (e.g. `"2.5×"`) fades in on zoom change and auto-hides after 1.5 s via `animateFloatAsState`.

`DatasetEditor` is instantiated once per `DatasetEditorScreen` session (via `remember`) and shared with each `FrameDetailScreen` child. Mutations (`saveBoxes`, `deleteFrames`) are always dispatched on `Dispatchers.IO`; after completion the grid calls `refresh()` to reload `frames` state from disk.
