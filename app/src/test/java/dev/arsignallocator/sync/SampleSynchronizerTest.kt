package dev.arsignallocator.sync

import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSynchronizerTest {
    private val key = DeviceKey("D01")

    @Test
    fun `associates using callback monotonic timestamps`() {
        val synchronizer = SampleSynchronizer()
        synchronizer.addPose(pose(at = 1_000_000_000L, x = 0.0))

        val result = synchronizer.synchronize(ble(at = 1_020_000_000L, rssi = -60))

        assertTrue(result.accepted)
        assertEquals(20_000_000L, result.poseAssociationAgeNanos)
        assertEquals(-60.0, result.filteredRssiDbm!!, 0.0)
    }

    @Test
    fun `rejects stale pose rather than mixing platform timestamp domains`() {
        val synchronizer = SampleSynchronizer()
        synchronizer.addPose(pose(at = 1_000_000_000L, x = 0.0))

        val result = synchronizer.synchronize(ble(at = 1_500_000_001L, rssi = -60))

        assertFalse(result.accepted)
        assertEquals("no_recent_pose", result.rejectionReason)
    }

    @Test
    fun `allows three colocated readings then caps correlated input`() {
        val synchronizer = SampleSynchronizer()
        synchronizer.addPose(pose(at = 1_000_000_000L, x = 0.02))
        val first = synchronizer.synchronize(ble(at = 1_010_000_000L, rssi = -62))
        val second = synchronizer.synchronize(ble(at = 1_020_000_000L, rssi = -58))
        val third = synchronizer.synchronize(ble(at = 1_030_000_000L, rssi = -60))
        val duplicate = synchronizer.synchronize(ble(at = 1_040_000_000L, rssi = -57))

        assertTrue(first.accepted)
        assertTrue(second.accepted)
        assertTrue(third.accepted)
        assertFalse(duplicate.accepted)
        assertEquals("correlated_spatial_bucket", duplicate.rejectionReason)
        assertEquals(9.0, duplicate.rssiVarianceDb2!!, 0.0)
    }

    @Test
    fun `movement to a new bucket enables another update`() {
        val synchronizer = SampleSynchronizer()
        synchronizer.addPose(pose(at = 1_000_000_000L, x = 0.0))
        assertTrue(synchronizer.synchronize(ble(at = 1_010_000_000L, rssi = -62)).accepted)

        synchronizer.addPose(pose(at = 1_100_000_000L, x = 0.3))
        assertTrue(synchronizer.synchronize(ble(at = 1_110_000_000L, rssi = -60)).accepted)
    }

    private fun ble(at: Long, rssi: Int) = BleObservation(
        observedAtPlatformNanos = 999L,
        receivedAtElapsedNanos = at,
        deviceKey = key,
        rssiDbm = rssi,
        txPowerDbm = null,
        advertisedName = null,
        manufacturerIds = emptySet(),
        serviceUuids = emptySet(),
    )

    private fun pose(at: Long, x: Double) = PoseObservation(
        capturedAtPlatformNanos = 111L,
        receivedAtElapsedNanos = at,
        xMetres = x,
        yMetres = 1.4,
        zMetres = 0.0,
        qx = 0.0,
        qy = 0.0,
        qz = 0.0,
        qw = 1.0,
        trackingState = PoseTrackingState.TRACKING,
    )
}
