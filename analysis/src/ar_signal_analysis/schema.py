"""Versioned trace model and strict validation helpers."""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from math import isfinite
from pathlib import Path
from typing import Any

SCHEMA_NAME = "ar-signal-locator-trace"
CURRENT_SCHEMA_VERSION = 2
SUPPORTED_SCHEMA_VERSIONS = frozenset({1, CURRENT_SCHEMA_VERSION})
TRACE_MODES = frozenset({"SINGLE_TARGET", "COMPLETE_SCAN"})
SIGNED_64_MIN = -(2**63)
SIGNED_64_MAX = 2**63 - 1
EVENT_CODES = frozenset(
    {
        "ar_state",
        "scanner_state",
        "signal_lost",
        "signal_recovered",
        "estimator_state_changed",
        "recoverable_failure",
    }
)
OUTCOMES = frozenset({"YES", "NO", "NOT_SURE", "SKIPPED", "INCOMPLETE"})
OUTCOME_REASONS = frozenset(
    {
        "device_disappeared",
        "device_hard_to_identify",
        "guidance_unclear",
        "estimated_area_wrong",
        "camera_ar_bluetooth_or_app_error",
        "other",
    }
)
REASON_OUTCOMES = frozenset({"NO", "NOT_SURE"})
ESTIMATOR_STATUSES_BY_KIND = {
    "bayesian_grid": frozenset({"collecting", "grid_multimodal", "grid_unimodal"}),
    "ekf": frozenset({"ekf"}),
}
BLUETOOTH_ADDRESS_PATTERN = re.compile(r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
V2_FIELDS = {
    "session": frozenset(
        {
            "schema_version",
            "record_type",
            "session_id",
            "mode",
            "created_at_utc",
            "started_at_monotonic_ns",
            "boot_id",
            "app_version",
            "device_model",
            "coordinate_frame",
            "target",
            "environment",
            "ground_truth",
            "simulation",
        }
    ),
    "target": frozenset({"session_key", "transport", "manufacturer_ids", "service_uuids"}),
    "ground_truth": frozenset({"x_m", "z_m"}),
    "simulation": frozenset(
        {
            "seed",
            "target_y_m",
            "rssi_at_1m_dbm",
            "path_loss_exponent",
            "student_t_df",
            "noise_scale_db",
            "outlier_probability",
            "outlier_scale_db",
        }
    ),
    "observation": frozenset(
        {
            "schema_version",
            "record_type",
            "session_id",
            "sequence",
            "device_key",
            "transport",
            "app_monotonic_ns",
            "platform_timestamp_ns",
            "rssi_dbm",
            "estimator_rssi_dbm",
            "tx_power_dbm",
            "accepted",
            "rejection_reason",
            "pose",
            "estimator",
            "pose_association_age_ns",
            "filtered_rssi_dbm",
            "rssi_variance_db2",
            "advertisement",
        }
    ),
    "pose": frozenset({"x_m", "y_m", "z_m", "qx", "qy", "qz", "qw", "tracking_state"}),
    "advertisement": frozenset({"manufacturer_ids", "service_uuids", "synthetic_outlier"}),
    "estimator": frozenset(
        {
            "kind",
            "status",
            "localized",
            "mean_x_m",
            "mean_z_m",
            "covariance_xx_m2",
            "covariance_xz_m2",
            "covariance_zz_m2",
            "region_probability",
        }
    ),
    "event": frozenset(
        {
            "schema_version",
            "record_type",
            "session_id",
            "app_monotonic_ns",
            "device_key",
            "transport",
            "code",
            "detail",
        }
    ),
    "outcome": frozenset(
        {
            "schema_version",
            "record_type",
            "session_id",
            "ended_at_utc",
            "duration_ms",
            "outcome",
            "reason",
            "note",
            "observed_device_count",
            "localized_device_count",
            "path_length_m",
            "device_states",
        }
    ),
    "device state": frozenset({"device_key", "transport", "status", "estimator"}),
}

type JsonObject = dict[str, Any]


class TraceValidationError(ValueError):
    """Raised when a trace does not satisfy the supported schema."""

    def __init__(self, message: str, *, source: Path | None = None, line: int | None = None):
        location = ""
        if source is not None:
            location = str(source)
        if line is not None:
            location = f"{location}:{line}" if location else f"line {line}"
        super().__init__(f"{location}: {message}" if location else message)
        self.source = source
        self.line = line


@dataclass(frozen=True, slots=True)
class GroundTruth:
    x_m: float
    z_m: float

    def to_json(self) -> JsonObject:
        return {"x_m": self.x_m, "z_m": self.z_m}


@dataclass(frozen=True, slots=True)
class Pose:
    x_m: float
    y_m: float
    z_m: float
    qx: float
    qy: float
    qz: float
    qw: float
    tracking_state: str

    def to_json(self) -> JsonObject:
        return {
            "x_m": self.x_m,
            "y_m": self.y_m,
            "z_m": self.z_m,
            "qx": self.qx,
            "qy": self.qy,
            "qz": self.qz,
            "qw": self.qw,
            "tracking_state": self.tracking_state,
        }


@dataclass(frozen=True, slots=True)
class EstimatorSnapshot:
    kind: str
    status: str
    localized: bool | None = None
    mean_x_m: float | None = None
    mean_z_m: float | None = None
    covariance_xx_m2: float | None = None
    covariance_xz_m2: float | None = None
    covariance_zz_m2: float | None = None
    region_probability: float | None = None

    @property
    def has_location(self) -> bool:
        return self.mean_x_m is not None and self.mean_z_m is not None

    @property
    def has_covariance(self) -> bool:
        values = (
            self.covariance_xx_m2,
            self.covariance_xz_m2,
            self.covariance_zz_m2,
        )
        return all(value is not None for value in values)

    def to_json(self) -> JsonObject:
        result: JsonObject = {
            "kind": self.kind,
            "status": self.status,
            "mean_x_m": self.mean_x_m,
            "mean_z_m": self.mean_z_m,
            "covariance_xx_m2": self.covariance_xx_m2,
            "covariance_xz_m2": self.covariance_xz_m2,
            "covariance_zz_m2": self.covariance_zz_m2,
            "region_probability": self.region_probability,
        }
        if self.localized is not None:
            result["localized"] = self.localized
        return result


@dataclass(frozen=True, slots=True)
class SessionRecord:
    schema_version: int
    session_id: str
    created_at_utc: str
    app_version: str
    device_model: str
    coordinate_frame: str
    started_at_monotonic_ns: int | None = None
    mode: str | None = None
    environment: str | None = None
    ground_truth: GroundTruth | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def to_json(self) -> JsonObject:
        result: JsonObject = dict(self.metadata)
        result.update(
            {
                "schema_version": self.schema_version,
                "record_type": "session",
                "session_id": self.session_id,
                "created_at_utc": self.created_at_utc,
                "app_version": self.app_version,
                "device_model": self.device_model,
                "coordinate_frame": self.coordinate_frame,
            }
        )
        if self.started_at_monotonic_ns is not None:
            result["started_at_monotonic_ns"] = self.started_at_monotonic_ns
        if self.mode is not None:
            result["mode"] = self.mode
        if self.environment is not None:
            result["environment"] = self.environment
        if self.ground_truth is not None:
            result["ground_truth"] = self.ground_truth.to_json()
        return result


@dataclass(frozen=True, slots=True)
class ObservationRecord:
    schema_version: int
    session_id: str
    sequence: int
    app_monotonic_ns: int
    platform_timestamp_ns: int
    rssi_dbm: int
    estimator_rssi_dbm: float | None
    tx_power_dbm: int | None
    accepted: bool
    pose: Pose | None
    rejection_reason: str | None = None
    estimator: EstimatorSnapshot | None = None
    pose_association_age_ns: int | None = None
    filtered_rssi_dbm: float | None = None
    rssi_variance_db2: float | None = None
    advertisement: Mapping[str, Any] = field(default_factory=dict)
    device_key: str | None = None
    transport: str | None = None

    def to_json(self) -> JsonObject:
        result: JsonObject = {
            "schema_version": self.schema_version,
            "record_type": "observation",
            "session_id": self.session_id,
            "sequence": self.sequence,
            "app_monotonic_ns": self.app_monotonic_ns,
            "platform_timestamp_ns": self.platform_timestamp_ns,
            "rssi_dbm": self.rssi_dbm,
            "estimator_rssi_dbm": self.estimator_rssi_dbm,
            "tx_power_dbm": self.tx_power_dbm,
            "accepted": self.accepted,
            "rejection_reason": self.rejection_reason,
            "pose": self.pose.to_json() if self.pose is not None else None,
            "estimator": self.estimator.to_json() if self.estimator is not None else None,
            "pose_association_age_ns": self.pose_association_age_ns,
            "filtered_rssi_dbm": self.filtered_rssi_dbm,
            "rssi_variance_db2": self.rssi_variance_db2,
        }
        if self.device_key is not None:
            result["device_key"] = self.device_key
        if self.transport is not None:
            result["transport"] = self.transport
        if self.advertisement:
            result["advertisement"] = dict(self.advertisement)
        return result


@dataclass(frozen=True, slots=True)
class EventRecord:
    schema_version: int
    session_id: str
    app_monotonic_ns: int
    code: str
    device_key: str | None = None
    transport: str | None = None
    detail: str | None = None

    def to_json(self) -> JsonObject:
        return {
            "schema_version": self.schema_version,
            "record_type": "event",
            "session_id": self.session_id,
            "app_monotonic_ns": self.app_monotonic_ns,
            "device_key": self.device_key,
            "transport": self.transport,
            "code": self.code,
            "detail": self.detail,
        }


@dataclass(frozen=True, slots=True)
class OutcomeRecord:
    schema_version: int
    session_id: str
    ended_at_utc: str
    duration_ms: int
    outcome: str
    observed_device_count: int
    localized_device_count: int
    path_length_m: float
    reason: str | None = None
    note: str | None = None
    device_states: tuple[Mapping[str, Any], ...] = ()

    def to_json(self) -> JsonObject:
        return {
            "schema_version": self.schema_version,
            "record_type": "outcome",
            "session_id": self.session_id,
            "ended_at_utc": self.ended_at_utc,
            "duration_ms": self.duration_ms,
            "outcome": self.outcome,
            "reason": self.reason,
            "note": self.note,
            "observed_device_count": self.observed_device_count,
            "localized_device_count": self.localized_device_count,
            "path_length_m": self.path_length_m,
            "device_states": [dict(item) for item in self.device_states],
        }


@dataclass(frozen=True, slots=True)
class Trace:
    session: SessionRecord
    observations: tuple[ObservationRecord, ...]
    events: tuple[EventRecord, ...] = ()
    outcome: OutcomeRecord | None = None
    source: Path | None = None

    @property
    def accepted_observations(self) -> tuple[ObservationRecord, ...]:
        return tuple(observation for observation in self.observations if observation.accepted)


def parse_session(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> SessionRecord:
    _require_record_type(value, "session", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
    if version == 2:
        _validate_signed_64_integers(value, source=source, line=line)
    session_id = _required_string(value, "session_id", source=source, line=line)
    ground_truth_raw = value.get("ground_truth")
    ground_truth = None
    if ground_truth_raw is not None:
        ground_truth_mapping = _mapping(ground_truth_raw, "ground_truth", source=source, line=line)
        if version == 2:
            _require_allowed_fields(ground_truth_mapping, "ground_truth", source=source, line=line)
        ground_truth = GroundTruth(
            x_m=_required_number(ground_truth_mapping, "x_m", source=source, line=line),
            z_m=_required_number(ground_truth_mapping, "z_m", source=source, line=line),
        )

    known_keys = {
        "schema_version",
        "record_type",
        "session_id",
        "created_at_utc",
        "app_version",
        "device_model",
        "coordinate_frame",
        "environment",
        "ground_truth",
    }
    if version == 2:
        known_keys.update({"started_at_monotonic_ns", "boot_id", "mode"})
    metadata = {key: item for key, item in value.items() if key not in known_keys}
    environment = _optional_string(value, "environment", source=source, line=line)
    created_at_utc = _required_string(value, "created_at_utc", source=source, line=line)
    _validate_utc_timestamp(created_at_utc, strict=version == 2, source=source, line=line)
    coordinate_frame = _required_string(value, "coordinate_frame", source=source, line=line)
    if coordinate_frame != "arcore_xz_m":
        raise TraceValidationError(
            "coordinate_frame must be 'arcore_xz_m'", source=source, line=line
        )
    mode = None
    started_at_monotonic_ns = None
    if version == 2:
        mode = _optional_string(value, "mode", source=source, line=line)
        started_at_monotonic_ns = _optional_integer(
            value, "started_at_monotonic_ns", source=source, line=line
        )
        boot_id = _optional_string(value, "boot_id", source=source, line=line)
        if started_at_monotonic_ns is not None and started_at_monotonic_ns < 0:
            raise TraceValidationError(
                "started_at_monotonic_ns must be non-negative", source=source, line=line
            )
        if boot_id is not None and (
            len(boot_id) != 64 or any(character not in "0123456789abcdef" for character in boot_id)
        ):
            raise TraceValidationError(
                "boot_id must be a lowercase SHA-256 digest", source=source, line=line
            )
        _require_allowed_fields(value, "session", source=source, line=line)
        if mode not in TRACE_MODES:
            raise TraceValidationError(
                "mode must be 'SINGLE_TARGET' or 'COMPLETE_SCAN'", source=source, line=line
            )
        if (mode == "SINGLE_TARGET") != (value.get("target") is not None):
            raise TraceValidationError(
                "SINGLE_TARGET requires target and COMPLETE_SCAN forbids it",
                source=source,
                line=line,
            )
        simulation = value.get("simulation")
        if simulation is not None:
            _require_allowed_fields(
                _mapping(simulation, "simulation", source=source, line=line),
                "simulation",
                source=source,
                line=line,
            )
        reject_private_fields(value, source=source, line=line)
        if boot_id is not None:
            metadata["boot_id"] = boot_id
    _validate_target(value.get("target"), version=version, source=source, line=line)
    return SessionRecord(
        schema_version=version,
        session_id=session_id,
        created_at_utc=created_at_utc,
        app_version=_required_string(value, "app_version", source=source, line=line),
        device_model=_required_string(value, "device_model", source=source, line=line),
        coordinate_frame=coordinate_frame,
        started_at_monotonic_ns=started_at_monotonic_ns,
        mode=mode,
        environment=environment,
        ground_truth=ground_truth,
        metadata=metadata,
    )


def parse_observation(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> ObservationRecord:
    _require_record_type(value, "observation", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
    if version == 2:
        _validate_signed_64_integers(value, source=source, line=line)
    accepted = _required_bool(value, "accepted", source=source, line=line)
    pose_raw = value.get("pose")
    pose = None
    if pose_raw is not None:
        pose_mapping = _mapping(pose_raw, "pose", source=source, line=line)
        pose = Pose(
            x_m=_required_number(pose_mapping, "x_m", source=source, line=line),
            y_m=_required_number(pose_mapping, "y_m", source=source, line=line),
            z_m=_required_number(pose_mapping, "z_m", source=source, line=line),
            qx=_required_number(pose_mapping, "qx", source=source, line=line),
            qy=_required_number(pose_mapping, "qy", source=source, line=line),
            qz=_required_number(pose_mapping, "qz", source=source, line=line),
            qw=_required_number(pose_mapping, "qw", source=source, line=line),
            tracking_state=_required_string(
                pose_mapping, "tracking_state", source=source, line=line
            ),
        )
        quaternion_norm_sq = pose.qx**2 + pose.qy**2 + pose.qz**2 + pose.qw**2
        if not 0.8 <= quaternion_norm_sq <= 1.2:
            raise TraceValidationError(
                "pose quaternion must be approximately normalized", source=source, line=line
            )
    if accepted and pose is None:
        raise TraceValidationError(
            "an accepted observation requires a pose", source=source, line=line
        )
    if (
        accepted
        and pose is not None
        and (
            pose.tracking_state != "TRACKING"
            if version == 2
            else pose.tracking_state.upper() != "TRACKING"
        )
    ):
        raise TraceValidationError(
            "an accepted observation requires TRACKING pose state", source=source, line=line
        )
    if (
        version == 2
        and pose is not None
        and pose.tracking_state
        not in {
            "TRACKING",
            "PAUSED",
            "STOPPED",
        }
    ):
        raise TraceValidationError("unknown pose tracking state", source=source, line=line)

    estimator_raw = value.get("estimator")
    estimator = None
    if estimator_raw is not None:
        estimator_mapping = _mapping(estimator_raw, "estimator", source=source, line=line)
        estimator = _parse_estimator(
            estimator_mapping, strict=version == 2, source=source, line=line
        )

    sequence = _required_integer(value, "sequence", source=source, line=line)
    app_timestamp = _required_integer(value, "app_monotonic_ns", source=source, line=line)
    platform_timestamp = _required_integer(value, "platform_timestamp_ns", source=source, line=line)
    if sequence < 0:
        raise TraceValidationError("sequence must be non-negative", source=source, line=line)
    if app_timestamp < 0 or platform_timestamp < 0:
        raise TraceValidationError("timestamps must be non-negative", source=source, line=line)

    rssi = _required_integer(value, "rssi_dbm", source=source, line=line)
    if not -127 <= rssi <= 20:
        raise TraceValidationError("rssi_dbm must be between -127 and 20", source=source, line=line)
    tx_power = _optional_integer(value, "tx_power_dbm", source=source, line=line)
    estimator_rssi = _optional_number(value, "estimator_rssi_dbm", source=source, line=line)
    rejection_reason = _optional_string(value, "rejection_reason", source=source, line=line)
    association_age = _optional_integer(value, "pose_association_age_ns", source=source, line=line)
    if association_age is not None and association_age < 0:
        raise TraceValidationError(
            "pose_association_age_ns must be non-negative", source=source, line=line
        )
    filtered_rssi = _optional_number(value, "filtered_rssi_dbm", source=source, line=line)
    rssi_variance = _optional_number(value, "rssi_variance_db2", source=source, line=line)
    if rssi_variance is not None and rssi_variance <= 0:
        raise TraceValidationError("rssi_variance_db2 must be positive", source=source, line=line)
    advertisement_raw = value.get("advertisement", {})
    advertisement = _mapping(advertisement_raw, "advertisement", source=source, line=line)
    _validate_transport(advertisement, "advertisement", source=source, line=line)
    device_key = _optional_string(value, "device_key", source=source, line=line)
    transport = _optional_string(value, "transport", source=source, line=line)
    if version == 2:
        _require_allowed_fields(value, "observation", source=source, line=line)
        if pose_raw is not None:
            _require_allowed_fields(pose_mapping, "pose", source=source, line=line)
        _require_allowed_fields(advertisement, "advertisement", source=source, line=line)
        synthetic_outlier = advertisement.get("synthetic_outlier")
        if synthetic_outlier is not None and not isinstance(synthetic_outlier, bool):
            raise TraceValidationError(
                "advertisement.synthetic_outlier must be a boolean",
                source=source,
                line=line,
            )
        if device_key is None or not _valid_device_key(device_key):
            raise TraceValidationError(
                "device_key must be a D01-style session-local key", source=source, line=line
            )
        _validate_transport(value, "observation", source=source, line=line)
        if transport is None:
            raise TraceValidationError(
                "transport must be present in v2 observations", source=source, line=line
            )
        if accepted and (
            estimator_rssi is None or rssi_variance is None or association_age is None
        ):
            raise TraceValidationError(
                "accepted v2 observations require estimator_rssi_dbm, "
                "rssi_variance_db2, and pose_association_age_ns",
                source=source,
                line=line,
            )
        if accepted and rejection_reason is not None:
            raise TraceValidationError(
                "accepted v2 observations must not have a rejection reason",
                source=source,
                line=line,
            )
        if not accepted and (rejection_reason is None or estimator_rssi is not None):
            raise TraceValidationError(
                "rejected v2 observations require a reason and null estimator_rssi_dbm",
                source=source,
                line=line,
            )
        reject_private_fields(value, source=source, line=line)

    return ObservationRecord(
        schema_version=version,
        session_id=_required_string(value, "session_id", source=source, line=line),
        sequence=sequence,
        app_monotonic_ns=app_timestamp,
        platform_timestamp_ns=platform_timestamp,
        rssi_dbm=rssi,
        estimator_rssi_dbm=estimator_rssi,
        tx_power_dbm=tx_power,
        accepted=accepted,
        pose=pose,
        rejection_reason=rejection_reason,
        estimator=estimator,
        pose_association_age_ns=association_age,
        filtered_rssi_dbm=filtered_rssi,
        rssi_variance_db2=rssi_variance,
        advertisement=dict(advertisement),
        device_key=device_key,
        transport=transport,
    )


def parse_event(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> EventRecord:
    _require_record_type(value, "event", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
    if version != 2:
        raise TraceValidationError(
            "event records require schema_version 2", source=source, line=line
        )
    _validate_signed_64_integers(value, source=source, line=line)
    _require_allowed_fields(value, "event", source=source, line=line)
    timestamp = _required_integer(value, "app_monotonic_ns", source=source, line=line)
    if timestamp < 0:
        raise TraceValidationError(
            "app_monotonic_ns must be non-negative", source=source, line=line
        )
    code = _required_string(value, "code", source=source, line=line)
    if code not in EVENT_CODES:
        raise TraceValidationError(f"unknown event code {code!r}", source=source, line=line)
    device_key = _optional_string(value, "device_key", source=source, line=line)
    if device_key is not None and not _valid_device_key(device_key):
        raise TraceValidationError(
            "device_key must be a D01-style session-local key", source=source, line=line
        )
    transport = _optional_string(value, "transport", source=source, line=line)
    if (device_key is None) != (transport is None):
        raise TraceValidationError(
            "device-scoped events require both device_key and transport",
            source=source,
            line=line,
        )
    if transport is not None:
        _validate_transport(value, "event", required=True, source=source, line=line)
    detail = _optional_boundary_string(value, "detail", source=source, line=line)
    if detail is not None and (len(detail) > 200 or _has_control_characters(detail)):
        raise TraceValidationError(
            "event detail must contain no control characters and be at most 200 characters",
            source=source,
            line=line,
        )
    reject_private_fields(value, source=source, line=line)
    return EventRecord(
        schema_version=version,
        session_id=_required_string(value, "session_id", source=source, line=line),
        app_monotonic_ns=timestamp,
        code=code,
        device_key=device_key,
        transport=transport,
        detail=detail,
    )


def parse_outcome(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> OutcomeRecord:
    _require_record_type(value, "outcome", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
    if version != 2:
        raise TraceValidationError(
            "outcome records require schema_version 2", source=source, line=line
        )
    _validate_signed_64_integers(value, source=source, line=line)
    _require_allowed_fields(value, "outcome", source=source, line=line)
    ended_at_utc = _required_string(value, "ended_at_utc", source=source, line=line)
    _validate_utc_timestamp(ended_at_utc, source=source, line=line)
    duration_ms = _required_integer(value, "duration_ms", source=source, line=line)
    outcome = _required_string(value, "outcome", source=source, line=line)
    observed_count = _required_integer(value, "observed_device_count", source=source, line=line)
    localized_count = _required_integer(value, "localized_device_count", source=source, line=line)
    path_length = _required_number(value, "path_length_m", source=source, line=line)
    if (
        duration_ms < 0
        or observed_count < 0
        or localized_count < 0
        or localized_count > observed_count
        or path_length < 0
    ):
        raise TraceValidationError(
            "outcome duration, counts, and path length must be consistent and non-negative",
            source=source,
            line=line,
        )
    if outcome not in OUTCOMES:
        raise TraceValidationError(f"unknown outcome {outcome!r}", source=source, line=line)
    reason = _optional_string(value, "reason", source=source, line=line)
    if reason is not None and reason not in OUTCOME_REASONS:
        raise TraceValidationError(f"unknown outcome reason {reason!r}", source=source, line=line)
    if reason is not None and outcome not in REASON_OUTCOMES:
        raise TraceValidationError(
            "outcome reasons are only valid for NO or NOT_SURE", source=source, line=line
        )
    note = _optional_boundary_string(value, "note", source=source, line=line)
    if note is not None and (len(note) > 500 or _has_control_characters(note)):
        raise TraceValidationError(
            "outcome note must contain no control characters and be at most 500 characters",
            source=source,
            line=line,
        )
    states_raw = value.get("device_states")
    if not isinstance(states_raw, list):
        raise TraceValidationError("device_states must be an array", source=source, line=line)
    states: list[Mapping[str, Any]] = []
    for state_raw in states_raw:
        state = _mapping(state_raw, "device state", source=source, line=line)
        _require_allowed_fields(state, "device state", source=source, line=line)
        device_key = _required_string(state, "device_key", source=source, line=line)
        if not _valid_device_key(device_key):
            raise TraceValidationError(
                "device state key must be D01-style", source=source, line=line
            )
        _validate_transport(state, "device state", required=True, source=source, line=line)
        status = _required_string(state, "status", source=source, line=line)
        estimator_raw = state.get("estimator")
        estimator = None
        if estimator_raw is not None:
            estimator = _parse_estimator(
                _mapping(estimator_raw, "estimator", source=source, line=line),
                strict=True,
                source=source,
                line=line,
            )
        if (status == "insufficient_evidence") != (estimator is None):
            raise TraceValidationError(
                "insufficient_evidence requires a null estimator and other states require one",
                source=source,
                line=line,
            )
        if estimator is not None and estimator.status != status:
            raise TraceValidationError(
                "device state status must match estimator status", source=source, line=line
            )
        states.append(dict(state))
    reject_private_fields(value, source=source, line=line)
    return OutcomeRecord(
        schema_version=version,
        session_id=_required_string(value, "session_id", source=source, line=line),
        ended_at_utc=ended_at_utc,
        duration_ms=duration_ms,
        outcome=outcome,
        observed_device_count=observed_count,
        localized_device_count=localized_count,
        path_length_m=path_length,
        reason=reason,
        note=note,
        device_states=tuple(states),
    )


def validate_trace(trace: Trace) -> None:
    """Validate both record fields and cross-record trace invariants."""

    session = parse_session(trace.session.to_json(), source=trace.source, line=1)
    observations = tuple(
        parse_observation(item.to_json(), source=trace.source, line=index)
        for index, item in enumerate(trace.observations, start=2)
    )
    events = tuple(parse_event(item.to_json(), source=trace.source) for item in trace.events)
    outcome = (
        parse_outcome(trace.outcome.to_json(), source=trace.source)
        if trace.outcome is not None
        else None
    )

    if session.schema_version == 2 and outcome is None:
        raise TraceValidationError("completed v2 trace requires one outcome", source=trace.source)
    device_transports: dict[str, str] = {}
    target_key: str | None = None
    if session.schema_version == 2:
        target = session.metadata.get("target")
        if isinstance(target, Mapping):
            target_key = str(target["session_key"])
            device_transports[target_key] = str(target["transport"])
    previous_sequence = -1
    previous_app_timestamp = -1
    observed_keys: set[str] = set()
    for index, observation in enumerate(observations, start=2):
        if observation.schema_version != session.schema_version:
            raise TraceValidationError(
                "record schema_version differs from session", source=trace.source, line=index
            )
        if observation.session_id != session.session_id:
            raise TraceValidationError(
                "record session_id differs from session", source=trace.source, line=index
            )
        if observation.sequence <= previous_sequence:
            raise TraceValidationError(
                "observation sequences must be strictly increasing",
                source=trace.source,
                line=index,
            )
        if observation.app_monotonic_ns < previous_app_timestamp:
            raise TraceValidationError(
                "app_monotonic_ns must be non-decreasing", source=trace.source, line=index
            )
        if observation.device_key is not None and observation.transport is not None:
            observed_keys.add(observation.device_key)
            previous_transport = device_transports.setdefault(
                observation.device_key, observation.transport
            )
            if previous_transport != observation.transport:
                raise TraceValidationError(
                    f"device_key {observation.device_key} changes transport",
                    source=trace.source,
                    line=index,
                )
        previous_sequence = observation.sequence
        previous_app_timestamp = observation.app_monotonic_ns
    for record in (*events, outcome):
        if record is None:
            continue
        if record.schema_version != session.schema_version:
            raise TraceValidationError(
                "record schema_version differs from session", source=trace.source
            )
        if record.session_id != session.session_id:
            raise TraceValidationError(
                "record session_id differs from session", source=trace.source
            )
    for event in events:
        if event.device_key is not None and event.transport is not None:
            previous_transport = device_transports.setdefault(event.device_key, event.transport)
            if previous_transport != event.transport:
                raise TraceValidationError(
                    f"device_key {event.device_key} changes transport", source=trace.source
                )
    if session.schema_version != 2 or outcome is None:
        return

    event_keys = {event.device_key for event in events if event.device_key is not None}
    selected_keys = {target_key} if target_key is not None else set()
    expected_keys = selected_keys if session.mode == "SINGLE_TARGET" else observed_keys | event_keys
    if session.mode == "SINGLE_TARGET" and not (observed_keys | event_keys) <= selected_keys:
        raise TraceValidationError(
            "single-target records must use the selected target key", source=trace.source
        )

    outcome_keys: set[str] = set()
    localized_count = 0
    for state in outcome.device_states:
        device_key = str(state["device_key"])
        transport = str(state["transport"])
        if device_key in outcome_keys:
            raise TraceValidationError(
                f"outcome repeats device_key {device_key}", source=trace.source
            )
        outcome_keys.add(device_key)
        previous_transport = device_transports.setdefault(device_key, transport)
        if previous_transport != transport:
            raise TraceValidationError(
                f"device_key {device_key} changes transport", source=trace.source
            )
        estimator = state.get("estimator")
        if isinstance(estimator, Mapping) and estimator.get("localized") is True:
            localized_count += 1

    if outcome_keys != expected_keys:
        raise TraceValidationError(
            "outcome device states must exactly cover selected, observed, and event devices",
            source=trace.source,
        )
    if outcome.observed_device_count != len(observed_keys):
        raise TraceValidationError(
            "observed_device_count must equal the number of observed device keys",
            source=trace.source,
        )
    if outcome.localized_device_count != localized_count:
        raise TraceValidationError(
            "localized_device_count must equal localized outcome device states",
            source=trace.source,
        )


def _parse_estimator(
    value: Mapping[str, Any],
    *,
    strict: bool = False,
    source: Path | None,
    line: int | None,
) -> EstimatorSnapshot:
    if strict:
        _require_allowed_fields(value, "estimator", source=source, line=line)
    snapshot = EstimatorSnapshot(
        kind=_required_string(value, "kind", source=source, line=line),
        status=_required_string(value, "status", source=source, line=line),
        localized=(
            _required_bool(value, "localized", source=source, line=line)
            if strict
            else value.get("localized")
        ),
        mean_x_m=_optional_number(value, "mean_x_m", source=source, line=line),
        mean_z_m=_optional_number(value, "mean_z_m", source=source, line=line),
        covariance_xx_m2=_optional_number(value, "covariance_xx_m2", source=source, line=line),
        covariance_xz_m2=_optional_number(value, "covariance_xz_m2", source=source, line=line),
        covariance_zz_m2=_optional_number(value, "covariance_zz_m2", source=source, line=line),
        region_probability=_optional_number(value, "region_probability", source=source, line=line),
    )
    if strict and snapshot.status not in ESTIMATOR_STATUSES_BY_KIND.get(snapshot.kind, frozenset()):
        raise TraceValidationError(
            "estimator kind/status combination is invalid", source=source, line=line
        )
    if (snapshot.mean_x_m is None) != (snapshot.mean_z_m is None):
        raise TraceValidationError(
            "estimator mean requires both mean_x_m and mean_z_m", source=source, line=line
        )
    covariance_values = (
        snapshot.covariance_xx_m2,
        snapshot.covariance_xz_m2,
        snapshot.covariance_zz_m2,
    )
    if any(item is not None for item in covariance_values) and not snapshot.has_covariance:
        raise TraceValidationError(
            "estimator covariance requires xx, xz, and zz", source=source, line=line
        )
    if snapshot.has_covariance:
        assert snapshot.covariance_xx_m2 is not None
        assert snapshot.covariance_xz_m2 is not None
        assert snapshot.covariance_zz_m2 is not None
        determinant = (
            snapshot.covariance_xx_m2 * snapshot.covariance_zz_m2 - snapshot.covariance_xz_m2**2
        )
        if snapshot.covariance_xx_m2 < 0 or snapshot.covariance_zz_m2 < 0 or determinant < -1e-10:
            raise TraceValidationError(
                "estimator covariance must be positive semidefinite", source=source, line=line
            )
    if snapshot.region_probability is not None and not 0 < snapshot.region_probability < 1:
        raise TraceValidationError(
            "region_probability must be between zero and one", source=source, line=line
        )
    return snapshot


def _validate_utc_timestamp(
    value: str,
    *,
    strict: bool = True,
    source: Path | None,
    line: int | None,
) -> None:
    pattern = r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z"
    if strict and re.fullmatch(pattern, value) is None:
        raise TraceValidationError(
            "UTC timestamp must use yyyy-MM-ddTHH:mm:ss.SSSZ",
            source=source,
            line=line,
        )
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise TraceValidationError(
            "created_at_utc must be an RFC 3339 timestamp", source=source, line=line
        ) from error
    if timestamp.tzinfo is None or timestamp.utcoffset() != UTC.utcoffset(timestamp):
        raise TraceValidationError(
            "created_at_utc must include a UTC offset", source=source, line=line
        )


def _validate_target(value: Any, *, version: int, source: Path | None, line: int | None) -> None:
    if value is None:
        return
    target = _mapping(value, "target", source=source, line=line)
    if version == 2:
        _require_allowed_fields(target, "target", source=source, line=line)
    session_key = _required_string(target, "session_key", source=source, line=line)
    if version == 2 and not _valid_device_key(session_key):
        raise TraceValidationError(
            "target.session_key must be a D01-style session-local key",
            source=source,
            line=line,
        )
    if version == 1:
        _required_string(target, "display_name", source=source, line=line)
    _validate_transport(target, "target", required=version == 2, source=source, line=line)
    manufacturer_ids = target.get("manufacturer_ids", [])
    if not isinstance(manufacturer_ids, list) or any(
        isinstance(item, bool) or not isinstance(item, int) for item in manufacturer_ids
    ):
        raise TraceValidationError(
            "target.manufacturer_ids must be an integer array", source=source, line=line
        )
    service_uuids = target.get("service_uuids", [])
    if not isinstance(service_uuids, list) or any(
        not isinstance(item, str) or not item for item in service_uuids
    ):
        raise TraceValidationError(
            "target.service_uuids must be a string array", source=source, line=line
        )


def _valid_device_key(value: str) -> bool:
    return len(value) >= 3 and value[0] == "D" and all("0" <= item <= "9" for item in value[1:])


def _validate_signed_64_integers(
    value: Any, *, source: Path | None, line: int | None
) -> None:
    if isinstance(value, Mapping):
        for item in value.values():
            _validate_signed_64_integers(item, source=source, line=line)
    elif isinstance(value, list):
        for item in value:
            _validate_signed_64_integers(item, source=source, line=line)
    elif (
        isinstance(value, int)
        and not isinstance(value, bool)
        and not SIGNED_64_MIN <= value <= SIGNED_64_MAX
    ):
        raise TraceValidationError(
            "integer must fit a signed 64-bit value", source=source, line=line
        )


def reject_private_fields(
    value: Mapping[str, Any], *, source: Path | None, line: int | None
) -> None:
    forbidden = {"address", "ephemeral_address", "advertised_name", "display_name"}
    stack: list[Any] = [value]
    while stack:
        item = stack.pop()
        if isinstance(item, Mapping):
            for key, child in item.items():
                if key.lower() in forbidden:
                    raise TraceValidationError(
                        f"v2 traces must not persist {key}", source=source, line=line
                    )
                stack.append(child)
        elif isinstance(item, list | tuple):
            stack.extend(item)
        elif isinstance(item, str) and BLUETOOTH_ADDRESS_PATTERN.search(item):
            raise TraceValidationError(
                "v2 traces must not persist Bluetooth addresses", source=source, line=line
            )


def _require_allowed_fields(
    value: Mapping[str, Any],
    object_name: str,
    *,
    source: Path | None,
    line: int | None,
) -> None:
    unknown = value.keys() - V2_FIELDS[object_name]
    if unknown:
        field = sorted(unknown)[0]
        if field.lower() in {"address", "ephemeral_address", "advertised_name", "display_name"}:
            raise TraceValidationError(
                f"v2 traces must not persist {field}", source=source, line=line
            )
        raise TraceValidationError(
            f"{object_name} contains unsupported field {field!r}",
            source=source,
            line=line,
        )


def _validate_transport(
    value: Mapping[str, Any],
    object_name: str,
    *,
    required: bool = False,
    source: Path | None,
    line: int | None,
) -> None:
    transport = value.get("transport")
    if (required and transport is None) or (
        transport is not None and transport not in {"low_energy", "classic"}
    ):
        raise TraceValidationError(
            f"{object_name}.transport must be 'low_energy' or 'classic'",
            source=source,
            line=line,
        )


def _has_control_characters(value: str) -> bool:
    return any(unicodedata.category(character) == "Cc" for character in value)


def _schema_version(value: Mapping[str, Any], *, source: Path | None, line: int | None) -> int:
    version = _required_integer(value, "schema_version", source=source, line=line)
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        supported = ", ".join(str(item) for item in sorted(SUPPORTED_SCHEMA_VERSIONS))
        raise TraceValidationError(
            f"unsupported schema_version {version}; supported: {supported}",
            source=source,
            line=line,
        )
    return version


def _require_record_type(
    value: Mapping[str, Any], expected: str, *, source: Path | None, line: int | None
) -> None:
    actual = _required_string(value, "record_type", source=source, line=line)
    if actual != expected:
        raise TraceValidationError(
            f"expected record_type {expected!r}, got {actual!r}", source=source, line=line
        )


def _mapping(value: Any, name: str, *, source: Path | None, line: int | None) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or not all(isinstance(key, str) for key in value):
        raise TraceValidationError(f"{name} must be an object", source=source, line=line)
    return value


def _required_string(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item.strip():
        raise TraceValidationError(f"{key} must be a non-empty string", source=source, line=line)
    return item


def _optional_string(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> str | None:
    item = value.get(key)
    if item is None:
        return None
    if not isinstance(item, str) or not item.strip():
        raise TraceValidationError(
            f"{key} must be null or a non-empty string", source=source, line=line
        )
    return item


def _optional_boundary_string(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> str | None:
    item = value.get(key)
    if item is None:
        return None
    if not isinstance(item, str):
        raise TraceValidationError(f"{key} must be null or a string", source=source, line=line)
    return item if item.strip() else None


def _required_bool(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> bool:
    item = value.get(key)
    if not isinstance(item, bool):
        raise TraceValidationError(f"{key} must be a boolean", source=source, line=line)
    return item


def _required_integer(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> int:
    item = value.get(key)
    if isinstance(item, bool) or not isinstance(item, int):
        raise TraceValidationError(f"{key} must be an integer", source=source, line=line)
    return item


def _required_number(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> float:
    item = value.get(key)
    if isinstance(item, bool) or not isinstance(item, int | float) or not isfinite(item):
        raise TraceValidationError(f"{key} must be a finite number", source=source, line=line)
    return float(item)


def _optional_integer(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> int | None:
    if value.get(key) is None:
        return None
    return _required_integer(value, key, source=source, line=line)


def _optional_number(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> float | None:
    item = value.get(key)
    if item is None:
        return None
    return _required_number(value, key, source=source, line=line)


def ensure_json_object(
    value: Any, *, source: Path | None = None, line: int | None = None
) -> Mapping[str, Any]:
    return _mapping(value, "record", source=source, line=line)


def ensure_nonempty_records(
    records: Sequence[Mapping[str, Any]], *, source: Path | None = None
) -> None:
    if not records:
        raise TraceValidationError("trace is empty", source=source)
