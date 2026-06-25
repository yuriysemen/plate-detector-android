---
id: REQ-014
title: Cloud Dataset Upload — Pre-signed URL Upload via WorkManager
status: done
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

Send the packaged dataset ZIP directly to an AWS S3 bucket using a pre-signed URL obtained from a Lambda-backed API Gateway endpoint. Upload is triggered manually from ContributeScreen (REQ-005) or automatically by `AutoUploadWorker` (REQ-015). The ZIP is deleted from the device after a successful upload.

The infrastructure (S3 bucket + Lambda + API Gateway) is defined and deployed separately — see **REQ-018**.

> **Authentication:** The API Gateway endpoint requires SigV4-signed requests. The Android app must sign each request using short-lived STS credentials obtained from a Cognito Identity Pool. Users authenticate via a Cognito User Pool (email + password + email verification). The AWS infrastructure for this is complete (REQ-018). Android-side implementation (sign-in/sign-up screens, SigV4 signing in `UploadDatasetWorker`) is the pending step.

---

## Upload architecture

```
Android app
    │
    │  POST /get-upload-url  { filename, device_id }
    ▼
API Gateway (no auth) → Lambda (generates pre-signed S3 PUT URL)
    │
    │  { upload_url, object_key, expires_in }
    ▼
Android app
    │
    │  PUT <upload_url>  (binary ZIP body, Content-Type: application/zip)
    ▼
S3 bucket  (private, server-side encrypted)
```

Key properties:
- **No static AWS credentials on device.** The app only stores the API Gateway URL.
- **Upload-only.** The pre-signed URL allows `s3:PutObject` on one specific object key.
- **Per-device isolation.** The Lambda writes to `uploads/<device_id>/<filename>` where `device_id` is the truncated hash from REQ-013.
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

### Step 1 — Request a pre-signed URL

```
POST <upload_service_url>/get-upload-url
Content-Type: application/json

{
  "filename": "plates_dataset_20260622_222232.zip",
  "device_id": "a3f8c1d4e9b2f7a0"
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
| All retries exhausted | `Result.failure()` — status shown as FAILED in ContributeScreen |

---

## WorkManager job design (`UploadDatasetWorker`)

- One `UploadDatasetWorker` per export ZIP, enqueued immediately after the ZIP is written.
- **Unique work name:** the ZIP file path — prevents duplicate uploads of the same file.
- **Network constraint:** `NetworkType.UNMETERED` by default; `NetworkType.CONNECTED` when "Upload on mobile data" is on.
- **Retry policy:** exponential backoff, maximum `MAX_ATTEMPTS = 5`.
- **Input data:** `KEY_ZIP_PATH`, `KEY_DEVICE_ID`, `KEY_UPLOAD_URL`, `KEY_FRAME_COUNT`, `KEY_IS_AUTO_UPLOAD`.
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

### Upload trigger
- [x] Tapping "Upload collected data" on ContributeScreen packages frames and enqueues `UploadDatasetWorker`.
- [x] The button is disabled when `total_frames == 0` or `upload_service_url` is blank.

### Upload flow
- [x] Worker POSTs to `<upload_service_url>/get-upload-url` with `filename` and `device_id`.
- [x] Worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [x] A 200 response from S3 deletes the local ZIP and removes the item from "Session in progress".
- [x] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [x] Upload respects the "Upload on mobile data" toggle (UNMETERED vs CONNECTED constraint).
- [x] Retry policy: up to `MAX_ATTEMPTS = 5` with exponential backoff.

### ContributeScreen status
- [x] "Session in progress" section appears as soon as a job is enqueued.
- [x] Status updates in real time as the WorkManager job progresses.
- [x] When WorkManager reports SUCCEEDED, the item disappears immediately from the list.
- [x] Failed items show a "Retry" button that re-enqueues the upload job.
- [x] The section disappears when no active or failed jobs remain.

### Known gaps (future work)
- [ ] API endpoint is currently unauthenticated — add SigV4 signing + Cognito Identity Pool (REQ-018).
