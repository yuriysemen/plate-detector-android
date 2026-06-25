---
id: REQ-014
title: Cloud Dataset Upload — Export Mode Selection and Pre-signed URL Upload
status: draft
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

Send the packaged dataset ZIP directly to an AWS S3 bucket using a pre-signed URL obtained from a Lambda function, authenticated via Cognito Identity Pool. Upload is triggered manually from ContributeScreen (REQ-005). No static AWS credentials are stored on the device. The ZIP is deleted from the device after a successful upload.

The infrastructure (S3 bucket + Lambda + API Gateway + Cognito) is defined and deployed separately — see **REQ-018**.

---

## Upload architecture

```
Android app
    │
    │  GetId + GetCredentialsForIdentity (HTTPS to Cognito)
    ▼
AWS Cognito Identity Pool  (guest/unauthenticated mode)
    │
    │  Temporary STS credentials (AccessKeyId, SecretKey, SessionToken) — valid ~1 hour
    ▼
Android app
    │
    │  POST /get-upload-url  { filename, device_id }  [SigV4-signed]
    ▼
API Gateway (IAM auth) → Lambda (generates pre-signed S3 PUT URL)
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
- **No static AWS credentials on the device.** Cognito provides temporary STS credentials that rotate every ~1 hour. These credentials only permit calling `POST /get-upload-url` — they cannot access S3 or any other AWS service directly.
- **Upload-only.** The pre-signed URL allows `s3:PutObject` on one specific object key; the device cannot list, read, or delete any objects.
- **Per-device isolation.** The Lambda writes each package to `uploads/<device_id>/<filename>` where `device_id` is the truncated hash from REQ-013. Devices cannot reach each other's prefixes.
- **Short-lived URL.** Pre-signed URLs expire in 1 hour (server-configurable). If the upload does not start within 1 hour of requesting the URL, the app must request a new one.
- **ZIP deleted after upload.** On successful upload the ZIP is removed from device storage. There is no on-device archive of past uploads.

---

## Upload trigger

Upload is initiated by tapping **"Upload collected data"** on ContributeScreen (REQ-005). There is no mode selection — cloud upload is the only export path. The first-time consent covering both collection and upload is handled by the "Contribute data" toggle consent dialog in REQ-005.

---

## Android upload flow (technical)

### Step 0 — Obtain temporary AWS credentials

The app uses `CognitoCachingCredentialsProvider` (AWS Android SDK) with the configured Identity Pool ID. On first use it calls Cognito to obtain temporary STS credentials; on subsequent calls it returns cached credentials until they expire (~1 hour), then fetches fresh ones transparently.

```kotlin
val credentialsProvider = CognitoCachingCredentialsProvider(
    context,
    identityPoolId,   // from Settings → Export → Identity Pool ID
    Regions.fromName(identityPoolId.substringBefore(":")),
)
// credentialsProvider.credentials blocks until credentials are ready;
// call off the main thread (WorkManager already does this).
```

Android Gradle dependencies required:
```
implementation("com.amazonaws:aws-android-sdk-cognitoidentity:2.x.x")
implementation("com.amazonaws:aws-android-sdk-core:2.x.x")
```

### Step 1 — Request a pre-signed URL

The request is signed with SigV4 using the credentials from Step 0. The AWS SDK provides `ApiGatewaySigner` / `AWS4Signer` for this; alternatively wrap OkHttp with an `AwsSigningInterceptor`.

```
POST <upload_service_url>/get-upload-url
Content-Type: application/json
Authorization: AWS4-HMAC-SHA256 Credential=<...>, SignedHeaders=<...>, Signature=<...>
X-Amz-Security-Token: <session_token>
X-Amz-Date: <timestamp>

{
  "filename": "plates_dataset_20260622_222232.zip",
  "device_id": "a3f8c1d4e9b2f7a0"
}
```

Response:

```json
{
  "upload_url": "https://bucket.s3.eu-west-1.amazonaws.com/uploads/a3f8c1d4.../plates_dataset_20260622_222232.zip?X-Amz-...",
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

A 200 response from S3 means the upload succeeded. No separate completion report is needed (S3 event notifications trigger any server-side pipeline).

### Error handling

| Failure point | Action |
|---|---|
| Step 0 Cognito error | Retry with exponential backoff; mark upload as Failed after max retries. |
| Step 1 network error | Retry with exponential backoff (WorkManager handles retries). |
| Step 1 HTTP 403 (invalid credentials) | Force-refresh Cognito credentials (`credentialsProvider.refresh()`), then retry Step 1 once. |
| Step 1 HTTP 4xx (other) | Log error; mark upload as Failed; do not retry automatically (config issue). |
| Step 2 URL expired (403) | Re-request a new URL (step 1) and retry the upload. Count as one retry attempt. |
| Step 2 network error | Retry with exponential backoff. |
| Step 2 partial upload | S3 rejects incomplete PUTs; retry from step 1. |

---

## WorkManager job design

- One `UploadDatasetWorker` per e xport ZIP, enqueued immediately after the ZIP is written.
- Network constraint: `NetworkType.UNMETERED` by default; `NetworkType.CONNECTED` when the "Upload on mobile data" toggle is on.
- Retry policy: exponential backoff, maximum 5 attempts over 24 hours.
- Input data: local ZIP file path, device ID, upload service URL.
- The worker is idempotent: if it runs twice (e.g. after a crash), it re-requests a fresh pre-signed URL and re-uploads. S3 PutObject is also idempotent (overwrites with identical content).
- On success: delete the local ZIP file. The item is removed from the "Session in progress" list in ContributeScreen. No persistent upload history is kept.
- On final failure (all retries exhausted): mark as `FAILED`. Upload status visible in ContributeScreen "Session in progress" section.

---

## Upload status

Upload status is shown in the **"Session in progress"** section of ContributeScreen (REQ-005). The statuses tracked by `UploadDatasetWorker` are:

| Status | Description |
|---|---|
| `PENDING` | Job enqueued, not yet started |
| `UPLOADING` | Upload in progress (progress % reported via WorkManager `setProgress`) |
| `FAILED` | All retries exhausted |

There is no `UPLOADED` persistent state — on success the ZIP is deleted and the record removed.

## Settings surface

Upload configuration fields are part of **ContributeScreen** (REQ-005), not a separate Settings section. See REQ-005 for field definitions, preference keys, and validation rules. Both the Upload server URL and Identity Pool ID are intentionally configurable (not hardcoded) so the same APK works with different deployments.

---

## Privacy and compliance

- No static AWS credentials are stored on the device. Cognito provides short-lived STS credentials (valid ~1 hour) that are cached in memory and on-device encrypted storage by `CognitoCachingCredentialsProvider`; they cannot be used to access S3 directly.
- The only external identifier stored is the local `device_id` (truncated SHA-256 hash, not raw ANDROID_ID — see REQ-013).
- Data Safety section update required before release:
  - **Data type shared:** Photos/videos.
  - **Shared with third parties:** Yes (AWS S3).
  - **Encrypted in transit:** Yes (HTTPS/TLS).
  - **Purpose:** App functionality (model improvement).
- Privacy policy update: describe cloud upload, retention period, and deletion request process.
- Preferred S3 region: `eu-west-1` (Ireland) for GDPR data residency. The region is determined by the bucket configured in REQ-018, not by the app.

---

## Acceptance criteria

### Upload trigger
- [ ] Tapping "Upload collected data" on ContributeScreen enqueues an `UploadDatasetWorker` job.
- [ ] If upload URL or Identity Pool ID is not configured, the button is disabled and a warning banner is shown.

### Upload flow
- [ ] After tapping "Upload collected data", a `WorkManager` job is enqueued.
- [ ] The worker obtains temporary STS credentials via `CognitoCachingCredentialsProvider` using the configured Identity Pool ID.
- [ ] The worker POSTs to `<upload_service_url>/get-upload-url` with `filename` and `device_id`, with the request SigV4-signed using the Cognito credentials.
- [ ] An unsigned request to `/get-upload-url` is rejected with HTTP 403.
- [ ] The worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [ ] A 200 response from S3 deletes the local ZIP and removes the item from "Session in progress".
- [ ] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [ ] If `/get-upload-url` returns 403 (expired Cognito credentials), the worker calls `credentialsProvider.refresh()` and retries once.
- [ ] Upload runs on Wi-Fi only by default; mobile data toggle overrides this.
- [ ] No static AWS Access Key or Secret Key appears in the APK, SharedPreferences, logs, or any file on device.
- [ ] Cognito credentials are cached; `GetCredentialsForIdentity` is not called on every upload attempt.
- [ ] Retry policy: up to 5 attempts with exponential backoff.

### ContributeScreen status
- [ ] "Session in progress" section appears as soon as a job is enqueued.
- [ ] Failed items show a "Retry" button that enqueues a new upload job.
- [ ] Status updates in real time as the WorkManager job progresses.
- [ ] The section disappears when no active or failed jobs remain.
