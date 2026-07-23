# AR Signal Locator analysis

Offline tools for the versioned NDJSON traces produced by the Android app. The
package deliberately keeps the trace contract small and rejects malformed data
instead of silently calculating metrics from ambiguous fields.

```bash
uv sync --locked --project analysis
uv run --project analysis ar-signal-simulate /tmp/example.ndjson --seed 7
uv run --project analysis ar-signal-analyse /tmp/example.ndjson
uv run --project analysis ar-signal-analyse /tmp/example.ndjson --format json
uv run --project analysis ar-signal-replay /tmp/example.ndjson --output /tmp/inputs.ndjson
```

Each trace starts with one `session` record followed by ordered `observation`
records. Schema version 1 is documented by the dataclasses and validators in
`src/ar_signal_analysis/schema.py`. Addresses are not required or emitted by the
simulator.

`ar-signal-replay` emits canonical ordered estimator inputs. Running it twice on
the same capture must produce byte-identical output; it prefers the Android
recorder's filtered RSSI/variance fields and uses documented fallbacks for older
or synthetic version-1 traces.
