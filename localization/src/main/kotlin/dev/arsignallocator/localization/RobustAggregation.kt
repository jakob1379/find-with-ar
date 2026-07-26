package dev.arsignallocator.localization

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

object RobustStatistics {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** Median absolute deviation, scaled to estimate Gaussian sigma. */
    fun robustSigma(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val center = median(values)
        return 1.4826 * median(values.map { kotlin.math.abs(it - center) })
    }
}

data class AggregatedSpatialRssiSample(
    val phonePosition: Vec2,
    val phoneHeightMetres: Double,
    val rssiDbm: Double,
    val measurementVarianceDb2: Double,
    val rawSampleCount: Int,
    val latestElapsedRealtimeNanos: Long,
    val phoneForward: Vec2?,
)

/**
 * Coalesces colocated readings into one likelihood term.
 *
 * Repeating packets while standing still improves the robust representative but
 * never increases the likelihood exponent. This prevents packet rate from being
 * mistaken for independent spatial evidence.
 */
class SpatialRssiAggregator(
    val bucketSizeMetres: Double = 0.35,
    private val minVarianceDb2: Double = 9.0,
    private val maxStoredSamplesPerBucket: Int = 101,
) {
    init {
        require(bucketSizeMetres > 0.0)
        require(minVarianceDb2 > 0.0)
        require(maxStoredSamplesPerBucket > 0)
    }

    private data class BucketKey(val x: Int, val z: Int)
    private val buckets = linkedMapOf<BucketKey, MutableList<SpatialRssiSample>>()
    var totalSamplesReceived: Int = 0
        private set

    fun add(sample: SpatialRssiSample) {
        totalSamplesReceived += 1
        val key = BucketKey(
            x = floor(sample.phonePosition.x / bucketSizeMetres).toInt(),
            z = floor(sample.phonePosition.z / bucketSizeMetres).toInt(),
        )
        val samples = buckets.getOrPut(key) { mutableListOf() }
        samples += sample
        if (samples.size > maxStoredSamplesPerBucket) samples.removeAt(0)
    }

    fun aggregated(): List<AggregatedSpatialRssiSample> = buckets
        .toSortedMap(compareBy<BucketKey>({ it.x }, { it.z }))
        .values
        .map(::aggregateBucket)

    fun clear() {
        buckets.clear()
        totalSamplesReceived = 0
    }

    private fun aggregateBucket(samples: List<SpatialRssiSample>): AggregatedSpatialRssiSample {
        val rssiValues = samples.map(SpatialRssiSample::rssiDbm)
        val robustSigma = RobustStatistics.robustSigma(rssiValues)
        return AggregatedSpatialRssiSample(
            phonePosition = Vec2(
                RobustStatistics.median(samples.map { it.phonePosition.x }),
                RobustStatistics.median(samples.map { it.phonePosition.z }),
            ),
            phoneHeightMetres = RobustStatistics.median(samples.map(SpatialRssiSample::phoneHeightMetres)),
            rssiDbm = RobustStatistics.median(rssiValues),
            measurementVarianceDb2 = max(
                minVarianceDb2,
                max(
                    RobustStatistics.median(samples.map(SpatialRssiSample::measurementVarianceDb2)),
                    robustSigma * robustSigma,
                ),
            ),
            rawSampleCount = samples.size,
            latestElapsedRealtimeNanos = samples.maxOf(SpatialRssiSample::elapsedRealtimeNanos),
            phoneForward = aggregateForward(samples),
        )
    }

    private fun aggregateForward(samples: List<SpatialRssiSample>): Vec2? {
        val forwards = samples.mapNotNull(SpatialRssiSample::phoneForward)
        if (forwards.isEmpty()) return null
        val x = RobustStatistics.median(forwards.map(Vec2::x))
        val z = RobustStatistics.median(forwards.map(Vec2::z))
        val length = hypot(x, z)
        return if (length > 1e-6) Vec2(x / length, z / length) else null
    }
}
