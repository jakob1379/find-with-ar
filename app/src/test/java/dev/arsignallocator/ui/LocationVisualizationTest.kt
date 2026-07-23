package dev.arsignallocator.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationVisualizationTest {
    @Test
    fun `all five visualizations can be toggled independently`() {
        val all = LocationVisualization.entries.toSet()

        assertEquals(5, all.size)
        LocationVisualization.entries.forEach { visualization ->
            val hidden = all.toggled(visualization)
            assertEquals(all - visualization, hidden)
            assertEquals(all, hidden.toggled(visualization))
        }
    }
}
