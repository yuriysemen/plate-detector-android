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
        user_id = body.get("user_id", "")

        if not SAFE_FILENAME.match(filename):
            return _response(400, {"error": "invalid filename"})

        if not user_id:
            return _response(400, {"error": "user_id is required"})

        # Sanitize device_id: keep only hex characters, max 32 chars
        safe_device = re.sub(r"[^a-f0-9]", "", device_id.lower())[:32] or "unknown"

        # Sanitize user_id: Cognito sub is a UUID — keep alphanumeric and hyphens, max 36 chars
        safe_user = re.sub(r"[^a-z0-9\-]", "", user_id.lower())[:36] or None
        if not safe_user:
            return _response(400, {"error": "invalid user_id"})

        object_key = f"uploads/{safe_user}/{safe_device}/{filename}"

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
