# Architecture

> This app was split down to detection-only per
> [REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md). Everything it used
> to do around accounts, capture, upload, and model auto-update — including the "Background upload
> jobs (WorkManager)", "Training data collection", and "Auth-state consistency" sections this
> document used to have — now lives in
> [`../training-android/ARCHITECTURE.md`](../training-android/ARCHITECTURE.md) instead. What
> follows describes this app's code as it is today.

## Overview

Single-Activity, fully Jetpack Compose app. No navigation library — navigation is manual state in `LivePlateDetectionScreen`. No ViewModel — all state lives in composable `remember`/`rememberSaveable` blocks.

## Screen flow

```
MainActivity
  └── LivePlateDetectionScreen          (top-level coordinator)
        ├── NoModelsScreen              (no .tflite bundled; Retry only — no accounts to sign into)
        ├── SettingsScreen              (model picker + confidence/scan-interval/resolution sliders)
        └── LiveDetectionUi             (camera + detection + overlay)
              └── CameraPreviewWithAnalysis   (CameraX binding)
```

`LivePlateDetectionScreen` owns the routing state (`showSettings`, `isModelEnabled`, `selectedId`). When no model is selected on first launch it opens Settings automatically.

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
  └─ results posted to main thread → recompose overlay Canvas + beep
```

Nothing here writes to disk or leaves the device — the pipeline exists purely to render the
overlay and (optionally) beep.

## Model management

Only one origin is ever produced: `DEFAULT` — an asset bundled at build time (downloaded by Gradle
from the latest `model_v*` GitHub Release, or placed manually in `assets/models/`). There is no
runtime download and nothing is ever deletable. `ModelOrigin` still declares `DOWNLOADED` / `CUSTOM`
/ `LEGACY_EXTERNAL` values for type-shape parity with `training-android`, but they're never
produced here.

`ModelSpec` carries a `ModelSource` sealed class (`Asset`, `FilePath`, `ContentUri`); only `Asset`
is ever used in this app. `PlateDetector.isValidModel()` validates a candidate's tensor shape
before it's offered as selectable — see `CLAUDE.md` for why.

`ModelPrefs` (SharedPreferences) persists: selected model ID, per-model confidence threshold,
show-labels flag, scan interval ms, analysis resolution.

A `.txt` sidecar file with the same base name as a `.tflite` is shown as the model description in
Settings.

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

`SettingsScreen` — below the model list card: confidence threshold slider, scan interval slider,
and analysis resolution picker. That's the whole screen; there's no account/upload section.
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
| `ModelSource` | `ModelTypes.kt` | Sealed: `Asset(path)`, `FilePath(file)`, `ContentUri(uri)` — only `Asset` is produced here |
| `CoordFormat` | `ModelTypes.kt` | `XYXY_SCORE_CLASS` or `YXYX_SCORE_CLASS` — how model output columns map |
| `OCRResult` | `PlateOCR.kt` | Cleaned plate text + confidence estimate |
| `ModelPrefs` | `LivePlateDetectionScreen.kt` | SharedPreferences wrapper; keys: selected model, per-model conf, show-labels, analysis resolution, `scan_interval_ms` (default 1000) |
