"""Deterministic reconstruction of the estimator's ordered input stream."""

from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

from ar_signal_analysis.schema import Trace

DEFAULT_MEASUREMENT_VARIANCE_DB2 = 9.0


def estimator_inputs(trace: Trace) -> tuple[dict[str, Any], ...]:
    """Return accepted spatial observations in the exact recorded sequence order."""

    result: list[dict[str, Any]] = []
    for observation in trace.observations:
        if not observation.accepted:
            continue
        pose = observation.pose
        if pose is None:  # Validation already prevents this; keep the boundary explicit.
            continue
        result.append(
            {
                "elapsed_realtime_ns": observation.app_monotonic_ns,
                "measurement_variance_db2": (
                    observation.rssi_variance_db2
                    if observation.rssi_variance_db2 is not None
                    else DEFAULT_MEASUREMENT_VARIANCE_DB2
                ),
                "phone_height_m": pose.y_m,
                "phone_x_m": pose.x_m,
                "phone_z_m": pose.z_m,
                "rssi_dbm": (
                    observation.filtered_rssi_dbm
                    if observation.filtered_rssi_dbm is not None
                    else float(observation.rssi_dbm)
                ),
                "sequence": observation.sequence,
                "session_id": observation.session_id,
            }
        )
    return tuple(result)


def format_estimator_inputs(records: Sequence[dict[str, Any]]) -> str:
    """Encode estimator inputs as canonical NDJSON for byte-identical replay checks."""

    return "".join(
        f"{json.dumps(record, sort_keys=True, separators=(',', ':'), allow_nan=False)}\n"
        for record in records
    )
