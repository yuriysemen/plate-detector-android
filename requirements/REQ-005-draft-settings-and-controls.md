---
id: REQ-005
title: Settings UI and Preference Keys for Data Collection
status: draft
priority: high
---

## Summary

Define what the user can control, where those controls appear in the app, and how preferences are persisted.

## New settings (all in SettingsScreen)

### 1. Enable data collection (toggle)

- Label: "Collect training data"
- Sub-label: "Saves detected frames and bounding boxes to device storage for model training."
- Type: Checkbox (same style as "Enable OCR")
- Default: **off**
- Must be opt-in. The feature must never activate without explicit user action.
- When toggled on for the first time, show a one-time confirmation dialog explaining:
  - What is saved (camera frames + bounding boxes)
  - Where it is saved (on this device only)
  - How to delete it

### 2. Minimum confidence for saving (slider)

- Label: "Save threshold: X%"
- Type: Slider, range 0.10–0.95, step 0.05
- Default: current model confidence threshold (reuse the per-model conf pref as default, but stored separately so changing detection threshold doesn't silently change collection threshold)
- Purpose: avoid filling storage with low-confidence guesses. A separate threshold gives independent control over what is shown vs. what is saved.

### 3. Storage usage display (read-only info row)

- Label: "Storage used: X MB (N frames)"
- Updated each time the Settings screen opens (scan `training_data/` directory).
- Not a preference — derived at runtime.

### 4. Clear collected data (button)

- Label: "Clear collected data"
- Shows a confirmation dialog: "Delete N frames? This cannot be undone."
- On confirm: deletes all files under `training_data/`, resets manifest.

### 5. Export collected data (button)

- Label: "Export dataset"
- Enabled only when frame count > 0.
- Action:
  1. Write `dataset.yaml` into `training_data/`.
  2. Zip `training_data/` into a temp file.
  3. Launch Android share sheet (`ACTION_SEND`) with the zip file.
- User can share to Files, Google Drive, AirDrop (via nearby share), email, etc.

## Preference keys (SharedPreferences, same `model_prefs` store)

| Key | Type | Default | Description |
|---|---|---|---|
| `collect_training_data` | Boolean | false | Master on/off switch |
| `collect_min_confidence` | Float | 0.5 | Min score to save a frame |
| `collect_first_time_shown` | Boolean | false | Whether the first-time dialog has been shown |

## Where in SettingsScreen

Add a new section below "Analysis resolution" and above the "Custom Model" button:

```
─────────────────────────────────
Training data collection
─────────────────────────────────
[Checkbox] Collect training data
           Saves frames with detections to device storage

Save threshold: 50%     [slider]

Storage used: 14.2 MB (247 frames)  [info, updates on open]

[EXPORT DATASET]   [CLEAR DATA]
─────────────────────────────────
```

## Changes to LiveDetectionUi / CameraPreviewWithAnalysis

- The saver is called **only** when `collectEnabled && detections.isNotEmpty()`. Frames with zero detections are never saved (REQ-001 decision).
- The saver runs after the detection + OCR step, before posting results to the main thread, on the same background analysis thread (no extra threading needed).
- Saver failure (disk full, I/O error) is caught and logged; it must never crash the analyzer or block the frame pipeline.
- There is no in-app mechanism to mark a detection as wrong or to save hard negatives. That responsibility belongs to the separate editing app.

## Acceptance criteria

- [ ] Collection toggle is off by default; switching it on shows the one-time dialog on first activation.
- [ ] Disabling collection mid-session does not delete already-saved frames.
- [ ] The "Storage used" line reflects the actual directory size, not an in-memory counter.
- [ ] Exporting while collection is active does not corrupt in-progress writes (implement by writing to a temp file then renaming, or by pausing collection during zip).
- [ ] Clearing data while collection is active resets correctly without leaving orphaned files.
