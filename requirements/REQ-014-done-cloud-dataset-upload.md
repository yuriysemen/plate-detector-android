---
id: REQ-014
title: Cloud Dataset Upload — Pre-signed URL Upload via WorkManager
status: done
priority: high
depends_on: REQ-013, REQ-018
---

> **Implemented in `android/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `training-android/`, not `android/`.**

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

A global action button ("Upload collected data") drives the primary flow, and each Upload history entry additionally has its own **Restart** control for recovering that one stuck/failed session without discarding every other in-flight upload.

- **Always tappable**, in every state (`NOT_QUEUED`, `PENDING`, `UPLOADING`, `FAILED`) as long as there are frames to upload and the user is signed in — including while a previous upload looks stuck or is still in progress. This gives the user a manual escape hatch instead of being stuck waiting on a job that may never resolve.
- **1-minute cooldown, not a state-based disable.** Immediately after being tapped the button disables for 60 seconds (to absorb accidental double-taps), then re-enables automatically regardless of whether the triggered upload has finished. It is never disabled purely because a job is "still uploading."
- **Restart semantics (global button).** Tapping the button while a previous upload is `PENDING`, `UPLOADING`, or `FAILED`:
  1. Cancels every outstanding `UploadDatasetWorker` job — manual or auto-triggered (`WorkManager.cancelAllWorkByTag("dataset_upload")`).
  2. Deletes every export ZIP + sidecar still on disk that hasn't reached `UPLOADED` (their frames were already removed from `training_data/` at export time, so this is a genuine restart, not a resume — the in-flight data is discarded, which is an accepted trade-off for a simple always-available reset).
  3. Exports the currently-collected frames into a new ZIP and enqueues a fresh `UploadDatasetWorker`, as normal.
- **`UPLOADED` needs no special case.** By the time an entry reaches `UPLOADED` its ZIP + sidecar are already deleted (see below), so it's never in the cancel/delete set — tapping the button then is just a normal new upload.

### Per-item Restart control (Upload history)

Each row in the Upload history section computes its own `canRestart` flag from that entry's live status and its sidecar's `updated_at` timestamp (see "Sidecar JSON schema" below):

| Displayed status | `canRestart` | Reasoning |
|---|---|---|
| `FAILED` | Immediately `true` | All retries exhausted — there is nothing to wait for. |
| `PENDING`, `UPLOADING` | `true` once `now - updated_at ≥ 30 minutes` | The worker's own status write is the only "last operation" signal we have; if it hasn't moved in 30 minutes the process most likely died (killed, OOM, force-stopped) without WorkManager ever reaching a terminal state — or, for `PENDING`, the job is `ENQUEUED` behind a network constraint that may never be satisfied (e.g. Wi-Fi-only while offline). |
| `NOT_QUEUED`, `UPLOADED` | `false` | Not applicable. |

A 15-second UI tick (`nowTick`, `ContributeScreen`) re-evaluates every row's `canRestart` live, so a stalled entry becomes restartable on screen without the user needing to leave and re-enter ContributeScreen.

When `canRestart` is true, the row shows a **Restart** `TextButton`. Tapping it re-enqueues the same ZIP via `enqueueUpload(..., forceAnyNetwork = true, policy = ExistingWorkPolicy.REPLACE)`, which also stamps a fresh `updated_at` via `writeUploadStatus(..., PENDING)`. Only that entry's unique work name is affected — every other outstanding upload is untouched.

`REPLACE` (not a separate `cancelUniqueWork()` followed by `enqueueUniqueWork(..., KEEP, ...)`) is deliberate: `cancelUniqueWork()` is asynchronous, so a subsequent `KEEP` enqueue can run before the cancellation has actually been processed, see the old job as still "pending", and silently drop the new request — leaving the stale job to eventually finish on its own (often with `FAILED`, since a cancelled-but-not-yet-torn-down worker's blocking network call can still throw once WorkManager's own cancellation propagates) with no replacement ever queued. `REPLACE` performs the cancel-and-insert as one atomic WorkManager transaction, closing that race.

This is additive to, not a replacement for, the global "Upload collected data" button — that one remains the always-available reset for "start over entirely."

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

**Streaming, not buffering.** `putZip()` calls `conn.setFixedLengthStreamingMode(zipFile.length())` before writing (which also sets the `Content-Length` header — no separate manual `setRequestProperty` call is needed). Without this call, `HttpURLConnection` buffers the *entire* request body in memory before sending any of it — a well-known gotcha, and for a multi-MB ZIP a significant, avoidable allocation on top of everything else already competing for heap during an upload. It was also the root cause of an intermittent `SocketException: Broken pipe`: buffering delays the first byte hitting the socket, and if that delay is long enough the connection can go stale/close server-side before the (buffered) write finally happens. With fixed-length streaming mode, bytes are written directly to the socket as the ZIP is read, in the same 64 KB chunks used for progress reporting (below) — memory use during the PUT is bounded by that chunk size, not the ZIP's total size.

### Error handling

| Failure point | Action |
|---|---|
| Step 1 network error | `Result.retry()` — WorkManager exponential backoff |
| Step 1 non-2xx response | `Result.retry()` up to `MAX_ATTEMPTS` |
| Step 2 403 (pre-signed URL expired) | Re-request URL from Step 1 and retry the PUT once |
| Step 2 network error | `Result.retry()` |
| `SessionExpiredException` (token refresh failed) | `Result.failure()` immediately — user must re-authenticate |
| All retries exhausted | `Result.failure()` — status shown as FAILED in ContributeScreen; recover by tapping that entry's **Restart** button, or "Upload collected data" to reset everything |

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
- **On final failure:** writes `FAILED` status to the sidecar; item shown as "Upload failed" in ContributeScreen with an immediate per-item **Restart** button (see "Per-item Restart control" above), in addition to the global "Upload collected data" button.

### Upload progress reporting

`putZip()` reads and writes the ZIP in a manual 64 KB chunked loop (rather than a single `copyTo()` call) so it can report progress via `CoroutineWorker.setProgress(Data)` as it goes — `KEY_PROGRESS_BYTES` (bytes written so far) and `KEY_PROGRESS_TOTAL` (ZIP size). Updates are throttled to at most once every 250 ms (the final chunk always reports, regardless of throttle) to avoid a WorkManager DB write per 64 KB chunk on large exports.

`ContributeScreen` reads this back from the same `WorkInfo` it already collects per row via `getWorkInfosByTagFlow` — no separate observation mechanism. While a row is `UPLOADING` and not stuck, it shows a determinate `CircularProgressIndicator` plus `"Uploading… NN%"`; if no progress data has arrived yet (e.g. right at the start of an attempt, or for a resumed job with no reported progress), it falls back to the original indeterminate spinner + `"Uploading…"`.

---

## Upload status

Upload status is tracked via the `.upload.json` sidecar file and WorkManager state. Statuses:

| Status | Description |
|---|---|
| `NOT_QUEUED` | ZIP exists but no WorkManager job is active |
| `PENDING` | Job enqueued, not yet started |
| `UPLOADING` | Worker is actively uploading |
| `FAILED` | All retries exhausted; ZIP + sidecar remain on disk until the user restarts an upload (globally or per-item) |
| `UPLOADED` | Terminal only in memory for one frame — the ZIP and sidecar are deleted immediately, so this status is never observed as a persisted/rendered list entry |

### Sidecar JSON schema

```json
{
  "status": "FAILED",
  "updated_at": 1755000000000
}
```

`updated_at` (epoch millis) is stamped by `DatasetExporter.writeUploadStatus()` on every write — enqueue, worker start, retry, and terminal failure — so it always reflects the last time *something* happened to this session. It is the sole input to the per-item stale-restart check (`STALE_UPLOAD_TIMEOUT_MS = 30 min`, see "Per-item Restart control" above). Sidecars written before this field existed are read with `updated_at` falling back to the sidecar file's own mtime.

`frame_count` for the completion notification (REQ-015) comes directly from `KEY_FRAME_COUNT` on the worker, not from the sidecar. There is no `s3_object_key` bookkeeping on device; S3 object lifecycle is the server operator's responsibility.

On success the sidecar is **deleted**, not updated to `UPLOADED` — the app keeps no permanent local record of what was sent, to minimize storage use (see "Upload button behavior" and "Per-item Restart control" above for the two restart paths that clean up stale `FAILED`/`PENDING`/stuck-`UPLOADING` entries).

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

### Per-item restart
- [x] Each Upload history row computes `canRestart` from its own live status and its sidecar's `updated_at`.
- [x] `FAILED` rows are restartable immediately.
- [x] `PENDING` and `UPLOADING` rows become restartable once `updated_at` is ≥ 30 minutes stale (`DatasetExporter.STALE_UPLOAD_TIMEOUT_MS`); `NOT_QUEUED`/`UPLOADED` rows are never restartable.
- [x] A 15-second UI tick re-evaluates `canRestart` for every visible row without requiring the user to leave and re-enter ContributeScreen.
- [x] A restartable row shows a **Restart** button; tapping it re-enqueues the same ZIP with `forceAnyNetwork = true` and `ExistingWorkPolicy.REPLACE` (atomic cancel-and-insert under that entry's unique work name), leaving every other in-flight upload untouched.
- [x] Restart uses `REPLACE`, not a separate `cancelUniqueWork()` + `KEEP` pair — avoids a race where an async pending cancellation causes `KEEP` to silently drop the new enqueue.
- [x] Re-enqueueing via Restart stamps a fresh `updated_at` on the sidecar (via the normal `PENDING` write in `enqueueUpload()`), resetting the stale timer.
- [x] `writeUploadStatus()` stamps `updated_at` (epoch millis) on every write; older sidecars without the field fall back to the sidecar file's mtime.

### Upload flow
- [x] Worker obtains STS credentials via `CognitoAuthManager.getAwsCredentials()` before each upload.
- [x] Worker POSTs to `<upload_service_url>/get-upload-url` with `filename`, `device_id`, and `user_id`; request is SigV4-signed using STS credentials.
- [x] Worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [x] A 200 response from S3 calls `onUploadSuccess`: deletes the local ZIP and its sidecar (no `UPLOADED` record is kept); entry disappears from the "Upload history" section.
- [x] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [x] `SessionExpiredException` causes immediate `Result.failure()` (no retry).
- [x] Manual upload, including a restart of a stuck/failed job via "Upload collected data" or a per-item Restart button, always uses `CONNECTED` (any network, including mobile data).
- [x] Auto-upload respects the "Upload on mobile data" toggle (UNMETERED vs CONNECTED constraint).
- [x] Retry policy: up to `MAX_ATTEMPTS = 5` with exponential backoff.

### ContributeScreen status
- [x] "Upload history" section appears as soon as any job is enqueued.
- [x] Section disappears once every entry has either succeeded (deleted) or been cleared by an upload restart — there is no persistent history to "clear" manually.
- [x] Status updates in real time as WorkManager job progresses.
- [x] When WorkManager reports SUCCEEDED: ZIP and sidecar both deleted; entry disappears from the list (no UPLOADED state is ever rendered).
- [x] Failed items show "Upload failed" status text plus an immediate per-item Restart button, in addition to the global "Upload collected data" button.
- [x] Stuck `PENDING` items show "Stuck pending — no progress in 30+ min"; stuck `UPLOADING` items show "Stuck uploading — no progress in 30+ min"; both get a Restart button once stale. Non-stale `PENDING`/`UPLOADING` show the normal "Pending upload" text / spinner + "Uploading…".
- [x] Section hidden only when there are no active entries.
- [x] Section shows at most 10 entries; footer shows "+ N more uploads not shown" when exceeded (expected to rarely trigger, since restarting an upload clears stale entries).

### Upload progress reporting
- [x] `putZip()` uploads via a manual chunked read/write loop (64 KB chunks) instead of `copyTo()`, calling `setProgress()` with bytes-written/total as it goes.
- [x] Progress updates are throttled to ≥ 250 ms apart; the final chunk always reports regardless of throttle.
- [x] `ContributeScreen` reads progress from the same per-row `WorkInfo` flow it already observes (`WorkInfo.progress`), with no separate plumbing.
- [x] While `UPLOADING` and not stuck, the row shows a determinate progress indicator and `"Uploading… NN%"`; falls back to the indeterminate spinner + `"Uploading…"` when no progress data is available yet.
- [x] `conn.setFixedLengthStreamingMode(zipFile.length())` is set before writing, so the PUT streams directly to the socket (bounded by the 64 KB chunk size) instead of buffering the whole ZIP in memory; this also fixed an intermittent `SocketException: Broken pipe` caused by the buffering delay.
