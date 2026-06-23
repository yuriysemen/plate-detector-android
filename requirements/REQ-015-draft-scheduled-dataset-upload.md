---
id: REQ-015
title: Scheduled Automatic Dataset Upload (Daily)
status: draft
priority: medium
depends_on: REQ-014, REQ-018
---

## Summary

Allow collected datasets to be uploaded automatically once per day without requiring the user to manually trigger export or upload. This enables continuous data aggregation for model retraining pipelines without daily user interaction.

---

## Behaviour

When the user has:
1. Enabled cloud upload (export mode = Cloud or Both, per REQ-014), AND
2. Enabled the **"Auto-upload once a day"** setting (see below),

the app will:

1. **Auto-export** accumulated frames into a ZIP at most once every 24 hours (provided at least `min_frames_for_auto_export` new frames exist since the last auto-export).
2. **Enqueue the ZIP for upload** using the same WorkManager mechanism as manual export (REQ-014).
3. **Reset collected frames** after the ZIP is written (same as manual export reset).

If fewer than `min_frames_for_auto_export` frames have been collected since the last auto-export, the scheduled job skips silently and reschedules for the next day.

---

## Scheduling

- Implemented with `WorkManager` `PeriodicWorkRequest` with a 24-hour period.
- Constraint: `NetworkType.UNMETERED` (or `CONNECTED` if the mobile data toggle in REQ-014 is on).
- The periodic job fires approximately at the same time each day (WorkManager flex window: ±1 hour).
- Preferred trigger time: user-configurable (default: 02:00 local time). If the device is off or has no network at that time, WorkManager retries at the next opportunity.

---

## Minimum frames threshold

- `min_frames_for_auto_export` default: **50 frames**.
- Configurable in Settings → Export → "Minimum frames for auto-export" (range 10–500, step 10).
- Prevents uploading a trivially small ZIP on days where the user barely used the app.

---

## Notifications

- On successful auto-export + upload: a notification is shown:
  > **Dataset uploaded** — 73 frames sent to the shared training pool. Tap to view.
  - Tapping the notification opens the Export screen.
- On failure: no notification (upload will retry per REQ-014 retry policy).
- Notifications can be disabled in the standard Android notification settings for the app's "Dataset" notification channel.

---

## Settings surface

New entry under Settings → Export (only visible when export mode is Cloud or Both):

- **"Auto-upload once a day"** — toggle, default **off**.
- **"Minimum frames for auto-export"** — numeric picker, default 50 (only visible when auto-upload is on).
- **"Preferred upload time"** — time picker, default 02:00 (only visible when auto-upload is on).

---

## Interaction with manual export

- A manual export does NOT reset the 24-hour auto-export timer. Both can fire independently.
- If a manual export runs between two scheduled auto-exports, collected frames are reset (as normal); the next auto-export will collect frames accumulated since that manual export.

---

## Acceptance criteria

- [ ] "Auto-upload once a day" toggle is hidden unless export mode is Cloud or Both.
- [ ] When enabled, a `PeriodicWorkRequest` is registered with WorkManager with a 24-hour period.
- [ ] The periodic job fires approximately at the configured preferred time each day.
- [ ] If fewer than `min_frames_for_auto_export` frames exist, the job exits without exporting or uploading.
- [ ] If the threshold is met, the job creates a ZIP, enqueues an upload job (REQ-014), and resets collected frames.
- [ ] A notification is shown on successful upload; no notification on failure.
- [ ] The periodic job is cancelled when the user disables the toggle or switches export mode to Manual.
- [ ] On app update or device restart, the periodic job is re-registered (WorkManager handles this automatically via `BOOT_COMPLETED` processing).
- [ ] Manual export and auto-export can coexist without conflict.
