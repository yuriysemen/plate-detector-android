import importlib.util
import io
import json
import os
from unittest.mock import MagicMock

import pytest

# Import using a unique module name to avoid shadowing the upload handler.py
_spec = importlib.util.spec_from_file_location(
    "get_model_url_handler",
    os.path.join(os.path.dirname(__file__), "../lambda/get_model_url/handler.py"),
)
model_handler = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(model_handler)

FAKE_URL_V1 = "https://test-bucket.s3.amazonaws.com/models/v0.1.0/plate_numbers.tflite?X-Amz-Signature=fake1"
FAKE_URL_V2 = "https://test-bucket.s3.amazonaws.com/models/v0.2.0/plate_numbers.tflite?X-Amz-Signature=fake2"


def _event(app_version: str | None = "0.0.11") -> dict:
    params = {"app_version": app_version} if app_version is not None else {}
    return {"queryStringParameters": params}


def _meta(version: str, min_ver: str, max_ver: str | None = None) -> bytes:
    m = {
        "model_version":   version,
        "min_app_version": min_ver,
        "tflite_filename": "plate_numbers.tflite",
        "description":     f"Model {version}",
    }
    if max_ver is not None:
        m["max_app_version"] = max_ver
    return json.dumps(m).encode()


def _s3_with_versions(monkeypatch, versions: list[tuple]) -> MagicMock:
    """
    Build a mock S3 client.
    versions: list of (model_version, min_app_version, max_app_version | None)
    """
    m = MagicMock()

    prefixes = [{"Prefix": f"models/v{v}/"} for v, _, _ in versions]
    m.list_objects_v2.return_value = {"CommonPrefixes": prefixes}

    def get_object(Bucket, Key):  # noqa: N803
        for ver, mn, mx in versions:
            if Key == f"models/v{ver}/metadata.json":
                return {"Body": io.BytesIO(_meta(ver, mn, mx))}
        raise Exception(f"key not found: {Key}")

    m.get_object.side_effect = get_object

    fake_urls = {
        f"models/v{v}/plate_numbers.tflite": f"https://fake/{v}" for v, _, _ in versions
    }

    def presign(op, Params, ExpiresIn):  # noqa: N803
        return fake_urls.get(Params["Key"], "https://fake/unknown")

    m.generate_presigned_url.side_effect = presign
    monkeypatch.setattr(model_handler, "s3", m)
    return m


# ── Happy path ─────────────────────────────────────────────────────────────────

def test_single_version_both_fields_identical(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler(_event("0.0.11"), None)
    assert resp["statusCode"] == 200
    body = json.loads(resp["body"])
    assert body["compatible"]["model_version"] == "0.1.0"
    assert body["latest"]["model_version"] == "0.1.0"
    assert body["compatible"]["s3_key"] == body["latest"]["s3_key"]


def test_two_versions_compatible_is_lower(monkeypatch):
    # v0.2.0 requires app 1.0.0+; app is 0.0.11 → compatible=v0.1.0, latest=v0.2.0
    _s3_with_versions(monkeypatch, [
        ("0.1.0", "0.0.1",  None),
        ("0.2.0", "1.0.0",  None),
    ])
    resp = model_handler.handler(_event("0.0.11"), None)
    assert resp["statusCode"] == 200
    body = json.loads(resp["body"])
    assert body["compatible"]["model_version"] == "0.1.0"
    assert body["latest"]["model_version"] == "0.2.0"


def test_latest_is_highest_semver(monkeypatch):
    _s3_with_versions(monkeypatch, [
        ("0.1.0", "0.0.1", None),
        ("0.9.0", "0.0.1", None),
        ("0.2.0", "0.0.1", None),
    ])
    resp = model_handler.handler(_event("1.0.0"), None)
    body = json.loads(resp["body"])
    assert body["latest"]["model_version"] == "0.9.0"
    assert body["compatible"]["model_version"] == "0.9.0"


def test_compatible_none_when_app_too_old(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.2.0", "1.0.0", None)])
    resp = model_handler.handler(_event("0.0.11"), None)
    assert resp["statusCode"] == 200
    body = json.loads(resp["body"])
    assert body["compatible"] is None
    assert body["latest"]["model_version"] == "0.2.0"


def test_download_url_and_s3_key_present(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler(_event("0.0.11"), None)
    body = json.loads(resp["body"])
    comp = body["compatible"]
    assert comp["s3_key"] == "models/v0.1.0/plate_numbers.tflite"
    assert comp["download_url"].startswith("https://")
    assert comp["expires_in"] == 3600


def test_description_included(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler(_event("0.0.11"), None)
    body = json.loads(resp["body"])
    assert body["compatible"]["description"] == "Model 0.1.0"


# ── max_app_version ────────────────────────────────────────────────────────────

def test_max_app_version_excludes_model(monkeypatch):
    # v0.1.0 max_app_version=0.9.99; app is 1.0.0 → excluded from compatible
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", "0.9.99")])
    resp = model_handler.handler(_event("1.0.0"), None)
    body = json.loads(resp["body"])
    assert body["compatible"] is None
    assert body["latest"]["model_version"] == "0.1.0"


def test_max_app_version_absent_means_unlimited(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler(_event("99.0.0"), None)
    body = json.loads(resp["body"])
    assert body["compatible"]["model_version"] == "0.1.0"


def test_max_app_version_inclusive_boundary(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", "1.0.0")])
    resp = model_handler.handler(_event("1.0.0"), None)
    body = json.loads(resp["body"])
    assert body["compatible"]["model_version"] == "0.1.0"


# ── Validation ─────────────────────────────────────────────────────────────────

def test_missing_app_version_returns_400(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler({"queryStringParameters": {}}, None)
    assert resp["statusCode"] == 400
    assert "app_version" in json.loads(resp["body"])["error"]


def test_null_query_params_returns_400(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler({"queryStringParameters": None}, None)
    assert resp["statusCode"] == 400


def test_malformed_app_version_returns_400(monkeypatch):
    _s3_with_versions(monkeypatch, [("0.1.0", "0.0.1", None)])
    resp = model_handler.handler(_event("not-a-version"), None)
    assert resp["statusCode"] == 400
    assert "invalid" in json.loads(resp["body"])["error"]


def test_no_models_returns_404(monkeypatch):
    m = MagicMock()
    m.list_objects_v2.return_value = {"CommonPrefixes": []}
    monkeypatch.setattr(model_handler, "s3", m)
    resp = model_handler.handler(_event("0.0.11"), None)
    assert resp["statusCode"] == 404


def test_s3_exception_returns_500(monkeypatch):
    m = MagicMock()
    m.list_objects_v2.side_effect = Exception("S3 unavailable")
    monkeypatch.setattr(model_handler, "s3", m)
    resp = model_handler.handler(_event("0.0.11"), None)
    assert resp["statusCode"] == 500
