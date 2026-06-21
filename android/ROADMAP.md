# Roadmap

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
- [x] Frame rate control — 1–15 fps slider in Settings; throttle derived per-frame via `rememberUpdatedState`; takes effect immediately without camera rebind

### Detection
- [x] TFLite YOLO inference on each camera frame (~8 fps)
- [x] Letterbox preprocessing (aspect-ratio-preserving resize with black padding)
- [x] Bounding box overlay (fit-center coordinate mapping)
- [x] Confidence threshold slider (per model, persisted in prefs)
- [x] Beep on detection

### OCR
- [x] ML Kit text recognizer on cropped plate region
- [x] OCR result shown in bounding box label
- [x] Enable/disable OCR toggle in Settings

### Model management
- [x] Bundled default models (downloaded at build time from GitHub Releases)
- [x] Custom model import (copy to internal storage, validate TFLite flatbuffer)
- [x] Multiple models selectable in Settings
- [x] Model description from `.txt` sidecar file
- [x] Delete custom/external models

### UI
- [x] Settings screen (model picker, confidence slider, OCR toggle, import button)
- [x] "No models" error screen with retry + file picker

### Training data collection
- [x] Opt-in toggle ("Collect training data" Switch in Settings); default off; persisted in SharedPreferences (`collect_training_data`)
- [x] First-time consent dialog on first enable — explains what is saved, where, and how to delete; acknowledgement persisted (`collect_first_time_shown`); subsequent toggles skip the dialog
- [x] `manifest.json` tracks `next_seq`, `total_frames`, `total_detections`, `multi_detection_frames`, and collection date range; updated after every saved frame; survives app restarts
- [x] Dataset export — creates `plates_dataset_<timestamp>.zip` in `filesDir/exports/` with frames randomly shuffled and split into `train/`, `val/`, `test/` subdirectories (default 70/20/10; configurable via sliders on the Export screen); auto-resets collected data on success; share sheet opens immediately
- [x] Exported files list — scrollable list on Export screen with per-file Share, Rename, and Delete actions

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

### Training data collection (requirements: REQ-001 – REQ-008)
- [ ] **Storage quota + LRU eviction** — 500 MB cap; oldest frames evicted automatically (REQ-006)
- [ ] **Play Store compliance** — Privacy Policy update, Auto Backup exclusion, Data Safety declaration (REQ-007)

### Parking access control (requirements: REQ-009 – REQ-010)
- [ ] **Vehicle type classifier** — MobileNetV2 TFLite model; classifies full frame as civilian / police / ambulance / fire_truck / military; runs in parallel with plate detector
- [ ] **Access decision UI** — AUTO-ALLOW (green) / CHECK PLATE (blue) / HOLD (orange) banner; majority-vote stability filter; configurable uncertain-type behavior and military auto-allow toggle in Settings
- [ ] **Access decision log** — local CSV, 90-day retention, exportable via share sheet; excluded from Auto Backup
- [ ] **Civilian plate database** — lookup collected plate against an allowed list (local SQLite or remote API); design TBD
