---
id: REQ-016
title: Model Distribution — S3 Storage, Authenticated Lambda, and On-Device Auto-Update
status: draft
priority: high
---

## Summary

Replace the current model delivery pipeline (GitHub Releases → build-time asset bundle) with a
tiered model distribution system:

1. **S3 storage** — models are stored in the existing dataset S3 bucket under a `models/` prefix,
   each with a `metadata.json` describing its minimum compatible app version.
2. **Authenticated Lambda endpoint** — a new `GET /get-model-url` Lambda returns pre-signed S3
   download URLs for the model best compatible with the requesting app version and for the globally
   latest model.
3. **On-device auto-update** — when signed in, the app checks for a newer compatible model on
   every startup and every hour while running. The user is prompted before a download is applied.
   Downloaded models are removed on sign-out; the build-time bundled model is never deleted.
4. **Build fix** — the Gradle task that bundles a default model at build time is updated to
   discover the latest `model_v*` GitHub Release by semantic version, authenticate with the GitHub
   API for private-repo access, and fail gracefully if no release is found so the build still
   succeeds.

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
      plate_numbers.txt        ← human-readable model description (same content as GitHub asset)
      metadata.json
    v0.2.0/
      plate_numbers.tflite
      plate_numbers.txt
      metadata.json
```

Every model version lives in its own subfolder named `v<semver>`.

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
| `model_version` | string (semver) | yes | Must match the folder name (e.g. `"0.1.0"` for `models/v0.1.0/`). |
| `min_app_version` | string (semver) | yes | Minimum `android:versionName` that can use this model. |
| `max_app_version` | string (semver) | no | Last `android:versionName` that can use this model. Omit if there is no upper bound. |
| `tflite_filename` | string | yes | Name of the `.tflite` file inside this folder. |
| `description` | string | yes | Release notes shown in the app. |

Initial model upload (`v0.1.0`) is a one-time operator task performed manually after stack
deployment. All subsequent uploads are also manual.

---

## Part B — New Lambda: `GetModelUrlFunction`

### Endpoint

```
GET /get-model-url
```

Added to the existing `UploadApi` HTTP API. Requires SigV4 authentication (same as
`POST /get-upload-url`).

### Request (query string)

| Parameter | Required | Description |
|---|---|---|
| `app_version` | yes | Android `versionName` from `BuildConfig.VERSION_NAME` (e.g. `"0.0.11"`) |

### Response (JSON)

```json
{
  "compatible": {
    "model_version": "0.1.0",
    "tflite_filename": "plate_numbers.tflite",
    "s3_key": "models/v0.1.0/plate_numbers.tflite",
    "download_url": "<pre-signed GET URL valid for URL_EXPIRY_SECONDS>",
    "expires_in": 3600,
    "description": "First production plate-detection model."
  },
  "latest": {
    "model_version": "0.2.0",
    "tflite_filename": "plate_numbers.tflite",
    "s3_key": "models/v0.2.0/plate_numbers.tflite",
    "download_url": "<pre-signed GET URL>",
    "expires_in": 3600,
    "description": "Improved night-time detection."
  }
}
```

- `compatible` — the highest-versioned model where `min_app_version ≤ app_version`.
- `latest` — the highest-versioned model regardless of `min_app_version`.
- When both point to the same model, both fields carry identical data.
- When no compatible model exists for `app_version`, `compatible` is `null`.
- HTTP 404 `{"error": "no models available"}` when the `models/` prefix is empty or absent.

### Lambda logic

```
handler: handler.handler
runtime: python3.12
```

1. Read `app_version` from the query string; return HTTP 400 if missing or empty.
2. Call `s3.list_objects_v2(Bucket=BUCKET, Prefix="models/", Delimiter="/")` to get version
   folder prefixes (e.g. `models/v0.1.0/`).
3. For each prefix, fetch `<prefix>metadata.json` and parse `model_version`,
   `min_app_version`, `max_app_version` (optional), `tflite_filename`, and `description`.
4. Sort all entries by semantic version, descending.
5. `latest` = first entry.
6. `compatible` = first entry where `min_app_version ≤ app_version` AND
   (`max_app_version` is absent OR `app_version ≤ max_app_version`) (semver comparison).
7. Generate `s3.generate_presigned_url("get_object", ...)` for each result's `.tflite` key.
8. Return the response JSON.

Validation:
- Malformed `app_version` (not semver) → HTTP 400 `{"error": "invalid app_version"}`.
- No version folders → HTTP 404 `{"error": "no models available"}`.
- Unhandled exception → HTTP 500 `{"error": "<message>"}`.

### New Lambda source file

```
infra/aws/lambda/get_model_url/
  handler.py
  requirements.txt   (empty — boto3 only)
```

### SAM template changes

**New IAM role `ModelLambdaRole`:**

| Permission | Resource |
|---|---|
| `s3:ListBucket` with condition `Prefix: "models/"` | `arn:aws:s3:::<BucketName>` |
| `s3:GetObject` | `arn:aws:s3:::<BucketName>/models/*` |
| CloudWatch Logs write | `*` |

**Existing `DeviceAuthRole` update** — add permission:

```yaml
- Effect: Allow
  Action: execute-api:Invoke
  Resource: !Sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${UploadApi}/${StageName}/GET/get-model-url"
```

**New `GetModelUrlFunction` resource** in `Resources` section, wired to
`GET /get-model-url` on `UploadApi`.

No new stack outputs are needed; the Android app constructs the model endpoint URL from the
existing `UPLOAD_SERVICE_URL` `BuildConfig` constant.

---

## Part C — Build Fix (Gradle)

### Current problems

| # | Problem |
|---|---|
| 1 | `releases/latest/download` resolves to the newest release overall (could be an Android `v*` tag, not a model `model_v*` tag). |
| 2 | Downloading assets from a private repo with an OAuth token requires using the GitHub API asset endpoint (`Accept: application/octet-stream`), not the browser download URL. The current code uses the browser URL which redirects and fails silently for private repos. |
| 3 | If the download fails the build fails, blocking the app build even when the model file would later be downloadable at runtime. |

### Required changes

**Release discovery via GitHub API**

Replace the hardcoded `modelReleaseBaseUrl` with a function that:

1. Calls `GET https://api.github.com/repos/yuriysemen/plate-detector-android/releases`
   with header `Authorization: Bearer <MODEL_DOWNLOAD_TOKEN>` and
   `Accept: application/vnd.github+json`.
2. Filters releases whose `tag_name` matches `^model_v\d+\.\d+\.\d+$`.
3. Sorts the matching releases by semantic version (compare `x`, then `y`, then `z` as integers).
4. Returns the release with the greatest version, or `null` if none found.

**Asset download via API endpoint**

For each file to download (`.tflite`, `.txt`):

1. Find the matching asset in the selected release's `assets` array by filename.
2. Download using the asset's `url` field (API URL, e.g.
   `https://api.github.com/repos/.../releases/assets/<id>`) with headers:
   - `Authorization: Bearer <token>`
   - `Accept: application/octet-stream`
3. Follow redirects. The server returns the binary content directly.

**Graceful failure**

If any step fails (no `model_v*` release found, network error, HTTP error, missing asset):

- Print a descriptive `[WARN]` message to Gradle console.
- Skip downloading that file.
- Do **not** throw; do not fail the task.

The build continues. The `assets/models/` directory may be empty; the app handles this at runtime
(see Part D — No-model handling).

**Token source** — updated resolution order:
1. `local.properties` key `MODEL_DOWNLOAD_TOKEN` (new — read via the existing `localProp()` helper)
2. Gradle property `MODEL_DOWNLOAD_TOKEN`
3. Environment variable `MODEL_DOWNLOAD_TOKEN`
4. Environment variable `GITHUB_TOKEN` (automatically available in GitHub Actions)

`local.properties` is already gitignored, making it the natural place for a developer's personal
access token without risk of accidentally committing it.

### Local build workflow

Developers building on their own machine have three options, in order of convenience:

**Option 1 — Add a GitHub token to `local.properties`** (recommended for active development)

Add the following line to `android/local.properties`:
```
MODEL_DOWNLOAD_TOKEN=ghp_<your_personal_access_token>
```

The token needs only `repo` scope (read access to releases on a private repo). The Gradle task
will then discover and download the latest `model_v*` release automatically, exactly as CI does.

**Option 2 — Place the model file manually** (quickest one-time setup)

Copy any compatible `.tflite` (and its `.txt` sidecar) directly into:
```
android/app/src/main/assets/models/
```
These files are gitignored. The `downloadDefaultModels` task checks whether the output file
already exists and skips the download when it does, so the manually placed file is used as-is.

**Option 3 — Build without a model** (no setup required)

If neither a token nor a local file is present, the task prints a `[WARN]` and the build
succeeds without a bundled model. The app builds and installs normally but shows the
"No detection model available" screen until a model is downloaded at runtime after sign-in.

### Acceptance criteria additions (local build)

- [ ] `MODEL_DOWNLOAD_TOKEN` in `local.properties` is picked up by the Gradle task without any
  extra configuration.
- [ ] When a valid model file already exists in `build/generated/assets/defaultModels/models/`
  or is manually placed in `src/main/assets/models/`, the download step is skipped.
- [ ] When no token and no local model file are present, the build completes with a `[WARN]`
  message and no error.

---

## Part D — Android App

### Model storage on device

```
filesDir/models/downloaded/
  plate_numbers_v0.1.0.tflite     ← active downloaded model
  plate_numbers_v0.1.0.txt        ← description saved from API response
```

Only one downloaded model is kept at a time. Files are named
`<base>_v<model_version>.<ext>` so the version is derivable from the filename.

### SharedPreferences keys (new)

| Key | Type | Description |
|---|---|---|
| `downloaded_model_version` | String | Active downloaded model version (e.g. `"0.1.0"`). Empty string if none. |
| `downloaded_model_s3_key` | String | S3 key of the active downloaded model (used for change detection). Empty if none. |

### Model selection priority

1. **Downloaded model** — if `downloaded_model_version` is non-empty and the corresponding file
   exists in `filesDir/models/downloaded/`. The filename is reconstructed from
   `<base>_v<downloaded_model_version>.tflite`.
2. **Bundled asset** — loaded directly from `assets/models/` (the file downloaded at build time,
   or absent if the build-time download was skipped).
3. **No model** — neither source is available: show the existing "No detection model available"
   error screen.

The bundled asset model is loaded via `ModelSource.Asset`; it is never copied to the filesystem
and never deleted.

### Model update check flow

**Trigger:**
- App startup (after login state is confirmed as signed-in).
- `WorkManager` `PeriodicWorkRequest` with 1-hour repeat interval (network constraint:
  `CONNECTED`; respects mobile-data preference — see below).

**Steps:**

1. Call `GET <UPLOAD_SERVICE_URL>/get-model-url?app_version=<BuildConfig.VERSION_NAME>` signed
   with SigV4 using the user's current STS credentials.
2. On HTTP error or network failure: silently skip; do not notify the user.
3. Parse `compatible` from the response. If `null`: skip.
4. Compare `compatible.s3_key` with the stored `downloaded_model_s3_key` pref.
   - If they match: no update needed; stop.
5. Show a confirmation dialog:
   > **"Detection model update available"**
   > A newer model (v*X.Y.Z*) is ready to download.
   > **[Update]** &nbsp; **[Later]**
6. On **"Later"**: dismiss. The check will run again next startup / next hourly tick.
7. On **"Update"**:
   a. Download the `.tflite` file from `compatible.download_url` to a temp path
      `filesDir/models/downloaded/<filename>.download`.
   b. On success:
      - Delete the previous downloaded `.tflite` and `.txt` files (if any).
      - Rename temp file to final path `filesDir/models/downloaded/<filename>`.
      - Write `compatible.description` to `<filename_without_ext>.txt` alongside it.
      - Update `downloaded_model_version` and `downloaded_model_s3_key` prefs.
      - Reload the detector with the new model immediately (no app restart required).
      - Show a toast: `"Model updated to v<X.Y.Z>"`.
   c. On download failure:
      - Delete the temp file.
      - Show a toast: `"Model download failed. Current model unchanged."`.
      - Do not modify prefs; current model continues in use.

### Latest-model informational banner

If `latest.model_version > compatible.model_version` (a newer model exists but requires a newer
app version):

- Show a one-line banner in Settings → Model section:
  > `"Model v<latest.model_version> available for app v<latest.min_app_version>+"`
- No action offered. Banner is display-only.
- If `latest == compatible`: no banner.

### Sign-out model cleanup

When the user signs out (inside `CognitoAuthManager.signOut()`):

1. Delete all files in `filesDir/models/downloaded/`.
2. Clear `downloaded_model_version` and `downloaded_model_s3_key` prefs.
3. The detector immediately falls back to the bundled asset model (or the "No models" screen if
   none is bundled).

### Periodic check `WorkManager` setup

- Task: `ModelCheckWorker` (new `CoroutineWorker`).
- Constraints: `NetworkType.CONNECTED`; additionally `UNMETERED` when "Use mobile data" pref is
  `false`.
- Schedule: `PeriodicWorkRequest` with `repeatInterval = 1 hour`.
- Enqueued at sign-in; cancelled at sign-out.
- `ExistingPeriodicWorkPolicy.KEEP` — does not restart an in-progress check.

### Mobile-data setting rename

The existing pref key `upload_on_mobile_data` and its `WorkManager` constraint logic are
unchanged.

Only the UI label changes:

| Before | After |
|---|---|
| "Upload on mobile data" | **"Use mobile data"** |
| "Allow dataset uploads over mobile data" | **"Allow uploads and model downloads over mobile data"** |

The pref key, default value (`false`), and all existing upload-worker logic are unaffected.

---

## Acceptance criteria

### A — S3 Model Storage

- [ ] Operator can upload a model by placing `plate_numbers.tflite`, `plate_numbers.txt`, and a
  valid `metadata.json` under `models/v<semver>/` in the bucket.
- [ ] `metadata.json` with all four required fields is present for each uploaded version.
- [ ] The `model_version` field in `metadata.json` matches the folder name.

### B — Lambda: GetModelUrl

- [ ] `GET /get-model-url?app_version=0.0.11` (SigV4-signed) returns HTTP 200 with
  `compatible` and `latest` fields.
- [ ] `compatible.model_version` is the highest version where `min_app_version ≤ 0.0.11`.
- [ ] `latest.model_version` is the globally highest version.
- [ ] Both `download_url` values are valid pre-signed `GetObject` URLs that return the `.tflite`
  binary.
- [ ] Pre-signed URLs expire after `URL_EXPIRY_SECONDS` seconds.
- [ ] When `compatible == latest`, both fields carry identical `model_version` and `s3_key`.
- [ ] Missing `app_version` returns HTTP 400.
- [ ] Unsigned request returns HTTP 403.
- [ ] No `model_v*` versions in S3 returns HTTP 404.
- [ ] `app_version` that is older than all `min_app_version` values: `compatible` is `null`, `latest` is populated.
- [ ] `app_version` that exceeds `max_app_version` of a model: that model is excluded from `compatible` candidates.
- [ ] A model with no `max_app_version` field is treated as compatible with all future app versions.
- [ ] Unit tests for handler logic (no AWS required).

### C — Build

- [ ] `downloadDefaultModels` queries the GitHub Releases API and selects the release whose tag
  matches `model_v<x.y.z>` with the greatest semantic version.
- [ ] Asset download uses the GitHub API asset URL with `Accept: application/octet-stream` and
  `Authorization: Bearer <token>`.
- [ ] When no `model_v*` release exists, the task logs a warning and does **not** fail the build.
- [ ] When the download returns a non-2xx HTTP code, the task logs a warning and does not fail the
  build.
- [ ] `GITHUB_TOKEN` available in the GitHub Actions environment is sufficient for both release
  listing and asset download on a private repo.
- [ ] On a successful download, the `.tflite` and `.txt` files are placed in
  `build/generated/assets/defaultModels/models/` and included in the APK as assets.

### D — Android App

#### Startup / periodic check
- [ ] On app startup with a signed-in user, the app calls `GET /get-model-url` and compares
  `compatible.s3_key` with `downloaded_model_s3_key` pref.
- [ ] If a different compatible model is found, a confirmation dialog is shown.
- [ ] On "Update", the new model downloads; on success the old downloaded model is deleted,
  prefs are updated, and the detector reloads.
- [ ] On download failure, a toast is shown and the current model continues unchanged.
- [ ] The bundled asset model is never deleted or modified.
- [ ] `ModelCheckWorker` periodic task runs every hour while the user is signed in.
- [ ] The periodic task respects the "Use mobile data" toggle for its network constraint.
- [ ] The periodic task does not run when no user is signed in.

#### Sign-out cleanup
- [ ] Signing out deletes all files in `filesDir/models/downloaded/`.
- [ ] After sign-out, `downloaded_model_version` and `downloaded_model_s3_key` prefs are empty.
- [ ] The detector immediately uses the bundled asset model after sign-out.

#### Latest-model informational banner
- [ ] When `latest.model_version > compatible.model_version`, a banner appears in Settings
  showing the newer version and its minimum required app version.
- [ ] When `latest == compatible`, no banner is shown.

#### Mobile data toggle
- [ ] The toggle label reads "Use mobile data" with updated description.
- [ ] All existing upload-worker behaviour is unaffected.

#### No-model graceful handling
- [ ] If neither a downloaded nor a bundled model is present, the "No detection model available"
  screen is shown instead of a crash.
