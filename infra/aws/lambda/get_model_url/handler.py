import boto3
import json
import os
import re

s3 = boto3.client("s3")
BUCKET = os.environ["BUCKET_NAME"]
EXPIRY = int(os.environ.get("URL_EXPIRY_SECONDS", 3600))

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")


def _parse_semver(version: str):
    parts = version.split(".")
    return (int(parts[0]), int(parts[1]), int(parts[2]))


def _semver_lte(a: str, b: str) -> bool:
    """Return True if semver a <= b."""
    return _parse_semver(a) <= _parse_semver(b)


def handler(event, context):
    try:
        params = event.get("queryStringParameters") or {}
        app_version = params.get("app_version", "").strip()

        if not app_version:
            return _response(400, {"error": "app_version is required"})
        if not SEMVER.match(app_version):
            return _response(400, {"error": "invalid app_version"})

        # List version folders under models/
        result = s3.list_objects_v2(Bucket=BUCKET, Prefix="models/", Delimiter="/")
        prefixes = [cp["Prefix"] for cp in result.get("CommonPrefixes", [])]

        if not prefixes:
            return _response(404, {"error": "no models available"})

        # Fetch metadata for each version folder
        entries = []
        for prefix in prefixes:
            try:
                obj = s3.get_object(Bucket=BUCKET, Key=f"{prefix}metadata.json")
                meta = json.loads(obj["Body"].read())
                entries.append({
                    "model_version":   meta["model_version"],
                    "min_app_version": meta["min_app_version"],
                    "max_app_version": meta.get("max_app_version"),
                    "tflite_filename": meta["tflite_filename"],
                    "description":     meta.get("description", ""),
                    "prefix":          prefix,
                })
            except Exception:
                continue  # skip malformed/missing metadata

        if not entries:
            return _response(404, {"error": "no models available"})

        # Sort by semver descending
        entries.sort(key=lambda e: _parse_semver(e["model_version"]), reverse=True)

        latest = entries[0]
        compatible = next(
            (
                e for e in entries
                if _semver_lte(e["min_app_version"], app_version)
                and (
                    e["max_app_version"] is None
                    or _semver_lte(app_version, e["max_app_version"])
                )
            ),
            None,
        )

        def _build_entry(e):
            s3_key = f"{e['prefix']}{e['tflite_filename']}"
            url = s3.generate_presigned_url(
                "get_object",
                Params={"Bucket": BUCKET, "Key": s3_key},
                ExpiresIn=EXPIRY,
            )
            return {
                "model_version":   e["model_version"],
                "tflite_filename": e["tflite_filename"],
                "s3_key":          s3_key,
                "download_url":    url,
                "expires_in":      EXPIRY,
                "description":     e["description"],
            }

        return _response(200, {
            "compatible": _build_entry(compatible) if compatible else None,
            "latest":     _build_entry(latest),
        })

    except Exception as exc:
        return _response(500, {"error": str(exc)})


def _response(status, body):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }
