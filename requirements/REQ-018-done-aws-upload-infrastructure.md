---
id: REQ-018
title: AWS Upload Infrastructure — S3 Bucket, Lambda, API Gateway, and Cognito Auth
status: done
priority: high
---

> **Implemented in `android-end-user-app/` originally; as of [REQ-031](REQ-031-done-split-detection-and-training-apps.md) this functionality lives in `android-training-data-collection-app/`, not `android-end-user-app/`.**

## Summary

Define, provision, and deploy the server-side AWS infrastructure that supports the cloud dataset upload feature (REQ-014). All infrastructure is declared as code in `aws-training-infra/aws/` using AWS SAM. The API Gateway endpoint is currently **unauthenticated** — adding SigV4 / Cognito authentication is a future hardening task.

---

## Repository structure

```
aws-training-infra/
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
| `AdminPrincipalArn` | IAM user or role ARN that gets full S3 access | `arn:aws:iam::123456789012:user/plate-detector-admin` |

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
1. Parse `filename`, `device_id`, and `user_id` from the POST body.
2. Validate `filename` against `^[\w\-. ]+\.zip$`; return 400 on failure.
3. Return 400 if `user_id` is missing or empty.
4. Sanitize `user_id` (Cognito sub — UUID format): strip non-alphanumeric/hyphen chars, lowercase, max 36 chars; return 400 if nothing remains.
5. Sanitize `device_id`: strip non-hex chars, max 32 chars; fall back to `"unknown"`.
6. Call `s3.generate_presigned_url("put_object", ...)` for key `uploads/<user_id>/<device_id>/<filename>`.
7. Return `{ upload_url, object_key, expires_in }`.

### 4. API Gateway (HTTP API v2, `UploadApi`)

- Route: `POST /get-upload-url` → `GetUploadUrlFunction`.
- **Auth: `AWS_IAM`** — all requests must carry a valid SigV4 signature. Unsigned requests receive HTTP 403.
- CORS: disabled (Android HTTP client does not use CORS).

### 5. Cognito User Pool (`UserPool` — `PlateDetectorUsers`)

- Sign-in identifier: email.
- Self-registration allowed (`AllowAdminCreateUserOnly: false`).
- Email verification required before account is active.
- Password policy: min 8 chars, upper + lower + digits required.
- Account recovery: email only.

### 6. Cognito User Pool App Client (`UserPoolClient` — `PlateDetectorAndroid`)

- No client secret (mobile apps cannot keep secrets).
- Auth flows: `ALLOW_USER_SRP_AUTH`, `ALLOW_REFRESH_TOKEN_AUTH`.
- `PreventUserExistenceErrors: ENABLED` — sign-in errors do not reveal whether an email is registered.
- Token validity: access token 1 h, ID token 1 h, refresh token 30 days.

### 7. Cognito Identity Pool (`DeviceIdentityPool`)

- Linked to the User Pool via `CognitoIdentityProviders`.
- `AllowUnauthenticatedIdentities: false` — a signed-in account is required; unauthenticated access is disabled.
- `AllowClassicFlow: false` — enhanced auth flow.
- `ServerSideTokenCheck: true` — Cognito validates the token server-side before issuing credentials.
- Authenticated IAM role (`DeviceAuthRole`): `execute-api:Invoke` on `POST /get-upload-url` only. No direct S3 access.

### Stack outputs

| Output | Description |
|---|---|
| `UploadServiceUrl` | API Gateway invoke URL — set as `UPLOAD_SERVICE_URL` in `android-end-user-app/local.properties` |
| `UserPoolId` | Cognito User Pool ID — set as `COGNITO_USER_POOL_ID` in `android-end-user-app/local.properties` |
| `UserPoolClientId` | Cognito App Client ID — set as `COGNITO_APP_CLIENT_ID` in `android-end-user-app/local.properties` |
| `IdentityPoolId` | Cognito Identity Pool ID — set as `COGNITO_IDENTITY_POOL_ID` in `android-end-user-app/local.properties` |

---

## Deployment

### First deploy

```bash
cd aws-training-infra/aws
pip install aws-sam-cli          # one-time
sam build
sam deploy --guided
# Enter BucketName and AdminPrincipalArn when prompted
```

### Subsequent deploys

```bash
cd aws-training-infra/aws
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
| No static AWS credentials in APK | ✓ App stores URL + 3 Cognito IDs only |
| S3 Block Public Access | ✓ All four settings enabled |
| Server-side encryption (AES-256) | ✓ |
| Lambda cannot read or delete data | ✓ `PutObject` only |
| Devices cannot enumerate uploads | ✓ No `ListBucket` granted |
| Admin access scoped to one IAM principal | ✓ |
| Filename injection prevented | ✓ Lambda validates against regex |
| Device ID injection prevented | ✓ Lambda strips non-hex chars |
| User ID injection prevented | ✓ Lambda strips non-UUID chars |
| API endpoint requires authentication | ✓ `AWS_IAM` authorizer — unsigned requests get HTTP 403 |
| Upload requires a verified user account | ✓ Cognito User Pool, email verification required |
| Unauthenticated access disabled | ✓ `AllowUnauthenticatedIdentities: false` |
| Authenticated role is least-privilege | ✓ `execute-api:Invoke` on one route only |
| Per-user data isolation in S3 | ✓ `uploads/<user_sub>/<device_id>/<filename>` |

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

### Cognito User Pool
- [x] User Pool (`PlateDetectorUsers`) created with email sign-in and self-registration.
- [x] Email verification required before account is active.
- [x] App Client (`PlateDetectorAndroid`) has no secret; uses SRP auth + refresh token.
- [x] `PreventUserExistenceErrors` enabled — sign-in failures don't reveal whether an email is registered.

### Cognito Identity Pool
- [x] Identity Pool linked to the User Pool via `CognitoIdentityProviders` with `ServerSideTokenCheck: true`.
- [x] `AllowUnauthenticatedIdentities: false` — unauthenticated access is disabled.
- [x] A valid User Pool ID token can be exchanged for STS credentials via `GetCredentialsForIdentity`.
- [x] STS credentials can be used to SigV4-sign a `POST /get-upload-url` request that succeeds.
- [x] An unauthenticated request to `POST /get-upload-url` returns HTTP 403.
- [x] The authenticated role cannot call any S3 API directly.
- [x] The authenticated role cannot call any API Gateway route other than `POST /get-upload-url`.

### Lambda / S3 path
- [x] Lambda requires `user_id` in the request body; missing or invalid returns HTTP 400.
- [x] S3 object key follows `uploads/<user_sub>/<device_id>/<filename>`.
- [x] `user_id` is sanitized (non-UUID chars stripped, max 36 chars).
- [x] 21 unit tests passing (handler logic tested locally without AWS).

### Admin access
- [x] The IAM principal in `AdminPrincipalArn` can list and download objects in the bucket.
- [x] No other IAM principal (other than the Lambda execution role for PutObject) has S3 access.

### Developer experience
- [x] `README.md` documents deploy commands, how to retrieve stack outputs, and how to set them as `local.properties` keys so the Android build embeds them via `BuildConfig`.
- [x] `samconfig.toml` is committed without secret values.
- [x] `.gitignore` excludes `.aws-sam/` build artefacts.
