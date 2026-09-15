---
id: REQ-015
title: Scheduled Automatic Dataset Upload (Daily)
status: done
priority: medium
depends_on: REQ-005, REQ-014, REQ-018
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

When contribution is enabled and upload is configured, the app automatically uploads collected frames once per day at a user-configurable time (default 02:00 local time). There is no on/off toggle — auto-upload is always active when the enabling conditions are met. After a successful auto-upload the collected frames are reset so the next run contains only newly collected data.

---

## Enabling condition

Auto-upload is active when **all** of the following are true:

1. "Contribute data" switch is **on** (REQ-005).
2. Upload server URL is configured (`upload_service_url` is non-blank).

If either condition becomes false, the scheduled `PeriodicWorkRequest` is cancelled.

---

## Scheduled time

- Default: **02:00** local time.
- Configurable via the **"Daily auto-upload time"** tappable row in the Upload configuration card (ContributeScreen). Tapping opens a Material3 24-hour `TimePicker` dialog.
- Stored in `SharedPreferences` key `auto_upload_time` as an `"HH:mm"` string.
- When the user changes the time the existing `PeriodicWorkRequest` is cancelled and a new one is enqueued (`CANCEL_AND_REENQUEUE`) with the updated initial delay.

---

## Daily job behaviour

Implemented as `AutoUploadWorker` — a `CoroutineWorker` scheduled via `PeriodicWorkRequest` with a 24-hour period and a ±30-minute flex window.

Network constraint mirrors the manual upload setting:
- `NetworkType.UNMETERED` when "Upload on mobile data" is **off**.
- `NetworkType.CONNECTED` when "Upload on mobile data" is **on**.

When the job runs:

1. If upload URL is blank → exit with `Result.failure()`.
2. If `total_frames == 0` → exit with `Result.success()` (nothing to upload; reschedule as normal).
3. If `total_frames > 0`:
   - Read frame count **before** export (needed for the success notification).
   - Call `exportSync()` — packages frames into a ZIP and resets collected frames.
   - Enqueue `UploadDatasetWorker` (same mechanism as REQ-014) with `KEY_IS_AUTO_UPLOAD = true` and `KEY_FRAME_COUNT`.
   - Write today's date to `auto_upload_last_date`.
   - Return `Result.success()`.

If the network constraint is not satisfied at the scheduled time, WorkManager holds the job and runs it as soon as the constraint is met. If the next 24-hour period fires first, that run takes precedence.

---

## On-app-start catch-up check

A background coroutine (`Dispatchers.IO`) runs `AutoUploadWorker.runCatchUpIfNeeded(context)` once **the camera pipeline (`LiveDetectionUi`) has actually composed** — not at raw process start.

**Trigger condition** (all must be true):
- Upload URL is configured.
- `auto_upload_last_date` is not today (covers both "never uploaded" and "missed yesterday").
- `total_frames > 0`.
- Network satisfies the mobile-data constraint right now.

**Action:** enqueue a one-shot `AutoUploadWorker` with the network constraint. WorkManager runs it immediately if network is up, or waits if it drops after the check.

This covers the case where the device was off or offline at the scheduled time.

**Why gated on the camera, not process start (see "Startup memory robustness" below):** `LiveDetectionUi` — and the CameraX bind/detection pipeline it owns — is only composed once `LivePlateDetectionScreen` has moved past Settings/model-picker/Auth (`!(showSettings || selected == null || !isModelEnabled || showAuth)`, `LivePlateDetectionScreen.kt`). That dismissal is a user-timed, unbounded interaction. Firing the catch-up export+upload unconditionally at process start — as the previous implementation did — meant a heavy `exportSync()` + `UploadDatasetWorker` PUT could already be mid-flight, with no fixed relationship to when the user actually lands on the camera screen and its own allocation-heavy startup begins. Gating the trigger on the camera having *already* composed removes that race by construction: the two heavy allocators are ordered (camera first, catch-up upload after) instead of landing at an arbitrary, user-controlled offset from each other.

---

## Startup memory robustness

**Observed failure:** on a device with a 256 MB Dalvik heap growth limit and no `largeHeap`, a cold app launch produced a `java.lang.OutOfMemoryError` on the main thread (Compose draw dispatch) within ~2 seconds, immediately preceded by a `WM-WorkerWrapper` OOM inside `UploadDatasetWorker.putZip` uploading a month-old leftover ZIP that WorkManager auto-resumed at process start. Contributing factors, all capable of running concurrently at cold start on the previous implementation:

1. **WorkManager auto-resuming a previously-enqueued `UploadDatasetWorker` job** left over from an earlier session (e.g. the July run that never reached a terminal state) — this happens automatically as soon as the process starts and constraints are satisfied; the app does not control its timing.
2. **The on-app-start catch-up check** (`AutoUploadWorker.runCatchUpIfNeeded`) previously fired unconditionally in `LivePlateDetectionScreen`'s top-level `LaunchedEffect(Unit)`, i.e. at raw process start, regardless of whether the user was still looking at the Settings/model-picker dialog.
3. **The camera pipeline's own startup allocation burst** — `LiveDetectionUi` composing and CameraX binding, which is inherently allocation-heavy (YUV→NV21→JPEG→Bitmap conversion per frame).

Fixes:

- **`android:largeHeap="true"`** added to `<application>` in `AndroidManifest.xml` — raises the heap ceiling well above the default 256 MB on most devices, giving headroom for camera + background upload/export to coexist. Does not reduce allocation churn, only the ceiling.
- **Buffered zip output stream.** `DatasetExporter.exportSync()` previously wrapped a raw `FileOutputStream` directly in the `ZipOutputStream` with no intermediate buffering, forcing an OS write syscall per ~8 KB chunk copied from each frame/label file. Wrapped in `BufferedOutputStream` — reduces I/O/GC churn and shortens how long the export loop runs, shrinking the window during which it competes with other allocators.
- **Catch-up trigger moved off raw process start onto camera-active** (see "On-app-start catch-up check" above) — removes the ability for a same-launch auto-export/upload to land at an arbitrary point *before* the camera has even started, ordering the two heavy allocators instead of leaving their overlap to chance.
- The WorkManager-auto-resumed leftover job (factor 1) is not directly controllable from app code — `largeHeap` is the primary mitigation for that piece specifically, since gating app-triggered work has no effect on WorkManager's own persisted-job resumption.

---

## Post-upload reset

`exportSync()` (called by `AutoUploadWorker`) packages frames into a ZIP — via a `BufferedOutputStream`-wrapped `ZipOutputStream` (see "Startup memory robustness" above) — and resets collected frames atomically — the reset is not deferred until upload completion. The ZIP is deleted by `UploadDatasetWorker` on successful upload.

After `AutoUploadWorker.doWork()` completes:
- `auto_upload_last_date` is set to today (`"YYYY-MM-DD"`).
- The "Last auto-upload" line in ContributeScreen reflects the updated date on next screen open.

---

## Notifications

`UploadDatasetWorker` posts a notification on success when `KEY_IS_AUTO_UPLOAD == true`:

> **Dataset uploaded** — N frames sent. Tap to open Contribute screen.

- Tapping opens `MainActivity` with `EXTRA_OPEN_CONTRIBUTE = true`, which navigates directly to ContributeScreen.
- Posted to the `"Dataset"` `NotificationChannel` (created in `MainActivity.onCreate`).
- Requires `POST_NOTIFICATIONS` permission (Android 13+); silently skipped if not granted.
- No notification when the job skips due to no frames or when network is unavailable.
- Upload failures are handled by `UploadDatasetWorker` retry policy (REQ-014); no extra notification from the scheduler.

---

## Scheduling triggers

`AutoUploadWorker.schedule(context)` is called (idempotent via `CANCEL_AND_REENQUEUE`) whenever relevant settings change:

| Event | Action |
|---|---|
| App start (`LivePlateDetectionScreen` first composition) | `schedule()` |
| Upload URL changed | `schedule()` |
| "Upload on mobile data" toggled | `schedule()` (updates network constraint) |
| "Daily auto-upload time" changed | `schedule()` (recalculates initial delay) |
| "Contribute data" disabled | `cancel()` |

---

## ContributeScreen UI

The following controls appear in the **Upload configuration** card (visible when upload URL is set):

| Control | Type | Default | Pref key |
|---|---|---|---|
| **Daily auto-upload time** | Tappable row → `TimePicker` dialog | 02:00 | `auto_upload_time` |
| **Last auto-upload** | Read-only text | — | `auto_upload_last_date` |

There is no "Auto-upload daily" toggle. Auto-upload is always on when upload is configured.

---

## Persistence across reboots and app updates

`WorkManager` re-registers periodic jobs automatically after device reboot. The on-start catch-up check compensates for any run missed due to app updates or OS job cancellation.

---

## Acceptance criteria

### Scheduling
- [x] `PeriodicWorkRequest` is registered on every app start when conditions are met.
- [x] No toggle exists — auto-upload is always active when URL is configured and contribution is on.
- [x] Changing the scheduled time cancels the existing job and registers a new one with the correct initial delay.
- [x] The job is cancelled when "Contribute data" is disabled or upload URL is cleared.
- [x] Changing "Upload on mobile data" re-registers the job with the updated network constraint.

### Scheduled job
- [x] The job fires at approximately the configured time each day (within the ±30 min flex window).
- [x] If `total_frames == 0` the job exits without uploading or resetting.
- [x] If `total_frames > 0` the job creates a ZIP, resets frames, enqueues `UploadDatasetWorker`, and updates `auto_upload_last_date`.
- [x] Job uses `UNMETERED` constraint when "Upload on mobile data" is off; `CONNECTED` when on.
- [x] If no network at scheduled time, the job runs as soon as the network constraint is satisfied.

### On-start catch-up
- [x] A background catch-up check runs once per app session.
- [x] The check is deferred until `LiveDetectionUi` (camera pipeline) has actually composed, not fired unconditionally at raw process start — avoids racing the camera's own startup allocation burst (see "Startup memory robustness").
- [x] The check triggers an immediate upload if URL is configured, `auto_upload_last_date != today`, `total_frames > 0`, and network constraint is satisfied.
- [x] The catch-up check does nothing if `total_frames == 0`, network is unavailable, or already uploaded today.

### Startup memory robustness
- [x] `android:largeHeap="true"` set in `AndroidManifest.xml`.
- [x] `DatasetExporter.exportSync()` wraps its `FileOutputStream` in a `BufferedOutputStream` before handing it to `ZipOutputStream`.
- [x] Catch-up trigger deferred until camera composed — see "On-start catch-up" above (same change, listed once).

### UI
- [x] "Daily auto-upload time" row is visible in the Upload configuration card when URL is configured.
- [x] Tapping the time row opens a 24-hour TimePicker dialog; confirming saves and reschedules.
- [x] "Last auto-upload: YYYY-MM-DD" (or "Not yet auto-uploaded") is shown below the time row.

### Notifications
- [x] A notification is shown on successful auto-upload with frame count; tapping opens ContributeScreen.
- [x] No notification when the job skips due to no frames or network unavailability.
