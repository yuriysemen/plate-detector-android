# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- Detailed architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Feature tracking and backlog: [ROADMAP.md](ROADMAP.md)

## Project overview

**Internal-only, not published to Google Play** — the training-data-collection counterpart to the
public [`../android/`](../android/CLAUDE.md) app (see [REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md)).
It started as a full copy of `android/` (same detection/OCR/capture/upload pipeline, `applicationId`
`com.github.yuriysemen.platesdetector.training`) and is where all data-collection code now lives —
`android/` no longer has any of it. Live detection (YOLO TFLite + ML Kit OCR) still exists here
because capture decisions depend on it, not because this app is meant to be used as a detector by
itself. The working directory for this Android project is `training-android/` (this folder);
Gradle commands must be run from here.

## Build commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Unsigned release APK
./gradlew :app:assembleRelease

# Unsigned release bundle
./gradlew :app:bundleRelease

# Run unit tests
./gradlew :app:test

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest
```

Signed release builds require env vars: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Model files

The `preBuild` task downloads a bundled default model from the latest GitHub Release tagged
`model_v<x.y.z>` (semantic version, private repo). For local builds, three options:

1. **Token in `local.properties`** (recommended) — add `MODEL_DOWNLOAD_TOKEN=ghp_<pat>` (needs
   `repo` scope). The token is also resolved from the Gradle property `MODEL_DOWNLOAD_TOKEN` or
   env vars `MODEL_DOWNLOAD_TOKEN` / `GITHUB_TOKEN` (set automatically in GitHub Actions).
2. **Manual placement** — copy `.tflite` + `.txt` sidecar to
   `app/src/main/assets/models/`; the download step is skipped when the file already exists.
3. **No model** — if neither is available the build succeeds with a `[WARN]`; the app shows a
   "No detection model" screen and waits for a runtime download after sign-in.

At runtime, signed-in users receive model updates via `GET /get-model-url` (Lambda); downloaded
models live in `filesDir/models/downloaded/`. They're kept across sign-out and removed only when a
**different** account signs in or the app is rebuilt for a different Cognito backend (REQ-029).

## Architecture

The app is entirely single-Activity Compose. `MainActivity` renders `LivePlateDetectionScreen`, which owns the top-level navigation state (settings vs. detection).

**Core flow:**
1. `LivePlateDetectionScreen` — discovers available models, manages prefs, routes between `SettingsScreen` and `LiveDetectionUi`.
2. `LiveDetectionUi` — sets up CameraX, runs `PlateDetector` on each frame via `ImageAnalysis` (throttled to ~8 fps), optionally chains `PlateOCR` on each detected bounding box. Hosts camera controls: pinch-to-zoom, tap-to-focus, torch toggle (`camera.cameraControl.enableTorch()`; auto-off on background), zoom shortcut buttons (1×/2×/3×), and EV compensation slider (`setExposureCompensationIndex()`). Analysis resolution is selected in Settings and applied via `ResolutionSelector` on camera bind.
3. `CameraPreviewWithAnalysis` — binds CameraX `Preview` + `ImageAnalysis` to the lifecycle. Frame → YUV→NV21→JPEG→Bitmap conversion, rotation, then detection on a single-thread executor.
4. `PlateDetector` — wraps TFLite `Interpreter`. Accepts `ModelSource` (asset, file path, or content URI), performs letterbox preprocessing, runs inference, decodes `[1, N, 6]` output (`[x1,y1,x2,y2,score,class]`), and unprojects coordinates back to original-image space. `PlateDetector.isValidModel()` (companion function) does the same shape checks against a candidate file *before* it's offered as selectable — `LivePlateDetectionScreen.availableModels()` filters both `listAssetModels()` and `listDownloadedModels()` through it, so a build with no real model bundled can't accidentally surface an unrelated `.tflite` (e.g. one of ML Kit's own internal OCR models) as a "model" and crash on construction; it shows "No detection model found" instead. `listAssetModels()` only scans `assets/models/` — an earlier fallback that scanned the whole assets root when that folder was empty has been removed, since that's exactly what surfaced ML Kit's files in the first place.
5. `PlateOCR` — wraps ML Kit `TextRecognizer`. Receives a cropped plate bitmap, returns `OCRResult` with cleaned alphanumeric text.
6. `ContributeScreen` — status card ("Frames waiting to upload", capture-status line, storage-used with ✏ quota edit, "Reset collected data"), storage quota banners (80% / 100%), upload-config card (auth row: **Sign in** / **Sign in again** (non-destructive re-auth) / **Sign out**, or "Session expired"; mobile-data toggle; daily time; last upload), the global "Upload collected data" button (60 s cooldown; tapping mid-upload cancels + restarts fresh), an **"Upload activity"** line + Details dialog over the persistent `UploadLog`, and the "Upload history" list — active/failed entries, per-row **Restart**, and the recorded **failure reason** shown under a failed row. No "View dataset" button (editor removed). Config IDs are `BuildConfig`/`AppConfig`, no UI fields.

**On-device dataset editing was removed (REQ-026).** `DatasetEditorScreen`, `FrameDetailScreen`, `DatasetEditor`, `FrameEntry`, `YoloBox` are gone. The generic app only captures (auto / manual / burst, all shipping the model's predicted boxes) and uploads; review/editing lives in `curation-android`. Capture is gated on **`collect_training_data` AND signed in** — `LiveDetectionUi` computes `captureActive` and nothing is written to `training_data/` unless both hold.

**Key data types:**
- `ModelSpec` / `ModelSource` (`Asset` / `FilePath` / `ContentUri`) / `CoordFormat` / `Detection` / `ModelOrigin` (`DEFAULT` bundled, `DOWNLOADED`, legacy `CUSTOM`/`LEGACY_EXTERNAL`).
- `SessionExpiredException` (real expiry → sign out), `ApiUnauthorizedException(httpCode)` (API 401/403, session valid — authz/config, NOT expiry), `RetryableHttpException` (429/5xx), `AuthChallengeException` (MFA / `NEW_PASSWORD_REQUIRED`).
- `UploadLog` — persistent (`filesDir/upload_log.json`, capped 100); `ModelUpdateLog` — in-memory, capped 100.

**Auth:** `CognitoAuthManager.getAwsCredentials()` is serialized process-wide by a companion `Mutex` (the periodic/startup/inline `ModelCheckWorker` paths + `UploadDatasetWorker` were racing the credential cache). Terminal Cognito errors map to `SessionExpiredException`. A persistent API 401/403, even after a forced credential refresh, fails the job with a message but **keeps the user signed in** — this is the curator-role / config case, not a dead session. `AppConfig.seedPrefsIfNeeded()` re-seeds `UploadPrefs` if the APK was rebuilt for a different backend and wipes the stale session. `signOut()` also clears the SDK's own `CognitoIdentityProviderCache` / `com.amazonaws.android.auth` prefs.

**Backend configuration (REQ-032):** `COGNITO_USER_POOL_ID` / `COGNITO_APP_CLIENT_ID` / `COGNITO_IDENTITY_POOL_ID` / `UPLOAD_SERVICE_URL` can now be edited on-device via `BackendConfigScreen`, not just baked in at build time. Every runtime call site already read these from `UploadPrefs` (not `BuildConfig` directly), so this was mostly a UI addition. Each field has its own "pinned" flag in `UploadPrefs` (`is*Pinned()` / `set*Manual()`) — once set via the screen, `AppConfig.seedPrefsIfNeeded()` skips that field forever, even across rebuilds with different `local.properties` values (a manual edit always wins). `AppConfig.IDENTITY_POOL_ID_PATTERN` (`<region>:<uuid>`) is the shared validity check used by `isConfigured`, `isBackendConfigured()`, and the screen's own save validation — plain non-empty wasn't enough, since a `local.properties` copy-pasted from `local.properties.example` without editing it is non-empty but not a real value. `NoModelsScreen` also has an always-visible "Configure backend…" link, independent of whatever `isBackendConfigured()` reports, so the screen is reachable no matter what state the stored config is in.

**Backup:** `android:allowBackup="false"` — no cloud Auto Backup, no `adb backup`. `res/xml/data_extraction_rules.xml` also excludes `training_data/`, `exports/`, `models/`, `upload_log.json` and the Cognito prefs from Android 12+ device-to-device transfer.

**Processing guard:** detection is disabled when the app is not in the foreground (`ON_STOP`).

## Commit preparation

Before committing, when asked to "commit this" or "make it ready for a commit", update the relevant docs to reflect what was done:

| File | Update when |
|---|---|
| `ROADMAP.md` | Move completed items from Backlog → Done; remove the item from Backlog |
| `ARCHITECTURE.md` | Any new component, data flow change, or notable UI behaviour added |
| `CLAUDE.md` | Changes to the build process, architecture overview, or project-level guidance |

Do **not** update docs automatically during feature work — only when explicitly asked to prepare for a commit.

## Model training (outside Android)

See `training/ultralytics/` for the Python training pipeline (YOLOv11 → TFLite export). The dataset layout expected is YOLO format; see `datasets/dataset_YOLO/data.yaml`.

Export command to produce a compatible TFLite file:
```bash
yolo export model=runs/detect/<name>/weights/best.pt format=tflite imgsz=640 nms=True conf=0.25 iou=0.45 max_det=300
```
The exported `best_float16.tflite` is the file to copy into the Android assets.