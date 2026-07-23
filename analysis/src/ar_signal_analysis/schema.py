"""Versioned trace model and strict validation helpers."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from math import isfinite
from pathlib import Path
from typing import Any

SCHEMA_NAME = "ar-signal-locator-trace"
CURRENT_SCHEMA_VERSION = 1
SUPPORTED_SCHEMA_VERSIONS = frozenset({CURRENT_SCHEMA_VERSION})

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
        return {
            "kind": self.kind,
            "status": self.status,
            "mean_x_m": self.mean_x_m,
            "mean_z_m": self.mean_z_m,
            "covariance_xx_m2": self.covariance_xx_m2,
            "covariance_xz_m2": self.covariance_xz_m2,
            "covariance_zz_m2": self.covariance_zz_m2,
            "region_probability": self.region_probability,
        }


@dataclass(frozen=True, slots=True)
class SessionRecord:
    schema_version: int
    session_id: str
    created_at_utc: str
    app_version: str
    device_model: str
    coordinate_frame: str
    environment: str | None = None
    ground_truth: GroundTruth | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def to_json(self) -> JsonObject:
        result: JsonObject = dict(self.metadata)
        result.update({
            "schema_version": self.schema_version,
            "record_type": "session",
            "session_id": self.session_id,
            "created_at_utc": self.created_at_utc,
            "app_version": self.app_version,
            "device_model": self.device_model,
            "coordinate_frame": self.coordinate_frame,
        })
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
        if self.advertisement:
            result["advertisement"] = dict(self.advertisement)
        return result


@dataclass(frozen=True, slots=True)
class Trace:
    session: SessionRecord
    observations: tuple[ObservationRecord, ...]
    source: Path | None = None

    @property
    def accepted_observations(self) -> tuple[ObservationRecord, ...]:
        return tuple(observation for observation in self.observations if observation.accepted)


def parse_session(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> SessionRecord:
    _require_record_type(value, "session", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
    session_id = _required_string(value, "session_id", source=source, line=line)
    ground_truth_raw = value.get("ground_truth")
    ground_truth = None
    if ground_truth_raw is not None:
        ground_truth_mapping = _mapping(
            ground_truth_raw, "ground_truth", source=source, line=line
        )
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
    metadata = {key: item for key, item in value.items() if key not in known_keys}
    environment = _optional_string(value, "environment", source=source, line=line)
    created_at_utc = _required_string(value, "created_at_utc", source=source, line=line)
    _validate_utc_timestamp(created_at_utc, source=source, line=line)
    coordinate_frame = _required_string(value, "coordinate_frame", source=source, line=line)
    if coordinate_frame != "arcore_xz_m":
        raise TraceValidationError(
            "coordinate_frame must be 'arcore_xz_m'", source=source, line=line
        )
    _validate_target(value.get("target"), source=source, line=line)
    return SessionRecord(
        schema_version=version,
        session_id=session_id,
        created_at_utc=created_at_utc,
        app_version=_required_string(value, "app_version", source=source, line=line),
        device_model=_required_string(value, "device_model", source=source, line=line),
        coordinate_frame=coordinate_frame,
        environment=environment,
        ground_truth=ground_truth,
        metadata=metadata,
    )


def parse_observation(
    value: Mapping[str, Any], *, source: Path | None = None, line: int | None = None
) -> ObservationRecord:
    _require_record_type(value, "observation", source=source, line=line)
    version = _schema_version(value, source=source, line=line)
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
    if accepted and pose is not None and pose.tracking_state.upper() != "TRACKING":
        raise TraceValidationError(
            "an accepted observation requires TRACKING pose state", source=source, line=line
        )

    estimator_raw = value.get("estimator")
    estimator = None
    if estimator_raw is not None:
        estimator_mapping = _mapping(estimator_raw, "estimator", source=source, line=line)
        estimator = _parse_estimator(estimator_mapping, source=source, line=line)

    sequence = _required_integer(value, "sequence", source=source, line=line)
    app_timestamp = _required_integer(value, "app_monotonic_ns", source=source, line=line)
    platform_timestamp = _required_integer(
        value, "platform_timestamp_ns", source=source, line=line
    )
    if sequence < 0:
        raise TraceValidationError("sequence must be non-negative", source=source, line=line)
    if app_timestamp < 0 or platform_timestamp < 0:
        raise TraceValidationError("timestamps must be non-negative", source=source, line=line)

    rssi = _required_integer(value, "rssi_dbm", source=source, line=line)
    if not -127 <= rssi <= 20:
        raise TraceValidationError(
            "rssi_dbm must be between -127 and 20", source=source, line=line
        )
    tx_power = _optional_integer(value, "tx_power_dbm", source=source, line=line)
    estimator_rssi = _optional_number(
        value, "estimator_rssi_dbm", source=source, line=line
    )
    rejection_reason = _optional_string(value, "rejection_reason", source=source, line=line)
    association_age = _optional_integer(
        value, "pose_association_age_ns", source=source, line=line
    )
    if association_age is not None and association_age < 0:
        raise TraceValidationError(
            "pose_association_age_ns must be non-negative", source=source, line=line
        )
    filtered_rssi = _optional_number(value, "filtered_rssi_dbm", source=source, line=line)
    rssi_variance = _optional_number(value, "rssi_variance_db2", source=source, line=line)
    if rssi_variance is not None and rssi_variance <= 0:
        raise TraceValidationError(
            "rssi_variance_db2 must be positive", source=source, line=line
        )
    advertisement_raw = value.get("advertisement", {})
    advertisement = _mapping(advertisement_raw, "advertisement", source=source, line=line)
    _validate_transport(advertisement, "advertisement", source=source, line=line)

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
    )


def validate_trace(trace: Trace) -> None:
    previous_sequence = -1
    previous_app_timestamp = -1
    for index, observation in enumerate(trace.observations, start=2):
        if observation.schema_version != trace.session.schema_version:
            raise TraceValidationError(
                "record schema_version differs from session", source=trace.source, line=index
            )
        if observation.session_id != trace.session.session_id:
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
        previous_sequence = observation.sequence
        previous_app_timestamp = observation.app_monotonic_ns


def _parse_estimator(
    value: Mapping[str, Any], *, source: Path | None, line: int | None
) -> EstimatorSnapshot:
    snapshot = EstimatorSnapshot(
        kind=_required_string(value, "kind", source=source, line=line),
        status=_required_string(value, "status", source=source, line=line),
        mean_x_m=_optional_number(value, "mean_x_m", source=source, line=line),
        mean_z_m=_optional_number(value, "mean_z_m", source=source, line=line),
        covariance_xx_m2=_optional_number(
            value, "covariance_xx_m2", source=source, line=line
        ),
        covariance_xz_m2=_optional_number(
            value, "covariance_xz_m2", source=source, line=line
        ),
        covariance_zz_m2=_optional_number(
            value, "covariance_zz_m2", source=source, line=line
        ),
        region_probability=_optional_number(
            value, "region_probability", source=source, line=line
        ),
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
            snapshot.covariance_xx_m2 * snapshot.covariance_zz_m2
            - snapshot.covariance_xz_m2**2
        )
        if (
            snapshot.covariance_xx_m2 < 0
            or snapshot.covariance_zz_m2 < 0
            or determinant < -1e-10
        ):
            raise TraceValidationError(
                "estimator covariance must be positive semidefinite", source=source, line=line
            )
    if snapshot.region_probability is not None and not 0 < snapshot.region_probability < 1:
        raise TraceValidationError(
            "region_probability must be between zero and one", source=source, line=line
        )
    return snapshot


def _validate_utc_timestamp(value: str, *, source: Path | None, line: int | None) -> None:
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


def _validate_target(value: Any, *, source: Path | None, line: int | None) -> None:
    if value is None:
        return
    target = _mapping(value, "target", source=source, line=line)
    _required_string(target, "session_key", source=source, line=line)
    _required_string(target, "display_name", source=source, line=line)
    _validate_transport(target, "target", source=source, line=line)
    manufacturer_ids = target.get("manufacturer_ids", [])
    if (
        not isinstance(manufacturer_ids, list)
        or any(isinstance(item, bool) or not isinstance(item, int) for item in manufacturer_ids)
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


def _validate_transport(
    value: Mapping[str, Any],
    object_name: str,
    *,
    source: Path | None,
    line: int | None,
) -> None:
    transport = value.get("transport")
    if transport is not None and transport not in {"low_energy", "classic"}:
        raise TraceValidationError(
            f"{object_name}.transport must be 'low_energy' or 'classic'",
            source=source,
            line=line,
        )


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


def _mapping(
    value: Any, name: str, *, source: Path | None, line: int | None
) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or not all(isinstance(key, str) for key in value):
        raise TraceValidationError(f"{name} must be an object", source=source, line=line)
    return value


def _required_string(
    value: Mapping[str, Any], key: str, *, source: Path | None, line: int | None
) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item.strip():
        raise TraceValidationError(
            f"{key} must be a non-empty string", source=source, line=line
        )
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
