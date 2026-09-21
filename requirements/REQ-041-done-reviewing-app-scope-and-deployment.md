---
id: REQ-041
title: android-training-data-reviewing-app — Purpose, Scope, and Deployment
status: done
priority: high
depends_on: REQ-022
---

## Summary

This is one of three separate, independently-deployed Android apps in this repo (see
[REQ-039](REQ-039-done-end-user-app-scope-and-deployment.md) and
[REQ-040](REQ-040-done-collection-app-scope-and-deployment.md) for the other two). This document is
the current-state reference for **`android-training-data-reviewing-app/`** specifically — what
it's for, what it does, and where it's deployed.

## Purpose

The curation step between raw collected photos and a model-ready dataset: a single trusted
curator reviews packages `android-training-data-collection-app/` uploaded, corrects/accepts/rejects
each plate detection, and produces a verified, training-ready dataset that
`training/ultralytics/` consumes to train or fine-tune the next model.

## What it does

- **Direct S3 (or, optionally, Google Drive — see below) access** from the device via a scoped
  IAM credential (`CuratorRole`) — no backend API in between (REQ-022).
- **Three-tab workflow** — Not Processed / In Progress / Done, derived entirely from what exists
  in storage (no separate status database) (REQ-023).
- **Review editor** — per-item Accept/Reject, box move/resize/add/delete, pinch-zoom/pan
  (REQ-024), with the ability to change an already-decided item later (REQ-034).
- **Complete** — bundles accepted/rejected items into compressed per-package archives under
  `done/`/`rejected/` plus a `data.yaml` regenerated from the current vehicle-category list
  (REQ-035), ready to feed the training pipeline.
- **Read-only Done-package viewer** — browse a completed package's accepted images and final boxes
  without re-entering the review flow (REQ-036).
- Single curator, one session at a time by design — no multi-curator locking model (REQ-022's
  "Future" note; REQ-023 adds lightweight attribution + stale-package detection as partial
  measures, not a full concurrency guarantee).

**Google Drive was evaluated as a second backend (REQ-037) and reverted.** A dual S3/Drive backend
was implemented and briefly present in this app's working tree, but was removed at the user's
request before being committed — this app is S3-only. (Kept here as a historical note in case
Drive support is revisited later; nothing Drive-related exists in the codebase currently.)

## Deployment

**Internal-only. There is no plan to ever publish this app to Google Play.** Sideloaded, same as
`android-training-data-collection-app/`. This is even more clearly out of scope for a public
listing than the collection app: it authenticates as a `curators`-group Cognito account with a
scoped IAM role granting direct read/write access to the entire training dataset bucket — a
single-trusted-operator design that assumes the person holding the APK is the operator, not a
general end user. Publishing it publicly would be a design mismatch independent of Play policy.

## Relationship to the other two apps

- **Consumes:** `uploads/<user_sub>/<device_id>/<filename>.zip` packages produced by
  `android-training-data-collection-app/`.
- **Produces:** `done/`/`rejected/` archives + manifests, consumed by `training/ultralytics/` to
  produce a new/updated model, which is then uploaded back to S3 for
  `android-training-data-collection-app/`'s (and, at build time, `android-end-user-app/`'s) model
  distribution to pick up.
- Deliberately **not** a shared-code counterpart to the other two apps — unlike
  `android-training-data-collection-app/` (a copy of `android-end-user-app/`'s code), this app
  reimplements shared concepts (Cognito auth, YOLO label parsing, the box editor) independently, as
  a standalone Gradle project with no build dependency on either sibling app.

## Key requirements

REQ-022–REQ-025, REQ-029 (curator-specific auth robustness), REQ-033–REQ-036.