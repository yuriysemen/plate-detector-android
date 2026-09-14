# Plate Detector — Training Data Collection (Android)

Internal-only Android app, **not published to Google Play**, used to build the training dataset
for the detection model. It's a copy of the published [`../android/`](../android/README.md) app
that keeps the full sign-in / capture / cloud-upload pipeline `android/` no longer has — see
[REQ-031](../requirements/REQ-031-draft-split-detection-and-training-apps.md) for why the two were
split. Detected/captured frames upload to the same S3 bucket `curation-android` reads from.

## Quick start

1. **Configure** — either:
   - copy `local.properties.example` to `local.properties` and fill in the `COGNITO_*` /
     `UPLOAD_SERVICE_URL` values from the [`infra/aws`](../infra/aws/README.md) stack outputs
     (same values as `curation-android/local.properties`'s `COGNITO_*`), then rebuild; or
   - build with no backend values at all and configure it **on-device** instead, via Settings →
     "Backend configuration" (or the "Configure backend…" link on the "No detection model" screen)
     — useful for handing the same generic APK to a tester without rebuilding per person
     ([REQ-032](../requirements/REQ-032-done-training-android-in-app-backend-config.md)). A value
     entered this way permanently overrides whatever `local.properties` says, even across rebuilds.
2. **Build & install:**
   ```bash
   ./gradlew :app:installDebug
   ```
3. Sign in (self-registration via email + password), enable "Contribute data" in Settings, and use
   the app like the published one — live detection, plus auto/manual/burst capture and upload.

## More detail

- [`CLAUDE.md`](CLAUDE.md) — architecture, key files, and build/config notes.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — deeper architecture reference.
- [`ROADMAP.md`](ROADMAP.md) — full feature history (inherited from `android/` as of the split).
- [`../curation-android/`](../curation-android/README.md) — where the uploads from this app get
  reviewed and turned into a training-ready dataset.
- [`../requirements/`](../requirements/README.md) — the spec each feature was built against.

## Why this isn't published

Capturing frames while driving means capturing *other people's* license plates, not just the
device owner's — a materially different privacy/legal posture than a detection-only app (see
REQ-031's "what this does and doesn't fix"). Keeping this app internal-only avoids Play Store's
account-deletion and Data Safety obligations, but does **not** remove the underlying data-handling
responsibility — treat what this app collects with real care regardless of distribution channel.
