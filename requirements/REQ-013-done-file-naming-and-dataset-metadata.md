---
id: REQ-013
title: Dataset File Naming and data.yaml Device Metadata
status: done
priority: high
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

Change collected frame file names to embed capture date, time, and a 6-digit sequence number, and enrich the exported `data.yaml` with device and build metadata so every exported package is self-describing and uniquely traceable.

---

## File naming

### Current format

```
frame_00000001.jpg  /  frame_00000001.txt
```

### New format

```
<YYYYMMDD>_<HHmmss>_<seq6>.jpg  /  <YYYYMMDD>_<HHmmss>_<seq6>.txt
```

- `YYYYMMDD` — local date at frame capture time (e.g. `20260622`).
- `HHmmss` — local wall-clock time at frame capture time (e.g. `143012`).
- `seq6` — 6-digit zero-padded global sequence counter, same monotonic counter as today's `seq8` (range `000001`–`999999`; 999,999 frames far exceeds any practical storage limit). Counter persists in `manifest.json` and never resets on eviction.
- Image and label share the same base name: `20260622_143012_000001.jpg` ↔ `20260622_143012_000001.txt`.

### Timestamp source

- Capture timestamp = `System.currentTimeMillis()` formatted in device local timezone (`SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)`).
- Stored in `manifest.json` per-frame entry so the timestamp can be reconstructed from the manifest without parsing the filename.

---

## data.yaml — device metadata section

The generated `data.yaml` gains a `device:` block appended after the standard YOLO fields. This block is ignored by `yolo train` (unknown keys are silently skipped) but is useful for dataset provenance tracking.

### New data.yaml structure

```yaml
train: train/images
val: val/images
test: test/images
nc: 1
names: ['License_Plate']

device:
  phone_model: "Samsung Galaxy S22"       # Build.MODEL
  phone_manufacturer: "Samsung"           # Build.MANUFACTURER
  android_version: "14"                   # Build.VERSION.RELEASE
  android_sdk: 34                         # Build.VERSION.SDK_INT
  app_version: "1.3.0"                    # BuildConfig.VERSION_NAME
  model_id: "asset:plate_numbers_v2"      # currently loaded model identifier
  export_timestamp: "2026-06-22T14:30:00" # ISO 8601, local time
  device_id: "a3f8c..."                   # SHA-256 of stable device identifier (first 16 hex chars)
  total_frames: 247
  total_detections: 389
  collected_from: "2026-06-21T14:30:00"   # ISO 8601
  collected_to: "2026-06-22T14:28:55"     # ISO 8601
```

### Device identifier

- Derived from `Settings.Secure.ANDROID_ID` hashed with SHA-256, truncated to the first 16 hex characters.
- Never includes raw ANDROID_ID; only the truncated hash is embedded in the file.
- Stable across app updates; changes on factory reset (acceptable).
- Used in REQ-015 to support GDPR deletion requests for cloud-uploaded packages.

---

## Acceptance criteria

### File naming
- [x] New frames are saved with the `<YYYYMMDD>_<HHmmss>_<seq6>` naming scheme.
- [x] Image and label files always share the same base name.
- [x] Sequence counter continues from the last persisted `next_seq`; no resets across sessions.

### data.yaml
- [x] Exported `data.yaml` contains all standard YOLO fields (`train`, `val`, `test`, `nc`, `names`).
- [x] Exported `data.yaml` contains the `device:` block with all fields listed above.
- [x] `yolo train data=data.yaml` succeeds without errors (unknown `device:` key is ignored).
- [x] `device_id` is a truncated SHA-256 hash, never the raw ANDROID_ID.
- [x] `phone_model`, `android_version`, `android_sdk`, and `app_version` match the device at export time.
