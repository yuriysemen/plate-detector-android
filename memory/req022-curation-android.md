---
name: req022-curation-android
description: REQ-022 done — new standalone curation-android app (Cognito sign-in, curators-group gate, direct CuratorRole S3 access). REQ-023/024 (workflow + editor) still draft.
metadata:
  type: project
---

REQ-022 (done, 2026-08-30) established the `curation-android/` app — a standalone, internal-only
Android app for a single trusted curator to review uploaded YOLO packages in S3 and promote them
into a training-ready `done/` dataset.

**Why:** the dataset needs human review before training; the main `android/` app only uploads.
This is a separate deliverable (not a feature of the main app), specced as REQ-022/023/024.

**How to apply:** when touching curation-android, its infra, or planning REQ-023/REQ-024, read
`curation-android/CLAUDE.md` and `requirements/REQ-022-done-...` first.

Key decisions / facts:
- **Standalone Gradle project**, package `com.github.yuriysemen.platesdetector.curation`, no build
  dependency on `android/`. Mirrors the main app's toolchain. Own committed `local.properties`
  (`COGNITO_*` shared with the main app, plus `DATASET_BUCKET_NAME`); AWS region derived from the
  identity-pool-id prefix.
- **Direct S3 access via `CuratorRole`**, not a Lambda-mediated API — single-trusted-curator,
  unpublished-APK threat model. `S3Access` is the seed for REQ-023's package-listing repository.
- **Single curator, one session** — no claim/lock markers (deferred).
- **Working copy on-device** — only `curation/<package_id>/manifest.json` synced to S3; makes
  progress restartable across kill/reboot/reinstall.
- **Infra** (`infra/aws/template.yaml`): `CuratorRole` (S3: list bucket; get `uploads|curation|
  done|rejected/*`; put `curation|done|rejected/*`; delete `curation/*` only — never writes/deletes
  `uploads/`), `CuratorGroup` (`curators`, manual membership via `admin-add-user-to-group`,
  `RoleArn` → CuratorRole), and a `Type: Token` entry in `DeviceIdentityPoolRoleAttachment
  .RoleMappings` (key is arbitrary — `IdentityProvider` carries the real value via `!Sub`).
  `DeviceAuthRole` (main app) untouched. Deployed to stack `plate-detector-upload`.
- **Gotcha:** a curator added to the group *after* signing in keeps a token with no role claim →
  Identity Pool resolves the default role → S3 `AccessDenied`. Fix = fresh sign-in. The app
  clears stale cached STS creds on `newCredentialsProvider()` and hints at this in the S3 card.
- **Still draft:** REQ-023 (Not Processed / In Progress / Done screens, start/complete/release),
  REQ-024 (per-image box editor).
