import json
from unittest.mock import MagicMock

import pytest

import handler

FAKE_URL = (
    "https://test-bucket.s3.eu-west-1.amazonaws.com"
    "/uploads/a3f8c1d4e9b2f7a0/dataset.zip?X-Amz-Signature=fake"
)


def _event(body: dict) -> dict:
    return {"body": json.dumps(body)}


@pytest.fixture
def mock_s3(monkeypatch):
    m = MagicMock()
    m.generate_presigned_url.return_value = FAKE_URL
    monkeypatch.setattr(handler, "s3", m)
    return m


# ── Happy path ────────────────────────────────────────────────────────────────

def test_valid_request_returns_200(mock_s3):
    response = handler.handler(_event({"filename": "dataset.zip", "device_id": "a3f8c1d4e9b2f7a0"}), None)

    assert response["statusCode"] == 200
    body = json.loads(response["body"])
    assert body["upload_url"] == FAKE_URL
    assert body["object_key"] == "uploads/a3f8c1d4e9b2f7a0/dataset.zip"
    assert body["expires_in"] == 3600


def test_presigned_url_uses_put_object(mock_s3):
    handler.handler(_event({"filename": "dataset.zip", "device_id": "abc"}), None)

    operation = mock_s3.generate_presigned_url.call_args[0][0]
    assert operation == "put_object"


def test_presigned_url_content_type_is_zip(mock_s3):
    handler.handler(_event({"filename": "dataset.zip", "device_id": "abc"}), None)

    params = mock_s3.generate_presigned_url.call_args[1]["Params"]
    assert params["ContentType"] == "application/zip"


def test_object_key_has_uploads_prefix(mock_s3):
    handler.handler(_event({"filename": "dataset.zip", "device_id": "abc123"}), None)

    params = mock_s3.generate_presigned_url.call_args[1]["Params"]
    assert params["Key"].startswith("uploads/")


# ── Filename validation ───────────────────────────────────────────────────────

def test_path_traversal_filename_returns_400(mock_s3):
    response = handler.handler(_event({"filename": "../../etc/passwd", "device_id": "abc"}), None)

    assert response["statusCode"] == 400
    assert "invalid filename" in response["body"]


def test_wrong_extension_returns_400(mock_s3):
    response = handler.handler(_event({"filename": "dataset.tar.gz", "device_id": "abc"}), None)

    assert response["statusCode"] == 400


def test_empty_filename_returns_400(mock_s3):
    response = handler.handler(_event({"filename": "", "device_id": "abc"}), None)

    assert response["statusCode"] == 400


def test_missing_filename_returns_400(mock_s3):
    response = handler.handler(_event({"device_id": "abc"}), None)

    assert response["statusCode"] == 400


# ── Null / missing body ───────────────────────────────────────────────────────

def test_null_body_returns_400(mock_s3):
    # event.body = None is valid from API Gateway when no body is sent
    response = handler.handler({"body": None}, None)

    assert response["statusCode"] == 400


# ── device_id sanitization ────────────────────────────────────────────────────

def test_non_hex_chars_stripped_from_device_id(mock_s3):
    response = handler.handler(_event({"filename": "dataset.zip", "device_id": "DEVICE-XYZ-a3f8c1d4"}), None)

    assert response["statusCode"] == 200
    key = json.loads(response["body"])["object_key"]
    assert "DEVICE" not in key
    assert "XYZ" not in key
    assert "a3f8c1d4" in key


def test_device_id_truncated_to_32_chars(mock_s3):
    response = handler.handler(_event({"filename": "dataset.zip", "device_id": "a" * 64}), None)

    key = json.loads(response["body"])["object_key"]
    safe_device = key.split("/")[1]
    assert len(safe_device) == 32


def test_all_non_hex_device_id_falls_back_to_unknown(mock_s3):
    response = handler.handler(_event({"filename": "dataset.zip", "device_id": "ZZZZZZZZ"}), None)

    key = json.loads(response["body"])["object_key"]
    assert key.startswith("uploads/unknown/")


def test_missing_device_id_falls_back_to_unknown(mock_s3):
    response = handler.handler(_event({"filename": "dataset.zip"}), None)

    key = json.loads(response["body"])["object_key"]
    assert key.startswith("uploads/unknown/")


# ── Error handling ────────────────────────────────────────────────────────────

def test_s3_exception_returns_500(mock_s3):
    mock_s3.generate_presigned_url.side_effect = Exception("S3 unavailable")

    response = handler.handler(_event({"filename": "dataset.zip", "device_id": "abc"}), None)

    assert response["statusCode"] == 500
    assert "S3 unavailable" in response["body"]
