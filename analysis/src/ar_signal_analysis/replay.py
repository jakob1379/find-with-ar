"""Deterministic reconstruction of the estimator's ordered input stream."""

from __future__ import annotations

import json
import math
from collections.abc import Sequence
from typing import Any

from ar_signal_analysis.schema import Trace

DEFAULT_MEASUREMENT_VARIANCE_DB2 = 9.0


def estimator_inputs(trace: Trace, device_key: str | None = None) -> tuple[dict[str, Any], ...]:
    """Return accepted spatial observations in the exact recorded sequence order."""

    result: list[dict[str, Any]] = []
    for observation in trace.observations:
        observation_device_key = _device_key(trace, observation.device_key)
        if not observation.accepted or (
            device_key is not None and observation_device_key != device_key
        ):
            continue
        pose = observation.pose
        if pose is None:  # Validation already prevents this; keep the boundary explicit.
            continue
        forward_x = -2.0 * (pose.qx * pose.qz + pose.qw * pose.qy)
        forward_z = -(1.0 - 2.0 * (pose.qx * pose.qx + pose.qy * pose.qy))
        forward_length = math.hypot(forward_x, forward_z)
        is_v2 = trace.session.schema_version == 2
        record = {
            "elapsed_realtime_ns": observation.app_monotonic_ns,
            "measurement_variance_db2": (
                observation.rssi_variance_db2
                if is_v2
                else observation.rssi_variance_db2 or DEFAULT_MEASUREMENT_VARIANCE_DB2
            ),
            "phone_forward_x": forward_x / forward_length if forward_length > 1e-6 else None,
            "phone_forward_z": forward_z / forward_length if forward_length > 1e-6 else None,
            "phone_height_m": pose.y_m,
            "phone_x_m": pose.x_m,
            "phone_z_m": pose.z_m,
            "rssi_dbm": (
                observation.estimator_rssi_dbm
                if is_v2 or observation.estimator_rssi_dbm is not None
                else (
                    observation.filtered_rssi_dbm
                    if observation.filtered_rssi_dbm is not None
                    else float(observation.rssi_dbm)
                )
            ),
            "sequence": observation.sequence,
            "session_id": observation.session_id,
        }
        if is_v2:
            record["device_key"] = observation_device_key
            record["pose_association_age_ns"] = observation.pose_association_age_ns
        result.append(record)
    return tuple(result)


def estimator_inputs_by_device(trace: Trace) -> dict[str, tuple[dict[str, Any], ...]]:
    """Return deterministic estimator inputs grouped by session-local device key."""

    keys = sorted({_device_key(trace, item.device_key) for item in trace.observations})
    return {key: estimator_inputs(trace, key) for key in keys}


def format_estimator_inputs(records: Sequence[dict[str, Any]]) -> str:
    """Encode estimator inputs as canonical NDJSON for byte-identical replay checks."""

    return "".join(
        f"{json.dumps(record, sort_keys=True, separators=(',', ':'), allow_nan=False)}\n"
        for record in records
    )


def _device_key(trace: Trace, observation_key: str | None) -> str:
    if observation_key is not None:
        return observation_key
    target = trace.session.metadata.get("target")
    if isinstance(target, dict) and isinstance(target.get("session_key"), str):
        return target["session_key"]
    return "D01"
