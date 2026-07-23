package dev.arsignallocator.sync

import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

data class SynchronizerConfig(
    val maximumPoseAgeNanos: Long = 250_000_000L,
    val retainedPoseDurationNanos: Long = 2_000_000_000L,
    val rssiWindowSize: Int = 7,
    val spatialBucketMetres: Double = 0.25,
    val minimumRssiVarianceDb2: Double = 9.0,
)

/**
 * Joins observations in the app's monotonic clock domain. ARCore's Frame timestamp is retained
 * for diagnostics only because ARCore deliberately does not define its clock domain.
 */
class SampleSynchronizer(
    private val config: SynchronizerConfig = SynchronizerConfig(),
) {
    private val poses = ArrayDeque<PoseObservation>()
    private val rssiWindow = ArrayDeque<Int>()
    private val consumedSpatialBuckets = mutableSetOf<Pair<Int, Int>>()

    @Synchronized
    fun addPose(pose: PoseObservation) {
        poses.addLast(pose)
        val oldestAllowed = pose.receivedAtElapsedNanos - config.retainedPoseDurationNanos
        while (poses.firstOrNull()?.receivedAtElapsedNanos?.let { it < oldestAllowed } == true) {
            poses.removeFirst()
        }
    }

    @Synchronized
    fun synchronize(ble: BleObservation): SynchronizedObservation {
        rssiWindow.addLast(ble.rssiDbm)
        while (rssiWindow.size > config.rssiWindowSize) rssiWindow.removeFirst()

        val pose = poses.minByOrNull {
            absoluteDifference(it.receivedAtElapsedNanos, ble.receivedAtElapsedNanos)
        }
        val age = pose?.let {
            absoluteDifference(it.receivedAtElapsedNanos, ble.receivedAtElapsedNanos)
        }
        val robustMedian = median(rssiWindow.map(Int::toDouble))
        val mad = median(rssiWindow.map { abs(it - robustMedian) })
        val variance = ((1.4826 * mad).pow(2)).coerceAtLeast(config.minimumRssiVarianceDb2)

        if (pose == null || age == null || age > config.maximumPoseAgeNanos) {
            return rejected(ble, pose, age, "no_recent_pose", robustMedian, variance)
        }
        if (pose.trackingState != PoseTrackingState.TRACKING) {
            return rejected(ble, pose, age, "pose_not_tracking", robustMedian, variance)
        }

        val bucket = spatialBucket(pose)
        if (!consumedSpatialBuckets.add(bucket)) {
            return rejected(ble, pose, age, "correlated_spatial_bucket", robustMedian, variance)
        }

        return SynchronizedObservation(
            ble = ble,
            pose = pose,
            poseAssociationAgeNanos = age,
            accepted = true,
            rejectionReason = null,
            filteredRssiDbm = robustMedian,
            rssiVarianceDb2 = variance,
        )
    }

    @Synchronized
    fun reset() {
        poses.clear()
        rssiWindow.clear()
        consumedSpatialBuckets.clear()
    }

    private fun spatialBucket(pose: PoseObservation): Pair<Int, Int> =
        floor(pose.xMetres / config.spatialBucketMetres).toInt() to
            floor(pose.zMetres / config.spatialBucketMetres).toInt()

    private fun rejected(
        ble: BleObservation,
        pose: PoseObservation?,
        age: Long?,
        reason: String,
        median: Double,
        variance: Double,
    ) = SynchronizedObservation(
        ble = ble,
        pose = pose,
        poseAssociationAgeNanos = age,
        accepted = false,
        rejectionReason = reason,
        filteredRssiDbm = median,
        rssiVarianceDb2 = variance,
    )

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun absoluteDifference(a: Long, b: Long): Long =
        if (a >= b) a - b else b - a
}
