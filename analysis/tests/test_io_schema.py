from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
import pytest

from ar_signal_analysis import Trace, TraceValidationError, load_trace, observations_frame
from ar_signal_analysis.io import write_trace

ANDROID_FIXTURE = Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v1.ndjson"


def test_loads_versioned_trace_and_normalizes_frame(trace: Trace) -> None:
    assert trace.session.schema_version == 1
    assert trace.session.session_id == "synthetic-fixture"
    assert trace.session.metadata["target"]["session_key"] == "synthetic-target"  # type: ignore[index]
    assert len(trace.observations) == 6

    frame = observations_frame(trace)

    assert tuple(frame["sequence"]) == (0, 1, 2, 3, 4, 5)
    assert str(frame["session_id"].dtype).startswith("string")
    assert str(frame["accepted"].dtype) == "boolean"
    assert pd.isna(frame.loc[5, "pose_x_m"])


def test_round_trip_is_canonical(trace: Trace, tmp_path: Path) -> None:
    destination = write_trace(trace, tmp_path / "round-trip.ndjson")

    loaded = load_trace(destination)

    assert loaded.session == trace.session
    assert loaded.observations == trace.observations


def test_android_recorder_contract_is_accepted() -> None:
    trace = load_trace(ANDROID_FIXTURE)

    assert trace.session.session_id == "android-fixture"
    assert trace.session.metadata["target"]["session_key"] == "D01"  # type: ignore[index]
    assert trace.observations[0].advertisement["manufacturer_ids"] == []


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda record: record.update(schema_version=99), "unsupported schema_version"),
        (lambda record: record.update(session_id="wrong"), "session_id differs"),
        (lambda record: record.update(sequence=0), "strictly increasing"),
    ],
)
def test_rejects_invalid_observation(
    fixture_path: Path,
    tmp_path: Path,
    mutation: object,
    message: str,
) -> None:
    records = [
        json.loads(line) for line in fixture_path.read_text().splitlines() if line.strip()
    ]
    target = records[2]
    assert callable(mutation)
    mutation(target)
    destination = tmp_path / "invalid.ndjson"
    destination.write_text("\n".join(json.dumps(record) for record in records) + "\n")

    with pytest.raises(TraceValidationError, match=message):
        load_trace(destination)


def test_reports_json_line_number(tmp_path: Path) -> None:
    destination = tmp_path / "broken.ndjson"
    destination.write_text('{"record_type":"session"}\nnot-json\n')

    with pytest.raises(TraceValidationError, match=r"broken\.ndjson:2: invalid JSON"):
        load_trace(destination)
