package dev.arsignallocator.localization

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A horizontal position in the AR anchor's x/z plane, measured in metres. */
data class Vec2(val x: Double, val z: Double) {
    init {
        require(x.isFinite() && z.isFinite()) { "Coordinates must be finite" }
    }

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, z + other.z)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, z - other.z)
    operator fun times(scale: Double): Vec2 = Vec2(x * scale, z * scale)
    fun squaredDistanceTo(other: Vec2): Double =
        (x - other.x) * (x - other.x) + (z - other.z) * (z - other.z)
    fun distanceTo(other: Vec2): Double = sqrt(squaredDistanceTo(other))
}

/** Small immutable matrix used for the public 2-D covariance API. */
data class Matrix2(
    val m00: Double,
    val m01: Double,
    val m10: Double,
    val m11: Double,
) {
    init {
        require(listOf(m00, m01, m10, m11).all { it.isFinite() }) {
            "Matrix entries must be finite"
        }
    }

    val determinant: Double get() = m00 * m11 - m01 * m10
    val trace: Double get() = m00 + m11
    val isSymmetric: Boolean get() = abs(m01 - m10) <= 1e-10

    fun symmetrized(): Matrix2 {
        val offDiagonal = (m01 + m10) / 2.0
        return Matrix2(m00, offDiagonal, offDiagonal, m11)
    }

    /** Projects a symmetric 2x2 matrix onto the positive-semidefinite cone. */
    fun projectedPsd(minEigenvalue: Double = 1e-9): Matrix2 {
        require(minEigenvalue >= 0.0)
        val symmetric = symmetrized()
        val halfTrace = symmetric.trace / 2.0
        val radius = hypot((symmetric.m00 - symmetric.m11) / 2.0, symmetric.m01)
        val high = max(halfTrace + radius, minEigenvalue)
        val low = max(halfTrace - radius, minEigenvalue)
        val angle = 0.5 * atan2(2.0 * symmetric.m01, symmetric.m00 - symmetric.m11)
        val c = cos(angle)
        val s = sin(angle)
        return Matrix2(
            m00 = high * c * c + low * s * s,
            m01 = (high - low) * c * s,
            m10 = (high - low) * c * s,
            m11 = high * s * s + low * c * c,
        )
    }

    fun quadraticForm(vector: Vec2): Double =
        vector.x * (m00 * vector.x + m01 * vector.z) +
            vector.z * (m10 * vector.x + m11 * vector.z)

    companion object {
        fun diagonal(xVariance: Double, zVariance: Double): Matrix2 =
            Matrix2(xVariance, 0.0, 0.0, zVariance)
    }
}

data class SpatialRssiSample(
    val phonePosition: Vec2,
    val phoneHeightMetres: Double,
    val rssiDbm: Double,
    val measurementVarianceDb2: Double = 9.0,
    val elapsedRealtimeNanos: Long = 0L,
    val phoneForward: Vec2? = null,
) {
    init {
        require(phoneHeightMetres.isFinite()) { "Phone height must be finite" }
        require(rssiDbm.isFinite()) { "RSSI must be finite" }
        require(measurementVarianceDb2.isFinite() && measurementVarianceDb2 > 0.0) {
            "Measurement variance must be finite and positive"
        }
        require(phoneForward == null || phoneForward.squaredDistanceTo(Vec2(0.0, 0.0)) > 1e-12) {
            "Phone forward vector must be non-zero"
        }
    }
}

data class GridCell(
    val indexX: Int,
    val indexZ: Int,
    val center: Vec2,
    val probability: Double,
)

/** Highest-posterior-density cells; the cells, not a bounding box, define membership. */
data class HpdRegion(
    val probabilityLevel: Double,
    val enclosedProbability: Double,
    val areaSquareMetres: Double,
    val cellResolutionMetres: Double,
    val cells: List<GridCell>,
) {
    init {
        require(probabilityLevel in 0.0..1.0)
        require(enclosedProbability >= 0.0 && enclosedProbability <= 1.0 + 1e-12)
        require(areaSquareMetres >= 0.0)
    }

    fun contains(point: Vec2): Boolean {
        val halfCell = cellResolutionMetres / 2.0 + 1e-12
        return cells.any { cell ->
            abs(cell.center.x - point.x) <= halfCell &&
                abs(cell.center.z - point.z) <= halfCell
        }
    }
}

data class LocationMode(
    val mapPosition: Vec2,
    val centroid: Vec2,
    val posteriorMass: Double,
    val fractionOfHpdMass: Double,
)

enum class EstimateState {
    COLLECTING,
    GRID_MULTIMODAL,
    GRID_UNIMODAL,
    EKF,
}

data class DataQuality(
    val samplesReceived: Int,
    val independentSpatialBuckets: Int,
    val positionBaselineMetres: Double,
    val minorAxisSpreadMetres: Double,
    val hasInformativeGeometry: Boolean,
)

data class EstimatorDiagnostics(
    val acceptedUpdates: Int = 0,
    val rejectedUpdates: Int = 0,
    val lastNormalizedInnovationSquared: Double? = null,
)

/**
 * Public estimate shared by grid and EKF implementations.
 *
 * [mapXZ] is null until a posterior estimate exists and is safe to use when non-null. [meanXZ]
 * is deliberately null while the grid posterior is insufficiently constrained or multimodal.
 */
data class LocationEstimate(
    val mapXZ: Vec2?,
    val meanXZ: Vec2?,
    val covarianceXZ: Matrix2?,
    val hpd50: HpdRegion?,
    val hpd90: HpdRegion?,
    val area50SquareMetres: Double?,
    val area90SquareMetres: Double?,
    val modes: List<LocationMode>,
    val state: EstimateState,
    val dataQuality: DataQuality,
    val diagnostics: EstimatorDiagnostics = EstimatorDiagnostics(),
) {
    val credibleAreaSquareMetres: Double? get() = area90SquareMetres
}

interface Localizer {
    fun update(sample: SpatialRssiSample): LocationEstimate
    fun estimate(): LocationEstimate
    fun reset()
}

internal fun covarianceEllipseArea(covariance: Matrix2, chiSquaredQuantile: Double): Double =
    PI * chiSquaredQuantile * sqrt(max(0.0, covariance.projectedPsd().determinant))
