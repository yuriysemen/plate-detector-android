---
id: REQ-034
title: Curation — Drop Reject Reason, Allow Changing an Already-Decided Item
status: done
priority: medium
---

## Summary

Two related simplifications to `android-training-data-reviewing-app`'s review workflow:

1. **No more reject-reason prompt.** Tapping Reject used to open a dialog asking for an optional
   reason before rejecting. That's gone — Reject now happens immediately, no dialog.
2. **A decided item can be changed.** Previously, once an item was Accepted or Rejected it was
   permanently read-only for the rest of the session — `CLAUDE.md` explicitly called this "out of
   scope for v1 (Release the package to redo)," i.e. the only way to fix a mistake was to release
   the whole package back to Not Processed and have someone start over from scratch. Now a decided
   item shows a **"Change decision"** button that reverts it to `PENDING`, in place, without
   affecting any other item in the package.

## Why

Both were friction discovered in real use: entering a reason for every reject slowed down the
common case (most rejects don't need an explanation), and losing the ability to fix a
misclick/mistake without redoing an entire package's worth of review was a disproportionate cost
for a single wrong tap.

## What changed

- **`CurationViewModel.reject()`** — now takes no argument; always rejects with `reason = null`.
  (`ManifestItem.reason` and its display in the review hint / `DoneManifest` are unchanged — an
  item rejected *before* this shipped still shows whatever reason was recorded for it.)
- **`ReviewScreen`** — the reject dialog (`showReject` state, the reason `OutlinedTextField`) is
  gone. Reject is now a single tap.
- **`CurationViewModel.undecide()`** (new) — reverts the *current* item's status back to `PENDING`
  via the existing `CurationManifest.withDecision(index, PENDING)` (no new manifest schema
  needed — passing `PENDING` already nulls out `labelContent`/`reason`/`decidedBy`/`decidedAt` by
  that function's existing logic). Unlike `decide()`, it does **not** advance to the next pending
  item — the curator stays put to actually fix the thing they reverted it to fix.
  `workingLabel` (the curator's box edits, if any) is untouched by either `decide()` or
  `undecide()`, so reopening a reverted item for editing shows exactly the box state it had right
  before the original decision — no data loss either direction.
- **`ReviewScreen`**'s bottom action row now shows **Reject / Accept** when the current item is
  `PENDING`, or a single **Change decision** button when it's already `ACCEPTED`/`REJECTED` —
  tapping it flips the item back to `PENDING` and the row immediately switches back to Reject/
  Accept for that same item.
- **`reviewHint()`** text updated from "— read-only (Release the package to redo)" to "— tap
  Change decision to edit or re-accept/re-reject".
- **No confirmation dialog** on either Reject or Change decision — both are cheap to reverse
  (Change decision undoes a Reject/Accept; Change decision again undoes a mistaken revert), so a
  confirmation step would mostly just add friction without preventing anything unrecoverable.

## What's unaffected

- **`complete()` / `completePackage()`** — once a package is completed, its items are uploaded to
  `done/`/`rejected/` in S3 and the local working copy is deleted (existing behavior, REQ-023).
  Changing a decision only makes sense while the package is still open (In Progress) in an active
  review session — there is no "undo" after Complete, same as before this change.
- **Per-item attribution / `reviewers()` tallies** — a reverted item drops out of the
  accepted/rejected counts until it's decided again, which is correct (it's `PENDING` again, same
  as any other not-yet-reviewed item).

## Acceptance criteria

- [x] Tapping Reject on a pending item rejects it immediately, no dialog.
- [x] An Accepted or Rejected item shows a "Change decision" button instead of Reject/Accept.
- [x] Tapping "Change decision" reverts the item to `PENDING`, in place (session index unchanged),
      with box editing and Reject/Accept immediately available again for that item.
- [x] The item's box state (workingLabel) after reverting matches what it was right before the
      original decision — no boxes are lost or reset.
- [x] `android-training-data-reviewing-app/CLAUDE.md` updated (the old "out of scope for v1" note removed / replaced).
