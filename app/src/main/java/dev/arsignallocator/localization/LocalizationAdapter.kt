package dev.arsignallocator.localization

import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.ProbabilityCell
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.localization.HybridGridEkfLocalizer
import dev.arsignallocator.localization.Localizer
import dev.arsignallocator.localization.LocationEstimate
import dev.arsignallocator.localization.SpatialRssiSample
import dev.arsignallocator.localization.Vec2
import kotlin.math.hypot

/** The only Android-to-localization-module coupling point. */
interface LocationEngine {
    fun update(observation: SynchronizedObservation): EstimateUiModel
    fun estimate(): EstimateUiModel
    fun reset()
}

class LocalizationAdapter(
    private val localizer: Localizer = HybridGridEkfLocalizer(),
) : LocationEngine {
    override fun update(observation: SynchronizedObservation): EstimateUiModel {
        require(observation.accepted)
        val pose = requireNotNull(observation.pose)
        val forwardX = -2.0 * (pose.qx * pose.qz + pose.qw * pose.qy)
        val forwardZ = -(1.0 - 2.0 * (pose.qx * pose.qx + pose.qy * pose.qy))
        val forwardLength = hypot(forwardX, forwardZ)
        val estimate = localizer.update(
            SpatialRssiSample(
                phonePosition = Vec2(pose.xMetres, pose.zMetres),
                phoneHeightMetres = pose.yMetres,
                rssiDbm = observation.ble.rssiDbm.toDouble(),
                measurementVarianceDb2 = requireNotNull(observation.rssiVarianceDb2),
                elapsedRealtimeNanos = observation.ble.receivedAtElapsedNanos,
                phoneForward = if (forwardLength > 1e-6) {
                    Vec2(forwardX / forwardLength, forwardZ / forwardLength)
                } else {
                    null
                },
            ),
        )
        return estimate.toUiModel()
    }

    override fun estimate(): EstimateUiModel = localizer.estimate().toUiModel()

    override fun reset() = localizer.reset()

    private fun LocationEstimate.toUiModel(): EstimateUiModel {
        val region = hpd90
        val covariance = covarianceXZ.takeIf { region == null }
        return EstimateUiModel(
            status = state,
            meanXMetres = meanXZ?.x,
            meanZMetres = meanXZ?.z,
            covarianceXX = covariance?.m00,
            covarianceXZ = covariance?.let { (it.m01 + it.m10) / 2.0 },
            covarianceZZ = covariance?.m11,
            regionProbability = region?.enclosedProbability ?: if (covariance != null) 0.90 else null,
            regionAreaSquareMetres = area90SquareMetres,
            ambiguity = modes.firstOrNull()?.let {
                (1.0 - it.fractionOfHpdMass).coerceIn(0.0, 1.0)
            },
            probabilityCells = region?.let { hpd ->
                hpd.cells.asSequence()
                    .sortedByDescending { it.probability }
                    .take(MAX_UI_PROBABILITY_CELLS)
                    .map { cell ->
                    ProbabilityCell(
                        xMetres = cell.center.x,
                        zMetres = cell.center.z,
                        probability = cell.probability,
                        resolutionMetres = hpd.cellResolutionMetres,
                    )
                }.toList()
            }.orEmpty(),
        )
    }

    private companion object {
        const val MAX_UI_PROBABILITY_CELLS = 1_500
    }
}
