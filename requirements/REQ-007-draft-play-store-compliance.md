---
id: REQ-007
title: Google Play Compliance and Privacy Policy Changes
status: draft
priority: high
---

> **Scope note (REQ-031):** all collection/upload/account code has been removed from `android/`
> entirely rather than fixing its Data Safety declarations — see
> [REQ-031](REQ-031-done-split-detection-and-training-apps.md), now done. This document's Play
> Store requirements **no longer apply to `android/`** — it collects nothing to declare, and
> `privacy-policy.md` is back to the "no data collected" baseline and factually true. The
> underlying privacy/legal considerations described below (account deletion, data minimization,
> GDPR posture) still apply to how `training-android/` is operated day to day — just not as Play
> Store obligations, since it isn't published there. The rest of this document is kept as a
> historical record of the analysis; it does not describe `android/`'s current behavior.

## Summary

The app's live Play Store listing (`privacy-policy.md`, effective 2026-01-29) still describes the
**original, pre-collection baseline**: no upload, no personal data collected. The app as it exists
in `android/` today no longer matches that description — it has opt-in local frame collection
(REQ-002–REQ-006), opt-in cloud upload to S3 (REQ-014, REQ-015, REQ-018), **email/password account
creation via Cognito** (REQ-018), and always-on on-device OCR (reads plate text, shown in the
label — not uploaded separately, but the images it's derived from are). This requirement defines
every change needed to bring the Play Store listing, in-app disclosures, and the privacy policy
back in line with what the app actually does before the next production release is submitted.

This is a hard prerequisite for [REQ-030](REQ-030-draft-google-play-release-readiness.md), not
optional polish — an inaccurate Data Safety declaration or a missing account-deletion path is
Play Store policy-violation territory, not just a nice-to-have.

## What changes from the current baseline

The current **live listing** (per `privacy-policy.md`) claims:
- Does not store camera frames or photos.
- Does not transmit data off-device.
- Collects no personal data.

The **current app**, depending on what the user enables:
- **Saves JPEG frames** (camera images) containing license plates to device storage.
- **Optionally uploads those frames** to an AWS S3 bucket via a pre-signed URL (REQ-014).
- **Creates an account** — email + password via Cognito, required to enable collection/upload at
  all (REQ-018, tightened to sign-in-required-to-capture in REQ-026).
- License plates are **personally identifiable information (PII)** in most jurisdictions.

This requires action in four places: the Play Store Data Safety form, account-deletion
disclosure, the privacy policy, and in-app disclosures.

---

## 1. Google Play — Data Safety section

### Data type: Photos and videos (cloud upload enabled — the only path since REQ-026)

| Field | Value |
|---|---|
| Data type | Photos and videos |
| Collected | Yes (when user enables "Contribute data" and is signed in) |
| Shared with third parties | **Yes** — uploaded to AWS S3 (operator's private bucket; AWS acts as a data processor) |
| Purpose of sharing | App functionality (model improvement) |
| Encrypted in transit | Yes (HTTPS/TLS, SigV4-signed) |
| User can request deletion | Yes — see §2 (Account and data deletion) |
| Required or optional | Optional (separate opt-in + first-time consent dialog, already implemented) |

### Data type: Personal info — Email address (account creation, REQ-018) — **missing from the original REQ-007 scope**

| Field | Value |
|---|---|
| Data type | Personal info → Email address |
| Collected | Yes — required to create a Cognito account |
| Shared with third parties | Yes — processed by AWS Cognito (data processor) |
| Purpose | Account management, app functionality |
| Encrypted in transit | Yes |
| User can request deletion | Yes — see §2 |
| Required or optional | Optional overall (the app is usable without signing in — detection works without an account; only capture/upload require it) |

**How to fill in Data Safety:**
- "Photos and videos" → "Photos" → Collected, Optional, Shared (third party = AWS), purpose "App functionality."
- "Personal info" → "Email address" → Collected, Shared (third party = AWS), purpose "Account management."
- Answer "Does your app allow users to request account or data deletion?" → **Yes** (only once §2 below is actually true — do not answer Yes until it is).

Failure to keep this section accurate after a data-collection behavior change is a Play policy
violation independent of whether the behavior is opt-in.

---

## 2. Account and data deletion — new requirement (Google Play "Account Deletion" policy)

Since the app creates user accounts (Cognito email/password, live since REQ-018), Play Console
now requires an account-deletion disclosure on every submission. Today the app has **no
self-service path** — `privacy-policy.md`'s draft language only offers "contact developer by
email," and there is no in-app "Delete my account" action (confirmed: no such code exists yet).
Google's stated preference is an in-app deletion option; a web-based request form is the fallback,
but a bare mailto with no stated SLA is the weakest acceptable form and the one most likely to
draw review friction.

**What's needed before this can be answered "Yes" in Data Safety:**
- [ ] Decide the mechanism: (a) in-app "Delete my account and data" action (Cognito
      `AdminDeleteUser` triggered via a new authenticated Lambda endpoint, since the app has no
      admin credentials on-device), or (b) a hosted web page with a request form / documented
      email process and a stated turnaround time.
- [ ] The chosen mechanism must delete (or trigger deletion of): the Cognito user record, and the
      user's uploaded objects under `uploads/<sub>/` in S3 (and anything already promoted into
      `curation/<id>/` or `done/<id>/` that's still attributable to them — decide and document how
      curated/training-committed data is handled, since it may already be baked into exported
      datasets).
- [ ] Document the mechanism and the data scope in `privacy-policy.md` (§4 below) and in the Play
      Console "Data safety" account-deletion fields (a public URL is required there even for the
      in-app option).

This is the single largest open item blocking an honest Data Safety answer — treat it as a
release blocker for REQ-030, not a follow-up.

---

## 3. In-app disclosures (required by Play policy) — **DONE**

- [x] First-time consent dialog before any local frame is written — states what's saved, that it's
      uploaded to a private server, and persists acknowledgement (`collect_first_time_shown`).
- [x] Settings toggle has an explanatory sub-label (REQ-005).

Still needed: the dialog/policy text should be updated once §2 (account deletion) is decided, so
the disclosure accurately describes how to delete an account, not just local data.

---

## 4. Privacy Policy — required rewrite

`privacy-policy.md` needs a full rewrite (not just an addition) since its Summary section
currently states the opposite of what the app does. Replace with:

```markdown
# Privacy Policy — Car Plate Detector

**Effective date:** <date of next release> (in effect until updated)

This Privacy Policy describes how **Car Plate Detector** handles information when you use the app.

## Summary
- The app uses the **camera** to detect license plates in real time; this always happens locally on your device.
- Signing in and enabling "Contribute data" are both optional. If you don't use them, no data leaves your device.
- If you sign in, the app creates an account using your **email address** (via AWS Cognito).
- If you additionally enable "Contribute data," camera frames containing detected license plates are saved on your device and uploaded to a private AWS S3 bucket operated by the developer, for the purpose of improving the detection model.
- The app does not use advertising SDKs or analytics identifiers, and does not sell data.

## Information the app processes
### Camera
The app accesses the camera to show a live preview and perform on-device license plate detection and text recognition (ML Kit). This always happens locally; the live preview itself is never uploaded.

### Account (optional)
Signing in creates an account via AWS Cognito using your email address and a password you choose. An account is required only to enable "Contribute data" or to receive model updates.

### Training data collection (optional, requires sign-in)
When you enable "Contribute data," the app saves JPEG frames with detected license plates and their bounding-box annotations to your device, then uploads them over HTTPS to a private AWS S3 bucket. License plates may be linked to vehicle owners and are treated as personal data.

## Data retention and deletion
- Local data: use "Clear collected data" in Settings, or uninstall the app.
- Account and uploaded data: <insert the mechanism decided in REQ-007 §2 — in-app action or web form/email, with expected turnaround>.

## Data sharing
- AWS (Cognito, S3) processes your email address and, if you opt in, your uploaded frames, as a data processor acting on the developer's instructions. Data is not sold or shared for advertising.

## Changes to this policy
If the app changes in a way that affects privacy, this policy will be updated and a new effective date published on the same page.

## Contact
If you have questions about this Privacy Policy, or to request deletion of your account and data, contact: **yuriy.semen@gmail.com**
```

Update `privacy-policy.md` in the repo, re-deploy it to the URL referenced in the Play Store
listing, and confirm the Play Console "Privacy policy" field still points at the same URL.

---

## 5. Permissions audit (updated)

| Permission | Status | Notes |
|---|---|---|
| `CAMERA` | Declared | Core functionality |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Declared | Upload + model download |
| `POST_NOTIFICATIONS` | Declared (added since original REQ-007 draft) | Runtime-requested on Android 13+ for auto-upload success notification (REQ-015). Not a Data Safety data type — no declaration needed beyond the permission itself. |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` | Not declared, not needed | `filesDir` (app-private internal storage) used throughout |

No Advertising ID: confirmed no ads/analytics SDK in `android/app/build.gradle.kts`, so the Play
Console "Advertising ID" declaration should be **No**.

---

## 6. Auto Backup exclusion — **DONE (2026-09-08)**

`android:allowBackup="false"` in `AndroidManifest.xml`; `data_extraction_rules.xml` also excludes
`training_data/`, `exports/`, `models/`, `upload_log.json`, and the Cognito token prefs from
device-to-device transfer. Nothing this app stores leaves the device via any OS backup/transfer
path.

---

## 7. GDPR / CCPA compliance checklist

| Requirement | How it is met |
|---|---|
| Lawful basis | Explicit opt-in consent before local collection; separate account creation for sign-in; sign-in required before any capture (REQ-026) |
| Right to access | Local export via share sheet; cloud copies identifiable by `device_id` hash (REQ-013) and Cognito `sub` |
| Right to erasure | "Clear collected data" (local); account + cloud deletion per §2 (**not yet implemented**) |
| Data minimisation | Only frames with ≥1 detection (or manual/burst capture) are saved |
| Storage limitation | Configurable quota (REQ-011/REQ-006) + collection pause when full |
| Transparency | First-time consent dialog + privacy policy (once rewritten per §4) |
| Cross-border transfer | Depends on the deployed S3 region — confirm it's still `eu-west-1` or document the actual region in the privacy policy |
| Data processor agreement | AWS is a data processor; operator must have accepted AWS's standard DPA before this release ships |

---

## Acceptance criteria

- [x] First-time consent dialog before any local frame is written.
- [x] Auto Backup disabled (`allowBackup="false"` + `data_extraction_rules.xml`).
- [ ] Account-deletion mechanism decided and implemented (§2) — **blocking**.
- [ ] `privacy-policy.md` rewritten per §4 and re-deployed to the listing's Privacy Policy URL.
- [ ] Data Safety section in Play Console updated: Photos/videos + Personal info/Email address,
      both marked Shared (AWS), and "account or data deletion" answered Yes only once §2 is true.
- [ ] Confirm the actual production S3 bucket region and reflect it accurately in the privacy
      policy's data-residency statement.
- [ ] AWS DPA accepted by the operator in the AWS account before this release goes live.
