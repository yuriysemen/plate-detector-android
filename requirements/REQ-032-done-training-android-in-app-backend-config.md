---
id: REQ-032
title: In-App Backend Configuration Screen (training-android)
status: done
priority: medium
---

## Summary

`training-android/` currently gets its Cognito/API backend pointer (`COGNITO_USER_POOL_ID`,
`COGNITO_APP_CLIENT_ID`, `COGNITO_IDENTITY_POOL_ID`, `UPLOAD_SERVICE_URL`) only at **build time**,
via `local.properties` → `BuildConfig` → seeded once into `UploadPrefs` (SharedPreferences).
Pointing a device at a different backend, or handing the app to a tester, currently means
rebuilding with different `local.properties` values.

This requirement adds an **in-app screen** to view and edit these four values directly on-device,
so one generic APK can be installed anywhere and pointed at a backend without a rebuild — a better
fit for `training-android` than the published app's build-time-only approach, since this one is
internal tooling potentially used across multiple devices/testers ([REQ-031](REQ-031-draft-split-detection-and-training-apps.md)).

**Scope: `training-android/` only.** `android/` is losing this entire subsystem per REQ-031, so
this doesn't apply there.

## What these four values actually are

For the in-app helper text and for anyone configuring this by hand:

| Value | What it is | Where it comes from |
|---|---|---|
| `COGNITO_USER_POOL_ID` | The Cognito **User Pool**'s ID (format `<region>_<id>`, e.g. `us-east-1_3kjy9Uu7z`). The pool of email+password accounts used to sign in. | `infra/aws` stack output `UserPoolId` |
| `COGNITO_APP_CLIENT_ID` | The User Pool's **App Client** ID. Identifies this app to Cognito for SRP sign-in (no client secret — it's a public/mobile client). | Stack output `UserPoolClientId` |
| `COGNITO_IDENTITY_POOL_ID` | The Cognito **Identity Pool** ID (format `<region>:<uuid>`). Exchanges a signed-in user's ID token for short-lived AWS STS credentials used to SigV4-sign upload/API requests. **The AWS region for every AWS call is derived from this value's `<region>:` prefix** — there's no separate region field. | Stack output `IdentityPoolId` |
| `UPLOAD_SERVICE_URL` | The base URL of the API Gateway HTTP API fronting the `get-upload-url` and `get-model-url` Lambdas (e.g. `https://xxxx.execute-api.<region>.amazonaws.com/prod`). | Stack output `UploadServiceUrl` |

None of these four are secrets (no static AWS keys are ever embedded) — they're pool/client
identifiers and a public endpoint URL, same as what already ships inside the built APK today.
Storing them in plain `SharedPreferences` and letting a user type them in is not a new exposure.

## Current architecture (why this is mostly a UI feature, not a new plumbing layer)

Every runtime call site (`CognitoAuthManager`, `AutoUploadWorker`, `ModelCheckWorker`,
`UploadDatasetWorker`) already reads these four values from `UploadPrefs`
(`LivePlateDetectionScreen.kt`), **not** from `BuildConfig` directly. `BuildConfig`/`AppConfig` is
only consulted once, by `AppConfig.seedPrefsIfNeeded()`, which seeds/reconciles `UploadPrefs` from
the build-time values on each app start. So the actual runtime source of truth is already an
editable store — there's just no UI over it today. This requirement is primarily: add a screen
bound to `UploadPrefs`'s existing getters/setters, plus reuse the "identity changed → sign out"
logic `seedPrefsIfNeeded()` already has.

## Proposed design

- **New screen** (e.g. `BackendConfigScreen`) with four text fields bound to
  `UploadPrefs.get/setUserPoolId`, `getAppClientId`/`setAppClientId`,
  `getIdentityPoolId`/`setIdentityPoolId`, `getUploadUrl`/`setUploadUrl`. Each field gets the
  one-line description from the table above as helper text.
- **Reachable from Settings** at any time (e.g. a "Backend configuration" row), not gated behind
  sign-in — you need it configured *before* you can sign in.
- **Auto-routed there when unconfigured**: if `AppConfig.isConfigured` (or the `UploadPrefs`
  equivalent) is false and the user taps "Sign in" / "Contribute data", route to this screen first
  instead of the current dead-end (rebuild-required) state.
- **Validation before save**: reject blank fields; validate `COGNITO_IDENTITY_POOL_ID` matches
  `<region>:<uuid>` shape (the region-derivation depends on it) with an inline error rather than
  accepting garbage and failing confusingly later — same defensive posture as the recent
  `PlateDetector.isValidModel` fix.
- **Sign-out on identity change**: saving a changed `COGNITO_USER_POOL_ID` /
  `COGNITO_APP_CLIENT_ID` / `COGNITO_IDENTITY_POOL_ID` must sign the user out and clear the
  downloaded model, mirroring `seedPrefsIfNeeded()`'s existing `identityChanged` handling — cached
  tokens/STS credentials belong to the old backend. Changing only `UPLOAD_SERVICE_URL` does not
  need a forced sign-out (matches its `isIdentity = false` treatment today).
- **Detection keeps working regardless** — camera/YOLO/OCR don't depend on any of this, so an
  unconfigured or misconfigured backend should never block plain detection, only sign-in/upload.

## Resolved: manual edits pin permanently (option a)

Each of the four fields gets its own "pinned" flag in `UploadPrefs`, set the moment the user saves
that field via `BackendConfigScreen`. `AppConfig.seedPrefsIfNeeded()`'s reconciliation loop skips
any field whose pin flag is set — a manual edit permanently opts that field out of build-time
reconciliation, even across future rebuilds/redeploys. Per-field (not a single all-or-nothing
flag) so, e.g., a user who only overrides `UPLOAD_SERVICE_URL` doesn't accidentally freeze the
three Cognito identity fields too. There is intentionally no "reset to build defaults" UI in this
first pass — if someone needs to un-pin, that's a manual `Clear storage` for now.

## Acceptance criteria

- [x] `BackendConfigScreen` exists, reachable from Settings, with the four fields and helper text.
- [x] Saving invalid input (blank, or a malformed Identity Pool ID) shows an inline error and does
      not persist.
- [x] Saving a changed identity value (`COGNITO_*`) signs the user out and clears the downloaded
      model; saving only `UPLOAD_SERVICE_URL` does not.
- [x] Tapping "Sign in" / "Contribute data" while unconfigured routes to `BackendConfigScreen`
      instead of the current dead end.
- [x] A manually-edited field is never overwritten by `seedPrefsIfNeeded()` again, even after a
      rebuild with different `local.properties` values.
- [x] `training-android/README.md` / `CLAUDE.md` updated to describe in-app configuration as an
      alternative to `local.properties`.

## Implementation notes

Two things came up during on-device testing that weren't anticipated in the design above:

- **"Configured" needs to mean "plausible," not just "non-empty."** A `local.properties` copy that
  still had the literal placeholder text from `local.properties.example` (e.g.
  `COGNITO_IDENTITY_POOL_ID=<region>:<identity-pool-uuid>`) was non-empty, so the original
  non-empty-string check treated it as configured, seeded the placeholder into `UploadPrefs`, and
  the app went straight to `AuthScreen` — `BackendConfigScreen` was never reachable at all. Fixed
  by validating the Identity Pool ID against the same `<region>:<uuid>` shape everywhere
  (`AppConfig.IDENTITY_POOL_ID_PATTERN`, shared by `isConfigured`, `isBackendConfigured`, and
  `BackendConfigScreen`'s own save validation), so a placeholder or malformed value no longer
  counts as configured anywhere in the chain.
- **A routing gate alone isn't enough to reach the screen.** Since the bug above meant
  "configured" could be wrong, `NoModelsScreen` got its own always-visible **"Configure backend…"**
  link, independent of what `isBackendConfigured` currently reports — the one guaranteed way in,
  no matter what state the stored config is in.
