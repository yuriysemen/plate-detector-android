---
id: REQ-025
title: Dataset Curation — Vehicle-Type Categories and Typed Boxes
status: done
priority: high
depends_on: REQ-022, REQ-023
---

> **Rolled back (2026-09-14) by [REQ-033](REQ-033-done-curation-remove-vehicle-type-classification.md):**
> per-box vehicle-type classification (the dropdown, the all-classified Accept gate) was removed to
> focus purely on plate detection. The category-list infrastructure described below was kept, not
> deleted, and is now a single `license_plate` class — see REQ-033 for what changed and why.

> **Status — done (2026-09-07).** Category list (`VehicleCategories`, S3 `config/` with bundled
> fallback), per-box class dropdown, add-box, and Accept's all-classified gate are implemented on
> `ReviewScreen`. `done/` output carries class ids, a regenerated `data.yaml`, and `class_counts`.
> `CuratorRole` reads `config/*`. Unit-tested (`CurationWorkflowTest`, `VehicleCategoriesTest`).
> Acceptance criteria below are behavioural and await a full on-device run, same as REQ-023.

## Summary

The parking barrier is meant to open automatically for police / fire / medical vehicles and fall
back to a plate check for civilian cars (REQ-009 vehicle-type detection, REQ-010 access decision).
That needs **training data labelled with vehicle type**, and the only place a human currently
touches this data is the curation app.

This requirement adds the **data-capture half only**:

1. A canonical **vehicle-category list** stored in S3.
2. A **per-box class picker** in the curation review screen (REQ-023) so the curator labels each
   detection with a vehicle type — and can **add a car box** when the whole vehicle is visible.
3. `done/` output carries the class ids, a regenerated `data.yaml`, and per-class counts.

The on-device classifier and the gate logic stay as their REQ-009 / REQ-010 drafts.

---

## Class scheme — the YOLO class id carries the type

The dataset stays YOLO format (REQ-002); the previously-always-`0` class-id column becomes
meaningful. The class picker works on **any box**:

- Imported boxes are drawn around the licence plate — they stay `license_plate` (class 0) unless
  the curator changes them.
- When the full vehicle is visible, the curator **draws a car box** and sets its type (`civil`,
  `police`, …). Plate box and car box coexist.
- When the full car is not visible (common — these are plate-crop photos), the image contributes
  plate-only data; the plate box can still be reclassified directly if the type is known.

### `config/vehicle-categories.json`

```json
{
  "version": 1,
  "classes": [
    { "id": 0, "key": "license_plate", "label": "License plate" },
    { "id": 1, "key": "civil",   "label": "Civil car" },
    { "id": 2, "key": "police",  "label": "Police car" },
    { "id": 3, "key": "fire",    "label": "Fire car" },
    { "id": 4, "key": "medical", "label": "Medical car" },
    { "id": 5, "key": "other",   "label": "Other" }
  ]
}
```

- Ordered; `id` = YOLO class id. **Ids are pinned** — reordering must not silently remap existing
  labels. Adding a class = append with the next id.
- Lives in the dataset bucket at `config/vehicle-categories.json`. The curator app **fetches** it
  on entering the workflow; a byte-identical copy is bundled at
  `android-training-data-reviewing-app/app/src/main/assets/vehicle-categories.json` as the offline fallback.
- **Read-only from the app.** The operator seeds / edits it:
  `aws s3 cp vehicle-categories.json s3://<bucket>/config/vehicle-categories.json`. An in-app editor
  is out of scope.

> The keys differ slightly from REQ-009's classes (`medical` vs `ambulance`, `other` vs
> `military`). Because the list is data-driven and the training pipeline maps keys, this is not
> blocking; REQ-009 should be reconciled when its model is built.

---

## Review screen additions (extends REQ-023 `ReviewScreen`)

- Under the image, a **box list** — one row per box: `Box N`, a **class dropdown** (populated from
  the category list, no blank option once chosen), a delete button, tap-to-highlight on the canvas.
- The canvas colours boxes: **amber** = unclassified, **cyan** = classified, **pink** = selected.
- **Add box** — a toggle in the top bar; while active, dragging on the image draws a rectangle
  which is appended as a new box (default class = first non-plate class). Precise move / resize of
  existing boxes stays REQ-024.
- **Accept rule** — Accept is disabled, with a hint, until **every box has a class chosen** (in
  addition to REQ-024's ≥1-box rule). Rejected images need no classes.
- Picks are held in the manifest (`box_classes`, `working_label`) and re-written to S3 on a short
  debounce, so classification survives an app kill mid-image.

### Manifest additions — `curation/<id>/manifest.json`

```json
{ "category_list_version": 1,
  "category_list": { "version": 1, "classes": [ { "id": 0, "key": "license_plate", "label": "License plate" }, "..." ] },
  "items": [ {
    "...": "...",
    "working_label": "0 0.51 0.33 0.22 0.07\n2 0.40 0.55 0.30 0.40",  // geometry incl. added boxes
    "box_classes": [0, 2]                                             // chosen class per box; null = not yet
  } ] }
```

`box_classes` and `working_label` are optional — absent on older / not-yet-touched items.

**`category_list` is a full snapshot of the category list at Start time, not just its version.**
Review (the class dropdown, `allBoxesClassified`) and Complete (`data.yaml` regeneration,
`class_counts`) always use *this* snapshot, never a freshly-fetched one — so if the canonical list
is extended after a package starts (e.g. `config/vehicle-categories.json` moves to version 2 while
this package is still version 1), the package stays internally consistent through review and
Complete regardless of what's currently in S3 or on-device, including a fully offline Complete.
`CurationRepository.completePackage` additionally rejects (`check`) if it's ever handed a category
list whose version doesn't match `category_list_version` — a safety backstop, not the normal path.
`category_list` is absent only on a manifest written before this existed; such a package falls back
to a freshly-fetched/bundled list for review and Complete, same risk as before this fix.

---

## Complete output

- Label files under `done/<id>/<subset>/labels/` carry the chosen class ids (`0..5`).
- **`data.yaml` is regenerated** (was copied verbatim in REQ-023): `nc` and `names` come from the
  category list (`names` in id order); the `device:` provenance block from the source `data.yaml`
  is preserved.
- `done/<id>/_manifest.json` gains `category_list_version` and `class_counts`
  (`{ "<key>": <box count over accepted items> }`).

---

## Infra — `aws-training-infra/aws/template.yaml`

`CuratorRole` `ReadDatasetPrefixes`: add `arn:aws:s3:::${BucketName}/config/*` to the
`s3:GetObject` resources. No write grant — the app never edits the list.

---

## Non-goals

- The on-device multi-class model / `PlateDetector` handling `nc > 1` (REQ-009).
- The access decision + gate UI (REQ-010).
- An in-app editor for the category list.
- Move / resize of existing boxes (REQ-024).
- Changing the main `android-end-user-app/` collector — it keeps writing class `0`; the curator assigns real
  types.

---

## Acceptance criteria

- [ ] The review screen shows a class dropdown per box, populated from
      `config/vehicle-categories.json`; with no network it falls back to the bundled list and still
      works.
- [ ] Accept is disabled until every box on the image has a class; the hint says so.
- [ ] Choosing a class updates `curation/<id>/manifest.json` (`box_classes`) within a couple of
      seconds; killing the app mid-image and reopening restores the chosen classes.
- [ ] "Add box" lets the curator drag a new rectangle; it appears in the box list with a class
      picker and can be deleted.
- [ ] After Complete, `done/<id>/` label files contain the chosen class ids; `data.yaml` has the
      category-list `nc` / `names` (id order) and the original `device:` block; `_manifest.json`
      has `category_list_version` and a `class_counts` map that sums to the accepted-box total.
- [ ] `CuratorRole` can read `config/*` but cannot write it, and still cannot write `uploads/*`.
- [ ] If the canonical list is extended (e.g. version 2 adds a class) after a package starts, that
      package's review and Complete still use its own version-1 snapshot (`category_list` in its
      manifest) — including a Complete performed fully offline — and `data.yaml`/`class_counts`
      stay internally consistent with the labels actually written, never mixing versions.
