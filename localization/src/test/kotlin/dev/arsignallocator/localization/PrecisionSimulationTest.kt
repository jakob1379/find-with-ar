package dev.arsignallocator.localization

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionSimulationTest {
    @Test
    fun `default estimator reports a point only when stress simulations support it`() {
        verify(
            "moderate",
            Environment(
                correlatedBiasSigmaDb = 0.4,
                noiseSigmaDb = 2.0,
                outlierProbability = 0.02,
                outlierSigmaDb = 10.0,
            ),
            maximumMedianErrorM = 1.5,
        )
        verify("severe", Environment(), maximumMedianErrorM = 2.5)
    }

    @Test
    fun `sparse observations keep an honest region for classic discovery`() {
        verify(
            "sparse",
            Environment(
                correlatedBiasSigmaDb = 0.4,
                noiseSigmaDb = 2.0,
                outlierProbability = 0.02,
                outlierSigmaDb = 10.0,
            ),
            samplesPerPosition = 1,
            maximumMedianErrorM = 1.5,
        )
    }

    private fun verify(
        name: String,
        environment: Environment,
        samplesPerPosition: Int = SAMPLES_PER_POSITION,
        maximumMedianErrorM: Double,
    ) {
        val results = SCENARIO_SEEDS.map {
            simulate(it, HybridGridEkfLocalizer(), environment, samplesPerPosition)
        }
        val located = results.filter { it.errorM != null }
        val summary = "$name: ${summarize(results)}"
        val locatedErrors = located.map { requireNotNull(it.errorM) }
        val baselineMedian = percentile(located.map(ScenarioResult::baselineErrorM).sorted(), 0.50)
        val allScenarioBaselineMedian = percentile(
            results.map(ScenarioResult::baselineErrorM).sorted(),
            0.50,
        )
        val mapMedianError = percentile(results.map(ScenarioResult::mapErrorM).sorted(), 0.50)
        val medianError = percentile(locatedErrors.sorted(), 0.50)
        val p90Error = percentile(locatedErrors.sorted(), 0.90)
        val mapP90Error = percentile(results.map(ScenarioResult::mapErrorM).sorted(), 0.90)
        val regionP90Area = percentile(results.map(ScenarioResult::regionAreaM2).sorted(), 0.90)

        println(summary)
        assertTrue(summary, located.isNotEmpty())
        assertTrue(summary, medianError <= maximumMedianErrorM)
        assertTrue(summary, p90Error <= 3.0)
        assertTrue(summary, mapMedianError < allScenarioBaselineMedian)
        assertTrue(summary, mapP90Error <= 3.0)
        assertTrue(summary, regionP90Area <= 35.0)
        assertTrue(summary, medianError < baselineMedian)
        assertTrue(summary, results.count(ScenarioResult::covered90).toDouble() / results.size >= 0.80)
        assertTrue(summary, results.any { it.errorM == null })
    }

    private fun summarize(results: List<ScenarioResult>): String {
        val located = results.filter { it.errorM != null }
        val errors = located.map { requireNotNull(it.errorM) }.sorted()
        val baselineErrors = results.map(ScenarioResult::baselineErrorM).sorted()
        val locatedBaselineErrors = located.map(ScenarioResult::baselineErrorM).sorted()
        val medianError = percentile(errors, 0.50)
        val p90Error = percentile(errors, 0.90)
        val medianBaseline = percentile(baselineErrors, 0.50)
        val medianLocatedBaseline = percentile(locatedBaselineErrors, 0.50)
        val mapErrors = results.map(ScenarioResult::mapErrorM).sorted()
        val regionAreas = results.map(ScenarioResult::regionAreaM2).sorted()
        val coverage = results.count(ScenarioResult::covered90).toDouble() / results.size
        val worst = located.sortedByDescending { it.errorM }.take(5).joinToString {
            "${it.seed}=${"%.2f".format(it.errorM)} (${it.detail})"
        }
        return "located=${located.size}/${results.size}, " +
            "median=${"%.2f".format(medianError)}m, p90=${"%.2f".format(p90Error)}m, " +
            "baseline(all)=${"%.2f".format(medianBaseline)}m, " +
            "baseline(located)=${"%.2f".format(medianLocatedBaseline)}m, " +
            "map=${"%.2f".format(percentile(mapErrors, 0.50))}/" +
            "${"%.2f".format(percentile(mapErrors, 0.90))}m median/p90, " +
            "area90=${"%.1f".format(percentile(regionAreas, 0.50))}/" +
            "${"%.1f".format(percentile(regionAreas, 0.90))}m2 median/p90, " +
            "coverage=${"%.0f".format(coverage * 100)}%, " +
            "worst=[$worst]"
    }

    private fun simulate(
        seed: Int,
        localizer: Localizer,
        environment: Environment,
        samplesPerPosition: Int,
    ): ScenarioResult {
        val random = Random(20260723L + seed.toLong() * 1_000_003L)
        val target = Vec2(
            x = random.nextDouble() * 3.0 - 1.5,
            z = random.nextDouble() * 3.0 - 1.5,
        )
        val rotation = random.nextDouble() * 2.0 * PI
        val translation = Vec2(
            x = random.nextDouble() - 0.5,
            z = random.nextDouble() - 0.5,
        )
        val path = basePath().map { transform(it, rotation, translation) }
        val referenceRssi = -64.0 + random.nextDouble() * 18.0
        val pathLossExponent = 1.4 + random.nextDouble() * 2.2
        val targetHeight = 0.05 + random.nextDouble() * 1.15
        val phoneHeight = 1.2
        var correlatedBias = 0.0
        var strongestRssi = Double.NEGATIVE_INFINITY
        var strongestPosition = path.first()

        path.forEachIndexed { index, phone ->
            val next = path[(index + 1) % path.size]
            val headingX = next.x - phone.x
            val headingZ = next.z - phone.z
            val targetX = target.x - phone.x
            val targetZ = target.z - phone.z
            val headingLength = max(1e-9, sqrt(headingX * headingX + headingZ * headingZ))
            val targetLength = max(1e-9, sqrt(targetX * targetX + targetZ * targetZ))
            val facingCosine = (headingX * targetX + headingZ * targetZ) /
                (headingLength * targetLength)
            val bodyShadowDb = -environment.bodyShadowDb * max(0.0, -facingCosine)
            val heightDelta = targetHeight - phoneHeight
            val distance = sqrt(target.squaredDistanceTo(phone) + heightDelta * heightDelta)
            repeat(samplesPerPosition) { repetition ->
                correlatedBias = 0.82 * correlatedBias +
                    random.nextGaussian() * environment.correlatedBiasSigmaDb
                val outlier = if (random.nextDouble() < environment.outlierProbability) {
                    random.nextGaussian() * environment.outlierSigmaDb
                } else {
                    0.0
                }
                val rssi = (
                    referenceRssi -
                        10.0 * pathLossExponent * log10(max(0.15, distance)) +
                        bodyShadowDb +
                        correlatedBias +
                        random.nextGaussian() * environment.noiseSigmaDb +
                        outlier
                    ).roundToInt().toDouble()
                if (rssi > strongestRssi) {
                    strongestRssi = rssi
                    strongestPosition = phone
                }
                localizer.update(
                    SpatialRssiSample(
                        phonePosition = phone,
                        phoneHeightM = phoneHeight,
                        rssiDbm = rssi,
                        measurementVarianceDb2 = 9.0,
                        elapsedRealtimeNanos = (
                            index * samplesPerPosition + repetition
                            ) * SAMPLE_INTERVAL_NANOS,
                        phoneForward = Vec2(headingX / headingLength, headingZ / headingLength),
                    ),
                )
            }
        }

        val estimate = localizer.estimate()
        return ScenarioResult(
            seed = seed,
            errorM = estimate.meanXZ?.distanceTo(target),
            mapErrorM = requireNotNull(estimate.mapXZ).distanceTo(target),
            regionAreaM2 = requireNotNull(estimate.area90SquareMetres),
            baselineErrorM = strongestPosition.distanceTo(target),
            covered90 = estimate.contains90(target),
            detail = "A=${"%.1f".format(referenceRssi)}, n=${"%.2f".format(pathLossExponent)}, " +
                "h=${"%.2f".format(targetHeight)}, target=$target, mean=${estimate.meanXZ}, " +
                "map=${estimate.mapXZ}, centroid=${estimate.modes.firstOrNull()?.centroid}, " +
                "dominant=${"%.2f".format(estimate.modes.firstOrNull()?.fractionOfHpdMass)}, " +
                "state=${estimate.state}, area90=${"%.1f".format(estimate.area90SquareMetres)}, " +
                "diameter90=${"%.1f".format(estimate.hpd90?.boundingDiameter())}",
        )
    }

    private fun HpdRegion.boundingDiameter(): Double {
        val spanX = cells.maxOf { it.center.x } - cells.minOf { it.center.x } + cellResolutionM
        val spanZ = cells.maxOf { it.center.z } - cells.minOf { it.center.z } + cellResolutionM
        return sqrt(spanX * spanX + spanZ * spanZ)
    }

    private fun LocationEstimate.contains90(target: Vec2): Boolean {
        hpd90?.let { return it.contains(target) }
        val mean = meanXZ ?: return false
        val covariance = covarianceXZ ?: return false
        val determinant = covariance.determinant
        if (determinant <= 0.0) return false
        val delta = target - mean
        val mahalanobisSquared = (
            covariance.m11 * delta.x * delta.x -
                2.0 * covariance.m01 * delta.x * delta.z +
                covariance.m00 * delta.z * delta.z
            ) / determinant
        return mahalanobisSquared <= -2.0 * ln(0.1)
    }

    private fun basePath(): List<Vec2> = List(24) { index ->
        val angle = 2.0 * PI * index / 24.0
        Vec2(3.1 * cos(angle), 2.6 * sin(angle) + 0.35 * sin(2.0 * angle))
    }

    private fun transform(point: Vec2, angle: Double, translation: Vec2): Vec2 = Vec2(
        x = translation.x + cos(angle) * point.x - sin(angle) * point.z,
        z = translation.z + sin(angle) * point.x + cos(angle) * point.z,
    )

    private fun percentile(sorted: List<Double>, probability: Double): Double {
        require(sorted.isNotEmpty())
        return sorted[((sorted.size - 1) * probability).roundToInt()]
    }

    private data class ScenarioResult(
        val seed: Int,
        val errorM: Double?,
        val mapErrorM: Double,
        val regionAreaM2: Double,
        val baselineErrorM: Double,
        val covered90: Boolean,
        val detail: String,
    )

    private data class Environment(
        val bodyShadowDb: Double = 7.0,
        val correlatedBiasSigmaDb: Double = 1.1,
        val noiseSigmaDb: Double = 2.5,
        val outlierProbability: Double = 0.05,
        val outlierSigmaDb: Double = 12.0,
    )

    private companion object {
        val SCENARIO_SEEDS = (0..10).toList() + 22
        const val SAMPLES_PER_POSITION = 3
        const val SAMPLE_INTERVAL_NANOS = 333_000_000L
    }
}
