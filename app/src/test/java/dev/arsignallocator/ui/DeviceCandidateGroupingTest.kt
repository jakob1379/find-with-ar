package dev.arsignallocator.ui

import dev.arsignallocator.updateCandidateOrder
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.TargetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCandidateGroupingTest {
    @Test
    fun `discovery keeps existing rows and appends new identities`() {
        val first = candidate("D01", "Speaker", -80.0)
        val second = candidate("D02", "Headphones", -45.0)
        val new = candidate("D03", "Watch", -60.0)

        assertEquals(
            listOf(first.key, second.key, new.key),
            updateCandidateOrder(
                previous = listOf(first.key, second.key),
                candidates = listOf(second, new, first),
                discoveryActive = true,
            ),
        )
    }

    @Test
    fun `discovery completion ranks bonded devices then robust signal`() {
        val strong = candidate("D01", "Speaker", -45.0)
        val bonded = candidate("D02", "Headphones", -80.0, bonded = true)
        val weak = candidate("D03", "Watch", -70.0)

        assertEquals(
            listOf(bonded.key, strong.key, weak.key),
            updateCandidateOrder(
                previous = listOf(weak.key, bonded.key, strong.key),
                candidates = listOf(weak, bonded, strong),
                discoveryActive = false,
            ),
        )
    }

    @Test
    fun `fresh scan resets the prior order`() {
        val current = candidate("D03", "Watch", -60.0)

        assertEquals(
            listOf(current.key),
            updateCandidateOrder(
                previous = listOf(DeviceKey("D01"), DeviceKey("D02")),
                candidates = listOf(current),
                discoveryActive = true,
                freshScan = true,
            ),
        )
    }

    @Test
    fun `groups named devices and searches live names case insensitively`() {
        val unnamed = candidate("D01", null, -40.0)
        val speaker = candidate("D02", "Kitchen Speaker", -60.0)
        val headphones = candidate(
            "D03",
            "Headphones",
            -70.0,
            transport = BluetoothTransport.CLASSIC,
        )
        val candidates = listOf(unnamed, speaker, headphones)
        val ordered = listOf(unnamed, headphones, speaker)

        val all = groupDeviceCandidates(ordered, "")
        assertEquals(listOf(headphones, speaker), all.named)
        assertEquals(listOf(unnamed), all.unnamed)

        val searched = groupDeviceCandidates(candidates, "SPEAK")
        assertEquals(listOf(speaker), searched.named)
        assertTrue(searched.unnamed.isEmpty())
    }

    private fun candidate(
        key: String,
        name: String?,
        medianRssiDbm: Double,
        bonded: Boolean = false,
        transport: BluetoothTransport = BluetoothTransport.LOW_ENERGY,
    ): DeviceCandidate {
        val deviceKey = DeviceKey(key)
        return DeviceCandidate(
            handle = TargetHandle(
                key = deviceKey,
                ephemeralAddress = key,
                advertisedName = name,
                manufacturerIds = emptySet(),
                serviceUuids = emptySet(),
                transport = transport,
                bonded = bonded,
            ),
            latestRssiDbm = medianRssiDbm.toInt(),
            medianRssiDbm = medianRssiDbm,
            observationsPerSecond = 1.0,
            lastSeenElapsedNanos = 0L,
        )
    }
}
