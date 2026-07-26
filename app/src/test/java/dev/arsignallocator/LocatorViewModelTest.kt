package dev.arsignallocator

import com.google.gson.JsonParser
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
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.ESTIMATE_STATUS_COLLECTING
import dev.arsignallocator.model.ESTIMATE_STATUS_EKF
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.ProbabilityCell
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceOutcome
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
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
            assertEquals(
                1,
                "\"record_type\":\"observation\"".toRegex().findAll(trace.readText()).count(),
            )
            assertTrue(trace.readText().contains("\"schema_version\":2"))
            assertTrue(trace.readText().contains("\"accepted\":true"))
            assertTrue(trace.readText().contains("\"outcome\":\"INCOMPLETE\""))
            assertTrue(viewModel.state.value.recoverableError!!.contains("unavailable"))

            viewModel.traceStore.deleteAllTraces()
            runCurrent()
            assertEquals(trace, viewModel.state.value.lastTraceFile)
            assertTrue(trace.exists())
            assertEquals(
                "Some sessions could not be deleted",
                viewModel.traceStore.error.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `single target records sanitized deduplicated transitions in write order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val selected = target("event-single")
            val scanner = FakeScanner(packet(selected, clock.nowNanos(), -65))
            val engine = FakeLocationEngine()
            val poseSource = FakePoseSource()
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = engine,
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    temporaryFolder.newFolder("event-single"),
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { clock.nowNanos() },
                    sessionIdFactory = { "event-single" },
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
            viewModel.selectDevice(selected.key)
            runCurrent()
            poseSource.emit(pose(clock.nowNanos()))
            scanner.tracking.emit(packet(selected, clock.nowNanos(), -60))
            runCurrent()

            engine.current = localizedEstimate()
            clock.value += 1L
            scanner.tracking.emit(packet(selected, clock.nowNanos(), -59))
            scanner.tracking.emit(packet(selected, clock.nowNanos() + 1L, -58))
            runCurrent()

            clock.value = 7_000_000_000L
            advanceTimeBy(2_001L)
            runCurrent()
            scanner.tracking.emit(packet(selected, clock.nowNanos(), -57))
            runCurrent()
            poseSource.emitStatus(
                ArStatus(
                    ArRuntimeStatus.ERROR,
                    "Test beacon AA:BB:CC:DD:EE:FF private exception",
                    recoverable = true,
                ),
            )
            poseSource.emitStatus(ArStatus(ArRuntimeStatus.ERROR, "different private detail", true))
            runCurrent()

            viewModel.completeTrace(TraceOutcome.YES)
            runCurrent()

            val lines = viewModel.state.value.lastTraceFile!!.readLines()
            val events = lines.map(JsonParser::parseString)
                .map { it.asJsonObject }
                .filter { it["record_type"].asString == "event" }
            assertEquals(
                listOf(
                    "ar_state" to "TRACKING",
                    "scanner_state" to "SINGLE_TARGET_ACTIVE",
                    "estimator_state_changed" to ESTIMATE_STATUS_COLLECTING,
                    "estimator_state_changed" to ESTIMATE_STATUS_EKF,
                    "signal_lost" to null,
                    "signal_recovered" to null,
                    "ar_state" to "ERROR",
                    "recoverable_failure" to "AR",
                    "scanner_state" to "STOPPED",
                ),
                events.map {
                    it["code"].asString to
                        it["detail"].takeUnless { detail -> detail.isJsonNull }?.asString
                },
            )
            assertTrue(
                lines.indexOfFirst { it.contains("\"sequence\":0") } <
                    lines.indexOfFirst {
                        it.contains("\"code\":\"estimator_state_changed\"") &&
                            it.contains(ESTIMATE_STATUS_COLLECTING)
                    },
            )
            assertTrue(
                lines.indexOfLast { it.contains("\"record_type\":\"observation\"") } <
                    lines.indexOfFirst { it.contains("\"code\":\"signal_recovered\"") },
            )
            val payload = lines.joinToString("\n")
            assertFalse(payload.contains("Test beacon"))
            assertFalse(payload.contains("AA:BB:CC:DD:EE:FF"))
            assertFalse(payload.contains("private exception"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `AR event vocabulary covers every public state without raw detail`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val selected = target("ar-vocabulary")
            val poseSource = FakePoseSource()
            val viewModel = LocatorViewModel(
                scanner = FakeScanner(packet(selected, 1_000_000_000L, -65)),
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    temporaryFolder.newFolder("ar-vocabulary"),
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { 0L },
                    sessionIdFactory = { "ar-vocabulary" },
                ),
                clock = FakeClock(1_000_000_000L),
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.bindPoseSource(poseSource)
            viewModel.setRuntimeReady(true)
            runCurrent()
            viewModel.selectDevice(selected.key)
            runCurrent()

            ArRuntimeStatus.entries.forEach { status ->
                repeat(2) {
                    poseSource.emitStatus(
                        ArStatus(status, "raw-$status-AA:BB:CC:DD:EE:FF"),
                    )
                    runCurrent()
                }
            }
            viewModel.completeTrace(TraceOutcome.SKIPPED)
            runCurrent()

            val payload = viewModel.state.value.lastTraceFile!!.readText()
            val arStates = payload.lineSequence()
                .filter(String::isNotBlank)
                .map(JsonParser::parseString)
                .map { it.asJsonObject }
                .filter {
                    it["record_type"].asString == "event" &&
                        it["code"].asString == "ar_state"
                }
                .map { it["detail"].asString }
                .toList()
            assertEquals(
                listOf(ArRuntimeStatus.TRACKING.name) +
                    ArRuntimeStatus.entries.map(Enum<*>::name),
                arStates,
            )
            assertFalse(payload.contains("raw-"))
            assertFalse(payload.contains("AA:BB:CC:DD:EE:FF"))
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
    fun `finished session rejects later events and new session starts with current state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val selected = target("session-events")
                val scanner = FakeScanner(packet(selected, 1_000_000_000L, -65))
                val poseSource = FakePoseSource()
                var session = 0
                val recorder = TraceRecorder(
                    temporaryFolder.newFolder("session-events"),
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { 0L },
                    sessionIdFactory = { "session-events-${++session}" },
                )
                val viewModel = LocatorViewModel(
                    scanner = scanner,
                    locationEngine = FakeLocationEngine(),
                    synchronizer = SampleSynchronizer(),
                    traceRecorder = recorder,
                    clock = FakeClock(1_000_000_000L),
                    appVersion = "test",
                    deviceModel = "fake",
                    computationDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
                viewModel.bindPoseSource(poseSource)
                viewModel.setRuntimeReady(true)
                runCurrent()
                viewModel.selectDevice(selected.key)
                runCurrent()

                viewModel.completeTrace(TraceOutcome.YES)
                poseSource.emitStatus(ArStatus(ArRuntimeStatus.READY, "private after finish"))
                runCurrent()
                viewModel.selectDevice(selected.key)
                runCurrent()
                viewModel.completeTrace(TraceOutcome.SKIPPED)
                runCurrent()

                val traces = recorder.completedTraces().associateBy { file ->
                    JsonParser.parseString(file.readLines().first())
                        .asJsonObject["session_id"].asString
                }
                val first = traces.getValue("session-events-1").readText()
                val second = traces.getValue("session-events-2").readText()
                assertFalse(first.contains("\"detail\":\"READY\""))
                assertEquals(
                    "outcome",
                    JsonParser.parseString(first.lineSequence().last(String::isNotBlank))
                        .asJsonObject["record_type"].asString,
                )
                assertTrue(second.contains("\"code\":\"ar_state\",\"detail\":\"READY\""))
                assertFalse(second.contains("private after finish"))
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
                        viewModel.completeTrace(TraceOutcome.SKIPPED)
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
            assertTrue(payload.contains("\"outcome\":\"SKIPPED\""))
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

            viewModel.completeTrace(TraceOutcome.SKIPPED)
            runCurrent()

            assertEquals(AppScreen.DEVICES, viewModel.state.value.screen)
            assertEquals(
                "The session could not be saved",
                viewModel.state.value.recoverableError,
            )
            assertTrue(
                temporaryFolder.root.listFiles().orEmpty()
                    .any { it.name.endsWith(".active.ndjson") },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `complete scan hard entitlement gate permits only confirmed and cached Pro`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            EntitlementState.entries.forEach { entitlement ->
                val directory = temporaryFolder.newFolder("entitlement-${entitlement.name}")
                val target = target("entitlement")
                val scanner = FakeScanner(packet(target, at = 1_000_000_000L, rssi = -65))
                val viewModel = LocatorViewModel(
                    scanner = scanner,
                    locationEngine = FakeLocationEngine(),
                    synchronizer = SampleSynchronizer(),
                    traceRecorder = TraceRecorder(
                        directory,
                        sessionIdFactory = { entitlement.name.lowercase() },
                    ),
                    clock = FakeClock(1_000_000_000L),
                    appVersion = "test",
                    deviceModel = "fake",
                    completeScanEntitlement = { entitlement },
                    computationDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
                viewModel.setRuntimeReady(true)
                runCurrent()

                val result = viewModel.startCompleteScan()
                runCurrent()

                val allowed = entitlement == EntitlementState.PRO_CACHED ||
                    entitlement == EntitlementState.PRO_CONFIRMED
                assertEquals(
                    if (allowed) {
                        CompleteScanStartResult.STARTED
                    } else {
                        CompleteScanStartResult.REQUIRES_PRO
                    },
                    result,
                )
                assertEquals(if (allowed) 1 else 0, scanner.completeScanCalls)
                assertEquals(
                    if (allowed) AppScreen.COMPLETE_SCAN else AppScreen.DEVICES,
                    viewModel.state.value.screen,
                )
                if (allowed) {
                    viewModel.stopAndReturnToDevices()
                    runCurrent()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `complete scan keeps independent pipelines updating while focus and staleness are UI only`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val clock = FakeClock(1_000_000_000L)
                val discoveryTarget = target("picker")
                val scanner = FakeScanner(
                    packet(discoveryTarget, at = clock.nowNanos(), rssi = -65),
                )
                val engines = mutableListOf<FakeLocationEngine>()
                val localized = localizedEstimate()
                val poseSource = FakePoseSource()
                val viewModel = LocatorViewModel(
                    scanner = scanner,
                    locationEngine = FakeLocationEngine(),
                    synchronizer = SampleSynchronizer(),
                    traceRecorder = TraceRecorder(
                        temporaryFolder.newFolder("complete-pipelines"),
                        wallClockMillis = { 0L },
                        monotonicClockNanos = { clock.nowNanos() },
                        sessionIdFactory = { "complete-pipelines" },
                    ),
                    clock = clock,
                    appVersion = "test",
                    deviceModel = "fake",
                    completeScanEntitlement = { EntitlementState.PRO_CONFIRMED },
                    locationEngineFactory = {
                        FakeLocationEngine(
                            if (engines.isEmpty()) localized else collectingEstimate(),
                        ).also(engines::add)
                    },
                    synchronizerFactory = { SampleSynchronizer() },
                    computationDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
                viewModel.bindPoseSource(poseSource)
                viewModel.setRuntimeReady(true)
                runCurrent()
                assertEquals(
                    CompleteScanStartResult.STARTED,
                    viewModel.startCompleteScan(),
                )
                runCurrent()

                val ble = target(
                    key = "D01",
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "Beacon",
                    transport = BluetoothTransport.LOW_ENERGY,
                )
                val classic = target(
                    key = "D02",
                    address = "AA:BB:CC:DD:EE:FF",
                    name = null,
                    transport = BluetoothTransport.CLASSIC,
                )
                poseSource.emit(pose(at = clock.nowNanos()))
                runCurrent()
                repeat(3) {
                    scanner.tracking.emit(packet(ble, clock.nowNanos() + it, -60))
                    runCurrent()
                }
                viewModel.focusCompleteScanDevice(ble.key)

                clock.value = 10_000_000_000L
                poseSource.emit(pose(at = clock.nowNanos(), x = 1.0))
                runCurrent()
                repeat(3) {
                    scanner.tracking.emit(packet(classic, clock.nowNanos() + it, -70))
                    runCurrent()
                }

                assertEquals(2, engines.size)
                assertEquals(listOf(3, 3), engines.map { it.updates })
                assertEquals(
                    listOf(3, 3),
                    viewModel.state.value.completeScanDevices.map { it.metrics.acceptedCount },
                )
                assertEquals(ble.key, viewModel.state.value.focusedDeviceKey)
                assertEquals(2, viewModel.state.value.breadcrumbs.size)
                assertEquals(1.0, viewModel.state.value.metrics.pathLengthMetres, 0.0001)

                clock.value = 32_000_000_000L
                advanceTimeBy(1_001L)
                runCurrent()
                val freshness = viewModel.state.value.completeScanDevices.associateBy { it.key }
                assertTrue(freshness.getValue(ble.key).lastSeen)
                assertFalse(freshness.getValue(classic.key).lastSeen)

                poseSource.emit(pose(at = clock.nowNanos(), x = 2.0))
                runCurrent()
                scanner.tracking.emit(packet(classic, clock.nowNanos(), -69))
                runCurrent()
                assertEquals(4, engines[1].updates)
                assertEquals(4, viewModel.state.value.completeScanDevices[1].metrics.receivedCount)
                assertEquals(ble.key, viewModel.state.value.focusedDeviceKey)
                scanner.tracking.emit(packet(ble, clock.nowNanos() + 1L, -59))
                runCurrent()

                viewModel.completeTrace(TraceOutcome.YES)
                runCurrent()
                val trace = viewModel.state.value.lastTraceFile!!
                val payload = trace.readText()
                assertTrue(payload.contains("\"mode\":\"COMPLETE_SCAN\""))
                assertTrue(payload.contains("\"observed_device_count\":2"))
                assertTrue(payload.contains("\"localized_device_count\":1"))
                assertTrue(payload.contains("\"device_key\":\"D01\""))
                assertTrue(payload.contains("\"device_key\":\"D02\""))
                assertTrue(
                    payload.contains(
                        "\"code\":\"scanner_state\",\"detail\":\"COMPLETE_SCAN_ACTIVE\"",
                    ),
                )
                assertEquals(
                    2,
                    "\"code\":\"estimator_state_changed\"".toRegex().findAll(payload).count(),
                )
                assertEquals(1, "\"code\":\"signal_lost\"".toRegex().findAll(payload).count())
                assertEquals(1, "\"code\":\"signal_recovered\"".toRegex().findAll(payload).count())
                assertTrue(
                    payload.contains(
                        "\"device_key\":\"D01\",\"transport\":\"low_energy\"," +
                            "\"code\":\"signal_recovered\"",
                    ),
                )
                assertFalse(payload.contains("AA:BB:CC:DD:EE:FF"))
                assertFalse(payload.contains("Beacon"))
                assertEquals(AppScreen.DEVICES, viewModel.state.value.screen)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `complete scan publishes a stable snapshot while another device arrives`() {
        try {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val io = QueuedDispatcher()
                Dispatchers.setMain(dispatcher)
                val clock = FakeClock(1_000_000_000L)
                val scanner = FakeScanner(packet(target("picker"), clock.nowNanos(), -65))
                val engines = mutableListOf<FakeLocationEngine>()
                val viewModel = LocatorViewModel(
                    scanner = scanner,
                    locationEngine = FakeLocationEngine(),
                    synchronizer = SampleSynchronizer(),
                    traceRecorder = TraceRecorder(
                        temporaryFolder.newFolder("complete-snapshot"),
                        monotonicClockNanos = clock::nowNanos,
                        sessionIdFactory = { "complete-snapshot" },
                    ),
                    clock = clock,
                    appVersion = "test",
                    deviceModel = "fake",
                    completeScanEntitlement = { EntitlementState.PRO_CONFIRMED },
                    locationEngineFactory = { FakeLocationEngine().also(engines::add) },
                    computationDispatcher = dispatcher,
                    ioDispatcher = io,
                )
                viewModel.setRuntimeReady(true)
                repeat(2) {
                    runCurrent()
                    io.runQueued()
                }
                viewModel.startCompleteScan()
                repeat(3) {
                    runCurrent()
                    io.runQueued()
                }

                val targets = (1..3).map {
                    target(
                        key = "D0$it",
                        address = "AA:BB:CC:DD:EE:0$it",
                        name = "Device $it",
                        transport = BluetoothTransport.LOW_ENERGY,
                    )
                }
                targets.take(2).forEach {
                    scanner.tracking.emit(packet(it, clock.nowNanos(), -60))
                    repeat(10) {
                        runCurrent()
                        io.runQueued()
                    }
                }
                assertEquals(2, viewModel.state.value.completeScanDevices.size)

                advanceTimeBy(1_001L)
                runCurrent()
                assertTrue(io.hasQueued())
                scanner.tracking.emit(packet(targets.last(), clock.nowNanos(), -60))
                runCurrent()
                assertEquals(3, engines.size)
                io.runQueued()
                runCurrent()
                repeat(10) {
                    io.runQueued()
                    runCurrent()
                }

                assertEquals(3, viewModel.state.value.completeScanDevices.size)
                viewModel.stopAndReturnToDevices()
                viewModel.onAppStopped()
                repeat(10) {
                    io.runQueued()
                    runCurrent()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `lifecycle stop cancels active scanners finalizes traces and resumes discovery`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                listOf(false, true).forEach { completeScan ->
                    val mode = if (completeScan) "complete" else "single"
                    val directory = temporaryFolder.newFolder("lifecycle-$mode")
                    val clock = FakeClock(1_000_000_000L)
                    val selected = target("lifecycle-$mode")
                    val scanner = FakeScanner(packet(selected, clock.nowNanos(), -65))
                    val poseSource = FakePoseSource()
                    val viewModel = LocatorViewModel(
                        scanner = scanner,
                        locationEngine = FakeLocationEngine(),
                        synchronizer = SampleSynchronizer(),
                        traceRecorder = TraceRecorder(
                            directory,
                            wallClockMillis = { 0L },
                            monotonicClockNanos = { clock.nowNanos() },
                            sessionIdFactory = { "lifecycle-$mode" },
                        ),
                        clock = clock,
                        appVersion = "test",
                        deviceModel = "fake",
                        completeScanEntitlement = { EntitlementState.PRO_CONFIRMED },
                        computationDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                    )
                    viewModel.bindPoseSource(poseSource)
                    viewModel.setRuntimeReady(true)
                    runCurrent()
                    if (completeScan) {
                        viewModel.startCompleteScan()
                    } else {
                        viewModel.selectDevice(selected.key)
                    }
                    runCurrent()
                    poseSource.emit(pose(clock.nowNanos(), x = 0.0))
                    clock.value += 1_000_000_000L
                    poseSource.emit(pose(clock.nowNanos(), x = 1.5))
                    runCurrent()

                    viewModel.onAppStopped()
                    runCurrent()

                    val payload = viewModel.state.value.lastTraceFile!!.readText()
                    assertTrue(payload.contains("\"outcome\":\"INCOMPLETE\""))
                    assertTrue(payload.contains("\"path_length_m\":1.5"))
                    assertEquals(
                        viewModel.state.value.lastTraceFile,
                        viewModel.traceStore.summaries.value.single().file,
                    )
                    assertNull(viewModel.traceStore.error.value)
                    assertEquals(
                        1,
                        if (completeScan) {
                            scanner.completeScanCancellations
                        } else {
                            scanner.trackCancellations
                        },
                    )
                    assertEquals(AppScreen.DEVICES, viewModel.state.value.screen)

                    viewModel.onAppStarted()
                    runCurrent()
                    assertEquals(2, scanner.discoveryCalls)
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `lifecycle stop cancels discovery and resume starts it only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val selected = target("discovery-lifecycle")
            val scanner = FakeScanner(packet(selected, 1_000_000_000L, -65)).apply {
                keepDiscoveryActive = true
            }
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(temporaryFolder.newFolder("discovery-lifecycle")),
                clock = FakeClock(1_000_000_000L),
                appVersion = "test",
                deviceModel = "fake",
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.setRuntimeReady(true)
            runCurrent()

            viewModel.onAppStopped()
            runCurrent()
            assertEquals(1, scanner.discoveryCancellations)
            assertFalse(viewModel.state.value.isDiscovering)

            viewModel.onAppStarted()
            runCurrent()
            viewModel.onAppStarted()
            runCurrent()

            assertEquals(2, scanner.discoveryCalls)
            assertTrue(viewModel.state.value.isDiscovering)
            viewModel.onAppStopped()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `history publishes newest ten and refreshes after delete`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var session = 0
            val recorder = TraceRecorder(
                traceDirectory = temporaryFolder.root,
                wallClockMillis = { session.toLong() },
                monotonicClockNanos = { session.toLong() },
                sessionIdFactory = { "history-${++session}" },
            )
            repeat(11) {
                recorder.startCompleteScan("1", "phone")
                recorder.finish(TraceOutcome.SKIPPED)
            }
            val viewModel = viewModel(recorder, dispatcher)
            runCurrent()

            assertEquals(10, viewModel.traceStore.summaries.value.size)
            assertTrue(viewModel.traceStore.summaries.value.first().file.name.endsWith("-history-11.ndjson"))

            viewModel.traceStore.deleteTrace(viewModel.traceStore.summaries.value.first().file)
            runCurrent()

            assertEquals(9, viewModel.traceStore.summaries.value.size)
            assertNull(viewModel.traceStore.error.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Complete Scan runtime failure preserves path and hides exception text`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val clock = FakeClock(1_000_000_000L)
            val selected = target("runtime-failure")
            val scanner = FakeScanner(packet(selected, clock.nowNanos(), -65)).apply {
                completeFailure = IllegalStateException("private driver exception")
            }
            val poseSource = FakePoseSource()
            val viewModel = LocatorViewModel(
                scanner = scanner,
                locationEngine = FakeLocationEngine(),
                synchronizer = SampleSynchronizer(),
                traceRecorder = TraceRecorder(
                    temporaryFolder.newFolder("runtime-failure"),
                    wallClockMillis = { 0L },
                    monotonicClockNanos = { clock.nowNanos() },
                    sessionIdFactory = { "runtime-failure" },
                ),
                clock = clock,
                appVersion = "test",
                deviceModel = "fake",
                completeScanEntitlement = { EntitlementState.PRO_CONFIRMED },
                computationDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
            viewModel.bindPoseSource(poseSource)
            viewModel.setRuntimeReady(true)
            runCurrent()
            viewModel.startCompleteScan()
            runCurrent()
            poseSource.emit(pose(clock.nowNanos(), x = 0.0))
            clock.value += 1_000_000_000L
            poseSource.emit(pose(clock.nowNanos(), x = 2.0))
            runCurrent()

            advanceTimeBy(101L)
            runCurrent()

            val error = viewModel.state.value.recoverableError!!
            assertTrue(error.contains("Bluetooth scanning failed"))
            assertFalse(error.contains("private driver exception"))
            val payload = viewModel.state.value.lastTraceFile!!.readText()
            assertTrue(payload.contains("\"outcome\":\"INCOMPLETE\""))
            assertTrue(payload.contains("\"path_length_m\":2.0"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed history deletion keeps item visible with stable error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var failDelete = false
            val recorder = TraceRecorder(
                traceDirectory = temporaryFolder.root,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "delete-failure" },
                deleteFile = { file -> !failDelete && file.delete() },
            )
            recorder.startCompleteScan("1", "phone")
            recorder.finish(TraceOutcome.SKIPPED)
            failDelete = true
            val viewModel = viewModel(recorder, dispatcher)
            runCurrent()
            val file = viewModel.traceStore.summaries.value.single().file

            viewModel.traceStore.deleteTrace(file)
            runCurrent()

            assertEquals(file, viewModel.traceStore.summaries.value.single().file)
            assertEquals("The session could not be deleted", viewModel.traceStore.error.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        recorder: TraceRecorder,
        dispatcher: CoroutineDispatcher,
    ) = LocatorViewModel(
        scanner = FakeScanner(packet(target("history"), at = 0L, rssi = -65)),
        locationEngine = FakeLocationEngine(),
        synchronizer = SampleSynchronizer(),
        traceRecorder = recorder,
        clock = FakeClock(0L),
        appVersion = "test",
        deviceModel = "fake",
        computationDispatcher = dispatcher,
        ioDispatcher = dispatcher,
    )

    private class FakeClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runQueued() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        fun hasQueued(): Boolean = tasks.isNotEmpty()
    }

    private class FakeScanner(private val discoveryPacket: ScanPacket) : BleScanner {
        val tracking = MutableSharedFlow<ScanPacket>(extraBufferCapacity = 4)
        var keepDiscoveryActive = false
        var completeFailure: Throwable? = null
        var discoveryCalls = 0
        var discoveryCancellations = 0
        var trackCalls = 0
        var trackCancellations = 0
        var completeScanCalls = 0
        var completeScanCancellations = 0
        override fun availability(): BleAvailability = BleAvailability.Available
        override fun discover(durationMillis: Long): Flow<ScanPacket> = flow {
            discoveryCalls += 1
            emit(discoveryPacket)
            if (keepDiscoveryActive) {
                try {
                    awaitCancellation()
                } finally {
                    discoveryCancellations += 1
                }
            }
        }
        override fun track(target: TargetHandle): Flow<ScanPacket> {
            trackCalls += 1
            return flow {
                try {
                    tracking.collect(::emit)
                } finally {
                    trackCancellations += 1
                }
            }
        }
        override fun completeScan(): Flow<ScanPacket> {
            completeScanCalls += 1
            return flow {
                try {
                    completeFailure?.let {
                        delay(100L)
                        throw it
                    }
                    tracking.collect(::emit)
                } finally {
                    completeScanCancellations += 1
                }
            }
        }
    }

    private class FakePoseSource : PoseSource {
        private val mutablePoses = MutableSharedFlow<PoseObservation>(extraBufferCapacity = 4)
        override val poses = mutablePoses
        override val status = MutableStateFlow(ArStatus(ArRuntimeStatus.TRACKING))
        suspend fun emit(pose: PoseObservation) = mutablePoses.emit(pose)
        suspend fun emitStatus(value: ArStatus) = status.emit(value)
    }

    private class FakeLocationEngine(
        var current: EstimateUiModel = collectingEstimate(),
    ) : LocationEngine {
        var updates = 0

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
            transport = target.transport,
        ),
    )

    private fun target(key: String) = TargetHandle(
        DeviceKey(key),
        "AA:BB:CC:DD:EE:FF",
        "Test beacon",
        emptySet(),
        emptySet(),
    )

    private fun target(
        key: String,
        address: String,
        name: String?,
        transport: BluetoothTransport,
    ) = TargetHandle(
        DeviceKey(key),
        address,
        name,
        emptySet(),
        emptySet(),
        transport,
    )

    private fun pose(at: Long, x: Double = 0.0) = PoseObservation(
        capturedAtPlatformNanos = 123L,
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

    private companion object {
        fun collectingEstimate() = EstimateUiModel(
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

        fun localizedEstimate() = EstimateUiModel(
            status = ESTIMATE_STATUS_EKF,
            meanXMetres = 2.0,
            meanZMetres = 3.0,
            covarianceXX = 1.0,
            covarianceXZ = 0.0,
            covarianceZZ = 1.0,
            regionProbability = 0.9,
            regionAreaSquareMetres = 5.0,
            ambiguity = 0.1,
            probabilityCells = listOf(ProbabilityCell(2.0, 3.0, 1.0, 0.5)),
        )
    }
}
