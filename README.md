# plate-detector-android

An end-to-end license-plate detection product: an Android app that runs a TFLite YOLO model
on-device and reads plate text via ML Kit OCR, the AWS infrastructure and training-data
curation tooling that turn its opt-in uploads into a growing labeled dataset, and the training
pipelines that turn that dataset back into new models. YOLO is the first (and currently
implemented) detection experiment; the repo is organized to let additional approaches be compared
over time.

## What's in this repo

| Component | What it demonstrates |
|---|---|
| [`android-end-user-app/`](android-end-user-app/README.md) — the published detection app | On-device ML (TFLite YOLO + ML Kit OCR). Detection-only — no storage, no upload, no accounts, no runtime network access — see [REQ-031](requirements/REQ-031-done-split-detection-and-training-apps.md) |
| [`android-training-data-collection-app/`](android-training-data-collection-app/README.md) — the data-collection app | Cognito auth, SigV4-signed cloud upload, background work scheduling — everything `android-end-user-app/`'s collection pipeline used to do, now unpublished and internal-only |
| [`android-training-data-reviewing-app/`](android-training-data-reviewing-app/README.md) — the data-curation app | A third, purpose-built Android app for a human-in-the-loop labeling workflow directly against S3 (no backend API) |
| [`aws-training-infra/aws/`](aws-training-infra/aws/README.md) — cloud infrastructure | AWS SAM: S3, Lambda, API Gateway, Cognito User/Identity Pools, scoped IAM roles |
| [`training/`](training/ultralytics/README.md) / [`experiments/`](experiments/ultralytics/README.md) — model training | Python YOLO training/export pipelines that consume the curated dataset |
| [`requirements/`](requirements/README.md) | Spec-driven development trail — one `REQ-NNN` doc per feature, from initial detection format through cloud upload, curation workflow, and auth hardening |

## Android app features (`android-end-user-app/`, published)
- Real-time license plate detection (on-device, TFLite YOLO)
- Bounding box overlay with confidence score
- OCR — reads plate text using ML Kit (always active; text shown in bounding box label)
- Optional beep alert on detection
- Multiple models selectable in Settings; per-model confidence threshold
- Detection-only: no accounts, no storage, no upload, no runtime network access —
  `android:allowBackup="false"` and nothing is ever written that would need excluding from backup
  in the first place ([REQ-031](requirements/REQ-031-done-split-detection-and-training-apps.md))

The account-creation / frame-capture / cloud-upload / model-auto-update pipeline this app used to
have now lives entirely in [`android-training-data-collection-app/`](android-training-data-collection-app/README.md) — an unpublished,
internal counterpart used to build the training dataset. See its README for that feature list.

## Getting a model for the Android app

`android-end-user-app/`'s model is **build-time only** — there's no runtime download path. The Gradle build
downloads a bundled default model automatically from the latest GitHub Release tagged
`model_v<x.y.z>`. For a local build you have three options:

**Option 1 — GitHub token (recommended):** Add to `android-end-user-app/local.properties` (gitignored):
```
MODEL_DOWNLOAD_TOKEN=ghp_<your_personal_access_token>
```
The token needs `repo` read scope. The build picks the latest `model_v*` release by semantic
version.

**Option 2 — Manual placement:** Copy a compatible `.tflite` (and its `.txt` sidecar) to
`android-end-user-app/app/src/main/assets/models/`. The download step is skipped when the file already exists.

**Option 3 — No model at build time:** If neither a token nor a local file is present, the build
succeeds with a warning. The app installs and shows a "No detection model" screen with a Retry
button — fixable only by rebuilding or manually placing a file and reinstalling.

(`android-training-data-collection-app/` additionally supports fetching model updates at runtime for signed-in users —
see its own docs; that path doesn't exist in the published app.)

## Notes on the components above
- `android-training-data-reviewing-app/` reuses the same Cognito backend as `android-training-data-collection-app/` but talks to S3
  directly via a scoped `CuratorRole` (no backend API). A `curators`-group account resolves *both*
  apps to `CuratorRole` (shared User Pool client), which is why `CuratorRole` is also granted
  `execute-api:Invoke` — otherwise a curator's own account couldn't use `android-training-data-collection-app/`.
  `android-end-user-app/` doesn't use Cognito at all, so this doesn't affect it.
- `experiments/` holds exploratory training work; entries may be promoted into `training/` once
  they prove useful, or stay for history and comparison.
- `datasets/dataset_YOLO/` documents the initial YOLO dataset layout and format expectations.

## Release artifacts (signed when secrets are available)
The GitHub Actions release workflow signs artifacts when the Android keystore secrets are provided. When the secrets are missing, it still builds unsigned release outputs.

- Android App Bundle (signed if secrets are present): `android-end-user-app/app/build/outputs/bundle/release/app-release.aab`.
- APK output:
  - Signed when secrets are present: `android-end-user-app/app/build/outputs/apk/release/app-release.apk`.
  - Unsigned when secrets are missing: `android-end-user-app/app/build/outputs/apk/release/app-release-unsigned.apk`.

## Building the Android app locally (unsigned)
Local builds are unsigned by default, so you can run the standard Gradle tasks without supplying any extra parameters:

```
cd android-end-user-app
./gradlew :app:assembleRelease
```

The unsigned APK will be available at:
`android-end-user-app/app/build/outputs/apk/release/app-release-unsigned.apk`.

If you prefer a bundle, run:

```
cd android-end-user-app
./gradlew :app:bundleRelease
```

The unsigned bundle will be at:
`android-end-user-app/app/build/outputs/bundle/release/app-release.aab`.

## Building signed artifacts in GitHub Actions
The release workflow signs artifacts only when the keystore secrets are present. Provide the following secrets:

- `ANDROID_KEYSTORE_BASE64` (base64-encoded JKS/keystore file)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow decodes the keystore and sets the environment variables used by Gradle (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) before running:

```
./gradlew clean :app:bundleRelease :app:assembleRelease
```

When those secrets are set, the workflow produces:

- Signed bundle: `android-end-user-app/app/build/outputs/bundle/release/app-release.aab`
- Signed APK: `android-end-user-app/app/build/outputs/apk/release/app-release.apk`

## Roadmap (planned)
- **Automated dataset consolidation + retraining** — pulling every curator-reviewed `done/` package
  out of S3 into one merged training set and kicking off a training run is still a manual step
  today (see [`training/`](training/ultralytics/README.md)); automating that hand-off is planned
  next.
- **Parking access control** — vehicle-type classifier + access decision overlay (civilian / police / emergency).

See `android-end-user-app/ROADMAP.md` for the full backlog.

## Privacy
`android-end-user-app/` (the published app) is designed to minimise data leaving the device — and since
[REQ-031](requirements/REQ-031-done-split-detection-and-training-apps.md), there's nothing left to
minimise:
- Camera frames are processed locally in memory; nothing is uploaded, ever.
- No accounts, no analytics or tracking, no runtime network access at all.

`android-training-data-collection-app/` (internal, unpublished) is where the optional data-collection pipeline lives
now — see its own README for what it collects and why it isn't published.

See: [Privacy Policy](privacy-policy.md)

## License
This repository is licensed under **AGPL-3.0-only**. See [LICENSE](LICENSE).

Ultralytics YOLO (used for training/export) is AGPL-3.0. Model artifacts produced through that pipeline are treated as AGPL-3.0 by default per Ultralytics licensing.

See: [Third-Party Notices](THIRD_PARTY_NOTICES.md)
