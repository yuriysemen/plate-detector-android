---
id: REQ-031
title: Split android/ into a Detection-Only Published App and a training-android Data-Collection App
status: draft
priority: high
---

## Summary

`android/` currently does two very different jobs in one app and one Play Store listing:
real-time on-device plate detection (the thing worth publishing), and an opt-in
account-creation / frame-capture / cloud-upload pipeline for building a training dataset (the
thing that turns "Car Plate Detector" into something closer to a private ALPR data-collection
operation — see REQ-007's account-deletion gap and the third-party-PII/legal concerns raised
alongside it). This requirement splits that in two:

- **`android/` (published, Google Play)** — detection only. Camera → TFLite YOLO → ML Kit OCR →
  on-screen overlay. **Stores nothing, uploads nothing, requires no account, needs no runtime
  network access.**
- **`training-android/` (new, not published — internal tool, sideloaded like `curation-android/`)**
  — everything that is today's "Contribute data" pipeline: sign-in, capture (auto/manual/burst),
  local storage, cloud upload, model auto-update. Used only by the developer / trusted testers to
  build the training dataset.
- **`curation-android/`** — unchanged in behavior; only its docs need to stop calling `android/`
  "the main app" that produces its uploads, since `training-android/` will be the producer now.

This removes essentially all of REQ-007's and REQ-030's compliance burden **from the published
app**, because the published app will no longer collect anything to declare. It does **not**
remove the underlying legal exposure of collecting third-party plates via `training-android/` —
that risk is about processing the data at all, not about which app does it or whether it's on
Play Store. See "What this does and doesn't fix" below.

## What this does and doesn't fix

**Fixes, for `android/`:**
- No more Play Data Safety declarations (nothing collected).
- No more account-deletion requirement (no accounts).
- REQ-007 and REQ-030 (as written) no longer apply to `android/` — see the notes added to those
  files once this ships.
- Privacy policy can revert to the original "no data collected, no upload" statement — true again.
- Likely no `INTERNET` permission needed on-device at all (see §3).

**Does not fix:**
- `training-android/` still captures other people's plates and centralizes them in your S3
  bucket. GDPR / state ALPR-style exposure is about that processing, independent of Play Store
  distribution. Keep treating `training-android/`'s data handling with the same care REQ-007
  described (minimize retention, honor deletion requests, know your actual data-residency region)
  even though nothing there is Play-reviewed.
- This is a scoping decision, not a legal opinion — get real legal advice before relying on
  "internal tool" as a shield if this dataset grows or gets used commercially.

## Key design decisions

- **`training-android/` is not a from-scratch reimplementation** — unlike `curation-android/`
  (which deliberately reimplements shared concepts rather than sharing code), `training-android/`
  starts as a **copy of today's `android/`** (it needs the same detection pipeline to know what to
  capture) and *keeps* the auth/capture/upload/model-update code that already exists there. The
  work is: rename the module/package, give it its own `applicationId`, and delete nothing.
- **`android/` is what gets stripped down**, not rebuilt — start from today's `android/` and
  remove the collection-related code, rather than starting a slim app from scratch. Lower risk of
  regressing the detection pipeline, which is the part actually shipping to users.
- **Same convention as `curation-android/`**: `training-android/` is a standalone Gradle project
  (own `settings.gradle.kts`, own `local.properties`), no build dependency between it and
  `android/`. It reuses the same Cognito backend and S3 bucket layout — no infra changes needed
  beyond documentation (uploads still land at `uploads/<sub>/<device>/<filename>.zip`; the
  `curators` group / `CuratorRole` / `execute-api:Invoke` grant from REQ-029 still exists and is
  still needed, now specifically for `training-android/` instead of "the main app").
- **`applicationId`**: `com.github.yuriysemen.platesdetector.training`, following the
  `curation-android` precedent (`...platesdetector.curation`). `android/`'s `applicationId`
  (`com.github.yuriysemen.platesdetector`) is unchanged — it's the one already on Play, changing
  it would mean losing the existing listing rather than updating it.
- **No Play Store presence for `training-android/`** — internal-only, sideloaded debug/release
  APK, same distribution model as `curation-android/`. No GitHub Actions signing workflow needed
  for it (mirror `curation-android/`'s `./gradlew :app:installDebug` convention), so no keystore
  secrets to manage for a second app.

## 1. What moves into `training-android/` (unchanged behavior, new home)

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

## 2. What stays in `android/` (published app)

- Live camera preview + controls (zoom, torch, tap-to-focus, EV compensation, resolution picker).
- `PlateDetector` (TFLite YOLO inference) + `PlateOCR` (ML Kit) — fully on-device, no network.
- Beep-on-detection.
- Settings: model picker (from bundled/local assets only — see §3), confidence threshold, scan
  interval, analysis resolution. No "Contribute data" row, no sign-in row, no model-update log.
- Model loading: the existing build-time bundling options (README "Getting a model" §Options 1–3)
  stay — a model is still fetched from a GitHub Release **at build time**, which happens on the
  developer's machine or in CI, not on-device. This is not user data collection and doesn't need
  an `INTERNET` permission at runtime.

## 3. Removed from `android/`

- All of §1's code, deleted (not stubbed) from `android/`.
- Runtime permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` — none of these
  are needed once there's no upload, no model-update check, and no upload-success notification.
  **Confirm no other feature needs them before removing** (e.g. if a future crash-reporting or
  update-check feature is added later, they'd come back).
- `local.properties` AWS/Cognito keys — `android/` no longer talks to Cognito, S3, or the API
  Gateway at all.
- `NoModelsScreen`'s sign-in path (REQ-027) — with no accounts, a missing model is just "no model
  bundled," a build-time problem, not a runtime one.

## 4. `curation-android/` — documentation only

No behavior change. Update:
- `curation-android/README.md` and `CLAUDE.md`: replace references to "the main `android/` app"
  (as the uploads producer) with "`training-android/`".
- `infra/aws/README.md`: same terminology update where it explains `DeviceAuthRole` /
  `CuratorRole` in terms of "the main app."

## 5. `requirements/` bookkeeping

- Existing REQ-002–REQ-021, REQ-026–REQ-029 stay as-is (accurate history of *what* was built);
  add a one-line note at the top of each pointing out the feature now lives in
  `training-android/`, not `android/`, once the split ships. (Do this as a follow-up sweep after
  implementation, not before — no need to touch 15 files speculatively now.)
- REQ-007 and REQ-030: add a note that their scope now applies to `android/`'s status quo only
  until this ships, and to `training-android/`'s *internal* data-handling practices afterward
  (not to Play Store compliance, since it won't be published there).

## Open questions (need a decision before implementation starts)

- [ ] Does `training-android/` need its own signing/release process at all, or is
      `installDebug`-only sufficient (matches `curation-android/`)?
- [ ] Should the existing Play Store listing's next release be **this split** (i.e., the next
      production update is "detection got simpler, nothing is collected anymore"), or should the
      collection features be pulled from Play first as a separate, smaller release, with the
      training app arriving after? Affects REQ-030's timeline.
- [ ] `training-android/`'s own model auto-update (§1) still depends on the Cognito/API-Gateway
      path — confirm REQ-029's `sam deploy` (still pending) happens before `training-android/` is
      used for real, or its uploads/model-checks will hit the same `execute-api` 403 the fix
      addressed.

## Acceptance criteria

- [ ] `training-android/` exists as a standalone Gradle project, builds, and reproduces every
      capability currently in `android/`'s "Contribute data" pipeline end-to-end (sign-in, capture,
      upload, model auto-update) under its own `applicationId`.
- [ ] `android/` builds and runs with zero references to Cognito/S3/WorkManager-upload code; no
      `INTERNET`/`ACCESS_NETWORK_STATE`/`POST_NOTIFICATIONS` permissions in its manifest unless a
      concrete remaining feature still needs one.
- [ ] `privacy-policy.md` (the one linked from the Play listing) reverted to the "no data
      collected" baseline and it is now factually true.
- [ ] `curation-android/` and `infra/aws/` docs no longer refer to `android/` as the uploads
      producer.
- [ ] Root `README.md` hub table and component READMEs updated to describe three apps instead of
      two.
