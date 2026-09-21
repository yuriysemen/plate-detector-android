---
id: REQ-038
title: Collection App — Capture Guidelines Shown Before First Camera Use
status: done
priority: low
---

## Summary

`android-training-data-collection-app/` had no in-app guidance for data collectors on *how* to
capture useful training photos — only the existing `collectTrainingData` consent dialog
(`SettingsScreen.kt`), which is about upload/privacy consent, not field technique. This requirement
adds a one-time, full-screen **"Capture guidelines"** screen shown before the camera is ever
enabled, so a collector sees the rules before they start shooting rather than never.

## Motivation

Arose from a conversation about how annotation/capture quality affects the plate-detection model's
training data: loose or partial bounding boxes teach the model to reproduce that sloppiness; a
detector's blind spots only get fixed if collectors deliberately capture what the *current* model
currently misses (auto-capture alone can only ever save what it already detects); and angular/
lighting diversity in capture — not annotation tricks — is what makes the detector robust to
real-world plate orientation and hard lighting. None of that is obvious to someone just handed the
app and told to go take photos of cars, and there was nowhere in the app that said it.

## Design

- **`CaptureGuidelinesScreen.kt`** (new) — full-screen, scrollable, five sections plus a "Got it —
  start capturing" button:
  1. **Use Manual Capture for what auto-capture misses** — tap Manual Capture even when the live
     overlay isn't boxing a real plate; those are the cases the model needs most.
  2. **Vary angle and distance** — don't shoot every car straight-on; mix close/far and vary
     height/side angle.
  3. **Seek out hard cases, don't avoid them** — low light/dusk, glare/reflections, dirty or
     partly-blocked plates.
  4. **More vehicles, not more photos of the same one** — a couple of shots per vehicle is enough.
  5. **Stay public, stay respectful** — public spaces only, avoid framing people/faces, respect
     no-photography signage.
- **Gating point:** `LiveDetectionUi` (`LivePlateDetectionScreen.kt`) checks
  `ModelPrefs.getCaptureGuidelinesShown()` as the very first thing in the composable, before the
  CAMERA permission `LaunchedEffect` and before `TrainingDataSaver`/`DatasetExporter` are created —
  if not yet acknowledged, it renders `CaptureGuidelinesScreen` and returns, so nothing
  camera-related runs until the collector acknowledges it.
- **Persistence:** `ModelPrefs.getCaptureGuidelinesShown` / `setCaptureGuidelinesShown` — a new
  `capture_guidelines_shown` boolean in the existing `model_prefs` SharedPreferences, following the
  exact same one-time-ack pattern already used for `collectFirstTimeShown` (the upload-consent
  dialog). Shown once per install; not tied to `collectTrainingData` or sign-in state, since it
  applies to anyone about to point the camera at cars, not just contributors who end up uploading.

## Files

New:
- `training/CaptureGuidelinesScreen.kt`

Modified:
- `training/LivePlateDetectionScreen.kt` — `ModelPrefs.getCaptureGuidelinesShown`/
  `setCaptureGuidelinesShown` (new prefs pair), and the gating `if (!guidelinesAcknowledged) { ...; return }`
  at the top of `LiveDetectionUi`.

## Acceptance criteria

- [x] A full-screen "Capture guidelines" screen appears the first time `LiveDetectionUi` composes,
      before the CAMERA permission prompt.
- [x] Acknowledging it ("Got it — start capturing") persists the flag and proceeds directly to the
      normal camera/permission flow.
- [x] On any subsequent launch, the guidelines screen does not reappear (persisted via
      `ModelPrefs`).
- [x] Not tied to `collectTrainingData`/sign-in state — appears regardless, since it's about field
      technique, not upload consent.
- [x] App compiles (`:app:compileDebugKotlin`) and the existing unit test suite passes
      (`:app:testDebugUnitTest`).

> **Verification gap:** built + unit-tested only. No on-device run yet — worth confirming the
> screen actually renders before the permission prompt and that "Got it" dismisses it permanently
> on a real device/emulator before treating this as fully verified.
