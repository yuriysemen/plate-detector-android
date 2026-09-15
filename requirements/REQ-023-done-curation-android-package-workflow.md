---
id: REQ-023
title: Dataset Curation Android App — Package Workflow (Not Processed / In Progress / Done)
status: done
priority: high
depends_on: REQ-022
---

> **Status — done (2026-08-30).** Three-tab workflow (`CurationHomeScreen`) plus a functional
> per-image review screen with a **read-only** YOLO overlay (box editing is REQ-024). Start
> downloads + unzips + writes an all-`pending` manifest; every accept/reject rewrites
> `curation/<id>/manifest.json` (stamping `decided_by`/`decided_at`); Complete finalises into
> `done/`/`rejected/` with a `reviewers` breakdown; Release/Discard removes the manifest.
> Includes the client-only stale-cleanup (2 h, heartbeat + Discard/Take over) and per-item
> attribution added per follow-up. See "Implementation notes" at the end. On-device end-to-end
> (start → review → complete) still to be run against a real uploaded package.

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

- A full claim/lock model preventing two curators from starting the same package concurrently —
  out of scope for the single-curator v1 (REQ-022 "Future: multi-curator"). The **stale-cleanup**
  and **per-item attribution** below are lightweight, client-only measures — not that model.
- A server-side or scheduled process that expires abandoned packages — stale cleanup only happens
  opportunistically when a curator opens the app (see "Stale In-Progress handling").
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
  timestamp, who started it (`curator_email`), and progress (`accepted: X, rejected: Y,
  remaining: Z` out of total items across train+val+test).
- A row whose `manifest.json` has had no S3 write for longer than the stale threshold is marked
  "⚠ Inactive Nh — may be abandoned" (see "Stale In-Progress handling"); its **Release** button
  reads "Discard" and its **Review** button reads "Take over".
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
  3. Writes `done/<package_id>/data.yaml` — the `train`/`val`/`test`/`nc`/`names` header is
     regenerated from the vehicle-category list (REQ-025); the source `device:` provenance block is
     preserved.
  4. Writes `done/<package_id>/_manifest.json` (`curator_email` = who started, `completed_by` =
     who tapped Complete, `started_at`, `completed_at`, `total`/`accepted`/`rejected`,
     `accepted_by_subset`, and a `reviewers` map — per-curator accept/reject tally).
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

## Manifest schema — `curation/<package_id>/manifest.json`

```json
{
  "package_id":   "<sub>__<device>__<stem>",
  "source_key":   "uploads/<sub>/<device>/<file>.zip",
  "filename":     "<file>.zip",
  "user_sub":     "<uploader cognito sub>",
  "device_id":    "<uploader device id>",
  "started_at":   "2026-08-30T12:00:00Z",
  "curator_email": "who-started@example.com",
  "items": [
    {
      "subset": "train",                       // train | val | test
      "basename": "20260101_120000_000001",
      "image_name": "20260101_120000_000001.jpg",
      "status": "pending | accepted | rejected",
      "label_content": "0 0.50 0.50 0.10 0.20\n",   // present when accepted
      "reason": "blurry plate",                      // optional, when rejected
      "decided_by": "who-reviewed-this@example.com", // present once decided
      "decided_at": "2026-08-30T12:03:21Z"           // present once decided
    }
  ]
}
```

Fields `decided_by` / `decided_at` are optional and absent on older manifests — parsing tolerates
their absence.

## Per-item attribution

- Every accept/reject stamps the item with `decided_by` (the signed-in curator's email) and
  `decided_at` (ISO-8601 UTC). This is independent of `curator_email`, which records only *who
  started* the package — after a "Take over" a second curator's decisions carry their own
  `decided_by`.
- `done/<package_id>/_manifest.json` aggregates this into a `reviewers` object,
  `{ "<email>": { "accepted": N, "rejected": M }, … }`, plus `completed_by`. This is the basis for
  any future per-curator throughput / time metrics; explicit per-image timing is **not** captured
  now (consecutive `decided_at` deltas are a usable proxy).

## Stale In-Progress handling

- "Last activity" for a package = the S3 `LastModified` of its `curation/<package_id>/manifest.json`
  (returned for free by `ListObjectsV2`). Every accept/reject already re-writes it; while the review
  screen is open the app additionally re-PUTs the manifest on a ~3-minute heartbeat so a curator
  lingering on one hard image still counts as active.
- Threshold: **2 hours** with no manifest write ⇒ the package is shown as stale on the In Progress
  screen (see Screen 2). Nothing is deleted automatically — cleanup is a curator action:
  - **Discard** — deletes `curation/<package_id>/manifest.json` (+ local cache); the package
    returns to Not Processed. Same operation as Release, confirmation dialog.
  - **Take over** — opens the package for review; opening re-PUTs the manifest, resetting the
    activity clock. The original `curator_email` is unchanged; subsequent decisions carry the new
    curator's `decided_by`.
- This is deliberately opportunistic — it only runs when *some* curator opens the app. A truly
  unattended expiry would need a scheduled job (out of scope; see Non-goals).

## Resumability

Covered under "Working-copy strategy" in REQ-022; see acceptance criteria below for the concrete
guarantees.

## Edge case — package rejected entirely

A package where the curator rejects every item can still be Completed; the resulting
`done/<package_id>/` contains an empty train/val/test tree plus `data.yaml` and a `_manifest.json`
showing `accepted: 0`. This is not blocked — an all-rejected source package is still a valid
"processed" outcome.

---

## Implementation notes

All in `android-training-data-reviewing-app/app/src/main/java/.../curation/`:

- **Data / logic** (unit-tested, `CurationWorkflowTest`): `PackageId` (`packageIdOf`), `UploadRef`
  (`parseUploadKey`), `YoloLabel` (parse/format), `CurationManifest` (+ `withDecision`, `reviewers`,
  JSON), `DoneManifest`, `PackageCache` (zip-slip-guarded unzip, item listing, label read/write).
- **`CurationRepository`** — all S3 (paginated `ListObjectsV2`, get/put/delete): `listNotProcessed`
  (set-subtract `curation/`+`done/` package-ids from `uploads/`), `listInProgress` →
  `InProgressItem(manifest, lastActivityMs)`, `listDone`, `startPackage`, `putManifest`,
  `ensureLocalCopy` (re-download if cache gone), `completePackage`, `releasePackage`.
  `STALE_IN_PROGRESS_MS = 2 h`.
- **`CurationViewModel`** — tab states, blocking `busy` progress, review `session`, `decide()`
  (stamps `decidedBy`/`decidedAt`, rewrites manifest, auto-advances), `complete`/`release`, and the
  ~3 min manifest heartbeat while a session is open.
- **UI** — `CurationHomeScreen` (bottom-nav tabs + busy dialog + error snackbar), `PackageTabs`
  (the three lists; In Progress shows stale rows with Discard / Take over), `ReviewScreen` (image +
  read-only YOLO overlay, Prev/Reject/Accept/Next, jump-to-item sheet, zero-box ⇒ Reject-only).
- `done/<id>/data.yaml`: the YOLO header is **regenerated** from the REQ-025 category list
  (`nc` / `names`), the source `device:` block is kept. `_manifest.json` also carries
  `category_list_version` + `class_counts` (REQ-025).

The criteria below are behavioural and await a full on-device run (start → review → complete)
against a real uploaded package.

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
- [ ] `done/<package_id>/_manifest.json` records `completed_by` and a `reviewers` map whose
      per-curator `accepted`/`rejected` counts sum to the package totals.
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
- [ ] Each accepted/rejected item in `manifest.json` carries `decided_by` (signed-in curator's
      email) and `decided_at`; `curator_email` still reflects who *started* the package.
- [ ] An In-Progress package with no manifest write for > 2 h is shown as stale ("⚠ Inactive …"),
      with **Discard** (→ Not Processed) and **Take over** actions; opening it (or making a
      decision) clears the stale state.
- [ ] With the review screen open and no decisions made, the manifest's S3 `LastModified` still
      advances (heartbeat) so the package does not go stale mid-session.
