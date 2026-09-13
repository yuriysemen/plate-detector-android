# Requirements

Spec-driven development trail for this repo: one `REQ-NNN-<status>-<title-slug>.md` file per
feature, written before implementation and kept up to date afterward. `status` in the filename
mirrors the `status:` field in the doc's own frontmatter — `draft` (proposed, not yet built),
`done` (implemented), or `superseded` (replaced by a later REQ, see `superseded_by:`).

| REQ | Status | Title |
|---|---|---|
| [001](REQ-001-draft-overview.md) | draft | Training Data Collection — Overview & Scope |
| [002](REQ-002-done-yolo-format-and-file-structure.md) | done | YOLO Format Specification and Directory Structure |
| [003](REQ-003-done-coordinate-transformation.md) | done | Coordinate Transformation — Detection Pixels to YOLO Normalized |
| [004](REQ-004-done-multiple-detections-per-frame.md) | done | Multiple Detections Per Frame — Investigation and Handling |
| [005](REQ-005-done-settings-and-controls.md) | done | Settings UI and Preference Keys for Data Collection |
| [006](REQ-006-done-storage-quota-and-export.md) | done | Storage Quota, LRU Eviction, and Dataset Export |
| [007](REQ-007-draft-play-store-compliance.md) | draft | Google Play Compliance and Privacy Policy Changes |
| [008](REQ-008-superseded-local-vs-cloud-storage.md) | superseded | Local-Only vs. Cloud Upload — Analysis and Decision |
| [009](REQ-009-draft-vehicle-type-detection.md) | draft | Vehicle Type Detection — Police, Medical, Fire vs Civilian |
| [010](REQ-010-draft-access-control-decision.md) | draft | Parking Access Control — Decision Logic and UI |
| [011](REQ-011-done-dataset-editor-and-storage-quota.md) | done | Dataset Editor and Configurable Storage Quota |
| [012](REQ-012-superseded-frame-detail-zoom.md) | superseded | Zoom and Pan in Frame Detail Editor |
| [013](REQ-013-done-file-naming-and-dataset-metadata.md) | done | Dataset File Naming and data.yaml Device Metadata |
| [014](REQ-014-done-cloud-dataset-upload.md) | done | Cloud Dataset Upload — Pre-signed URL Upload via WorkManager |
| [015](REQ-015-done-scheduled-dataset-upload.md) | done | Scheduled Automatic Dataset Upload (Daily) |
| [016](REQ-016-done-model-distribution-and-auto-update.md) | done | Model Distribution — S3 Storage, Authenticated Lambda, and On-Device Auto-Update |
| [017](REQ-017-draft-autoparking-auto-configuration.md) | draft | Auto-parking Settings Auto-configuration |
| [018](REQ-018-done-aws-upload-infrastructure.md) | done | AWS Upload Infrastructure — S3 Bucket, Lambda, API Gateway, and Cognito Auth |
| [019](REQ-019-done-settings-screen.md) | done | Settings Screen Layout and Controls |
| [020](REQ-020-done-manual-frame-capture.md) | done | Manual Frame Capture Button |
| [021](REQ-021-done-burst-frame-collection.md) | done | Burst Frame Collection Mode |
| [022](REQ-022-done-curation-android-overview-and-auth.md) | done | Dataset Curation Android App — Overview, Authentication & AWS Access |
| [023](REQ-023-done-curation-android-package-workflow.md) | done | Dataset Curation Android App — Package Workflow (Not Processed / In Progress / Done) |
| [024](REQ-024-done-curation-android-review-editor.md) | done | Dataset Curation Android App — Review Editor (Accept, Edit, Reject) |
| [025](REQ-025-done-curation-vehicle-type-categories.md) | done | Dataset Curation — Vehicle-Type Categories and Typed Boxes |
| [026](REQ-026-done-generic-app-capture-only-no-editing.md) | done | Generic App — Remove On-Device Review/Editing; Capture-and-Upload Only |
| [027](REQ-027-done-auth-failure-recovery.md) | done | Auth-Failure Detection and Always-Available Re-Sign-In |
| [028](REQ-028-done-upload-diagnostics.md) | done | Upload Diagnostics — Failure Reasons and a Persistent Activity Log |
| [029](REQ-029-done-auth-robustness.md) | done | Auth Robustness — Curator API Access + Login/Logout Failure-Path Hardening |
