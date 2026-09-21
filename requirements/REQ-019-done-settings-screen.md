---
id: REQ-019
title: Settings Screen Layout and Controls
status: done
priority: high
---

> **Partially split by [REQ-031](REQ-031-done-split-detection-and-training-apps.md):** sections 1–4 below (model selection, confidence threshold, scan interval, analysis resolution) are unchanged and still exactly how `android-end-user-app/`'s `SettingsScreen` works today. Only section 5 ("Contribute data row") and later, describing sign-in and data-collection controls, are `android-training-data-collection-app/`-only now.

## Summary

Defines the full layout, controls, and preference persistence for `SettingsScreen`. The screen is opened from the hamburger button in `LiveDetectionUi` and closed via the system Back gesture (which also commits the selected model).

---

## Layout (top to bottom)

### 1. Model selection card

An `OutlinedCard` containing a scrollable list of all available models followed by a "Custom Model" footer row. All items are inside the card with a `HorizontalDivider` separating the list from the footer.

**Model list rows** (one per `ModelSpec`):
- RadioButton on the left — selected when this model is active.
- Title (`ModelSpec.title`) in `titleMedium`.
- Source label (origin: bundled / custom) in `bodySmall`.
- Optional description from `.txt` sidecar in `bodySmall` (max 3 lines, ellipsized).
- Delete `IconButton` on the right — shown only for deletable models (`ModelSpec.isDeletable`).
- Tapping the row or the RadioButton selects the model locally (not committed until Back).

**Footer row ("+ Custom Model")**:
- Full-width `TextButton` with an `Add` icon, below a `HorizontalDivider`.
- Tapping opens a system file picker filtered to `.tflite` files.
- On selection: copied to `filesDir/models/custom/`, validated (TFLite header check), then appears in the list.

### 2. Confidence threshold

- Label: `"Confidence threshold: X.XX"` — live-updated as the slider moves.
- `Slider` with `valueRange = 0.05f..0.95f`.
- Applies to the **currently selected** model; stored per-model in `model_prefs`.
- Shown only when at least one model is available.

### 3. Scan interval

- Label: `"Scan interval: <current label>"` — e.g. `"Scan interval: 1 second"`.
- Discrete `Slider` with 5 snap positions mapped to non-linear ms values:

| Step | Value | Label |
|---|---|---|
| 0 | 5000 ms | 5 seconds |
| 1 | 2000 ms | 2 seconds |
| 2 | 1000 ms | 1 second (default) |
| 3 | 500 ms | ½ second |
| 4 | 0 ms | No delay |

- Stored in `model_prefs` key `scan_interval_ms` (default `1000`).
- Takes effect immediately without camera rebind (applied per-frame via `rememberUpdatedState`).

### 4. Analysis resolution

> **Amended by [REQ-042](REQ-042-done-collection-frame-size-restriction.md)** for the collection app: the `Default` option is removed and the stored default is `LOW`. Text below is the original spec.

- Label: `"Analysis resolution"` in `titleMedium`.
- RadioButton list with one row per `AnalysisResolution` entry:
  - Primary text: `res.label` (e.g. `"Default"`, `"Low (640×480)"`, `"HD (1280×720)"`).
  - Secondary text: `res.detail` (description of trade-off).
- Stored in `model_prefs` key `analysis_resolution`.
- Changing resolution fully rebinds the camera (via `key(spec.id, analysisResolution)` on `CameraPreviewWithAnalysis`).

### 5. Contribute data row

- Defined in full by **REQ-005**.
- Row with title `"Contribute data"`, summary line (on/off state), and a `Switch` on the right.
- First enable shows consent dialog (REQ-005).
- Tapping the row navigates to `ContributeScreen`.

---

## Navigation and commit behaviour

- `SettingsScreen` is displayed inside `LivePlateDetectionScreen` in place of `LiveDetectionUi` when `showSettings == true`.
- Model selection is **local** while the screen is open (stored in `rememberSaveable`); it is committed to `ModelPrefs` and the caller only when Back is pressed (`BackHandler`).
- Confidence changes are committed immediately via `onConfidenceChange` (called in `onValueChange`).
- Scan interval and analysis resolution changes are committed immediately via their respective `onChange` callbacks.

---

## OCR

OCR (ML Kit text recognition) is always enabled. There is no toggle in Settings. It runs automatically on every frame where detections are not empty.

---

## Preference keys (`model_prefs` SharedPreferences)

| Key | Type | Default | Description |
|---|---|---|---|
| `selected_model_id` | String | first bundled model | ID of the active model |
| `conf_<modelId>` | Float | 0.5 | Per-model confidence threshold |
| `show_labels` | Boolean | true | Whether to draw label text on bounding boxes |
| `scan_interval_ms` | Int | 1000 | Time between analysed frames in ms (0 = no delay) |
| `analysis_resolution` | String | `"DEFAULT"` | `AnalysisResolution` enum name |
| `training_data_quota_mb` | Int | 500 | Max storage for collected frames (also used by REQ-005/REQ-011) |

Collect / upload preferences are listed in REQ-005.

---

## Acceptance criteria

- [ ] All available models are listed inside a single `OutlinedCard`; the "+ Custom Model" button is inside the same card below a divider.
- [ ] Selecting a model updates the RadioButton immediately; the model is not applied until Back is pressed.
- [ ] Importing a `.tflite` file copies it to internal storage, validates it, and adds it to the list in the same session.
- [ ] Deletable models show a delete button; bundled models do not.
- [ ] Confidence threshold slider shows the current value in the label and updates live.
- [ ] Scan interval slider snaps to exactly 5 positions; the label updates to reflect the selection.
- [ ] Analysis resolution picker shows all `AnalysisResolution` options with label and detail text.
- [ ] Changing analysis resolution causes the camera to rebind.
- [ ] There is no OCR toggle anywhere in the UI.
- [ ] The "Contribute data" row appears at the bottom; its Switch and navigation behaviour follow REQ-005.
- [ ] All preference changes survive app restart.
