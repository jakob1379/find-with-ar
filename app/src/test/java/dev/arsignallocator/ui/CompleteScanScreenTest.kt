package dev.arsignallocator.ui

import dev.arsignallocator.localization.EstimateState
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.CompleteScanDevice
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.ProbabilityCell
import dev.arsignallocator.model.TrackingMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class CompleteScanScreenTest {
    @Test
    fun `device status stays plain language and frozen state wins`() {
        val sparse = device(received = 2, accepted = 2)
        assertEquals("Not enough signal", completeScanDeviceStatus(sparse, 2.0))
        assertEquals(
            "More movement needed",
            completeScanDeviceStatus(device(received = 3, accepted = 3), 0.5),
        )
        assertEquals(
            "Likely area found",
            completeScanDeviceStatus(
                device(received = 3, accepted = 3, estimate = localizedEstimate()),
                2.0,
            ),
        )
        assertEquals(
            "Last seen · estimate frozen",
            completeScanDeviceStatus(sparse.copy(signalLost = true), 2.0),
        )
    }

    private fun device(
        received: Int,
        accepted: Int,
        estimate: EstimateUiModel? = null,
    ) = CompleteScanDevice(
        key = DeviceKey("D01"),
        displayName = "Device D01",
        transport = BluetoothTransport.LOW_ENERGY,
        metrics = TrackingMetrics(receivedCount = received, acceptedCount = accepted),
        estimate = estimate,
    )

    private fun localizedEstimate() = EstimateUiModel(
        status = EstimateState.EKF,
        meanXMetres = 1.0,
        meanZMetres = 2.0,
        covarianceXX = 1.0,
        covarianceXZ = 0.0,
        covarianceZZ = 1.0,
        regionProbability = 0.9,
        regionAreaSquareMetres = 5.0,
        ambiguity = 0.1,
        probabilityCells = listOf(ProbabilityCell(1.0, 2.0, 1.0, 0.5)),
    )
}
