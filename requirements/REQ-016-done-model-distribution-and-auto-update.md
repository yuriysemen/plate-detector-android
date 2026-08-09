---
id: REQ-016
title: Model Distribution — S3 Storage, Authenticated Lambda, and On-Device Auto-Update
status: done
priority: high
---

## Summary

Replace the previous model delivery pipeline (GitHub Releases → build-time asset bundle) with a
tiered model distribution system:

1. **S3 storage** — models are stored in the existing dataset S3 bucket under a `models/` prefix,
   each with a `metadata.json` describing its minimum compatible app version.
2. **Authenticated Lambda endpoint** — a new `GET /get-model-url` Lambda returns pre-signed S3
   download URLs for the model best compatible with the requesting app version and for the globally
   latest model.
3. **On-device auto-update** — when signed in, the app checks for a newer compatible model on
   every startup and every hour while running. The user is prompted before a download is applied;
   the downloaded model is auto-selected immediately after install.
4. **Manual check** — Settings shows a "Check now" button (signed-in only) that runs the model
   check inline, with a spinner during the request, and picks up any newly-found update immediately.
5. **In-memory activity log** — a per-session event log records every check, result, and download
   attempt; a one-line summary is visible in Settings with a "Details" button for the full list.
6. **Build fix** — the Gradle task that bundles a default model at build time is updated to
   discover the latest `model_v*` GitHub Release by semantic version, authenticate with the GitHub
   API for private-repo access, and fail gracefully if no release is found.
7. **Custom model import removed** — the ability to import `.tflite` files directly from the
   device storage was removed; authenticated server download is now the only path for adding models
   beyond the bundled default.

---

## Goals

- Allow the detection model to be updated for signed-in users without shipping a new APK.
- Keep a working fallback for unauthenticated users using the bundled build-time model.
- Map each model to a minimum compatible app version so that incompatible combinations are never
  offered.
- Expose both the "compatible with this app version" and the "globally latest" model in the API
  response so the app can inform the user when an app update would unlock a better model.
- Reuse the existing Cognito / SigV4 authentication stack with no new auth primitives.
- Rename the mobile-data toggle to cover both uploads and model downloads.

## Non-goals

- Uploading models to S3 (operator does this manually; out of scope).
- SHA-256 integrity verification of downloaded models (future hardening).
- Model rollback to a previous version.

---

## Part A — S3 Model Storage

### Bucket folder structure

```
<bucket>/
  uploads/        ← existing; unchanged
  models/
    v0.1.0/
      plate_numbers.tflite
      plate_numbers.txt        ← human-readable model description
      metadata.json
    v0.2.0/
      ...
```

### `metadata.json` schema

```json
{
  "model_version": "0.1.0",
  "min_app_version": "0.0.11",
  "max_app_version": "0.9.99",
  "tflite_filename": "plate_numbers.tflite",
  "description": "First production plate-detection model."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `model_version` | string (semver) | yes | Must match the folder name. |
| `min_app_version` | string (semver) | yes | Minimum `android:versionName` that can use this model. |
| `max_app_version` | string (semver) | no | Last `android:versionName`. Omit for no upper bound. |
| `tflite_filename` | string | yes | Name of the `.tflite` file inside this folder. |
| `description` | string | yes | Release notes shown in the app. |

---

## Part B — New Lambda: `GetModelUrlFunction`

### Endpoint

```
GET /get-model-url
```

Added to the existing `UploadApi` HTTP API. Requires SigV4 authentication.

### Request

| Parameter | Required | Description |
|---|---|---|
| `app_version` | yes | `BuildConfig.VERSION_NAME` (e.g. `"0.0.11"`) |

### Response

```json
{
  "compatible": {
    "model_version": "0.1.0",
    "s3_key": "models/v0.1.0/plate_numbers.tflite",
    "download_url": "<pre-signed GET URL>",
    "description": "First production plate-detection model."
  },
  "latest": { ... }
}
```

`compatible` is `null` when no model satisfies the app version constraint. HTTP 404 when the
`models/` prefix is empty.

---

## Part C — Build Fix

Replaced hardcoded `releases/latest` URL with a GitHub API call that:
1. Lists all releases, filters `model_v<x.y.z>` tags by semver, picks the greatest.
2. Downloads assets via the API asset endpoint with `Accept: application/octet-stream` and Bearer token.
3. Fails gracefully with a `[WARN]` if no release is found — build continues; app shows "No model" screen.

Token resolution order: `local.properties` → Gradle property → `MODEL_DOWNLOAD_TOKEN` env → `GITHUB_TOKEN` env.

---

## Part D — Android App

### Model storage

```
filesDir/models/downloaded/
  <filename>.tflite     ← active downloaded model (one at a time)
  <filename>.txt        ← description sidecar from Lambda response
```

### Selection priority

1. `DOWNLOADED` — if `downloaded_model_version` pref is non-empty and the file exists.
2. `DEFAULT` — bundled asset from `assets/models/`.
3. No model — "No detection model available" screen.

After a confirmed download the downloaded model is **auto-selected immediately** without requiring
the user to open Settings.

### Update check flow

Triggered at sign-in (one-shot) and every hour via `ModelCheckWorker` (periodic). Also triggerable
manually via "Check now" button in Settings (signed-in only; runs `ModelCheckWorker.performCheck()`
inline without WorkManager scheduling; shows a spinner during the request).

Steps:
1. `ModelCheckWorker.performCheck(context)` calls `GET /get-model-url?app_version=…` SigV4-signed.
2. Stores check result in `DownloadedModelPrefs` (last check time, latest version, pending update).
3. `LivePlateDetectionScreen` reads pending update in `LaunchedEffect(isSignedIn, reloadKey)` and
   shows a confirmation dialog.
4. "Update" → `applyModelUpdate()` downloads to `.download` temp file, replaces old model on
   success, auto-selects the new model, increments `reloadKey`.
5. "Later" → pending prefs cleared; will re-prompt on next check.
6. All events (check started, result, download started/completed/failed) appended to
   `ModelUpdateLog` (in-memory singleton, never persisted).

Since this check runs hourly (far more often than uploads), it's usually the first place a dead
Cognito refresh token is discovered. On `SessionExpiredException` from `getAwsCredentials()`,
`performCheck()` calls `CognitoAuthManager.markSessionExpired()` before re-throwing — see REQ-014
"Session expiry" for what that does and how it surfaces in ContributeScreen.

### Activity log (`ModelUpdateLog`)

Singleton `object` holding a `MutableStateFlow<List<Entry>>`. Never written to disk; resets on
app restart. Each entry has `timeMs: Long`, `message: String`, and `level: Level` (INFO / SUCCESS
/ ERROR). Settings shows the latest entry as a one-liner with colour coding; "Details" button
opens an `AlertDialog` with the full list (newest first, `HH:mm:ss` prefix). Log section and
"Check now" button are only visible when signed in.

### Sign-out cleanup

`CognitoAuthManager.signOut()` deletes `filesDir/models/downloaded/` and clears all
`DownloadedModelPrefs` keys. The detector falls back to the bundled asset (or "No model" screen).

### Informational banner

When `latest.model_version > compatible.model_version`, Settings shows a card:
`"Model v<latest> is available but requires a newer app version."` (display-only).

### Mobile-data toggle

UI label changed from "Upload on mobile data" to **"Use mobile data"** (covers uploads and model
downloads). Pref key and default value unchanged.

---

## Acceptance criteria

### A — S3 Model Storage
- [x] `metadata.json` with all required fields present under `models/v0.1.0/`.
- [x] `model_version` matches the folder name.

### B — Lambda: GetModelUrl
- [x] `GET /get-model-url?app_version=0.0.1` returns HTTP 200 with `compatible` and `latest`.
- [x] `compatible.model_version` is the highest version where `min_app_version ≤ app_version`.
- [x] `latest.model_version` is the globally highest version.
- [x] Both `download_url` values are valid pre-signed `GetObject` URLs.
- [x] Missing `app_version` returns HTTP 400.
- [x] Unsigned request returns HTTP 403.
- [x] No model versions in S3 returns HTTP 404.
- [x] `compatible` is `null` when app version is below all `min_app_version` values.
- [x] `max_app_version` exclusion works correctly.
- [x] Unit tests pass without AWS.

### C — Build
- [x] `downloadDefaultModels` picks the latest `model_v<x.y.z>` release by semantic version.
- [x] Asset download uses API URL with `Accept: application/octet-stream` + Bearer token.
- [x] No `model_v*` release → `[WARN]`, build succeeds.
- [x] Non-2xx HTTP → `[WARN]`, build succeeds.
- [x] `MODEL_DOWNLOAD_TOKEN` in `local.properties` is picked up automatically.

### D — Android App
- [x] `ModelCheckWorker` runs on sign-in (one-shot) and every hour (periodic).
- [x] Confirmation dialog shown when a new compatible model S3 key differs from active key.
- [x] On "Update": download, replace old model, auto-select new model, reload detector.
- [x] On "Later": pending prefs cleared; re-prompted on next check.
- [x] On download failure: toast shown, current model unchanged.
- [x] Bundled asset model never deleted or modified.
- [x] Periodic task respects "Use mobile data" network constraint.
- [x] Sign-out deletes `filesDir/models/downloaded/` and clears all downloaded model prefs.
- [x] Latest-model banner shown in Settings when a newer model requires a higher app version.
- [x] "Use mobile data" toggle label updated.
- [x] "No detection model available" screen shown when neither downloaded nor bundled model exists.
- [x] "Check now" button in Settings triggers an inline check (spinner during request; signed-in only).
- [x] Model update activity log shows one-line status + "Details" dialog in Settings (signed-in only).
- [x] Auto-selected downloaded model persisted in `ModelPrefs`; detector switches immediately.
- [x] Custom `.tflite` import from device storage removed from UI.
