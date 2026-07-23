"""Calibration-report aggregation and rendering."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from ar_signal_analysis.metrics import (
    final_estimate_metrics,
    path_metrics,
    percentile,
    strongest_rssi_baseline,
)
from ar_signal_analysis.schema import Trace

type Report = dict[str, Any]


def build_calibration_report(
    traces: Sequence[Trace], *, coverage_probability: float = 0.8
) -> Report:
    """Build a deterministic, JSON-serializable report for one or more sessions."""

    if not traces:
        raise ValueError("at least one trace is required")
    if not 0 < coverage_probability < 1:
        raise ValueError("coverage_probability must be between zero and one")

    sessions: list[Report] = []
    baseline_errors: list[float] = []
    estimator_errors: list[float] = []
    coverage_values: list[bool] = []
    paired_improvements: list[float] = []
    held_out_errors: list[float] = []
    estimated_count = 0
    by_environment: defaultdict[str, list[float]] = defaultdict(list)

    for trace in traces:
        baseline = strongest_rssi_baseline(trace)
        estimate = final_estimate_metrics(trace, probability=coverage_probability)
        if estimate is not None:
            estimated_count += 1
        path = path_metrics(trace)
        baseline_error = baseline.get("error_m") if baseline is not None else None
        estimate_error = estimate.get("error_m") if estimate is not None else None
        covered = estimate.get("covered") if estimate is not None else None
        if isinstance(baseline_error, int | float):
            baseline_errors.append(float(baseline_error))
        if isinstance(estimate_error, int | float):
            estimator_errors.append(float(estimate_error))
            environment = (trace.session.environment or "unspecified").lower()
            by_environment[environment].append(float(estimate_error))
            if str(trace.session.metadata.get("dataset_split", "")).lower() in {
                "held_out",
                "validation",
            }:
                held_out_errors.append(float(estimate_error))
        if isinstance(baseline_error, int | float) and isinstance(
            estimate_error, int | float
        ):
            paired_improvements.append(float(baseline_error) - float(estimate_error))
        if isinstance(covered, bool):
            coverage_values.append(covered)
        sessions.append(
            {
                "session_id": trace.session.session_id,
                "source": _source_name(trace.source),
                "environment": trace.session.environment,
                "labelled": trace.session.ground_truth is not None,
                "path": path,
                "strongest_rssi_baseline": baseline,
                "final_estimate": estimate,
            }
        )

    baseline_summary = _error_summary(baseline_errors)
    estimator_summary = _error_summary(estimator_errors)
    empirical_coverage = (
        sum(coverage_values) / len(coverage_values) if coverage_values else None
    )
    median_baseline = baseline_summary["median_error_m"]
    median_estimator = estimator_summary["median_error_m"]
    median_improvement = (
        median_baseline - median_estimator
        if isinstance(median_baseline, float) and isinstance(median_estimator, float)
        else None
    )
    relative_improvement = (
        median_improvement / median_baseline
        if isinstance(median_improvement, float)
        and isinstance(median_baseline, float)
        and median_baseline > 0
        else None
    )

    open_errors = by_environment.get("open", []) + by_environment.get("open_room", [])
    furnished_errors = by_environment.get("furnished", []) + by_environment.get(
        "furnished_room", []
    )
    gates = {
        "beats_strongest_rssi_median": (
            percentile(paired_improvements, 0.5) > 0
            if paired_improvements
            else None
        ),
        "open_room_median_le_1_5_m": _threshold_gate(open_errors, 0.5, 1.5),
        "furnished_room_median_le_2_5_m": _threshold_gate(
            furnished_errors, 0.5, 2.5
        ),
        "held_out_p90_le_3_m": _threshold_gate(held_out_errors, 0.9, 3.0),
        "nominal_80pct_coverage_between_70_and_90pct": (
            0.7 <= empirical_coverage <= 0.9
            if coverage_probability == 0.8 and len(coverage_values) >= 10
            else None
        ),
    }

    return {
        "report_schema_version": 1,
        "coverage_probability": coverage_probability,
        "summary": {
            "session_count": len(traces),
            "labelled_session_count": sum(
                trace.session.ground_truth is not None for trace in traces
            ),
            "estimated_session_count": estimated_count,
            "coverage_session_count": len(coverage_values),
            "held_out_session_count": len(held_out_errors),
            "strongest_rssi_baseline": baseline_summary,
            "estimator": estimator_summary,
            "median_improvement_m": median_improvement,
            "relative_median_improvement": relative_improvement,
            "empirical_coverage": empirical_coverage,
            "gates": gates,
        },
        "sessions": sessions,
    }


def format_text_report(report: Report) -> str:
    """Render a compact report suitable for a terminal or CI log."""

    summary = report["summary"]
    lines = [
        "AR Signal Locator calibration report",
        (
            f"Sessions: {summary['session_count']} "
            f"({summary['labelled_session_count']} labelled, "
            f"{summary['estimated_session_count']} estimated)"
        ),
    ]
    baseline = summary["strongest_rssi_baseline"]
    estimator = summary["estimator"]
    lines.append(
        "Median error: strongest RSSI "
        f"{_format_measurement(baseline['median_error_m'], 'm')}; estimator "
        f"{_format_measurement(estimator['median_error_m'], 'm')}"
    )
    lines.append(
        f"Estimator p90 error: {_format_measurement(estimator['p90_error_m'], 'm')}"
    )
    lines.append(
        f"Empirical {float(report['coverage_probability']):.0%} coverage: "
        f"{_format_percentage(summary['empirical_coverage'])}"
    )
    lines.append("Sessions:")
    for session in report["sessions"]:
        path = session["path"]
        baseline_error = _nested(session, "strongest_rssi_baseline", "error_m")
        estimate_error = _nested(session, "final_estimate", "error_m")
        covered = _nested(session, "final_estimate", "covered")
        lines.append(
            f"- {session['session_id']}: {path['accepted_count']} accepted, "
            f"path {_format_measurement(path['path_length_m'], 'm')}, "
            f"baseline {_format_measurement(baseline_error, 'm')}, "
            f"estimate {_format_measurement(estimate_error, 'm')}, "
            f"covered {_format_bool(covered)}"
        )
    evaluated_gates = {
        name: value for name, value in summary["gates"].items() if value is not None
    }
    if evaluated_gates:
        lines.append("Evaluated gates:")
        lines.extend(
            f"- {name}: {'PASS' if value else 'FAIL'}"
            for name, value in evaluated_gates.items()
        )
    return "\n".join(lines) + "\n"


def _error_summary(errors: Sequence[float]) -> Report:
    return {
        "count": len(errors),
        "median_error_m": percentile(errors, 0.5),
        "p90_error_m": percentile(errors, 0.9),
        "mean_error_m": sum(errors) / len(errors) if errors else None,
    }


def _threshold_gate(
    errors: Sequence[float], quantile: float, maximum: float
) -> bool | None:
    value = percentile(errors, quantile)
    return value <= maximum if value is not None else None


def _source_name(source: Path | None) -> str | None:
    return str(source) if source is not None else None


def _nested(value: Report, outer: str, inner: str) -> Any:
    nested = value.get(outer)
    return nested.get(inner) if isinstance(nested, dict) else None


def _format_measurement(value: Any, unit: str) -> str:
    return f"{float(value):.2f} {unit}" if isinstance(value, int | float) else "n/a"


def _format_percentage(value: Any) -> str:
    return f"{float(value):.1%}" if isinstance(value, int | float) else "n/a"


def _format_bool(value: Any) -> str:
    return "yes" if value is True else "no" if value is False else "n/a"
