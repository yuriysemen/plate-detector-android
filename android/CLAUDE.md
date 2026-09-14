# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- Detailed architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Feature tracking and backlog: [ROADMAP.md](ROADMAP.md)

## Project overview

**Published, detection-only Android app** — real-time on-device license plate detection and OCR.
Uses a YOLO TFLite model for bounding-box detection and ML Kit for text recognition. It stores
nothing, uploads nothing, requires no account, and makes no network calls at runtime (see
[REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md)). The
account/capture/upload pipeline this app used to have now lives entirely in
[`../training-android/`](../training-android/CLAUDE.md), an internal, unpublished counterpart —
its history is what most of this app's `ROADMAP.md` used to describe.

The working directory for the Android project is `android/` (this folder); Gradle commands must
be run from here.

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
`model_v<x.y.z>` (semantic version, private repo) — this happens at **build time only**, on the
developer's machine or in CI; the installed app never makes a network call. For local builds,
three options:

1. **Token in `local.properties`** (recommended) — add `MODEL_DOWNLOAD_TOKEN=ghp_<pat>` (needs
   `repo` scope). The token is also resolved from the Gradle property `MODEL_DOWNLOAD_TOKEN` or
   env vars `MODEL_DOWNLOAD_TOKEN` / `GITHUB_TOKEN` (set automatically in GitHub Actions).
2. **Manual placement** — copy `.tflite` + `.txt` sidecar to
   `app/src/main/assets/models/`; the download step is skipped when the file already exists.
3. **No model** — if neither is available the build succeeds with a `[WARN]`; the app shows a
   "No detection model" screen with a Retry button. There is no runtime download path — a missing
   model can only be fixed by rebuilding or manually placing a file and reinstalling.

## Architecture

The app is entirely single-Activity Compose. `MainActivity` renders `LivePlateDetectionScreen`, which owns the top-level navigation state (settings vs. detection).

**Core flow:**
1. `LivePlateDetectionScreen` — discovers available models, manages prefs, routes between `SettingsScreen` and `LiveDetectionUi`.
2. `LiveDetectionUi` — sets up CameraX, runs `PlateDetector` on each frame via `ImageAnalysis` (throttled to ~8 fps), optionally chains `PlateOCR` on each detected bounding box. Hosts camera controls: pinch-to-zoom, tap-to-focus, torch toggle (`camera.cameraControl.enableTorch()`; auto-off on background), zoom shortcut buttons (1×/2×/3×), and EV compensation slider (`setExposureCompensationIndex()`). Analysis resolution is selected in Settings and applied via `ResolutionSelector` on camera bind.
3. `CameraPreviewWithAnalysis` — binds CameraX `Preview` + `ImageAnalysis` to the lifecycle. Frame → YUV→NV21→JPEG→Bitmap conversion, rotation, then detection on a single-thread executor.
4. `PlateDetector` — wraps TFLite `Interpreter`. Accepts `ModelSource` (asset or file path), performs letterbox preprocessing, runs inference, decodes `[1, N, 6]` output (`[x1,y1,x2,y2,score,class]`), and unprojects coordinates back to original-image space. `PlateDetector.isValidModel()` (companion function) does the same shape checks against a candidate file *before* it's offered as selectable — `LivePlateDetectionScreen.availableModels()` filters `listAssetModels()` through it, so a build with no real model bundled can't accidentally surface an unrelated `.tflite` (e.g. one of ML Kit's own internal OCR models) as a "model" and crash on construction; it shows "No detection model found" instead. Asset discovery is scoped to `assets/models/` only — no fallback scan of the whole assets root.
5. `PlateOCR` — wraps ML Kit `TextRecognizer`. Receives a cropped plate bitmap, returns `OCRResult` with cleaned alphanumeric text.

**Key data types:**
- `ModelSpec` / `ModelSource` (`Asset` / `FilePath` — `FilePath` is unused today since there's no runtime download, kept for type-shape parity with `training-android`) / `CoordFormat` / `Detection` / `ModelOrigin` (only `DEFAULT` is ever produced here; `DOWNLOADED`/`CUSTOM`/`LEGACY_EXTERNAL` are vestigial and harmless).

**Backup:** `android:allowBackup="false"` — no cloud Auto Backup, no `adb backup`. There's no separate `data_extraction_rules.xml` — nothing this app writes (a small `SharedPreferences` file holding the selected model id, confidence, scan interval, and label-visibility toggle) is sensitive enough to need explicit device-transfer exclusion.

**Processing guard:** detection is disabled when the app is not in the foreground (`ON_STOP`).

**No network, no accounts:** `INTERNET`, `ACCESS_NETWORK_STATE`, and `POST_NOTIFICATIONS` are not declared in the manifest. There is nothing to sign in to and nothing to upload.

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
