package dev.arsignallocator

import dev.arsignallocator.ar.PoseSource
import dev.arsignallocator.ble.BleAvailability
import dev.arsignallocator.ble.BleScanner
import dev.arsignallocator.ble.ScanPacket
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.localization.LocationEngine
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.ArStatus
import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocatorViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `coordinates discovery pose scan localization and versioned trace`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val target = TargetHandle(
                DeviceKey("D01"),
                "AA:BB:CC:DD:EE:FF",
                "Test beacon",
                setOf(76),
                setOf("180f"),
            )
            val discoveryPacket = packet(target, at = clock.nowNanos(), rssi = -65)
            val scanner = FakeScanner(discoveryPacket)
            val engine = FakeLocationEngine()
            val poseSource = FakePoseSource()
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = engine,
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    temporaryFolder.root,
                    wallClockMillis = { 0L },
                    sessionIdFactory = { "coordinator-test" },
                ),
                clock = clock,
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.bindPoseSource(poseSource)
            viewModel.setRuntimeReady(true)
            runCurrent()

            assertEquals(1, viewModel.state.value.candidates.size)
            viewModel.selectDevice(target.key)
            runCurrent()
            poseSource.emit(pose(at = clock.nowNanos()))
            runCurrent()
            clock.value += 20_000_000L
            scanner.tracking.emit(packet(target, at = clock.nowNanos(), rssi = -60))
            runCurrent()

            assertEquals(1, viewModel.state.value.metrics.receivedCount)
            assertEquals(1, viewModel.state.value.metrics.acceptedCount)
            assertEquals(1, engine.updates)
            val trace = viewModel.state.value.lastTraceFile
            assertNotNull(trace)
            assertEquals(2, trace!!.readLines().size)
            assertTrue(trace.readText().contains("\"schema_version\":1"))
            assertTrue(trace.readText().contains("\"accepted\":true"))

            viewModel.setRuntimeReady(false)
            runCurrent()
            assertTrue(viewModel.state.value.recoverableError!!.contains("unavailable"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }

    private class FakeScanner(private val discoveryPacket: ScanPacket) : BleScanner {
        val tracking = MutableSharedFlow<ScanPacket>(extraBufferCapacity = 4)
        override fun availability(): BleAvailability = BleAvailability.Available
        override fun discover(durationMillis: Long): Flow<ScanPacket> =
            kotlinx.coroutines.flow.flowOf(discoveryPacket)
        override fun track(target: TargetHandle): Flow<ScanPacket> = tracking
    }

    private class FakePoseSource : PoseSource {
        private val mutablePoses = MutableSharedFlow<PoseObservation>(extraBufferCapacity = 4)
        override val poses = mutablePoses
        override val status = MutableStateFlow(ArStatus(ArRuntimeStatus.TRACKING))
        suspend fun emit(pose: PoseObservation) = mutablePoses.emit(pose)
    }

    private class FakeLocationEngine : LocationEngine {
        var updates = 0
        private val current = EstimateUiModel(
            status = "COLLECTING",
            meanXMetres = null,
            meanZMetres = null,
            covarianceXX = null,
            covarianceXZ = null,
            covarianceZZ = null,
            regionProbability = 0.9,
            regionAreaSquareMetres = 10.0,
            ambiguity = 1.0,
        )

        override fun update(observation: SynchronizedObservation): EstimateUiModel {
            updates += 1
            return current
        }

        override fun estimate(): EstimateUiModel = current
        override fun reset() = Unit
    }

    private fun packet(target: TargetHandle, at: Long, rssi: Int) = ScanPacket(
        target,
        BleObservation(
            observedAtPlatformNanos = at - 5,
            receivedAtElapsedNanos = at,
            deviceKey = target.key,
            rssiDbm = rssi,
            txPowerDbm = null,
            advertisedName = target.advertisedName,
            manufacturerIds = target.manufacturerIds,
            serviceUuids = target.serviceUuids,
        ),
    )

    private fun pose(at: Long) = PoseObservation(
        capturedAtPlatformNanos = 123L,
        receivedAtElapsedNanos = at,
        xMetres = 0.0,
        yMetres = 1.4,
        zMetres = 0.0,
        qx = 0.0,
        qy = 0.0,
        qz = 0.0,
        qw = 1.0,
        trackingState = PoseTrackingState.TRACKING,
    )
}
