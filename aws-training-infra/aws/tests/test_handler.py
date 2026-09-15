import json
from unittest.mock import MagicMock

import pytest

import handler

FAKE_URL = (
    "https://test-bucket.s3.eu-west-1.amazonaws.com"
    "/uploads/550e8400-e29b-41d4-a716-446655440000/a3f8c1d4e9b2f7a0/dataset.zip?X-Amz-Signature=fake"
)

VALID_USER_ID = "550e8400-e29b-41d4-a716-446655440000"  # Cognito sub (UUID)
VALID_DEVICE_ID = "a3f8c1d4e9b2f7a0"


def _event(body: dict) -> dict:
    return {"body": json.dumps(body)}


def _valid_body(**overrides) -> dict:
    base = {"filename": "dataset.zip", "device_id": VALID_DEVICE_ID, "user_id": VALID_USER_ID}
    base.update(overrides)
    return base


@pytest.fixture
def mock_s3(monkeypatch):
    m = MagicMock()
    m.generate_presigned_url.return_value = FAKE_URL
    monkeypatch.setattr(handler, "s3", m)
    return m


# ── Happy path ────────────────────────────────────────────────────────────────

def test_valid_request_returns_200(mock_s3):
    response = handler.handler(_event(_valid_body()), None)

    assert response["statusCode"] == 200
    body = json.loads(response["body"])
    assert body["upload_url"] == FAKE_URL
    assert body["object_key"] == f"uploads/{VALID_USER_ID}/{VALID_DEVICE_ID}/dataset.zip"
    assert body["expires_in"] == 3600


def test_presigned_url_uses_put_object(mock_s3):
    handler.handler(_event(_valid_body()), None)

    operation = mock_s3.generate_presigned_url.call_args[0][0]
    assert operation == "put_object"


def test_presigned_url_content_type_is_zip(mock_s3):
    handler.handler(_event(_valid_body()), None)

    params = mock_s3.generate_presigned_url.call_args[1]["Params"]
    assert params["ContentType"] == "application/zip"


def test_object_key_has_uploads_prefix(mock_s3):
    handler.handler(_event(_valid_body()), None)

    params = mock_s3.generate_presigned_url.call_args[1]["Params"]
    assert params["Key"].startswith("uploads/")


def test_object_key_contains_user_and_device(mock_s3):
    handler.handler(_event(_valid_body()), None)

    params = mock_s3.generate_presigned_url.call_args[1]["Params"]
    assert f"uploads/{VALID_USER_ID}/{VALID_DEVICE_ID}/" in params["Key"]


# ── Filename validation ───────────────────────────────────────────────────────

def test_path_traversal_filename_returns_400(mock_s3):
    response = handler.handler(_event(_valid_body(filename="../../etc/passwd")), None)

    assert response["statusCode"] == 400
    assert "invalid filename" in response["body"]


def test_wrong_extension_returns_400(mock_s3):
    response = handler.handler(_event(_valid_body(filename="dataset.tar.gz")), None)

    assert response["statusCode"] == 400


def test_empty_filename_returns_400(mock_s3):
    response = handler.handler(_event(_valid_body(filename="")), None)

    assert response["statusCode"] == 400


def test_missing_filename_returns_400(mock_s3):
    body = {"device_id": VALID_DEVICE_ID, "user_id": VALID_USER_ID}
    response = handler.handler(_event(body), None)

    assert response["statusCode"] == 400


# ── user_id validation ────────────────────────────────────────────────────────

def test_missing_user_id_returns_400(mock_s3):
    body = {"filename": "dataset.zip", "device_id": VALID_DEVICE_ID}
    response = handler.handler(_event(body), None)

    assert response["statusCode"] == 400
    assert "user_id" in response["body"]


def test_empty_user_id_returns_400(mock_s3):
    response = handler.handler(_event(_valid_body(user_id="")), None)

    assert response["statusCode"] == 400


def test_all_invalid_chars_in_user_id_returns_400(mock_s3):
    response = handler.handler(_event(_valid_body(user_id="!@#$%^&*()")), None)

    assert response["statusCode"] == 400
    assert "invalid user_id" in response["body"]


def test_user_id_uppercased_is_lowercased(mock_s3):
    upper_uuid = VALID_USER_ID.upper()
    response = handler.handler(_event(_valid_body(user_id=upper_uuid)), None)

    assert response["statusCode"] == 200
    key = json.loads(response["body"])["object_key"]
    assert VALID_USER_ID in key  # lowercased in output


def test_user_id_truncated_to_36_chars(mock_s3):
    long_id = "a" * 50
    response = handler.handler(_event(_valid_body(user_id=long_id)), None)

    assert response["statusCode"] == 200
    key = json.loads(response["body"])["object_key"]
    safe_user = key.split("/")[1]
    assert len(safe_user) == 36


def test_user_id_special_chars_stripped(mock_s3):
    response = handler.handler(_event(_valid_body(user_id="user@example.com")), None)

    assert response["statusCode"] == 200
    key = json.loads(response["body"])["object_key"]
    assert "@" not in key
    assert "example.com" not in key


# ── Null / missing body ───────────────────────────────────────────────────────

def test_null_body_returns_400(mock_s3):
    response = handler.handler({"body": None}, None)

    assert response["statusCode"] == 400


# ── device_id sanitization ────────────────────────────────────────────────────

def test_non_hex_chars_stripped_from_device_id(mock_s3):
    response = handler.handler(_event(_valid_body(device_id="DEVICE-XYZ-a3f8c1d4")), None)

    assert response["statusCode"] == 200
    key = json.loads(response["body"])["object_key"]
    assert "DEVICE" not in key
    assert "XYZ" not in key
    assert "a3f8c1d4" in key


def test_device_id_truncated_to_32_chars(mock_s3):
    response = handler.handler(_event(_valid_body(device_id="a" * 64)), None)

    key = json.loads(response["body"])["object_key"]
    safe_device = key.split("/")[2]
    assert len(safe_device) == 32


def test_all_non_hex_device_id_falls_back_to_unknown(mock_s3):
    response = handler.handler(_event(_valid_body(device_id="ZZZZZZZZ")), None)

    key = json.loads(response["body"])["object_key"]
    assert key.split("/")[2] == "unknown"


def test_missing_device_id_falls_back_to_unknown(mock_s3):
    body = {"filename": "dataset.zip", "user_id": VALID_USER_ID}
    response = handler.handler(_event(body), None)

    key = json.loads(response["body"])["object_key"]
    assert key.split("/")[2] == "unknown"


# ── Error handling ────────────────────────────────────────────────────────────

def test_s3_exception_returns_500(mock_s3):
    mock_s3.generate_presigned_url.side_effect = Exception("S3 unavailable")

    response = handler.handler(_event(_valid_body()), None)

    assert response["statusCode"] == 500
    assert "S3 unavailable" in response["body"]
