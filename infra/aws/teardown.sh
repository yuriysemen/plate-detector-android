#!/usr/bin/env bash
# Empties the S3 bucket created by this stack, then deletes the stack.
# Must be run from the infra/aws/ directory.
#
# Usage:
#   ./teardown.sh [--stack-name <name>] [--region <region>] [--yes]
#
# --yes   Skip the confirmation prompt (useful in CI).

set -euo pipefail

STACK_NAME="plate-detector-upload"
REGION="us-east-1"
SKIP_CONFIRM=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stack-name) STACK_NAME="$2"; shift 2 ;;
    --region)     REGION="$2";     shift 2 ;;
    --yes)        SKIP_CONFIRM=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

echo "Stack : $STACK_NAME"
echo "Region: $REGION"
echo ""

# ── Resolve bucket name from stack parameters ──────────────────────────────
BUCKET_NAME=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --query "Stacks[0].Parameters[?ParameterKey=='BucketName'].ParameterValue" \
  --output text 2>/dev/null || true)

if [[ -z "$BUCKET_NAME" ]]; then
  echo "ERROR: Could not find stack '$STACK_NAME' in region '$REGION'." >&2
  echo "       Check that the stack exists and your AWS credentials are configured." >&2
  exit 1
fi

echo "Bucket: $BUCKET_NAME"
echo ""

# ── Confirmation prompt ────────────────────────────────────────────────────
if [[ "$SKIP_CONFIRM" == false ]]; then
  echo "This will permanently delete all uploaded datasets and the entire stack."
  read -r -p "Type the bucket name to confirm deletion: " CONFIRM
  if [[ "$CONFIRM" != "$BUCKET_NAME" ]]; then
    echo "Cancelled — name did not match." >&2
    exit 1
  fi
  echo ""
fi

# ── Empty the bucket (including all object versions if versioning is on) ───
echo "Emptying s3://$BUCKET_NAME ..."

# Delete current objects
OBJECT_COUNT=$(aws s3 ls "s3://$BUCKET_NAME" --recursive --region "$REGION" 2>/dev/null \
  | wc -l | tr -d ' ')

if [[ "$OBJECT_COUNT" -gt 0 ]]; then
  aws s3 rm "s3://$BUCKET_NAME" --recursive --region "$REGION"
  echo "Deleted $OBJECT_COUNT object(s)."
else
  echo "Bucket is already empty."
fi

# Delete any delete markers / non-current versions (in case versioning was ever enabled)
aws s3api list-object-versions \
  --bucket "$BUCKET_NAME" \
  --region "$REGION" \
  --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' \
  --output json 2>/dev/null \
| python3 -c "
import sys, json, subprocess, shlex
data = json.load(sys.stdin)
objs = [o for o in (data.get('Objects') or []) if o.get('VersionId')]
if not objs:
    sys.exit(0)
payload = json.dumps({'Objects': objs, 'Quiet': True})
cmd = ['aws', 's3api', 'delete-objects',
       '--bucket', '$BUCKET_NAME', '--region', '$REGION',
       '--delete', payload]
subprocess.run(cmd, check=True)
print(f'Deleted {len(objs)} object version(s).')
" || true

aws s3api list-object-versions \
  --bucket "$BUCKET_NAME" \
  --region "$REGION" \
  --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' \
  --output json 2>/dev/null \
| python3 -c "
import sys, json, subprocess
data = json.load(sys.stdin)
markers = [o for o in (data.get('Objects') or []) if o.get('VersionId')]
if not markers:
    sys.exit(0)
payload = json.dumps({'Objects': markers, 'Quiet': True})
cmd = ['aws', 's3api', 'delete-objects',
       '--bucket', '$BUCKET_NAME', '--region', '$REGION',
       '--delete', payload]
subprocess.run(cmd, check=True)
print(f'Deleted {len(markers)} delete marker(s).')
" || true

echo ""

# ── Delete the CloudFormation stack via SAM ────────────────────────────────
echo "Deleting stack '$STACK_NAME' ..."
sam delete \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --no-prompts

echo ""
echo "Done. Stack and bucket deleted."
