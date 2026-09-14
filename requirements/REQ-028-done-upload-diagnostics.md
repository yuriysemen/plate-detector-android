---
id: REQ-028
title: Upload Diagnostics — Failure Reasons and a Persistent Activity Log
status: done
priority: medium
depends_on: REQ-014, REQ-027
---

> **Implemented in `android/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `training-android/`, not `android/`.**

## Summary

"Upload failed" was a dead end: `UploadDatasetWorker` caught the exception, wrote a bare `FAILED`
to the sidecar (`status` + `updated_at` only — no message), and `ContributeScreen` hard-coded the
string `"Upload failed"`. Nothing recorded *why*.

This adds:

1. **A recorded failure reason** per upload (HTTP code / exception / attempt count), stored in the
   sidecar and shown on the Upload-history card.
2. **`UploadLog`** — a persistent, capped activity log for every upload lifecycle event, with a
   one-line status + **Details** dialog on ContributeScreen (mirrors the model-update activity
   line in Settings).

---

## Motivation

Direct follow-up to REQ-027: a user seeing `get-model-url HTTP 403` was also getting "Upload
failed" with no explanation. `get-upload-url` shares the same auth, so the cause was the same
(expired session), but the UI gave no way to know that.

---

## `UploadLog` (new)

`UploadLog.kt` — the persistent counterpart to the in-memory `ModelUpdateLog`. Backed by
`filesDir/upload_log.json` because `UploadDatasetWorker` / `AutoUploadWorker` run headless (often
after the Activity is gone) — an in-memory-only log would be empty by the time the user opens
ContributeScreen to see what happened.

- `enum Level { INFO, SUCCESS, ERROR }`, `data class Entry(timeMs, message, level)`.
- In-memory `StateFlow<List<Entry>>` seeded from disk on first `ensureLoaded()` / `log()`, kept in
  sync on every `log()` — a worker in the live app process updates the open UI immediately.
- Capped at **100 entries** (oldest trimmed). `@Synchronized` methods; all disk I/O wrapped in
  `runCatching`.
- `log(context, message, level)`, `ensureLoaded(context)`, `clear(context)`, `latest`.

### Logged events

| Source | Event | Level |
|---|---|---|
| `UploadDatasetWorker` | `Upload started: <zip> — <n> frames, <size>` (first attempt only) | INFO |
| | `Attempt <k> failed: <reason> — retrying` | INFO |
| | `Upload failed after <k> attempts: <reason>` | ERROR |
| | `Upload failed: <reason>` (terminal, no retry — auth) | ERROR |
| | `Upload complete: <zip> — <n> frames sent` | SUCCESS |
| `AutoUploadWorker` | `Daily auto-upload queued — <n> frames` | INFO |
| | `Daily auto-upload skipped — not signed in or upload not configured` | INFO |
| | `Daily auto-upload: packaging failed (<msg>) — will retry` | INFO |
| `ContributeScreen` | `Upload queued — <n> frames` / `Upload restarted: <zip>` / `Packaging failed: <msg>` | INFO / ERROR |

---

## Failure reason in the sidecar

- `DatasetExporter.writeUploadStatus(zipFile, status, detail: String? = null)` — `detail` is
  written to the sidecar as `"detail"`. When `detail` is null the **previous** detail is preserved
  (so an UPLOADING → PENDING transition doesn't wipe the reason a prior attempt failed).
- `readSidecarInfo` now returns `SidecarInfo(status, updatedAt, detail)`; `ExportFile` gains
  `statusDetail: String?`.
- `UploadDatasetWorker` writes a human reason on every failure:

| Cause | `detail` written |
|---|---|
| `get-upload-url` / credentials 401 / 403 / session-expired | `not authorized (HTTP 401/403) — your sign-in has expired. Open Contribute and tap "Sign in again".` |
| S3 PUT still 403 after link refresh | `S3 rejected the upload (HTTP 403) even after refreshing the link` |
| network / 5xx / other, mid-retries | `Attempt <k> failed: <msg> — retrying` |
| network / 5xx / other, exhausted | `Failed after <k> attempts: <msg>` |

---

## ContributeScreen UI

### Upload-history card

Below the status row, when the entry is `FAILED` or stuck-restartable and has a `statusDetail`,
the reason is shown as a `bodySmall` line. So a failed card now reads e.g.:

> **plates_dataset_20260908_141212**
> 3.4 MB · 2026-09-08 14:12
> Upload failed
> *not authorized (HTTP 401/403) — your sign-in has expired. Open Contribute and tap "Sign in again".*

### Upload activity line + Details dialog

Between the upload button and the history list, shown whenever `UploadLog` is non-empty (i.e.
even after the per-upload rows are gone — they're deleted on success):

- One line: the latest `UploadLog` entry, colour-coded by level, with a **Details** text button.
- **Details** opens an `AlertDialog` — scrollable list, newest first, `MMM d, HH:mm:ss` prefix,
  colour-coded. A **Clear** button wipes the log.

---

## Acceptance criteria

- [x] `UploadLog` persists to `filesDir/upload_log.json`, survives process death, caps at 100
      entries, and exposes a `StateFlow` seeded from disk.
- [x] `UploadDatasetWorker` logs start / each retry / terminal failure / success with a reason.
- [x] `AutoUploadWorker` logs queued / skipped / packaging-failed.
- [x] `ContributeScreen` manual upload + restart + packaging failure are logged.
- [x] The sidecar stores a `detail` string; `writeUploadStatus(status, detail=null)` preserves the
      prior detail.
- [x] `ExportFile.statusDetail` is surfaced on the history card for FAILED / stuck entries.
- [x] Auth failures (401/403/session-expired) produce the "sign in again" reason — consistent with
      REQ-027's `markSessionExpired()` on the same paths.
- [x] "Upload activity" line + **Details** dialog (+ Clear) appear on ContributeScreen when the log
      is non-empty.
- [x] Built + unit-tested: `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`
      pass (JBR via Android Studio).

> **Verification gap:** no on-device run. `UploadLog` file persistence is not unit-tested (needs a
> `Context`; the repo's test suite is pure-JVM with no Robolectric) — it's build-verified only.

---

## Notes

- Successful uploads still delete their ZIP + sidecar immediately (REQ-014) — no per-upload row
  persists. `UploadLog` is now the durable record of what was sent and when.
- `UploadLog` is deliberately separate from `ModelUpdateLog` (in-memory, session-only): uploads
  need to survive the worker outliving the UI; model checks are only ever triggered while the app
  is composed.
