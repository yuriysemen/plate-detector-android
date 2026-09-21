# Plate Detector — Training Data Collection App (Android)

Internal-only Android app that builds the training dataset. It runs the same live plate detector as
the published app, but adds the parts the published app deliberately does not have: sign-in,
frame capture, and scheduled cloud upload.

**Not published to Google Play.**

## Why this module exists

A detection model is only as good as the data it was trained on, and the best data is real footage
from real roads. Producing it means capturing frames that contain *other people's* license plates,
which is a very different privacy and legal posture from a detector that keeps nothing. Rather than
hide collection behind a setting in the public app, it was split into this separate, internal app
([REQ-031](../requirements/REQ-031-done-split-detection-and-training-apps.md)). The scope and
deployment summary is in
[REQ-040](../requirements/REQ-040-done-collection-app-scope-and-deployment.md).

## What it does

- **Live detection** (TFLite YOLO + ML Kit OCR), because capture decisions depend on what the model
  currently sees.
- **Frame capture** in three modes: automatic, manual (a capture button), and burst. Each frame is
  stored together with the model's predicted boxes in YOLO format.
- **Gated capture:** nothing is written unless "collect training data" is enabled *and* the user is
  signed in.
- **Cloud upload** of collected frames as ZIP packages to S3 — on demand or on a daily schedule,
  optionally restricted to Wi-Fi, running in the background through WorkManager.
- **Sign-in** with self-registration by email and password (Amazon Cognito). Short-lived AWS
  credentials come from a Cognito Identity Pool, and API calls are SigV4-signed. No AWS keys are
  ever bundled in the APK.
- **Storage management:** a configurable quota with least-recently-used eviction, and an option to
  reset collected data.
- **Upload diagnostics:** every upload attempt is logged with its failure reason, and shown in the
  app.
- **Model updates:** signed-in users can receive newer detection models through the backend.
- **Resilient auth:** session expiry, authorization failures, and misconfiguration are told apart,
  so a user is never stuck in a dead sign-in state.
- **On-device backend configuration**, so one generic APK can be handed to a tester without
  rebuilding it for them.
- A one-time **capture guidelines** screen before the camera is first enabled.

Reviewing and editing the collected frames is **not** done here. That happens in
[`android-training-data-reviewing-app`](../android-training-data-reviewing-app/README.md).

## Where it fits

```
this app ──(SigV4 upload)──▶ AWS (S3) ──▶ reviewing app ──▶ verified dataset ──▶ training
```

The backend it talks to is [`aws-training-infra`](../aws-training-infra/aws/README.md).

## Tech

Kotlin · Jetpack Compose · CameraX · TensorFlow Lite · ML Kit · WorkManager · Amazon Cognito ·
AWS SigV4 · S3 pre-signed uploads

## Quick start

1. **Configure** — either:
   - copy `local.properties.example` to `local.properties` and fill in the `COGNITO_*` and
     `UPLOAD_SERVICE_URL` values from the
     [`aws-training-infra/aws`](../aws-training-infra/aws/README.md) stack outputs (the `COGNITO_*`
     values are the same ones the reviewing app uses), then rebuild; or
   - build with no backend values and configure it **on-device** instead, via Settings → "Backend
     configuration" (or the "Configure backend…" link on the "No detection model" screen)
     ([REQ-032](../requirements/REQ-032-done-training-android-in-app-backend-config.md)). A value
     entered this way permanently overrides `local.properties`, even across rebuilds.
2. **Build and install:**
   ```bash
   ./gradlew :app:installDebug
   ```
3. Sign in, enable "Contribute data" in Settings, and use the app like the published one — live
   detection, plus auto, manual, and burst capture and upload.

Other Gradle tasks (run from this folder): `:app:assembleDebug`, `:app:assembleRelease`,
`:app:test`, `:app:connectedAndroidTest`.

## Why this isn't published

Capturing frames while driving means capturing other people's license plates, not just the device
owner's. Keeping the app internal avoids Play Store's account-deletion and Data Safety obligations,
but it does **not** remove the underlying data-handling responsibility. What this app collects
should be treated with real care regardless of how it is distributed.

## More detail

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — architecture reference.
- [`CLAUDE.md`](CLAUDE.md) — key files and build/config notes for anyone (human or AI) changing this app.
- [`ROADMAP.md`](ROADMAP.md) — full feature history.
- [`../requirements/`](../requirements/README.md) — the specs each feature was built against.
