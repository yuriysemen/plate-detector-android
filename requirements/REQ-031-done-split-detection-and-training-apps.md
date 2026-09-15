---
id: REQ-031
title: Split android-end-user-app/ into a Detection-Only Published App and a android-training-data-collection-app Data-Collection App
status: done
priority: high
---

## Summary

`android-end-user-app/` currently does two very different jobs in one app and one Play Store listing:
real-time on-device plate detection (the thing worth publishing), and an opt-in
account-creation / frame-capture / cloud-upload pipeline for building a training dataset (the
thing that turns "Car Plate Detector" into something closer to a private ALPR data-collection
operation — see REQ-007's account-deletion gap and the third-party-PII/legal concerns raised
alongside it). This requirement splits that in two:

- **`android-end-user-app/` (published, Google Play)** — detection only. Camera → TFLite YOLO → ML Kit OCR →
  on-screen overlay. **Stores nothing, uploads nothing, requires no account, needs no runtime
  network access.**
- **`android-training-data-collection-app/` (new, not published — internal tool, sideloaded like `android-training-data-reviewing-app/`)**
  — everything that is today's "Contribute data" pipeline: sign-in, capture (auto/manual/burst),
  local storage, cloud upload, model auto-update. Used only by the developer / trusted testers to
  build the training dataset.
- **`android-training-data-reviewing-app/`** — unchanged in behavior; only its docs need to stop calling `android-end-user-app/`
  "the main app" that produces its uploads, since `android-training-data-collection-app/` will be the producer now.

This removes essentially all of REQ-007's and REQ-030's compliance burden **from the published
app**, because the published app will no longer collect anything to declare. It does **not**
remove the underlying legal exposure of collecting third-party plates via `android-training-data-collection-app/` —
that risk is about processing the data at all, not about which app does it or whether it's on
Play Store. See "What this does and doesn't fix" below.

## What this does and doesn't fix

**Fixes, for `android-end-user-app/`:**
- No more Play Data Safety declarations (nothing collected).
- No more account-deletion requirement (no accounts).
- REQ-007 and REQ-030 (as written) no longer apply to `android-end-user-app/` — see the notes added to those
  files once this ships.
- Privacy policy can revert to the original "no data collected, no upload" statement — true again.
- Likely no `INTERNET` permission needed on-device at all (see §3).

**Does not fix:**
- `android-training-data-collection-app/` still captures other people's plates and centralizes them in your S3
  bucket. GDPR / state ALPR-style exposure is about that processing, independent of Play Store
  distribution. Keep treating `android-training-data-collection-app/`'s data handling with the same care REQ-007
  described (minimize retention, honor deletion requests, know your actual data-residency region)
  even though nothing there is Play-reviewed.
- This is a scoping decision, not a legal opinion — get real legal advice before relying on
  "internal tool" as a shield if this dataset grows or gets used commercially.

## Key design decisions

- **`android-training-data-collection-app/` is not a from-scratch reimplementation** — unlike `android-training-data-reviewing-app/`
  (which deliberately reimplements shared concepts rather than sharing code), `android-training-data-collection-app/`
  starts as a **copy of today's `android-end-user-app/`** (it needs the same detection pipeline to know what to
  capture) and *keeps* the auth/capture/upload/model-update code that already exists there. The
  work is: rename the module/package, give it its own `applicationId`, and delete nothing.
- **`android-end-user-app/` is what gets stripped down**, not rebuilt — start from today's `android-end-user-app/` and
  remove the collection-related code, rather than starting a slim app from scratch. Lower risk of
  regressing the detection pipeline, which is the part actually shipping to users.
- **Same convention as `android-training-data-reviewing-app/`**: `android-training-data-collection-app/` is a standalone Gradle project
  (own `settings.gradle.kts`, own `local.properties`), no build dependency between it and
  `android-end-user-app/`. It reuses the same Cognito backend and S3 bucket layout — no infra changes needed
  beyond documentation (uploads still land at `uploads/<sub>/<device>/<filename>.zip`; the
  `curators` group / `CuratorRole` / `execute-api:Invoke` grant from REQ-029 still exists and is
  still needed, now specifically for `android-training-data-collection-app/` instead of "the main app").
- **`applicationId`**: `com.github.yuriysemen.platesdetector.training`, following the
  `android-training-data-reviewing-app` precedent (`...platesdetector.curation`). `android-end-user-app/`'s `applicationId`
  (`com.github.yuriysemen.platesdetector`) is unchanged — it's the one already on Play, changing
  it would mean losing the existing listing rather than updating it.
- **No Play Store presence for `android-training-data-collection-app/`** — internal-only, sideloaded debug/release
  APK, same distribution model as `android-training-data-reviewing-app/`. No GitHub Actions signing workflow needed
  for it (mirror `android-training-data-reviewing-app/`'s `./gradlew :app:installDebug` convention), so no keystore
  secrets to manage for a second app.

## 1. What moves into `android-training-data-collection-app/` (unchanged behavior, new home)

Everything under REQ-005, REQ-006, REQ-011, REQ-013, REQ-014, REQ-015, REQ-016, REQ-018,
REQ-020, REQ-021, REQ-026, REQ-027, REQ-028, REQ-029 that isn't pure detection:

- Auth: `CognitoAuthManager`, `AuthScreen`, sign-in/out UI, session-expiry handling.
- Capture: `TrainingDataSaver` (auto/manual/burst), `DatasetExporter`, storage quota + eviction.
- Upload: `UploadDatasetWorker`, `AutoUploadWorker`, `UploadLog`, upload diagnostics UI.
- Model distribution: `ModelCheckWorker`, S3 `models/v<semver>/` auto-update, update activity log.
- `ContributeScreen` and its Settings-screen entry point.
- `local.properties` AWS/Cognito config (`COGNITO_*`, `UPLOAD_SERVICE_URL`).
- The detection pipeline itself (`PlateDetector`, `PlateOCR`, CameraX plumbing) — needed here too,
  since capture decisions depend on live detection output.

## 2. What stays in `android-end-user-app/` (published app)

- Live camera preview + controls (zoom, torch, tap-to-focus, EV compensation, resolution picker).
- `PlateDetector` (TFLite YOLO inference) + `PlateOCR` (ML Kit) — fully on-device, no network.
- Beep-on-detection.
- Settings: model picker (from bundled/local assets only — see §3), confidence threshold, scan
  interval, analysis resolution. No "Contribute data" row, no sign-in row, no model-update log.
- Model loading: the existing build-time bundling options (README "Getting a model" §Options 1–3)
  stay — a model is still fetched from a GitHub Release **at build time**, which happens on the
  developer's machine or in CI, not on-device. This is not user data collection and doesn't need
  an `INTERNET` permission at runtime.

## 3. Removed from `android-end-user-app/`

- All of §1's code, deleted (not stubbed) from `android-end-user-app/`.
- Runtime permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` — none of these
  are needed once there's no upload, no model-update check, and no upload-success notification.
  **Confirm no other feature needs them before removing** (e.g. if a future crash-reporting or
  update-check feature is added later, they'd come back).
- `local.properties` AWS/Cognito keys — `android-end-user-app/` no longer talks to Cognito, S3, or the API
  Gateway at all.
- `NoModelsScreen`'s sign-in path (REQ-027) — with no accounts, a missing model is just "no model
  bundled," a build-time problem, not a runtime one.

## 4. `android-training-data-reviewing-app/` — documentation only

No behavior change. Update:
- `android-training-data-reviewing-app/README.md` and `CLAUDE.md`: replace references to "the main `android-end-user-app/` app"
  (as the uploads producer) with "`android-training-data-collection-app/`".
- `aws-training-infra/aws/README.md`: same terminology update where it explains `DeviceAuthRole` /
  `CuratorRole` in terms of "the main app."

## 5. `requirements/` bookkeeping

- Existing REQ-002–REQ-021, REQ-026–REQ-029 stay as-is (accurate history of *what* was built);
  add a one-line note at the top of each pointing out the feature now lives in
  `android-training-data-collection-app/`, not `android-end-user-app/`, once the split ships. (Do this as a follow-up sweep after
  implementation, not before — no need to touch 15 files speculatively now.)
- REQ-007 and REQ-030: add a note that their scope now applies to `android-end-user-app/`'s status quo only
  until this ships, and to `android-training-data-collection-app/`'s *internal* data-handling practices afterward
  (not to Play Store compliance, since it won't be published there).

## Open questions — resolved

- **`android-training-data-collection-app/` signing:** `installDebug`-only, matching `android-training-data-reviewing-app/`. No CI
  signing workflow was added for it.
- **Release sequencing:** not decided by this document — still an open call for whoever submits
  the next Play release (tracked in REQ-030's own scope, not blocking this split's implementation).
- **`sam deploy` for REQ-029's `CuratorRole` `execute-api:Invoke` fix:** still pending as of this
  writing. `android-training-data-collection-app/`'s uploads/model-checks will hit the same `execute-api` 403 REQ-029
  fixed until that deploy happens — this split doesn't change that, it's an existing prerequisite.

## Acceptance criteria

- [x] `android-training-data-collection-app/` exists as a standalone Gradle project, builds, and reproduces every
      capability that was in `android-end-user-app/`'s "Contribute data" pipeline end-to-end (sign-in, capture,
      upload, model auto-update) under its own `applicationId`.
- [x] `android-end-user-app/` builds and runs with zero references to Cognito/S3/WorkManager-upload code; no
      `INTERNET`/`ACCESS_NETWORK_STATE`/`POST_NOTIFICATIONS` permissions in its manifest.
- [x] `privacy-policy.md` (the one linked from the Play listing) is the "no data collected"
      baseline and it is now factually true.
- [x] `android-training-data-reviewing-app/` and `aws-training-infra/aws/` docs no longer refer to `android-end-user-app/` as the uploads
      producer.
- [x] Root `README.md` hub table and component READMEs updated to describe three apps instead of
      two.
