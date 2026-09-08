---
id: REQ-011
title: Dataset Editor and Configurable Storage Quota
status: done
priority: high
note: Dataset Editor + Frame Detail sections removed by REQ-026 (all editing moved to curation-android). The configurable storage-quota section remains in force.
---

## Summary

Add a **Dataset Editor** screen that lets the user review, correct, and prune collected training images before they are exported as a YOLO dataset. Editing happens on the raw on-device data (`training_data/images/` + `training_data/labels/`) and is fully decoupled from the export pipeline (REQ-002 export logic is unchanged).

Also replace the hidden 500 MB developer constant from REQ-006 with a **user-configurable storage quota** in Settings. Collection pauses automatically when the quota is reached, and a warning appears when usage crosses a configurable warning threshold.

---

## Pipeline position

```
Collection (REQ-001/002) → [Editor — NEW] → Export / ZIP (REQ-002, unchanged)
```

The editor writes changes back to `training_data/images/` and `training_data/labels/`. The export step reads those same files; it requires no knowledge of the editor.

---

## Entry point

The **"View dataset"** button is on ContributeScreen (REQ-005), in the bottom-left of the stats card alongside the "Reset collected data" button. The button is disabled when `total_frames == 0`.

---

## Dataset Editor screen

### Layout

A scrollable grid of image thumbnails, two columns wide. Each thumbnail:

- Displays the JPEG frame.
- Draws all bounding boxes for that frame as thin coloured rectangles overlaid on the thumbnail (same colour for all boxes; no labels needed at this scale).
- Shows the frame sequence number below the thumbnail (e.g. `#00000042`).
- Shows the number of detections for that frame (e.g. `2 boxes`).

Thumbnails are sorted by sequence number, ascending (same order as collection).

A **counter** in the screen title or top bar shows the total frame count (e.g. `Edit dataset — 247 frames`). The counter updates live as frames are deleted.

### Selecting and deleting multiple frames

- Long-press a thumbnail to enter **selection mode**. A checkmark overlay appears on the pressed thumbnail and a contextual action bar appears at the top.
- In selection mode, tapping additional thumbnails toggles their selection.
- The action bar shows the selected count (e.g. `3 selected`) and a **Delete** icon button.
- Tapping Delete in the action bar shows a confirmation: `"Delete 3 frames? This cannot be undone."` On confirmation, all selected frames are removed (image + label files) and `manifest.json` is updated.
- Pressing **Back** or tapping outside the selection exits selection mode without deleting.

### Opening a frame for detail editing

Tapping a thumbnail (outside selection mode) opens a **Frame Detail screen**.

---

## Frame Detail screen

The screen has two explicit modes: **view** (default) and **edit**.

### View mode

The default state when opening a frame. Read-only — no accidental edits.

- Full-screen display of the JPEG image with all bounding boxes drawn as coloured rectangles (no handles, no selection).
- Top bar: `←` Back · frame name · ✏ Edit button.
- Back navigates to the grid immediately — no confirmation dialog.
- Pinch-to-zoom and double-tap-to-reset work in both modes.

### Edit mode

Entered by tapping the ✏ button. Allows modifying boxes.

- Top bar: `Cancel` · frame name · `×` delete-box icon (visible when a box is selected) · `✓` Save (enabled when dirty, spinner while saving) · `⋮` overflow (Delete frame).
- Cancel / Back: exits edit mode without saving. Shows `"Discard unsaved changes?"` dialog if there are unsaved edits; confirming restores boxes to their state at the time edit mode was entered.
- Saving returns to the grid.

### Editing a bounding box (edit mode only)

- Tap inside a box to **select** it (highlighted border + 8 resize handles appear).
- **Drag** the body to reposition; **drag** a corner or edge handle to resize.
- Box is constrained to image bounds at all times.
- Coordinates are re-normalised to [0, 1] on Save.

### Adding a new box (edit mode only)

A **`+`** FAB is always visible at the bottom-right while not drawing. Tapping enters draw mode: drag to draw a new box. The new box snaps to a minimum size if the drag is too short. Class is always `0` (plate).

### Deleting a box (edit mode only)

Tap a box to select it, then tap `×` in the top bar. If the last box is deleted: `"No boxes remain. Delete the frame entirely?"` — Yes deletes the frame and returns to the grid; No keeps the frame with an empty `.txt`.

### Saving edits

Tapping `✓` Save:
1. Rewrites the `.txt` file in YOLO normalised format (`0 x_center y_center width height` per box).
2. Updates `manifest.json` (`total_detections` recalculated).
3. Returns to the thumbnail grid.

Save runs on a background thread; a spinner replaces the `✓` icon while saving.

### Deleting a frame (edit mode only)

`⋮` → "Delete frame" → `"Delete this frame? This cannot be undone."` On confirmation:
1. Deletes the `.jpg` and `.txt` files.
2. Decrements `manifest.json` counters.
3. Returns to the grid.

---

## Configurable storage quota

### Setting

The quota is not a separate Settings page entry. It is edited via a small **✏ edit icon** on the "Storage used" line of the stats card in ContributeScreen:

- Tapping the icon opens an `AlertDialog` with an `OutlinedTextField` pre-filled with the current quota.
- Units are always MB (no MB/GB switcher).
- **Default:** `500 MB`.
- **Minimum:** 100 MB.
- **Persistence:** stored in `SharedPreferences` key `training_data_quota_mb` as an integer number of megabytes.

The hidden developer constant in REQ-006 is superseded by this setting and must be removed.

### Warning threshold

When total storage used by `training_data/` crosses **80%** of the configured quota, a persistent **yellow warning banner** appears:

- On the main camera screen (below or above the existing overlay).
- On the ContributeScreen.
- Text: `"Training storage at X% — consider exporting or editing your dataset."` where X is the integer percentage.

The warning is dismissed automatically when usage drops below 80% (e.g. after the user edits/deletes frames or resets).

The 80% threshold is a code constant (not user-configurable).

### Collection pause at quota

When total storage used reaches or exceeds the configured quota:

1. **Collection is paused** — the frame saver stops writing new frames, regardless of the toggle state. The toggle remains on visually; collection resumes as soon as usage drops below quota (e.g. after editing or exporting+resetting).
2. A **red banner** is shown on the camera screen: `"Storage limit reached (X MB). Export or edit your dataset to continue collecting."` with a shortcut button: **"Edit"** → navigates to the Dataset Editor.
3. The collection toggle in Settings shows the same red banner below it.

Storage is measured by summing the sizes of all files under `training_data/images/` and `training_data/labels/`. `manifest.json` is negligible and excluded from the calculation.

Storage is re-checked:
- Before each frame write (existing behavior from REQ-006 LRU check point, repurposed).
- When the Settings page is opened.
- When the ContributeScreen is opened.
- Lazily on the camera screen (re-checked every 5 seconds while collection is on, not per frame).

### Interaction with LRU eviction (REQ-006)

The LRU auto-eviction strategy described in REQ-006 is **disabled** by default when this editor is present. Auto-deleting oldest frames silently removes potentially curated data, which is worse than pausing collection and prompting the user to act. The REQ-006 LRU implementation may be removed or hidden behind a separate developer toggle.

---

## manifest.json changes

No new fields are required. The editor updates existing fields after mutations:

| Field | Updated when |
|---|---|
| `total_frames` | Frame deleted |
| `total_detections` | Frame deleted, or boxes edited and saved |

`next_seq` is never modified by the editor (gaps in the sequence from deletions are harmless).

---

## Performance constraints

| Operation | Target |
|---|---|
| Grid thumbnail load (visible items) | < 150 ms per thumbnail (load from disk + decode + overlay) |
| Frame Detail image display | < 300 ms to show full-res image with boxes |
| Box drag frame rate | ≥ 30 fps while dragging a handle |
| Save label file | < 100 ms (small text file write) |
| Delete frame | < 200 ms |

Thumbnails should be loaded asynchronously using an image loading library (e.g. Coil) with the bounding boxes drawn as a post-processing step on the decoded bitmap. Thumbnails may be down-sampled to a fixed size (e.g. 256×256) for memory efficiency; the full-res JPEG is loaded only in the Frame Detail screen.

---

## Acceptance criteria

### Dataset Editor — grid

- [x] "View dataset" button appears in the stats card on ContributeScreen; it is disabled when `total_frames == 0`.
- [x] Grid shows one thumbnail per collected frame with overlaid bounding boxes.
- [x] Frame count in the title updates immediately when frames are deleted.
- [x] Long-press enters selection mode; tapping additional thumbnails toggles them.
- [x] Batch delete removes all selected frames (images + labels) after confirmation and updates `manifest.json`.

### Dataset Editor — Frame Detail

- [x] Tapping a thumbnail opens Frame Detail in **view mode** — boxes shown read-only, no handles.
- [x] Tapping ✏ Edit enters **edit mode**; tapping Cancel exits edit mode and restores boxes if changes were made.
- [x] Back in view mode navigates to the grid without a dialog.
- [x] Back / Cancel in edit mode with unsaved changes shows `"Discard unsaved changes?"` and returns to view mode on confirm.
- [x] Each box can be repositioned by dragging its body (edit mode).
- [x] Each box can be resized by dragging corner or edge handles (edit mode).
- [x] Boxes cannot be dragged outside the image bounds.
- [x] `+` FAB is always visible in edit mode when not drawing; tapping enters draw mode regardless of selection state.
- [x] Deleting the last box in a frame prompts the user to delete the frame or keep it empty.
- [x] Save rewrites the `.txt` file with normalized YOLO coordinates.
- [x] Deleting a frame removes both `.jpg` and `.txt` and decrements `manifest.json` counters.

### Storage quota

- [x] ContributeScreen stats card shows a ✏ edit icon on the "Storage used" line; tapping it opens a dialog to edit the quota in MB (minimum 100 MB, default 500 MB).
- [x] The configured quota is persisted across app restarts.
- [x] A yellow warning banner appears on the camera screen and ContributeScreen when usage ≥ 80% of quota.
- [x] When usage reaches the quota, the frame saver stops writing new frames.
- [x] A red banner with an "Edit" shortcut is shown on the camera screen when collection is paused by quota.
- [x] Collection resumes automatically (without toggling off/on) once usage drops below the quota.
- [x] Storage is re-checked on camera screen every 5 seconds when collection is active (not per frame).
- [x] The hidden 500 MB developer constant from REQ-006 is removed; the Settings value is the sole source of truth.
- [x] LRU auto-eviction from REQ-006 is disabled; the user is prompted to act instead of silent deletion.
