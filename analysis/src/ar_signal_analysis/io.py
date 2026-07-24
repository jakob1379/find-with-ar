"""NDJSON trace input/output and tabular normalization."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pandas as pd

from ar_signal_analysis.schema import (
    EventRecord,
    Trace,
    TraceValidationError,
    ensure_json_object,
    parse_event,
    parse_observation,
    parse_outcome,
    parse_session,
    reject_private_fields,
    validate_trace,
)


def load_trace(path: str | Path) -> Trace:
    """Load and validate a versioned NDJSON trace.

    Blank lines are ignored. The first non-blank line must be the session record.
    V2 may then contain observations and events and must end with one outcome.
    """

    source = Path(path)
    parsed: list[tuple[int, dict[str, Any]]] = []
    try:
        with source.open(encoding="utf-8") as stream:
            for line_number, raw_line in enumerate(stream, start=1):
                if not raw_line.strip():
                    continue
                try:
                    value = json.loads(raw_line)
                except json.JSONDecodeError as error:
                    raise TraceValidationError(
                        f"invalid JSON: {error.msg}", source=source, line=line_number
                    ) from error
                except ValueError as error:
                    raise TraceValidationError(
                        f"invalid JSON number: {error}", source=source, line=line_number
                    ) from error
                mapping = ensure_json_object(value, source=source, line=line_number)
                parsed.append((line_number, dict(mapping)))
    except OSError as error:
        raise TraceValidationError(f"cannot read trace: {error}", source=source) from error

    if not parsed:
        raise TraceValidationError("trace is empty", source=source)

    session_line, session_raw = parsed[0]
    session = parse_session(session_raw, source=source, line=session_line)
    observations = []
    events = []
    outcome = None
    for index, (line_number, value) in enumerate(parsed[1:], start=1):
        record_type = value.get("record_type")
        if record_type == "observation":
            observations.append(parse_observation(value, source=source, line=line_number))
        elif record_type == "event":
            events.append(parse_event(value, source=source, line=line_number))
        elif record_type == "outcome":
            if outcome is not None:
                raise TraceValidationError(
                    "trace has more than one outcome", source=source, line=line_number
                )
            if index != len(parsed) - 1:
                raise TraceValidationError(
                    "outcome must be the final record", source=source, line=line_number
                )
            outcome = parse_outcome(value, source=source, line=line_number)
        else:
            raise TraceValidationError(
                f"unsupported record_type {record_type!r}", source=source, line=line_number
            )
    trace = Trace(
        session=session,
        observations=tuple(observations),
        events=tuple(events),
        outcome=outcome,
        source=source,
    )
    validate_trace(trace)
    return trace


def write_trace(trace: Trace, path: str | Path) -> Path:
    """Validate and write a trace as canonical compact NDJSON."""

    validate_trace(trace)
    destination = Path(path)
    timed_records = sorted(
        [*trace.observations, *trace.events],
        key=lambda item: (item.app_monotonic_ns, isinstance(item, EventRecord)),
    )
    records = [trace.session.to_json(), *(item.to_json() for item in timed_records)]
    if trace.outcome is not None:
        records.append(trace.outcome.to_json())
    if trace.session.schema_version == 2:
        for line, record in enumerate(records, start=1):
            reject_private_fields(record, source=destination, line=line)
    payload = "".join(
        f"{json.dumps(record, sort_keys=True, separators=(',', ':'))}\n" for record in records
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(payload, encoding="utf-8")
    return destination


def observations_frame(trace: Trace) -> pd.DataFrame:
    """Return observations in a flat, nullable pandas DataFrame."""

    rows: list[dict[str, Any]] = []
    for observation in trace.observations:
        pose = observation.pose
        estimator = observation.estimator
        rows.append(
            {
                "session_id": observation.session_id,
                "device_key": observation.device_key,
                "transport": observation.transport,
                "sequence": observation.sequence,
                "app_monotonic_ns": observation.app_monotonic_ns,
                "platform_timestamp_ns": observation.platform_timestamp_ns,
                "rssi_dbm": observation.rssi_dbm,
                "estimator_rssi_dbm": observation.estimator_rssi_dbm,
                "tx_power_dbm": observation.tx_power_dbm,
                "accepted": observation.accepted,
                "rejection_reason": observation.rejection_reason,
                "pose_association_age_ns": observation.pose_association_age_ns,
                "filtered_rssi_dbm": observation.filtered_rssi_dbm,
                "rssi_variance_db2": observation.rssi_variance_db2,
                "pose_x_m": pose.x_m if pose is not None else None,
                "pose_y_m": pose.y_m if pose is not None else None,
                "pose_z_m": pose.z_m if pose is not None else None,
                "tracking_state": pose.tracking_state if pose is not None else None,
                "estimator_kind": estimator.kind if estimator is not None else None,
                "estimate_x_m": estimator.mean_x_m if estimator is not None else None,
                "estimate_z_m": estimator.mean_z_m if estimator is not None else None,
                "covariance_xx_m2": (estimator.covariance_xx_m2 if estimator is not None else None),
                "covariance_xz_m2": (estimator.covariance_xz_m2 if estimator is not None else None),
                "covariance_zz_m2": (estimator.covariance_zz_m2 if estimator is not None else None),
            }
        )
    return pd.DataFrame(rows).convert_dtypes()
