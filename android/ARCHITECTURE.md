# Architecture

## Overview

Single-Activity, fully Jetpack Compose app. No navigation library — navigation is manual state in `LivePlateDetectionScreen`. No ViewModel — all state lives in composable `remember`/`rememberSaveable` blocks.

## Screen flow

```
MainActivity
  └── LivePlateDetectionScreen          (top-level coordinator)
        ├── NoModelsScreen              (no .tflite assets found)
        ├── SettingsScreen              (model picker + sliders + OCR toggle)
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
  └─ PlateOCR.recognizePlate()  (if enableOCR && detections not empty)
        ├─ crop+pad bitmap to detection bounds
        └─ ML Kit TextRecognizer → clean alphanumeric text

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

`ModelPrefs` (SharedPreferences) persists: selected model ID, per-model confidence threshold, show-labels flag, OCR-enabled flag.

A `.txt` sidecar file with the same base name as a `.tflite` is shown as the model description in Settings.

## Camera

`CameraPreviewWithAnalysis` binds CameraX `Preview` + `ImageAnalysis` to the lifecycle inside a `DisposableEffect`. It exposes the `Camera` object and `MeteringPointFactory` via `onCameraReady` so `LiveDetectionUi` can attach gesture controls without coupling camera setup to UI logic.

The overlay `Canvas` (sibling of the camera view in a `Box`) handles:
- Bounding box + label rendering (fit-center coordinate mapping matches `PreviewView.ScaleType.FIT_CENTER`)
- Pinch-to-zoom via `detectTransformGestures` → `camera.cameraControl.setZoomRatio()`
- Tap-to-focus via `detectTapGestures` → `FocusMeteringAction` + animated focus ring

Processing is suppressed when the app is not in the foreground (`ON_STOP` lifecycle event) and `keepScreenOn` is tied to the same flag.

## Key types

| Type | File | Purpose |
|---|---|---|
| `Detection` | `PlateDetector.kt` | Bounding box in original-image pixels + score + classId + OCR result |
| `ModelSpec` | `ModelSpec.kt` | Per-model metadata (id, title, source, conf, origin) |
| `ModelSource` | `ModelTypes.kt` | Sealed: `Asset(path)`, `FilePath(file)`, `ContentUri(uri)` |
| `CoordFormat` | `ModelTypes.kt` | `XYXY_SCORE_CLASS` or `YXYX_SCORE_CLASS` — how model output columns map |
| `OCRResult` | `PlateOCR.kt` | Cleaned plate text + confidence estimate |
| `ModelPrefs` | `LivePlateDetectionScreen.kt` | SharedPreferences wrapper (private object) |
