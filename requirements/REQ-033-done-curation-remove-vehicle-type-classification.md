---
id: REQ-033
title: Curation — Remove Vehicle-Type Classification, Single License-Plate Class
status: done
priority: medium
supersedes: REQ-025
---

## Summary

[REQ-025](REQ-025-done-curation-vehicle-type-categories.md) added per-box vehicle-type
classification to `curation-android` (civil / police / fire / medical / other / license_plate,
curator-chosen from a dropdown, required before Accept). This requirement rolls that back to
focus purely on license-plate detection: every box is now automatically the single
`license_plate` class, and there is no way to change it.

This is a scope decision, not a technical dead end — the underlying category-list infrastructure
(`VehicleCategories`, S3 `config/vehicle-categories.json` with a bundled fallback, the per-package
category-list snapshot) was kept rather than deleted, specifically so vehicle-type classification
could be re-expanded later without rebuilding that plumbing from scratch.

## What changed

- **`app/src/main/assets/vehicle-categories.json`** bumped to `version: 2`, trimmed to one class:
  `{"id": 0, "key": "license_plate", "label": "License plate"}`. The S3 copy needs the same update
  — see `infra/aws/README.md`'s "Category list" section for the `aws s3 cp` command.
- **`CurationViewModel.currentBoxes()`** — for a `PENDING` item, every box's class is now always
  `LICENSE_PLATE_CLASS_ID` (`0`), not read from `item.boxClasses`. `setBoxClass()` was removed
  (nothing calls it anymore). `addBox()` no longer takes a `classId` parameter.
- **`ReviewScreen`** — the per-box class dropdown (`ClassDropdown`) is gone; `BoxPanel` shows a
  static "License plate" label per box instead. The amber "unclassified" overlay color is gone
  (every box is always classified now) — boxes are cyan, or pink when selected.
- **`reviewHint()`** — the "Choose a type for every box before accepting" message is gone (it's
  unreachable now: classes are never null). "No boxes — this image must be Rejected, not Accepted"
  is unchanged and still the only reason Accept can be blocked.
- **Not changed:** `VehicleCategories`, `CurationRepository.fetchCategories()`,
  `CurationManifest.categories` (the per-package snapshot), and `completePackage()`'s `data.yaml`
  regeneration from that snapshot — all still work exactly as before REQ-025, just operating on a
  one-entry list. A package already In Progress when this ships keeps using whatever category-list
  version it snapshotted at Start (REQ-025's own offline-safety design), so it isn't disrupted.

## Why the infrastructure stayed

Ripping out `VehicleCategories`/the S3 config fetch entirely would have been more destructive than
necessary for a "for now" scope decision, and `data.yaml`'s `nc`/`names` still needs *some* class
list to regenerate from at Complete — a one-entry list is the simplest thing that satisfies both
"no vehicle-type classification" and "don't rebuild the config/versioning/snapshot plumbing later
if this comes back."

## Acceptance criteria

- [x] No dropdown or other UI lets a curator choose a box's class.
- [x] Every box is `license_plate` (class `0`) automatically, both for existing predicted boxes and
      newly hand-drawn ones.
- [x] Accept is blocked only when there are zero boxes on the current item (unchanged trigger,
      simplified reason).
- [x] `data.yaml` for a newly-started package declares `nc: 1, names: ['license_plate']`.
- [x] A package already In Progress under the old 6-class snapshot is unaffected — it completes
      against its own snapshot, not the new one.
- [x] `curation-android/CLAUDE.md` and `infra/aws/README.md` updated to describe the rollback and
      the still-live category-list mechanism.
- [ ] Operator has re-run `aws s3 cp .../vehicle-categories.json s3://.../config/vehicle-categories.json`
      so newly-*started* packages pick up the single-class list from S3 too (the bundled fallback
      alone only covers an offline first fetch).
