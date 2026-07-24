from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from pathlib import Path

import pandas as pd
import pytest

from ar_signal_analysis import Trace, TraceValidationError, load_trace, observations_frame
from ar_signal_analysis.io import write_trace

ANDROID_FIXTURE = Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v1.ndjson"
ANDROID_V2_FIXTURE = (
    Path(__file__).parents[2] / "test-data" / "fixtures" / "android_trace_v2.ndjson"
)
ANDROID_RECOVERED_V2_FIXTURE = (
    Path(__file__).parents[2] / "test-data" / "fixtures" / "android_recovered_trace_v2.ndjson"
)
V2_VALIDATION_CASES = (
    Path(__file__).parents[2] / "test-data" / "fixtures" / "v2_validation_cases.json"
)


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


def test_historical_v1_fixture_is_unchanged_and_target_transport_is_optional(
    fixture_path: Path,
) -> None:
    assert hashlib.sha256(fixture_path.read_bytes()).hexdigest() == (
        "670725253202c9991b11bbe7a7bf1557348c69300573cab1d4ababfbb8adb9bc"
    )
    assert load_trace(fixture_path).session.metadata["target"].get("transport") is None  # type: ignore[union-attr]


def test_round_trip_is_canonical(trace: Trace, tmp_path: Path) -> None:
    destination = write_trace(trace, tmp_path / "round-trip.ndjson")

    loaded = load_trace(destination)

    assert loaded.session == trace.session
    assert loaded.observations == trace.observations


def test_android_recorder_contract_is_accepted() -> None:
    trace = load_trace(ANDROID_FIXTURE)

    assert trace.session.session_id == "android-fixture"
    assert trace.session.metadata["target"]["session_key"] == "D01"  # type: ignore[index]
    assert trace.session.metadata["target"]["transport"] == "classic"  # type: ignore[index]
    assert trace.observations[0].estimator_rssi_dbm == -61.0
    assert trace.observations[0].advertisement["transport"] == "classic"
    assert trace.observations[0].advertisement["manufacturer_ids"] == []


def test_v1_v2_named_session_metadata_stays_opaque_and_round_trips(tmp_path: Path) -> None:
    records = [
        json.loads(line) for line in ANDROID_FIXTURE.read_text().splitlines() if line.strip()
    ]
    records[0].update(
        mode={"legacy": True},
        boot_id=["opaque", 7],
        started_at_monotonic_ns="not-a-v2-clock",
    )
    source = _write_records(tmp_path, records)

    trace = load_trace(source)

    assert trace.session.mode is None
    assert trace.session.started_at_monotonic_ns is None
    assert trace.session.metadata["mode"] == {"legacy": True}
    assert trace.session.metadata["boot_id"] == ["opaque", 7]
    assert trace.session.metadata["started_at_monotonic_ns"] == "not-a-v2-clock"
    written = json.loads(
        write_trace(trace, tmp_path / "round-trip.ndjson").read_text().splitlines()[0]
    )
    assert written["mode"] == {"legacy": True}
    assert written["boot_id"] == ["opaque", 7]
    assert written["started_at_monotonic_ns"] == "not-a-v2-clock"


def test_android_v2_complete_scan_contract_is_accepted(tmp_path: Path) -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)

    assert trace.session.schema_version == 2
    assert trace.session.mode == "COMPLETE_SCAN"
    assert (
        trace.session.metadata["boot_id"]
        == "454089edd3c095cda91fbeba3215dd0ccf288e4e26147ecd5e5d96384194e574"
    )
    assert trace.session.device_model == "Test phone 📱"
    assert [item.device_key for item in trace.observations] == ["D01", "D02", "D01"]
    assert [item.sequence for item in trace.observations] == [0, 1, 2]
    assert trace.events[0].code == "signal_lost"
    assert trace.events[0].device_key == "D02"
    assert trace.events[0].transport == "classic"
    assert trace.events[0].detail == "No packets — café 😀"
    assert trace.outcome is not None
    assert trace.outcome.outcome == "YES"
    assert trace.outcome.note == "Found — ✅"
    assert trace.outcome.observed_device_count == 2

    round_trip = load_trace(write_trace(trace, tmp_path / "v2-round-trip.ndjson"))
    assert round_trip.session == trace.session
    assert round_trip.observations == trace.observations
    assert round_trip.events == trace.events
    assert round_trip.outcome == trace.outcome


def test_v2_rejects_raw_boot_identity(tmp_path: Path) -> None:
    records = _v2_records()
    records[0]["boot_id"] = "raw-os-boot-uuid"

    with pytest.raises(TraceValidationError, match="SHA-256 digest"):
        load_trace(_write_records(tmp_path, records))


def test_huge_json_integer_is_reported_as_trace_validation_error(tmp_path: Path) -> None:
    payload = ANDROID_V2_FIXTURE.read_text().replace(
        '"sequence":0',
        f'"sequence":{("9" * 5_000)}',
        1,
    )
    destination = tmp_path / "huge-integer.ndjson"
    destination.write_text(payload)

    with pytest.raises(TraceValidationError, match="invalid JSON number"):
        load_trace(destination)


def test_android_recovery_contract_is_accepted() -> None:
    trace = load_trace(ANDROID_RECOVERED_V2_FIXTURE)

    assert trace.outcome is not None
    assert trace.outcome.outcome == "INCOMPLETE"
    assert trace.outcome.duration_ms == 5_000
    assert trace.outcome.observed_device_count == 2
    assert trace.outcome.localized_device_count == 1
    assert [
        (state["device_key"], state["transport"], state["status"])
        for state in trace.outcome.device_states
    ] == [
        ("D01", "low_energy", "ekf"),
        ("D02", "classic", "insufficient_evidence"),
    ]


def test_v2_rejects_persisted_advertised_name(tmp_path: Path) -> None:
    records = [
        json.loads(line) for line in ANDROID_V2_FIXTURE.read_text().splitlines() if line.strip()
    ]
    records[1]["advertisement"]["advertised_name"] = "private name"
    destination = tmp_path / "privacy-leak.ndjson"
    destination.write_text("\n".join(json.dumps(record) for record in records) + "\n")

    with pytest.raises(TraceValidationError, match="must not persist advertised_name"):
        load_trace(destination)


def test_v2_rejects_device_key_transport_changes(tmp_path: Path) -> None:
    records = [
        json.loads(line) for line in ANDROID_V2_FIXTURE.read_text().splitlines() if line.strip()
    ]
    records[4]["transport"] = "classic"
    destination = tmp_path / "transport-crossover.ndjson"
    destination.write_text("\n".join(json.dumps(record) for record in records) + "\n")

    with pytest.raises(TraceValidationError, match="device_key D01 changes transport"):
        load_trace(destination)


@pytest.mark.parametrize(
    "field",
    ["estimator_rssi_dbm", "rssi_variance_db2", "pose_association_age_ns"],
)
def test_v2_rejects_missing_exact_estimator_inputs(tmp_path: Path, field: str) -> None:
    records = _v2_records()
    records[1][field] = None

    with pytest.raises(TraceValidationError, match="accepted v2 observations require"):
        load_trace(_write_records(tmp_path, records))


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (
            lambda records: (
                records[2].update(device_key="D03"),
                records[3].update(device_key="D03"),
            ),
            "outcome device states must exactly cover",
        ),
        (
            lambda records: records[3].update(device_key="D03"),
            "outcome device states must exactly cover",
        ),
        (
            lambda records: records[5]["device_states"][0].update(transport="classic"),
            "device_key D01 changes transport",
        ),
        (
            lambda records: records[3].update(transport="low_energy"),
            "device_key D02 changes transport",
        ),
        (
            lambda records: records[5]["device_states"].pop(),
            "outcome device states must exactly cover",
        ),
        (
            lambda records: records[5].update(observed_device_count=1),
            "observed_device_count must equal",
        ),
        (
            lambda records: records[5].update(localized_device_count=0),
            "localized_device_count must equal",
        ),
        (
            lambda records: records[5]["device_states"][0].update(status="insufficient_evidence"),
            "insufficient_evidence requires",
        ),
        (
            lambda records: records[5]["device_states"][0]["estimator"].update(kind="ekf"),
            "kind/status combination",
        ),
    ],
)
def test_v2_rejects_identity_count_and_state_invariant_violations(
    tmp_path: Path,
    mutate: object,
    message: str,
) -> None:
    records = _v2_records()
    assert callable(mutate)
    mutate(records)

    with pytest.raises(TraceValidationError, match=message):
        load_trace(_write_records(tmp_path, records))


def test_v2_single_target_rejects_an_observation_for_another_device(tmp_path: Path) -> None:
    records = _v2_records()
    records[0]["mode"] = "SINGLE_TARGET"
    records[0]["target"] = {
        "session_key": "D01",
        "transport": "low_energy",
        "manufacturer_ids": [],
        "service_uuids": [],
    }

    with pytest.raises(TraceValidationError, match="selected target key"):
        load_trace(_write_records(tmp_path, records))


def test_v2_complete_scan_allows_event_scoped_device_with_final_state(
    tmp_path: Path,
) -> None:
    records = _v2_records()
    records[3]["device_key"] = "D03"
    records[5]["device_states"].append(
        {
            "device_key": "D03",
            "transport": "classic",
            "status": "insufficient_evidence",
            "estimator": None,
        }
    )

    trace = load_trace(_write_records(tmp_path, records))

    assert trace.outcome is not None
    assert [state["device_key"] for state in trace.outcome.device_states] == [
        "D01",
        "D02",
        "D03",
    ]


def test_write_trace_reuses_field_parsers_for_constructed_records(tmp_path: Path) -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)
    malformed = replace(trace.observations[0], transport=None, rssi_dbm=-200)

    with pytest.raises(TraceValidationError, match="rssi_dbm"):
        write_trace(
            replace(trace, observations=(malformed, *trace.observations[1:])), tmp_path / "x"
        )


def test_write_trace_reports_missing_device_state_transport_as_schema_error(
    tmp_path: Path,
) -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)
    assert trace.outcome is not None
    states = [dict(state) for state in trace.outcome.device_states]
    states[0].pop("transport")

    with pytest.raises(TraceValidationError, match=r"device state\.transport"):
        write_trace(
            replace(trace, outcome=replace(trace.outcome, device_states=tuple(states))),
            tmp_path / "x",
        )


def test_write_trace_requires_constructed_target_transport(tmp_path: Path) -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)
    session = replace(
        trace.session,
        mode="SINGLE_TARGET",
        metadata={
            **trace.session.metadata,
            "target": {
                "session_key": "D01",
                "manufacturer_ids": [],
                "service_uuids": [],
            },
        },
    )

    with pytest.raises(TraceValidationError, match=r"target\.transport"):
        write_trace(replace(trace, session=session), tmp_path / "x")


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (
            lambda records: records[3].update(detail="scanner\nfailed"),
            "control characters",
        ),
        (
            lambda records: records[5].update(reason="free-form"),
            "unknown outcome reason",
        ),
        (
            lambda records: records[5].update(reason="other"),
            "only valid for NO or NOT_SURE",
        ),
        (
            lambda records: records[5].update(note="x" * 501),
            "at most 500",
        ),
    ],
)
def test_v2_rejects_unbounded_control_or_outcome_text(
    tmp_path: Path,
    mutation: object,
    message: str,
) -> None:
    records = _v2_records()
    assert callable(mutation)
    mutation(records)

    with pytest.raises(TraceValidationError, match=message):
        load_trace(_write_records(tmp_path, records))


def test_v2_accepts_documented_reason_and_text_boundaries(tmp_path: Path) -> None:
    records = _v2_records()
    records[3]["detail"] = "x" * 200
    records[5].update(outcome="NO", reason="guidance_unclear", note="x" * 500)

    trace = load_trace(_write_records(tmp_path, records))

    assert trace.events[0].detail == "x" * 200
    assert trace.outcome is not None
    assert trace.outcome.reason == "guidance_unclear"
    assert trace.outcome.note == "x" * 500


@pytest.mark.parametrize(
    "case",
    json.loads(V2_VALIDATION_CASES.read_text()),
    ids=lambda case: case["name"],
)
def test_shared_strict_v2_grammar_cases(tmp_path: Path, case: dict[str, object]) -> None:
    records = _v2_records()
    records[0]["created_at_utc"] = case["created_at_utc"]
    records[1]["pose"]["tracking_state"] = case["tracking_state"]  # type: ignore[index]
    records[3]["detail"] = str(case["detail_character"]) * int(case["detail_count"])  # type: ignore[arg-type]
    if "device_key" in case:
        records[1]["device_key"] = case["device_key"]
        records[4]["device_key"] = case["device_key"]
        records[5]["device_states"][0]["device_key"] = case["device_key"]  # type: ignore[index]
    if "platform_timestamp_ns" in case:
        records[1]["platform_timestamp_ns"] = case["platform_timestamp_ns"]
    destination = _write_records(tmp_path, records)

    if case["valid"]:
        assert load_trace(destination).events[0].detail == records[3]["detail"]
    else:
        with pytest.raises(TraceValidationError):
            load_trace(destination)


def test_v2_rejects_unsupported_fields_at_every_structural_boundary(
    tmp_path: Path,
) -> None:
    def target(records: list[dict[str, object]]) -> None:
        records[0]["mode"] = "SINGLE_TARGET"
        records[0]["target"] = {
            "session_key": "D01",
            "transport": "low_energy",
            "manufacturer_ids": [],
            "service_uuids": [],
            "stable_identifier": "private",
        }

    mutations = [
        lambda records: records[0].update(gps_coordinates="private"),
        target,
        lambda records: records[0].update(
            ground_truth={"x_m": 0.0, "z_m": 0.0, "gps_coordinates": "private"}
        ),
        lambda records: records[0].update(simulation={"email": "private"}),
        lambda records: records[1].update(camera_image="private"),
        lambda records: records[1]["pose"].update(gps_coordinates="private"),  # type: ignore[union-attr]
        lambda records: records[1]["advertisement"].update(advertising_id="private"),  # type: ignore[union-attr]
        lambda records: records[3].update(contact_data="private"),
        lambda records: records[4]["estimator"].update(play_account="private"),  # type: ignore[union-attr]
        lambda records: records[5].update(email="private"),
        lambda records: records[5]["device_states"][0].update(stable_identifier="private"),  # type: ignore[index,union-attr]
        lambda records: records[5]["device_states"][0]["estimator"].update(  # type: ignore[index,union-attr]
            camera_video="private"
        ),
    ]
    for mutate in mutations:
        records = _v2_records()
        mutate(records)

        with pytest.raises(TraceValidationError, match="unsupported field"):
            load_trace(_write_records(tmp_path, records))


def test_blank_android_event_detail_and_outcome_note_normalize_to_null(
    tmp_path: Path,
) -> None:
    records = _v2_records()
    records[3]["detail"] = " \t "
    records[5]["note"] = "\n"

    trace = load_trace(_write_records(tmp_path, records))

    assert trace.events[0].detail is None
    assert trace.outcome is not None
    assert trace.outcome.note is None
    written = [
        json.loads(line)
        for line in write_trace(trace, tmp_path / "normalized.ndjson").read_text().splitlines()
    ]
    assert written[3]["detail"] is None
    assert written[-1]["note"] is None


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("display_name", "private display name", "display_name"),
        ("advertised_name", "private advertised name", "advertised_name"),
        ("label", "AA:BB:CC:DD:EE:FF", "Bluetooth address"),
    ],
)
def test_v2_writer_rejects_private_target_data(
    tmp_path: Path,
    field: str,
    value: str,
    message: str,
) -> None:
    trace = load_trace(ANDROID_V2_FIXTURE)
    target = {
        "session_key": "D01",
        "transport": "low_energy",
        field: value,
    }
    session = replace(
        trace.session,
        mode="SINGLE_TARGET",
        metadata={**trace.session.metadata, "target": target},
    )
    destination = tmp_path / "privacy-leak.ndjson"

    with pytest.raises(TraceValidationError, match=message):
        write_trace(replace(trace, session=session), destination)

    assert not destination.exists()


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
    records = [json.loads(line) for line in fixture_path.read_text().splitlines() if line.strip()]
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


def test_rejects_unknown_bluetooth_transport(tmp_path: Path) -> None:
    records = [
        json.loads(line) for line in ANDROID_FIXTURE.read_text().splitlines() if line.strip()
    ]
    records[1]["advertisement"]["transport"] = "wifi"
    destination = tmp_path / "invalid-transport.ndjson"
    destination.write_text("\n".join(json.dumps(record) for record in records) + "\n")

    with pytest.raises(TraceValidationError, match=r"advertisement\.transport"):
        load_trace(destination)


def _v2_records() -> list[dict[str, object]]:
    return [
        json.loads(line) for line in ANDROID_V2_FIXTURE.read_text().splitlines() if line.strip()
    ]


def _write_records(tmp_path: Path, records: list[dict[str, object]]) -> Path:
    destination = tmp_path / "mutated.ndjson"
    destination.write_text("\n".join(json.dumps(record) for record in records) + "\n")
    return destination
