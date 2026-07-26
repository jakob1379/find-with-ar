package dev.arsignallocator.localization

import java.util.Random
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class SyntheticRssiConfig(
    val referenceRssiDbm: Double = -55.0,
    val pathLossExponent: Double = 2.2,
    val targetHeightMetres: Double = 0.05,
    val phoneHeightMetres: Double = 1.1,
    val gaussianNoiseSigmaDb: Double = 2.0,
    val outlierProbability: Double = 0.0,
    val outlierSigmaDb: Double = 12.0,
    val minDistanceMetres: Double = 0.15,
    val reportedMeasurementVarianceDb2: Double = 4.0,
) {
    init {
        require(pathLossExponent > 0.0)
        require(gaussianNoiseSigmaDb >= 0.0)
        require(outlierProbability in 0.0..1.0)
        require(outlierSigmaDb >= 0.0)
        require(minDistanceMetres > 0.0)
        require(reportedMeasurementVarianceDb2 > 0.0)
    }
}

/** Seeded synthetic trace generator for replayable estimator tests and experiments. */
class SyntheticRssiSimulator(
    seed: Long,
    val config: SyntheticRssiConfig = SyntheticRssiConfig(),
) {
    private val random = Random(seed)

    fun sample(
        targetPosition: Vec2,
        phonePosition: Vec2,
        elapsedRealtimeNanos: Long = 0L,
    ): SpatialRssiSample {
        val heightDelta = config.targetHeightMetres - config.phoneHeightMetres
        val distance = max(
            config.minDistanceMetres,
            sqrt(targetPosition.squaredDistanceTo(phonePosition) + heightDelta * heightDelta),
        )
        val expected = config.referenceRssiDbm -
            10.0 * config.pathLossExponent * log10(distance)
        val ordinaryNoise = random.nextGaussian() * config.gaussianNoiseSigmaDb
        val outlierNoise = if (random.nextDouble() < config.outlierProbability) {
            random.nextGaussian() * config.outlierSigmaDb
        } else {
            0.0
        }
        return SpatialRssiSample(
            phonePosition = phonePosition,
            phoneHeightMetres = config.phoneHeightMetres,
            rssiDbm = expected + ordinaryNoise + outlierNoise,
            measurementVarianceDb2 = config.reportedMeasurementVarianceDb2,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    fun trace(targetPosition: Vec2, phonePath: List<Vec2>): List<SpatialRssiSample> =
        phonePath.mapIndexed { index, phonePosition ->
            sample(targetPosition, phonePosition, index * 100_000_000L)
        }
}
