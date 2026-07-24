package dev.arsignallocator.ble

import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.TargetHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompleteScanFlowTest {
    @Test
    fun `complete scan repeats BLE and Classic`() =
        runTest {
            var bleWindows = 0
            var classicInquiries = 0

            val packets = continuousCompleteScan(
                supportsLowEnergy = true,
                scanLowEnergyWindow = {
                    bleWindows += 1
                    flowOf(packet(BluetoothTransport.LOW_ENERGY, "ble-$bleWindows"))
                },
                scanClassicInquiry = {
                    classicInquiries += 1
                    flowOf(packet(BluetoothTransport.CLASSIC, "classic-$classicInquiries"))
                },
            ).take(5).toList()

            assertEquals(
                listOf(
                    BluetoothTransport.LOW_ENERGY,
                    BluetoothTransport.CLASSIC,
                    BluetoothTransport.LOW_ENERGY,
                    BluetoothTransport.CLASSIC,
                    BluetoothTransport.LOW_ENERGY,
                ),
                packets.map { it.target.transport },
            )
            assertEquals(3, bleWindows)
            assertEquals(2, classicInquiries)
        }

    @Test
    fun `Classic-only schedule never opens a BLE window`() = runTest {
        var classicInquiries = 0

        val packets = continuousCompleteScan(
            supportsLowEnergy = false,
            scanLowEnergyWindow = { error("BLE must not run") },
            scanClassicInquiry = {
                classicInquiries += 1
                flowOf(packet(BluetoothTransport.CLASSIC, "classic-$classicInquiries"))
            },
        ).take(3).toList()

        assertEquals(3, classicInquiries)
        assertEquals(
            List(3) { BluetoothTransport.CLASSIC },
            packets.map { it.target.transport },
        )
    }

    @Test
    fun `identity is stable within a session and isolated by transport`() {
        val identities = SessionDeviceIdentities()

        val ble = identities.key(BluetoothTransport.LOW_ENERGY, "AA:BB:CC:DD:EE:FF")
        assertEquals(ble, identities.key(BluetoothTransport.LOW_ENERGY, "AA:BB:CC:DD:EE:FF"))
        val classic = identities.key(BluetoothTransport.CLASSIC, "AA:BB:CC:DD:EE:FF")

        assertEquals(DeviceKey("D01"), ble)
        assertEquals(DeviceKey("D02"), classic)
        assertEquals(2, identities.size())
    }

    @Test
    fun `cancelling an overlapping session cannot clear the newer identity map`() = runTest {
        lateinit var firstIdentities: SessionDeviceIdentities
        lateinit var secondIdentities: SessionDeviceIdentities
        val first = launch {
            continuousCompleteScan(
                supportsLowEnergy = true,
                scanLowEnergyWindow = { identities ->
                    firstIdentities = identities
                    kotlinx.coroutines.flow.flow {
                        emit(packet(BluetoothTransport.LOW_ENERGY, "first"))
                        awaitCancellation()
                    }
                },
                scanClassicInquiry = { flowOf() },
            ).collect {}
        }
        runCurrent()
        val second = launch {
            continuousCompleteScan(
                supportsLowEnergy = true,
                scanLowEnergyWindow = { identities ->
                    secondIdentities = identities
                    kotlinx.coroutines.flow.flow {
                        identities.key(BluetoothTransport.LOW_ENERGY, "second-a")
                        emit(packet(BluetoothTransport.LOW_ENERGY, "second"))
                        awaitCancellation()
                    }
                },
                scanClassicInquiry = { flowOf() },
            ).collect {}
        }
        runCurrent()

        assertNotSame(firstIdentities, secondIdentities)
        first.cancelAndJoin()
        assertEquals(
            DeviceKey("D02"),
            secondIdentities.key(BluetoothTransport.CLASSIC, "second-b"),
        )
        second.cancelAndJoin()
    }

    private fun packet(transport: BluetoothTransport, address: String): ScanPacket {
        val target = TargetHandle(
            key = DeviceKey(address),
            ephemeralAddress = address,
            advertisedName = null,
            manufacturerIds = emptySet(),
            serviceUuids = emptySet(),
            transport = transport,
        )
        return ScanPacket(
            target,
            BleObservation(
                observedAtPlatformNanos = 1L,
                receivedAtElapsedNanos = 1L,
                deviceKey = target.key,
                rssiDbm = -60,
                txPowerDbm = null,
                advertisedName = null,
                manufacturerIds = emptySet(),
                serviceUuids = emptySet(),
                transport = transport,
            ),
        )
    }
}
