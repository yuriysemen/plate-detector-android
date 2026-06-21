---
id: REQ-001
title: Training Data Collection — Overview & Scope
status: draft
priority: high
---

## Summary

Add an opt-in mode to the app that saves detected frames and their bounding-box annotations in YOLO format to device storage. The saved dataset can be used to fine-tune the existing model or to train a new model from scratch using the existing `training/ultralytics/` pipeline.

## Goals

- Produce a YOLO-format dataset (images + label files) directly from real-world app usage.
- Support both fine-tuning (continuing from `best.pt`) and full retraining from scratch.
- Work completely offline — no network required for collection.
- Be safe to publish on Google Play without violating policies or the existing privacy policy.

## Non-goals

- Automatic upload to any server (covered separately in REQ-008).
- In-app annotation correction (user cannot move/resize boxes in this scope).
- Video recording.
- Collecting frames with zero detections (background-only samples) — out of scope for now.

## Feature scope (child requirements)

| Req | Topic |
|---|---|
| REQ-002 | YOLO format specification and directory structure |
| REQ-003 | Coordinate transformation from Detection to YOLO |
| REQ-004 | Multiple detections per frame |
| REQ-005 | Settings UI and preference keys |
| REQ-006 | Storage quota, LRU eviction, and export |
| REQ-007 | Google Play compliance and privacy policy changes |
| REQ-008 | Local-only vs. cloud upload analysis |

## Dependencies

- `PlateDetector.detectAll()` already returns pixel-space `Detection` objects with coordinates already unprojected from the letterbox — no additional math needed inside the detector.
- The `rotated` Bitmap in `CameraPreviewWithAnalysis` is the canonical coordinate space: annotations and the saved image must share this same bitmap.
- Existing training pipeline at `training/ultralytics/` expects YOLO format with a `data.yaml` descriptor.

## Decisions

- **Background frames (no detections):** Not collected. Only frames with at least one detection above the save threshold are saved. This keeps storage usage predictable and focused on positive examples.
- **Rejected detections / hard negatives:** Out of scope for this feature. A separate editing app will handle reviewing collected frames, correcting annotations, and flagging false positives. That app will add a rejection flag to the annotation if needed. This app only collects; it does not judge.
