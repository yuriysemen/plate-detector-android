import os
import sys

# Fake AWS credentials so boto3 doesn't try to contact real credential providers
# (which would fail in CI and local envs without configured profiles).
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
os.environ.setdefault("AWS_ACCESS_KEY_ID", "testing")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "testing")
os.environ.setdefault("AWS_SESSION_TOKEN", "testing")

# Set required env vars before handler.py is imported (module-level code reads them)
os.environ.setdefault("BUCKET_NAME", "test-bucket")
os.environ.setdefault("URL_EXPIRY_SECONDS", "3600")

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../lambda/get_upload_url"))
