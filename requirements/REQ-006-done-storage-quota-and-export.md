---
id: REQ-006
title: Storage Quota, LRU Eviction, and Dataset Export
status: done
priority: medium
---

> **Implemented in `android/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `training-android/`, not `android/`.**

## Summary

Define limits to prevent the feature from consuming unbounded device storage, how old data is evicted when limits are reached, and how the final dataset is packaged for use with the training pipeline.

## Implementation status

- **Storage quota + warning banners** — implemented (REQ-011 replaced the hidden constant with a user-configurable quota in Settings; 80% yellow banner and 100% red pause banner are live).
- **LRU eviction** — **not implemented and superseded by REQ-011**. Silently deleting curated frames was deemed worse than pausing collection and prompting the user to act via the Dataset Editor. The collection pause strategy from REQ-011 is the replacement.
- **Export flow** — implemented. The ZIP format, train/val/test split, and FileProvider setup differ from this spec in several ways (see REQ-002 for the authoritative implementation details).

## Storage location

`context.filesDir/training_data/` — app-private internal storage.

- No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions required on any API level.
- Automatically deleted when the user uninstalls the app (Android OS behaviour).
- Not visible in the device file browser unless the user has a root explorer.
- Not backed up by Android Auto Backup by default (large binary files are excluded by size heuristics). To be explicit, add to `backup_rules.xml`: exclude `training_data/`.

## Storage quota

> **Superseded by REQ-011.** Quota is now user-configurable in Settings (default 500 MB, range 100 MB – 20 GB). The hidden developer constant below was removed.

Hard cap: **500 MB** (configurable via a hidden developer constant, not exposed in UI).

Approximate frame sizes:
- JPEG quality 90, 640×480 ≈ 50–100 KB
- JPEG quality 90, 1080×1920 ≈ 200–400 KB

At 500 MB cap:
- ~1,250 frames at HD (1080×1920 @ 400 KB)
- ~5,000 frames at Low (640×480 @ 100 KB)

Since only frames with detections are saved (REQ-001 decision), storage grows only when plates are actually visible — not continuously. In typical use (driving past parked cars) this is a small fraction of all processed frames, so the 500 MB cap should last a long time before LRU kicks in.

## LRU eviction strategy

> **Not implemented — superseded by REQ-011.** Collection pauses at quota; the user prunes via the Dataset Editor instead of silent auto-deletion.

When a new frame would push total size over the quota:
1. List all `.jpg` files in `images/` sorted by last-modified time (oldest first).
2. Delete the oldest image and its paired `.txt` label file until there is space for the new frame.
3. Update `manifest.json` after eviction.

Eviction happens synchronously before writing the new frame, on the analysis background thread.

**Why LRU not FIFO:** same result for continuous collection, but LRU is correct if the user resumes collection after a gap (old frames from previous sessions are evicted first).

## Export flow

> **Superseded by REQ-002.** The actual implementation stores ZIPs in `filesDir/exports/` (not `cacheDir/export/`), auto-splits into `train/`/`val/`/`test/` subdirectories, and does not require a manual split step. See REQ-002 for authoritative details.

Triggered by the "Export dataset" button in Settings (see REQ-005).

Steps:
1. Pause new writes (set an in-memory flag checked by the saver).
2. Write `dataset.yaml` (overwrite if exists).
3. Update `manifest.json` with final timestamp.
4. Create a zip archive:
   - Output: `context.cacheDir/export/plate_dataset_<timestamp>.zip`
   - Contents: everything under `training_data/` (preserving `images/` and `labels/` subdirectories).
5. Resume writes.
6. Launch share sheet via `FileProvider` (required on API 24+; add provider to `AndroidManifest.xml`).
7. Delete the temp zip from `cacheDir` once the share intent is delivered (or on next export).

## dataset.yaml content

> **Superseded by REQ-002.** Actual format uses `train/images`, `val/images`, `test/images` paths and class name `License_Plate`. See REQ-002.

```yaml
path: .
train: images
val: images
nc: 1
names:
  0: plate
```

Note: the user is expected to split `images/` into `train/` and `val/` subdirectories before running `yolo train`. This is documented in an exported `README.txt` inside the zip.

## README.txt bundled in export zip

> **Superseded by REQ-002.** The export auto-splits frames; no manual split step is needed. See REQ-002 for the actual bundled `data.yaml` and directory layout.

```
Plate Detector — Training Dataset
Exported: 2026-06-21 14:30
Frames:   247
Detections: 389

Directory layout:
  images/   — JPEG frames (rotated to viewing orientation)
  labels/   — YOLO annotation .txt files (one line per detection)
  dataset.yaml — Ultralytics dataset descriptor

To train (fine-tune from last checkpoint):
  yolo train model=runs/detect/<run>/weights/best.pt data=dataset.yaml epochs=30 imgsz=640

To train from scratch:
  yolo train model=yolo11n.pt data=dataset.yaml epochs=100 imgsz=640

Split train/val before training:
  Move ~20% of images/ and their matching labels/ into val/images/ and val/labels/
  Update dataset.yaml accordingly.
```

## FileProvider setup (implementation note)

Add to `AndroidManifest.xml`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths"/>
</provider>
```

`file_provider_paths.xml`:
```xml
<paths>
    <cache-path name="exports" path="export/"/>
</paths>
```

## Acceptance criteria

- [x] Deleting a frame also deletes its paired `.txt` — no orphaned label files.
- [x] The exported ZIP is accepted by macOS Archive Utility and standard `unzip` without errors.
- [x] `yolo train data=dataset.yaml` runs without modification after unzipping the export.
- [x] Export button is disabled (greyed out) when 0 frames have been collected.
- [x] Exporting does not block the UI thread.
- [ ] ~~Total storage never exceeds 500 MB; oldest frames are deleted automatically when the cap is reached.~~ — superseded by REQ-011 (collection pause + Dataset Editor instead of silent LRU eviction).
