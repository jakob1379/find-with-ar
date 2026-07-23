# Synthetic precision experiments

These experiments are deterministic regression tests, not a substitute for labelled room captures. The simulator deliberately differs from the estimator by adding quantized RSSI, correlated bias, body shadowing, Gaussian noise and occasional large outliers.

## Reproduce

```bash
nix develop -c ./gradlew --no-daemon \
  :localization:test \
  --tests dev.arsignallocator.localization.PrecisionSimulationTest \
  --info
```

`PrecisionSimulationTest` uses 12 seed-mixed target, radio-model, height, path-rotation and translation scenarios. Every path has 24 spatial positions. The moderate and severe runs use three readings per position; the sparse run uses one to approximate the lower observation rate of Bluetooth Classic inquiry.

## Results

Results on 23 July 2026:

| Environment | Point emitted | Point median / p90 error | MAP median / p90, all runs | Strongest-RSSI median, all runs | 90% area median / p90 | 90% coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Moderate noise/outliers | 2 / 12 | 0.31 m / 0.31 m | 0.31 m / 2.12 m | 1.72 m | 14.0 m² / 18.5 m² | 12 / 12 |
| Severe correlated multipath/outliers | 1 / 12 | 2.20 m / 2.20 m | 0.46 m / 0.80 m | 2.14 m | 14.9 m² / 22.1 m² | 12 / 12 |
| Sparse moderate observations | 1 / 12 | 0.19 m / 0.19 m | 0.51 m / 1.77 m | 1.63 m | 12.9 m² / 21.6 m² | 12 / 12 |

Point statistics are conditional on the estimator emitting a point. MAP is a diagnostic grid maximum and is not displayed as a location when confidence is insufficient. Most runs intentionally remain `COLLECTING` and expose the full 90% probability region instead. The region-size gate in the regression test prevents full-grid coverage from passing as a useful result.

A severe-noise sweep checked whether increasing the maximum “located” region area improved usefulness:

| Maximum 90% area | Point emitted | Point median / p90 error | Strongest-RSSI median on emitted cases |
| ---: | ---: | ---: | ---: |
| 8 m² | 1 / 12 | 2.20 m / 2.20 m | 3.33 m |
| 12 m² | 2 / 12 | 2.45 m / 2.45 m | 3.33 m |
| 16 m² | 8 / 12 | 2.18 m / 2.41 m | 2.14 m |

The 8 m² gate remains the default. Relaxing it to 16 m² produced many more points but made their median slightly worse than the strongest-RSSI baseline.

## Changes driven by the experiments

- Derive phone-forward direction from the ARCore quaternion and model bounded body-shadow attenuation.
- Use broader transmit-power, path-loss and target-height hypotheses with a robust Student-t likelihood.
- Avoid adding model variance twice.
- Preserve the grid's 90% region during EKF refinement and discard EKF state whenever the grid is no longer safely unimodal.
- Withhold a point when the 90% region exceeds 8 m²; continue displaying the probability region.
- Accept up to three colocated raw readings, then robustly combine them into one likelihood term so packet rate does not masquerade as independent geometry.

The 100% region coverage above is encouraging but statistically small and simulator-derived. Release claims still require the labelled open/furnished-room protocol in [`validation-protocol.md`](validation-protocol.md), including held-out devices and a discoverable Classic target.
