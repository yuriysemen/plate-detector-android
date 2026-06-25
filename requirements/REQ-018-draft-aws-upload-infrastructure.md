---
id: REQ-018
title: AWS Upload Infrastructure — S3 Bucket, Lambda, API Gateway, and Cognito Identity Pool
status: draft
priority: high
---

## Summary

Define, provision, and deploy the server-side AWS infrastructure that supports the cloud dataset upload feature (REQ-014). All infrastructure is declared as code in a new `infra/aws/` folder in the repository. Deployment requires two inputs from the operator: the desired S3 bucket name and an IAM principal ARN (user or role) that will have read/manage access to the collected data. Everything else is generated automatically.

The API Gateway endpoint is protected with **AWS IAM authentication**. Android devices obtain temporary, auto-rotating STS credentials from a **Cognito Identity Pool** (guest/unauthenticated mode) and sign each request with SigV4. No static AWS credentials are stored on devices.

---

## Repository structure

```
infra/
  aws/
    README.md           ← deployment instructions (inputs, commands, outputs)
    template.yaml       ← AWS SAM template (S3 + Lambda + API Gateway)
    samconfig.toml      ← default SAM deploy parameters (gitignored values filled on first deploy)
    lambda/
      get_upload_url/
        handler.py      ← Lambda function source
        requirements.txt
    .gitignore          ← excludes .aws-sam/ build artefacts and secrets
```

**Tooling: AWS SAM (Serverless Application Model)**
- Single `sam build && sam deploy --guided` deploys the full stack.
- No Terraform or CDK dependency. SAM is AWS-native, installs via pip/brew, and requires only an AWS CLI profile already configured on the operator's machine.
- The SAM template is CloudFormation under the hood; all resources appear in the AWS console under a named CloudFormation stack.

---

## Operator inputs (deploy-time parameters)

The operator provides these two values at first deploy (`sam deploy --guided` prompts for them and writes them to `samconfig.toml` for subsequent deploys):

| Parameter | Description | Example |
|---|---|---|
| `BucketName` | Globally unique S3 bucket name | `plate-dataset-uploads` |
| `AdminPrincipalArn` | IAM user or role ARN that gets full S3 access (for data review/download) | `arn:aws:iam::826077735947:user/plate-detector-admin` |

Optional parameters (have defaults, can be overridden):

| Parameter | Default | Description |
|---|---|---|
| `AwsRegion` | `us-east-1` | S3 and Lambda region |
| `PresignedUrlExpirySeconds` | `3600` | How long a pre-signed URL is valid |
| `StageName` | `prod` | API Gateway stage name |

---

## Resources created by the SAM template

### 1. S3 bucket

```
Properties:
  BucketName: !Ref BucketName
  AccessControl: Private
  PublicAccessBlockConfiguration:
    BlockPublicAcls: true
    BlockPublicPolicy: true
    IgnorePublicAcls: true
    RestrictPublicBuckets: true
  BucketEncryption:
    ServerSideEncryptionConfiguration:
      - ServerSideEncryptionByDefault:
          SSEAlgorithm: AES256
  VersioningConfiguration:
    Status: Suspended   # can be enabled by operator after deploy
```

Bucket policy grants:
- `s3:PutObject` on `arn:aws:s3:::${BucketName}/uploads/*` → Lambda execution role only.
- Full S3 access (`s3:*`) on the entire bucket → `AdminPrincipalArn`.
- No other principals. No public access at any level.

### 2. Lambda execution role (IAM)

```
Policies:
  - s3:PutObject on uploads/* of the bucket
  - logs:CreateLogGroup, logs:CreateLogStream, logs:PutLogEvents (CloudWatch Logs)
```

No `s3:GetObject`, `s3:ListBucket`, or `s3:DeleteObject`. The Lambda can only write.

### 3. Lambda function — `GetUploadUrlFunction`

- **Runtime:** Python 3.12
- **Handler:** `get_upload_url/handler.handler`
- **Timeout:** 10 seconds
- **Memory:** 128 MB
- **Environment variables:**
  - `BUCKET_NAME` — from the `BucketName` parameter
  - `URL_EXPIRY_SECONDS` — from `PresignedUrlExpirySeconds`

**Lambda source (`lambda/get_upload_url/handler.py`):**

```python
import boto3
import json
import os
import re

s3 = boto3.client("s3")
BUCKET = os.environ["BUCKET_NAME"]
EXPIRY = int(os.environ.get("URL_EXPIRY_SECONDS", 3600))

SAFE_FILENAME = re.compile(r"^[\w\-. ]+\.zip$")  # alphanumeric, dash, dot, space, ends .zip

def handler(event, context):
    try:
        body = json.loads(event.get("body") or "{}")
        filename = body.get("filename", "")
        device_id = body.get("device_id", "unknown")

        if not SAFE_FILENAME.match(filename):
            return _response(400, {"error": "invalid filename"})

        # Sanitize device_id: keep only hex characters, max 32 chars
        safe_device = re.sub(r"[^a-f0-9]", "", device_id.lower())[:32] or "unknown"

        object_key = f"uploads/{safe_device}/{filename}"

        url = s3.generate_presigned_url(
            "put_object",
            Params={
                "Bucket": BUCKET,
                "Key": object_key,
                "ContentType": "application/zip",
            },
            ExpiresIn=EXPIRY,
        )

        return _response(200, {
            "upload_url": url,
            "object_key": object_key,
            "expires_in": EXPIRY,
        })

    except Exception as exc:
        return _response(500, {"error": str(exc)})


def _response(status, body):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }
```

### 4. API Gateway (HTTP API)

- **Type:** AWS::Serverless::HttpApi (API Gateway v2 — lower cost, lower latency than REST API).
- **Route:** `POST /get-upload-url` → `GetUploadUrlFunction`.
- **Auth:** AWS IAM (`AWS_IAM` authorizer on the route). API Gateway validates the SigV4 signature on every request. Unsigned requests receive HTTP 403.
- **CORS:** disabled (Android OkHttp does not use CORS; CORS is only needed for browser clients).
- **Throttling (optional):** can be enabled via API Gateway usage plan if abuse is a concern. Default: no throttling.
- **Output:** the API Gateway invoke URL is printed as a CloudFormation stack output (`UploadServiceUrl`) after deploy.

```yaml
# Route-level IAM auth in SAM
Events:
  GetUploadUrl:
    Type: HttpApi
    Properties:
      ApiId: !Ref UploadApi
      Path: /get-upload-url
      Method: POST
      Auth:
        Authorizer: AWS_IAM
```

### 5. Cognito Identity Pool

Provides temporary STS credentials to Android devices without requiring user accounts or embedding static keys in the APK.

- **Mode:** `AllowUnauthenticatedIdentities: true` (guest access — no login required).
- **Unauthenticated role** grants a single permission: `execute-api:Invoke` on the `POST /get-upload-url` route only. No S3 access, no Lambda invocation, no other AWS actions.
- Credentials issued by Cognito/STS are valid for ~1 hour and rotate automatically.

```yaml
UploadIdentityPool:
  Type: AWS::Cognito::IdentityPool
  Properties:
    IdentityPoolName: PlateDetectorUploaders
    AllowUnauthenticatedIdentities: true

UploadUnauthRole:
  Type: AWS::IAM::Role
  Properties:
    AssumeRolePolicyDocument:
      Version: "2012-10-17"
      Statement:
        - Effect: Allow
          Principal:
            Federated: cognito-identity.amazonaws.com
          Action: sts:AssumeRoleWithWebIdentity
          Condition:
            StringEquals:
              "cognito-identity.amazonaws.com:aud": !Ref UploadIdentityPool
            "ForAnyValue:StringLike":
              "cognito-identity.amazonaws.com:amr": unauthenticated
    Policies:
      - PolicyName: InvokeUploadApi
        PolicyDocument:
          Version: "2012-10-17"
          Statement:
            - Effect: Allow
              Action: execute-api:Invoke
              Resource: !Sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${UploadApi}/${StageName}/POST/get-upload-url"

IdentityPoolRoleAttachment:
  Type: AWS::Cognito::IdentityPoolRoleAttachment
  Properties:
    IdentityPoolId: !Ref UploadIdentityPool
    Roles:
      unauthenticated: !GetAtt UploadUnauthRole.Arn
```

---

## Deployment workflow

### First deploy

```bash
cd infra/aws
pip install aws-sam-cli          # one-time if not already installed
sam build
sam deploy --guided
# Follow prompts: enter BucketName, AdminPrincipalArn, confirm remaining defaults
# SAM writes choices to samconfig.toml for future deploys
```

### Subsequent deploys (after Lambda code changes)

```bash
cd infra/aws
sam build && sam deploy
```

### Retrieve the stack outputs

```bash
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs" \
  --output table
```

Two values are needed by the Android app:

- **`UploadServiceUrl`** — paste into Settings → Export → Upload server URL.
- **`CognitoIdentityPoolId`** — paste into Settings → Export → Identity Pool ID.

### Teardown

```bash
sam delete --stack-name plate-detector-upload
# Note: S3 bucket must be emptied manually before stack deletion
```

---

## Security properties

| Property | Design |
|---|---|
| API endpoint requires authentication | All requests must be SigV4-signed; unsigned requests receive HTTP 403 |
| No static credentials in APK | App stores Identity Pool ID (not a secret) + API URL; no AWS keys |
| Temporary credentials only | Cognito/STS issues credentials valid for ~1 hour; no long-lived keys on device |
| Device permissions are minimal | Unauthenticated role grants `execute-api:Invoke` on one route only |
| No public S3 access | Block Public Access enabled on bucket |
| Devices cannot read data | Pre-signed URL allows `PutObject` on one path only; no `GetObject` |
| Devices cannot enumerate uploads | No `ListBucket` granted anywhere in the bucket policy |
| Lambda cannot read data | Execution role grants `PutObject` only |
| Admin access scoped to one IAM principal | `AdminPrincipalArn` is the only entity with broad S3 access |
| Filename injection prevented | Lambda validates filename against `^[\w\-. ]+\.zip$` regex |
| Device ID injection prevented | Lambda strips non-hex characters from `device_id` |

---

## Acceptance criteria

### Infrastructure
- [ ] `sam build && sam deploy --guided` completes without errors when given `BucketName` and `AdminPrincipalArn`.
- [x] The S3 bucket is created private with Block Public Access fully enabled.
- [x] Server-side encryption (AES-256) is enabled on the bucket.
- [x] The `UploadServiceUrl` stack output contains a valid HTTPS URL.
- [ ] The `CognitoIdentityPoolId` stack output contains a valid identity pool ID in `region:uuid` format.
- [ ] A SigV4-signed `POST /get-upload-url` with a valid body returns HTTP 200 and a pre-signed URL.
- [ ] An unsigned `POST /get-upload-url` returns HTTP 403.
- [ ] The returned pre-signed URL allows `PUT` of a `.zip` file to S3 (verified by uploading a test file via `curl`).
- [x] The pre-signed URL does not allow `GET` or `DELETE` on the same object.
- [x] A `POST /get-upload-url` with an invalid filename (e.g. `../../etc/passwd`) returns HTTP 400.
- [ ] `sam delete` tears down all resources (after bucket is emptied).

### Cognito Identity Pool
- [ ] The identity pool allows unauthenticated (guest) access.
- [ ] Credentials obtained from the identity pool can sign a request to `POST /get-upload-url` successfully.
- [ ] Credentials obtained from the identity pool cannot invoke any other AWS action (verified by attempting `s3:ListBuckets` — expect `AccessDenied`).
- [ ] Credentials expire after ~1 hour and are refreshed transparently by `CognitoCachingCredentialsProvider`.

### Admin access
- [x] The IAM principal specified in `AdminPrincipalArn` can list and download objects in the bucket.
- [x] No other IAM principal (other than the Lambda execution role for PutObject) has any S3 access.

### Developer experience
- [ ] `README.md` in `infra/aws/` documents the two required inputs, the deploy commands, and how to retrieve both `UploadServiceUrl` and `CognitoIdentityPoolId`.
- [x] `samconfig.toml` is committed without secret values; `BucketName` and `AdminPrincipalArn` are re-entered on first deploy on a new machine.
- [x] `.gitignore` excludes `.aws-sam/` build artefacts.
