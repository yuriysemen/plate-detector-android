---
name: req022-curation-android
description: curation-android app — REQ-022 (auth + CuratorRole S3), REQ-023 (3-tab workflow + review screen), REQ-025 (vehicle-type class picker + S3 category list) all done. REQ-024 (precise box move/resize) draft.
metadata:
  type: project
---

The `curation-android/` app — a standalone, internal-only Android app for a single trusted curator
to review uploaded YOLO packages in S3 and promote them into a training-ready `done/` dataset.
**REQ-022** (foundation), **REQ-023** (workflow) and **REQ-025** (vehicle-type categories) done as
of 2026-08-31; **REQ-024** (precise box move/resize + pinch-zoom) is draft — add/delete boxes and
the class picker already exist via REQ-025.

**Why:** the dataset needs human review before training; the main `android/` app only uploads.
This is a separate deliverable (not a feature of the main app), specced as REQ-022/023/024.

**How to apply:** when touching curation-android, its infra, or planning REQ-024, read
`curation-android/CLAUDE.md` and `requirements/REQ-022-done-...` / `REQ-023-done-...` first.

Key decisions / facts:
- **Standalone Gradle project**, package `com.github.yuriysemen.platesdetector.curation`, no build
  dependency on `android/`. Mirrors the main app's toolchain. Own committed `local.properties`
  (`COGNITO_*` shared with the main app, plus `DATASET_BUCKET_NAME`); AWS region derived from the
  identity-pool-id prefix.
- **Direct S3 access via `CuratorRole`**, not a Lambda-mediated API — single-trusted-curator,
  unpublished-APK threat model. `CurationRepository` holds all S3 ops.
- **Workflow state is 100% S3-file-derived** (no DB/status field): Not Processed = `uploads` zip
  with no `curation/<id>/manifest.json` and no `done/<id>/_manifest.json`; In Progress = curation
  manifest exists; Done = done manifest exists. Start PUTs the manifest, each decision rewrites it,
  Complete finalises into `done/`+`rejected/` and deletes the curation manifest, Release/Discard
  deletes it.
- **Single curator, one session** — no claim/lock. REQ-023 added client-only partials:
  per-item `decided_by`/`decided_at`; stale cleanup at 2 h (manifest S3 `LastModified` + ~3 min
  heartbeat while reviewing) surfaced as Discard / Take over on the In Progress tab.
- **Review screen** (`ReviewScreen`): image + YOLO overlay + a per-box **vehicle-type class
  dropdown** (REQ-025), top-bar **add-box** (drag a rectangle) + delete. Accept blocked until every
  box is classified. Precise move/resize of existing boxes = REQ-024 (not built).
- **REQ-025 vehicle classes:** YOLO class-id column carries the type. `config/vehicle-categories
  .json` in the bucket (`0` license_plate … `5` other), fetched on workflow entry, bundled fallback
  at `curation-android/app/src/main/assets/vehicle-categories.json`. `id` is the class id — pinned,
  append-only. `done/` label files carry the ids; `data.yaml` header regenerated (`nc`/`names`);
  `_manifest.json` gains `category_list_version` + `class_counts`. `CuratorRole` gained
  `s3:GetObject` on `config/*` (read-only). Operator seeds the list with `aws s3 cp`. This is the
  training-data source for REQ-009 (whose separate-classifier recommendation is now one option).
- **Working copy on-device** (`filesDir/packages/<id>/`) — only `manifest.json` synced to S3; makes
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
- **Still draft:** REQ-024 (precise box **move/resize** + pinch-zoom/pan on `ReviewScreen`).
