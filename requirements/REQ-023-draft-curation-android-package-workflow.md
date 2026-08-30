---
id: REQ-023
title: Dataset Curation Android App — Package Workflow (Not Processed / In Progress / Done)
status: draft
priority: high
depends_on: REQ-022
---

## Summary

Three-screen workflow: **Not Processed** (raw uploads, startable), **In Progress** (packages the
curator has started and is reviewing image-by-image), and **Done** (completed, training-ready
packages in `done/`). List/read/write operations are plain S3 calls made by the app itself,
using the direct on-device S3 access and single-curator model from REQ-022 — no backend Lambda
involved.

---

## Goals

- Let the curator work through the upload backlog oldest-first by default, while allowing manual
  selection of any specific package.
- Make per-image review progress durable — closing the app mid-package does not lose work; the
  curator resumes where they left off, even after a reinstall (REQ-022's working-copy strategy).
- Preserve train/val/test subset membership end-to-end from upload through `done/`.
- Keep original uploads immutable; only `curation/`, `done/`, and `rejected/` are written to.

## Non-goals

- Preventing two curators from starting the same package concurrently — out of scope for the
  single-curator v1 (REQ-022 "Future: multi-curator").
- Bulk "accept all" — every image is individually reviewed (REQ-024).
- Editing pixel data (crop/rotate/brightness) — only bounding boxes and accept/reject (REQ-024).

---

## Screen 1 — Not Processed

- The app lists `uploads/**/*.zip` via `ListObjectsV2` (paginated), computes each package's
  `package_id`, and excludes any package_id that already has `curation/<package_id>/manifest.json`
  (in progress) or `done/<package_id>/_manifest.json` (finished).
- Default sort: oldest first, by S3 `LastModified` — no extra HEAD calls needed, `ListObjectsV2`
  already returns size and `LastModified` per object.
- Each row shows: original filename, uploading user (sub, truncated), device id, upload date, ZIP
  size.
- "Start reviewing" button on any row — lets the curator pick a specific package, not just the
  oldest one.

## Start (Not Processed → In Progress)

1. Curator taps "Start reviewing".
2. The app downloads the ZIP (`GetObject`) to app-private storage and unzips it locally, building
   an initial in-memory/local manifest listing every item (`subset`, `basename`, `status:
   "pending"`).
3. The app uploads `curation/<package_id>/manifest.json` to S3 (`PutObject`) — this is what makes
   the package appear under "In Progress" and disappear from "Not Processed" going forward.
4. Because there is a single curator, no conditional-write/claim-race handling is needed (no
   `If-None-Match` dance) — a plain `PutObject` is sufficient. If this app is ever used by two
   curators simultaneously, a second "Start reviewing" on the same package silently overwrites the
   first curator's manifest — a known, accepted limitation of the single-curator design (REQ-022).
5. A local progress indicator is shown during download+unzip — this is entirely on-device and
   typically finishes in a few seconds; there is no async server-side unpack step to poll for,
   since there's no Lambda doing the work.
6. The original ZIP under `uploads/` is never modified or deleted.

## Screen 2 — In Progress

- The app lists `curation/**/manifest.json`, reads each, and shows: original filename, started-at
  timestamp, and progress (`accepted: X, rejected: Y, remaining: Z` out of total items across
  train+val+test).
- Tapping a row:
  - If the local unzip cache for that `package_id` is still present on-device, opens the editor
    (REQ-024) directly at the first `pending` item.
  - If not (reinstall, or a different device), the app re-downloads and re-unzips the source ZIP,
    then replays `manifest.json`'s stored per-item decisions against the fresh unzip (already
    `accepted`/`rejected` items are not re-shown for review), then opens at the first `pending`
    item.
- **Complete** button — enabled only when `remaining == 0`. On tap:
  1. Uploads every `accepted` item's image+label to
     `done/<package_id>/<subset>/{images,labels}/...`.
  2. Uploads every `rejected` item's image+label to
     `rejected/<package_id>/<subset>/{images,labels}/...`.
  3. Copies/regenerates `data.yaml` into `done/<package_id>/data.yaml`.
  4. Writes `done/<package_id>/_manifest.json` (curator email, `started_at`, `completed_at`,
     counts).
  5. Deletes `curation/<package_id>/manifest.json` and the local unzip cache.
  - A progress indicator is shown during the upload (can be tens to low hundreds of small `PutObject`
    calls for a large package).
- **Release** button — deletes `curation/<package_id>/manifest.json` from S3 and the local unzip
  cache; the package returns to "Not Processed". Requires a confirmation dialog ("Release
  `<filename>`? All review progress will be lost.").

## Screen 3 — Done

- Lists `done/**/_manifest.json` (read-only audit view): filename/package id, completed-at
  timestamp, counts (total / accepted / rejected).
- No actions in v1 beyond viewing the summary.

---

## Per-image review

Detailed in REQ-024. Marking an item updates local state and immediately re-writes
`curation/<package_id>/manifest.json` in full (small JSON, cheap to overwrite completely on every
decision — no partial-update mechanism needed).

## Resumability

Covered under "Working-copy strategy" in REQ-022; see acceptance criteria below for the concrete
guarantees.

## Edge case — package rejected entirely

A package where the curator rejects every item can still be Completed; the resulting
`done/<package_id>/` contains an empty train/val/test tree plus `data.yaml` and a `_manifest.json`
showing `accepted: 0`. This is not blocked — an all-rejected source package is still a valid
"processed" outcome.

---

## Acceptance criteria

- [ ] A package with an existing `curation/<package_id>/manifest.json` does not appear in "Not
      Processed".
- [ ] A package with an existing `done/<package_id>/_manifest.json` does not appear in "Not
      Processed" or "In Progress".
- [ ] Starting a package downloads and unzips the source ZIP locally and writes an initial
      `curation/<package_id>/manifest.json` with every item `pending`.
- [ ] Killing the app mid-review and reopening it resumes the same package at the first `pending`
      item using the local unzip cache, with no network call required.
- [ ] Reinstalling the app (local cache gone) and reopening an in-progress package re-downloads and
      re-unzips the source ZIP, then correctly skips items already marked `accepted`/`rejected` per
      the S3 manifest, resuming at the first still-`pending` item.
- [ ] "Complete" is only enabled when every item's status is `accepted` or `rejected`.
- [ ] After completion, `done/<package_id>/` contains only accepted items, correctly split by
      train/val/test, plus `data.yaml` and `_manifest.json`.
- [ ] After completion, `rejected/<package_id>/` contains all rejected items, correctly split by
      subset (only created if at least one item was rejected).
- [ ] After completion, `curation/<package_id>/manifest.json` no longer exists and the local unzip
      cache is deleted.
- [ ] The original ZIP in `uploads/` is byte-for-byte unchanged after a package is fully processed.
- [ ] Releasing an in-progress package deletes `curation/<package_id>/manifest.json` and the local
      cache, and the package reappears in "Not Processed".
- [ ] "Not Processed" defaults to oldest-first but any row's "Start reviewing" button works
      regardless of position.
- [ ] A package where every item is rejected can still be Completed, producing an empty
      `done/<package_id>/` image tree with `accepted: 0` in `_manifest.json`.
