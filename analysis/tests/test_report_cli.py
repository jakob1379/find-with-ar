from __future__ import annotations

import json
from pathlib import Path

from ar_signal_analysis import Trace, build_calibration_report, format_text_report
from ar_signal_analysis.cli import analyse_main


def test_builds_concise_calibration_report(trace: Trace) -> None:
    report = build_calibration_report([trace])
    summary = report["summary"]

    assert summary["session_count"] == 1
    assert summary["estimated_session_count"] == 1
    assert summary["median_improvement_m"] > 0
    assert summary["gates"]["beats_strongest_rssi_median"] is True
    assert summary["gates"]["open_room_median_le_1_5_m"] is True
    assert summary["empirical_coverage"] == 1.0

    text = format_text_report(report)
    assert "strongest RSSI" in text
    assert "synthetic-fixture" in text
    assert "Evaluated gates:" in text


def test_cli_emits_json(fixture_path: Path, capsys: object) -> None:
    assert analyse_main([str(fixture_path), "--format", "json"]) == 0
    captured = capsys.readouterr()  # type: ignore[attr-defined]
    report = json.loads(captured.out)

    assert report["report_schema_version"] == 1
    assert report["sessions"][0]["path"]["accepted_count"] == 5


def test_cli_writes_text_report(fixture_path: Path, tmp_path: Path) -> None:
    destination = tmp_path / "report.txt"

    assert analyse_main([str(fixture_path), "--output", str(destination)]) == 0

    assert destination.read_text().startswith("AR Signal Locator calibration report")
