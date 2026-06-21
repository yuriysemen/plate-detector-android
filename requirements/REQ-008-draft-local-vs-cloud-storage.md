---
id: REQ-008
title: Local-Only vs. Cloud Upload — Analysis and Decision
status: draft
priority: medium
---

## Summary

Compare storing collected frames on-device only vs. uploading them to AWS S3 (or similar). Define what changes in terms of implementation, Play Store compliance, and user privacy for each option.

---

## Option A — Local only (recommended for v1)

Frames are saved to `context.filesDir/training_data/` and never leave the device unless the user explicitly exports via the share sheet.

### Pros

- **No permissions beyond what is already declared.** `filesDir` requires nothing.
- **Minimal Play Store obligation.** No network transmission = simpler Data Safety declaration.
- **No backend infrastructure.** No S3 bucket, IAM roles, API keys, or server costs.
- **Simplest privacy story.** The current privacy policy's "data never leaves your device" statement is preserved with only a minor addendum.
- **No GDPR data processor agreement needed.** No third-party receives the data.
- **Works offline.** Collection works anywhere, with no connectivity required.

### Cons

- **Data stays on one device.** Collecting large datasets across many users requires each user to manually export and send files.
- **Storage limit.** Internal storage on budget devices may be small. The 500 MB cap means ~1,250 HD frames per device.
- **Manual aggregation.** If multiple testers are collecting, someone must gather and merge their exports.

### Implementation effort

Low. Covered entirely by REQ-002 through REQ-006.

---

## Option B — Upload to AWS S3

Frames are uploaded to an S3 bucket after being saved locally (local copy retained or discarded based on a setting).

### Pros

- **Aggregate dataset from many users.** All contributors' frames land in one place automatically.
- **Unlimited storage** (cost-bounded, not device-bounded).
- **Can trigger automated pipelines.** S3 event notifications can start a SageMaker training job when a new batch arrives.

### Cons

**Implementation complexity:**
- AWS credentials must not be embedded in the APK (security risk). Requires either:
  - A backend that vends temporary STS tokens (Cognito Identity Pool is the standard approach).
  - API Gateway + Lambda as a proxy.
- Retry logic for failed uploads, background upload service, battery impact.
- Upload queue management (avoid uploading 500 MB of frames on mobile data).
  - Need a "Wi-Fi only upload" option.

**Play Store obligations (significantly higher):**
- Data Safety section must declare:
  - **Shared with third parties:** Yes (AWS is a third party under Google's definition even if it's your own bucket).
  - **Encrypted in transit:** Yes (HTTPS).
  - **Data type:** Photos and videos — collected and shared.
- Google may require additional justification for sharing photo data with third parties.
- Uploading images without explicit per-upload consent is risky. A one-time opt-in at feature enable is borderline — stronger practice is to show what will be uploaded before each batch.

**Privacy and legal:**
- License plates are PII. Storing them on a server introduces GDPR data controller obligations:
  - Must have a Data Processing Agreement with AWS (AWS's standard DPA covers this, but you must accept it in your AWS account settings).
  - Must disclose the storage location (EU users: prefer AWS EU regions to avoid Schrems II complications).
  - Must support right to erasure requests — a user who later requests deletion means you must be able to identify and delete their frames from the S3 bucket. Requires tagging each upload with a user identifier or device identifier.
- **If users are in the EU:** GDPR requires consent to be "freely given, specific, informed, and unambiguous." Bundling cloud upload into a single "collect training data" toggle may not be sufficient; a separate toggle for "also upload to server" is safer.

**Android policy:**
- `INTERNET` permission is likely already declared (for model download from GitHub Releases). No new permission needed.
- However, background uploads require `FOREGROUND_SERVICE` or `WorkManager`. Using `WorkManager` with a network constraint (Wi-Fi only) is the Android-recommended approach.

---

## Option C — Hybrid (local + optional upload)

Local collection is always on when the feature is enabled. A separate toggle — "Also upload to improve the shared model" — controls whether frames are also queued for upload.

This is the cleanest UX and legal separation:
- Local collection: one Data Safety entry, minimal privacy obligation.
- Cloud upload: separate opt-in, separate consent dialog, separate Data Safety entry.
- Users who distrust cloud upload can still contribute by exporting manually.

---

## Recommendation

**Implement Option A (local only) first.**

Reasons:
1. Lower risk — no backend to build, no privacy incidents possible from server breaches.
2. The export mechanism (zip + share sheet) is sufficient for a small group of testers to aggregate data manually.
3. If the app grows and many users are contributing, Option C can be added as a second phase without changing the local collection foundation.
4. Play Store review is faster and lower-risk with local-only data handling.

**If cloud upload is added later (Option C):**
- Use **Cognito Identity Pool** (unauthenticated identity) to vend temporary S3 credentials. Never embed AWS Access Key + Secret in the APK.
- Use **WorkManager** with `NetworkType.UNMETERED` constraint (Wi-Fi only).
- Tag each S3 object with a stable device identifier (hashed Android ID or a UUID stored in prefs) to support deletion requests.
- Update Privacy Policy and Data Safety before releasing.
- Prefer **AWS eu-west-1 (Ireland)** or **eu-central-1 (Frankfurt)** as default region for GDPR-friendly data residency.

---

## Acceptance criteria (Option A — current scope)

- [ ] No network requests are made as part of the data collection feature.
- [ ] `INTERNET` permission usage is unchanged from the pre-feature baseline.
- [ ] Export is user-initiated only (no background uploads, no silent sharing).
- [ ] Architecture is designed so that a future upload queue can be added alongside the local saver without refactoring the core collection logic.
