package dev.arsignallocator.localization

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class RssiPrediction(
    val rssiDbm: Double,
    /** Derivatives with respect to x, z, reference RSSI A and path-loss exponent n. */
    val jacobian: DoubleArray,
)

internal data class DirectionalRssiAdjustment(
    val rssiDb: Double,
    val derivativeX: Double,
    val derivativeZ: Double,
)

internal fun directionalRssiAdjustment(
    target: Vec2,
    phone: Vec2,
    phoneForward: Vec2?,
    maximumAttenuationDb: Double,
): DirectionalRssiAdjustment {
    if (phoneForward == null || maximumAttenuationDb == 0.0) {
        return DirectionalRssiAdjustment(0.0, 0.0, 0.0)
    }
    val deltaX = target.x - phone.x
    val deltaZ = target.z - phone.z
    val distance = hypot(deltaX, deltaZ)
    if (distance <= 1e-6) return DirectionalRssiAdjustment(0.0, 0.0, 0.0)
    val forwardLength = hypot(phoneForward.x, phoneForward.z)
    val forwardX = phoneForward.x / forwardLength
    val forwardZ = phoneForward.z / forwardLength
    val dot = forwardX * deltaX + forwardZ * deltaZ
    val facingCosine = dot / distance
    if (facingCosine >= 0.0) return DirectionalRssiAdjustment(0.0, 0.0, 0.0)
    val distanceCubed = distance * distance * distance
    return DirectionalRssiAdjustment(
        rssiDb = maximumAttenuationDb * facingCosine,
        derivativeX = maximumAttenuationDb * (
            forwardX / distance - dot * deltaX / distanceCubed
            ),
        derivativeZ = maximumAttenuationDb * (
            forwardZ / distance - dot * deltaZ / distanceCubed
            ),
    )
}

object RssiObservationModel {
    fun predictAndJacobian(
        state: DoubleArray,
        sample: SpatialRssiSample,
        targetHeightMetres: Double,
        minDistanceMetres: Double = 0.15,
        bodyShadowAttenuationDb: Double = 0.0,
    ): RssiPrediction {
        require(state.size == 4)
        require(minDistanceMetres > 0.0)
        val deltaX = state[0] - sample.phonePosition.x
        val deltaZ = state[1] - sample.phonePosition.z
        val deltaHeight = targetHeightMetres - sample.phoneHeightMetres
        val rawDistanceSquared =
            deltaX * deltaX + deltaZ * deltaZ + deltaHeight * deltaHeight
        val minimumDistanceSquared = minDistanceMetres * minDistanceMetres
        val distanceSquared = max(rawDistanceSquared, minimumDistanceSquared)
        val distance = sqrt(distanceSquared)
        val referenceRssi = state[2]
        val pathLossExponent = state[3]
        val directional = directionalRssiAdjustment(
            target = Vec2(state[0], state[1]),
            phone = sample.phonePosition,
            phoneForward = sample.phoneForward,
            maximumAttenuationDb = bodyShadowAttenuationDb,
        )
        val predicted = referenceRssi -
            10.0 * pathLossExponent * log10(distance) +
            directional.rssiDb
        val spatialScale = if (rawDistanceSquared <= minimumDistanceSquared) {
            0.0
        } else {
            -10.0 * pathLossExponent / (ln(10.0) * distanceSquared)
        }
        return RssiPrediction(
            rssiDbm = predicted,
            jacobian = doubleArrayOf(
                spatialScale * deltaX + directional.derivativeX,
                spatialScale * deltaZ + directional.derivativeZ,
                1.0,
                -10.0 * log10(distance),
            ),
        )
    }
}

data class EkfConfig(
    val positionProcessVarianceSquareMetres: Double = 1e-5,
    val referenceRssiProcessVarianceDb2: Double = 0.02,
    val pathLossProcessVariance: Double = 0.0002,
    val modelVarianceDb2: Double = 16.0,
    val normalizedInnovationSquaredGate: Double = 9.0,
    val minDistanceMetres: Double = 0.15,
    val spatialBucketSizeMetres: Double = 0.35,
    val minCovarianceEigenvalue: Double = 1e-9,
    val referenceRssiBoundsDbm: ClosedFloatingPointRange<Double> = -100.0..-20.0,
    val pathLossBounds: ClosedFloatingPointRange<Double> = 1.0..5.0,
) {
    init {
        require(positionProcessVarianceSquareMetres >= 0.0)
        require(referenceRssiProcessVarianceDb2 >= 0.0)
        require(pathLossProcessVariance >= 0.0)
        require(modelVarianceDb2 > 0.0)
        require(normalizedInnovationSquaredGate > 0.0)
        require(minDistanceMetres > 0.0 && spatialBucketSizeMetres > 0.0)
        require(minCovarianceEigenvalue >= 0.0)
    }
}

/** Extended Kalman filter initialized from a dominant Bayesian-grid mode. */
class EkfLocalizer(
    initialization: EkfInitialization,
    val config: EkfConfig = EkfConfig(),
    initialDataQuality: DataQuality = DataQuality(0, 0, 0.0, 0.0, true),
) : Localizer {
    private val initial = initialization.deepCopy()
    private val initialQuality = initialDataQuality
    private var state = initialization.state.copyOf()
    private var covariance = projectSymmetricPsd(
        initialization.covariance,
        config.minCovarianceEigenvalue,
    )
    private val targetHeightMetres = initialization.targetHeightMetres
    private var totalSamples = initialDataQuality.samplesReceived
    private var acceptedUpdates = 0
    private var rejectedUpdates = 0
    private var lastNis: Double? = null
    private val bucketPositions = linkedMapOf<Pair<Int, Int>, Vec2>().apply {
        initialization.observedPhonePositions.forEach { position ->
            put(bucketKey(position), position)
        }
    }
    private var latest = buildEstimate(initialDataQuality)

    @Synchronized
    override fun update(sample: SpatialRssiSample): LocationEstimate {
        totalSamples += 1
        val bucket = bucketKey(sample.phonePosition)
        // A colocated packet is correlated evidence. Do not shrink covariance again.
        if (bucket in bucketPositions) {
            latest = buildEstimate(dataQuality())
            return latest
        }
        bucketPositions[bucket] = sample.phonePosition

        val predictedCovariance = Array(4) { row -> covariance[row].copyOf() }
        predictedCovariance[0][0] += config.positionProcessVarianceSquareMetres
        predictedCovariance[1][1] += config.positionProcessVarianceSquareMetres
        predictedCovariance[2][2] += config.referenceRssiProcessVarianceDb2
        predictedCovariance[3][3] += config.pathLossProcessVariance
        covariance = projectSymmetricPsd(predictedCovariance, config.minCovarianceEigenvalue)

        val prediction = RssiObservationModel.predictAndJacobian(
            state = state,
            sample = sample,
            targetHeightMetres = targetHeightMetres,
            minDistanceMetres = config.minDistanceMetres,
            bodyShadowAttenuationDb = initial.bodyShadowAttenuationDb,
        )
        val measurementVariance = sample.measurementVarianceDb2 + config.modelVarianceDb2
        val covarianceTimesJacobian = DoubleArray(4) { row ->
            (0..3).sumOf { column -> covariance[row][column] * prediction.jacobian[column] }
        }
        val innovationVariance = measurementVariance +
            (0..3).sumOf { index -> prediction.jacobian[index] * covarianceTimesJacobian[index] }
        val innovation = sample.rssiDbm - prediction.rssiDbm
        val nis = innovation * innovation / innovationVariance
        lastNis = nis
        if (!nis.isFinite() || nis > config.normalizedInnovationSquaredGate) {
            rejectedUpdates += 1
            latest = buildEstimate(dataQuality())
            return latest
        }

        val gain = DoubleArray(4) { covarianceTimesJacobian[it] / innovationVariance }
        for (index in 0..3) state[index] += gain[index] * innovation
        state[2] = state[2].coerceIn(config.referenceRssiBoundsDbm)
        state[3] = state[3].coerceIn(config.pathLossBounds)

        // Joseph form is numerically safer than P = (I-KH)P.
        val identityMinusKh = identity(4)
        for (row in 0..3) {
            for (column in 0..3) {
                identityMinusKh[row][column] -= gain[row] * prediction.jacobian[column]
            }
        }
        val joseph = add(
            multiply(multiply(identityMinusKh, covariance), transpose(identityMinusKh)),
            outer(gain, gain, measurementVariance),
        )
        covariance = projectSymmetricPsd(joseph, config.minCovarianceEigenvalue)
        acceptedUpdates += 1
        latest = buildEstimate(dataQuality())
        return latest
    }

    @Synchronized
    override fun estimate(): LocationEstimate = latest

    @Synchronized
    override fun reset() {
        state = initial.state.copyOf()
        covariance = projectSymmetricPsd(initial.covariance, config.minCovarianceEigenvalue)
        totalSamples = initialQuality.samplesReceived
        acceptedUpdates = 0
        rejectedUpdates = 0
        lastNis = null
        bucketPositions.clear()
        initial.observedPhonePositions.forEach { position ->
            bucketPositions[bucketKey(position)] = position
        }
        latest = buildEstimate(initialQuality)
    }

    fun stateVector(): DoubleArray = synchronized(this) { state.copyOf() }
    fun fullCovariance(): Array<DoubleArray> = synchronized(this) {
        Array(4) { covariance[it].copyOf() }
    }

    private fun buildEstimate(quality: DataQuality): LocationEstimate {
        val position = Vec2(state[0], state[1])
        val positionCovariance = Matrix2(
            covariance[0][0],
            covariance[0][1],
            covariance[1][0],
            covariance[1][1],
        ).projectedPsd(config.minCovarianceEigenvalue)
        return LocationEstimate(
            mapXZ = position,
            meanXZ = position,
            covarianceXZ = positionCovariance,
            hpd50 = null,
            hpd90 = null,
            area50SquareMetres = covarianceEllipseArea(positionCovariance, CHI_SQUARED_2D_50),
            area90SquareMetres = covarianceEllipseArea(positionCovariance, CHI_SQUARED_2D_90),
            modes = listOf(LocationMode(position, position, 1.0, 1.0)),
            state = EstimateState.EKF,
            dataQuality = quality,
            diagnostics = EstimatorDiagnostics(acceptedUpdates, rejectedUpdates, lastNis),
        )
    }

    private fun dataQuality(): DataQuality {
        val positions = bucketPositions.values.toList()
        if (positions.isEmpty()) return DataQuality(totalSamples, 0, 0.0, 0.0, false)
        var baseline = 0.0
        for (first in positions.indices) {
            for (second in first + 1 until positions.size) {
                baseline = max(baseline, positions[first].distanceTo(positions[second]))
            }
        }
        val meanX = positions.map(Vec2::x).average()
        val meanZ = positions.map(Vec2::z).average()
        var xx = 0.0
        var xz = 0.0
        var zz = 0.0
        for (position in positions) {
            val dx = position.x - meanX
            val dz = position.z - meanZ
            xx += dx * dx
            xz += dx * dz
            zz += dz * dz
        }
        xx /= positions.size
        xz /= positions.size
        zz /= positions.size
        val halfTrace = (xx + zz) / 2.0
        val radius = kotlin.math.hypot((xx - zz) / 2.0, xz)
        val minor = sqrt(max(0.0, halfTrace - radius))
        return DataQuality(totalSamples, positions.size, baseline, minor, positions.size >= 3)
    }

    private fun EkfInitialization.deepCopy(): EkfInitialization = EkfInitialization(
        state.copyOf(),
        Array(covariance.size) { covariance[it].copyOf() },
        targetHeightMetres,
        observedPhonePositions.toList(),
        bodyShadowAttenuationDb,
    )

    private fun bucketKey(position: Vec2): Pair<Int, Int> = Pair(
        floor(position.x / config.spatialBucketSizeMetres).toInt(),
        floor(position.z / config.spatialBucketSizeMetres).toInt(),
    )
}

data class HybridConfig(val maxConsecutiveEkfRejections: Int = 4) {
    init {
        require(maxConsecutiveEkfRejections > 0)
    }
}

/** Grid acquisition with an EKF fast path once one mode dominates. */
class HybridGridEkfLocalizer(
    private val grid: BayesianGridLocalizer = BayesianGridLocalizer(),
    private val ekfConfig: EkfConfig = EkfConfig(),
    private val hybridConfig: HybridConfig = HybridConfig(),
) : Localizer {
    private var ekf: EkfLocalizer? = null
    private var consecutiveRejections = 0

    @Synchronized
    override fun update(sample: SpatialRssiSample): LocationEstimate {
        val gridEstimate = grid.update(sample)
        if (gridEstimate.state != EstimateState.GRID_UNIMODAL) {
            ekf = null
            consecutiveRejections = 0
            return gridEstimate
        }
        val activeEkf = ekf
        if (activeEkf == null) {
            val initialization = grid.ekfInitializationOrNull() ?: return gridEstimate
            val initialized = EkfLocalizer(initialization, ekfConfig, gridEstimate.dataQuality)
                .also { ekf = it }
                .estimate()
            return initialized.withGridRegion(gridEstimate)
        }

        val rejectedBefore = activeEkf.estimate().diagnostics.rejectedUpdates
        val estimate = activeEkf.update(sample)
        consecutiveRejections = if (estimate.diagnostics.rejectedUpdates > rejectedBefore) {
            consecutiveRejections + 1
        } else {
            0
        }
        if (consecutiveRejections >= hybridConfig.maxConsecutiveEkfRejections) {
            // A run of mutually inconsistent measurements means the stationary-target
            // assumption is no longer trustworthy. Discard the stale confident mode and
            // restart acquisition from the newest sample so the UI broadens immediately.
            grid.reset()
            consecutiveRejections = 0
            ekf = null
            return grid.update(sample)
        }
        if (gridEstimate.hpd90?.contains(requireNotNull(estimate.meanXZ)) == false) {
            val replacement = grid.ekfInitializationOrNull()?.let {
                EkfLocalizer(it, ekfConfig, gridEstimate.dataQuality)
            }
            ekf = replacement
            consecutiveRejections = 0
            return replacement?.estimate()?.withGridRegion(gridEstimate) ?: gridEstimate
        }
        return estimate.withGridRegion(gridEstimate)
    }

    @Synchronized
    override fun estimate(): LocationEstimate {
        val gridEstimate = grid.estimate()
        return ekf?.estimate()?.withGridRegion(gridEstimate) ?: gridEstimate
    }

    @Synchronized
    override fun reset() {
        grid.reset()
        ekf = null
        consecutiveRejections = 0
    }

    private fun LocationEstimate.withGridRegion(gridEstimate: LocationEstimate): LocationEstimate = copy(
        hpd50 = gridEstimate.hpd50,
        hpd90 = gridEstimate.hpd90,
        area50SquareMetres = gridEstimate.area50SquareMetres,
        area90SquareMetres = gridEstimate.area90SquareMetres,
        modes = gridEstimate.modes,
        dataQuality = gridEstimate.dataQuality,
    )
}

internal const val CHI_SQUARED_2D_50 = 1.3862943611198906
internal const val CHI_SQUARED_2D_90 = 4.605170185988092

internal fun projectSymmetricPsd(
    input: Array<DoubleArray>,
    minEigenvalue: Double = 1e-9,
): Array<DoubleArray> {
    val size = input.size
    require(size > 0 && input.all { it.size == size })
    val matrix = Array(size) { row ->
        DoubleArray(size) { column ->
            val value = (input[row][column] + input[column][row]) / 2.0
            require(value.isFinite()) { "Covariance contains non-finite values" }
            value
        }
    }
    val eigenvectors = identity(size)
    repeat(64) {
        var p = 0
        var q = 1.coerceAtMost(size - 1)
        var largest = 0.0
        for (row in 0 until size) {
            for (column in row + 1 until size) {
                if (abs(matrix[row][column]) > largest) {
                    largest = abs(matrix[row][column])
                    p = row
                    q = column
                }
            }
        }
        if (largest < 1e-12 || size == 1) return@repeat
        val offDiagonal = matrix[p][q]
        val tau = (matrix[q][q] - matrix[p][p]) / (2.0 * offDiagonal)
        val tangent = if (tau >= 0.0) {
            1.0 / (tau + sqrt(1.0 + tau * tau))
        } else {
            -1.0 / (-tau + sqrt(1.0 + tau * tau))
        }
        val cosine = 1.0 / sqrt(1.0 + tangent * tangent)
        val sine = tangent * cosine
        val app = matrix[p][p]
        val aqq = matrix[q][q]
        matrix[p][p] = app - tangent * offDiagonal
        matrix[q][q] = aqq + tangent * offDiagonal
        matrix[p][q] = 0.0
        matrix[q][p] = 0.0
        for (index in 0 until size) {
            if (index == p || index == q) continue
            val aip = matrix[index][p]
            val aiq = matrix[index][q]
            matrix[index][p] = cosine * aip - sine * aiq
            matrix[p][index] = matrix[index][p]
            matrix[index][q] = sine * aip + cosine * aiq
            matrix[q][index] = matrix[index][q]
        }
        for (row in 0 until size) {
            val vip = eigenvectors[row][p]
            val viq = eigenvectors[row][q]
            eigenvectors[row][p] = cosine * vip - sine * viq
            eigenvectors[row][q] = sine * vip + cosine * viq
        }
    }
    val eigenvalues = DoubleArray(size) { max(matrix[it][it], minEigenvalue) }
    return Array(size) { row ->
        DoubleArray(size) { column ->
            (0 until size).sumOf { index ->
                eigenvectors[row][index] * eigenvalues[index] * eigenvectors[column][index]
            }
        }
    }
}

private fun identity(size: Int): Array<DoubleArray> =
    Array(size) { row -> DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 } }

private fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> =
    Array(matrix[0].size) { row -> DoubleArray(matrix.size) { column -> matrix[column][row] } }

private fun multiply(
    left: Array<DoubleArray>,
    right: Array<DoubleArray>,
): Array<DoubleArray> = Array(left.size) { row ->
    DoubleArray(right[0].size) { column ->
        right.indices.sumOf { index -> left[row][index] * right[index][column] }
    }
}

private fun add(
    left: Array<DoubleArray>,
    right: Array<DoubleArray>,
): Array<DoubleArray> = Array(left.size) { row ->
    DoubleArray(left[row].size) { column -> left[row][column] + right[row][column] }
}

private fun outer(left: DoubleArray, right: DoubleArray, scale: Double): Array<DoubleArray> =
    Array(left.size) { row ->
        DoubleArray(right.size) { column -> left[row] * right[column] * scale }
    }
