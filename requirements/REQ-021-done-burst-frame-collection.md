---
id: REQ-021
title: Burst Frame Collection Mode
status: done
priority: medium
depends_on: REQ-002, REQ-005, REQ-006, REQ-020
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

Add a burst collection mode to the live detection screen. The user sets a target photo count (default 100), taps a burst button to start, and the app saves every analyzed frame (with YOLO labels when detections are available, or empty labels otherwise) until the target is reached. On completion a dialog offers to upload the collected data and start a new round, or stop collecting.

---

## Motivation

Single-frame manual capture (REQ-020) requires the user to tap once per frame. When driving or scanning a parking lot it is more practical to switch into a continuous collection mode that captures all frames automatically, then review and annotate them later in the Dataset Editor. The count cap (default 100) prevents unintended storage use while still allowing the user to configure larger runs.

---

## Burst mode button

### Placement
A `BurstMode` icon button in the **top bar** of `LiveDetectionUi`, immediately to the right of the manual capture button. Visible only when `collect_training_data` is enabled.

- Tapping while **inactive** → opens the setup dialog.
- Tapping while **active** → stops burst collection immediately (same effect as "Stop collecting" in the completion dialog).

### Setup dialog
Shown before starting burst:

| Field | Default | Constraints |
|---|---|---|
| **Count** — photos to collect before pausing | 100 | min 1, max 9999 |

Actions: **Start** / **Cancel**.

---

## While burst is active

- The burst button icon is tinted **yellow**.
- A progress line `"Burst: N / M"` is shown in yellow below the model label in the top bar.
- **Regular auto-save** (collect-training-data, detections-only) is **suspended** — burst saving takes exclusive control of frame writing. This prevents double-saving the same frame.
- The single-shot manual capture button is **disabled** (dimmed, non-interactive) while burst is running.

---

## Frame saving

Every frame processed by the `ImageAnalysis` pipeline is saved, regardless of whether detections are present:

| Detections | Saved via | Label file |
|---|---|---|
| Present (≥ 1) | `TrainingDataSaver.saveFrame()` | YOLO lines (classId xc yc w h) |
| Absent | `TrainingDataSaver.saveFrameManual()` | Empty file (valid hard negative) |

The existing MB-based storage quota check is not applied to burst frames — the user has explicitly started a bounded collection run.

---

## Completion dialog

When `collected == target`, burst stops automatically and a dialog appears (non-dismissable via back gesture):

**Title:** "Collection complete"

**Body:** `"$target photos collected and saved."` + error note if not signed in / URL not configured.

| Button | When shown | Action |
|---|---|---|
| **Send to server** | Signed in and upload URL configured | Enqueues `UploadDatasetWorker` (WorkManager) → resets counter → sets `burstActive = true` (next round starts immediately) → closes dialog |
| **Go to upload screen** | Not signed in or URL not set | Navigates to ContributeScreen → exits burst mode |
| **Stop collecting** | Always (dismiss button) | Sets `burstActive = false` → closes dialog |

After "Send to server" the upload proceeds in the background via `WorkManager`; the camera view stays active and the next collection round begins immediately.

---

## Implementation notes

- `burstActive`, `burstTarget`, `burstCollected` are plain `remember` state (not `rememberSaveable`) — burst resets to off on Activity recreation, which is the correct behaviour (don't resume mid-burst after rotation).
- `onBurstFrameSaved` callback is posted to `mainExecutor` from the analysis thread to keep state mutations on the main thread; the check `if (burstActive)` in the callback handles the case where multiple callbacks are queued before the first one sets `burstActive = false`.
- `enqueueDatasetUpload()` — private top-level helper in `LivePlateDetectionScreen.kt` that mirrors the WorkManager enqueue logic in `ContributeScreen`, including the `"dataset_upload"` tag (REQ-014) — so restarting an upload from ContributeScreen's "Upload collected data" button also cancels a burst-triggered upload still in flight.
- `isSignedIn` and `onUploadNow` are passed from `LivePlateDetectionScreen` to `LiveDetectionUi`; `onUploadNow` is `null` when upload is not configured, so the completion dialog shows the "Go to upload screen" fallback.

---

## Acceptance criteria

- [x] Burst button appears in `LiveDetectionUi` top bar when `collect_training_data` is on; hidden otherwise.
- [x] Setup dialog lets the user configure photo count (default 100, min 1).
- [x] While burst is active: icon is yellow; progress `"Burst: N / M"` shown in yellow below model label; single-shot capture button is disabled.
- [x] Every analyzed frame is saved — frames with detections get YOLO labels; frames without get empty label files.
- [x] Regular auto-save (detections-only) is suspended while burst is running; no double-saves.
- [x] When the target is reached: burst stops automatically; completion dialog appears.
- [x] "Send to server" (signed-in + configured): enqueues upload, resets counter, starts next round without closing the camera view.
- [x] "Go to upload screen" (not signed-in / not configured): navigates to ContributeScreen, exits burst mode.
- [x] "Stop collecting": exits burst mode.
- [x] Tapping the burst button while active stops burst immediately without showing the completion dialog.
- [x] Burst state resets to off on Activity recreation (rotation, background kill).
