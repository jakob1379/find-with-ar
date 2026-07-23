# AR Signal Locator

Android proof of concept for locating a stationary, actively advertising Bluetooth Low Energy device by combining BLE RSSI observations with ARCore phone poses.

The app is intentionally honest about the available evidence. Early observations are shown as a probability region; a single direction or covariance ellipse is shown only after the data supports one dominant location.

## Scope

- Android, Kotlin, Jetpack Compose and ARCore
- Foreground BLE advertisement scanning
- Anchor-relative 2D localization with height hypotheses
- Bayesian grid acquisition and optional EKF refinement
- Offline, versioned trace capture and deterministic replay
- Python/uv simulation and calibration utilities

It cannot find a device that is powered off, asleep, radio-blocked or no longer advertising.

## Development

```bash
nix develop
just bootstrap-wrapper   # needed only if the wrapper JAR was not retained
just check
just install
```

Use a physical ARCore-supported phone for combined BLE and AR validation. The emulator is useful for UI and lifecycle tests but cannot reproduce indoor radio propagation.

Generate and inspect a deterministic synthetic trace with:

```bash
uv run --project analysis ar-signal-simulate /tmp/synthetic.ndjson --seed 7
uv run --project analysis ar-signal-replay /tmp/synthetic.ndjson --output /tmp/inputs.ndjson
uv run --project analysis ar-signal-analyse /tmp/synthetic.ndjson
```

See [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for staged gate evidence and [`docs/validation-protocol.md`](docs/validation-protocol.md) for the physical-phone procedure.

## Verification boundary

The debug APK, Android unit tests, Kotlin estimator tests, lint, Python tests and flake evaluation are verified in the build workspace. Simultaneous BLE + ARCore behavior and real-room error/coverage remain physical-phone gates; no hardware claim is treated as complete until a captured trace is replayed successfully.
