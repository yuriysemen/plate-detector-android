# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- Detailed architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Feature tracking and backlog: [ROADMAP.md](ROADMAP.md)

## Project overview

Android app for real-time on-device license plate detection and OCR. Uses a YOLO TFLite model for bounding-box detection and ML Kit for text recognition. The working directory for the Android project is `android/` (this folder); Gradle commands must be run from here.

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

The app requires `.tflite` model files in the assets directory. They are **gitignored** and downloaded automatically during `preBuild` from GitHub Releases (`plate_numbers.tflite` + `plate_numbers.txt`). To override, set the Gradle property `MODEL_FILES` (comma-separated filenames) or `MODEL_DOWNLOAD_TOKEN`/`GITHUB_TOKEN` for private releases.

To use a locally trained model instead, copy the `.tflite` file to:
- `app/src/main/assets/models/` (preferred), or
- `app/src/main/assets/` (fallback)

A `.txt` file with the same base name is read as the model description shown in the UI.

## Architecture

The app is entirely single-Activity Compose. `MainActivity` renders `LivePlateDetectionScreen`, which owns the top-level navigation state (settings vs. detection).

**Core flow:**
1. `LivePlateDetectionScreen` — discovers available models, manages prefs, routes between `SettingsScreen` and `LiveDetectionUi`.
2. `LiveDetectionUi` — sets up CameraX, runs `PlateDetector` on each frame via `ImageAnalysis` (throttled to ~8 fps), optionally chains `PlateOCR` on each detected bounding box. Hosts camera controls: pinch-to-zoom, tap-to-focus, torch toggle (`camera.cameraControl.enableTorch()`; auto-off on background), zoom shortcut buttons (1×/2×/3×), and EV compensation slider (`setExposureCompensationIndex()`).
3. `CameraPreviewWithAnalysis` — binds CameraX `Preview` + `ImageAnalysis` to the lifecycle. Frame → YUV→NV21→JPEG→Bitmap conversion, rotation, then detection on a single-thread executor.
4. `PlateDetector` — wraps TFLite `Interpreter`. Accepts `ModelSource` (asset, file path, or content URI), performs letterbox preprocessing, runs inference, decodes `[1, N, 6]` output (`[x1,y1,x2,y2,score,class]`), and unprojects coordinates back to original-image space.
5. `PlateOCR` — wraps ML Kit `TextRecognizer`. Receives a cropped plate bitmap, returns `OCRResult` with cleaned alphanumeric text.

**Key data types:**
- `ModelSpec` — per-model metadata (id, display title, source, confidence threshold, description, origin).
- `ModelSource` — sealed class: `Asset(path)`, `FilePath(file)`, `ContentUri(uri)`.
- `CoordFormat` — `XYXY_SCORE_CLASS` or `YXYX_SCORE_CLASS`; controls how model output columns are interpreted.
- `Detection` — bounding box in original-image pixels, score, classId, optional OCR text/confidence.
- `ModelOrigin` — `DEFAULT` (bundled asset), `CUSTOM` (imported to internal storage), `LEGACY_EXTERNAL` (old content URI path).

**Model management:**
- `ModelPrefs` (SharedPreferences) persists the selected model ID, per-model confidence thresholds, show-labels flag, and OCR toggle.
- Custom models are copied to `context.filesDir/models/custom/` on import and validated by attempting to construct a `TFLite Interpreter`.
- `tflite` files are marked `noCompress` in the build config so they can be memory-mapped; the loader falls back to `readBytes()` if `openFd` fails.

**Processing guard:** detection is disabled when the app is not in the foreground (`ON_STOP` lifecycle event), preventing background inference.

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