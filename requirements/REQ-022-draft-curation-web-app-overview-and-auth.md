---
id: REQ-022
title: Dataset Curation Web App — Overview, Authentication & AWS Infrastructure
status: draft
priority: high
depends_on: REQ-013, REQ-018
---

## Summary

A new Angular single-page app, internal-only, used by trusted curators (not end users) to turn raw
uploaded YOLO packages sitting in S3 `uploads/` into a verified, training-ready dataset in S3
`done/`. It authenticates against the same Cognito User Pool the Android app uses, but only
members of a new `curators` group can use it. **The browser never talks to S3 directly.** Every
data operation (listing, claiming, editing, completing) goes through a new set of Lambda functions
behind the existing API Gateway, the same pre-signed-URL-issuing pattern the Android app already
uses for uploads — just with a richer set of routes. This means the backend, not the frontend
bundle, enforces every business rule (who owns a claim, whether it's expired, valid state
transitions, etc.).

This formalizes the "separate editing app" mentioned as out of scope in REQ-001's Decisions
section ("A separate editing app will handle reviewing collected frames, correcting annotations,
and flagging false positives").

---

## Goals

- Reuse 100% of existing Cognito infrastructure (User Pool, Identity Pool) — no parallel auth system.
- Gate access to a small set of trusted curators via a Cognito group, not all app users.
- Mediate every data operation through dedicated Lambda functions so the backend enforces business
  rules (ownership, claim expiry, valid state transitions, the zero-box rule, etc.) regardless of
  what the frontend bundle does or is tricked into doing.
- Give the curator's browser **zero direct AWS data-plane permissions** — its only capability is
  invoking the curation API routes, exactly like the Android app's `DeviceAuthRole` can only invoke
  `get-upload-url`.
- Keep the original uploaded ZIPs in `uploads/` immutable and untouched — curation never deletes
  or modifies a user's original submission.
- Make every curation action attributable (which curator did what, when) for audit purposes, with
  the curator's identity resolved server-side rather than trusted from client input.

## Non-goals

- Self-service sign-up for curators — accounts are added to the `curators` group manually by an
  operator (same manual-provisioning pattern as `AdminPrincipalArn` in REQ-018).
- Any change to the Android app or its existing upload/model-update Lambda endpoints.
- Automated quality scoring or ML-assisted review — purely manual curation in this version.
- Multi-curator review/approval workflow (second reviewer sign-off) — a single curator's decision
  is final in this version.

---

## Bucket prefix layout (extends REQ-018's bucket)

```
<bucket>/
  uploads/<user_sub>/<device_id>/<filename>.zip     ← existing, untouched, "not processed"
  models/...                                         ← existing (REQ-016)
  processing/
    _claims/<package_id>.json                        ← claim markers, one per in-progress package
    <curator_sub>/<package_id>/
      train/{images,labels}/...                      ← unreviewed working copy
      val/{images,labels}/...
      test/{images,labels}/...
      reviewed/<subset>/{images,labels}/...           ← marked-ready pairs, moved here immediately
      rejected/<subset>/{images,labels}/...           ← marked-rejected pairs, moved here immediately
      manifest.json                                   ← per-item status, resumable progress
  done/<package_id>/
    train/{images,labels}/...
    val/{images,labels}/...
    test/{images,labels}/...
    data.yaml                                          ← copied from original package
    _manifest.json                                     ← curator, started_at, completed_at, counts
  rejected/<package_id>/
    <subset>/{images,labels}/...
```

`package_id` = sanitized `<user_sub>__<device_id>__<filename-without-zip>` — deterministic from the
source ZIP's S3 key, so a curated package can always be traced back to its original upload and
device. All of this structure is now **internal to the Lambda functions** — the browser never
addresses an S3 key directly except via a short-lived, single-object pre-signed URL that a Lambda
hands it.

---

## Authentication & authorization

### Sign-in

Identical mechanism to the Android app (REQ-014/REQ-018): email + password against the same
Cognito User Pool, SRP auth flow via the Amplify/Cognito JS SDK. The ID token is exchanged for
temporary STS credentials via the existing Identity Pool, which the browser uses to SigV4-sign
requests to the curation API — exactly how `UploadDatasetWorker` signs `POST /get-upload-url`
today.

### New Cognito group: `curators`

- Created in the existing `PlateDetectorUsers` User Pool.
- Membership managed manually (`aws cognito-idp admin-add-user-to-group` or console) — no
  self-service.
- The web app checks `cognito:groups` in the decoded ID token after sign-in; users not in the
  group see an "access denied" screen and don't attempt API calls.

### New IAM role: `CuratorApiRole`

- New role mapping on the existing `DeviceIdentityPool`: requests whose ID token contains
  `curators` in `cognito:groups` assume `CuratorApiRole` instead of the existing `DeviceAuthRole`
  (Identity Pool "Choose role with rules" / token-based role mapping).
- **Permissions: `execute-api:Invoke` on the new `/curation/*` routes only. No S3 permissions of
  any kind, no permissions on `/get-upload-url` or `/get-model-url`.** This is the same
  least-privilege shape as `DeviceAuthRole` (execute-api:Invoke on exactly its own routes), just
  pointed at a different route prefix.
- `DeviceAuthRole` (used by the Android app) is unchanged.

### Curator identity resolution (server-side, not client-supplied)

Every curation Lambda needs to know *which curator* is calling, to enforce "only the claiming
curator can edit/complete/release their own package." This must not be trusted from a
client-supplied field (a tampered or buggy frontend could otherwise claim to be a different
curator). Instead:

- When the Identity Pool exchanges a Cognito User Pool ID token for STS credentials, the resulting
  session's `amr` (authentication methods reference) includes an entry of the form
  `cognito-idp.<region>.amazonaws.com/<user_pool_id>:CognitoSignIn:<sub>`.
- API Gateway's `AWS_IAM` authorizer surfaces this as
  `event.requestContext.authorizer.iam.cognitoIdentity.amr` in the Lambda event.
- Every curation Lambda extracts `<sub>` from this claim and uses it as `curator_sub` for all
  authorization checks (claim ownership, `processing/<curator_sub>/...` paths, etc.). A
  `claimed_by_email` field may additionally be accepted from the request body for **display
  purposes only**; it is never used in an authorization decision.
- This is a deliberate hardening relative to `GetUploadUrlFunction` (REQ-018), which currently
  trusts a client-supplied `user_id` for the device-upload path — acceptable there because a
  device can only ever write into its own `uploads/<user_id>/...` prefix and the consequence of
  spoofing is at most writing to the wrong device folder. Here, a spoofed identity could let one
  curator silently act on another curator's claimed package, so it is resolved server-side.

### New Lambda execution role: `CurationLambdaRole`

Used by all curation Lambda functions (not exposed to the browser). Scoped to the dataset bucket:

| Action | Resource |
|---|---|
| `s3:ListBucket` | whole bucket (to enumerate `uploads/`, `processing/`, `done/`) |
| `s3:GetObject` | `uploads/*`, `processing/*`, `done/*`, `rejected/*` |
| `s3:PutObject` | `processing/*`, `done/*`, `rejected/*` (never `uploads/*` — originals are read-only) |
| `s3:DeleteObject` | `processing/*` only |

No `s3:DeleteObject` on `uploads/`, `done/`, or `rejected/` — once a package reaches `done/` or
`rejected/`, nothing in this system can delete it (manual operator action only, same as today's
admin principal).

---

## Curation API surface

All routes added to the existing `UploadApi` HTTP API (REQ-018), `AWS_IAM` auth, reachable only by
`CuratorApiRole`.

| Method & path | Purpose | Sync/Async |
|---|---|---|
| `GET /curation/not-processed` | List unclaimed (or claim-expired) uploaded packages, oldest first | Sync |
| `GET /curation/in-progress` | List the caller's own claimed packages with progress counts | Sync |
| `GET /curation/processed` | List completed packages from `done/` (shared, all curators) | Sync |
| `POST /curation/packages/{package_id}/claim` | Claim a package; kicks off server-side unzip | Sync trigger → async work |
| `GET /curation/packages/{package_id}` | Poll claim/unpack status (`claiming` / `ready` / `failed`) and progress | Sync |
| `POST /curation/packages/{package_id}/heartbeat` | Refresh the claim's activity timestamp | Sync |
| `GET /curation/packages/{package_id}/items` | List items (subset, basename, status) for navigation | Sync |
| `GET /curation/packages/{package_id}/items/{subset}/{basename}` | Get one item: pre-signed image URL + label content | Sync |
| `POST /curation/packages/{package_id}/items/{subset}/{basename}/decision` | Mark an item ready or rejected | Sync |
| `POST /curation/packages/{package_id}/release` | Abandon a claim, discard working files | Sync |
| `POST /curation/packages/{package_id}/complete` | Finish a package; kicks off server-side finalize | Sync trigger → async work |

Full request/response payloads and per-route validation rules are specified in REQ-023 (package
lifecycle routes) and REQ-024 (item-level routes). The exact mapping of routes to individual Lambda
functions (one Lambda per route vs. a shared handler) is an implementation detail left to the SAM
template; the contract above is what matters for the frontend.

### Why these specific operations are server-side instead of client-side

Unzipping the source package and fanning it out into train/val/test, and later copying reviewed
files into `done/`/`rejected/`, are both done by Lambda (`claim` and `complete`) rather than in the
browser, because:
- It removes the need for any S3 write/delete permission on the browser's role at all.
- ZIP parsing and the train/val/test fan-out happen in one place, in code the backend team
  controls and can change without a frontend release.
- It avoids shipping a ZIP-parsing library to the browser and downloading/re-uploading
  potentially large payloads twice (S3→browser→S3) over a residential/office connection.

### Long-running operations and the API Gateway timeout

API Gateway HTTP APIs enforce a hard **29-second** integration timeout. Unzipping a package
(`claim`) or copying all reviewed/rejected files into `done/`/`rejected/` (`complete`) can involve
hundreds of S3 object operations and may not reliably finish in 29 seconds for larger packages.
Both routes therefore use a **fire-and-poll** pattern:

1. `POST .../claim` (or `.../complete`) validates the request, writes/updates a status field
   (`"claiming"` / `"completing"`) on the claim marker, asynchronously invokes a worker Lambda
   (`lambda:InvokeFunction`, `InvocationType=Event`), and returns immediately (HTTP 202).
2. The worker Lambda (generous timeout, e.g. 5 minutes; not reachable via API Gateway) does the
   actual bulk S3 work, then updates the claim marker's status to `"ready"` (claim) or removes it
   entirely and writes the `done/<package_id>/_manifest.json` (complete) — or sets status to
   `"failed"` with an error message if something goes wrong.
3. The frontend polls `GET /curation/packages/{package_id}` every few seconds until the status
   changes, then proceeds (opens the editor, or shows the package under Processed).

All other routes (heartbeat, item get/decision, release, the three list endpoints) are simple
enough to stay fully synchronous.

---

## Hosting

Static Angular production build, deployed to S3 + CloudFront in the existing AWS account —
consistent with the rest of the infra being AWS-only. A new private S3 bucket holds the built
`dist/` output; CloudFront serves it (origin access control, no public bucket access), giving
HTTPS and a stable URL without standing up a separate hosting service. Out of scope for this
requirement's acceptance criteria — tracked as a build/deploy task, not a data-handling concern.

---

## Decisions

- **Hosting target:** S3 + CloudFront (confirmed above).
- **Curator identity:** curator accounts are existing Cognito User Pool accounts that get added to
  the `curators` group — not a separate identity space. The same account a curator uses for any
  personal Android-app usage, if any, is the one added to the group (confirmed).
- **Data access model:** Lambda-mediated API, not direct browser-to-S3 access — every data
  operation is validated and performed server-side so the backend fully controls what the frontend
  can do.

## Deferred — not blocking, verify during implementation

- Identity Pool rule-based role mapping requires `cognito:groups` to be present in the ID token and
  compatibility with `ServerSideTokenCheck: true` (already set on `DeviceIdentityPool` per
  REQ-018). Expected to work based on standard Cognito behavior, but needs a smoke test once the
  group and role mapping are actually created — skipped for now rather than blocking this
  requirements doc.
- Confirming `event.requestContext.authorizer.iam.cognitoIdentity.amr` is populated as expected for
  HTTP API v2 + `AWS_IAM` authorizer + Identity Pool credentials (the curator-identity-resolution
  mechanism above) — needs a smoke test during implementation; if the claim format differs from
  assumed, the fallback is a verified-JWT approach (Lambda independently validates the ID token
  against the User Pool's JWKS) instead of reading it off the IAM authorizer context.

---

## Acceptance criteria

- [ ] A Cognito user not in the `curators` group can sign in but sees "access denied" and no
      package data.
- [ ] A Cognito user in the `curators` group signs in and obtains STS credentials scoped to
      `CuratorApiRole`.
- [ ] `CuratorApiRole` can invoke `/curation/*` routes but cannot invoke `/get-upload-url` or
      `/get-model-url`.
- [ ] `CuratorApiRole` has no S3 permissions of any kind (verified by attempting a direct S3 call
      with the curator's STS credentials and observing `AccessDenied`).
- [ ] `CurationLambdaRole` can list/get/put under `processing/`, get under `uploads/`/`done/`/
      `rejected/`, but cannot put or delete under `uploads/`, and cannot delete under `done/` or
      `rejected/`.
- [ ] `DeviceAuthRole` permissions are unchanged from REQ-018 (regression check).
- [ ] Every curation Lambda resolves `curator_sub` from the IAM authorizer's `amr` claim, not from
      any client-supplied request field.
- [ ] Curator sign-out clears cached STS credentials from browser memory/storage.
- [ ] A `claim` or `complete` request returns HTTP 202 immediately and the corresponding worker
      Lambda completes the bulk S3 work asynchronously, observable via status polling.