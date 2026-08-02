---
id: REQ-023
title: Dataset Curation — Package Lifecycle (Not Processed / In Progress / Processed)
status: draft
priority: high
depends_on: REQ-022
---

## Summary

Three-screen workflow in the curation web app: **Not Processed** (raw uploads, claimable),
**In Progress** (packages a curator has claimed and is reviewing image-by-image), and
**Processed** (completed, training-ready packages in `done/`). All state transitions go through
the Lambda-mediated API defined in REQ-022 — the frontend never writes to S3 directly.

---

## Goals

- Let curators work through the upload backlog oldest-first by default, while allowing manual
  selection of any specific package.
- Prevent two curators from claiming and working on the same package simultaneously.
- Make per-image review progress durable — closing the browser mid-package does not lose work;
  the curator resumes where they left off.
- Preserve train/val/test subset membership end-to-end from upload through `done/`.
- Keep original uploads immutable; only `processing/` and `done/`/`rejected/` are written to.
- Support multiple curators working concurrently, each in their own
  `processing/<curator_sub>/` workspace, with no cross-curator interference.
- Automatically free up a package if the curator who claimed it stops working on it, so an
  abandoned claim never permanently blocks a package from the rest of the team.
- Let the backend, not the frontend, be the source of truth for every state transition (claimed,
  expired, reviewed, completed) — the API rejects any request that violates the rules below
  regardless of what the UI allows the curator to click.

## Non-goals

- Editing pixel data (cropping, rotating, brightness) — only bounding boxes and accept/reject, per
  REQ-024.
- Cross-curator handoff of an in-progress package (only the claiming curator can work on or
  complete it in this version).
- Bulk "approve all" — every image must be individually reviewed (REQ-024).

---

## Screen 1 — Not Processed

- Calls `GET /curation/not-processed`, which lists every `.zip` under `uploads/**` that has **no**
  matching *active* claim (an expired claim does not count as active — see Claim expiry below) and
  no matching `done/<package_id>/` record.
- Default sort: oldest first (by S3 `LastModified` of the ZIP object), applied server-side.
- Each row shows: original filename, uploading user (sub, truncated), device id, upload date, ZIP
  size.
- "Process" button on each row (any row, not just the oldest one) — this is what lets a curator
  select a specific file rather than only the oldest.
- Tapping "Process" calls `POST /curation/packages/{package_id}/claim` (below); on success the row
  disappears from this screen and the package appears under "In Progress" for that curator.
- If the claim call fails because another curator claimed it microseconds earlier (race), the API
  returns HTTP 409 and the app shows "Already claimed by another curator — refresh list" rather
  than a generic error.

## Screen 2 — In Progress

- Calls `GET /curation/in-progress`, which returns only the **calling curator's own** claimed
  packages — the Lambda derives `curator_sub` server-side (REQ-022), so curators cannot see or
  enumerate each other's in-progress work even by tampering with the request. A package whose
  claim has expired (see below) no longer appears here for the curator who originally claimed it.
- Each row shows: original filename, claimed-at timestamp, last-active timestamp, expires-at
  timestamp (`last_activity_at` + 24h, refreshed automatically while the curator is actively
  working — see Claim expiry below), status (`claiming` / `ready` / `completing` / `failed`), and
  progress (`reviewed: X, rejected: Y, remaining: Z` out of total items across train+val+test).
- While status is `claiming` (server-side unzip still running, REQ-022), the row shows a spinner
  and is not yet openable; the app polls `GET /curation/packages/{package_id}` until status flips
  to `ready` or `failed`.
- Tapping a `ready` row opens the annotation editor (REQ-024) at the first unreviewed item.
- **Complete package** button — enabled only when `remaining == 0` (every image marked ready or
  rejected). Calls `POST /curation/packages/{package_id}/complete`, which returns immediately
  (status becomes `completing`) while the server-side finalize work runs (below); the app polls
  until the package disappears from In Progress and appears under Processed.
- **Release** button — calls `POST /curation/packages/{package_id}/release`, which deletes the
  claim marker and the curator's `processing/<curator_sub>/<package_id>/` working tree, including
  any review progress. The package returns to "Not Processed" for anyone (including the same
  curator) to claim again later. Requires a confirmation dialog ("Release `<filename>`? All review
  progress on this package will be lost.").

## Screen 3 — Processed

- Calls `GET /curation/processed`, which lists completed packages from `done/**` (shared, visible
  to all curators — read-only audit view).
- Each row shows: original filename/package id, curator who completed it, completed-at timestamp,
  counts (total / reviewed / rejected).
- No actions in v1 beyond viewing the summary (no re-opening a completed package).

---

## Claim (Not Processed → In Progress)

1. Curator taps "Process" on a package; the app calls `POST /curation/packages/{package_id}/claim`.
2. The Lambda checks for an existing claim at `processing/_claims/<package_id>.json`:
   - **No claim exists**, or the existing claim is **expired** (see Claim expiry below) → proceed.
     If expired, the Lambda first deletes the stale claim marker and the stale
     `processing/<other_curator_sub>/<package_id>/` tree before continuing.
   - **Active claim exists**, belonging to someone else → return HTTP 409 ("already claimed").
3. The Lambda writes `processing/_claims/<package_id>.json` with a **conditional PUT**
   (`If-None-Match: *`) containing `{source_key, claimed_by: <curator_sub resolved server-side>,
   claimed_by_email, claimed_at, last_activity_at, status: "claiming"}` (`last_activity_at`
   initialized equal to `claimed_at`). The conditional write is the concurrency guard for the
   simultaneous-claim race — if another curator's claim was written between this check and this
   write, S3 rejects with HTTP 412 and the Lambda returns 409 to the caller.
4. The Lambda returns HTTP 202 immediately and asynchronously invokes the unpack worker, which:
   downloads the source ZIP (`s3:GetObject` on `uploads/...`), unzips it server-side, uploads each
   image/label pair individually to `processing/<curator_sub>/<package_id>/<subset>/{images,labels}/`,
   preserving subset (train/val/test); writes
   `processing/<curator_sub>/<package_id>/manifest.json` listing every item (`subset`, `basename`,
   `status: "pending"`); and finally flips the claim marker's `status` to `"ready"` (or `"failed"`
   with an error message on failure).
5. The app polls `GET /curation/packages/{package_id}` until `status` is `ready` or `failed`.
6. The original ZIP under `uploads/` is **never modified or deleted**.

## Claim expiry (auto-release after 24 hours of inactivity)

A claim with **no activity for 24 hours** is considered abandoned and is automatically released,
making the package available to any curator (including the original one) again. Activity, not
elapsed time since claiming, is what keeps a claim alive — a curator actively working a large
package for several days never loses it as long as they keep being active at least once every 24
hours.

- **Rule:** a claim is *expired* when `now - last_activity_at >= 24h`.
- **What counts as activity** (each updates `last_activity_at` on the claim marker, server-side):
  - Marking an item ready or rejected (REQ-024) — the natural, frequent checkpoint.
  - A periodic heartbeat call, `POST /curation/packages/{package_id}/heartbeat`, sent by the editor
    while the package is open and the browser tab is focused: every 5 minutes. The Lambda verifies
    the caller's `curator_sub` matches `claimed_by` before refreshing — a heartbeat cannot be used
    to keep alive a claim that isn't the caller's own. This covers the case where a curator spends
    a long time on a single difficult image without yet marking it.
  - The heartbeat stops (and `last_activity_at` stops advancing) once the tab is closed, navigated
    away from, or loses focus for an extended period — exact backgrounding behavior is an
    implementation detail, but the intent is "stops being sent when the curator is not actually
    looking at the package."
- **Enforcement is server-side and lazy** — there is no scheduled backend job. Expiry is checked
  and acted on at two points, both inside Lambda code:
  1. **`GET /curation/not-processed`** — any expired claim encountered while listing
     `processing/_claims/` is treated as not-claimed for response purposes (the package is included
     in the not-processed list).
  2. **`POST /curation/packages/{package_id}/claim`** — before writing a new claim, the Lambda
     actively deletes the expired claim marker and the **entire**
     `processing/<original_curator_sub>/<package_id>/` tree belonging to the curator who let it
     expire, then proceeds to claim it fresh.
- This relies on `CurationLambdaRole` (not the browser's role) having `s3:DeleteObject` across all
  of `processing/*`, not scoped per-curator-sub — already specified in REQ-022. The browser itself
  never has this permission.
- If the original curator is still actively working when another curator's claim reclaims the
  package out from under them (only possible if their heartbeat genuinely stopped for 24h, e.g.
  the tab was closed and is now reopened), their next API call against that package (e.g.
  `.../decision`) returns HTTP 410 Gone because the claim marker no longer matches their
  `curator_sub`. The app shows "This package's claim expired and was released to another curator —
  your unsaved progress on it was discarded," returning them to the Not Processed/In Progress
  screens.

## Per-image review (detailed in REQ-024)

Marking an item ready or rejected calls
`POST /curation/packages/{package_id}/items/{subset}/{basename}/decision`. The Lambda verifies the
caller's `curator_sub` matches the claim's `claimed_by` and that the claim is not expired, then
moves (S3 copy + delete) the image+label pair from
`processing/<curator_sub>/<package_id>/<subset>/{images,labels}/` into
`processing/<curator_sub>/<package_id>/reviewed/<subset>/...` or `.../rejected/<subset>/...`
respectively, updates that item's status in `manifest.json`, and refreshes `last_activity_at` on
the claim marker. This happens immediately per image — it is not deferred to package completion.

## Complete package (In Progress → Processed)

`POST /curation/packages/{package_id}/complete` is accepted only when the Lambda confirms every
item's status is `reviewed` or `rejected` (re-checked server-side against `manifest.json`, not
trusted from the frontend's locally-tracked count) and the caller's `curator_sub` matches
`claimed_by`. On acceptance the Lambda returns HTTP 202 (`status: "completing"`) and asynchronously
invokes the finalize worker, which:

1. Copies `processing/<curator_sub>/<package_id>/reviewed/**` → `done/<package_id>/**`, preserving
   subset structure.
2. Copies the original `data.yaml` from the source ZIP (or regenerates an equivalent) into
   `done/<package_id>/data.yaml`.
3. If any rejected items exist, copies `processing/<curator_sub>/<package_id>/rejected/**` →
   `rejected/<package_id>/**`.
4. Writes `done/<package_id>/_manifest.json` with curator, `claimed_at`, `completed_at`, and
   counts.
5. Deletes the entire `processing/<curator_sub>/<package_id>/` tree and its claim marker.

The app polls `GET /curation/packages/{package_id}` (returns 404 once the claim marker is deleted,
which the app interprets as "done") until the package disappears from In Progress; it then appears
on the Processed screen.

## Resumability

If the curator closes the browser mid-package, on next sign-in `GET /curation/in-progress` still
returns the package (the claim marker and manifest persist in S3, server-side). Re-opening it
calls `GET /curation/packages/{package_id}/items` and resumes the editor at the first `pending`
item — the unzip step in "Claim" is **not** repeated; the Lambda detects the existing
`manifest.json` and skips straight to returning the current state.

---

## Acceptance criteria

- [ ] A package claimed by curator A does not appear in curator B's "Not Processed" or "In
      Progress" list, even if curator B's client sends a manipulated request claiming to be
      curator A.
- [ ] Concurrent claim requests on the same package by two curators result in exactly one HTTP 202
      and one HTTP 409.
- [ ] Releasing a claim returns the package to "Not Processed" with no leftover objects under
      `processing/<curator_sub>/<package_id>/`.
- [ ] Closing the browser mid-review and signing back in resumes the same package with prior
      reviewed/rejected items intact, without re-running the unzip step.
- [ ] `POST .../complete` is rejected by the Lambda (not just disabled in the UI) when any item's
      server-side status is still `pending`.
- [ ] After completion, `done/<package_id>/` contains only reviewed items, correctly split by
      train/val/test.
- [ ] After completion, `processing/<curator_sub>/<package_id>/` no longer exists.
- [ ] The original ZIP in `uploads/` is byte-for-byte unchanged after a package is fully
      processed.
- [ ] Not Processed list defaults to oldest-first but any row's "Process" button works regardless
      of position.
- [ ] A claim with no activity for 24 hours no longer appears as "claimed" in the Not Processed
      list for any curator, including the one who originally claimed it.
- [ ] Marking an item ready or rejected refreshes the claim's `last_activity_at`.
- [ ] The editor sends a heartbeat roughly every 5 minutes while open and focused, refreshing
      `last_activity_at` even when no item has been marked yet.
- [ ] A heartbeat or decision call from a curator who does not own the claim is rejected
      (HTTP 403/410), even if it targets a package_id the caller knows about.
- [ ] A package actively worked for longer than 24 hours total, with regular activity throughout,
      is never auto-expired.
- [ ] Claiming a package with an expired prior claim deletes the prior claimant's
      `processing/<their_sub>/<package_id>/` tree and claim marker before creating the new claim.
- [ ] A curator whose claim has not expired keeps exclusive access — a second curator's claim
      attempt within the 24-hour-since-last-activity window is rejected with HTTP 409.
- [ ] If a curator's claim expires and is taken by someone else while they are still in the
      editor, their next action against that package fails gracefully (HTTP 410 with a clear
      message) instead of a silent error or data corruption.
- [ ] `claim` and `complete` each return HTTP 202 within the synchronous request and the actual
      bulk file movement is verifiably performed by the async worker (observable via status
      polling), never blocking on the 29-second API Gateway integration timeout.