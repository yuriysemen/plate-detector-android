---
id: REQ-007
title: Google Play Compliance and Privacy Policy Changes
status: draft
priority: high
---

## Summary

Data collection and cloud upload change the app's obligations under Google Play policies, GDPR, and the existing privacy policy. This requirement defines every change needed before the updated app can be published. It covers **two modes**: local-only collection (REQ-002–REQ-006) and optional cloud upload (REQ-014, REQ-015).

## What changes from the current baseline

The current app (per `privacy-policy.md`):
- Does not store camera frames or photos.
- Does not transmit data off-device.
- Collects no personal data.

The updated app, depending on what the user enables:
- **Saves JPEG frames** (camera images) containing license plates to device storage.
- **Optionally uploads those frames** to an AWS S3 bucket via a pre-signed URL (REQ-014).
- License plates are **personally identifiable information (PII)** in most jurisdictions — they can be linked to vehicle owners.

This requires action in three places: the Play Store listing, the privacy policy, and the app itself.

---

## 1. Google Play — Data Safety section

Google Play requires the Data Safety section to be updated whenever data collection behaviour changes. Incorrect declarations can result in app removal.

### Required declarations when collection is OFF (default state)

No change from current. The feature is opt-in and inactive by default; no data is collected.

However: Google's guidance states you must disclose data that *can* be collected even if it requires user opt-in. Interpret conservatively and declare both the optional local collection and the optional cloud upload.

### Required declarations — local collection only (Export action = Manual)

| Field | Value |
|---|---|
| Data type | Photos and videos |
| Collected | Yes (when user enables the feature) |
| Shared with third parties | No |
| Used for app functionality | Yes |
| Encrypted in transit | N/A (no transit) |
| User can request deletion | Yes (via "Clear collected data" in Settings) |
| Required or optional | Optional (user opt-in) |

### Required declarations — cloud upload enabled (Export action = Cloud or Both, REQ-014)

| Field | Value |
|---|---|
| Data type | Photos and videos |
| Collected | Yes |
| Shared with third parties | **Yes** — uploaded to AWS S3 (operator's private bucket) |
| Purpose of sharing | App functionality (model improvement) |
| Encrypted in transit | Yes (HTTPS/TLS) |
| User can request deletion | Yes (contact address in privacy policy) |
| Required or optional | Optional (separate opt-in, consent dialog in REQ-014) |

**How to fill in Data Safety:**
- Under "Data types" → "Photos and videos" → "Photos" → select "Collected".
- Mark as "Optional — users can choose whether this data is collected."
- Purpose: "App functionality."
- For cloud upload: also declare "Shared with third parties" = Yes, purpose "App functionality."

Failure to update Data Safety after publishing the feature can result in a policy strike.

---

## 2. Privacy Policy — required changes

The existing `privacy-policy.md` must be updated. Key additions:

### Add a new section: "Training data collection (optional feature)"

```
## Training data collection (optional)

The app includes an optional feature, disabled by default, that saves 
camera frames and bounding-box annotations to your device for the purpose
of improving the detection model.

**What is saved:** JPEG images from the live camera view, paired with 
bounding-box coordinate files in YOLO format. Images may contain license 
plates visible in the camera view.

**Where it is stored:** On your device, in the app's private storage 
directory. If you additionally enable "Upload to shared dataset" (a 
separate opt-in), images are transmitted over HTTPS to a private AWS S3 
bucket operated by the developer and used solely for model training.

**Who can access it:** By default, only you, via the app's "Export 
dataset" function. If cloud upload is enabled, the developer's research 
team has access to the uploaded packages.

**How to delete it:** Use "Clear collected data" in Settings, or uninstall 
the app. For cloud-uploaded data, contact [contact address] to request 
deletion; packages are identified by a device-specific anonymised 
identifier.

**Legal basis (GDPR):** Local collection is based on your explicit consent 
(opt-in toggle). Cloud upload is based on a separate explicit consent 
(second opt-in dialog). You may withdraw consent at any time by disabling 
the feature and clearing collected data.
```

Update the Summary bullet at the top:
```
- When the optional training data collection feature is enabled, camera 
  frames are saved locally on your device. If you additionally enable 
  cloud upload, frames are transmitted to a private research server.
```

Update `privacy-policy.md` in the repo and ensure the Play Store listing 
links to the same document.

---

## 3. In-app disclosures (required by Play policy)

Google Play policy requires "prominent disclosure" before collecting sensitive data, even locally. This means:

- The opt-in toggle in Settings must be accompanied by a clear explanation of what is collected (already in REQ-005 sub-label text).
- A **first-time dialog** must appear the first time the user enables the toggle, before any data is written. The dialog must:
  - State what is saved (frames containing license plates).
  - State it stays on-device.
  - Link to or summarize the privacy policy.
  - Have explicit "Enable" and "Cancel" buttons.

This satisfies both Google Play's "prominent disclosure before collection" requirement and GDPR's informed consent requirement.

---

## 4. Permissions audit

| Permission | Current status | Change needed |
|---|---|---|
| `CAMERA` | Already declared | None |
| `INTERNET` | Likely already declared (model download) | None |
| `WRITE_EXTERNAL_STORAGE` | Not declared | Not needed — using `filesDir` (internal) |
| `READ_EXTERNAL_STORAGE` | Not declared | Not needed |
| `READ_MEDIA_IMAGES` | Not declared | Not needed |

Using `context.filesDir` (app-private internal storage) requires **no additional permissions** on any Android API level. This is the correct storage choice.

---

## 5. Auto Backup exclusion — **DONE (2026-09-08)**

Android Auto Backup was silently copying `training_data/` (license-plate images) + `exports/*.zip`
+ the Cognito token SharedPreferences to the user's Google Drive.

**Implemented:** `android:allowBackup="false"` in `AndroidManifest.xml` — disables cloud Auto
Backup *and* `adb backup` entirely. `res/xml/backup_rules.xml` deleted (moot). `fullBackupContent`
attribute removed.

`res/xml/data_extraction_rules.xml` kept as belt-and-suspenders for Android 12+ device-to-device
transfer (a separate mechanism `allowBackup="false"` doesn't fully gate) — both `<cloud-backup>`
and `<device-transfer>` `<exclude>` `training_data`, `exports`, `models`, `upload_log.json`, and
`com.amazonaws.android.auth` / `CognitoIdentityProviderCache` / `AWS.Cognito.ContextData`.

Net: nothing this app stores leaves the device via any OS backup/transfer path. The trade-off —
a new phone starts fresh (re-sign-in, re-download model, settings default) — is acceptable for
this app (transient data, cheap re-fetch).

---

## 6. GDPR / CCPA compliance checklist

| Requirement | How it is met |
|---|---|
| Lawful basis | Explicit opt-in consent before local collection; separate explicit consent before cloud upload (REQ-014 consent dialog) |
| Right to access | User can export via share sheet (local); cloud copies identifiable by `device_id` hash (REQ-013) |
| Right to erasure | "Clear collected data" deletes local files; cloud deletion on request using `device_id` tag on S3 objects (REQ-018) |
| Data minimisation | Only frames with at least one detection above threshold are saved; background-only frames are never saved |
| Storage limitation | Configurable quota (REQ-011) + collection pause when full |
| Transparency | First-time consent dialog (local collection) + separate consent dialog (cloud upload, REQ-014) + privacy policy section |
| Cross-border transfer | Local-only: none. Cloud upload: AWS S3 in configured region (default `eu-west-1`, Ireland — within EU, no Schrems II issue). |
| Data processor agreement | AWS is a data processor; operator must accept AWS's standard DPA in their AWS account settings before cloud upload goes live. |

---

## Acceptance criteria

### Local collection
- [ ] Data Safety section updated in Play Console before the new version is submitted, declaring both local collection and optional cloud sharing.
- [ ] `privacy-policy.md` updated and re-deployed to the URL referenced in Play Store listing.
- [ ] First-time consent dialog appears before any local frame is written.
- [x] Auto Backup disabled — `android:allowBackup="false"`; `data_extraction_rules.xml` also excludes the sensitive dirs from D2D transfer (2026-09-08). Verify on device with `adb shell bmgr backupnow <pkg>` → expects "Package ... not eligible for backup".
- [ ] Disabling the collection toggle stops all writes immediately.
- [ ] "Clear collected data" deletes all files under `training_data/` and cannot be undone.

### Cloud upload (REQ-014)
- [ ] Cloud upload consent dialog appears the first time the user selects Cloud or Both export mode; no upload occurs without this consent.
- [ ] Data Safety section declares "Shared with third parties: Yes" when cloud upload is available as an option.
- [ ] Privacy policy section accurately describes both local and cloud storage modes.
- [ ] AWS DPA accepted by operator in AWS account before the production deployment of REQ-018 goes live.
- [ ] S3 bucket is in `eu-west-1` (or an explicitly chosen GDPR-compliant region) for the production deployment.
