package dev.arsignallocator.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BayesianGridLocalizerTest {
    private val exactConfig = GridConfig(
        minX = -3.0,
        maxX = 3.0,
        minZ = -3.0,
        maxZ = 3.0,
        resolutionMetres = 0.2,
        referenceRssiHypothesesDbm = listOf(-55.0),
        pathLossExponentHypotheses = listOf(2.2),
        targetHeightHypothesesMetres = listOf(0.1),
        modelSigmaDb = 1.0,
        spatialBucketSizeMetres = 0.2,
        minSpatialBucketsForInitialization = 5,
        minPositionBaselineMetres = 1.0,
        minMinorAxisSpreadMetres = 0.1,
    )

    @Test
    fun `duplicate stationary packets do not create false confidence`() {
        val localizer = BayesianGridLocalizer(exactConfig)
        val sample = SpatialRssiSample(
            phonePosition = Vec2(-1.4, 0.3),
            phoneHeightMetres = 1.1,
            rssiDbm = -61.0,
            measurementVarianceDb2 = 1.0,
        )
        val once = localizer.update(sample)
        val posteriorOnce = localizer.posteriorCells().map(GridCell::probability)
        repeat(100) { localizer.update(sample) }
        val repeated = localizer.estimate()
        val posteriorRepeated = localizer.posteriorCells().map(GridCell::probability)

        assertEquals(once.area50SquareMetres!!, repeated.area50SquareMetres!!, 0.0)
        assertEquals(once.area90SquareMetres!!, repeated.area90SquareMetres!!, 0.0)
        assertEquals(posteriorOnce, posteriorRepeated)
        assertEquals(1, repeated.dataQuality.independentSpatialBuckets)
        assertEquals(101, repeated.dataQuality.samplesReceived)
    }

    @Test
    fun `seeded simulation and replay are deterministic`() {
        val target = Vec2(0.8, -0.6)
        val path = informativePath()
        val firstTrace = SyntheticRssiSimulator(1234L, simulatorConfig()).trace(target, path)
        val secondTrace = SyntheticRssiSimulator(1234L, simulatorConfig()).trace(target, path)
        assertEquals(firstTrace, secondTrace)

        val first = BayesianGridLocalizer(exactConfig)
        val second = BayesianGridLocalizer(exactConfig)
        firstTrace.forEach(first::update)
        secondTrace.forEach(second::update)
        assertEquals(first.estimate(), second.estimate())
        assertEquals(first.posteriorCells(), second.posteriorCells())
    }

    @Test
    fun `posterior converges on an informative two dimensional path`() {
        val target = Vec2(0.8, -0.6)
        val simulator = SyntheticRssiSimulator(77L, simulatorConfig())
        val localizer = BayesianGridLocalizer(exactConfig)
        val trace = simulator.trace(target, informativePath())
        trace.take(2).forEach(localizer::update)
        val earlyArea = localizer.estimate().area90SquareMetres!!
        trace.drop(2).forEach(localizer::update)
        val estimate = localizer.estimate()

        assertTrue("MAP was ${estimate.mapXZ}", estimate.mapXZ!!.distanceTo(target) < 0.5)
        assertTrue(estimate.area90SquareMetres!! < earlyArea)
        assertTrue(estimate.dataQuality.hasInformativeGeometry)
        assertNotNull(estimate.meanXZ)
        assertNotNull(localizer.ekfInitializationOrNull())
    }

    @Test
    fun `hybrid does not double count grid samples after EKF initialization`() {
        val hybrid = HybridGridEkfLocalizer(BayesianGridLocalizer(exactConfig))
        val trace = SyntheticRssiSimulator(91L, simulatorConfig())
            .trace(Vec2(0.8, -0.6), informativePath())
        trace.forEach(hybrid::update)
        val initialized = hybrid.estimate()
        assertEquals(EstimateState.EKF, initialized.state)

        repeat(50) { hybrid.update(trace.last()) }
        val repeated = hybrid.estimate()
        assertEquals(initialized.area90SquareMetres!!, repeated.area90SquareMetres!!, 0.0)
        assertEquals(initialized.covarianceXZ, repeated.covarianceXZ)
    }

    @Test
    fun `HPD regions are nested and show expected truth coverage`() {
        var coveredBy90 = 0
        val targets = listOf(
            Vec2(-1.0, -0.8),
            Vec2(-0.5, 0.7),
            Vec2(0.2, -0.3),
            Vec2(0.8, 0.9),
            Vec2(1.2, -1.1),
            Vec2(1.5, 0.1),
        )
        for ((index, target) in targets.withIndex()) {
            val localizer = BayesianGridLocalizer(exactConfig)
            SyntheticRssiSimulator(index + 800L, simulatorConfig())
                .trace(target, informativePath())
                .forEach(localizer::update)
            val hpd50 = localizer.estimate().hpd50!!
            val hpd90 = localizer.estimate().hpd90!!
            val ninetyCells = hpd90.cells.map { it.indexX to it.indexZ }.toSet()

            assertTrue(hpd50.enclosedProbability >= 0.50)
            assertTrue(hpd90.enclosedProbability >= 0.90)
            assertTrue(hpd50.areaSquareMetres <= hpd90.areaSquareMetres)
            assertTrue(hpd50.cells.all { (it.indexX to it.indexZ) in ninetyCells })
            if (hpd90.contains(target)) coveredBy90 += 1
        }
        // Deterministic smoke test for severe under-coverage, not a calibration claim.
        assertTrue("90% HPD covered $coveredBy90/${targets.size}", coveredBy90 >= 5)
    }

    @Test
    fun `hybrid beats strongest RSSI median error across seeded scenarios`() {
        val targets = listOf(
            Vec2(-1.0, -0.8),
            Vec2(-0.5, 0.7),
            Vec2(0.2, -0.3),
            Vec2(0.8, 0.9),
            Vec2(1.2, -1.1),
            Vec2(1.5, 0.1),
        )
        val baselineErrors = mutableListOf<Double>()
        val hybridErrors = mutableListOf<Double>()
        targets.forEachIndexed { index, target ->
            val trace = SyntheticRssiSimulator(
                seed = 900L + index,
                config = simulatorConfig().copy(
                    gaussianNoiseSigmaDb = 1.5,
                    outlierProbability = 0.05,
                    outlierSigmaDb = 8.0,
                    reportedMeasurementVarianceDb2 = 2.25,
                ),
            ).trace(target, informativePath())
            baselineErrors += trace.maxBy(SpatialRssiSample::rssiDbm)
                .phonePosition.distanceTo(target)

            val localizer = HybridGridEkfLocalizer(BayesianGridLocalizer(exactConfig))
            trace.forEach(localizer::update)
            val estimate = localizer.estimate().meanXZ ?: localizer.estimate().mapXZ!!
            hybridErrors += estimate.distanceTo(target)
        }

        assertTrue(
            "hybrid median ${median(hybridErrors)} vs baseline ${median(baselineErrors)}",
            median(hybridErrors) < median(baselineErrors),
        )
    }

    @Test
    fun `consecutive inconsistent updates reset to broad acquisition`() {
        val hybrid = HybridGridEkfLocalizer(
            grid = BayesianGridLocalizer(exactConfig),
            hybridConfig = HybridConfig(maxConsecutiveEkfRejections = 2),
        )
        SyntheticRssiSimulator(440L, simulatorConfig())
            .trace(Vec2(0.8, -0.6), informativePath())
            .forEach(hybrid::update)
        assertEquals(EstimateState.EKF, hybrid.estimate().state)

        hybrid.update(
            SpatialRssiSample(Vec2(2.8, 2.8), 1.1, rssiDbm = -5.0),
        )
        val reset = hybrid.update(
            SpatialRssiSample(Vec2(-2.8, 2.8), 1.1, rssiDbm = -5.0),
        )

        assertEquals(EstimateState.COLLECTING, reset.state)
        assertEquals(1, reset.dataQuality.independentSpatialBuckets)
        assertEquals(null, reset.meanXZ)
        assertEquals(null, reset.covarianceXZ)
    }

    private fun simulatorConfig() = SyntheticRssiConfig(
        referenceRssiDbm = -55.0,
        pathLossExponent = 2.2,
        targetHeightMetres = 0.1,
        phoneHeightMetres = 1.1,
        gaussianNoiseSigmaDb = 0.7,
        reportedMeasurementVarianceDb2 = 1.0,
    )

    private fun informativePath(): List<Vec2> = listOf(
        Vec2(-2.2, -1.8),
        Vec2(-2.0, -0.6),
        Vec2(-1.8, 0.8),
        Vec2(-1.0, 1.8),
        Vec2(0.2, 2.2),
        Vec2(1.4, 1.9),
        Vec2(2.2, 1.0),
        Vec2(2.4, -0.2),
        Vec2(2.1, -1.4),
        Vec2(1.0, -2.1),
        Vec2(-0.3, -2.3),
        Vec2(-1.5, -2.1),
    )

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
}
