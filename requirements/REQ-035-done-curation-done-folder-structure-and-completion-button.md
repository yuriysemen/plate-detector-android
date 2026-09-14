---
id: REQ-035
title: Curation — Mirror Upload Folder Structure in done/, Surface Complete in ReviewScreen
status: done
priority: medium
---

## Summary

Three related gaps found by actually using the review workflow end to end:

1. **No visible "you're done" affordance inside `ReviewScreen`.** "Complete" already exists today
   — but only as a button on the package's row in the **In Progress tab list**
   (`PackageTabs.kt`, enabled once `pending == 0`), not anywhere inside `ReviewScreen` itself. A
   curator who just reviewed the last image has to back out of the review screen and find the
   package again in the list to finish it.
2. **`done/`'s folder structure doesn't mirror the original upload's.** Today, `completePackage()`
   writes accepted items to `done/<packageId>/<subset>/images|labels/...`, where `packageId` is a
   **flat, sanitized string** — `<user_sub>__<device_id>__<filename-stem>` (`PackageId.kt`) — not a
   nested path. The original upload, by contrast, lives at
   `uploads/<user_sub>/<device_id>/<filename>.zip` — three real path segments plus a single `.zip`
   file as the leaf, not a folder. You asked for `done/` to use the *same* nested path shape, with
   something appended to the leaf name to mark it as curated and when.
3. **`done/` should be one compressed file per package, not one S3 object per image/label.** A
   package can have hundreds of images; today `completePackage()` does an individual S3 `PUT` for
   every accepted item's image *and* its label (two requests per item — 200+ requests for a
   100-image package). You want the curated output uploaded as a **single compressed archive**
   instead — which also happens to be exactly how the *original* `uploads/<sub>/<device>/<file>.zip`
   already works, and how `training-android` already packages its own uploads. This resolves gap 2
   more precisely than my first draft did: since `uploads/` itself is a single `.zip` file at that
   nested path, not a folder, the truly parallel shape for `done/` is a single `.zip` file too.

## Current behavior (for reference)

```
uploads/<sub>/<device>/<filename>.zip                              ← original upload (nested path)

curation/<sub>__<device>__<stem>/manifest.json                     ← working state (flat id)
done/<sub>__<device>__<stem>/<subset>/images/<image>.jpg            ← completed output (flat id)
done/<sub>__<device>__<stem>/<subset>/labels/<basename>.txt
done/<sub>__<device>__<stem>/data.yaml
done/<sub>__<device>__<stem>/_manifest.json
rejected/<sub>__<device>__<stem>/<subset>/images|labels/...         ← rejected items (flat id)
```

## Proposed change

### 1. Complete, reachable from `ReviewScreen`

**Resolved (Q2):** a **"Complete" action in the top app bar**, next to the existing add-box/jump-to
icons — always visible for the whole review session, not conditionally shown. It's **enabled only
when every item has been taken to a decision** — `session.manifest.pending == 0`, the same
condition `PackageTabs.kt`'s list-row Complete button already uses — and disabled (greyed out)
otherwise. Tapping it calls the same `vm.complete(manifest) { ... }` the list screen's button
calls today; no new completion logic, just a second, more convenient place to trigger it without
leaving the review screen. (`complete()` itself was extended to drop the open session on success —
see "Implementation notes" below — so the review screen doesn't need its own navigate-back
callback; leaving the completed session, once cleared, falls straight through to the tab list.)

### 2. `done/` (and `rejected/`) mirror the upload's nested path — as a single zip

```
done/<sub>/<device>/<filename-stem>-curated-<YYYYMMDD>_<HHmmss>.zip           ← one archive: <subset>/images/, <subset>/labels/, data.yaml
done/<sub>/<device>/<filename-stem>-curated-<YYYYMMDD>_<HHmmss>._manifest.json  ← sidecar, NOT zipped in — see why below
```

- `<sub>`, `<device>`, `<filename-stem>` are exactly what's already in `CurationManifest.userSub` /
  `.deviceId` / `.filename` — no new data needed, just used as three path segments instead of
  concatenated into one.
- `<YYYYMMDD>_<HHmmss>` (the completion timestamp, UTC) matches the date/time format already used
  elsewhere in this codebase for filenames (frame capture naming in `android/`/`training-android`)
  rather than introducing a new convention.
- This keeps a completed package traceable straight back to exactly which raw upload it came from
  by just reading the path — no need to open `_manifest.json` to find `sourceKey`.
- **The zip is built on-device** (in `CurationRepository.completePackage()`, using the already
  locally-cached accepted images/labels — no new download needed) **and uploaded as one S3 `PUT`**,
  replacing today's per-file loop. For a 100-image package that's 2 requests total (archive +
  manifest sidecar) instead of 200+.
- **`_manifest.json` stays a separate, un-zipped sidecar object**, not bundled inside the archive.
  `CurationRepository.listDone()` (the Done tab) works today by listing every `**/_manifest.json`
  key under `done/` and reading each one directly — small, individually fetchable JSON files. If
  `_manifest.json` moved inside the zip, populating the Done tab would mean downloading and
  unzipping every completed package just to show a summary list. Keeping it as a sidecar with the
  same base name as its archive preserves that cheap listing, at the minor cost of two objects
  per completed package instead of a single self-contained one.
- **`rejected/`** gets the same nested-path-plus-zip treatment (Q1, resolved below):
  `rejected/<sub>/<device>/<filename-stem>-rejected-<timestamp>.zip` — a distinct `-rejected-`
  suffix rather than reusing `-curated-`, since these items were reviewed but not accepted into the
  curated dataset. No `_manifest.json` sidecar for `rejected/` — nothing lists it today (there's no
  "Rejected" tab), so there's nothing that needs cheap enumeration.

### What's unaffected

- `curation/<packageId>/manifest.json` (the transient in-progress working file) and the on-device
  cache (`filesDir/packages/<packageId>/`) — both stay flat-id-keyed, and the working copy stays
  unzipped locally during review (editing hundreds of loose files in a zip in place isn't
  practical). Only the final `done/` upload changes shape.
- `PackageId.packageIdOf()` itself, and everywhere it's used to key `curation/`/local cache, is
  unchanged. This only touches what `completePackage()` writes to `done/`.
- `data.yaml`'s own regeneration logic (`rewriteDataYamlHeader`) is unchanged — it just ends up
  inside the zip instead of as a loose object.

## Resolved questions

**Q1. Does `rejected/` get the same nested-path-plus-zip treatment?**
A: Yes.

**Q2. Exact placement of the in-`ReviewScreen` Complete affordance?**
A: Always-visible top-app-bar action, enabled/disabled by whether every item has been taken to a
decision (accepted → `done/`, rejected → `rejected/`) — see "Proposed change" §1 above.

**Q3. Already-completed / already-reviewed packages — leave as-is, or migrate to the new shape?**
A: Leave each reviewed package as its own separate zip file, exactly as completed — no retroactive
merging or migration. A later, separate feature will assemble the actual training dataset by
pulling from these individual curated zips (not part of this requirement).

**Q4. Completion time or package-start time for the archive's timestamp?**
A: Completion time.

## Acceptance criteria

- [x] `ReviewScreen`'s top app bar shows a "Complete" action at all times during a review session,
      enabled only when every item in the package has been accepted or rejected (no `PENDING` items
      remain), disabled otherwise — same trigger condition `PackageTabs.kt`'s list-row Complete
      button already uses.
- [x] Tapping it completes the package exactly as today's list-screen Complete button does, without
      requiring navigation back to the In Progress list.
- [x] A newly completed package uploads exactly one archive —
      `done/<sub>/<device>/<filename-stem>-curated-<timestamp>.zip` — containing every accepted
      item's image, label, and `data.yaml`, plus one small `_manifest.json` sidecar alongside it
      (not inside the archive).
- [x] Rejected items upload as `rejected/<sub>/<device>/<filename-stem>-rejected-<timestamp>.zip`,
      no manifest sidecar.
- [x] Completing a 100-item package results in a small, constant number of S3 `PUT` requests (the
      two archives plus the manifest sidecar), not 200+.
- [x] The Done tab (`listDone()`) still works unmodified — it only ever reads `_manifest.json`
      sidecars, never needs to open an archive.
- [x] Existing `done/`/`rejected/` output from before this ships is untouched — no migration, each
      already-completed package remains exactly as it is.
- [x] `curation-android/CLAUDE.md` updated to describe the new `done/`/`rejected/` path shapes and
      why the manifest stays a sidecar instead of living inside the zip.

## Implementation notes

Two things came up during implementation that weren't fully worked out in the design above:

- **Backward compatibility for `listNotProcessed()`/`listDone()`.** Both functions detect a
  completed package by scanning for a `_manifest.json` sidecar under `done/`. The new nested
  naming (`done/<sub>/<device>/<stem>-curated-<ts>._manifest.json`) doesn't match the old flat
  shape's suffix (`done/<packageId>/_manifest.json`) — without handling both, every package
  completed *before* this shipped would have silently reappeared in Not Processed (and vanished
  from the Done tab) the moment this landed. Fixed by having both functions recognize either
  naming scheme: `PackageId.packageIdFromDoneManifestKey()` parses the new shape and returns null
  for anything else, with a string-suffix fallback for the old flat shape.
- **`CurationViewModel.complete()` could resurrect a just-deleted manifest.** Completing from
  *inside* an open `ReviewScreen` session is new — previously Complete only ever ran from the list
  screen, where no session was open. `completePackage()` deletes `curation/<id>/manifest.json` as
  its last step; if the session were left open afterward, the periodic heartbeat or a debounced
  box-edit save could PUT that file right back. Fixed by having `complete()` clear the session
  directly on success (when it's the currently-open one), rather than relying on the caller to
  separately call `closeSession()` afterward.
