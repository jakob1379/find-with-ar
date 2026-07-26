package dev.arsignallocator.localization

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EkfLocalizerTest {
    @Test
    fun `Joseph updates retain symmetric positive semidefinite covariance`() {
        val initialization = EkfInitialization(
            state = doubleArrayOf(0.8, -0.5, -55.0, 2.2),
            covariance = arrayOf(
                doubleArrayOf(1.2, 0.15, 0.0, 0.0),
                doubleArrayOf(0.15, 1.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 16.0, 0.3),
                doubleArrayOf(0.0, 0.0, 0.3, 0.4),
            ),
            targetHeightMetres = 0.1,
        )
        val filter = EkfLocalizer(initialization)
        val simulator = SyntheticRssiSimulator(
            seed = 22L,
            config = SyntheticRssiConfig(outlierProbability = 0.15),
        )
        val target = Vec2(1.0, -0.8)
        val path = circularPath(radius = 2.5, count = 36)
        simulator.trace(target, path).forEach(filter::update)

        val covariance = filter.fullCovariance()
        for (row in covariance.indices) {
            assertTrue(covariance[row][row] >= 0.0)
            for (column in covariance.indices) {
                assertEquals(covariance[row][column], covariance[column][row], 1e-9)
            }
        }
        val random = Random(9L)
        repeat(100) {
            val vector = DoubleArray(4) { random.nextGaussian() }
            val quadratic = vector.indices.sumOf { row ->
                vector.indices.sumOf { column ->
                    vector[row] * covariance[row][column] * vector[column]
                }
            }
            assertTrue("quadratic form $quadratic", quadratic >= -1e-8)
        }
    }

    @Test
    fun `NIS gate rejects a gross outlier`() {
        val filter = EkfLocalizer(
            EkfInitialization(
                state = doubleArrayOf(0.0, 0.0, -55.0, 2.2),
                covariance = Array(4) { row ->
                    DoubleArray(4) { column -> if (row == column) 0.5 else 0.0 }
                },
                targetHeightMetres = 0.1,
            ),
        )
        val estimate = filter.update(
            SpatialRssiSample(Vec2(2.0, 1.0), 1.1, rssiDbm = -5.0),
        )
        assertEquals(1, estimate.diagnostics.rejectedUpdates)
        assertEquals(0, estimate.diagnostics.acceptedUpdates)
    }

    private fun circularPath(radius: Double, count: Int): List<Vec2> =
        List(count) { index ->
            val angle = 2.0 * Math.PI * index / count
            Vec2(radius * kotlin.math.cos(angle), radius * kotlin.math.sin(angle))
        }
}
