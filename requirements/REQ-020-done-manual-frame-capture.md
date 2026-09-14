---
id: REQ-020
title: Manual Frame Capture Button
status: done
priority: medium
depends_on: REQ-002, REQ-005, REQ-006
---

> **Implemented in `android/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `training-android/`, not `android/`.**

## Summary

Add a manual capture button to the live detection screen so the user can save a frame even when the model fails to detect a plate. The saved frame has an empty label file — the user can add bounding boxes later in the Dataset Editor. Empty label files are the natural marker for manually captured "missed" cases, distinguishable from auto-captured frames which always have at least one annotation.

---

## Motivation

The model is not yet trained well enough to catch every plate. When the user sees a plate the model is missing, they need a way to say "capture this frame now" so it can be added to the training dataset and annotated later.

---

## Button behaviour

### Placement
A shutter/camera icon button in the **top bar** of `LiveDetectionUi`, to the right of the existing controls (torch, stats). Visible only when `collect_training_data` is enabled. Hidden (not just disabled) when the feature is off, since it has no purpose without collection.

### On press — within quota
1. Grab the latest analyzed camera frame (same bitmap used by auto-capture, same resolution).
2. Save the frame to `training_data/images/<YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg` using the existing naming convention.
3. Write an **empty** `training_data/labels/<YYYYMMDD>_<HHmmss>_<NNNNNN>.txt` (zero lines — no annotations).
4. Increment `total_frames` in `manifest.json`; `total_detections` is unchanged (no annotations added).
5. Show a short toast: `"Frame saved"`.
6. Start a **1-second cooldown** — button is visually dimmed and non-interactive during this period.

### On press — quota full (100%)
- Do **not** save the frame.
- Show a short toast: `"Storage quota full"`.
- No cooldown started (user can immediately try again after freeing space).
- The 80% warning state does not block capture — frame is saved normally.

### Cooldown visual
During the 1-second cooldown the button icon is rendered at reduced alpha (e.g. 40%) to communicate it is temporarily inactive. No spinner or animation needed.

---

## Data format

Manually captured frames use the **same file format, naming, and directory** as auto-captured frames:

```
training_data/
  images/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.jpg   (JPEG quality 90, same as auto-capture)
  labels/   <YYYYMMDD>_<HHmmss>_<NNNNNN>.txt   (empty file — zero annotation lines)
```

An empty `.txt` label file is valid YOLO format (zero objects in frame). The Dataset Editor and the export pipeline handle it correctly — the frame is included in the ZIP split as a hard negative or later annotated by the user.

**Filtering manually captured frames:** since auto-captured frames always contain ≥ 1 annotation (the `detections.isNotEmpty()` guard prevents saving otherwise), any frame with an empty label file is by definition a manually captured missed case. No separate manifest counter or flag is needed.

---

## Frame source

The button captures the **latest analyzed frame** already held in memory by `LiveDetectionUi` — the same bitmap produced by the `ImageAnalysis` pipeline. This ensures:
- Consistent resolution and JPEG quality with auto-captured frames.
- No additional CameraX use case (`ImageCapture`) required.
- No perceptible delay — capture is immediate.

The latest analyzed bitmap should be exposed as a `State<Bitmap?>` (or similar) from `CameraPreviewWithAnalysis` / `LiveDetectionUi` so the button callback can access it.

---

## Acceptance criteria

- [x] Capture button (shutter icon) appears in the `LiveDetectionUi` top bar when `collect_training_data` is on.
- [x] Button is hidden (not rendered) when `collect_training_data` is off.
- [x] Tapping the button within quota saves a JPEG frame and an empty `.txt` label file with the standard naming convention.
- [x] `total_frames` in `manifest.json` increments by 1; `total_detections` is unchanged.
- [x] Toast `"Frame saved"` is shown on successful capture.
- [x] Button is dimmed and non-interactive for 1 second after a successful capture.
- [x] Tapping the button when quota is full shows toast `"Storage quota full"` and saves nothing; no cooldown.
- [x] Tapping the button at 80% quota (warning state) saves the frame normally.
- [x] Saved frames appear in the Dataset Editor grid alongside auto-captured frames.
- [x] Empty-label frames are included in the export ZIP (valid hard negatives or pending annotation).
- [x] The button does not interfere with the ongoing detection/OCR pipeline.
