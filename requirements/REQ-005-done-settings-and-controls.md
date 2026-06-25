---
id: REQ-005
title: Settings UI and Preference Keys for Data Collection
status: done
priority: high
---

## Summary

Defines what the user can control regarding training data collection and cloud upload, where those controls appear in the app, and how preferences are persisted.

The previous design had two separate Settings entries — "Collect training data" (toggle) and "Export dataset" (button). These are combined into a single **"Contribute data"** entry that owns the full lifecycle: collection, editing, and upload.

---

## Settings in SettingsScreen

### "Contribute data" entry

- A single row with a **Switch** on the right and a summary line below the label.
- **Switch default:** off. When off, no frames are collected and no uploads occur.
- **Summary when off:** `"Disabled — no frames are collected"`
- **Summary when on:** `"On — collecting and uploading frames"`
- Tapping anywhere on the row (except the switch itself) navigates to **ContributeScreen**.
- When the switch is turned on for the first time, a one-time consent dialog is shown before saving the preference (see Consent dialog below). Subsequent toggles skip it.
- Disabling the switch mid-session does not delete already-saved frames.

### Consent dialog (first time only)

Shown when the switch is turned on for the first time. The user must accept before the preference is saved.

```
Contribute training data?

When enabled, the app will:
• Save camera frames to this device whenever a plate is detected.
• Upload a packaged dataset to a private research server to improve
  plate detection.

Images are stored under a private device identifier.
To delete: open Contribute data and tap Reset collected data.

[Cancel]   [Enable]
```

If the user taps Cancel, the switch stays off.

> **Note:** The original draft included a "contact address" for deletion requests. Self-service deletion via "Reset collected data" is the implemented mechanism. A formal contact address for deletion requests is deferred to the privacy policy update (REQ-007).

---

## ContributeScreen

Replaces the former ExportScreen. Accessed by tapping the "Contribute data" row in Settings.

### Stats card

Displays frames collected, total detections, and storage used. Read from `manifest.json` and the filesystem each time the screen opens and re-read automatically after any reset or upload action.

**Storage used** line format: `X.XXX / 500 MB` — current usage in MB (3 decimal places) against the configurable quota. A small **edit icon (✏)** on the right of the line opens a dialog to change the quota (MB only, minimum 100 MB, default 500 MB). Quota stored in `SharedPreferences` key `training_data_quota_mb`.

At the bottom of the card, side by side in a single row:
- **View dataset** button (left, disabled when `total_frames == 0`). Navigates to the Dataset Editor (REQ-011).
- **Reset collected data** button (right, disabled when `total_frames == 0`). Confirmation dialog: `"Delete N frames? This cannot be undone."` Deletes all files under `training_data/` and resets `manifest.json`.

### Upload configuration

Always visible. Required for uploads to work.

- **Upload server URL** — text field (URL input type). Placeholder: `https://your-api.execute-api.eu-west-1.amazonaws.com/prod`. Stored in `SharedPreferences` key `upload_service_url`.
- **Upload on mobile data** — toggle, default off.
- **Daily auto-upload time** — tappable row showing current scheduled time; tapping opens a 24-hour clock picker (Material3 `TimePicker`). Default 02:00. Stored in `SharedPreferences` key `auto_upload_time`. See REQ-015.
- **Last auto-upload** — read-only line: `"Last auto-upload: YYYY-MM-DD"` or `"Not yet auto-uploaded"`. Sourced from `SharedPreferences` key `auto_upload_last_date`.

A warning banner is shown if the URL field is empty: `"Upload not configured — data will be collected but not sent."`

### Actions

- **Upload collected data** — button below the upload configuration card (enabled when `total_frames > 0` AND `upload_service_url` is non-blank). Packages frames into a ZIP and enqueues an upload job (see REQ-014). The dataset split is always applied with default ratios (70 / 20 / 10); this is not user-configurable.

### "Session in progress" section

Shown **only** when there is at least one upload job in a PENDING, UPLOADING, or FAILED state. Hidden completely when no jobs are active.

Each item shows:

| Status | Display |
|---|---|
| `PENDING` | Filename + `"Pending upload"` label |
| `UPLOADING` | Filename + spinner + `"Uploading…"` |
| `FAILED` | Filename + `"Upload failed"` + `"Retry"` button |

When WorkManager reports `SUCCEEDED` for a job, the item is immediately removed from the list and the ZIP is deleted from device storage. There is no persistent "previous uploads" history.

---

## Preference keys

### `model_prefs` SharedPreferences

| Key | Type | Default | Description |
|---|---|---|---|
| `collect_training_data` | Boolean | `false` | Master on/off switch |
| `collect_first_time_shown` | Boolean | `false` | Whether the first-time consent dialog has been shown |
| `training_data_quota_mb` | Int | `500` | Storage quota in MB (min 100); edited via ✏ icon in stats card |

### `upload_prefs` SharedPreferences

| Key | Type | Default | Description |
|---|---|---|---|
| `upload_service_url` | String | `""` | API Gateway endpoint URL |
| `upload_on_mobile_data` | Boolean | `false` | Allow uploads over metered connections |
| `auto_upload_time` | String | `"02:00"` | Daily auto-upload time in HH:mm (24h); see REQ-015 |
| `auto_upload_last_date` | String? | `null` | ISO date of last successful auto-upload; see REQ-015 |
| `cognito_user_pool_id` | String | `""` | Cognito User Pool ID (e.g. `us-east-1_xxxxxxxx`); from stack output `UserPoolId` |
| `cognito_app_client_id` | String | `""` | Cognito App Client ID; from stack output `UserPoolClientId` |
| `cognito_identity_pool_id` | String | `""` | Cognito Identity Pool ID (e.g. `us-east-1:uuid`); from stack output `IdentityPoolId` |
| `cognito_user_id` | String | `""` | Cognito sub of the signed-in user; written after successful sign-in, cleared on sign-out |

> The Cognito preference keys and corresponding UI fields in ContributeScreen (User Pool ID, App Client ID, Identity Pool ID inputs; sign-in/sign-up flow) are pending the Android auth client implementation (REQ-014).

---

## Changes to LiveDetectionUi / CameraPreviewWithAnalysis

- The saver is called **only** when `collectEnabled && detections.isNotEmpty()`. Frames with zero detections are never saved (REQ-001 decision).
- The saver runs after detection + OCR, before posting results to the main thread, on the same background analysis thread — no extra threading needed.
- Saver failure (disk full, I/O error) is caught and logged; it never crashes the analyzer or blocks the frame pipeline.

---

## Removed from prior design

The following items are removed and their underlying logic must be deleted:

- **"Export dataset" button** in Settings — replaced by the "Contribute data" combined entry.
- **Dataset split sliders** (train / val / test percentages) — split always uses default values (70 / 20 / 10); no UI control.
- **Share sheet** — the app no longer opens a system share sheet after packaging. Cloud upload is the only export path.
- **"Previous exports" list** — replaced by "Session in progress" (conditional, active jobs only).
- **Share action** on export items.
- **Rename action** on export items.
- **Export mode radio group** (Manual / Upload / Both) from REQ-014 — there is no mode choice; upload is always the behavior when contribution is enabled.

---

## Acceptance criteria

- [x] Settings shows a single "Contribute data" row with a Switch and a summary line.
- [x] Switch is off by default; turning it on for the first time shows the consent dialog.
- [x] Cancelling the consent dialog leaves the switch off.
- [x] Tapping the row navigates to ContributeScreen.
- [x] ContributeScreen stats card shows frames collected, total detections, and `X.XXX / <quota> MB` storage line with ✏ edit icon.
- [x] All three stats (frames, detections, storage used) reset to zero together after a successful upload or reset action.
- [x] Tapping the ✏ icon opens a dialog pre-filled with the current quota; saving updates `training_data_quota_mb`.
- [x] "View dataset" and "Reset collected data" buttons are side by side at the bottom of the stats card; both disabled when `total_frames == 0`.
- [x] No separate "Storage limit" card appears anywhere.
- [x] Upload configuration card contains: URL field, mobile data toggle, daily auto-upload time row, last auto-upload line.
- [x] "Upload collected data" button is disabled when `total_frames == 0` or `upload_service_url` is blank.
- [x] "Session in progress" section is hidden when no jobs are active; it appears as soon as a job is enqueued.
- [x] Completed items disappear from the list as soon as WorkManager reports SUCCEEDED; the ZIP is deleted from device storage.
- [x] Failed items show a "Retry" button that re-enqueues the upload job.
- [x] Dataset split sliders do not appear anywhere in the app.
- [x] No share sheet is opened at any point in the upload flow.
- [x] Disabling collection mid-session does not delete already-saved frames.
- [x] "Reset collected data" requires confirmation and deletes all frames under `training_data/`.
- [x] Clearing data while collection is active resets correctly without leaving orphaned files.

## Pending (Android auth client — REQ-014)

- [ ] Upload configuration card includes three additional fields: User Pool ID, App Client ID, Identity Pool ID.
- [ ] ContributeScreen shows a sign-in / sign-up entry point when the user is not authenticated.
- [ ] After successful sign-in the `cognito_user_id` pref is written; it is cleared on sign-out.
- [ ] "Upload collected data" button is also disabled when no user is signed in.
- [ ] Privacy policy (REQ-007) updated with a formal contact address for data deletion requests.
