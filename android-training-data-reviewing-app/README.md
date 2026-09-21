# Plate Detector — Data Reviewing App (Android)

Internal-only Android app for a single trusted curator to review the packages uploaded by the
collection app and turn them into a verified, training-ready dataset.

**Never published to an app store**; installed by sideloading the APK.

## Why this module exists

The collection app labels each frame with the model's own predicted boxes, so the raw uploads
contain mistakes: missed plates, wrong boxes, and frames that should not be used at all. Training on
that would teach the model its own errors. This app is the human-in-the-loop step in between — a
person looks at every item, fixes or rejects it, and only then does it become training data.

Its scope and deployment summary is in
[REQ-041](../requirements/REQ-041-done-reviewing-app-scope-and-deployment.md).

## What it does

- **Three-tab workflow:** *Not processed*, *In progress*, and *Done*. There is no status database —
  the state of every package is derived entirely from which files exist in S3.
- **Review editor:** accept or reject each item, and move, resize, add, or delete bounding boxes,
  with pinch-to-zoom and pan for precision. A decided item can be changed again later.
- **Resumable sessions:** the working copy of a package lives on the device, and progress is synced
  to S3, so it survives app kills, reboots, and reinstalls.
- **Complete:** bundles accepted and rejected items into compressed per-package archives
  (`done/`, `rejected/`) with a regenerated `data.yaml`, so finishing a 100-item package takes a
  handful of uploads instead of hundreds.
- **Done-package viewer:** a read-only grid of a completed package's accepted images with their
  final boxes overlaid.
- **Attribution and stale-package handling:** each decision records who made it and when, and a
  package left idle for over two hours is offered for discard or takeover.
- **Access control:** only members of the Cognito `curators` group can use the app. Anyone else sees
  "Access denied", and no S3 call is made for them.

## Design decisions worth noting

- **Direct S3 access, no backend API.** The app gets short-lived credentials for a scoped
  `CuratorRole` through the Cognito Identity Pool and talks to S3 itself. For a single trusted
  curator using an unpublished APK, this removes an entire API layer. The role can read `uploads/`,
  write `curation/`, `done/` and `rejected/`, and delete only inside `curation/` — it can never
  destroy raw uploads.
- **Workflow state lives in storage, not in a database.** Fewer moving parts, and the bucket is
  always the source of truth.
- **One curator, one session at a time.** Full multi-curator locking was consciously deferred; the
  attribution and stale-package handling above are partial measures, not a concurrency guarantee.
- **A category list was built, then rolled back.** Vehicle-type classification (civilian, police,
  fire, medical) was implemented and then removed to focus on plate detection. The infrastructure
  was kept, so re-expanding is cheap. The history is in
  [REQ-025](../requirements/REQ-025-done-curation-vehicle-type-categories.md) and
  [REQ-033](../requirements/REQ-033-done-curation-remove-vehicle-type-classification.md).

## Where it fits

```
collection app ──▶ S3 uploads/ ──▶ this app ──▶ S3 done/ ──▶ training
```

The backend it uses is [`aws-training-infra`](../aws-training-infra/aws/README.md). The finished
`done/` packages are what [`training/`](../training/ultralytics/README.md) consumes.

## Tech

Kotlin · Jetpack Compose · Amazon Cognito (SRP sign-in) · AWS SDK for S3 · Cognito Identity Pool
token-based role mapping

## Quick start

1. **Deploy the infra** (one-time; adds `CuratorRole` and the `curators` group):
   ```bash
   cd ../aws-training-infra/aws && sam build && sam deploy
   ```
2. **Authorize a curator** — their Cognito account must already exist:
   ```bash
   aws cognito-idp admin-add-user-to-group \
     --user-pool-id <UserPoolId> --username <email-or-sub> --group-name curators
   ```
3. **Configure** — copy `local.properties.example` to `local.properties` and fill in the `COGNITO_*`
   values from the stack outputs (the same ones the collection app uses) and `DATASET_BUCKET_NAME`
   from the `DatasetBucketName` output.
4. **Build and install:**
   ```bash
   ./gradlew :app:installDebug
   ```
5. Sign in. A non-curator sees "Access denied". A curator lands on the home screen, where an **S3
   access** card runs a live listing against the bucket to confirm the role works. If you were added
   to the group *after* signing in, sign out and back in.

Other Gradle tasks (run from this folder): `:app:assembleDebug`, `:app:assembleRelease`,
`:app:testDebugUnitTest`. A JDK 17+ is required.

## More detail

- [`CLAUDE.md`](CLAUDE.md) — architecture, file-by-file responsibilities, and configuration.
- [`../requirements/`](../requirements/README.md) — REQ-022 to REQ-036 and REQ-041 cover this app.
