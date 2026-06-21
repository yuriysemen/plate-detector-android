---
id: REQ-002
title: YOLO Format Specification and Directory Structure
status: draft
priority: high
---

## Summary

Define exactly how images and annotations are stored on device so the exported dataset can be fed directly into the `training/ultralytics/` pipeline without conversion.

## YOLO annotation format

Each image has a paired `.txt` file with one line per detected object:

```
<class_id> <x_center> <y_center> <width> <height>
```

- All five values are floating-point.
- `x_center`, `y_center`, `width`, `height` are **normalized to [0, 1]** relative to the saved image's pixel dimensions.
- Multiple detections in the same frame = multiple lines in the same `.txt` file.
- A frame with no detections is **not saved at all** (decided in REQ-001). The saver is only invoked when `detections.isNotEmpty()`.

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

## dataset.yaml (generated at export)

```yaml
path: .
train: images
val: images   # user splits later; we ship one set
nc: 1
names:
  0: plate
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

- [ ] Saving a frame with two detections produces a `.jpg` and a `.txt` with two lines.
- [ ] The `dataset.yaml` generated at export is accepted by `yolo train data=dataset.yaml` without modification.
- [ ] File names are globally unique across all sessions; no two frames ever share the same sequence number.
- [ ] After an app restart, the next saved frame continues from the last `next_seq` in `manifest.json`, not from 1.
- [ ] LRU-evicted frames leave a gap in the sequence; remaining files are still valid YOLO samples.
- [ ] Deleting all collected data removes all files under `training_data/` and resets `next_seq` to 1 in the manifest.
