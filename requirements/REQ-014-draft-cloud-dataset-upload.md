---
id: REQ-014
title: Cloud Dataset Upload — Export Mode Selection and Pre-signed URL Upload
status: draft
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

Replace the automatic post-export share sheet with a user-controlled export mode setting. Add a "Upload to shared dataset" option that sends the ZIP package directly to an AWS S3 bucket using a pre-signed URL obtained from a Lambda function. No AWS credentials are stored on the device.

The infrastructure (S3 bucket + Lambda + API Gateway) is defined and deployed separately — see **REQ-018**.

---

## Upload architecture

```
Android app
    │
    │  POST /get-upload-url  { filename, device_id }
    ▼
API Gateway → Lambda (generates pre-signed S3 PUT URL)
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
- **No AWS credentials on the device.** The app only knows the Lambda endpoint URL.
- **Upload-only.** The pre-signed URL allows `s3:PutObject` on one specific object key; the device cannot list, read, or delete any objects.
- **Per-device isolation.** The Lambda writes each package to `uploads/<device_id>/<filename>` where `device_id` is the truncated hash from REQ-013. Devices cannot reach each other's prefixes.
- **Short-lived URL.** Pre-signed URLs expire in 1 hour (server-configurable). If the upload does not start within 1 hour of requesting the URL, the app must request a new one.

---

## Export mode setting

A new setting **"Export action"** replaces the automatic post-export share sheet. The user chooses one of three modes in Settings → Export:

| Mode | Label | Behaviour |
|---|---|---|
| A | Manual share | Share sheet opens after export (current behaviour). User sends wherever they want. |
| B | Upload to shared dataset | ZIP is uploaded via the Lambda endpoint. Share sheet does NOT open automatically. |
| C | Both | Upload via Lambda AND open share sheet (user can also send to personal Drive / email). |

- Default mode: **A (Manual share)** — preserves existing behaviour for users who have not opted in.
- When the user selects mode B or C for the first time, the consent dialog is shown before saving the setting.
- The setting is stored in `SharedPreferences` key `export_mode` (values: `MANUAL`, `CLOUD`, `BOTH`).
- Mode B and C are only functional when an upload URL is configured (see Settings surface below). If no URL is configured, mode B behaves like A and shows a warning banner.

---

## First-time consent dialog (modes B and C)

Shown once when the user first selects a cloud upload mode. Must be accepted before the setting is saved.

```
Upload dataset to shared model training pool?

Your collected frames (license plate images) will be uploaded to
a private research server. Images are used only to improve the
plate detection model.

• Your uploads are stored under a private device identifier.
• Other contributors cannot access your images.
• You can request deletion by contacting [contact address].
• Uploads happen over Wi-Fi only (configurable in Settings).

[Cancel]   [I Agree — Enable Upload]
```

If the user taps Cancel, the setting remains at the previous value (no change).

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
| Step 1 network error | Retry with exponential backoff (WorkManager handles retries). |
| Step 1 HTTP 4xx | Log error; mark upload as Failed; do not retry automatically (config issue). |
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
- On success: update the export metadata (Room or a JSON sidecar) to mark the ZIP as `UPLOADED` with a timestamp.
- On final failure (all retries exhausted): mark as `FAILED`. Upload status visible in the Export screen.

---

## Upload status in the Export screen

Each ZIP in the exports list shows an upload status badge (only when export mode is Cloud or Both):

| Status | Display |
|---|---|
| `NOT_QUEUED` | No badge (mode was Manual at export time) |
| `PENDING` | "Pending upload" chip |
| `UPLOADING` | Progress bar with percentage |
| `UPLOADED` | Green checkmark + "Uploaded" |
| `FAILED` | Red "Failed" chip + "Retry" button |

A manual **"Retry upload"** action is available on Failed items. Tapping it enqueues a new `UploadDatasetWorker` for that ZIP.

---

## Settings surface

New entries under Settings → Export:

- **Export action** — radio group or spinner: Manual / Upload to shared dataset / Both. Default: Manual.
- **Upload server URL** — text field (URL input type). Placeholder: `https://your-api.execute-api.eu-west-1.amazonaws.com/prod`. Visible when export action is Cloud or Both. Stored in `SharedPreferences` key `upload_service_url`. Validated: must be a valid HTTPS URL.
- **Upload on mobile data** — toggle, default off. Only visible when export action is Cloud or Both.

The URL field is intentionally exposed rather than hardcoded so the same APK can be used by different deployments (different S3 regions, different research groups) without a code change.

---

## Privacy and compliance

- No AWS credentials are stored on the device at any time.
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

### Export mode setting
- [ ] Settings → Export shows an "Export action" control with three options: Manual, Upload, Both.
- [ ] Default value is Manual; existing share sheet behaviour is unchanged.
- [ ] Selecting Cloud or Both for the first time shows the consent dialog.
- [ ] Cancelling the consent dialog leaves the setting unchanged.
- [ ] Accepting the consent dialog saves the new setting permanently.
- [ ] If Cloud or Both is selected but no upload URL is configured, a warning banner is shown and upload is skipped.

### Upload flow
- [ ] After a successful export, a `WorkManager` job is enqueued when mode is Cloud or Both.
- [ ] The worker POSTs to `<upload_service_url>/get-upload-url` with `filename` and `device_id`.
- [ ] The worker PUTs the ZIP binary to the received pre-signed URL with `Content-Type: application/zip`.
- [ ] A 200 response from S3 marks the upload as `UPLOADED`.
- [ ] If the pre-signed URL is expired (S3 returns 403), the worker re-requests a new URL and retries.
- [ ] Upload runs on Wi-Fi only by default; mobile data toggle overrides this.
- [ ] No AWS credentials (Access Key, Secret Key, session token) appear in any SharedPreference, log, or file on device.
- [ ] Retry policy: up to 5 attempts with exponential backoff.

### Export screen
- [ ] Each ZIP shows its upload status badge.
- [ ] Failed items show a "Retry" button that enqueues a new upload job.
- [ ] Uploaded items show a green checkmark.
- [ ] Status updates in real time as the WorkManager job progresses.

### Share sheet interaction
- [ ] Share sheet opens automatically after export when mode is Manual or Both.
- [ ] Share sheet does NOT open automatically when mode is Cloud.
