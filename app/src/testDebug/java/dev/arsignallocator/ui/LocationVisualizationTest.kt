package dev.arsignallocator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocationVisualizationTest {
    @Test
    fun `all five debug visualizations can be toggled independently`() {
        val all = TrackingVisualizations()

        assertEquals(5, LocationVisualization.entries.size)
        LocationVisualization.entries.forEach { visualization ->
            val hidden = all.toggled(visualization)
            assertFalse(visualization.enabled(hidden))
            assertEquals(all, hidden.toggled(visualization))
        }
    }
}
