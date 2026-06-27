---
name: req016-model-distribution
description: REQ-021 draft — model stored in S3 models/ prefix, new GetModelUrl Lambda, build fix for model_v* GitHub releases, on-device auto-update for signed-in users. Supersedes REQ-016.
metadata:
  type: project
---

REQ-021 (draft) covers the full model distribution overhaul.

**Why:** Models need to be updatable without shipping a new APK; authenticated users should always get the latest compatible model; the build was broken for private-repo GitHub Releases.

**How to apply:** When working on anything touching model download, Lambda infra, or the Gradle build model task, refer to `requirements/REQ-021-draft-model-distribution-and-auto-update.md` for the agreed design.

Key decisions:
- S3 bucket (existing) — new `models/v<semver>/` prefix; each version has `metadata.json` with `model_version` and `min_app_version`.
- New Lambda `GetModelUrlFunction` — `GET /get-model-url?app_version=<versionName>` on existing UploadApi; returns `compatible` and `latest` model with pre-signed GET URLs.
- Build Gradle task — queries GitHub API to find latest `model_v*` release by semver; uses Bearer token + `Accept: application/octet-stream` for private repo; fails gracefully (warning, not error) if no release found.
- Android — `ModelCheckWorker` (1 h periodic, on startup if signed in); confirmation dialog before download; bundled asset never deleted; all downloaded models deleted on sign-out.
- Mobile data toggle renamed "Use mobile data" — covers uploads AND model downloads.
- Supersedes REQ-016.