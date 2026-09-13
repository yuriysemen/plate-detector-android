# Plate Curation (Android)

Internal-only Android app for a single trusted curator to review YOLO packages uploaded to S3 by
the main [`../android`](../android) app and promote the good ones into a training-ready `done/`
dataset. Never published to an app store; installed by sideloading the debug/release APK.

It shares the main app's Cognito backend but accesses S3 **directly** through a scoped
`CuratorRole` — no backend API. Full rationale and spec: [`../requirements`](../requirements)
REQ-022, REQ-023, REQ-024, REQ-025 (all done).

## Quick start

1. **Deploy the infra** (one-time, adds `CuratorRole` + the `curators` group):
   ```bash
   cd ../infra/aws && sam build && sam deploy
   ```
2. **Authorize a curator** — their Cognito account must exist already:
   ```bash
   aws cognito-idp admin-add-user-to-group \
     --user-pool-id <UserPoolId> --username <email-or-sub> --group-name curators
   ```
3. **Configure** — copy `local.properties.example` to `local.properties` and fill in `COGNITO_*`
   from the stack outputs (same values as `../android/local.properties`) and
   `DATASET_BUCKET_NAME` from the `DatasetBucketName` output.
4. **Build & install:**
   ```bash
   ./gradlew :app:installDebug
   ```
5. Sign in. A non-curator sees "Access denied". A curator lands on the home screen; the **S3
   access** card runs a live `ListObjectsV2` against the bucket to confirm `CuratorRole` works.
   If you were added to the group *after* signing in, sign out and back in.

See [CLAUDE.md](CLAUDE.md) for build commands, configuration, and architecture.
