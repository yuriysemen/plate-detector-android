---
id: REQ-001
title: Training Data Collection — Overview & Scope
status: draft
priority: high
---

> **Current-state note:** this document predates the three-app split (REQ-031) and describes the
> original single-app design. For what actually exists today — three separate apps, their
> purposes, and where each is deployed — see
> [REQ-039](REQ-039-done-end-user-app-scope-and-deployment.md) (`android-end-user-app/`),
> [REQ-040](REQ-040-done-collection-app-scope-and-deployment.md)
> (`android-training-data-collection-app/`), and
> [REQ-041](REQ-041-done-reviewing-app-scope-and-deployment.md)
> (`android-training-data-reviewing-app/`). This document remains useful as the original
> feature-scope tracker for the collection pipeline's history (table below), but its Goals section
> below no longer matches current deployment reality (see "Be safe to publish on Google Play" —
> that goal applied when this was all one app; the app that now carries this functionality,
> `android-training-data-collection-app/`, is deliberately never published).

## Summary

Add an opt-in mode to the app that saves detected frames and their bounding-box annotations in YOLO format to device storage. The saved dataset can be used to fine-tune the existing model or to train a new model from scratch using the existing `training/ultralytics/` pipeline.

## Goals

- Produce a YOLO-format dataset (images + label files) directly from real-world app usage.
- Support both fine-tuning (continuing from `best.pt`) and full retraining from scratch.
- Work completely offline — no network required for collection.
- Be safe to publish on Google Play without violating policies or the existing privacy policy.

## Non-goals

- ~~Automatic upload to any server~~ — now in scope as REQ-014 (cloud upload) and REQ-015 (scheduled upload).
- In-app annotation correction — briefly implemented in REQ-011, **removed again in REQ-026**. The generic app only captures and uploads; all box editing lives in `android-training-data-reviewing-app` (REQ-024/REQ-025).
- Video recording.
- Collecting frames with zero detections (background-only samples) — out of scope for now.

## Feature scope (child requirements)

| Req     | Topic                                                                      | Status                           |
|---------|----------------------------------------------------------------------------|----------------------------------|
| REQ-002 | YOLO format specification and directory structure                          | done                             |
| REQ-003 | Coordinate transformation from Detection to YOLO                           | done                             |
| REQ-004 | Multiple detections per frame                                              | done                             |
| REQ-005 | Settings UI and preference keys for data collection                        | done (Cognito UI fields pending REQ-014) |
| REQ-006 | Storage quota, LRU eviction, and export                                    | done (LRU superseded by REQ-011) |
| REQ-007 | Google Play compliance and privacy policy changes                          | draft                            |
| REQ-008 | Local-only vs. cloud upload analysis                                       | superseded by REQ-014 + REQ-018  |
| REQ-011 | Dataset Editor, Frame Detail editing, and configurable storage quota       | done (editor removed by REQ-026; quota retained) |
| REQ-012 | Zoom and pan in Frame Detail Editor                                        | superseded by REQ-026            |
| REQ-013 | Dataset file naming (`<date>_<time>_<seq6>`) and data.yaml device metadata | done                             |
| REQ-014 | Cloud dataset upload — authenticated upload via WorkManager and pre-signed S3 URLs | done                         |
| REQ-015 | Scheduled automatic daily dataset upload                                   | done                             |
| REQ-016 | Model update system — remote download and auto-apply                       | draft                            |
| REQ-017 | Auto-parking settings auto-configuration                                   | draft                            |
| REQ-018 | AWS upload infrastructure — S3 bucket, Lambda, API Gateway, Cognito (IaC) | done                             |
| REQ-019 | Settings screen layout and controls (model picker, scan interval, etc.)    | done                             |
| REQ-022 | Curation Android app — overview, Cognito auth, and direct S3 access        | done                             |
| REQ-023 | Curation Android app — package workflow (not processed/in progress/done)   | done                             |
| REQ-024 | Curation Android app — review editor (accept, edit, reject)                | done                              |
| REQ-025 | Curation — vehicle-type categories (S3 list) and typed YOLO boxes          | done                              |
| REQ-026 | Generic app — remove on-device review/editing; capture-and-upload only     | done                              |
| REQ-027 | Auth-failure detection (API 401/403) and always-available re-sign-in       | done                              |
| REQ-028 | Upload diagnostics — failure reasons + persistent upload activity log      | done                              |
| REQ-029 | Auth robustness — curator API access + login/logout failure-path hardening | done (infra needs `sam deploy`)   |

## Dependencies

- `PlateDetector.detectAll()` already returns pixel-space `Detection` objects with coordinates already unprojected from the letterbox — no additional math needed inside the detector.
- The `rotated` Bitmap in `CameraPreviewWithAnalysis` is the canonical coordinate space: annotations and the saved image must share this same bitmap.
- Existing training pipeline at `training/ultralytics/` expects YOLO format with a `data.yaml` descriptor.

## Decisions

- **Background frames (no detections):** Not collected. Only frames with at least one detection above the save threshold are saved. This keeps storage usage predictable and focused on positive examples.
- **Rejected detections / hard negatives:** Out of scope for this feature. A separate editing app will handle reviewing collected frames, correcting annotations, and flagging false positives. That app will add a rejection flag to the annotation if needed. This app only collects; it does not judge.
