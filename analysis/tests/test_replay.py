from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest

from ar_signal_analysis import (
    estimator_inputs,
    estimator_inputs_by_device,
    format_estimator_inputs,
    load_trace,
)
from ar_signal_analysis.cli import replay_main

ANDROID_FIXTURE = Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v1.ndjson"
ANDROID_V2_FIXTURE = (
    Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v2.ndjson"
)


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


def test_replay_cli_requires_device_for_multi_device_trace(
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit) as error:
        replay_main([str(ANDROID_V2_FIXTURE)])

    assert error.value.code == 2
    assert "pass --device-key (D01, D02)" in capsys.readouterr().err


def test_replay_cli_never_interleaves_selected_device_stream(
    tmp_path: Path,
) -> None:
    destination = tmp_path / "D01.ndjson"

    assert (
        replay_main(
            [
                str(ANDROID_V2_FIXTURE),
                "--device-key",
                "D01",
                "--output",
                str(destination),
            ]
        )
        == 0
    )

    records = [json.loads(line) for line in destination.read_text().splitlines()]
    assert [record["sequence"] for record in records] == [0, 2]
    assert {record["device_key"] for record in records} == {"D01"}


def test_explicit_estimator_rssi_preserves_new_raw_input() -> None:
    trace = load_trace(ANDROID_FIXTURE)
    observation = replace(trace.observations[0], estimator_rssi_dbm=-61.0)

    assert estimator_inputs(replace(trace, observations=(observation,)))[0]["rssi_dbm"] == -61.0


def test_replays_interleaved_v2_devices_without_identity_crossover() -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)

    first = estimator_inputs_by_device(trace)
    second = estimator_inputs_by_device(trace)

    assert first == second
    assert list(first) == ["D01", "D02"]
    assert [item["sequence"] for item in first["D01"]] == [0, 2]
    assert [item["rssi_dbm"] for item in first["D01"]] == [-61.0, -58.0]
    assert [item["sequence"] for item in first["D02"]] == [1]
    assert all(item["device_key"] == "D01" for item in first["D01"])
    assert [item["pose_association_age_ns"] for item in first["D01"]] == [2, 2]
    assert format_estimator_inputs(first["D01"]) == format_estimator_inputs(second["D01"])


def test_v2_replay_uses_exact_recorded_estimator_inputs() -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)
    observation = replace(
        trace.observations[0],
        rssi_dbm=-90,
        filtered_rssi_dbm=-80.0,
        estimator_rssi_dbm=-63.25,
        rssi_variance_db2=4.5,
        pose_association_age_ns=123,
    )

    replayed = estimator_inputs(replace(trace, observations=(observation,)))[0]

    assert replayed["rssi_dbm"] == -63.25
    assert replayed["measurement_variance_db2"] == 4.5
    assert replayed["pose_association_age_ns"] == 123
