# Plate Detector — AWS Upload Infrastructure

AWS SAM stack that backs the cloud dataset upload feature (REQ-014, REQ-018) and the internal
dataset-curation app (REQ-022). Creates an S3 bucket, two Lambda functions, an API Gateway HTTP
API, a Cognito User Pool + Identity Pool, and the `CuratorRole` used for direct S3 curation access.

---

## Prerequisites

- AWS CLI configured with a profile that has permissions to create S3, Lambda, IAM, and API Gateway resources.
- AWS SAM CLI installed (`pip install aws-sam-cli` or `brew install aws-sam-cli`).

---

## Required inputs

Provided interactively on first deploy (`sam deploy --guided` prompts for them):

| Parameter | Description | Example |
|---|---|---|
| `BucketName` | Globally unique S3 bucket name | `plate-dataset-uploads` |
| `AdminPrincipalArn` | IAM user or role ARN with full S3 access for data review | `arn:aws:iam::826077735947:user/plate-detector-admin` |

Optional parameters (defaults shown, override if needed):

| Parameter | Default | Description |
|---|---|---|
| `PresignedUrlExpirySeconds` | `3600` | Pre-signed URL validity in seconds |
| `StageName` | `prod` | API Gateway stage name |

---

## What this stack creates

| Resource | Purpose |
|---|---|
| S3 bucket | Private, encrypted storage for uploaded dataset ZIPs and curated output |
| Lambda (`GetUploadUrlFunction`) | Generates pre-signed S3 PUT URLs on request |
| Lambda (`GetModelUrlFunction`) | Generates pre-signed S3 GET URLs for model artifacts (REQ-016) |
| API Gateway HTTP API | HTTPS endpoint for the Lambdas; **IAM-authenticated** (SigV4 required) |
| Cognito User Pool (`PlateDetectorUsers`) | Email + password accounts; SRP auth, self sign-up with email verification |
| Cognito Identity Pool (`PlateDetectorDevices`) | Exchanges a User Pool ID token for short-lived STS credentials — no static AWS keys in the APK |
| IAM role (`DeviceAuthRole`) | Default role for authenticated Cognito identities (`android-training-data-collection-app/`): `execute-api:Invoke` on `POST /get-upload-url` and `GET /get-model-url` only. No direct S3 access. |
| Cognito group (`curators`) + IAM role (`CuratorRole`) | Gates the internal `android-training-data-reviewing-app` app (REQ-022). A signed-in `curators` member's ID token resolves — via the Identity Pool's token-based role mapping — to `CuratorRole`, which has **direct** scoped S3 access: read-only `config/` (REQ-025 category list); list/read `uploads/`, `curation/`, `done/`, `rejected/`; write `curation/`, `done/`, `rejected/`; delete `curation/` only. Never touches `uploads/` or `config/` destructively. Non-members fall back to `DeviceAuthRole` (unchanged). |

---

## Dataset curators (`android-training-data-reviewing-app` app)

The `curators` Cognito group is **not** self-service. To authorize a curator, add their existing
Cognito account to the group:

```bash
aws cognito-idp admin-add-user-to-group \
  --user-pool-id "$(aws cloudformation describe-stacks --stack-name plate-detector-upload \
      --query "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue" --output text)" \
  --username curator@example.com \
  --group-name curators
```

The user must sign out and back in (or wait for token refresh) for the new group to appear in
their ID token. To revoke, use `admin-remove-user-from-group` with the same arguments.

The `android-training-data-reviewing-app` app also needs `DatasetBucketName` from the stack outputs — paste it into
`android-training-data-reviewing-app/local.properties` as `DATASET_BUCKET_NAME` (the `COGNITO_*` values are the same
as `android-training-data-collection-app`).

### Category list (was vehicle-type classification, REQ-025 — now a single class)

Vehicle-type classification was rolled back to focus purely on plate detection; every detection is
now just `license_plate` (class id `0`). The underlying config mechanism is unchanged — the app
still reads its class list from `s3://<bucket>/config/vehicle-categories.json` (with a bundled
fallback in the APK), it's just a one-entry list now. Seed / update it:

```bash
aws s3 cp android-training-data-reviewing-app/app/src/main/assets/vehicle-categories.json \
  s3://plate-dataset-uploads/config/vehicle-categories.json
```

`CuratorRole` can read `config/*` but not write it. Class `id` is the YOLO class id and must stay
stable — append new classes, never reorder or reuse an id, if vehicle-type classification is ever
reintroduced. A package already in progress keeps using whichever category-list version was live
when it was started (embedded in its own manifest snapshot), so bumping this doesn't disrupt one.

---

## Creating the IAM admin principal

The `AdminPrincipalArn` is the IAM identity (user or role) that will be able to list and download objects from the S3 bucket — used for reviewing collected datasets. Choose one of the two options below.

### Option A — Use your existing IAM identity (simplest)

If you already have AWS CLI configured, your current identity is probably sufficient. Get its ARN:

```bash
aws sts get-caller-identity --query Arn --output text
```

Example output:
```
arn:aws:iam::826077735947:user/plate-detector-admin
```

Use this value as `AdminPrincipalArn` during deploy.

### Option B — Create a dedicated IAM user

Recommended if you want a separate identity with access scoped only to this bucket, or if multiple people need admin access.

**Step 1 — Create the user**

```bash
aws iam create-user --user-name plate-detector-admin
```

**Step 2 — Get the user ARN** (use this as `AdminPrincipalArn` during deploy)

```bash
aws iam get-user --user-name plate-detector-admin --query User.Arn --output text
```

**Step 3 — Create access keys so the user can authenticate**

```bash
aws iam create-access-key --user-name plate-detector-admin
```

Save the `AccessKeyId` and `SecretAccessKey` from the output — they are shown only once.

**Step 4 — Configure a named CLI profile for the admin user**

```bash
aws configure --profile plate-detector-admin
# Enter the AccessKeyId and SecretAccessKey from Step 3
# Region: us-east-1
```

**Step 5 — Verify access after deploy**

```bash
aws s3 ls s3://plate-dataset-uploads/uploads/ --profile plate-detector-admin
```

> **Note:** The bucket policy grants the admin principal S3 access directly, so no additional IAM policies need to be attached to the user.

---

## Deploy

### Deploy

`samconfig.toml` is gitignored (it holds your account ID and bucket name). Copy
`samconfig.toml.example` to `samconfig.toml` and fill in your own `BucketName` and
`AdminPrincipalArn`, then:

```bash
cd aws-training-infra/aws
sam build && sam deploy
```

To change any parameter (e.g. bucket name or region), edit `samconfig.toml` first, then redeploy. To be prompted interactively instead (and skip creating `samconfig.toml` by hand), run `sam deploy --guided`.

---

## Running unit tests

Tests cover the Lambda handler logic locally — no AWS account or credentials needed.

```bash
cd aws-training-infra/aws
pip install pytest boto3   # one-time
python -m pytest tests/ -v
```

Expected output: 14 tests covering happy path, filename validation, device ID sanitization, and error handling.

---

## Retrieve stack outputs

After deploy, get both values to paste into the Android app:

```bash
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs" \
  --output table
```

Or individually:

```bash
# API Gateway URL
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs[?OutputKey=='UploadServiceUrl'].OutputValue" \
  --output text

# Cognito Identity Pool ID
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs[?OutputKey=='IdentityPoolId'].OutputValue" \
  --output text
```

Paste both values into the Android app:
- **Upload server URL** → `UploadServiceUrl`
- **Identity Pool ID** → `IdentityPoolId` (format: `<region>:<uuid>`, e.g. `eu-west-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)

---

## Teardown

A `teardown.sh` script handles the full sequence — it empties the bucket (including any versioned objects) then calls `sam delete`:

```bash
cd aws-training-infra/aws
./teardown.sh
```

You will be prompted to type the bucket name to confirm before anything is deleted.

To skip the prompt (e.g. in CI):

```bash
./teardown.sh --yes
```

Override stack name or region if needed:

```bash
./teardown.sh --stack-name my-stack --region eu-west-1
```
