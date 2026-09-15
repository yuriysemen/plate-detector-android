---
id: REQ-002
title: YOLO Format Specification and Directory Structure
status: done
priority: high
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

Define exactly how images and annotations are stored on device so the exported dataset can be fed directly into the `training/ultralytics/` pipeline without conversion.

## Settings flag

Training data collection is controlled by a `Switch` in the Settings page, placed on the same row as the **Export dataset** navigation entry (right side of the row).

- **Default:** off.
- **Persistence:** stored in `SharedPreferences` key `collect_training_data` so the value survives app restarts.
- **Effect when off:** the frame saver is never invoked; no images or labels are written. All other collection infrastructure (directory structure, manifest) remains intact.
- **Effect when on:** collection resumes from where it left off (`next_seq` from `manifest.json`).

## YOLO annotation format

Each image has a paired `.txt` file with one line per detected object:

```
<class_id> <x_center> <y_center> <width> <height>
```

- All five values are floating-point.
- `x_center`, `y_center`, `width`, `height` are **normalized to [0, 1]** relative to the saved image's pixel dimensions.
- Multiple detections in the same frame = multiple lines in the same `.txt` file.
- A frame with no detections is **not saved at all** (decided in REQ-001). The saver is only invoked when `detections.isNotEmpty()`.
- `class_id` is **`0` at collection time** (single-class plate detection). The **curation app** (REQ-025) reassigns it to a vehicle-type class (`0` license_plate, `1` civil, `2` police, `3` fire, `4` medical, `5` other) — so a curated dataset in `done/` has `nc > 1` and the class column is meaningful. See `config/vehicle-categories.json` and REQ-025.

Example for two plates detected in one frame:

```
0 0.512 0.334 0.218 0.071
0 0.741 0.601 0.163 0.058
```

## Directory structure on device

```
<filesDir>/training_data/
  images/
    frame_00000001.jpg
    frame_00000002.jpg
    ...
  labels/
    frame_00000001.txt
    frame_00000002.txt
    ...
  dataset.yaml          ← generated at export time, not during collection
  manifest.json         ← metadata: total frames, date range, app version, model id used
```

`filesDir` = `context.filesDir` (app-private internal storage, no storage permission required).

## File naming

Pattern: `frame_<seq8>.jpg`

- `seq8` is a zero-padded 8-digit global counter: `00000001`, `00000002`, …, `99999999`.
- The counter is persisted in `manifest.json` (`next_seq` field) so it never resets across app restarts or sessions. The next frame always gets a higher number than any previously saved frame, even after LRU eviction.
- No timestamp in the file name. Collection date range is recorded in `manifest.json` instead (`collected_from` / `collected_to` fields), keeping the file name clean and sortable by sequence alone.
- Same base name for image and label: `frame_00000001.jpg` ↔ `frame_00000001.txt`.
- 8 digits supports up to 99,999,999 frames — far beyond any realistic storage limit.

## Image format

- **Format:** JPEG, quality 90.
- **Content:** the `rotated` Bitmap — the frame after YUV→Bitmap conversion and rotation correction. This is the same coordinate space as the `Detection` pixel values.
- **Resolution:** whatever the current analysis resolution setting produces (HAL default, 640×480, or 1280×720). The YOLO annotation is normalized, so resolution changes do not invalidate existing labels — see REQ-003.

## Export screen

A dedicated Export screen is reachable from the Settings page via an "Export dataset" entry.

### Content displayed

The screen reads `manifest.json` and shows:

- Total frames collected
- Total detections
- Collection date range (`collected_from` → `collected_to`)
- An **Export** button (disabled and labelled "No data yet" when `total_frames == 0`)

### Split configuration

Before tapping Export the user can adjust how frames are divided into train / validation / test sets using two sliders on the Export screen:

- **Train** — range 50–90%, default **70%**
- **Validation** — range 5% to (95% − train%), default **20%**
- **Test** — computed automatically as `100% − train% − validation%` (minimum 5%); shown read-only below the sliders

Each label shows the estimated frame count for that split (e.g. "Train: 70%  (174 frames)"). When the train slider increases, the validation slider is clamped so test never drops below 5%.

### Export action

When the user taps Export:

1. A ZIP archive is created in `<filesDir>/exports/` with the name `plates_dataset_<timestamp>.zip`, where `<timestamp>` is `yyyyMMdd_HHmmss` in local time.
2. Collected images are randomly shuffled, then split into train / val / test subsets according to the configured ratios. Random shuffle ensures correlated consecutive frames are spread across all three sets.
3. The ZIP contains:
   ```
   train/
     images/
       frame_00000001.jpg
       …
     labels/
       frame_00000001.txt
       …
   val/
     images/
       frame_00000003.jpg
       …
     labels/
       frame_00000003.txt
       …
   test/
     images/
       frame_00000007.jpg
       …
     labels/
       frame_00000007.txt
       …
   data.yaml
   ```
4. `data.yaml` is generated fresh at export time (see content below). Structure and class name match `datasets/dataset_YOLO/data.yaml` so the export can be merged with existing training data without modification.
5. The Android share sheet opens immediately after the ZIP is written, using a `FileProvider` URI so the file can be sent to Drive, email, or any other app.
6. Previous export ZIPs in `<filesDir>/exports/` are **not** deleted; each export produces a new timestamped file.
7. If `<filesDir>/exports/` does not exist it is created at export time.

### Auto-reset after export

Immediately after the ZIP is successfully written and before the share sheet opens, all collected images and labels under `training_data/images/` and `training_data/labels/` are deleted and `next_seq` is reset to 1 in `manifest.json`. The export ZIP itself is unaffected. This ensures the next collection session starts from a clean slate without the user having to reset manually.

### In-progress state

While the ZIP is being built the Export button shows a loading indicator and is non-interactive. Export runs on a background thread; the UI remains responsive.

### Error handling

If the export fails (e.g. storage full), a brief error message is shown in the UI. The partial ZIP file, if any, is deleted. The collected data is **not** reset on failure.

## Exported files list

The Export screen contains a scrollable list of all ZIP files previously created under `<filesDir>/exports/`. Each list item shows:

- The file name (without the `.zip` extension)
- The file size
- The creation date

Each item has three actions:

- **Share** — Opens the Android share sheet for that ZIP (same `FileProvider` mechanism as the post-export share). The primary target for this action is Google Drive, but any app registered for the MIME type `application/zip` appears.
- **Rename** — Opens an inline text field pre-filled with the current name (without extension). Confirms on keyboard "Done" or a checkmark button. Saves the new name with `.zip` appended. The name must be non-empty and must not conflict with an existing file in `<filesDir>/exports/`; invalid input is rejected with an inline error.
- **Delete** — Shows a confirmation dialog ("Delete \<name\>.zip?") before removing the file.

The list is sorted by creation date, newest first. If no exports exist, the list shows an empty-state message ("No exports yet").

## Reset collected data

A **Reset** button is shown on the Export screen below the stats and above the Export button.

- Tapping Reset shows a confirmation dialog: "Delete all X collected frames? This cannot be undone."
- On confirmation: all files under `training_data/images/` and `training_data/labels/` are deleted and `manifest.json` is rewritten with `next_seq: 1`, `total_frames: 0`, `total_detections: 0`.
- The Export screen stats update immediately to reflect the reset state.
- The Reset button is disabled when `total_frames == 0`.
- Reset does **not** affect files in `<filesDir>/exports/`.

## data.yaml (generated at export)

Matches the structure of the existing `datasets/dataset_YOLO/data.yaml` so exported ZIPs can be merged with existing training data without conversion.

```yaml
train: train/images
val: val/images
test: test/images
nc: 1
names: ['License_Plate']
```

## manifest.json (written incrementally)

```json
{
  "app_version": "1.2.0",
  "model_id": "asset:plate_numbers",
  "collected_from": "2026-06-21T14:30:00",
  "collected_to": "2026-06-21T15:45:00",
  "total_frames": 247,
  "total_detections": 389,
  "next_seq": 248
}
```

- Updated after each saved frame (overwrite in place).
- `next_seq` is the counter value to use for the **next** frame. On first run it starts at 1. It is never decremented, even when frames are evicted by LRU — evicted frames leave a gap in the sequence, which is intentional and harmless for training.

## Acceptance criteria

### Collection
- [x] The Settings page contains a "Collect training data" toggle (Switch), defaulting to off on first install.
- [x] The toggle state is preserved after the app is killed and relaunched.
- [x] When the toggle is off, no files are written to `training_data/` regardless of detections.
- [x] When the toggle is turned on after being off, collection resumes from the previous `next_seq`.
- [x] Saving a frame with two detections produces a `.jpg` and a `.txt` with two lines.
- [x] File names are globally unique across all sessions; no two frames ever share the same sequence number.
- [x] After an app restart, the next saved frame continues from the last `next_seq` in `manifest.json`, not from 1.
- [ ] LRU-evicted frames leave a gap in the sequence; remaining files are still valid YOLO samples. *(LRU eviction deferred to REQ-006)*
- [x] Deleting all collected data removes all files under `training_data/` and resets `next_seq` to 1 in the manifest.

### Export
- [x] Settings page contains an "Export dataset" entry that navigates to the Export screen.
- [x] Export screen shows total frames, total detections, and the collection date range from `manifest.json`.
- [x] The Export button is disabled (labelled "No data yet") when `total_frames == 0`.
- [x] Tapping Export produces a valid ZIP at `<filesDir>/exports/plates_dataset_<timestamp>.zip`.
- [x] The ZIP contains `train/images/`, `train/labels/`, `val/images/`, `val/labels/`, `test/images/`, `test/labels/`, and a generated `data.yaml`; no other files.
- [x] Frames are randomly shuffled before splitting so consecutive correlated frames are spread across all three sets.
- [x] Split ratios are configurable via sliders (train 50–90%, validation 5–45%, test computed); defaults are 70/20/10.
- [x] `data.yaml` inside the ZIP is accepted by `yolo train data=data.yaml` without modification; class name matches `License_Plate`.
- [x] After the ZIP is successfully written, collected images and labels are deleted and `next_seq` resets to 1.
- [x] The Android share sheet opens automatically after the ZIP is written and data is reset.
- [x] Each export creates a new timestamped file; previously exported ZIPs are not deleted.
- [x] While export is in progress the button shows a loading state and is non-interactive.
- [x] If export fails, a user-visible error is shown, any partial ZIP is removed, and collected data is not reset.

### Exported files list
- [x] Export screen lists all ZIPs in `<filesDir>/exports/`, sorted newest first.
- [x] Each item shows file name, size, and creation date.
- [x] Share action opens the Android share sheet for that specific ZIP.
- [x] Rename action allows editing the file name; rejects empty names and name conflicts.
- [x] Delete action requires confirmation before removing the file.
- [x] An empty-state message is shown when no exports exist.

### Reset
- [x] Export screen shows a Reset button, disabled when `total_frames == 0`.
- [x] Tapping Reset shows a confirmation dialog stating the number of frames to be deleted.
- [x] On confirmation, all images and labels are deleted and `manifest.json` is rewritten with zero counts and `next_seq: 1`.
- [x] Export screen stats update immediately after reset.
- [x] Reset does not affect any files in `<filesDir>/exports/`.
