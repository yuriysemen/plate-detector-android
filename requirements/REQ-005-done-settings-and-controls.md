---
id: REQ-005
title: Settings UI and Preference Keys for Data Collection
status: done
priority: high
---

## Summary

Defines what the user can control regarding training data collection, where those controls appear in the app, and how preferences are persisted.

## Settings in SettingsScreen

### 1. Enable data collection (toggle)

- Label: "Collect training data"
- Type: Switch (same style as "Enable OCR")
- Default: **off**
- Must be opt-in. The feature never activates without explicit user action.
- When toggled on for the first time, a one-time confirmation dialog explains:
  - What is saved (camera frames + bounding boxes)
  - Where it is saved (on this device only)
  - How to delete it (Export dataset → Reset collected data)
- After the dialog is acknowledged, subsequent toggles skip it.

### 2. Export dataset (button)

- Label: "Export dataset" with archive icon
- Navigates to the dedicated ExportScreen.

## ExportScreen

- **Stats card** — frames collected, total detections, collection date range; read from `manifest.json` each time the screen opens.
- **Dataset split sliders** — configurable train / val / test percentages (default 70 / 20 / 10); test percentage is derived (100 − train − val).
- **Reset collected data** — button (enabled when frames > 0) with confirmation dialog: "Delete N frames? This cannot be undone." Deletes all files under `training_data/` and resets the manifest.
- **Export dataset** — button (enabled when frames > 0); creates `plates_dataset_<timestamp>.zip` in `filesDir/exports/` with frames shuffled and split per the configured ratios; opens the system share sheet immediately after; collected data is auto-reset on success.
- **Previous exports list** — scrollable list of past ZIP files with per-file Share, Rename, and Delete actions.

## Preference keys (`model_prefs` SharedPreferences)

| Key | Type | Default | Description |
|---|---|---|---|
| `collect_training_data` | Boolean | false | Master on/off switch |
| `collect_first_time_shown` | Boolean | false | Whether the first-time consent dialog has been shown |

## Changes to LiveDetectionUi / CameraPreviewWithAnalysis

- The saver is called **only** when `collectEnabled && detections.isNotEmpty()`. Frames with zero detections are never saved (REQ-001 decision).
- The saver runs after detection + OCR, before posting results to the main thread, on the same background analysis thread — no extra threading needed.
- Saver failure (disk full, I/O error) is caught and logged; it never crashes the analyzer or blocks the frame pipeline.
- There is no in-app mechanism to mark a detection as wrong or to save hard negatives. That responsibility belongs to the separate editing app.

## Acceptance criteria

- [x] Collection toggle is off by default; switching it on for the first time shows the consent dialog.
- [x] Disabling collection mid-session does not delete already-saved frames.
- [x] Exporting while collection is active does not corrupt in-progress writes (export writes to a temp ZIP then renames).
- [x] Clearing data while collection is active resets correctly without leaving orphaned files.
