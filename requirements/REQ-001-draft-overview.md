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

- ~~Automatic upload to any server~~ — now in scope as REQ-014 (cloud upload) and REQ-015 (scheduled upload).
- ~~In-app annotation correction (user cannot move/resize boxes in this scope).~~ — **implemented in REQ-011.**
- Video recording.
- Collecting frames with zero detections (background-only samples) — out of scope for now.

## Feature scope (child requirements)

| Req     | Topic                                                                      | Status                           |
|---------|----------------------------------------------------------------------------|----------------------------------|
| REQ-002 | YOLO format specification and directory structure                          | done                             |
| REQ-003 | Coordinate transformation from Detection to YOLO                           | done                             |
| REQ-004 | Multiple detections per frame                                              | done                             |
| REQ-005 | Settings UI and preference keys                                            | done                             |
| REQ-006 | Storage quota, LRU eviction, and export                                    | done (LRU superseded by REQ-011) |
| REQ-007 | Google Play compliance and privacy policy changes                          | draft                            |
| REQ-008 | Local-only vs. cloud upload analysis                                       | superseded by REQ-014 + REQ-018  |
| REQ-011 | Dataset Editor, Frame Detail editing, and configurable storage quota       | done                             |
| REQ-012 | Zoom and pan in Frame Detail Editor                                        | done                             |
| REQ-013 | Dataset file naming (`<date>_<time>_<seq6>`) and data.yaml device metadata | draft                            |
| REQ-014 | Cloud dataset upload — export mode selection and AWS S3 upload             | draft                            |
| REQ-015 | Scheduled automatic daily dataset upload                                   | draft                            |
| REQ-016 | Model update system — remote download and auto-apply                       | draft                            |
| REQ-017 | Auto-parking settings auto-configuration                                   | draft                            |
| REQ-018 | AWS upload infrastructure — S3 bucket, Lambda, API Gateway (IaC)          | draft                            |

## Dependencies

- `PlateDetector.detectAll()` already returns pixel-space `Detection` objects with coordinates already unprojected from the letterbox — no additional math needed inside the detector.
- The `rotated` Bitmap in `CameraPreviewWithAnalysis` is the canonical coordinate space: annotations and the saved image must share this same bitmap.
- Existing training pipeline at `training/ultralytics/` expects YOLO format with a `data.yaml` descriptor.

## Decisions

- **Background frames (no detections):** Not collected. Only frames with at least one detection above the save threshold are saved. This keeps storage usage predictable and focused on positive examples.
- **Rejected detections / hard negatives:** Out of scope for this feature. A separate editing app will handle reviewing collected frames, correcting annotations, and flagging false positives. That app will add a rejection flag to the annotation if needed. This app only collects; it does not judge.
