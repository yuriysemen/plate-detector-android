---
id: REQ-005
title: Settings UI and Preference Keys for Data Collection
status: draft
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
• Save camera frames where a license plate is detected.
• Periodically upload a packaged dataset to a private
  research server to improve plate detection.

Images are stored under a private device identifier.
Other contributors cannot access your images.
You can request deletion by contacting [contact address].
Uploads happen over Wi-Fi only (configurable).

[Cancel]   [Enable]
```

If the user taps Cancel, the switch stays off.

---

## ContributeScreen

Replaces the former ExportScreen. Accessed by tapping the "Contribute data" row in Settings.

### Stats card

Frames collected, total detections, collection date range. Read from `manifest.json` each time the screen opens.

### Storage limit

- **Limit** — numeric text field with MB / GB unit selector. Default: 500 MB. Range: 100 MB – 20 GB. Stored in `SharedPreferences` key `training_data_quota_mb`. Moved here from the main Settings screen — storage limit is a dependency of data collection, not a general app setting.

### Upload configuration

Always visible. Required for uploads to work.

- **Upload server URL** — text field (URL input type). Placeholder: `https://your-api.execute-api.eu-west-1.amazonaws.com/prod`. Stored in `SharedPreferences` key `upload_service_url`. Validated: must be a valid HTTPS URL.
- **Identity Pool ID** — text field. Placeholder: `eu-west-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Stored in `SharedPreferences` key `cognito_identity_pool_id`. Validated: must match `<region>:<uuid>` pattern.
- **Upload on mobile data** — toggle, default off.

A warning banner is shown below the config section if either URL or Identity Pool ID is missing: `"Upload not configured — data will be collected but not sent."`

### Actions

- **Edit dataset** — button (disabled when `total_frames == 0`). Navigates to the Dataset Editor (REQ-011).
- **Upload collected data** — button (enabled when `total_frames > 0` AND upload is configured). Packages frames into a ZIP and enqueues an upload job (see REQ-014). The dataset split is always applied with default ratios (70 / 20 / 10); this is not user-configurable.
- **Reset collected data** — button (enabled when `total_frames > 0`). Confirmation dialog: `"Delete N frames? This cannot be undone."` Deletes all files under `training_data/` and resets `manifest.json`.

### "Session in progress" section

Shown **only** when there is at least one upload job in a PENDING, UPLOADING, or FAILED state. Hidden completely when no jobs are active.

Each item shows:

| Status | Display |
|---|---|
| `PENDING` | Filename + `"Pending upload"` chip |
| `UPLOADING` | Filename + progress bar with percentage |
| `FAILED` | Filename + red `"Failed"` chip + `"Retry"` button |

On successful upload the ZIP is deleted from the device and the item is removed from this list. There is no persistent "previous uploads" history.

---

## Preference keys (`model_prefs` SharedPreferences)

| Key | Type | Default | Description |
|---|---|---|---|
| `collect_training_data` | Boolean | false | Master on/off switch |
| `collect_first_time_shown` | Boolean | false | Whether the first-time consent dialog has been shown |
| `upload_service_url` | String | `""` | API Gateway endpoint URL |
| `cognito_identity_pool_id` | String | `""` | Cognito Identity Pool ID (`region:uuid`) |
| `upload_on_mobile_data` | Boolean | false | Allow uploads over metered connections |

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

- [ ] Settings shows a single "Contribute data" row with a Switch and a summary line.
- [ ] Switch is off by default; turning it on for the first time shows the consent dialog.
- [ ] Cancelling the consent dialog leaves the switch off.
- [ ] Tapping the row navigates to ContributeScreen.
- [ ] ContributeScreen shows the stats card, upload config fields, action buttons, and (conditionally) the "Session in progress" section.
- [ ] Dataset split sliders do not appear anywhere in the app.
- [ ] "Upload collected data" button is disabled when `total_frames == 0` or upload is not configured.
- [ ] "Session in progress" section is hidden when no jobs are active; it appears as soon as a job is enqueued.
- [ ] Completed (UPLOADED) items disappear from the list and the ZIP is deleted from device storage.
- [ ] Failed items show a "Retry" button that re-enqueues the upload job.
- [ ] No share sheet is opened at any point in the upload flow.
- [ ] Share and Rename actions do not appear on any export item.
- [ ] Disabling collection mid-session does not delete already-saved frames.
- [ ] "Reset collected data" requires confirmation and deletes all frames under `training_data/`.
- [ ] Clearing data while collection is active resets correctly without leaving orphaned files.
