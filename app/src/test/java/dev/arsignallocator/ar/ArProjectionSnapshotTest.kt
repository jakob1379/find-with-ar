package dev.arsignallocator.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArProjectionSnapshotTest {
    @Test
    fun `projects anchor coordinates into normalized screen coordinates`() {
        val projection = ArProjectionSnapshot(identity(), updatedAtElapsedNanos = 1L)

        val center = projection.project(0.0, 0.0, 0.0)!!
        val lowerRight = projection.project(1.0, -1.0, 0.0)!!

        assertEquals(0.5f, center.x, 0f)
        assertEquals(0.5f, center.y, 0f)
        assertEquals(1f, lowerRight.x, 0f)
        assertEquals(1f, lowerRight.y, 0f)
    }

    @Test
    fun `does not project a point behind the camera`() {
        val behind = identity().also { it[15] = -1f }
        assertNull(ArProjectionSnapshot(behind, 1L).project(0.0, 0.0, 0.0))
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
