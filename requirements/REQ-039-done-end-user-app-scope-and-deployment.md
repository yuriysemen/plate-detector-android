---
id: REQ-039
title: android-end-user-app — Purpose, Scope, and Deployment
status: done
priority: high
depends_on: REQ-031
---

## Summary

This is one of three separate, independently-deployed Android apps in this repo (see
[REQ-040](REQ-040-done-collection-app-scope-and-deployment.md) and
[REQ-041](REQ-041-done-reviewing-app-scope-and-deployment.md) for the other two). This document is
the current-state reference for **`android-end-user-app/`** specifically — what it's for, what it
does and doesn't do, and where it's deployed — since that context had been scattered across
REQ-007/REQ-030/REQ-031's scope notes and was a source of confusion (which app a given compliance
requirement actually applies to).

## Purpose

Real-time, on-device license-plate detection for an end user: point the camera, see bounding boxes
and OCR'd plate text overlaid live. Nothing else.

## What it does

- Live camera preview (CameraX) → TFLite YOLO detection → ML Kit OCR on each detected box →
  on-screen overlay.
- Multiple bundled/local models selectable in Settings; per-model confidence threshold, scan
  interval, analysis resolution.
- Optional beep on detection.
- Everything runs fully on-device.

## What it explicitly does not do

- **No accounts, no sign-in.**
- **No storage** of camera frames — nothing is written to disk beyond the selected model file
  itself.
- **No upload, no runtime network access.** Model selection is limited to what's bundled at build
  time or manually placed in assets (see its README's "Getting a model" section) — there is no
  in-app model-download path (that only exists in `android-training-data-collection-app/`).
- No training-data collection, no review/curation of any kind.

This is deliberate and was the point of [REQ-031](REQ-031-done-split-detection-and-training-apps.md):
this app used to also handle account creation, opt-in frame capture, and cloud upload, which made
it both a detection tool and a private data-collection pipeline in one Play Store listing. REQ-031
split that pipeline out entirely into `android-training-data-collection-app/`, leaving this app as
detection-only.

## Deployment

**Published on Google Play** (`applicationId com.github.yuriysemen.platesdetector`) — this is the
one app of the three that actually has a public listing. Because it collects and transmits
nothing, its Play Store Data Safety declarations are trivial ("no data collected") and it carries
none of the account-deletion / ongoing-compliance obligations described in
[REQ-007](REQ-007-superseded-play-store-compliance.md) (superseded — that document's remaining
concerns don't apply to this app post-REQ-031, and don't apply to the other two apps because
neither is published — see REQ-040/041).

Release mechanics (versioning, store listing, rollout) for this app's *next* production release are
tracked separately in [REQ-030](REQ-030-draft-google-play-release-readiness.md) — that document is
about release logistics, not compliance content, and remains active/relevant to this app.

## Relationship to the other two apps

Produces nothing the other apps consume — it's the leaf of the pipeline, not a source. It *does*
share the same detection/OCR code lineage as `android-training-data-collection-app/` (which started
as a full copy of this app, per REQ-031) and receives model updates the same way any bundled model
does — at build time only, unlike `android-training-data-collection-app/`'s additional runtime
model-download path.

## Key requirements

REQ-001–REQ-006, REQ-013, REQ-016, REQ-017, REQ-019 (original detection/Settings feature work,
before the split), REQ-031 (the split itself), REQ-030 (release readiness, still active).
