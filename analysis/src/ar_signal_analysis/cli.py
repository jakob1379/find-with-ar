"""Command-line interfaces for analysis and synthetic trace generation."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

from ar_signal_analysis.io import load_trace, write_trace
from ar_signal_analysis.replay import estimator_inputs_by_device, format_estimator_inputs
from ar_signal_analysis.report import build_calibration_report, format_text_report
from ar_signal_analysis.schema import TraceValidationError
from ar_signal_analysis.simulate import SimulationConfig, simulate_trace, utc_now_iso


def analyse_entrypoint() -> None:
    raise SystemExit(analyse_main())


def simulate_entrypoint() -> None:
    raise SystemExit(simulate_main())


def replay_entrypoint() -> None:
    raise SystemExit(replay_main())


def analyse_main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="ar-signal-analyse",
        description="Validate traces and report baseline, path, error, and coverage metrics.",
    )
    parser.add_argument("traces", nargs="+", type=Path, help="versioned NDJSON trace")
    parser.add_argument(
        "--format", choices=("text", "json"), default="text", help="report encoding"
    )
    parser.add_argument("--output", type=Path, help="write the report instead of stdout")
    parser.add_argument(
        "--coverage-probability",
        type=float,
        default=0.8,
        help="nominal 2D Gaussian region probability (default: 0.8)",
    )
    parser.add_argument(
        "--strict-gates",
        action="store_true",
        help="exit 1 if any evaluated MVP gate fails",
    )
    args = parser.parse_args(argv)
    try:
        traces = [load_trace(path) for path in args.traces]
        report = build_calibration_report(
            traces, coverage_probability=args.coverage_probability
        )
    except (TraceValidationError, ValueError) as error:
        parser.exit(2, f"error: {error}\n")

    payload = (
        json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
        if args.format == "json"
        else format_text_report(report)
    )
    if args.output is None:
        sys.stdout.write(payload)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")

    if args.strict_gates:
        evaluated = [value for value in report["summary"]["gates"].values() if value is not None]
        if evaluated and not all(evaluated):
            return 1
    return 0


def replay_main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="ar-signal-replay",
        description="Reconstruct the ordered estimator-input stream from an Android trace.",
    )
    parser.add_argument("trace", type=Path, help="versioned NDJSON trace")
    parser.add_argument(
        "--device-key",
        help="session-local device key; required when a trace contains multiple devices",
    )
    parser.add_argument("--output", type=Path, help="write canonical NDJSON instead of stdout")
    args = parser.parse_args(argv)
    try:
        streams = estimator_inputs_by_device(load_trace(args.trace))
        if args.device_key is None and len(streams) > 1:
            parser.error(
                "trace contains multiple devices; pass --device-key "
                f"({', '.join(streams)})"
            )
        if not streams and args.device_key is None:
            payload = ""
        else:
            device_key = args.device_key or next(iter(streams))
            if device_key not in streams:
                parser.error(
                    f"device key {device_key!r} is not present "
                    f"({', '.join(streams) or 'no devices'})"
                )
            payload = format_estimator_inputs(streams[device_key])
        if args.output is None:
            sys.stdout.write(payload)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(payload, encoding="utf-8")
    except (OSError, TraceValidationError, ValueError) as error:
        parser.exit(2, f"error: {error}\n")
    return 0


def simulate_main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="ar-signal-simulate",
        description="Generate a seeded log-distance RSSI NDJSON trace.",
    )
    parser.add_argument("output", type=Path)
    parser.add_argument("--samples", type=int, default=160)
    parser.add_argument("--interval-s", type=float, default=0.25)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--session-id", default="synthetic-0001")
    parser.add_argument("--target-x-m", type=float, default=1.4)
    parser.add_argument("--target-y-m", type=float, default=0.4)
    parser.add_argument("--target-z-m", type=float, default=0.6)
    parser.add_argument("--rssi-at-1m-dbm", type=float, default=-52.0)
    parser.add_argument("--path-loss-exponent", type=float, default=2.2)
    parser.add_argument("--student-t-df", type=float, default=4.0)
    parser.add_argument("--noise-scale-db", type=float, default=2.5)
    parser.add_argument("--outlier-probability", type=float, default=0.04)
    parser.add_argument("--outlier-scale-db", type=float, default=12.0)
    args = parser.parse_args(argv)
    try:
        config = SimulationConfig(
            session_id=args.session_id,
            sample_count=args.samples,
            interval_s=args.interval_s,
            seed=args.seed,
            target_x_m=args.target_x_m,
            target_y_m=args.target_y_m,
            target_z_m=args.target_z_m,
            rssi_at_1m_dbm=args.rssi_at_1m_dbm,
            path_loss_exponent=args.path_loss_exponent,
            student_t_df=args.student_t_df,
            noise_scale_db=args.noise_scale_db,
            outlier_probability=args.outlier_probability,
            outlier_scale_db=args.outlier_scale_db,
            created_at_utc=utc_now_iso(),
        )
        write_trace(simulate_trace(config), args.output)
    except (OSError, ValueError) as error:
        parser.exit(2, f"error: {error}\n")
    sys.stdout.write(f"Wrote {args.samples} observations to {args.output}\n")
    return 0
