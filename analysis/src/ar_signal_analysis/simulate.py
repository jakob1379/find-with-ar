"""Seeded log-distance RSSI trace simulator."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from math import log10, pi

import numpy as np
import numpy.typing as npt

from ar_signal_analysis.schema import (
    CURRENT_SCHEMA_VERSION,
    GroundTruth,
    ObservationRecord,
    Pose,
    SessionRecord,
    Trace,
)


@dataclass(frozen=True, slots=True)
class SimulationConfig:
    """Parameters for a deterministic synthetic walking session."""

    session_id: str = "synthetic-0001"
    sample_count: int = 160
    interval_s: float = 0.25
    seed: int = 0
    target_x_m: float = 1.4
    target_y_m: float = 0.4
    target_z_m: float = 0.6
    phone_height_m: float = 1.35
    room_width_m: float = 6.0
    room_depth_m: float = 5.0
    rssi_at_1m_dbm: float = -52.0
    path_loss_exponent: float = 2.2
    min_distance_m: float = 0.15
    student_t_df: float = 4.0
    noise_scale_db: float = 2.5
    outlier_probability: float = 0.04
    outlier_scale_db: float = 12.0
    created_at_utc: str = "2026-01-01T00:00:00Z"

    def validate(self) -> None:
        if self.sample_count < 1:
            raise ValueError("sample_count must be positive")
        if self.interval_s <= 0:
            raise ValueError("interval_s must be positive")
        if self.room_width_m <= 0 or self.room_depth_m <= 0:
            raise ValueError("room dimensions must be positive")
        if self.min_distance_m <= 0:
            raise ValueError("min_distance_m must be positive")
        if self.student_t_df <= 2:
            raise ValueError("student_t_df must exceed 2 for finite variance")
        if self.noise_scale_db < 0 or self.outlier_scale_db < 0:
            raise ValueError("noise scales must be non-negative")
        if not 0 <= self.outlier_probability <= 1:
            raise ValueError("outlier_probability must be between zero and one")
        if self.path_loss_exponent <= 0:
            raise ValueError("path_loss_exponent must be positive")


def simulate_trace(
    config: SimulationConfig | None = None,
    *,
    path_xz_m: npt.ArrayLike | None = None,
) -> Trace:
    """Simulate an informative path with Student-t noise and sparse outliers."""

    config = config or SimulationConfig()
    config.validate()
    path = (
        default_walk_path(config)
        if path_xz_m is None
        else np.asarray(path_xz_m, dtype=np.float64)
    )
    if path.shape != (config.sample_count, 2):
        raise ValueError(f"path_xz_m must have shape ({config.sample_count}, 2)")
    if not np.all(np.isfinite(path)):
        raise ValueError("path_xz_m must contain finite positions")

    rng = np.random.default_rng(config.seed)
    target = np.asarray(
        [config.target_x_m, config.target_y_m, config.target_z_m], dtype=np.float64
    )
    phone_positions = np.column_stack(
        (
            path[:, 0],
            np.full(config.sample_count, config.phone_height_m),
            path[:, 1],
        )
    )
    distances = np.maximum(np.linalg.norm(phone_positions - target, axis=1), config.min_distance_m)
    expected_rssi = config.rssi_at_1m_dbm - 10.0 * config.path_loss_exponent * np.log10(
        distances
    )
    noise = rng.standard_t(config.student_t_df, size=config.sample_count) * config.noise_scale_db
    outlier_mask = rng.random(config.sample_count) < config.outlier_probability
    outlier_noise = rng.normal(0.0, config.outlier_scale_db, size=config.sample_count)
    rssi = np.rint(
        np.clip(expected_rssi + noise + outlier_mask * outlier_noise, -127.0, 20.0)
    ).astype(np.int64)

    interval_ns = round(config.interval_s * 1_000_000_000)
    observations = tuple(
        ObservationRecord(
            schema_version=CURRENT_SCHEMA_VERSION,
            session_id=config.session_id,
            sequence=index,
            app_monotonic_ns=index * interval_ns,
            platform_timestamp_ns=index * interval_ns,
            rssi_dbm=int(value),
            tx_power_dbm=round(config.rssi_at_1m_dbm),
            accepted=True,
            pose=Pose(
                x_m=float(path[index, 0]),
                y_m=config.phone_height_m,
                z_m=float(path[index, 1]),
                qx=0.0,
                qy=0.0,
                qz=0.0,
                qw=1.0,
                tracking_state="TRACKING",
            ),
            advertisement={"synthetic_outlier": bool(outlier_mask[index])},
        )
        for index, value in enumerate(rssi)
    )
    session = SessionRecord(
        schema_version=CURRENT_SCHEMA_VERSION,
        session_id=config.session_id,
        created_at_utc=config.created_at_utc,
        app_version="analysis-simulator/0.1.0",
        device_model="synthetic",
        coordinate_frame="arcore_xz_m",
        environment="synthetic",
        ground_truth=GroundTruth(x_m=config.target_x_m, z_m=config.target_z_m),
        metadata={
            "target": {
                "session_key": "synthetic-target",
                "display_name": "Synthetic beacon",
                "manufacturer_ids": [],
                "service_uuids": [],
            },
            "simulation": {
                "seed": config.seed,
                "target_y_m": config.target_y_m,
                "rssi_at_1m_dbm": config.rssi_at_1m_dbm,
                "path_loss_exponent": config.path_loss_exponent,
                "student_t_df": config.student_t_df,
                "noise_scale_db": config.noise_scale_db,
                "outlier_probability": config.outlier_probability,
                "outlier_scale_db": config.outlier_scale_db,
            }
        },
    )
    return Trace(session=session, observations=observations)


def default_walk_path(config: SimulationConfig) -> npt.NDArray[np.float64]:
    """Generate a smooth two-lobed path with useful two-dimensional spread."""

    phase = np.linspace(0.0, 2.0 * pi, config.sample_count, endpoint=False)
    x = config.room_width_m / 2.0 + config.room_width_m * 0.42 * np.sin(phase)
    z = config.room_depth_m / 2.0 + config.room_depth_m * 0.38 * np.sin(2.0 * phase)
    return np.column_stack((x, z)).astype(np.float64)


def expected_log_distance_rssi_dbm(
    distance_m: float,
    *,
    rssi_at_1m_dbm: float,
    path_loss_exponent: float,
    min_distance_m: float = 0.15,
) -> float:
    """Evaluate the noiseless observation model used by the simulator."""

    if distance_m < 0:
        raise ValueError("distance_m must be non-negative")
    if min_distance_m <= 0:
        raise ValueError("min_distance_m must be positive")
    if path_loss_exponent <= 0:
        raise ValueError("path_loss_exponent must be positive")
    return rssi_at_1m_dbm - 10.0 * path_loss_exponent * log10(
        max(distance_m, min_distance_m)
    )


def utc_now_iso() -> str:
    """Return an ISO timestamp for interactive CLI-generated traces."""

    return datetime.now(UTC).isoformat().replace("+00:00", "Z")
