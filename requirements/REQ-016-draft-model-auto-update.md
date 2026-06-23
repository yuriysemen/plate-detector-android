---
id: REQ-016
title: Model Update System
status: draft
priority: high
---

## Summary

The detection model is currently bundled as an app asset and never changes without an app update. Add a mechanism to download and switch to newer model versions from a remote source, with the frequency and auto-apply behaviour configurable by the user.

---

## Goals

- Allow the model to be updated without shipping a new APK.
- Give the user full control: they can disable auto-update, require confirmation before switching, or let it apply automatically.
- Verify integrity before applying any downloaded model.
- Keep one previous model version as a rollback.

---

## Update source

- **Primary:** GitHub Releases on this repository. Each release that ships an updated model attaches the `.tflite` file as a release asset alongside a `model_manifest.json` describing the version, hash, and release notes.
- **Fallback / future:** a configurable base URL in `BuildConfig` so the download source can be changed without an APK update (via a thin redirect service or static hosting).

### model_manifest.json (hosted, checked by the app)

```json
{
  "version": "v2.1.0",
  "release_date": "2026-07-01",
  "model_filename": "plate_detector_v2.1.0.tflite",
  "download_url": "https://github.com/…/releases/download/v2.1.0/plate_detector_v2.1.0.tflite",
  "sha256": "a3f8c1d…",
  "size_bytes": 6291456,
  "min_app_version": "1.3.0",
  "release_notes": "Improved small plate detection at distance."
}
```

---

## Update check flow

1. App fetches `model_manifest.json` from the configured URL.
2. Compares `version` field against the currently active model version (stored in `SharedPreferences` key `active_model_version`).
3. Checks `min_app_version`: if the current app version is older than required, the update is skipped silently (the model is not compatible).
4. If a newer compatible version is found:
   - **Manual mode:** a badge or update indicator appears in Settings → Model; no automatic action.
   - **Auto-download mode:** the model file is downloaded in the background via `WorkManager` (Wi-Fi only unless the user has enabled mobile data downloads).
5. After download, SHA-256 of the downloaded file is verified against the manifest. If it does not match, the file is deleted and the check is retried at the next scheduled interval.
6. On verification success:
   - **Confirm-before-switch mode:** a notification is shown: "New plate detection model ready — tap to apply." The new model is not loaded until the user taps.
   - **Auto-apply mode:** the model is swapped on next app foreground event (not mid-session). A notification informs the user: "Model updated to v2.1.0."

---

## Update check schedule

Configurable in Settings → Model → "Check for model updates":

| Option | Behaviour |
|---|---|
| Off | Never checks. Manual check only via a "Check now" button. |
| On app launch | Checks once per launch if the last check was more than N hours ago (N = 12 by default). |
| Daily (default) | `WorkManager` `PeriodicWorkRequest`, 24-hour period, fires on Wi-Fi. |
| Weekly | Same, 7-day period. |

The "Check now" button is always available regardless of schedule and triggers an immediate fetch.

---

## Model storage on device

```
<filesDir>/models/
  active/
    plate_detector_v2.1.0.tflite   ← symlink or copied file; loaded by the detector
  previous/
    plate_detector_v2.0.0.tflite   ← one version back; retained for rollback
  pending/
    plate_detector_v2.2.0.tflite   ← downloaded but not yet applied
```

- Only one pending model is kept at a time; a new download overwrites the pending slot.
- Only one previous model is kept; the old previous is deleted when a new active is promoted.
- The bundled app asset (`assets/plate_numbers.tflite`) is the fallback: if `<filesDir>/models/active/` is empty (first install, or after factory reset), the detector uses the bundled asset.

---

## Rollback

- Settings → Model shows:
  - Current active model version and date.
  - Previous model version (if available) with a **"Roll back to vX.Y.Z"** button.
- Rolling back: the previous model is moved to active, the current active is deleted (or moved to pending slot if the user may want to re-apply it).
- No automatic rollback on detection quality degradation — manual only.

---

## Settings surface

Settings → Model (new section):

- **Active model** — read-only label: `v2.1.0 (updated 2026-07-01)` or `Bundled (v1.0.0)`.
- **Check for updates** — picker: Off / On launch / Daily / Weekly (default: Daily).
- **Auto-apply updates** — toggle: on = apply without prompt, off = notify and wait for user tap (default: off — notify only).
- **Download on mobile data** — toggle, default off.
- **Check now** — button, triggers immediate manifest fetch.
- **Roll back to vX.Y.Z** — button, only visible when a previous model is stored.
- **Release notes** — expandable text showing the manifest `release_notes` of the active model.

---

## Acceptance criteria

### Update check
- [ ] App fetches `model_manifest.json` from the configured URL according to the selected schedule.
- [ ] If the manifest version matches the active version, no download is triggered.
- [ ] If `min_app_version` is not met, the update is skipped and no indication is shown to the user (silent skip).
- [ ] "Check now" button triggers an immediate fetch regardless of schedule.

### Download and verification
- [ ] Model download respects the Wi-Fi / mobile data setting.
- [ ] Downloaded file SHA-256 is verified before the file is moved out of the `pending/` directory.
- [ ] If SHA-256 verification fails, the file is deleted and the failure is logged (no user-visible error for automatic checks; error shown for manual "Check now").
- [ ] Only one pending model file is kept; a new download replaces the previous pending.

### Apply
- [ ] In notify-only mode: a notification appears when a verified model is pending; the model is NOT loaded until the user acts on the notification.
- [ ] In auto-apply mode: the model is applied on the next app foreground event after verification, not mid-session.
- [ ] After applying, `active_model_version` in `SharedPreferences` is updated.
- [ ] The previously active model is moved to `previous/`; the old previous is deleted.
- [ ] The detector uses the bundled asset if `models/active/` is empty.

### Rollback
- [ ] Roll back button is visible only when a previous model file exists.
- [ ] Tapping Roll back loads the previous model immediately (app restart or next foreground event).
- [ ] After rollback, `active_model_version` is updated to the rolled-back version.

### Settings
- [ ] All settings listed above are present and functional.
- [ ] Settings persist across app restarts.
