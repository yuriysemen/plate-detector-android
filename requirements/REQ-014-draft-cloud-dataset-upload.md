---
id: REQ-014
title: Cloud Dataset Upload — Pre-signed URL Upload via WorkManager
status: done
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

Send the packaged dataset ZIP directly to an AWS S3 bucket using a pre-signed URL obtained from a Lambda-backed API Gateway endpoint. Upload is triggered manually from ContributeScreen (REQ-005) or automatically by `AutoUploadWorker` (REQ-015). The ZIP is deleted from the device after a successful upload.

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
    │  POST /get-upload-url  { filename, device_id, user_id }   ← SigV4-signed
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
- **ZIP deleted after upload.** On success the ZIP is removed from device storage.

---

## Upload trigger

Upload is initiated by:
- Tapping **"Upload collected data"** on ContributeScreen (manual).
- `AutoUploadWorker` running at the scheduled daily time (automatic — REQ-015).

There is no mode selection — cloud upload is the only export path.

---

## Android upload flow (technical)

### Step 0 — Authenticate (one-time, token cached by SDK)

The user signs in via `AuthScreen` using email + password (Cognito SRP auth). `CognitoAuthManager.signIn()` stores the Cognito sub (`user_id`) and email in `UploadPrefs`. Before each upload `CognitoAuthManager.getAwsCredentials()` exchanges the current ID token for short-lived STS credentials via the Identity Pool.

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
| All retries exhausted | `Result.failure()` — status shown as FAILED in ContributeScreen |

---

## WorkManager job design (`UploadDatasetWorker`)

- One `UploadDatasetWorker` per export ZIP, enqueued immediately after the ZIP is written.
- **Unique work name:** the ZIP file path — prevents duplicate uploads of the same file.
- **Network constraint:** `NetworkType.UNMETERED` by default; `NetworkType.CONNECTED` when "Upload on mobile data" is on.
- **Retry policy:** exponential backoff, maximum `MAX_ATTEMPTS = 5`.
- **Input data:** `KEY_ZIP_PATH`, `KEY_DEVICE_ID`, `KEY_UPLOAD_URL`, `KEY_USER_ID`, `KEY_USER_POOL_ID`, `KEY_IDENTITY_POOL_ID`, `KEY_FRAME_COUNT`, `KEY_IS_AUTO_UPLOAD`.
- The worker is idempotent: if it runs twice it re-requests a fresh pre-signed URL and re-uploads. S3 PutObject is also idempotent (overwrites with identical content).
- **On success:** deletes the local ZIP; if `KEY_IS_AUTO_UPLOAD == true` posts a notification (see REQ-015).
- **On final failure:** writes `FAILED` status to the export metadata; item shown with "Retry" button in ContributeScreen.

---

## Upload status

Upload status is tracked via the ZIP file's sidecar metadata and WorkManager state. Statuses:

| Status | Description |
|---|---|
| `NOT_QUEUED` | ZIP exists but no WorkManager job is active |
| `PENDING` | Job enqueued, not yet started |
| `UPLOADING` | Worker is actively uploading |
| `FAILED` | All retries exhausted |
| `UPLOADED` | Upload succeeded; ZIP deleted; item removed from UI |

ContributeScreen observes job state via `getWorkInfosByTagFlow` and maps `WorkInfo.State` to `UploadStatus`. When `SUCCEEDED` is reported the item is immediately removed from the "Session in progress" list.

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

### Configuration
- [x] Cognito User Pool ID, App Client ID, Identity Pool ID, and Upload URL are embedded via `BuildConfig` fields read from `local.properties` at build time.
- [x] `AppConfig.seedPrefsIfNeeded()` seeds `UploadPrefs` from `BuildConfig` on first app launch.
- [x] No manual configuration fields shown to end users.

### Upload trigger
- [x] Tapping "Upload collected data" on ContributeScreen packages frames and enqueues `UploadDatasetWorker`.
- [x] The button is disabled when `total_frames == 0` or the user is not signed in.

### Upload flow
- [x] Worker obtains STS credentials via `CognitoAuthManager.getAwsCredentials()` before each upload.
- [x] Worker POSTs to `<upload_service_url>/get-upload-url` with `filename`, `device_id`, and `user_id`; request is SigV4-signed using STS credentials.
- [x] Worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [x] A 200 response from S3 deletes the local ZIP and removes the item from "Session in progress".
- [x] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [x] `SessionExpiredException` causes immediate `Result.failure()` (no retry).
- [x] Manual upload ("Upload collected data" button) and "Retry" always use `CONNECTED` (any network, including mobile data).
- [x] Auto-upload respects the "Upload on mobile data" toggle (UNMETERED vs CONNECTED constraint).
- [x] Retry policy: up to `MAX_ATTEMPTS = 5` with exponential backoff.

### ContributeScreen status
- [x] "Session in progress" section appears as soon as a job is enqueued.
- [x] Status updates in real time as the WorkManager job progresses.
- [x] When WorkManager reports SUCCEEDED, the item disappears immediately from the list.
- [x] Failed items show a "Retry" button that re-enqueues the upload job.
- [x] The section disappears when no active or failed jobs remain.
