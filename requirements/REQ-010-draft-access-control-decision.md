---
id: REQ-010
title: Parking Access Control — Decision Logic and UI
status: draft
priority: high
---

## Summary

Define how the app combines vehicle type classification (REQ-009) and plate detection (existing) to produce an access decision for the parking operator: AUTO-ALLOW, CHECK PLATE, or HOLD. The decision is displayed as a prominent, high-contrast banner on screen.

The civilian plate database lookup is **out of scope** for this requirement — the CHECK PLATE state is a placeholder that shows the OCR'd plate text for the operator to act on manually. A future requirement will define the database integration.

---

## Decision pipeline

```
VehicleClassification (from REQ-009)
  │
  ├── type is POLICE / AMBULANCE / FIRE_TRUCK   → AUTO-ALLOW
  │
  ├── type is MILITARY
  │     └── "auto-allow military" setting ON    → AUTO-ALLOW
  │         "auto-allow military" setting OFF   → CHECK PLATE
  │
  ├── type is CIVILIAN                          → CHECK PLATE
  │
  └── type is UNKNOWN or confidence < threshold
        └── "on uncertain type" setting:
              "allow"     → AUTO-ALLOW (with UNCERTAIN tag in log)
              "hold"      → HOLD (operator decides)
              "civilian"  → CHECK PLATE  ← default
```

---

## Configurable settings

Both settings live in the existing `model_prefs` SharedPreferences store and are exposed in the Settings screen under a new **"Parking access control"** section.

### 1. On uncertain vehicle type

When the vehicle type classifier confidence is below the configured threshold.

| Option | Behavior | When to use |
|---|---|---|
| **Treat as civilian** *(default)* | Proceed to plate check as if civilian | Highest security — no unrecognized vehicle auto-enters |
| **Allow** | AUTO-ALLOW | Prioritizes never blocking a real emergency vehicle |
| **Hold — operator decides** | Show HOLD banner, wait for manual input | Requires a person watching the screen |

Preference key: `access_uncertain_behavior`
Type: String — `"civilian"` / `"allow"` / `"hold"`
Default: `"civilian"`

### 2. Auto-allow military vehicles

Toggle: "Auto-allow military vehicles"
Sub-label: "Army and military vehicles are automatically allowed without plate check."
Default: **off**

Preference key: `access_allow_military`
Type: Boolean
Default: `false`

### 3. Vehicle type confidence threshold

The minimum classifier confidence to accept a vehicle type declaration as certain. Below this value the result is UNCERTAIN.

- Label: "Vehicle type confidence: X%"
- Slider: range 0.60–0.99, step 0.01
- Default: 0.85

Preference key: `vehicle_type_confidence_threshold`
Type: Float
Default: `0.85f`

### Settings section layout

```
─────────────────────────────────────────
Parking access control
─────────────────────────────────────────

[Toggle] Auto-allow military vehicles
         Army and military vehicles are automatically allowed.

On uncertain vehicle type:
  ○ Treat as civilian (recommended)
  ○ Allow
  ○ Hold — operator decides

Vehicle type confidence: 85%     [slider 60–99%]

─────────────────────────────────────────
```

---

## UI states

The access decision is displayed as a full-width banner at the **top** of the live detection screen, above the camera preview. The banner is always visible when the access control mode is active.

### AUTO-ALLOW

```
┌────────────────────────────────────────┐
│  ✓  AUTO-ALLOW                         │
│     Police vehicle  ·  94% confidence  │
└────────────────────────────────────────┘
```
Background: **green** (`#2E7D32`)
Text: white
Stays visible for 5 seconds, then returns to IDLE.

### CHECK PLATE

```
┌────────────────────────────────────────┐
│  ↗  CHECK PLATE                        │
│     АА 1234 ВС  ·  Civilian            │
└────────────────────────────────────────┘
```
Background: **blue** (`#1565C0`)
Text: white
The plate text comes from OCR. If OCR is disabled or no plate is detected, shows "plate not read — verify manually."
Stays visible until a new vehicle is detected or dismissed by operator.

### HOLD — Operator decision required

```
┌────────────────────────────────────────┐
│  ⏸  HOLD — VERIFY MANUALLY             │
│     Vehicle type unclear  ·  61%       │
└────────────────────────────────────────┘
```
Background: **orange** (`#E65100`)
Text: white
Accompanied by an audible alert (short beep, respects device volume/Do Not Disturb).

### IDLE

```
┌────────────────────────────────────────┐
│     Waiting for vehicle...             │
└────────────────────────────────────────┘
```
Background: dark overlay, low opacity.
Shown when no vehicle or plate is detected for > 3 seconds.

---

## Stability filter (debounce)

The classifier is running on every analysis frame (~8 fps). A single-frame classification must not immediately flip the banner. Apply a **majority-vote filter over the last 5 frames**:

- The decision banner updates only when the same decision appears in ≥ 3 of the last 5 frames.
- This prevents flickering between CIVILIAN and UNCERTAIN on a partially occluded vehicle.
- The filter resets when a new vehicle enters the frame (significant change in detection bounding box position).

---

## Access decision log

Every final decision (after stability filter) is appended to a local log.

**Storage:** `context.filesDir/access_log/access_log.csv` (append-only)
**Retention:** 90 days; entries older than 90 days are deleted on app start.
**Format:**

```
timestamp,vehicle_type,type_confidence,plate_text,decision,reason
2026-06-21T14:32:10,POLICE,0.94,,AUTO_ALLOW,confirmed_special
2026-06-21T14:33:05,CIVILIAN,0.91,АА1234ВС,CHECK_PLATE,confirmed_civilian
2026-06-21T14:35:22,UNKNOWN,0.61,,HOLD,uncertain_type
```

| Field | Description |
|---|---|
| `timestamp` | ISO-8601 local time |
| `vehicle_type` | Result from classifier |
| `type_confidence` | Top-class confidence |
| `plate_text` | OCR result, empty if not read |
| `decision` | `AUTO_ALLOW` / `CHECK_PLATE` / `HOLD` |
| `reason` | Machine-readable reason code |

**Reason codes:**

| Code | Meaning |
|---|---|
| `confirmed_special` | High-confidence police/ambulance/fire_truck |
| `confirmed_military` | High-confidence military + setting enabled |
| `confirmed_civilian` | High-confidence civilian |
| `military_check_plate` | Military detected but setting is off → plate check |
| `uncertain_allow` | Low confidence + setting = allow |
| `uncertain_hold` | Low confidence + setting = hold |
| `uncertain_civilian` | Low confidence + setting = treat as civilian |

**Log viewer in Settings:** last 50 entries in a scrollable list. "Export log (CSV)" button shares the full log via the share sheet.

---

## Privacy note

The access log contains license plate text and vehicle type. It is:
- Stored in `context.filesDir` (app-private, no storage permission needed).
- **Excluded from Android Auto Backup** (same `backup_rules.xml` / `data_extraction_rules.xml` as training data — add `access_log` to the exclusion).
- Deleted automatically after 90 days.
- Not transmitted anywhere.

Update `privacy-policy.md` to disclose the local access log when this feature is enabled.

---

## Out of scope for this requirement

- **Civilian plate database lookup** — the CHECK PLATE state is a manual prompt for now. Future requirement will add local SQLite or remote API integration.
- **Barrier control integration** — no physical output (relay, GPIO) is defined here. The decision is visual only.
- **Multiple vehicles in frame simultaneously** — the parking camera sees one vehicle at a time; this case is not handled.

---

## Acceptance criteria

- [ ] A police car detection triggers AUTO-ALLOW banner within 3 seconds of the vehicle being clearly in frame.
- [ ] A civilian car shows CHECK PLATE with the OCR plate text.
- [ ] Changing "On uncertain vehicle type" setting takes effect immediately without restarting the camera.
- [ ] The banner does not flicker — the majority-vote filter prevents frame-to-frame oscillation.
- [ ] Every final decision is written to the CSV log with correct timestamp and reason code.
- [ ] Log entries older than 90 days are cleaned up on app start.
- [ ] The access log directory is excluded from Android Auto Backup.
- [ ] AUTO-ALLOW banner is clearly readable in bright outdoor daylight (text contrast ratio ≥ 4.5:1).
- [ ] When the vehicle type classifier is absent (graceful degradation from REQ-009), all vehicles are treated per the "On uncertain type" setting.
