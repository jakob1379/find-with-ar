package dev.arsignallocator.trace

import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceRecorderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes deterministic schema v1 without a Bluetooth address`() {
        val target = TargetHandle(
            key = DeviceKey("D01"),
            ephemeralAddress = "AA:BB:CC:DD:EE:FF",
            advertisedName = "Beacon \"one\"",
            manufacturerIds = setOf(76),
            serviceUuids = setOf("180f"),
            transport = BluetoothTransport.CLASSIC,
        )
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            sessionIdFactory = { "test-session" },
        )
        val file = recorder.start("0.1.0", "Test phone", target)
        recorder.record(sample(), null)
        recorder.stop()

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(
            "{\"schema_version\":1,\"record_type\":\"session\",\"session_id\":\"test-session\"," +
                "\"created_at_utc\":\"1970-01-01T00:00:00.000Z\",\"app_version\":\"0.1.0\"," +
                "\"device_model\":\"Test phone\",\"coordinate_frame\":\"arcore_xz_m\"," +
                "\"target\":{\"session_key\":\"D01\",\"display_name\":\"Beacon \\\"one\\\"\"," +
                "\"transport\":\"classic\",\"manufacturer_ids\":[76]," +
                "\"service_uuids\":[\"180f\"]}}",
            lines[0],
        )
        assertFalse(lines.joinToString().contains("AA:BB"))
        assertEquals(
            "{\"schema_version\":1,\"record_type\":\"observation\",\"session_id\":\"test-session\"," +
                "\"sequence\":0,\"app_monotonic_ns\":20,\"platform_timestamp_ns\":10," +
                "\"rssi_dbm\":-61,\"estimator_rssi_dbm\":-61,\"tx_power_dbm\":null," +
                "\"accepted\":true,\"rejection_reason\":null," +
                "\"pose\":{\"x_m\":1.0,\"y_m\":1.4,\"z_m\":2.0,\"qx\":0.0,\"qy\":0.0," +
                "\"qz\":0.0,\"qw\":1.0,\"tracking_state\":\"TRACKING\"},\"estimator\":null," +
                "\"pose_association_age_ns\":2,\"filtered_rssi_dbm\":-60.0,\"rssi_variance_db2\":9.0," +
                "\"advertisement\":{\"advertised_name\":null,\"transport\":\"classic\"," +
                "\"manufacturer_ids\":[],\"service_uuids\":[]}}",
            lines[1],
        )
    }

    private fun sample() = SynchronizedObservation(
        ble = BleObservation(
            10L,
            20L,
            DeviceKey("D01"),
            -61,
            null,
            null,
            emptySet(),
            emptySet(),
            BluetoothTransport.CLASSIC,
        ),
        pose = PoseObservation(
            11L, 18L, 1.0, 1.4, 2.0, 0.0, 0.0, 0.0, 1.0, PoseTrackingState.TRACKING,
        ),
        poseAssociationAgeNanos = 2L,
        accepted = true,
        rejectionReason = null,
        filteredRssiDbm = -60.0,
        rssiVarianceDb2 = 9.0,
    )
}
