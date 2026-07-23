from math import pi

import pytest

from ar_signal_analysis import (
    GroundTruth,
    Trace,
    ellipse_metrics,
    final_estimate_metrics,
    path_metrics,
    strongest_rssi_baseline,
)


def test_strongest_rssi_baseline_scores_pose(trace: Trace) -> None:
    baseline = strongest_rssi_baseline(trace)

    assert baseline is not None
    assert baseline["sequence"] == 4
    assert baseline["rssi_dbm"] == -55.0
    assert baseline["error_m"] == pytest.approx(5**0.5 / 10)


def test_path_metrics_capture_geometry_and_rejections(trace: Trace) -> None:
    metrics = path_metrics(trace)

    assert metrics["accepted_count"] == 5
    assert metrics["rejected_count"] == 1
    assert metrics["pose_association_rate"] == pytest.approx(5 / 6)
    assert metrics["duration_s"] == 1.0
    assert metrics["path_length_m"] == 7.5
    assert metrics["span_x_m"] == 2.0
    assert metrics["span_z_m"] == 2.0
    assert metrics["geometry_ratio"] > 0.5
    assert metrics["advertisement_interval_median_ms"] == 250.0


def test_ellipse_metrics_use_exact_chi_square_two_quantile() -> None:
    metrics = ellipse_metrics(
        mean_x_m=0.0,
        mean_z_m=0.0,
        covariance=[[1.0, 0.0], [0.0, 1.0]],
        ground_truth=GroundTruth(x_m=1.0, z_m=0.0),
        probability=0.8,
    )

    expected_threshold = -2.0 * __import__("math").log(0.2)
    assert metrics["threshold"] == pytest.approx(expected_threshold)
    assert metrics["area_m2"] == pytest.approx(pi * expected_threshold)
    assert metrics["mahalanobis_sq"] == 1.0
    assert metrics["covered"] is True


def test_final_estimate_scores_error_and_coverage(trace: Trace) -> None:
    metrics = final_estimate_metrics(trace)

    assert metrics is not None
    assert metrics["kind"] == "hybrid"
    assert metrics["error_m"] == pytest.approx(2**0.5 / 10)
    assert metrics["covered"] is True
    assert metrics["area_m2"] > 0


def test_rejects_non_psd_covariance() -> None:
    with pytest.raises(ValueError, match="positive semidefinite"):
        ellipse_metrics(
            mean_x_m=0.0,
            mean_z_m=0.0,
            covariance=[[1.0, 2.0], [2.0, 1.0]],
            ground_truth=GroundTruth(x_m=0.0, z_m=0.0),
        )
