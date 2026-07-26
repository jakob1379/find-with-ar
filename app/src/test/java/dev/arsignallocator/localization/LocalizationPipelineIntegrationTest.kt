package dev.arsignallocator.localization

import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.sync.SampleSynchronizer
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationPipelineIntegrationTest {
    @Test
    fun `default app pipeline encloses targets and withholds unsupported points`() {
        val random = Random(20260723L)
        var withheldPoints = 0
        repeat(12) { scenario ->
            val angle = random.nextDouble() * 2.0 * Math.PI
            val translation = Vec2(
                x = random.nextDouble() - 0.5,
                z = random.nextDouble() - 0.5,
            )
            val target = transform(Vec2(1.1, -0.8), angle, translation)
            val path = walkingPath().map { transform(it, angle, translation) }
            val estimate = locate(
                target,
                path,
                LocalizationAdapter(),
                bodyShadowAttenuationDb = 6.0,
                referenceRssiDbm = -58.0,
            ).estimate
            val meanX = estimate.meanXMetres
            val meanZ = estimate.meanZMetres
            if (meanX == null || meanZ == null) {
                withheldPoints += 1
            } else {
                val position = Vec2(meanX, meanZ)
                assertTrue(
                    "scenario $scenario default estimate $estimate missed $target",
                    position.distanceTo(target) <= 1.5,
                )
            }
            assertTrue(
                "scenario $scenario default 90% region missed $target",
                estimate.containsInRegion90(target),
            )
        }
        assertTrue("the confidence gate was not exercised", withheldPoints > 0)
    }

    @Test
    fun `generated measurements stay attached to their poses in either walking direction`() {
        val target = Vec2(1.1, -0.8)
        val path = walkingPath()

        val forward = locate(target, path)
        val reverse = locate(target, path.reversed())
        val forwardPosition = Vec2(
            forward.estimate.meanXMetres!!,
            forward.estimate.meanZMetres!!,
        )
        val reversePosition = Vec2(
            reverse.estimate.meanXMetres!!,
            reverse.estimate.meanZMetres!!,
        )

        assertTrue(forward.acceptedPackets == path.size)
        assertTrue(reverse.acceptedPackets == path.size)
        assertTrue(
            "forward estimate $forwardPosition missed $target",
            forwardPosition.distanceTo(target) <= 0.65,
        )
        assertTrue(
            "reverse estimate $reversePosition missed $target",
            reversePosition.distanceTo(target) <= 0.65,
        )
        assertTrue(
            "walking direction moved the estimate from $forwardPosition to $reversePosition",
            forwardPosition.distanceTo(reversePosition) <= 0.05,
        )
    }

    private fun locate(
        target: Vec2,
        path: List<Vec2>,
        engine: LocationEngine = LocalizationAdapter(BayesianGridLocalizer(exactConfig)),
        bodyShadowAttenuationDb: Double = 0.0,
        referenceRssiDbm: Double = -55.0,
    ): PipelineResult {
        val synchronizer = SampleSynchronizer()
        var estimate = engine.estimate()
        var acceptedPackets = 0
        path.forEachIndexed { index, phone ->
            val timestamp = 1_000_000_000L + index * 500_000_000L
            val direction = if (index < path.lastIndex) {
                path[index + 1] - phone
            } else {
                phone - path[index - 1]
            }
            val directionLength = sqrt(direction.x * direction.x + direction.z * direction.z)
            val phoneForward = Vec2(direction.x / directionLength, direction.z / directionLength)
            synchronizer.addPose(pose(timestamp, phone, phoneForward))
            val joined = synchronizer.synchronize(
                ble(
                    timestamp + 10_000_000L,
                    expectedRssi(
                        target,
                        phone,
                        phoneForward,
                        bodyShadowAttenuationDb,
                        referenceRssiDbm,
                    ),
                ),
            )
            if (joined.accepted) {
                acceptedPackets += 1
                estimate = engine.update(joined)
            }
        }
        return PipelineResult(estimate, acceptedPackets)
    }

    private fun walkingPath(): List<Vec2> = List(17) { index ->
        val progress = index / 16.0
        Vec2(
            x = -4.0 + 8.0 * progress,
            z = 1.8 * sin(progress * Math.PI * 1.35),
        )
    }

    private fun transform(point: Vec2, angle: Double, translation: Vec2): Vec2 = Vec2(
        x = translation.x + cos(angle) * point.x - sin(angle) * point.z,
        z = translation.z + sin(angle) * point.x + cos(angle) * point.z,
    )

    private fun expectedRssi(
        target: Vec2,
        phone: Vec2,
        phoneForward: Vec2,
        bodyShadowAttenuationDb: Double,
        referenceRssiDbm: Double,
    ): Int {
        val heightDelta = 0.1 - 1.1
        val distance = sqrt(target.squaredDistanceTo(phone) + heightDelta * heightDelta)
        val horizontalDistance = max(1e-9, target.distanceTo(phone))
        val targetDirection = target - phone
        val facingCosine = (
            phoneForward.x * targetDirection.x + phoneForward.z * targetDirection.z
            ) / horizontalDistance
        return (
            referenceRssiDbm -
                22.0 * log10(distance) +
                bodyShadowAttenuationDb * minOf(0.0, facingCosine)
            ).roundToInt()
    }

    private fun pose(timestamp: Long, position: Vec2, phoneForward: Vec2) = PoseObservation(
        capturedAtPlatformNanos = timestamp,
        receivedAtElapsedNanos = timestamp,
        xMetres = position.x,
        yMetres = 1.1,
        zMetres = position.z,
        qx = 0.0,
        qy = sin(headingRadians(phoneForward) / 2.0),
        qz = 0.0,
        qw = cos(headingRadians(phoneForward) / 2.0),
        trackingState = PoseTrackingState.TRACKING,
    )

    private fun headingRadians(phoneForward: Vec2): Double =
        atan2(-phoneForward.x, -phoneForward.z)

    private fun ble(timestamp: Long, rssiDbm: Int) = BleObservation(
        observedAtPlatformNanos = timestamp,
        receivedAtElapsedNanos = timestamp,
        deviceKey = DeviceKey("D01"),
        rssiDbm = rssiDbm,
        txPowerDbm = null,
        advertisedName = null,
        manufacturerIds = emptySet(),
        serviceUuids = emptySet(),
    )

    private data class PipelineResult(
        val estimate: EstimateUiModel,
        val acceptedPackets: Int,
    )

    private fun EstimateUiModel.containsInRegion90(target: Vec2): Boolean {
        if (probabilityCells.isNotEmpty()) {
            return probabilityCells.any { cell ->
                val halfCell = cell.resolutionMetres / 2.0 + 1e-12
                kotlin.math.abs(cell.xMetres - target.x) <= halfCell &&
                    kotlin.math.abs(cell.zMetres - target.z) <= halfCell
            }
        }
        val meanX = meanXMetres ?: return false
        val meanZ = meanZMetres ?: return false
        val xx = covarianceXX ?: return false
        val xz = covarianceXZ ?: return false
        val zz = covarianceZZ ?: return false
        val determinant = xx * zz - xz * xz
        if (determinant <= 0.0) return false
        val dx = target.x - meanX
        val dz = target.z - meanZ
        return (zz * dx * dx - 2.0 * xz * dx * dz + xx * dz * dz) / determinant <=
            4.605170186
    }

    private companion object {
        val exactConfig = GridConfig(
            minX = -5.0,
            maxX = 5.0,
            minZ = -5.0,
            maxZ = 5.0,
            resolutionMetres = 0.2,
            referenceRssiHypothesesDbm = listOf(-55.0),
            pathLossExponentHypotheses = listOf(2.2),
            targetHeightHypothesesMetres = listOf(0.1),
            modelSigmaDb = 1.0,
            spatialBucketSizeMetres = 0.2,
            minSpatialBucketsForInitialization = 5,
            minPositionBaselineMetres = 1.0,
            minMinorAxisSpreadMetres = 0.1,
            bodyShadowAttenuationDb = 0.0,
        )
    }
}
