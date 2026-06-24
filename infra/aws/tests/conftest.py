import os
import sys

# Set required env vars before handler.py is imported (module-level code reads them)
os.environ.setdefault("BUCKET_NAME", "test-bucket")
os.environ.setdefault("URL_EXPIRY_SECONDS", "3600")

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../lambda/get_upload_url"))
