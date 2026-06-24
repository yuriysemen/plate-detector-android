# Plate Detector — AWS Upload Infrastructure

AWS SAM stack that backs the cloud dataset upload feature (REQ-014).
Creates an S3 bucket, a Lambda function, and an API Gateway HTTP API.

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

`samconfig.toml` already contains the bucket name, region, and admin ARN, so no interactive prompts are needed:

```bash
cd infra/aws
sam build && sam deploy
```

To change any parameter (e.g. bucket name or region), edit `samconfig.toml` first, then redeploy. To be prompted interactively instead, run `sam deploy --guided`.

---

## Running unit tests

Tests cover the Lambda handler logic locally — no AWS account or credentials needed.

```bash
cd infra/aws
pip install pytest boto3   # one-time
python -m pytest tests/ -v
```

Expected output: 14 tests covering happy path, filename validation, device ID sanitization, and error handling.

---

## Retrieve the upload URL

After deploy, get the API Gateway URL to paste into the Android app:

```bash
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs[?OutputKey=='UploadServiceUrl'].OutputValue" \
  --output text
```

Paste the result into **Settings → Export → Upload server URL** in the Android app.

---

## Teardown

```bash
# Empty the bucket first (SAM cannot delete a non-empty bucket)
aws s3 rm s3://plate-dataset-uploads --recursive

sam delete --stack-name plate-detector-upload
```
