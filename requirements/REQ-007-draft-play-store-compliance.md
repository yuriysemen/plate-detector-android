---
id: REQ-007
title: Google Play Compliance and Privacy Policy Changes
status: draft
priority: high
---

## Summary

Data collection — even local, on-device collection — changes the app's obligations under Google Play policies, GDPR, and the existing privacy policy. This requirement defines every change needed before the updated app can be published.

## What changes from the current baseline

The current app (per `privacy-policy.md`):
- Does not store camera frames or photos.
- Does not transmit data off-device.
- Collects no personal data.

The new feature, when enabled:
- **Saves JPEG frames** (camera images) containing license plates to device storage.
- License plates are **personally identifiable information (PII)** in most jurisdictions — they can be linked to vehicle owners.

This is a material change and requires action in three places: the Play Store listing, the privacy policy, and the app itself.

---

## 1. Google Play — Data Safety section

Google Play requires the Data Safety section to be updated whenever data collection behaviour changes. Incorrect declarations can result in app removal.

### Required declarations when collection is OFF (default state)

No change from current. The feature is opt-in and inactive by default; no data is collected.

However: Google's guidance states you must disclose data that *can* be collected even if it requires user opt-in. Interpret conservatively and declare the optional collection.

### Required declarations when collection is ON (user-enabled)

| Field | Value |
|---|---|
| Data type | Photos and videos |
| Collected | Yes (when user enables the feature) |
| Shared with third parties | No (local only; see REQ-008 for cloud variant) |
| Used for app functionality | Yes |
| Encrypted in transit | N/A (no transit) |
| User can request deletion | Yes (via "Clear collected data" in Settings) |
| Required or optional | Optional (user opt-in) |

**How to fill in Data Safety:**
- Under "Data types" → "Photos and videos" → "Photos" → select "Collected".
- Mark as "Optional — users can choose whether this data is collected."
- Purpose: "App functionality."
- Sharing: "Data is not shared with third parties."

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

**Where it is stored:** Exclusively on your device, in the app's private 
storage directory. Files are not transmitted to any server.

**Who can access it:** Only you, via the app's "Export dataset" function. 
The data is not accessible to other apps.

**How to delete it:** Use "Clear collected data" in Settings, or uninstall 
the app (uninstalling deletes all app data automatically).

**Legal basis (GDPR):** Processing is based on your explicit consent 
(opt-in toggle). You may withdraw consent at any time by disabling the 
feature and clearing collected data.
```

Also update the Summary bullet at the top:
```
- When the optional training data collection feature is enabled, camera 
  frames are saved locally on your device. This data never leaves your device.
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

## 5. Auto Backup exclusion

Android Auto Backup may attempt to back up `training_data/` to Google Drive, which would effectively upload license plate images without explicit user consent for cloud storage.

**Action required:** Add backup exclusion rules.

`res/xml/backup_rules.xml`:
```xml
<full-backup-content>
    <exclude domain="file" path="training_data"/>
</full-backup-content>
```

For Android 12+ (API 31), also add to `AndroidManifest.xml`:
```xml
<application
    android:dataExtractionRules="@xml/data_extraction_rules"
    ...>
```

`res/xml/data_extraction_rules.xml`:
```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="training_data"/>
    </cloud-backup>
    <device-transfer>
        <!-- Allow device-to-device transfer if desired; plates stay local -->
        <include domain="file" path="training_data"/>
    </device-transfer>
</data-extraction-rules>
```

Without this exclusion, Auto Backup can upload files silently — a privacy and policy violation.

---

## 6. GDPR / CCPA compliance checklist

| Requirement | How it is met |
|---|---|
| Lawful basis | Explicit opt-in consent before any collection |
| Right to access | User can export via share sheet |
| Right to erasure | "Clear collected data" button deletes everything |
| Data minimisation | Only frames with at least one detection above the save threshold are saved. Background-only frames are never saved (REQ-001 decision). No rejection flags or hard-negative labels are stored — annotation decisions are deferred to a separate editing app. |
| Storage limitation | 500 MB quota + LRU eviction |
| Transparency | In-app dialog + privacy policy section |
| No cross-border transfer | Data stays on device (local-only scope) |

---

## Acceptance criteria

- [ ] Data Safety section updated in Play Console before the new version is submitted.
- [ ] `privacy-policy.md` updated and re-deployed to the URL referenced in Play Store listing.
- [ ] First-time consent dialog appears before any file is written.
- [ ] Auto Backup exclusion rules are present in the build and verified via `adb shell bmgr run`.
- [ ] Disabling the collection toggle stops all writes immediately.
- [ ] "Clear collected data" deletes all files under `training_data/` and cannot be undone.
