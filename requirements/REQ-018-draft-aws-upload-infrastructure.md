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
- **Auth: none** (open endpoint). Future work: add IAM auth + Cognito Identity Pool (see Known gaps).
- CORS: disabled (Android HTTP client does not use CORS).

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

| Property | Current status |
|---|---|
| No static AWS credentials in APK | ✓ App only stores the API Gateway URL |
| S3 Block Public Access | ✓ All four settings enabled |
| Server-side encryption (AES-256) | ✓ |
| Lambda cannot read or delete data | ✓ `PutObject` only |
| Devices cannot enumerate uploads | ✓ No `ListBucket` granted |
| Admin access scoped to one IAM principal | ✓ |
| Filename injection prevented | ✓ Lambda validates against regex |
| Device ID injection prevented | ✓ Lambda strips non-hex chars |
| API endpoint authentication | ✗ **Currently unauthenticated — future work** |

---

## Acceptance criteria

### Infrastructure
- [x] `sam build && sam deploy --guided` completes without errors when given `BucketName` and `AdminPrincipalArn`.
- [x] The S3 bucket is created private with Block Public Access fully enabled.
- [x] Server-side encryption (AES-256) is enabled on the bucket.
- [x] The `UploadServiceUrl` stack output contains a valid HTTPS URL.
- [x] A `POST /get-upload-url` with a valid JSON body returns HTTP 200 and a pre-signed URL.
- [x] The returned pre-signed URL allows `PUT` of a `.zip` file to S3.
- [x] The pre-signed URL does not allow `GET` or `DELETE` on the same object.
- [x] A `POST /get-upload-url` with an invalid filename returns HTTP 400.
- [ ] `sam delete` tears down all resources (after bucket is emptied).

### Admin access
- [x] The IAM principal in `AdminPrincipalArn` can list and download objects in the bucket.
- [x] No other IAM principal (other than the Lambda execution role for PutObject) has S3 access.

### Developer experience
- [x] `README.md` in `infra/aws/` documents the deploy commands and how to retrieve `UploadServiceUrl`.
- [x] `samconfig.toml` is committed without secret values.
- [x] `.gitignore` excludes `.aws-sam/` build artefacts.

### Known gaps (future work)
- [ ] API Gateway route has no authentication — add IAM auth (`AWS_IAM` authorizer) and a Cognito Identity Pool so devices obtain short-lived STS credentials instead of relying on URL secrecy.
