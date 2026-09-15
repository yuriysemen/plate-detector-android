# Roadmap

> This app was split down to detection-only per
> [REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md). Everything below
> about accounts, capture, cloud upload, and model auto-update (previously "Training data
> collection", "Model distribution and auto-update", "Generic-app cleanup + auth robustness", and
> "Cloud dataset upload") now lives in
> [`../android-training-data-collection-app/ROADMAP.md`](../android-training-data-collection-app/ROADMAP.md) instead — that's where the
> code for it lives now too. What follows covers only what's still in this app.
>
> REQ-007 (Play Store compliance) and REQ-030 (release readiness) — both scoped around the
> account/data-collection features this app no longer has — are resolved by this split rather
> than deferred: nothing is collected here, so there's nothing to declare.

## Done

### Camera
- [x] Live camera preview (CameraX, back camera)
- [x] Processing guard: pauses when app goes to background
- [x] Pinch-to-zoom (`detectTransformGestures` → `setZoomRatio`)
- [x] Tap-to-focus (`detectTapGestures` → `FocusMeteringAction`) with animated focus ring
- [x] Zoom level indicator in top bar
- [x] Torch toggle (flashlight button in top bar; auto-off on background; hidden when no flash unit)
- [x] Zoom shortcut buttons — 1×/2×/3× pill buttons at bottom center; filtered to camera's max zoom; highlights active level
- [x] Exposure compensation — EV slider above zoom buttons; reads `ExposureState` range; shows computed EV value; hidden when unsupported
- [x] Analysis resolution control — `DEFAULT`/`LOW (640×480)`/`HD (1280×720)` picker in Settings; uses `ResolutionSelector`; camera rebinds on change
- [x] Scan interval control — RadioButton list in Settings; options: 5 s / 2 s / 1 s / ½ s / No delay (default 1 s); throttle applied per-frame via `rememberUpdatedState`; takes effect immediately without camera rebind

### Detection
- [x] TFLite YOLO inference on each camera frame (~8 fps)
- [x] Letterbox preprocessing (aspect-ratio-preserving resize with black padding)
- [x] Bounding box overlay (fit-center coordinate mapping)
- [x] Confidence threshold slider (per model, persisted in prefs)
- [x] Beep on detection

### OCR
- [x] ML Kit text recognizer on cropped plate region
- [x] OCR result shown in bounding box label
- [x] OCR always enabled — runs automatically on every detection

### Model management
- [x] Bundled default models (downloaded at build time from GitHub Releases via `model_v*` tag; graceful fallback when no release found)
- [x] Multiple models selectable in Settings
- [x] Model description from `.txt` sidecar file
- [x] Model-discovery validation — `PlateDetector.isValidModel()` filters candidate models by their actual tensor shape before they're selectable, and asset discovery is scoped to `assets/models/` only (an earlier whole-assets-root fallback could surface an unrelated `.tflite` bundled by a dependency like ML Kit and crash on construction instead of showing "No detection model found")

### UI
- [x] Settings screen (model picker, confidence slider, scan interval picker, analysis resolution picker)
- [x] "No models" error screen — Retry only; no accounts to sign into (REQ-031)

### Play Store readiness (requirements: REQ-031)
- [x] **Detection-only split** — accounts, capture, cloud upload, and model auto-update removed
      entirely (moved to `android-training-data-collection-app/`); no `INTERNET`/`ACCESS_NETWORK_STATE`/
      `POST_NOTIFICATIONS` permissions; `privacy-policy.md` reverted to (and now factually
      matches) the original "no data collected" baseline.

---

## Backlog

### Camera — easy
- [ ] **Front/back camera toggle** — change `CameraSelector`, rebind. ~1h

### Camera — medium
- [ ] **Faster YUV→Bitmap** — replace current YUV→NV21→JPEG→Bitmap (lossy) with direct YUV→RGB pixel copy. ~half day

### Camera — hard / device-specific
- [ ] **Physical lens switching** (ultrawide/telephoto) — enumerate physical cameras via `Camera2CameraInfo`, custom `CameraSelector` per lens. Multi-day, Samsung-specific.
- [ ] **Night/HDR mode** — CameraX Extensions (`camera-extensions` dep). Device-dependent.

### Detection
- [ ] **Plate-specific model** — current model is generic YOLO; training a license-plate-only model would improve accuracy significantly
- [ ] **NMS / deduplication** — current output may produce overlapping boxes for the same plate; add IoU-based suppression client-side if the exported model doesn't include it

### OCR
- [ ] **OCR confidence filtering** — discard low-confidence reads (ML Kit doesn't expose per-char confidence; heuristic: minimum text length, alphanumeric ratio)
- [ ] **Plate format validation** — filter OCR output by known regional formats (e.g. `[A-Z]{2}[0-9]{3}[A-Z]{2}`)
- [ ] **OCR history / log** — show a scrollable list of recently detected plate texts with timestamps

### Events / integrations
- [ ] **Detection event format** — emit structured events (timestamp, plate text, bounding box) for downstream consumers. Format TBD.
- [ ] **Notification on detection** — system notification when a plate is detected (background use case)

### Settings
- [ ] **Per-model class filter** — let user pin detection to a specific class ID (e.g. class 0 = plates only)

### Auto-parking settings (requirements: REQ-017)
- [ ] **Auto-parking settings auto-configuration** — detect device capability on first enable; apply High-quality / Balanced / Efficient preset based on camera resolution and CPU cores; one-time informational banner; "Reset to recommended defaults" button in Settings (REQ-017)

### Parking access control (requirements: REQ-009 – REQ-010)
- [ ] **Vehicle type classifier** — MobileNetV2 TFLite model; classifies full frame as civilian / police / ambulance / fire_truck / military; runs in parallel with plate detector
- [ ] **Access decision UI** — AUTO-ALLOW (green) / CHECK PLATE (blue) / HOLD (orange) banner; majority-vote stability filter; configurable uncertain-type behavior and military auto-allow toggle in Settings
- [ ] **Access decision log** — local CSV, 90-day retention, exportable via share sheet; excluded from Auto Backup
- [ ] **Civilian plate database** — lookup collected plate against an allowed list (local SQLite or remote API); design TBD
