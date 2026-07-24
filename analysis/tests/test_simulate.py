from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from ar_signal_analysis import SimulationConfig, load_trace, path_metrics, simulate_trace
from ar_signal_analysis.cli import simulate_main
from ar_signal_analysis.io import write_trace
from ar_signal_analysis.simulate import expected_log_distance_rssi_dbm


def test_simulator_is_seeded_and_uses_student_t_outliers() -> None:
    config = SimulationConfig(sample_count=32, seed=42, outlier_probability=0.25)

    first = simulate_trace(config)
    second = simulate_trace(config)

    assert [item.rssi_dbm for item in first.observations] == [
        item.rssi_dbm for item in second.observations
    ]
    assert any(item.advertisement["synthetic_outlier"] for item in first.observations)
    assert first.session.metadata["simulation"]["student_t_df"] == 4.0  # type: ignore[index]
    assert path_metrics(first)["geometry_ratio"] > 0.1


def test_accepts_explicit_path(tmp_path: Path) -> None:
    config = SimulationConfig(
        sample_count=3,
        noise_scale_db=0.0,
        outlier_probability=0.0,
        target_x_m=0.0,
        target_y_m=1.35,
        target_z_m=0.0,
    )
    path = np.asarray([[1.0, 0.0], [2.0, 0.0], [4.0, 0.0]])

    trace = simulate_trace(config, path_xz_m=path)
    trace = load_trace(write_trace(trace, tmp_path / "zero-noise.ndjson"))

    assert all(
        observation.rssi_variance_db2 is not None and observation.rssi_variance_db2 > 0.0
        for observation in trace.observations
    )
    assert [item.rssi_dbm for item in trace.observations] == [
        -52,
        round(expected_log_distance_rssi_dbm(2.0, rssi_at_1m_dbm=-52.0, path_loss_exponent=2.2)),
        round(expected_log_distance_rssi_dbm(4.0, rssi_at_1m_dbm=-52.0, path_loss_exponent=2.2)),
    ]


def test_simulate_cli_writes_loadable_trace(tmp_path: Path, capsys: object) -> None:
    destination = tmp_path / "generated.ndjson"

    assert simulate_main([str(destination), "--samples", "12", "--seed", "5"]) == 0
    captured = capsys.readouterr()  # type: ignore[attr-defined]

    assert "12 observations" in captured.out
    assert len(load_trace(destination).observations) == 12


def test_rejects_nonfinite_variance_student_t() -> None:
    with pytest.raises(ValueError, match="exceed 2"):
        simulate_trace(SimulationConfig(student_t_df=2.0))
