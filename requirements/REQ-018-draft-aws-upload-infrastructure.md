---
id: REQ-018
title: AWS Upload Infrastructure — S3 Bucket, Lambda, and API Gateway
status: done
priority: high
---

## Summary

Define, provision, and deploy the server-side AWS infrastructure that supports the cloud dataset upload feature (REQ-014). All infrastructure is declared as code in `infra/aws/` using AWS SAM. The API Gateway endpoint is currently **unauthenticated** — adding SigV4 / Cognito authentication is a future hardening task.

---

## Repository structure

```
infra/
  aws/
    README.md           ← deployment instructions
    template.yaml       ← AWS SAM template (S3 + Lambda + API Gateway)
    samconfig.toml      ← SAM deploy parameters (gitignored secrets filled on first deploy)
    lambda/
      get_upload_url/
        handler.py
        requirements.txt
    tests/
    .gitignore          ← excludes .aws-sam/ build artefacts
```

---

## Operator inputs (deploy-time parameters)

| Parameter | Description | Example |
|---|---|---|
| `BucketName` | Globally unique S3 bucket name | `plate-dataset-uploads` |
| `AdminPrincipalArn` | IAM user or role ARN that gets full S3 access | `arn:aws:iam::826077735947:user/plate-detector-admin` |

Optional parameters (defaults in template):

| Parameter | Default | Description |
|---|---|---|
| `PresignedUrlExpirySeconds` | `3600` | Pre-signed URL validity in seconds |
| `StageName` | `prod` | API Gateway stage name |

The AWS region is determined by the CLI profile used during `sam deploy`.

---

## Resources created

### 1. S3 bucket (`DatasetBucket`)

- Block Public Access fully enabled on all four settings.
- Server-side encryption: AES-256.
- Versioning: suspended (can be enabled post-deploy).

Bucket policy grants:
- `s3:PutObject` on `uploads/*` → Lambda execution role only.
- `s3:*` on the entire bucket → `AdminPrincipalArn`.
- No other principals; no public access.

### 2. Lambda execution role (`UploadLambdaRole`)

Permissions:
- `s3:PutObject` on `uploads/*` of the bucket.
- CloudWatch Logs write (`CreateLogGroup`, `CreateLogStream`, `PutLogEvents`).

No `GetObject`, `ListBucket`, or `DeleteObject`.

### 3. Lambda function (`GetUploadUrlFunction`)

- **Runtime:** Python 3.12
- **Timeout:** 10 seconds / **Memory:** 128 MB
- **Environment:** `BUCKET_NAME`, `URL_EXPIRY_SECONDS`

Logic:
1. Parse `filename` and `device_id` from the POST body.
2. Validate `filename` against `^[\w\-. ]+\.zip$`; return 400 on failure.
3. Strip non-hex characters from `device_id`; truncate to 32 chars.
4. Call `s3.generate_presigned_url("put_object", ...)` for key `uploads/<device_id>/<filename>`.
5. Return `{ upload_url, object_key, expires_in }`.

### 4. API Gateway (HTTP API v2, `UploadApi`)

- Route: `POST /get-upload-url` → `GetUploadUrlFunction`.
- **Auth: `AWS_IAM`** — all requests must carry a valid SigV4 signature. Unsigned requests receive HTTP 403.
- CORS: disabled (Android HTTP client does not use CORS).

### 5. Cognito Identity Pool (`DeviceIdentityPool`)

- `AllowUnauthenticatedIdentities: true` — devices obtain guest (unauthenticated) STS credentials without user login.
- `AllowClassicFlow: false` — enhanced auth flow.
- Unauthenticated IAM role (`DeviceUnauthRole`): `execute-api:Invoke` on `POST /get-upload-url` only. No S3 access.
- Role attachment (`DeviceIdentityPoolRoleAttachment`) links the role to the pool's unauthenticated identity.

The Identity Pool ID is emitted as a stack output (`IdentityPoolId`) and pasted into the Android app alongside the API URL.

### Stack outputs

| Output | Description |
|---|---|
| `UploadServiceUrl` | API Gateway invoke URL — paste into app Settings → Upload server URL |

---

## Deployment

### First deploy

```bash
cd infra/aws
pip install aws-sam-cli          # one-time
sam build
sam deploy --guided
# Enter BucketName and AdminPrincipalArn when prompted
```

### Subsequent deploys

```bash
cd infra/aws
sam build && sam deploy
```

### Retrieve stack outputs

```bash
aws cloudformation describe-stacks \
  --stack-name plate-detector-upload \
  --query "Stacks[0].Outputs" \
  --output table
```

### Teardown

```bash
sam delete --stack-name plate-detector-upload
# Empty the S3 bucket manually before stack deletion
```

---

## Security properties

| Property | Status |
|---|---|
| No static AWS credentials in APK | ✓ App stores URL + Identity Pool ID only |
| S3 Block Public Access | ✓ All four settings enabled |
| Server-side encryption (AES-256) | ✓ |
| Lambda cannot read or delete data | ✓ `PutObject` only |
| Devices cannot enumerate uploads | ✓ No `ListBucket` granted |
| Admin access scoped to one IAM principal | ✓ |
| Filename injection prevented | ✓ Lambda validates against regex |
| Device ID injection prevented | ✓ Lambda strips non-hex chars |
| API endpoint authentication | ✓ `AWS_IAM` authorizer + Cognito Identity Pool |
| Unauthenticated device role is least-privilege | ✓ `execute-api:Invoke` on one route only |

---

## Acceptance criteria

### Infrastructure
- [x] `sam build && sam deploy --guided` completes without errors when given `BucketName` and `AdminPrincipalArn`.
- [x] The S3 bucket is created private with Block Public Access fully enabled.
- [x] Server-side encryption (AES-256) is enabled on the bucket.
- [x] The `UploadServiceUrl` stack output contains a valid HTTPS URL.
- [x] The `IdentityPoolId` stack output contains the Cognito Identity Pool ID.
- [x] A `POST /get-upload-url` with a valid SigV4-signed body returns HTTP 200 and a pre-signed URL.
- [x] An unsigned `POST /get-upload-url` returns HTTP 403.
- [x] The returned pre-signed URL allows `PUT` of a `.zip` file to S3.
- [x] The pre-signed URL does not allow `GET` or `DELETE` on the same object.
- [x] A `POST /get-upload-url` with an invalid filename returns HTTP 400.
- [x] `teardown.sh` empties the bucket (including versioned objects) then calls `sam delete --no-prompts`; prompts user to type the bucket name before proceeding; supports `--yes`, `--stack-name`, `--region` flags.

### Cognito Identity Pool
- [x] Cognito Identity Pool (`PlateDetectorDevices`) is created with unauthenticated access enabled.
- [x] `GetCredentialsForIdentity` with the pool ID returns temporary STS credentials (AccessKeyId, SecretKey, SessionToken).
- [x] STS credentials can be used to sign a `POST /get-upload-url` request that succeeds.
- [x] The unauthenticated role cannot call any S3 API directly.
- [x] The unauthenticated role cannot call any API Gateway route other than `POST /get-upload-url`.

### Admin access
- [x] The IAM principal in `AdminPrincipalArn` can list and download objects in the bucket.
- [x] No other IAM principal (other than the Lambda execution role for PutObject) has S3 access.

### Developer experience
- [x] `README.md` documents deploy commands, how to retrieve both `UploadServiceUrl` and `IdentityPoolId`, and where to paste them in the app.
- [x] `samconfig.toml` is committed without secret values.
- [x] `.gitignore` excludes `.aws-sam/` build artefacts.
