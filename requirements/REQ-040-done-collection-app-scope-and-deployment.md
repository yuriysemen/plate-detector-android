---
id: REQ-040
title: android-training-data-collection-app — Purpose, Scope, and Deployment
status: done
priority: high
depends_on: REQ-031
---

## Summary

This is one of three separate, independently-deployed Android apps in this repo (see
[REQ-039](REQ-039-done-end-user-app-scope-and-deployment.md) and
[REQ-041](REQ-041-done-reviewing-app-scope-and-deployment.md) for the other two). This document is
the current-state reference for **`android-training-data-collection-app/`** specifically — what
it's for, what it does, and where it's deployed.

## Purpose

Used by the project's own trusted data collectors to gather real-world training photos: the same
live detection pipeline as `android-end-user-app/`, plus an opt-in capture-and-upload pipeline that
turns camera sessions into a growing labeled dataset in S3.

## What it does

- Everything `android-end-user-app/` does (live detection + OCR overlay), plus:
- **Account/sign-in** — Cognito email/password (`CognitoAuthManager`).
- **Capture** — auto-on-detection, manual shutter, and burst modes (REQ-020, REQ-021), gated on
  `collect_training_data` **and** signed-in (REQ-026). A one-time **capture guidelines** screen
  (REQ-038) is shown before the camera is ever enabled, covering field technique (use Manual
  Capture for what auto-capture misses, vary angle/distance, seek out hard cases, favor more
  vehicles over duplicate shots, stay public/respectful).
- **Upload** — `UploadDatasetWorker` streams packages to S3 via a pre-signed URL (REQ-014),
  scheduled daily (REQ-015) or manual, with restart/diagnostics (REQ-028).
- **Model distribution** — checks for and downloads updated models at runtime for signed-in users
  (REQ-016) — the one capability `android-end-user-app/` deliberately doesn't have.
- **No on-device review/editing** — captured frames ship the model's raw predicted boxes as-is
  (REQ-026); all correction/curation happens in `android-training-data-reviewing-app/`, not here.

## Deployment

**Internal-only. There is no plan to ever publish this app to Google Play.** It's sideloaded
(`./gradlew :app:installDebug` / a distributed release APK), same distribution model as
`android-training-data-reviewing-app/`. This was a deliberate decision, not an oversight:

- The app creates Cognito accounts and uploads user-captured images (which may contain
  third-party-identifiable license plates), which under Play policy requires an in-app or
  documented account-deletion mechanism and an accurate, continuously-maintained Data Safety
  declaration.
- That ongoing compliance burden isn't worth carrying for a tool only the project's own data
  collectors run — there's no public audience it needs to satisfy Play policy for.
- [REQ-007](REQ-007-superseded-play-store-compliance.md) originally scoped exactly this compliance
  work (account deletion, Data Safety form, privacy policy rewrite) back when this functionality
  still lived inside the published app; it's superseded now — not because the underlying
  privacy/data-minimization practices it describes stopped mattering (they still apply day to day:
  opt-in consent, storage quota, Auto Backup exclusion), just because none of it is a *Play Store*
  obligation for an app that isn't published there.

## Relationship to the other two apps

- **Consumes:** model files, either bundled at build time or downloaded at runtime after sign-in
  (same S3 `models/` layout `android-end-user-app/` uses at build time only).
- **Produces:** `uploads/<user_sub>/<device_id>/<filename>.zip` packages in S3 — the sole input to
  `android-training-data-reviewing-app/`'s "Not Processed" queue.
- Started as a full copy of `android-end-user-app/`'s pre-split code (REQ-031) and still shares that
  detection/OCR lineage, but is a fully standalone Gradle project with its own `applicationId`
  (`com.github.yuriysemen.platesdetector.training`) — no build dependency between the two.

## Key requirements

REQ-005, REQ-006, REQ-011 (quota portion), REQ-013–REQ-015, REQ-016, REQ-018, REQ-020, REQ-021,
REQ-026–REQ-029, REQ-031 (created this app), REQ-032, REQ-038.