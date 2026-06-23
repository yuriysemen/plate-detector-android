---
id: REQ-017
title: Auto-parking Settings Auto-configuration
status: draft
priority: medium
---

## Summary

Auto-parking mode currently requires the user to manually configure detection thresholds, sensitivity, and trigger behaviour. These settings should be pre-configured automatically based on context (first launch defaults, device capability, or location pattern recognition), reducing setup friction to zero for the common case.

> **Note:** The exact triggers for auto-configuration are partially undefined. This requirement covers three progressive levels of automation; the team should decide which level to implement first before implementation begins.

---

## Level 1 — Smart defaults on first enable (recommended for v1)

When the user enables auto-parking mode for the first time:

1. The app inspects the device and selects the best-fit preset:

   | Condition | Preset applied |
   |---|---|
   | Camera resolution ≥ 1280×720 and CPU cores ≥ 6 | **High-quality** — lower confidence threshold (0.45), higher frame rate (15 fps analysis) |
   | Camera resolution ≥ 640×480 | **Balanced** — default threshold (0.55), standard frame rate (10 fps analysis) |
   | Low-end device (< 640×480 or < 4 cores) | **Efficient** — higher threshold (0.65), reduced frame rate (5 fps analysis) |

2. Settings are written to `SharedPreferences` as if the user had set them manually. The user can override any value afterwards.
3. A brief tooltip or banner: "Auto-parking configured for your device. You can adjust in Settings."
4. Auto-configuration never re-runs automatically after first enable (it is a one-time initialisation). The user can trigger it again via a "Reset to recommended defaults" button in Settings → Auto-parking.

---

## Level 2 — Location-based adaptation (future)

When the user parks in the same location repeatedly (GPS geofence within 50 m radius, at least 3 times), the app learns the detection conditions for that location (indoor garage with low light vs. outdoor lot with variable sun angle) and suggests adjustments:

- "You park here often. Adjust sensitivity for indoor lighting? [Apply] [Dismiss]"
- Applied as an override for that geofence; default settings remain unchanged for other locations.

**Prerequisite:** requires `ACCESS_FINE_LOCATION` permission. Prompt only requested if the user enables location-based adaptation. Currently out of scope for v1.

---

## Level 3 — Continuous adaptive tuning (future)

Over time, the app tracks detection quality metrics (false positive rate estimated from user corrections in REQ-011) and auto-adjusts the confidence threshold to maintain a target precision. Fully automatic, no user prompts.

**Prerequisite:** requires sufficient correction data from REQ-011 and a local lightweight calibration loop. Currently out of scope.

---

## Settings surface

Settings → Auto-parking:

- **"Reset to recommended defaults"** — button that re-runs the Level 1 device-capability preset logic and resets all auto-parking settings to the computed values. Shows a confirmation dialog before resetting.
- **"Location-based adaptation"** — toggle (default off, Level 2, future).
- Existing manual controls (confidence threshold, frame rate, etc.) remain fully accessible and override the auto-configured values.

---

## Acceptance criteria (Level 1)

- [ ] When auto-parking is enabled for the first time, the device-capability check runs and one of the three presets is applied to `SharedPreferences`.
- [ ] The preset is applied before the first frame is analysed (not after a session starts).
- [ ] A one-time informational banner is shown confirming that settings were auto-configured.
- [ ] The auto-configuration does not run again on subsequent enables (unless the user taps "Reset to recommended defaults").
- [ ] "Reset to recommended defaults" shows a confirmation dialog, then re-runs the preset logic and overwrites current auto-parking settings.
- [ ] All auto-configured values can be overridden by the user in Settings → Auto-parking.
- [ ] The feature works without any network access or additional permissions beyond those already declared.
