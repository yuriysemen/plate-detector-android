---
id: REQ-026
title: Generic App — Remove On-Device Review/Editing; Capture-and-Upload Only
status: done
priority: high
depends_on: REQ-005, REQ-011, REQ-014, REQ-022
supersedes_partial: REQ-011, REQ-012
---

## Summary

The generic `plate-detector-android` app currently collects training frames **and** lets the
user review, correct, prune, and re-annotate them on-device (Dataset Editor + Frame Detail
editor, REQ-011/REQ-012) before they are uploaded. All curation now happens exclusively in the
`curation-android` app (REQ-022–REQ-025).

This requirement strips the generic app down to its collection role: **capture frames and upload
them to the research server**. There is no on-device editing, box manipulation, thumbnail grid,
or per-frame detail view. The app captures, buffers briefly, uploads, and forgets.

Capture is gated on the user being **signed in** with image saving **enabled**. When either
condition is not met, the detection/OCR pipeline runs normally but no frame is written to disk.

---

## Motivation

- The on-device editor duplicates the curation app's box-editing UI (REQ-024/REQ-025 re-implemented
  it independently anyway) with none of the workflow guarantees — it was always a stopgap.
- Editing on the collecting device produces inconsistent curation quality and invites the user to
  discard "ugly" frames that are exactly the hard cases training needs.
- Removing the editor deletes ~1,300 lines (`DatasetEditorScreen`, `FrameDetailScreen`,
  `DatasetEditor`, editor nav state, box-geometry gesture code) and its Coil dependency usage from
  the generic app.
- A capture-only app has a much simpler privacy story: frames are transient, leave the device on a
  schedule, and only exist while a signed-in user has opted in.

---

## Scope

### In scope

- Remove the Dataset Editor screen, the Frame Detail screen, and all on-device box editing.
- Gate frame capture on `signed-in AND collect_training_data`.
- Simplify `ContributeScreen` to a status + upload screen (no "View dataset").
- Keep the existing capture modes: auto-on-detection (REQ-001/004), manual shutter (REQ-020),
  burst (REQ-021).
- Keep the existing upload package format and pipeline (REQ-014) unchanged.
- Keep the model's predicted boxes as the YOLO label file for each auto-captured frame.

### Out of scope / unchanged

- The upload ZIP format, split, `data.yaml`, `UploadDatasetWorker`, pre-signed URL flow,
  auth infra, `AutoUploadWorker` scheduling (REQ-014/REQ-015/REQ-018) — **no change**.
- The `curation-android` app — it keeps consuming the identical package format (REQ-023). No
  curation-side change is required by this requirement.
- Detection, OCR, model management, model auto-update (REQ-016).

---

## Capture gating

### Rule

A frame is written to `training_data/` **only when both** are true at capture time:

1. `collect_training_data` preference is `true`, **and**
2. `CognitoAuthManager.isSignedIn()` is `true` (i.e. a valid, non-expired session).

If either is false, the analysis pipeline still runs detection + OCR and draws overlays; it
simply does not call `TrainingDataSaver`. This applies to **all three capture paths** —
auto-on-detection, manual shutter, and burst.

### Enabling the toggle while signed out

When the user turns on **"Contribute data"** (Settings row, REQ-005) or the equivalent control
while not signed in:

1. Show the first-time consent dialog if not already accepted (updated wording below).
2. On accept, route to `AuthScreen`.
3. The preference is set to `true` regardless of whether sign-in completes — but capture stays
   dormant until a session exists. The Settings summary and `ContributeScreen` show why (see UI).

### Session expiry mid-use

Already handled by the existing session-expiry detection (REQ-014): `isSignedIn()` returns
`false` once the refresh token dies, so capture stops automatically. `ContributeScreen` shows the
existing red "session expired" banner. No new logic — the gating rule above already covers it.

### Sign-out

- Frames already buffered on device are **retained** and upload after the next sign-in.
- The existing behavior (sign-out cancels the auto-upload schedule, clears cached user id/email)
  is unchanged.
- Buffered frames upload under whichever user is signed in at upload time. This is an accepted
  edge case for a single-user device; not worth per-frame user attribution on the client.

---

## Frame content (labels)

Unchanged from today:

| Capture path | Label file written |
|---|---|
| Auto-on-detection (REQ-001/004) | YOLO lines from the model's detections: `classId xc yc w h`, normalized |
| Manual shutter (REQ-020) | Empty `.txt` — the marker for a "model missed this" frame |
| Burst (REQ-021) | YOLO lines when detections present, empty `.txt` otherwise |

The predicted boxes are a **starting point for the curator**, not ground truth. The curation app
(REQ-024) already supports correcting, moving, resizing, adding, deleting, and re-classifying
every box, so shipping the model's guess costs nothing and saves the curator the initial draw on
the common case.

`TrainingDataSaver` needs no changes.

---

## What is removed

### Screens / code deleted

| File | Disposition |
|---|---|
| `DatasetEditorScreen.kt` | Delete |
| `FrameDetailScreen.kt` | Delete |
| `DatasetEditor.kt` | Delete — move the one still-used helper (`trainingUsageBytes()`) to `DatasetExporter` (or a small `TrainingDataStore`) |
| `showEditor` nav branch in `LivePlateDetectionScreen` | Delete |
| Any box-geometry / drag-handle / draw-box gesture code used only by `FrameDetailScreen` | Delete |

### UI removed

- **"View dataset"** button on the `ContributeScreen` stats card.
- The Frame Detail **"✏ Edit"** mode, box handles, `+` add-box FAB, delete-box, discard-changes
  dialog — all gone with the screen.
- Pinch-zoom / double-tap-reset in Frame Detail (REQ-012) — gone with the screen. (Camera-screen
  pinch-zoom is a different feature and stays.)

### Requirements partially superseded

- **REQ-011** — the "Dataset Editor screen" and "Frame Detail screen" sections and their
  acceptance criteria are superseded by this requirement. The **configurable storage quota**
  portion of REQ-011 (quota setting, 80% warning banner, pause-at-quota) **stays** — it is a
  capture-side concern, not editing. Update REQ-011 with a note pointing here.
- **REQ-012** (Frame Detail zoom/pan) — fully superseded for the generic app. Mark `superseded`.
- **REQ-001** — update the feature-scope table row for REQ-011 and add REQ-026; the non-goal note
  "In-app annotation correction … implemented in REQ-011" reverts to a non-goal.
- **REQ-005** — update the consent dialog wording and the ContributeScreen section (below).

---

## ContributeScreen (simplified)

Reached from the "Contribute data" row in Settings (REQ-005). Sections, top to bottom:

### 1. Banners (unchanged logic)

- Storage warning / limit banner at ≥ 80% / ≥ 100% of quota.
- "Session expired — sign in again" (red) or "Not signed in — frames are not being saved"
  (informational) — see below.

### 2. Status card (replaces the stats card)

- `Frames waiting to upload: N` (was "Frames collected") — this is the local buffer count, which
  drops to 0 after each successful upload.
- `Total boxes: N` (was "Total detections") — optional, keep if cheap.
- `Storage used: X.XXX / <quota> MB` with the ✏ quota-edit icon — **kept** from REQ-011.
- **"Reset collected data"** button — kept; deletes the local buffer (`training_data/`), confirm
  dialog `"Delete N buffered frames? This cannot be undone."`
- **No "View dataset" button.**

### 3. Capture status line (new)

One line stating whether capture is currently active:

| Condition | Text |
|---|---|
| toggle on + signed in | `"Saving frames — signed in as <email>"` |
| toggle on + not signed in | `"Paused — sign in to save frames"` + inline **Sign in** button |
| toggle on + session expired | `"Paused — session expired"` + inline **Sign in** button |
| toggle off | `"Image saving is off"` |

### 4. Upload configuration card (unchanged — REQ-005)

Auth status row, "Use mobile data" toggle, "Daily auto-upload time" row, "Last auto-upload" line.

### 5. "Upload collected data" button (unchanged — REQ-014)

Enabled when `frames > 0 AND signed in AND not in 60s cooldown`. Same restart-everything
semantics.

### 6. Upload history (unchanged — REQ-014)

Active + up to 10 recent entries, per-item restart, progress percentage.

---

## Settings changes (REQ-005)

### "Contribute data" row

- Summary when on + signed in: `"On — saving and uploading frames"`
- Summary when on + not signed in: `"On — sign in to start saving frames"`
- Summary when off: `"Off — no frames are saved"`

### Consent dialog (updated wording)

```
Contribute training data?

When enabled and you are signed in, the app will:
• Save camera frames whenever a plate is detected.
• Upload them to a private research server to improve plate detection.

Frames are held on this device only until the next upload, then deleted.
Nothing is saved while you are signed out.

[Cancel]   [Enable]
```

Removes the "stored under a private device identifier" / "tap Reset to delete" framing that
implied durable on-device retention. Self-service reset still exists on `ContributeScreen`.

---

## Storage / buffer behavior

- `training_data/` is now a short-lived **upload buffer**, not a curated dataset.
- The REQ-011 quota mechanism is retained as a safety cap:
  - Default 500 MB, min 100 MB, key `training_data_quota_mb`.
  - 80% → yellow warning banner (camera screen + ContributeScreen).
  - 100% → auto-capture pauses; red banner on camera screen. Because uploads now run on a daily
    schedule and delete their ZIP on success, hitting the cap should be rare (only if the device
    is offline or signed out for a long stretch while the toggle is on).
  - Burst mode continues to bypass the cap (REQ-021 — bounded run).
- After a successful upload the buffer is cleared by the existing `resetCollectedData()` inside
  `exportSync()` — unchanged.

---

## Acceptance criteria

### Removal

- [x] `DatasetEditorScreen.kt` and `FrameDetailScreen.kt` are deleted; no navigation path reaches
      an editor or frame-detail view anywhere in the app.
- [x] `DatasetEditor.kt` is deleted; `ContributeScreen` computes storage usage via
      `DatasetExporter.trainingUsageBytes()`.
- [x] "View dataset" button no longer appears on `ContributeScreen`.
- [x] The app compiles with no unused references to the removed screens; no dead box-geometry
      gesture code remains (`:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest` all pass).
- [x] REQ-011 and REQ-012 are annotated as partially / fully superseded; REQ-001's table is
      updated and REQ-026 added.

### Capture gating

- [x] With the toggle on and a valid session, auto-capture, manual shutter, and burst all write
      frames as before.
- [x] With the toggle on but signed out, none of the three paths write any file to
      `training_data/`; detection + OCR + overlays still work. (`captureActive = collectTrainingData && isSignedIn`
      gates `CameraPreviewWithAnalysis`, the capture buttons, and `onLatestFrame`.)
- [x] With the toggle on but the session expired, capture is dormant — `isSignedIn()` already
      returns `false` on refresh-token expiry (REQ-014), so `captureActive` is false.
- [x] Turning the toggle on while signed out shows the consent dialog (first time), then routes to
      `AuthScreen` (`showAuth = true` in `onCollectTrainingDataChange`), and capture begins once
      sign-in succeeds.
- [x] Turning the toggle off stops capture immediately; buffered frames are not deleted.
- [x] Signing out retains buffered frames; they upload after the next sign-in (sign-out path
      unchanged — only cancels the auto-upload schedule).

### Frame content

- [x] Auto-captured frames still ship a YOLO `.txt` with the model's predicted boxes
      (`TrainingDataSaver` unchanged).
- [x] Manual-shutter frames still ship an empty `.txt`.
- [x] The uploaded ZIP is byte-for-byte the same format the curation app consumes today
      (train/val/test split + `data.yaml`); `DatasetExporter` unchanged, `curation-android` untouched.

### ContributeScreen

- [x] Status card shows "Frames waiting to upload: N" and drops to 0 after a successful upload.
- [x] The capture-status line reflects all four states (on+signed-in, on+signed-out,
      on+expired, off) with the correct text and inline Sign-in button where specified.
- [x] "Reset collected data" clears `training_data/` after confirmation.
- [x] Quota ✏ edit, 80% warning, and 100% pause behaviors from REQ-011 still work.
- [x] Upload configuration card, "Upload collected data" button, and Upload history are unchanged
      in behavior.

### Settings

- [x] The "Contribute data" row summary reflects the signed-in / signed-out distinction.
- [x] The consent dialog uses the updated wording.

> **Verification gap:** built + unit-tested only (`assembleDebug`, `testDebugUnitTest` green, JDK via
> Android Studio's bundled JBR). No on-device run of the capture-gating / sign-in-routing flow yet —
> same gap noted for REQ-023/024/025.

---

## Migration (users upgrading from an editor build)

- Any frames already buffered in `training_data/` are treated as a normal upload buffer: they
  upload on the next scheduled or manual upload and are then deleted. No forced migration, no
  data loss beyond the normal upload-then-delete cycle.
- No preference migration needed — `collect_training_data`, `training_data_quota_mb`, and all
  `upload_prefs` keys keep their meaning.
