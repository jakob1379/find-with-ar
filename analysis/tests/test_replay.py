from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

from ar_signal_analysis import estimator_inputs, format_estimator_inputs, load_trace
from ar_signal_analysis.cli import replay_main

ANDROID_FIXTURE = Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v1.ndjson"


def test_reconstructs_recorded_estimator_input_deterministically() -> None:
    trace = load_trace(ANDROID_FIXTURE)

    first = estimator_inputs(trace)
    second = estimator_inputs(trace)

    assert first == second
    assert first == (
        {
            "elapsed_realtime_ns": 20,
            "measurement_variance_db2": 9.0,
            "phone_forward_x": -0.0,
            "phone_forward_z": -1.0,
            "phone_height_m": 1.4,
            "phone_x_m": 1.0,
            "phone_z_m": 2.0,
            "rssi_dbm": -61.0,
            "sequence": 0,
            "session_id": "android-fixture",
        },
    )
    assert format_estimator_inputs(first) == format_estimator_inputs(second)


def test_replay_cli_writes_canonical_ndjson(tmp_path: Path) -> None:
    destination = tmp_path / "estimator-inputs.ndjson"

    assert replay_main([str(ANDROID_FIXTURE), "--output", str(destination)]) == 0

    records = [json.loads(line) for line in destination.read_text().splitlines()]
    assert records[0]["sequence"] == 0
    assert records[0]["rssi_dbm"] == -61.0


def test_explicit_estimator_rssi_preserves_new_raw_input() -> None:
    trace = load_trace(ANDROID_FIXTURE)
    observation = replace(trace.observations[0], estimator_rssi_dbm=-61.0)

    assert estimator_inputs(replace(trace, observations=(observation,)))[0]["rssi_dbm"] == -61.0
