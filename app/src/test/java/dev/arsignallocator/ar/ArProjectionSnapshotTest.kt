package dev.arsignallocator.ar

import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
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

    @Test
    fun `generated yaw rotations report the camera heading on the top-down map`() {
        val random = Random(20260723L)
        repeat(100) {
            val yaw = random.nextDouble() * 2.0 * Math.PI - Math.PI
            val projection = ArProjectionSnapshot(
                identity(),
                updatedAtElapsedNanos = 1L,
                cameraPose = pose(qy = sin(yaw / 2.0), qw = cos(yaw / 2.0)),
            )

            assertEquals(
                Math.toDegrees(-yaw).toFloat(),
                projection.cameraHeadingDegrees!!,
                0.001f,
            )
        }
    }

    private fun pose(qy: Double, qw: Double) = PoseObservation(
        capturedAtPlatformNanos = 1L,
        receivedAtElapsedNanos = 1L,
        xMetres = 0.0,
        yMetres = 1.0,
        zMetres = 0.0,
        qx = 0.0,
        qy = qy,
        qz = 0.0,
        qw = qw,
        trackingState = PoseTrackingState.TRACKING,
    )

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
