---
id: REQ-030
title: Google Play Release Readiness — Next Production Release
status: draft
priority: high
---

> **Scope note (REQ-031):** the app has been split per
> [REQ-031](REQ-031-done-split-detection-and-training-apps.md), now done — the next release is a
> *simplification* (detection-only, nothing collected), not the account/upload-adding release this
> document originally assumed. Most of §§1–4 below still apply as written (version bump, store
> listing refresh, rollout strategy), but the Data Safety / account-deletion work in REQ-007 is now
> unnecessary rather than a blocker — REQ-007's scope note explains why. Release sequencing (is
> this split itself the next submission, or a separate smaller one first) is still an open call,
> not resolved by either document.

## Summary

"Car Plate Detector" is already published on Google Play (current build: `versionCode 11` /
`versionName 0.0.11`). Since that baseline, REQ-014 through REQ-029 have shipped a large amount of
new behavior — cloud upload, Cognito accounts, OCR, model auto-update, auth-failure recovery,
upload diagnostics — none of which has been reflected in a production release yet. This is the
concrete, one-time checklist to get the *next* release published safely. It assumes
[REQ-007](REQ-007-draft-play-store-compliance.md) (privacy/compliance content) is completed first
— this doc is about the release *mechanics*, not the disclosures themselves.

## Preconditions (do not start this checklist before these are true)

- [ ] REQ-007 acceptance criteria all checked, in particular the account-deletion mechanism (§2)
      and the rewritten `privacy-policy.md` (§4), both re-deployed and live at the listing's
      privacy-policy URL.
- [ ] `aws-training-infra/aws`'s `CuratorRole` `execute-api:Invoke` fix (REQ-029) has been `sam deploy`ed to
      production, and REQ-026–029 have had at least one real on-device pass (sign-in, capture,
      upload, forced-401 recovery) — not just unit tests.

## 1. Versioning

Current: `versionCode = 11`, `versionName = "0.0.11"` (`android-end-user-app/app/build.gradle.kts`).

- [ ] Bump `versionCode` (must strictly increase; Play rejects a re-used or lower code).
- [ ] Bump `versionName`. Given the scope of changes (accounts, cloud upload, OCR, curation
      pipeline) since the last shipped baseline, a minor bump (e.g. `0.1.0`) better signals this
      than another patch increment — your call, but keep it monotonic either way.

## 2. Target API level compliance

`compileSdk = 37`, `targetSdk = 37` — currently at the latest API level, so this release is not at
risk of Play's targetSdk-recency requirement. Re-check this each release cycle since Play's
minimum required target API level moves forward roughly once a year.

## 3. Data Safety, permissions, advertising ID

Covered in full by REQ-007 §§1, 2, 5. Do not proceed past this section until that document's
acceptance criteria are met.

## 4. Store listing content

- [ ] Screenshots — the Settings screen, ContributeScreen, and detection overlay have all changed
      materially (OCR label text, "Upload activity" line, sign-in rows, session-expired banner).
      If current listing screenshots predate these, refresh them.
- [ ] "What's new" / release notes copy — draft below, adjust to taste:

  > This update adds optional cloud-assisted model improvement: sign in to contribute detected
  > frames toward training better models, with full control over what's collected and a clear way
  > to delete your data. Also includes on-device text recognition (OCR) on detected plates,
  > automatic model updates, and reliability fixes for sign-in and uploads.

- [ ] Confirm the Privacy Policy URL field in Play Console still points to the freshly re-deployed
      `privacy-policy.md`.

## 5. Rollout strategy

This release changes account/data-handling behavior materially (new account creation flow, new
data-sharing declarations) — recommend **not** a full-percentage production push on day one:

- [ ] Prefer a staged rollout (e.g. start at 10–20% in the Play Console production track) or run
      it through Internal/Closed testing for a few days first if there's any doubt about the
      auth-recovery or upload-diagnostics changes (REQ-027–029) under real network conditions.
- [ ] Watch Play Console's pre-launch report and crash/ANR rate for the first cohort before
      widening rollout.

## 6. Signing and build

- [ ] Confirm `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` /
      `ANDROID_KEY_PASSWORD` secrets are still valid in the GitHub Actions environment (or your
      local signing setup) — a lost/rotated keystore means Play will reject the upload as a
      different signer.
- [ ] Build the release artifact: `./gradlew :app:bundleRelease` (`.aab` is what Play Console
      expects for a production upload; `.apk` is for direct/sideload distribution only).

## Acceptance criteria

- [ ] All items in §§1–6 checked.
- [ ] REQ-007 fully closed.
- [ ] Release uploaded to Play Console, Data Safety form matches REQ-007, rollout staged per §5.
