"""Baseline, path-geometry, localization-error, and coverage metrics."""

from __future__ import annotations

from collections.abc import Sequence
from math import hypot, isfinite, log, pi, sqrt

import numpy as np
import numpy.typing as npt

from ar_signal_analysis.schema import EstimatorSnapshot, GroundTruth, Trace

type MetricValue = float | int | bool | str | None
type Metrics = dict[str, MetricValue]


def strongest_rssi_baseline(trace: Trace) -> Metrics | None:
    """Use the AR pose at the single strongest accepted RSSI observation."""

    candidates = [
        observation
        for observation in trace.observations
        if observation.accepted and observation.pose is not None
    ]
    if not candidates:
        return None
    strongest = max(candidates, key=lambda item: (item.rssi_dbm, -item.sequence))
    assert strongest.pose is not None
    result: Metrics = {
        "sequence": strongest.sequence,
        "rssi_dbm": strongest.rssi_dbm,
        "x_m": strongest.pose.x_m,
        "z_m": strongest.pose.z_m,
    }
    if trace.session.ground_truth is not None:
        result["error_m"] = horizontal_error_m(
            strongest.pose.x_m,
            strongest.pose.z_m,
            trace.session.ground_truth,
        )
    else:
        result["error_m"] = None
    return result


def path_metrics(trace: Trace, *, spatial_bucket_m: float = 0.25) -> Metrics:
    """Summarize movement and spatial diversity of accepted camera poses."""

    if spatial_bucket_m <= 0:
        raise ValueError("spatial_bucket_m must be positive")
    accepted = [
        observation
        for observation in trace.observations
        if observation.accepted and observation.pose is not None
    ]
    all_count = len(trace.observations)
    associated_count = sum(observation.pose is not None for observation in trace.observations)
    rejected_count = all_count - sum(observation.accepted for observation in trace.observations)
    result: Metrics = {
        "observation_count": all_count,
        "accepted_count": len(accepted),
        "rejected_count": rejected_count,
        "pose_association_rate": associated_count / all_count if all_count else None,
        "accepted_rate": len(accepted) / all_count if all_count else None,
        "duration_s": None,
        "path_length_m": 0.0,
        "net_displacement_m": 0.0,
        "span_x_m": 0.0,
        "span_z_m": 0.0,
        "spatial_rms_m": 0.0,
        "minor_spread_m": 0.0,
        "major_spread_m": 0.0,
        "geometry_ratio": 0.0,
        "unique_spatial_buckets": 0,
        "advertisement_interval_median_ms": None,
        "rssi_median_dbm": None,
        "rssi_mad_db": None,
    }
    if not accepted:
        return result

    positions = np.asarray(
        [(item.pose.x_m, item.pose.z_m) for item in accepted if item.pose is not None],
        dtype=np.float64,
    )
    app_timestamps = np.asarray([item.app_monotonic_ns for item in accepted], dtype=np.int64)
    platform_timestamps = np.asarray(
        [item.platform_timestamp_ns for item in accepted], dtype=np.int64
    )
    rssi = np.asarray([item.rssi_dbm for item in accepted], dtype=np.float64)

    if len(positions) > 1:
        steps = np.linalg.norm(np.diff(positions, axis=0), axis=1)
        path_length = float(np.sum(steps))
        net_displacement = float(np.linalg.norm(positions[-1] - positions[0]))
    else:
        path_length = 0.0
        net_displacement = 0.0

    centered = positions - positions.mean(axis=0)
    covariance = centered.T @ centered / len(positions)
    eigenvalues = np.maximum(np.linalg.eigvalsh(covariance), 0.0)
    minor_variance = float(eigenvalues[0])
    major_variance = float(eigenvalues[-1])
    geometry_ratio = minor_variance / major_variance if major_variance > 1e-12 else 0.0
    buckets = np.floor(positions / spatial_bucket_m).astype(np.int64)
    unique_bucket_count = len({(int(row[0]), int(row[1])) for row in buckets})
    intervals_ms = np.diff(platform_timestamps).astype(np.float64) / 1_000_000.0
    positive_intervals = intervals_ms[intervals_ms > 0]
    rssi_median = float(np.median(rssi))

    result.update(
        {
            "duration_s": float((app_timestamps[-1] - app_timestamps[0]) / 1e9),
            "path_length_m": path_length,
            "net_displacement_m": net_displacement,
            "span_x_m": float(np.ptp(positions[:, 0])),
            "span_z_m": float(np.ptp(positions[:, 1])),
            "spatial_rms_m": float(sqrt(float(np.mean(np.sum(centered**2, axis=1))))),
            "minor_spread_m": sqrt(minor_variance),
            "major_spread_m": sqrt(major_variance),
            "geometry_ratio": geometry_ratio,
            "unique_spatial_buckets": unique_bucket_count,
            "advertisement_interval_median_ms": (
                float(np.median(positive_intervals)) if len(positive_intervals) else None
            ),
            "rssi_median_dbm": rssi_median,
            "rssi_mad_db": float(np.median(np.abs(rssi - rssi_median))),
        }
    )
    return result


def horizontal_error_m(x_m: float, z_m: float, ground_truth: GroundTruth) -> float:
    return hypot(x_m - ground_truth.x_m, z_m - ground_truth.z_m)


def ellipse_metrics(
    *,
    mean_x_m: float,
    mean_z_m: float,
    covariance: npt.ArrayLike,
    ground_truth: GroundTruth,
    probability: float = 0.8,
) -> Metrics:
    """Calculate 2D Gaussian ellipse area, Mahalanobis distance, and containment."""

    if not 0 < probability < 1:
        raise ValueError("probability must be between zero and one")
    matrix = np.asarray(covariance, dtype=np.float64)
    if matrix.shape != (2, 2):
        raise ValueError("covariance must be a 2x2 matrix")
    if not np.all(np.isfinite(matrix)):
        raise ValueError("covariance must contain finite values")
    if not np.allclose(matrix, matrix.T, atol=1e-10):
        raise ValueError("covariance must be symmetric")
    eigenvalues = np.linalg.eigvalsh(matrix)
    if np.min(eigenvalues) < -1e-10:
        raise ValueError("covariance must be positive semidefinite")

    delta = np.asarray(
        [ground_truth.x_m - mean_x_m, ground_truth.z_m - mean_z_m], dtype=np.float64
    )
    threshold = -2.0 * log(1.0 - probability)  # Exact chi-square(2) quantile.
    determinant = max(float(np.linalg.det(matrix)), 0.0)
    area_m2 = pi * threshold * sqrt(determinant)

    # A singular ellipse has zero width. Its null-space component must also be zero.
    eigenvalues, eigenvectors = np.linalg.eigh(matrix)
    projected = eigenvectors.T @ delta
    positive = eigenvalues > 1e-12
    null_space_compatible = bool(np.all(np.abs(projected[~positive]) <= 1e-9))
    raw_mahalanobis_sq = (
        float(np.sum(projected[positive] ** 2 / eigenvalues[positive]))
        if null_space_compatible
        else float("inf")
    )
    return {
        "probability": probability,
        "error_m": horizontal_error_m(mean_x_m, mean_z_m, ground_truth),
        "mahalanobis_sq": raw_mahalanobis_sq if isfinite(raw_mahalanobis_sq) else None,
        "threshold": threshold,
        "covered": raw_mahalanobis_sq <= threshold,
        "area_m2": area_m2,
    }


def final_estimate_metrics(trace: Trace, *, probability: float = 0.8) -> Metrics | None:
    """Score the final location-bearing estimator snapshot in a labelled trace."""

    ground_truth = trace.session.ground_truth
    snapshot = next(
        (
            item.estimator
            for item in reversed(trace.observations)
            if item.estimator is not None and item.estimator.has_location
        ),
        None,
    )
    if snapshot is None:
        return None
    assert snapshot.mean_x_m is not None
    assert snapshot.mean_z_m is not None
    result: Metrics = {
        "kind": snapshot.kind,
        "status": snapshot.status,
        "x_m": snapshot.mean_x_m,
        "z_m": snapshot.mean_z_m,
        "error_m": (
            horizontal_error_m(snapshot.mean_x_m, snapshot.mean_z_m, ground_truth)
            if ground_truth is not None
            else None
        ),
        "probability": probability,
        "covered": None,
        "area_m2": None,
        "mahalanobis_sq": None,
    }
    if ground_truth is None or not snapshot.has_covariance:
        return result
    covariance = _snapshot_covariance(snapshot)
    result.update(
        ellipse_metrics(
            mean_x_m=snapshot.mean_x_m,
            mean_z_m=snapshot.mean_z_m,
            covariance=covariance,
            ground_truth=ground_truth,
            probability=probability,
        )
    )
    return result


def percentile(values: Sequence[float], quantile: float) -> float | None:
    if not values:
        return None
    return float(np.quantile(np.asarray(values, dtype=np.float64), quantile))


def _snapshot_covariance(snapshot: EstimatorSnapshot) -> npt.NDArray[np.float64]:
    assert snapshot.covariance_xx_m2 is not None
    assert snapshot.covariance_xz_m2 is not None
    assert snapshot.covariance_zz_m2 is not None
    return np.asarray(
        [
            [snapshot.covariance_xx_m2, snapshot.covariance_xz_m2],
            [snapshot.covariance_xz_m2, snapshot.covariance_zz_m2],
        ],
        dtype=np.float64,
    )
