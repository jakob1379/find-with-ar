# Measurement trace schema

The app records UTF-8 newline-delimited JSON. The first non-empty line is one
`session` record. Schema v2 then contains `observation` and `event` records and ends
with exactly one `outcome`. An in-progress file has the suffix `.active.ndjson`; startup
recovery closes one as `INCOMPLETE` before it becomes export-ready.

The executable contract is shared by the Android encoder and
`analysis/src/ar_signal_analysis/schema.py`. Android-shaped v1 and v2 fixtures live in
`test-data/fixtures/`. Analysis accepts both versions.

## Session record

All versions contain `schema_version`, `record_type: "session"`, `session_id`,
`created_at_utc`, `app_version`, `device_model`, and
`coordinate_frame: "arcore_xz_m"`.

V2 adds `mode`, either `SINGLE_TARGET` or `COMPLETE_SCAN`. A single-target session has a
redacted `target` containing its `D01` session key, transport, manufacturer IDs, and
service UUIDs. Complete Scan has no selected target. V1 retains its original target and
optional calibration metadata. New V2 writers also record non-negative
`started_at_monotonic_ns`; readers accept older V2 records without it so crashed sessions
created before this field can still be recovered. Android writers also persist a per-boot
`boot_id`: the lowercase SHA-256 digest of UTF-8
`session_id + NUL + raw OS boot ID`, never the raw OS boot identifier. Recovery recomputes
the session-bound digest and uses a monotonic duration only while it matches; otherwise it
safely falls back to the wall-clock interval. Python preserves this optional field and
older V2 fixtures without it remain valid. V1 fields named `mode`, `boot_id`, or
`started_at_monotonic_ns` are opaque metadata, as they predate these V2 meanings.

## Observation record

V2 observations contain:

| Field | Meaning |
| --- | --- |
| `schema_version`, `record_type`, `session_id` | Version and owning session |
| `sequence` | Strictly increasing across every device in the session |
| `device_key` | Session-local `D01`, `D02`, … identity |
| `transport` | `low_energy` or `classic` |
| `app_monotonic_ns`, `platform_timestamp_ns` | Callback association and raw platform times |
| `rssi_dbm`, `estimator_rssi_dbm`, `tx_power_dbm` | Raw, accepted-estimator, and advertised power |
| `accepted`, `rejection_reason` | Estimator admission result |
| `pose`, `pose_association_age_ns` | Associated normalized AR pose and callback age |
| `filtered_rssi_dbm`, `rssi_variance_db2` | Robust signal diagnostics |
| `estimator` | Optional estimator status, mean, covariance, and region probability |
| `advertisement` | Manufacturer IDs and service UUIDs only |

An accepted V2 record has a normalized `TRACKING` pose and exact non-null
`estimator_rssi_dbm`, `rssi_variance_db2`, and `pose_association_age_ns` inputs. Covariance
is positive semidefinite. Observation sequences strictly increase and app-monotonic
observation timestamps do not decrease. V1 observations keep their existing fields and
legacy replay fallbacks; analysis derives their single device key from the session target.
All V2 integer fields fit a signed 64-bit integer, and device keys use only ASCII digits
with grammar `D[0-9]+`.

## Event record

V2 events contain `schema_version`, `record_type: "event"`, `session_id`,
`app_monotonic_ns`, optional `device_key` and matching `transport`, `code`, and optional
`detail`. Device key and transport are either both present or both null. Stable codes are:

- `ar_state`
- `scanner_state`
- `signal_lost`
- `signal_recovered`
- `estimator_state_changed`
- `recoverable_failure`

Release details contain no control characters and are limited to 200 characters. Blank
optional details are normalized to `null`.

## Outcome record

The final v2 record contains `ended_at_utc`, non-negative `duration_ms`, `outcome`
(`YES`, `NO`, `NOT_SURE`, `SKIPPED`, or `INCOMPLETE`), optional `reason` and note,
observed/localized device counts, `path_length_m`, and `device_states`. Each device state
contains its session key, transport, status, and optional final estimator snapshot. Notes
contain no control characters and are limited to 500 characters; blank optional notes are
normalized to `null`.

Reasons are fixed codes: `device_disappeared`, `device_hard_to_identify`,
`guidance_unclear`, `estimated_area_wrong`, `camera_ar_bluetooth_or_app_error`, and
`other`. A reason is valid only with `NO` or `NOT_SURE`; the optional bounded user note is
stored separately. Device states exactly cover the selected target and every observed or
device-scoped-event identity. Their transports remain stable, state and estimator statuses
agree, and observed and localized counts are derived from observations and final states
rather than trusted as independent metadata.

Normal and same-boot recovered durations use monotonic end minus
`started_at_monotonic_ns`. Recovery after reboot, or of an older V2 session without a
usable boot identity, uses `ended_at_utc - created_at_utc`. Observation
span is not a session-duration clock: a session with one or no packets can still have a
non-zero duration.

## Filesystem lifecycle

Each session starts as `signal-trace-<session-id>.active.ndjson`. Finishing appends its
outcome and publishes it as
`signal-trace-g<20-digit-completion-generation>-<session-id>.ndjson`. The app-local
generation, not wall-clock file metadata, orders and prunes new completed traces; legacy
filenames fall back to modification time. Startup recovers leftover active files with an
`INCOMPLETE` outcome and a new completion generation. Recovery streams records and
quarantines malformed active files as `.quarantined.ndjson`; neither active nor
quarantined files are exportable or listed in history. Their presence or a failed
publication/deletion remains a visible storage error. Delete all includes completed,
active, and quarantined trace files. The newest ten completed files are retained and their
app-private paths are the list/delete/export inputs. Android dispatches recovery, start,
stop, list, and deletion work to its I/O dispatcher.

## Privacy invariant

Hardware addresses and live advertised names exist only in process memory. V2 rejects
serialized address, `advertised_name`, and `display_name` fields. Exported device
identities are session-local keys reset for every session; traces contain no GPS or camera
frames or raw OS boot identifiers.
