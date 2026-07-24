package dev.arsignallocator

import dev.arsignallocator.ar.PoseSource
import dev.arsignallocator.ble.BleAvailability
import dev.arsignallocator.ble.BleScanner
import dev.arsignallocator.ble.ScanPacket
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.localization.LocationEngine
import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.ArStatus
import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.ESTIMATE_STATUS_COLLECTING
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                    deleteFile = { false },
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
            assertNull(viewModel.state.value.lastTraceFile)
            assertTrue(
                temporaryFolder.root.listFiles()
                    .orEmpty()
                    .single()
                    .name
                    .endsWith(".active.ndjson"),
            )

            viewModel.setRuntimeReady(false)
            runCurrent()
            val trace = viewModel.state.value.lastTraceFile
            assertNotNull(trace)
            assertFalse(trace!!.name.endsWith(".active.ndjson"))
            assertEquals(3, trace.readLines().size)
            assertTrue(trace.readText().contains("\"schema_version\":2"))
            assertTrue(trace.readText().contains("\"accepted\":true"))
            assertTrue(trace.readText().contains("\"outcome\":\"INCOMPLETE\""))
            assertTrue(viewModel.state.value.recoverableError!!.contains("unavailable"))

            viewModel.deleteLocalTraces()
            runCurrent()
            assertEquals(trace, viewModel.state.value.lastTraceFile)
            assertTrue(trace.exists())
            assertTrue(viewModel.state.value.recoverableError!!.contains("Could not delete 1"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `explicit completion records only the supplied outcome`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val target = target("explicit-outcome")
            val scanner = FakeScanner(packet(target, at = clock.nowNanos(), rssi = -65))
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    temporaryFolder.root,
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { 0L },
                    sessionIdFactory = { "explicit-outcome" },
                ),
                clock = clock,
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.setRuntimeReady(true)
            runCurrent()
            viewModel.selectDevice(target.key)
            runCurrent()

            viewModel.completeTrace(TraceOutcome.YES, note = "explicit test answer")
            runCurrent()

            val payload = viewModel.state.value.lastTraceFile!!.readText()
            assertTrue(payload.contains("\"outcome\":\"YES\""))
            assertTrue(payload.contains("\"note\":\"explicit test answer\""))
            assertFalse(payload.contains("\"outcome\":\"NO\""))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `explicit completion survives a newer session starting`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        val ioScheduler = TestCoroutineScheduler()
        val ioDispatcher = StandardTestDispatcher(ioScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val target = target("completion-race")
            val scanner = FakeScanner(packet(target, at = 1_000_000_000L, rssi = -65))
            var sessionNumber = 0
            val recorder = TraceRecorder(
                temporaryFolder.root,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "completion-race-${++sessionNumber}" },
            )
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = recorder,
                clock = FakeClock(1_000_000_000L),
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = mainDispatcher,
                ioDispatcher = ioDispatcher,
            )

            runCurrent()
            ioScheduler.runCurrent()
            runCurrent()
            viewModel.setRuntimeReady(true)
            runCurrent()
            viewModel.selectDevice(target.key)
            runCurrent()
            ioScheduler.runCurrent()
            runCurrent()

            viewModel.completeTrace(TraceOutcome.YES)
            runCurrent()
            assertEquals(1, viewModel.state.value.candidates.size)
            viewModel.selectDevice(target.key)
            runCurrent()
            ioScheduler.runCurrent()
            runCurrent()

            val firstTrace = recorder.completedTraces()
                .single { it.readText().contains("\"session_id\":\"completion-race-1\"") }
            assertTrue(firstTrace.readText().contains("\"outcome\":\"YES\""))
            assertFalse(firstTrace.readText().contains("\"outcome\":\"INCOMPLETE\""))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale trace start recovers itself before a newer finish can run`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val target = target("stale-start")
            val scanner = FakeScanner(packet(target, at = clock.nowNanos(), rssi = -65))
            lateinit var viewModel: LocatorViewModel
            var interruptStart = true
            val recorder = TraceRecorder(
                traceDirectory = temporaryFolder.root,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "stale-start" },
                writerFactory = { file ->
                    if (interruptStart) {
                        interruptStart = false
                        viewModel.stopAndReturnToDevices()
                    }
                    file.bufferedWriter()
                },
            )
            viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = recorder,
                clock = clock,
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.setRuntimeReady(true)
            runCurrent()

            viewModel.selectDevice(target.key)
            runCurrent()

            assertEquals(AppScreen.DEVICES, viewModel.state.value.screen)
            assertEquals(0, scanner.trackCalls)
            assertFalse(
                temporaryFolder.root.listFiles().orEmpty()
                    .any { it.name.endsWith(".active.ndjson") },
            )
            val payload = recorder.completedTraces().single().readText()
            assertTrue(payload.contains("\"session_id\":\"stale-start\""))
            assertTrue(payload.contains("\"outcome\":\"INCOMPLETE\""))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `finalization failure is recoverable UI state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val target = TargetHandle(
                DeviceKey("D01"),
                "AA:BB:CC:DD:EE:FF",
                "Test beacon",
                emptySet(),
                emptySet(),
            )
            val viewModel = LocatorViewModel(
                scanner = FakeScanner(packet(target, at = clock.nowNanos(), rssi = -65)),
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    traceDirectory = temporaryFolder.root,
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { 0L },
                    sessionIdFactory = { "rename-failure" },
                    renameFile = { _, _ -> false },
                ),
                clock = clock,
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.setRuntimeReady(true)
            runCurrent()
            viewModel.selectDevice(target.key)
            runCurrent()

            viewModel.stopAndReturnToDevices()
            runCurrent()

            assertEquals(AppScreen.DEVICES, viewModel.state.value.screen)
            assertTrue(viewModel.state.value.recoverableError!!.contains("Could not finalize"))
            assertTrue(
                temporaryFolder.root.listFiles().orEmpty()
                    .any { it.name.endsWith(".active.ndjson") },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }

    private class FakeScanner(private val discoveryPacket: ScanPacket) : BleScanner {
        val tracking = MutableSharedFlow<ScanPacket>(extraBufferCapacity = 4)
        var trackCalls = 0
        override fun availability(): BleAvailability = BleAvailability.Available
        override fun discover(durationMillis: Long): Flow<ScanPacket> =
            kotlinx.coroutines.flow.flowOf(discoveryPacket)
        override fun track(target: TargetHandle): Flow<ScanPacket> {
            trackCalls += 1
            return tracking
        }
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
            status = ESTIMATE_STATUS_COLLECTING,
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

    private fun target(key: String) = TargetHandle(
        DeviceKey(key),
        "AA:BB:CC:DD:EE:FF",
        "Test beacon",
        emptySet(),
        emptySet(),
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
