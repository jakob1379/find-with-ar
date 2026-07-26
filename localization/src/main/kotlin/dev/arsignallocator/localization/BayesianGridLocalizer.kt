package dev.arsignallocator.localization

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GridConfig(
    val minX: Double = -5.0,
    val maxX: Double = 5.0,
    val minZ: Double = -5.0,
    val maxZ: Double = 5.0,
    val resolutionMetres: Double = 0.30,
    val referenceRssiHypothesesDbm: List<Double> = listOf(-66.0, -58.0, -50.0, -42.0),
    val pathLossExponentHypotheses: List<Double> = listOf(1.4, 2.2, 3.0, 3.8),
    val targetHeightHypothesesMetres: List<Double> = listOf(0.05, 0.6, 1.15),
    val modelSigmaDb: Double = 4.0,
    val minimumMeasurementVarianceDb2: Double = 1.0,
    val studentTDegreesOfFreedom: Double = 3.0,
    val minDistanceMetres: Double = 0.15,
    val spatialBucketSizeMetres: Double = 0.35,
    val dominantModeFraction: Double = 0.80,
    val minSpatialBucketsForInitialization: Int = 5,
    val minPositionBaselineMetres: Double = 1.25,
    val minMinorAxisSpreadMetres: Double = 0.12,
    val bodyShadowAttenuationDb: Double = 6.0,
    val maxLocatedAreaSquareMetres: Double = 8.0,
) {
    init {
        require(minX < maxX && minZ < maxZ)
        require(resolutionMetres > 0.0)
        require(referenceRssiHypothesesDbm.isNotEmpty() && referenceRssiHypothesesDbm.all { it.isFinite() })
        require(pathLossExponentHypotheses.isNotEmpty() && pathLossExponentHypotheses.all { it > 0.0 })
        require(targetHeightHypothesesMetres.isNotEmpty() && targetHeightHypothesesMetres.all { it.isFinite() })
        require(modelSigmaDb > 0.0)
        require(minimumMeasurementVarianceDb2 > 0.0)
        require(studentTDegreesOfFreedom > 0.0)
        require(minDistanceMetres > 0.0)
        require(spatialBucketSizeMetres > 0.0)
        require(dominantModeFraction in 0.5..1.0)
        require(minSpatialBucketsForInitialization >= 2)
        require(minPositionBaselineMetres >= 0.0 && minMinorAxisSpreadMetres >= 0.0)
        require(bodyShadowAttenuationDb >= 0.0)
        require(maxLocatedAreaSquareMetres > 0.0)
    }
}

data class RadioHypothesis(
    val referenceRssiDbm: Double,
    val pathLossExponent: Double,
    val targetHeightMetres: Double,
)

/** State used to initialize the optional [EkfLocalizer]. */
data class EkfInitialization(
    /** x, z, reference RSSI A, path-loss exponent n. */
    val state: DoubleArray,
    val covariance: Array<DoubleArray>,
    val targetHeightMetres: Double,
    /** Buckets already represented by the grid posterior; EKF must not count them twice. */
    val observedPhonePositions: List<Vec2> = emptyList(),
    val bodyShadowAttenuationDb: Double = 0.0,
) {
    init {
        require(state.size == 4)
        require(covariance.size == 4 && covariance.all { it.size == 4 })
    }
}

/**
 * Deterministic recursive Bayesian localizer over position and shared radio hypotheses.
 *
 * A/n/height hypotheses are shared across the complete session. They are therefore
 * dimensions of the joint posterior rather than being independently marginalized
 * for each packet.
 */
class BayesianGridLocalizer(
    val config: GridConfig = GridConfig(),
) : Localizer {
    private val width = floor((config.maxX - config.minX) / config.resolutionMetres).toInt() + 1
    private val depth = floor((config.maxZ - config.minZ) / config.resolutionMetres).toInt() + 1
    private val cellCount = width * depth
    private val hypotheses: List<RadioHypothesis> = buildList {
        for (referenceRssi in config.referenceRssiHypothesesDbm) {
            for (pathLoss in config.pathLossExponentHypotheses) {
                for (height in config.targetHeightHypothesesMetres) {
                    add(RadioHypothesis(referenceRssi, pathLoss, height))
                }
            }
        }
    }
    private val aggregator = SpatialRssiAggregator(
        bucketSizeMetres = config.spatialBucketSizeMetres,
        minVarianceDb2 = config.minimumMeasurementVarianceDb2,
    )

    private var latest = emptyEstimate()
    private var latestPosterior = DoubleArray(cellCount)
    private var latestJointPosterior = DoubleArray(cellCount * hypotheses.size)
    private var latestInitialization: EkfInitialization? = null

    @Synchronized
    override fun update(sample: SpatialRssiSample): LocationEstimate {
        aggregator.add(sample)
        recompute()
        return latest
    }

    @Synchronized
    override fun estimate(): LocationEstimate = latest

    @Synchronized
    override fun reset() {
        aggregator.clear()
        latest = emptyEstimate()
        latestPosterior = DoubleArray(cellCount)
        latestJointPosterior = DoubleArray(cellCount * hypotheses.size)
        latestInitialization = null
    }

    @Synchronized
    fun posteriorCells(): List<GridCell> = latestPosterior.mapIndexed { index, probability ->
        cell(index, probability)
    }

    @Synchronized
    fun ekfInitializationOrNull(): EkfInitialization? = latestInitialization?.deepCopy()

    private fun recompute() {
        val observations = aggregator.aggregated()
        if (observations.isEmpty()) {
            latest = emptyEstimate()
            latestInitialization = null
            return
        }

        val logPosterior = DoubleArray(cellCount * hypotheses.size)
        var maximumLogWeight = Double.NEGATIVE_INFINITY
        for (cellIndex in 0 until cellCount) {
            val position = cellCenter(cellIndex)
            for (hypothesisIndex in hypotheses.indices) {
                val hypothesis = hypotheses[hypothesisIndex]
                var logWeight = 0.0
                for (observation in observations) {
                    val predicted = predictedRssi(position, hypothesis, observation)
                    val variance = observation.measurementVarianceDb2 +
                        config.modelSigmaDb * config.modelSigmaDb
                    logWeight += studentTLogKernel(
                        residual = observation.rssiDbm - predicted,
                        variance = variance,
                        degreesOfFreedom = config.studentTDegreesOfFreedom,
                    )
                }
                val jointIndex = jointIndex(cellIndex, hypothesisIndex)
                logPosterior[jointIndex] = logWeight
                maximumLogWeight = max(maximumLogWeight, logWeight)
            }
        }

        var normalizer = 0.0
        val jointPosterior = DoubleArray(logPosterior.size)
        for (index in logPosterior.indices) {
            val weight = kotlin.math.exp(logPosterior[index] - maximumLogWeight)
            jointPosterior[index] = weight
            normalizer += weight
        }
        for (index in jointPosterior.indices) jointPosterior[index] /= normalizer

        val marginal = DoubleArray(cellCount)
        for (cellIndex in 0 until cellCount) {
            var mass = 0.0
            for (hypothesisIndex in hypotheses.indices) {
                mass += jointPosterior[jointIndex(cellIndex, hypothesisIndex)]
            }
            marginal[cellIndex] = mass
        }
        latestPosterior = marginal
        latestJointPosterior = jointPosterior

        val mapIndex = marginal.indices.maxByOrNull { marginal[it] }!!
        val hpd50 = hpdRegion(0.50, marginal)
        val hpd90 = hpdRegion(0.90, marginal)
        val components = connectedComponents(hpd90, marginal)
        val modes = components.map { component ->
            val mapCellIndex = component.indices.maxByOrNull { marginal[it] }!!
            var weightedX = 0.0
            var weightedZ = 0.0
            for (index in component.indices) {
                val center = cellCenter(index)
                weightedX += center.x * marginal[index]
                weightedZ += center.z * marginal[index]
            }
            LocationMode(
                mapPosition = cellCenter(mapCellIndex),
                centroid = Vec2(weightedX / component.mass, weightedZ / component.mass),
                posteriorMass = component.mass,
                fractionOfHpdMass = component.mass / hpd90.enclosedProbability,
            )
        }

        val quality = dataQuality(observations)
        val dominant = components.firstOrNull()
        val hasDominantMode = dominant != null &&
            dominant.mass / hpd90.enclosedProbability >= config.dominantModeFraction
        val isUnimodal = quality.hasInformativeGeometry &&
            hasDominantMode &&
            hpd90.areaSquareMetres <= config.maxLocatedAreaSquareMetres
        val moments = if (isUnimodal) positionMoments(dominant.indices, marginal) else null

        latestInitialization = if (isUnimodal) {
            ekfInitialization(dominant.indices, jointPosterior, observations)
        } else {
            null
        }
        latest = LocationEstimate(
            mapXZ = cellCenter(mapIndex),
            meanXZ = moments?.first,
            covarianceXZ = moments?.second,
            hpd50 = hpd50,
            hpd90 = hpd90,
            area50SquareMetres = hpd50.areaSquareMetres,
            area90SquareMetres = hpd90.areaSquareMetres,
            modes = modes,
            state = when {
                !quality.hasInformativeGeometry -> EstimateState.COLLECTING
                isUnimodal -> EstimateState.GRID_UNIMODAL
                hasDominantMode -> EstimateState.COLLECTING
                else -> EstimateState.GRID_MULTIMODAL
            },
            dataQuality = quality,
        )
    }

    private fun predictedRssi(
        target: Vec2,
        hypothesis: RadioHypothesis,
        observation: AggregatedSpatialRssiSample,
    ): Double {
        val horizontalSquared = target.squaredDistanceTo(observation.phonePosition)
        val heightDelta = hypothesis.targetHeightMetres - observation.phoneHeightMetres
        val distance = max(config.minDistanceMetres, sqrt(horizontalSquared + heightDelta * heightDelta))
        return hypothesis.referenceRssiDbm -
            10.0 * hypothesis.pathLossExponent * log10(distance) +
            directionalRssiAdjustment(
                target = target,
                phone = observation.phonePosition,
                phoneForward = observation.phoneForward,
                maximumAttenuationDb = config.bodyShadowAttenuationDb,
            ).rssiDb
    }

    private fun hpdRegion(level: Double, marginal: DoubleArray): HpdRegion {
        val sortedIndices = marginal.indices.sortedWith(
            compareByDescending<Int> { marginal[it] }.thenBy { it },
        )
        val selected = ArrayList<GridCell>()
        var mass = 0.0
        for (index in sortedIndices) {
            selected += cell(index, marginal[index])
            mass += marginal[index]
            if (mass + 1e-15 >= level) break
        }
        return HpdRegion(
            probabilityLevel = level,
            enclosedProbability = min(1.0, mass),
            areaSquareMetres = selected.size * config.resolutionMetres * config.resolutionMetres,
            cellResolutionMetres = config.resolutionMetres,
            cells = selected,
        )
    }

    private data class Component(val indices: List<Int>, val mass: Double)

    private fun connectedComponents(region: HpdRegion, marginal: DoubleArray): List<Component> {
        val selected = BooleanArray(cellCount)
        region.cells.forEach { selected[cellIndex(it.indexX, it.indexZ)] = true }
        val visited = BooleanArray(cellCount)
        val components = mutableListOf<Component>()
        for (start in 0 until cellCount) {
            if (!selected[start] || visited[start]) continue
            val queue = ArrayDeque<Int>()
            val indices = mutableListOf<Int>()
            queue.add(start)
            visited[start] = true
            var mass = 0.0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                indices += index
                mass += marginal[index]
                val x = index % width
                val z = index / width
                for (deltaZ in -1..1) {
                    for (deltaX in -1..1) {
                        if (deltaX == 0 && deltaZ == 0) continue
                        val neighbourX = x + deltaX
                        val neighbourZ = z + deltaZ
                        if (neighbourX !in 0 until width || neighbourZ !in 0 until depth) continue
                        val neighbour = cellIndex(neighbourX, neighbourZ)
                        if (selected[neighbour] && !visited[neighbour]) {
                            visited[neighbour] = true
                            queue.add(neighbour)
                        }
                    }
                }
            }
            components += Component(indices, mass)
        }
        return components.sortedWith(
            compareByDescending<Component> { it.mass }
                .thenBy { it.indices.minOrNull() ?: Int.MAX_VALUE },
        )
    }

    private fun positionMoments(
        indices: List<Int>,
        marginal: DoubleArray,
    ): Pair<Vec2, Matrix2> {
        val mass = indices.sumOf { marginal[it] }
        val meanX = indices.sumOf { cellCenter(it).x * marginal[it] } / mass
        val meanZ = indices.sumOf { cellCenter(it).z * marginal[it] } / mass
        var xx = 0.0
        var xz = 0.0
        var zz = 0.0
        for (index in indices) {
            val center = cellCenter(index)
            val dx = center.x - meanX
            val dz = center.z - meanZ
            val probability = marginal[index] / mass
            xx += probability * dx * dx
            xz += probability * dx * dz
            zz += probability * dz * dz
        }
        val withinCellVariance = config.resolutionMetres * config.resolutionMetres / 12.0
        return Vec2(meanX, meanZ) to Matrix2(
            xx + withinCellVariance,
            xz,
            xz,
            zz + withinCellVariance,
        ).projectedPsd()
    }

    private fun ekfInitialization(
        componentIndices: List<Int>,
        jointPosterior: DoubleArray,
        observations: List<AggregatedSpatialRssiSample>,
    ): EkfInitialization {
        val included = BooleanArray(cellCount)
        componentIndices.forEach { included[it] = true }
        var mass = 0.0
        val mean = DoubleArray(4)
        var meanHeight = 0.0
        for (cellIndex in 0 until cellCount) {
            if (!included[cellIndex]) continue
            val center = cellCenter(cellIndex)
            for (hypothesisIndex in hypotheses.indices) {
                val probability = jointPosterior[jointIndex(cellIndex, hypothesisIndex)]
                val hypothesis = hypotheses[hypothesisIndex]
                mass += probability
                mean[0] += probability * center.x
                mean[1] += probability * center.z
                mean[2] += probability * hypothesis.referenceRssiDbm
                mean[3] += probability * hypothesis.pathLossExponent
                meanHeight += probability * hypothesis.targetHeightMetres
            }
        }
        for (i in mean.indices) mean[i] /= mass
        meanHeight /= mass

        val covariance = Array(4) { DoubleArray(4) }
        for (cellIndex in 0 until cellCount) {
            if (!included[cellIndex]) continue
            val center = cellCenter(cellIndex)
            for (hypothesisIndex in hypotheses.indices) {
                val probability = jointPosterior[jointIndex(cellIndex, hypothesisIndex)] / mass
                val hypothesis = hypotheses[hypothesisIndex]
                val value = doubleArrayOf(
                    center.x,
                    center.z,
                    hypothesis.referenceRssiDbm,
                    hypothesis.pathLossExponent,
                )
                for (row in 0..3) {
                    for (column in 0..3) {
                        covariance[row][column] += probability *
                            (value[row] - mean[row]) * (value[column] - mean[column])
                    }
                }
            }
        }
        val withinCellVariance = config.resolutionMetres * config.resolutionMetres / 12.0
        covariance[0][0] += withinCellVariance
        covariance[1][1] += withinCellVariance
        covariance[2][2] = max(covariance[2][2], 1.0)
        covariance[3][3] = max(covariance[3][3], 0.01)
        return EkfInitialization(
            state = mean,
            covariance = projectSymmetricPsd(covariance),
            targetHeightMetres = meanHeight,
            observedPhonePositions = observations.map { it.phonePosition },
            bodyShadowAttenuationDb = config.bodyShadowAttenuationDb,
        )
    }

    private fun dataQuality(observations: List<AggregatedSpatialRssiSample>): DataQuality {
        var baseline = 0.0
        for (first in observations.indices) {
            for (second in first + 1 until observations.size) {
                baseline = max(
                    baseline,
                    observations[first].phonePosition.distanceTo(observations[second].phonePosition),
                )
            }
        }
        val meanX = observations.map { it.phonePosition.x }.average()
        val meanZ = observations.map { it.phonePosition.z }.average()
        var xx = 0.0
        var xz = 0.0
        var zz = 0.0
        for (observation in observations) {
            val dx = observation.phonePosition.x - meanX
            val dz = observation.phonePosition.z - meanZ
            xx += dx * dx
            xz += dx * dz
            zz += dz * dz
        }
        xx /= observations.size
        xz /= observations.size
        zz /= observations.size
        val halfTrace = (xx + zz) / 2.0
        val radius = hypot((xx - zz) / 2.0, xz)
        val minorAxisSpread = sqrt(max(0.0, halfTrace - radius))
        val informative = observations.size >= config.minSpatialBucketsForInitialization &&
            baseline >= config.minPositionBaselineMetres &&
            minorAxisSpread >= config.minMinorAxisSpreadMetres
        return DataQuality(
            samplesReceived = aggregator.totalSamplesReceived,
            independentSpatialBuckets = observations.size,
            positionBaselineMetres = baseline,
            minorAxisSpreadMetres = minorAxisSpread,
            hasInformativeGeometry = informative,
        )
    }

    private fun emptyEstimate(): LocationEstimate = LocationEstimate(
        mapXZ = null,
        meanXZ = null,
        covarianceXZ = null,
        hpd50 = null,
        hpd90 = null,
        area50SquareMetres = null,
        area90SquareMetres = null,
        modes = emptyList(),
        state = EstimateState.COLLECTING,
        dataQuality = DataQuality(0, 0, 0.0, 0.0, false),
    )

    private fun cell(index: Int, probability: Double): GridCell {
        val indexX = index % width
        val indexZ = index / width
        return GridCell(indexX, indexZ, cellCenter(index), probability)
    }

    private fun cellCenter(index: Int): Vec2 = Vec2(
        x = config.minX + (index % width) * config.resolutionMetres,
        z = config.minZ + (index / width) * config.resolutionMetres,
    )

    private fun cellIndex(x: Int, z: Int): Int = z * width + x
    private fun jointIndex(cellIndex: Int, hypothesisIndex: Int): Int =
        cellIndex * hypotheses.size + hypothesisIndex

    private fun EkfInitialization.deepCopy(): EkfInitialization = EkfInitialization(
        state = state.copyOf(),
        covariance = Array(covariance.size) { covariance[it].copyOf() },
        targetHeightMetres = targetHeightMetres,
        observedPhonePositions = observedPhonePositions.toList(),
        bodyShadowAttenuationDb = bodyShadowAttenuationDb,
    )
}

private fun studentTLogKernel(
    residual: Double,
    variance: Double,
    degreesOfFreedom: Double,
): Double = -((degreesOfFreedom + 1.0) / 2.0) *
    ln(1.0 + residual * residual / (degreesOfFreedom * variance))
