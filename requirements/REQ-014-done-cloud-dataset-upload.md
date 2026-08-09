---
id: REQ-014
title: Cloud Dataset Upload — Pre-signed URL Upload via WorkManager
status: done
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

Send the packaged dataset ZIP directly to an AWS S3 bucket using a pre-signed URL obtained from a Lambda-backed API Gateway endpoint. Upload is triggered manually from ContributeScreen (REQ-005) or automatically by `AutoUploadWorker` (REQ-015). On successful upload, both the ZIP and its status sidecar are deleted from the device — no local trace of a completed upload is kept, to minimize the app's storage footprint.

The infrastructure (S3 bucket + Lambda + API Gateway + Cognito) is defined and deployed separately — see **REQ-018**.

Authentication is fully implemented end-to-end: users sign up and sign in with email + password via `AuthScreen`; the app exchanges their ID token for short-lived STS credentials from the Cognito Identity Pool; `UploadDatasetWorker` SigV4-signs every API Gateway request using those credentials.

---

## Upload architecture

```
Android app  (CognitoAuthManager)
    │
    │  SRP sign-in → Cognito User Pool → ID token
    │  ID token → Cognito Identity Pool → STS credentials (accessKey, secretKey, sessionToken)
    │
    │  POST /get-upload-url  { filename1, device_id, user_id }   ← SigV4-signed
    ▼
API Gateway (AWS_IAM auth) → Lambda (validates user_id, generates pre-signed S3 PUT URL)
    │
    │  { upload_url, object_key, expires_in }
    ▼
Android app
    │
    │  PUT <upload_url>  (binary ZIP body, Content-Type: application/zip)
    ▼
S3 bucket  (private, server-side encrypted)
           uploads/<user_sub>/<device_id>/<filename>
```

Key properties:
- **No static AWS credentials on device.** The app stores 4 Cognito config IDs (non-secret) embedded via `BuildConfig`; short-lived STS credentials are obtained at runtime and never persisted.
- **User authentication required.** Upload is only possible after signing in. The Identity Pool has `AllowUnauthenticatedIdentities: false`.
- **Upload-only.** The pre-signed URL allows `s3:PutObject` on one specific object key.
- **Per-user + per-device isolation.** The Lambda writes to `uploads/<user_sub>/<device_id>/<filename>`.
- **Short-lived URL.** Pre-signed URLs expire in 1 hour. If the upload has not started within that window, the app re-requests a fresh URL.
- **Nothing kept after upload.** On success the ZIP and its `.upload.json` sidecar are both removed from device storage — no permanent upload history is retained on device.

---

## Upload trigger

Upload is initiated by:
- Tapping **"Upload collected data"** on ContributeScreen (manual).
- `AutoUploadWorker` running at the scheduled daily time (automatic — REQ-015).

There is no mode selection — cloud upload is the only export path.

### Upload button behavior

A single global action button ("Upload collected data") drives the whole flow. There is no per-item "Retry" button — recovering a stuck or failed upload is always done by pressing this one button again.

- **Always tappable**, in every state (`NOT_QUEUED`, `PENDING`, `UPLOADING`, `FAILED`) as long as there are frames to upload and the user is signed in — including while a previous upload looks stuck or is still in progress. This gives the user a manual escape hatch instead of being stuck waiting on a job that may never resolve.
- **1-minute cooldown, not a state-based disable.** Immediately after being tapped the button disables for 60 seconds (to absorb accidental double-taps), then re-enables automatically regardless of whether the triggered upload has finished. It is never disabled purely because a job is "still uploading."
- **Restart semantics.** Tapping the button while a previous upload is `PENDING`, `UPLOADING`, or `FAILED`:
  1. Cancels every outstanding `UploadDatasetWorker` job — manual or auto-triggered (`WorkManager.cancelAllWorkByTag("dataset_upload")`).
  2. Deletes every export ZIP + sidecar still on disk that hasn't reached `UPLOADED` (their frames were already removed from `training_data/` at export time, so this is a genuine restart, not a resume — the in-flight data is discarded, which is an accepted trade-off for a simple always-available reset).
  3. Exports the currently-collected frames into a new ZIP and enqueues a fresh `UploadDatasetWorker`, as normal.
- **`UPLOADED` needs no special case.** By the time an entry reaches `UPLOADED` its ZIP + sidecar are already deleted (see below), so it's never in the cancel/delete set — tapping the button then is just a normal new upload.

---

## Android upload flow (technical)

### Step 0 — Authenticate (one-time, token cached by SDK)

The user signs in via `AuthScreen` using email + password (Cognito SRP auth). `CognitoAuthManager.signIn()` stores the Cognito sub (`user_id`) and email in `UploadPrefs`. Before each upload `CognitoAuthManager.getAwsCredentials()` exchanges the current ID token for short-lived STS credentials via the Identity Pool.

### Session expiry

`isSignedIn()` was previously just `UploadPrefs.getCognitoUserId(context).isNotEmpty()` — a flag set once at sign-in and only ever cleared by an explicit sign-out. It never reflected whether the Cognito **refresh token** itself (~30-day TTL) had actually expired, so a user whose refresh token died could sit on ContributeScreen seeing "Signed in" indefinitely while every upload silently failed with the same unrecoverable error.

Refresh-token expiry is only discoverable when the app actually tries to use it — `CognitoAuthManager.getIdToken()` throws `SessionExpiredException` when the SDK's session refresh falls through to `getAuthenticationDetails()` (i.e. the refresh token is dead, full re-authentication is required). Two workers hit this path:
- `UploadDatasetWorker` — via `getAwsCredentials()` before every upload attempt.
- `ModelCheckWorker` — via `getAwsCredentials()` on every hourly/on-demand model check (REQ-016), which runs far more often than uploads and so is usually the first to notice.

Both catch sites now call `CognitoAuthManager.markSessionExpired()`, which sets a persisted `cognito_session_expired` flag in `UploadPrefs`. This is deliberately lighter than `signOut()`:
- The cached user id and email are **kept** (not cleared), so the UI can say *who* needs to sign in again.
- The downloaded custom model is **not deleted** — only re-authentication is required, not a full local reset.

`isSignedIn()` now returns `UploadPrefs.getCognitoUserId(context).isNotEmpty() && !UploadPrefs.getSessionExpired(context)` — so a session-expired user is treated as signed out everywhere that already gates on `isSignedIn` (upload button, burst-mode "Send to server", model "Check now"), with no extra plumbing needed at those call sites. A separate `CognitoAuthManager.isSessionExpired()` lets the UI distinguish "never signed in" from "was signed in, now expired" for messaging.

`signIn()` clears the flag on success; `signOut()` also clears it (so a deliberate sign-out doesn't leave a stale "expired" banner behind).

**Resync on screen open.** Because the flag can be set by a background worker while ContributeScreen isn't composed, `LivePlateDetectionScreen` re-reads `isSignedIn()` / `currentUserEmail()` / `isSessionExpired()` from `CognitoAuthManager` every time the Contribute screen is opened (`LaunchedEffect(showExport)`), so a background-detected expiry is reflected the next time the user looks, without needing a live stream of auth state while the screen is already open.

**ContributeScreen treatment:**
- A red `StorageBanner` at the top: *"Your session expired — sign in again to resume uploads."* with a "Sign in" action button. Takes priority over the generic "Upload not configured" banner (which would otherwise also fire, since `uploadConfigured` requires `isSignedIn`).
- The auth status row shows "Session expired" / "Sign in again as `<email>`" instead of "Not signed in", when an email is cached.
- The "Upload collected data" button label reads "Session expired — sign in" instead of the generic "Sign in to upload".

### Step 1 — Request a pre-signed URL

The request is SigV4-signed using the STS credentials.

```
POST <upload_service_url>/get-upload-url
Content-Type: application/json
Authorization: AWS4-HMAC-SHA256 …
X-Amz-Date: …
X-Amz-Security-Token: …

{
  "filename": "plates_dataset_20260622_222232.zip",
  "device_id": "a3f8c1d4e9b2f7a0",
  "user_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
}
```

Response:

```json
{
  "upload_url": "https://bucket.s3.amazonaws.com/uploads/a3f8c1d4.../plates_dataset_20260622_222232.zip?X-Amz-...",
  "object_key": "uploads/a3f8c1d4e9b2f7a0/plates_dataset_20260622_222232.zip",
  "expires_in": 3600
}
```

### Step 2 — Upload the ZIP

```
PUT <upload_url>
Content-Type: application/zip
Content-Length: <file size in bytes>

<binary ZIP body>
```

A 200 response from S3 means the upload succeeded.

### Error handling

| Failure point | Action |
|---|---|
| Step 1 network error | `Result.retry()` — WorkManager exponential backoff |
| Step 1 non-2xx response | `Result.retry()` up to `MAX_ATTEMPTS` |
| Step 2 403 (pre-signed URL expired) | Re-request URL from Step 1 and retry the PUT once |
| Step 2 network error | `Result.retry()` |
| `SessionExpiredException` (token refresh failed) | `Result.failure()` immediately — user must re-authenticate |
| All retries exhausted | `Result.failure()` — status shown as FAILED in ContributeScreen; recover by tapping "Upload collected data" again (no per-item retry) |

---

## WorkManager job design (`UploadDatasetWorker`)

- One `UploadDatasetWorker` per export ZIP, enqueued immediately after the ZIP is written.
- **Unique work name:** the ZIP file path — prevents duplicate uploads of the same file.
- **Tag:** every job is also tagged `"dataset_upload"` (in addition to its per-ZIP tag) so all outstanding jobs — manual or auto — can be cancelled together when the user restarts an upload (see Upload button behavior).
- **Network constraint:** `NetworkType.UNMETERED` by default; `NetworkType.CONNECTED` when "Upload on mobile data" is on.
- **Retry policy:** exponential backoff, maximum `MAX_ATTEMPTS = 5`.
- **Input data:** `KEY_ZIP_PATH`, `KEY_DEVICE_ID`, `KEY_UPLOAD_URL`, `KEY_USER_ID`, `KEY_USER_POOL_ID`, `KEY_IDENTITY_POOL_ID`, `KEY_FRAME_COUNT`, `KEY_IS_AUTO_UPLOAD`.
- The worker is idempotent: if it runs twice it re-requests a fresh pre-signed URL and re-uploads. S3 PutObject is also idempotent (overwrites with identical content).
- **On success:** deletes the local ZIP and its sidecar — no `UPLOADED` record is persisted; the entry simply disappears from ContributeScreen's Upload history. If `KEY_IS_AUTO_UPLOAD == true` posts a notification (see REQ-015).
- **On final failure:** writes `FAILED` status to the sidecar; item shown as "Upload failed" in ContributeScreen with no per-item action — recovery is via the global "Upload collected data" button.

---

## Upload status

Upload status is tracked via the `.upload.json` sidecar file and WorkManager state. Statuses:

| Status | Description |
|---|---|
| `NOT_QUEUED` | ZIP exists but no WorkManager job is active |
| `PENDING` | Job enqueued, not yet started |
| `UPLOADING` | Worker is actively uploading |
| `FAILED` | All retries exhausted; ZIP + sidecar remain on disk until the user restarts an upload |
| `UPLOADED` | Terminal only in memory for one frame — the ZIP and sidecar are deleted immediately, so this status is never observed as a persisted/rendered list entry |

### Sidecar JSON schema

```json
{
  "status": "FAILED"
}
```

The sidecar only ever needs to carry the transient `status` field (`NOT_QUEUED`/`PENDING`/`UPLOADING`/`FAILED`) — it exists solely so ContributeScreen can render in-flight/failed state. `frame_count` for the completion notification (REQ-015) comes directly from `KEY_FRAME_COUNT` on the worker, not from the sidecar. There is no `uploaded_at` or `s3_object_key` bookkeeping on device; S3 object lifecycle is the server operator's responsibility.

On success the sidecar is **deleted**, not updated to `UPLOADED` — the app keeps no permanent local record of what was sent, to minimize storage use (see "Upload button behavior" above for the restart path that also cleans up stale `FAILED`/`PENDING` entries).

### Active entries

`DatasetExporter.listExports()` returns only **active entries** — ZIPs still on disk with status `NOT_QUEUED`, `PENDING`, `UPLOADING`, or `FAILED`. There are no history entries: once a ZIP's upload succeeds, both it and its sidecar are deleted and it stops appearing entirely.

Entries are surfaced in the "Upload history" section in ContributeScreen (effectively an "active uploads" list now), sorted by creation date descending. ContributeScreen observes job state via `getWorkInfosByTagFlow` and maps `WorkInfo.State` to `UploadStatus`.

**Display cap:** ContributeScreen shows at most 10 entries, with a footer line `"+ N more uploads not shown"` if exceeded. In practice this cap is rarely hit since restarting an upload clears out prior entries.

---

## Privacy and compliance

- No AWS credentials are stored on device. The only credential is the API Gateway URL (kept private by the operator).
- The only external device identifier is `device_id` (truncated SHA-256 hash — see REQ-013).
- Data Safety section update required before release: Photos/videos shared with third parties (AWS S3), encrypted in transit (HTTPS), purpose: app functionality.
- Privacy policy update: describe cloud upload, retention period, deletion request process.

---

## Acceptance criteria

### Authentication
- [x] `AuthScreen` provides sign-up (email + password + confirm), email verification (6-digit code), and sign-in flows.
- [x] `UserNotConfirmedException` on sign-in auto-routes to the verify-email screen.
- [x] "Resend code" uses `resendConfirmationCodeInBackground` (not re-registration).
- [x] Cognito exceptions are mapped to user-friendly messages (wrong password, duplicate email, expired code, etc.).
- [x] Sign-in/out status row shown in ContributeScreen Upload configuration card.
- [x] Upload button shows "Sign in to upload" when the user is not authenticated.
- [x] `AutoUploadWorker` skips silently when `user_id`, `user_pool_id`, or `identity_pool_id` is not set.
- [x] Sign-out cancels the scheduled auto-upload.
- [x] `isSignedIn()` returns `false` once the Cognito refresh token has expired, not just when no user was ever cached — a `SessionExpiredException` caught by `UploadDatasetWorker` or `ModelCheckWorker` calls `markSessionExpired()`, which is reflected by `isSignedIn()` immediately (no separate network check needed).
- [x] Session-expired state is distinguishable from never-signed-in: `isSessionExpired()` stays `true`, and the cached email is preserved (not cleared) so the UI can say who needs to sign in again.
- [x] ContributeScreen shows a red "Your session expired — sign in again to resume uploads." banner with a "Sign in" action when expired, taking priority over the generic "Upload not configured" banner.
- [x] The auth status row and the "Upload collected data" button label both reflect the session-expired state distinctly from "not signed in".
- [x] Opening ContributeScreen re-syncs sign-in/expiry state from `CognitoAuthManager`, so an expiry detected by a background worker while the screen wasn't open is picked up on next visit.
- [x] A successful sign-in clears the expired flag; an explicit sign-out also clears it (no stale "expired" banner after a deliberate sign-out).
- [x] Detecting session expiry does not delete the downloaded custom model or the cached email — only re-authentication is required, unlike explicit sign-out.

### Configuration
- [x] Cognito User Pool ID, App Client ID, Identity Pool ID, and Upload URL are embedded via `BuildConfig` fields read from `local.properties` at build time.
- [x] `AppConfig.seedPrefsIfNeeded()` seeds `UploadPrefs` from `BuildConfig` on first app launch.
- [x] No manual configuration fields shown to end users.

### Upload trigger
- [x] Tapping "Upload collected data" on ContributeScreen packages frames and enqueues `UploadDatasetWorker`.
- [x] The button is disabled when `total_frames == 0` or the user is not signed in.
- [x] The button is additionally disabled for 60 seconds after each tap (a fixed cooldown, not tied to job state), then re-enables automatically even if the triggered upload is still in progress.
- [x] The button is otherwise tappable in every upload state (`PENDING`, `UPLOADING`, `FAILED`) — it is never disabled just because a job is still running.
- [x] Tapping while a previous job is `PENDING`, `UPLOADING`, or `FAILED` cancels all outstanding `UploadDatasetWorker` jobs tagged `"dataset_upload"` (manual and auto), deletes their ZIPs + sidecars, then exports the currently-collected frames into a fresh ZIP and enqueues a new upload.

### Upload flow
- [x] Worker obtains STS credentials via `CognitoAuthManager.getAwsCredentials()` before each upload.
- [x] Worker POSTs to `<upload_service_url>/get-upload-url` with `filename`, `device_id`, and `user_id`; request is SigV4-signed using STS credentials.
- [x] Worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [x] A 200 response from S3 calls `onUploadSuccess`: deletes the local ZIP and its sidecar (no `UPLOADED` record is kept); entry disappears from the "Upload history" section.
- [x] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [x] `SessionExpiredException` causes immediate `Result.failure()` (no retry).
- [x] Manual upload, including a restart of a stuck/failed job via "Upload collected data", always uses `CONNECTED` (any network, including mobile data).
- [x] Auto-upload respects the "Upload on mobile data" toggle (UNMETERED vs CONNECTED constraint).
- [x] Retry policy: up to `MAX_ATTEMPTS = 5` with exponential backoff.

### ContributeScreen status
- [x] "Upload history" section appears as soon as any job is enqueued.
- [x] Section disappears once every entry has either succeeded (deleted) or been cleared by an upload restart — there is no persistent history to "clear" manually.
- [x] Status updates in real time as WorkManager job progresses.
- [x] When WorkManager reports SUCCEEDED: ZIP and sidecar both deleted; entry disappears from the list (no UPLOADED state is ever rendered).
- [x] Failed items show "Upload failed" status text with no per-item action; recovery is only via the global "Upload collected data" button.
- [x] Section hidden only when there are no active entries.
- [x] Section shows at most 10 entries; footer shows "+ N more uploads not shown" when exceeded (expected to rarely trigger, since restarting an upload clears stale entries).
