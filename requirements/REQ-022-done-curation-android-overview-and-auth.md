---
id: REQ-022
title: Dataset Curation Android App — Overview, Authentication & AWS Access
status: done
priority: high
depends_on: REQ-013, REQ-018
---

> **Status — done (2026-08-30).** Standalone `android-training-data-reviewing-app/` Gradle project scaffolded;
> Cognito SRP sign-in, `curators`-group gate, direct `CuratorRole` S3 access, and a placeholder
> home screen with a live `ListObjectsV2` check. Infra (`aws-training-infra/aws/template.yaml`) gained
> `CuratorRole`, the `curators` Cognito group, and a token-based Identity Pool role mapping;
> deployed to the `plate-detector-upload` stack. Package workflow (REQ-023) and review editor
> (REQ-024) remain draft. See "Implementation notes" at the end.

## Summary

A new standalone Android app, `android-training-data-reviewing-app/`, internal-only and never published to an app
store, used by a trusted curator (not end users) to turn raw uploaded YOLO packages sitting in S3
`uploads/` into a verified, training-ready dataset in S3 `done/`. The app uses **direct,
IAM-scoped S3 access from the device** rather than a Lambda-mediated backend API — a deliberate
simplification appropriate for a single trusted curator on a sideloaded, unpublished APK, where the
untrusted-frontend/multiple-curator threat model that would justify a mediated API doesn't apply.

Package lifecycle is specified in **REQ-023**; the per-image review editor is specified in
**REQ-024**.

---

## Goals

- Reuse 100% of the existing Cognito infrastructure (User Pool, Identity Pool — REQ-018). No
  parallel auth system, no new backend service.
- Gate access to a curator via a Cognito group, membership managed manually (same manual-
  provisioning pattern as `AdminPrincipalArn` in REQ-018).
- Give the curator's device direct but tightly-scoped S3 permissions via a dedicated IAM role —
  enough to list/read uploads and read/write/delete curation working files, and read/write
  `done/`/`rejected/`, but never destructively touch `uploads/`.
- Keep original uploaded ZIPs in `uploads/` immutable and untouched.
- Make review progress durable enough to survive an app kill, phone reboot, or reinstall — a
  package's state is never lost as long as the original ZIP and its manifest still exist in S3
  ("restart the process").
- Ship as a fully standalone Gradle/Android project — no build-time dependency on `android-end-user-app/`.

## Non-goals

- Concurrent multi-curator support — single curator, one device/session at a time for now (see
  "Future: multi-curator" below).
- Self-service curator sign-up.
- Automated quality scoring or ML-assisted review.
- Merging completed packages into one physical combined dataset directory — `done/` accumulates
  curated packages individually; consolidating them for training is a downstream/training-pipeline
  concern (existing `training/` tooling), out of scope here. Today an administrator does it by hand,
  deliberately, to keep preparing the next training version quick; the `done/` layout (REQ-035) is
  designed so a web or desktop tool can automate it later.
- Any change to the existing `android-end-user-app/` app or its Lambda endpoints (`get-upload-url`,
  `get-model-url`).

---

## Bucket prefix layout (extends REQ-018's bucket)

```
<bucket>/
  uploads/<user_sub>/<device_id>/<filename>.zip     ← existing, untouched, "not processed" source
  models/...                                         ← existing (REQ-016), unrelated
  curation/<package_id>/
    manifest.json                                    ← per-item status + edited label content;
                                                          the durable "resume" record
  done/<package_id>/
    train/{images,labels}/...
    val/{images,labels}/...
    test/{images,labels}/...
    data.yaml                                         ← copied/regenerated from the original package
    _manifest.json                                    ← curator, started_at, completed_at, counts
  rejected/<package_id>/
    <subset>/{images,labels}/...
```

`package_id` = sanitized `<user_sub>__<device_id>__<filename-without-zip>`, deterministic from the
source ZIP's S3 key, so a curated package always traces back to its source ZIP and device.

Note there is **no** full `<subset>/{images,labels}/` working tree under `curation/` in S3 — see
"Working-copy strategy" below for why.

## Working-copy strategy (why manifest-only, not a full S3 mirror)

- Starting a package downloads the source ZIP from `uploads/` and unzips it into app-private local
  storage; review works entirely from that local copy.
- Every accept/reject decision (including any edited box content) is written to
  `curation/<package_id>/manifest.json` in S3 immediately — the **only** S3 write during review,
  and it's small and cheap (a single JSON object, rewritten in place).
- On Complete, the app uploads accepted images+labels directly to `done/<package_id>/...` and
  rejected ones to `rejected/<package_id>/...`, reading from the local unzip cache.
- **Restart/resume:** app killed or phone rebooted → the local unzip cache is still on disk, resume
  instantly, no network needed. App reinstalled or a different device used → the app re-downloads
  and re-unzips the original ZIP (still immutable in `uploads/`) and replays
  `curation/<package_id>/manifest.json`'s per-item decisions against it — fully recoverable, at the
  cost of re-running the unzip step.
- **Trade-off accepted:** far cheaper in S3 PUTs and storage than mirroring the entire working tree
  into S3, at the cost of Complete requiring the local unzip cache to be present or re-derivable
  (never resumable from S3 alone). Acceptable because `uploads/` is immutable and never expires.

---

## Authentication & authorization

### Sign-in

Same mechanism as the main app (REQ-014): email + password, Cognito SRP via
`aws-android-sdk-cognitoidentityprovider` (already used by `android-end-user-app/`'s `CognitoAuthManager`). The
ID token is exchanged for short-lived STS credentials via the existing Identity Pool.

### New Cognito group: `curators`

- Created in the existing `PlateDetectorUsers` User Pool.
- Membership managed manually (`aws cognito-idp admin-add-user-to-group`) — no self-service.
- The app checks `cognito:groups` in the decoded ID token after sign-in; a user not in the group
  sees "access denied" and the app never attempts any S3 call.

### New IAM role: `CuratorRole`

- New role mapping on the existing `DeviceIdentityPool`: requests whose ID token contains
  `curators` in `cognito:groups` assume `CuratorRole` instead of the existing `DeviceAuthRole`
  (token-based role mapping).
- `DeviceAuthRole` (used by the main `android-end-user-app/` app) is unchanged.

`CuratorRole` S3 permissions, scoped to the dataset bucket:

| Action | Resource | Why |
|---|---|---|
| `s3:ListBucket` | whole bucket | enumerate `uploads/`, `curation/`, `done/`, `rejected/` for the three screens |
| `s3:GetObject` | `uploads/*`, `curation/*`, `done/*`, `rejected/*` | download source ZIPs, read manifests, view done/rejected summaries |
| `s3:PutObject` | `curation/*`, `done/*`, `rejected/*` (never `uploads/*`) | write manifest.json, finalize into done/rejected |
| `s3:DeleteObject` | `curation/*` only | clean up manifest.json on Release/Complete |

No delete permission on `uploads/`, `done/`, or `rejected/` — once something lands there, nothing
in this app can remove it (manual operator action only).

### Dependencies

- `aws-android-sdk-cognitoidentityprovider` (auth — same as the main app).
- `aws-android-sdk-s3` (new — `AmazonS3Client`/`TransferUtility`, uses the same
  `CognitoCachingCredentialsProvider` the auth SDK produces; no manual SigV4 signing needed for S3
  calls, unlike the main app's manually-signed API Gateway calls).

### Configuration

Own `local.properties`-backed `BuildConfig` fields — `COGNITO_USER_POOL_ID`,
`COGNITO_APP_CLIENT_ID`, `COGNITO_IDENTITY_POOL_ID`, `DATASET_BUCKET_NAME`, `AWS_REGION`. Same
underlying IDs as the main app's config (shared backend), entered independently since this is a
standalone Gradle project with no build dependency on `android-end-user-app/`.

---

## Future: multi-curator (explicitly deferred, not designed now)

If a second curator is ever needed, the manifest-only working-copy strategy above is **not** safe
against two curators starting the same `package_id` concurrently — there is no claim/lock marker
in this design; a second "Start reviewing" simply overwrites the first curator's manifest. If that
becomes a real need, design a proper claim + expiry model then (per-curator working prefix,
conditional-write claim markers, conflict handling on a concurrent claim) rather than retrofitting
it piecemeal.

REQ-023 adds two lightweight, client-only pieces that partially cover this ground without the full
model: **per-item attribution** (`decided_by` / `decided_at` on each manifest item) and
**opportunistic stale cleanup** (a package with no manifest write for > 2 h is offered for
Discard / Take over when a curator next opens the app). Neither is a concurrency guarantee.

---

## Infra changes required (`aws-training-infra/aws/template.yaml`)

- New `curators` Cognito group.
- New `CuratorRole` with the policy above.
- New Identity Pool role mapping (token-based on `cognito:groups`).
- **No new Lambda functions, no new API Gateway routes** — a Lambda-mediated API surface is not
  needed under this design.

---

## Decisions

- **Access model:** direct, IAM-scoped S3 access from the device, not a Lambda-mediated API —
  justified by the single-trusted-curator, unpublished-app threat model (confirmed).
- **Project structure:** standalone Gradle project, no shared module with `android-end-user-app/` (confirmed) —
  box-editing UI and YOLO label parsing are reimplemented rather than shared.
- **Concurrency:** single curator/session at a time for v1 (confirmed) — no claim/lock markers.
- **Working copy:** lives on-device, not mirrored into an S3 `curation/` working tree; only
  `manifest.json` is synced (confirmed) — trades a rebuildable local cache for far less S3 traffic
  and storage than a full server-side working-tree mirror would need.

---

## Acceptance criteria

- [x] A Cognito user not in the `curators` group can sign in but sees "access denied" and no
      package data; the app makes no S3 call in this case. — `AccessDeniedScreen`; `CurationApp`
      routes there without constructing `S3Access`.
- [x] A Cognito user in the `curators` group signs in and obtains STS credentials scoped to
      `CuratorRole`. — token-based role mapping deployed; `HomeScreen` S3 check exercises it. (A
      token issued before the group's role assignment resolves to the default role until the next
      sign-in — surfaced with a hint in the S3 check card.)
- [x] `CuratorRole` can list/get under `uploads/`, `curation/`, `done/`, `rejected/`; can put under
      `curation/`, `done/`, `rejected/` only; can delete under `curation/` only. — `CuratorS3Access`
      inline policy in `template.yaml`, verified with `aws iam get-role-policy`.
- [ ] `CuratorRole` cannot put or delete anything under `uploads/` (verified by attempting a direct
      call with curator credentials and observing `AccessDenied`). — policy grants no such action;
      explicit negative call not yet run.
- [ ] `CuratorRole` cannot delete under `done/` or `rejected/`. — same; explicit negative call not
      yet run.
- [x] `DeviceAuthRole` permissions (used by the main `android-end-user-app/` app) are unchanged — regression
      check. — deploy changeset showed only `Add CuratorRole`, `Add CuratorGroup`,
      `Modify DeviceIdentityPoolRoleAttachment`; `authenticated` role still `DeviceAuthRole`.
- [x] Curator sign-out clears cached STS credentials from device memory/storage. —
      `CuratorAuthManager.signOut()` calls `CognitoCachingCredentialsProvider.clear()` and
      `CuratorPrefs.clear()`.
- [x] The app builds and runs as a standalone Gradle project with no dependency on `android-end-user-app/`. —
      `android-training-data-reviewing-app/` builds via its own Gradle wrapper; no `:android` include.
- [x] The original ZIP in `uploads/` is byte-for-byte unchanged after any curation activity. —
      REQ-022 wires no write path at all; `CuratorRole` has no `PutObject`/`DeleteObject` on
      `uploads/*`.

## Implementation notes

- **Project:** `android-training-data-reviewing-app/` — package `com.github.yuriysemen.platesdetector.curation`,
  standalone Gradle project mirroring `android-end-user-app/`'s toolchain (AGP, Kotlin, Compose BOM, JVM 17).
  Its own committed `local.properties` (`COGNITO_*` shared with the main app,
  `DATASET_BUCKET_NAME`); AWS region is derived from the Identity Pool ID prefix.
- **Auth:** `CuratorAuthManager` — a trimmed copy of the main app's `CognitoAuthManager` (no
  shared module, per the decision above): SRP sign-in only (no sign-up/confirm — curators are
  provisioned manually), token refresh, `groups()` / `isCurator()` decoding `cognito:groups`.
- **S3:** `S3Access` builds an `AmazonS3Client` from `CuratorRole` STS credentials
  (`aws-android-sdk-s3`); REQ-022 only calls `ListObjectsV2` to prove access — it is the seed for
  REQ-023's package-listing repository.
- **UI:** `CurationApp` state machine — `AuthScreen` → (`AccessDeniedScreen` | `HomeScreen`).
  `HomeScreen` is a placeholder listing the three REQ-023 screens plus the S3 access-check card.
- **Infra:** `CuratorRole` (trust: `AssumeRoleWithWebIdentity`, `aud` = Identity Pool),
  `CuratorGroup` (`GroupName: curators`, `RoleArn` → `CuratorRole`, membership added manually via
  `aws cognito-idp admin-add-user-to-group`), and a `Type: Token` entry in the
  `DeviceIdentityPoolRoleAttachment` `RoleMappings`. No new Lambda / API Gateway route. No
  S3 bucket-policy change (same-account role, identity-based policy suffices).
