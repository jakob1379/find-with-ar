# MVP validation protocol

This protocol separates checks that can be automated from the radio and camera evidence that requires a physical phone.

## 1. Fresh development environment

On an x86-64 Linux laptop with Nix and flakes enabled:

```bash
git clone <repository-url>
cd ar-signal-locator
nix develop
just check
adb devices
just install
```

Record the NixOS/Linux version, phone model, Android version and the commit under test. The gate passes only if the checked-in lock files are unchanged after the build.

## 2. BLE and AR concurrency spike

Use an ARCore-supported phone and a controllable beacon advertising at least once per second.

1. Grant camera, nearby-device and precise-location permissions.
2. Select the beacon while it is visibly advertising.
3. Start a capture and walk a varied path for at least 30 seconds and 3 metres.
4. Confirm AR tracking remains `TRACKING`, then export the NDJSON trace.
5. Repeat with each headphone or consumer device of interest, both outside and inside its charging case.

Pass criteria:

- repeated advertisements continue while AR tracking is active;
- at least 95% of accepted BLE samples have a sufficiently recent valid pose;
- signal loss produces an explicit state rather than a stale confident estimate;
- no stable hardware address appears in the exported trace.

## 3. Deterministic replay

```bash
uv run --project analysis ar-signal-replay capture.ndjson --output estimator-inputs-a.ndjson
uv run --project analysis ar-signal-replay capture.ndjson --output estimator-inputs-b.ndjson
cmp estimator-inputs-a.ndjson estimator-inputs-b.ndjson
uv run --project analysis ar-signal-analyse capture.ndjson --format json --output report.json
```

Replay the same capture twice. Ordered estimator inputs and the generated report must be byte-for-byte identical. Archive the capture checksum with the test notes.

## 4. Labelled localization trials

Mark the beacon's ground-truth `x/z` coordinate and collect at least three distinct paths for every condition:

- open room and furnished room;
- floor, table and shelf target heights;
- line of sight, behind furniture and body occlusion;
- different phone orientations and, when available, a second handset.

Hold out complete positions or rooms from tuning. Compare the final estimate against the strongest-RSSI observation from the same trace.

Initial go/no-go targets after about 20–30 seconds are:

| Measure | Target |
| --- | --- |
| Open-room median horizontal error | at most 1.5 m |
| Furnished-room median horizontal error | at most 2.5 m |
| Held-out 90th-percentile error | at most 3 m |
| Nominal 80% region empirical coverage | 70–90% |
| Time to first useful region | under 15 s at 1 advertisement/s |

The hybrid estimator must improve on strongest RSSI and must not report high confidence for an uninformative path. If it does not clear that gate, stop feature work and recalibrate the observation model before presenting the AR region as a location.

## 5. Hardening checks

- Deny each permission and verify a recoverable explanation.
- Toggle Bluetooth during a session.
- Cover the camera or force AR tracking loss.
- Start and stop ten sessions without restarting the app.
- Run continuously for ten minutes while recording thermal and battery observations.
- Export a trace, delete local session data and verify that it no longer appears in the app.

Physical results should be appended to the implementation plan as dated evidence; code completion alone does not close these gates.
