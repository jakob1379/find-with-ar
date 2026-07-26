package dev.arsignallocator.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustAggregationTest {
    @Test
    fun `median aggregation resists a single RSSI outlier`() {
        val aggregator = SpatialRssiAggregator(bucketSizeMetres = 0.5, minVarianceDb2 = 1.0)
        listOf(-60.0, -61.0, -59.0, -60.0, -10.0).forEachIndexed { index, rssi ->
            aggregator.add(
                SpatialRssiSample(
                    phonePosition = Vec2(0.1, 0.1),
                    phoneHeightMetres = 1.1,
                    rssiDbm = rssi,
                    measurementVarianceDb2 = 1.0,
                    elapsedRealtimeNanos = index.toLong(),
                ),
            )
        }
        val result = aggregator.aggregated().single()
        assertEquals(-60.0, result.rssiDbm, 0.0)
        assertEquals(5, result.rawSampleCount)
        assertTrue(result.measurementVarianceDb2 >= 1.0)
    }
}
