package dev.arsignallocator.localization

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationGenerativeTest {
    @Test
    fun `generated rigid room transforms keep the target inside the posterior`() {
        val random = Random(20260723L)
        repeat(24) { scenario ->
            val angle = random.nextDouble() * 2.0 * Math.PI
            val translation = Vec2(
                x = random.nextDouble() * 1.5 - 0.75,
                z = random.nextDouble() * 1.5 - 0.75,
            )
            val target = transform(Vec2(0.7, -0.5), angle, translation)
            val path = basePath().map { transform(it, angle, translation) }
            val localizer = BayesianGridLocalizer(exactConfig)
            SyntheticRssiSimulator(
                seed = scenario.toLong(),
                config = SyntheticRssiConfig(
                    referenceRssiDbm = -55.0,
                    pathLossExponent = 2.2,
                    targetHeightM = 0.1,
                    phoneHeightM = 1.1,
                    gaussianNoiseSigmaDb = 0.0,
                    reportedMeasurementVarianceDb2 = 1.0,
                ),
            ).trace(target, path).forEach(localizer::update)

            val estimate = localizer.estimate()
            assertTrue(
                "scenario $scenario missed $target with MAP ${estimate.mapXZ}",
                estimate.hpd90!!.contains(target),
            )
            assertTrue(
                "scenario $scenario MAP ${estimate.mapXZ}",
                estimate.mapXZ!!.distanceTo(target) <= 0.35,
            )
        }
    }

    private fun transform(point: Vec2, angle: Double, translation: Vec2): Vec2 = Vec2(
        x = translation.x + cos(angle) * point.x - sin(angle) * point.z,
        z = translation.z + sin(angle) * point.x + cos(angle) * point.z,
    )

    private fun basePath(): List<Vec2> = listOf(
        Vec2(-2.7, -1.8),
        Vec2(-2.4, -0.7),
        Vec2(-2.1, 0.5),
        Vec2(-1.5, 1.6),
        Vec2(-0.5, 2.3),
        Vec2(0.7, 2.5),
        Vec2(1.9, 2.0),
        Vec2(2.7, 1.0),
        Vec2(2.9, -0.2),
        Vec2(2.4, -1.4),
        Vec2(1.4, -2.3),
        Vec2(0.1, -2.6),
        Vec2(-1.2, -2.4),
    )

    private companion object {
        val exactConfig = GridConfig(
            minX = -5.0,
            maxX = 5.0,
            minZ = -5.0,
            maxZ = 5.0,
            resolutionM = 0.2,
            referenceRssiHypothesesDbm = listOf(-55.0),
            pathLossExponentHypotheses = listOf(2.2),
            targetHeightHypothesesM = listOf(0.1),
            modelSigmaDb = 1.0,
            spatialBucketSizeM = 0.2,
            minSpatialBucketsForInitialization = 5,
            minPositionBaselineM = 1.0,
            minMinorAxisSpreadM = 0.1,
        )
    }
}
