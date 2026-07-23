# Measurement trace schema

The app records newline-delimited JSON. The first non-empty line is one `session` record; every later line is an `observation`. Version 1 uses UTF-8 and app-monotonic nanoseconds for BLE/pose association.

The executable contract is shared by the Android encoder and `analysis/src/ar_signal_analysis/schema.py`. A recorder-shaped fixture lives at `test-data/fixtures/android_trace_v1.ndjson` and is loaded by the Python test suite.

## Session record

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer, `1` | Trace contract version |
| `record_type` | string, `session` | Record discriminator |
| `session_id` | non-empty string | Random identifier for this capture |
| `created_at_utc` | RFC 3339 UTC string | Capture creation time |
| `app_version` | string | Android build version |
| `device_model` | string | Phone manufacturer/model diagnostic |
| `coordinate_frame` | string, `arcore_xz_m` | Floor-anchor-relative metres |
| `target` | object | Session-local key, display name, manufacturer IDs and service UUIDs |
| `environment` | optional string | Calibration label such as `open` or `furnished` |
| `ground_truth` | optional `{x_m,z_m}` | Labelled benchmark coordinate |

Additional session keys are retained as metadata by the analysis loader.

## Observation record

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer, `1` | Must match the session |
| `record_type` | string, `observation` | Record discriminator |
| `session_id` | string | Must match the session |
| `sequence` | increasing integer | Deterministic input order |
| `app_monotonic_ns` | non-negative integer | BLE callback time from `elapsedRealtimeNanos` |
| `platform_timestamp_ns` | non-negative integer | Raw BLE `ScanResult` timestamp, diagnostic only |
| `rssi_dbm` | integer | Raw RSSI in `[-127,20]` |
| `tx_power_dbm` | integer or null | Advertised Tx power when available |
| `accepted` | boolean | Whether this packet became an estimator input |
| `rejection_reason` | string or null | For example `no_recent_pose` or `correlated_spatial_bucket` |
| `pose` | object or null | Anchor-relative `x/y/z`, quaternion and tracking state |
| `estimator` | object or null | Kind/status, optional mean, covariance and region probability |
| `pose_association_age_ns` | integer or null | BLE-to-pose callback age |
| `filtered_rssi_dbm` | number or null | Robust rolling median |
| `rssi_variance_db2` | number or null | MAD-derived variance with a noise floor |
| `advertisement` | object | Name/manufacturer/service diagnostics without an address |

An accepted record must contain a normalized `TRACKING` pose. Estimator covariance, when present, must be positive semidefinite. Sequences are strictly increasing and app-monotonic timestamps are non-decreasing.

## Privacy invariant

Android Bluetooth addresses are used only inside the active process to keep scanning the selected radio. They are never serialized. The exported `target.session_key` is a short `D01`, `D02`, … identifier that is reset for each discovery session.
