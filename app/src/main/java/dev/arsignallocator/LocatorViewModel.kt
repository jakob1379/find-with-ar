package dev.arsignallocator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.arsignallocator.ble.BleAvailability
import dev.arsignallocator.ble.BleScanner
import dev.arsignallocator.ble.RadioIdentity
import dev.arsignallocator.ble.ScanPacket
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.localization.LocationEngine
import dev.arsignallocator.localization.LocalizationAdapter
import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.CompleteScanDevice
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.ESTIMATE_STATUS_EKF
import dev.arsignallocator.model.ESTIMATE_STATUS_GRID_MULTIMODAL
import dev.arsignallocator.model.ESTIMATE_STATUS_GRID_UNIMODAL
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.model.TrackingMetrics
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.allowsCompleteScan
import dev.arsignallocator.ar.PoseSource
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceMode
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceOutcomeReason
import dev.arsignallocator.trace.TraceSummary
import dev.arsignallocator.trace.TraceSummaryReader
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocatorViewModel(
    private val scanner: BleScanner,
    private val locationEngine: LocationEngine,
    private val synchronizer: SampleSynchronizer,
    private val traceRecorder: TraceRecorder,
    private val clock: MonotonicClock,
    private val appVersion: String,
    private val deviceModel: String,
    private val completeScanEntitlement: () -> EntitlementState = { EntitlementState.FREE },
    private val locationEngineFactory: () -> LocationEngine = { LocalizationAdapter() },
    private val synchronizerFactory: () -> SampleSynchronizer = { SampleSynchronizer() },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(LocatorUiState())
    val state: StateFlow<LocatorUiState> = _state.asStateFlow()
    private val _traceSummaries = MutableStateFlow<List<TraceSummary>>(emptyList())
    val traceSummaries: StateFlow<List<TraceSummary>> = _traceSummaries.asStateFlow()
    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    private var runtimeReady = false
    private var discoveryJob: Job? = null
    private var scanJob: Job? = null
    private var lostTargetJob: Job? = null
    private var poseJob: Job? = null
    private var arStatusJob: Job? = null
    private var traceActive = false
    private var appInForeground = true
    @Volatile
    private var traceOperation = 0L
    private val traceMutex = Mutex()
    private val candidateAccumulators = linkedMapOf<DeviceKey, CandidateAccumulator>()
    private var candidateOrder = emptyList<DeviceKey>()
    private var lastBreadcrumb: PoseObservation? = null
    private var latestPose: PoseObservation? = null
    private val completePipelines = linkedMapOf<RadioIdentity, CompleteDevicePipeline>()

    init {
        viewModelScope.launch {
            val operation = traceOperation
            val initialized = runCatching {
                traceIo { historySnapshot(traceRecorder.initialize()) }
            }
            if (operation == traceOperation) {
                val snapshot = initialized.getOrNull()
                _traceSummaries.value = snapshot?.summaries.orEmpty()
                _historyError.value = when {
                    initialized.isFailure -> HISTORY_READ_ERROR
                    snapshot?.hasInvalidTrace == true -> HISTORY_READ_ERROR
                    traceRecorder.storageError != null -> HISTORY_READ_ERROR
                    else -> null
                }
                _state.update {
                    it.copy(
                        lastTraceFile = snapshot?.files?.firstOrNull(),
                        recoverableError = _historyError.value,
                    )
                }
            }
        }
    }

    fun bindPoseSource(source: PoseSource) {
        poseJob?.cancel()
        arStatusJob?.cancel()
        poseJob = viewModelScope.launch {
            source.poses.collect(::onPose)
        }
        arStatusJob = viewModelScope.launch {
            source.status.collect { status ->
                _state.update { current ->
                    current.copy(arStatus = status, guidance = guidance(current.copy(arStatus = status)))
                }
            }
        }
    }

    fun setRuntimeReady(ready: Boolean) {
        if (runtimeReady == ready) return
        runtimeReady = ready
        if (!ready) {
            stopRuntimeWork("Permissions or Bluetooth are unavailable")
        } else if (appInForeground && _state.value.screen == AppScreen.DEVICES) {
            startDiscovery()
        }
    }

    fun startDiscovery() {
        if (!runtimeReady || !appInForeground) return
        when (val availability = scanner.availability()) {
            BleAvailability.Available -> Unit
            BleAvailability.Disabled -> return fail("Bluetooth is turned off")
            BleAvailability.Unsupported -> return fail("This phone does not support Bluetooth scanning")
            is BleAvailability.PermissionDenied ->
                return fail("Nearby-device permission is unavailable")
        }

        stopTrackingJobs()
        discoveryJob?.cancel()
        candidateAccumulators.clear()
        candidateOrder = updateCandidateOrder(
            previous = candidateOrder,
            candidates = emptyList(),
            discoveryActive = true,
            freshScan = true,
        )
        _state.update {
            it.copy(
                screen = AppScreen.DEVICES,
                isDiscovering = true,
                discoverySecondsRemaining = DISCOVERY_SECONDS,
                candidates = emptyList(),
                selected = null,
                recoverableError = null,
                guidance = "Scanning for nearby Bluetooth devices",
            )
        }
        discoveryJob = viewModelScope.launch {
            val countdown = launch {
                for (remaining in DISCOVERY_SECONDS downTo 1) {
                    _state.update { it.copy(discoverySecondsRemaining = remaining) }
                    delay(1_000L)
                }
            }
            scanner.discover(DISCOVERY_SECONDS * 1_000L)
                .catch { fail("Nearby-device scanning stopped. Try again") }
                .collect(::onDiscovered)
            countdown.cancel()
            val rankedCandidates = orderCandidates(
                _state.value.candidates,
                discoveryActive = false,
            )
            _state.update {
                it.copy(
                    isDiscovering = false,
                    discoverySecondsRemaining = 0,
                    candidates = rankedCandidates,
                    guidance = if (rankedCandidates.isEmpty()) {
                        "No observable Bluetooth device found. Wake it, enable pairing mode, and retry"
                    } else {
                        "Select the device you want to locate"
                    },
                )
            }
        }
    }

    fun selectDevice(key: DeviceKey) {
        val candidate = _state.value.candidates.firstOrNull { it.key == key } ?: return
        val operation = ++traceOperation
        discoveryJob?.cancel()
        discoveryJob = null
        synchronizer.reset()
        locationEngine.reset()
        lastBreadcrumb = null
        latestPose = null
        val previousTrace = _state.value.lastTraceFile
        _state.value = LocatorUiState(
            screen = AppScreen.TRACKING,
            selected = candidate,
            isDiscovering = false,
            arStatus = _state.value.arStatus,
            guidance = "Signal found. Walk sideways while keeping the camera uncovered",
            recoverableError = _historyError.value,
            lastTraceFile = previousTrace,
        )
        viewModelScope.launch {
            val traceError = runCatching {
                traceIo {
                    if (operation != traceOperation) return@traceIo
                    traceRecorder.start(appVersion, deviceModel, candidate.handle)
                }
            }.exceptionOrNull()?.let { TRACE_START_ERROR }
            if (operation != traceOperation) return@launch
            traceActive = traceError == null
            _state.update {
                it.copy(recoverableError = traceError)
            }
            startSelectedScan(candidate)
        }
    }

    fun startCompleteScan(): CompleteScanStartResult {
        if (_state.value.screen != AppScreen.DEVICES) {
            return CompleteScanStartResult.UNAVAILABLE
        }
        if (!completeScanEntitlement().allowsCompleteScan()) {
            return CompleteScanStartResult.REQUIRES_PRO
        }
        if (!runtimeReady) {
            fail("Permissions or Bluetooth are unavailable")
            return CompleteScanStartResult.UNAVAILABLE
        }
        when (val availability = scanner.availability()) {
            BleAvailability.Available -> Unit
            BleAvailability.Disabled -> {
                fail("Bluetooth is turned off")
                return CompleteScanStartResult.UNAVAILABLE
            }
            BleAvailability.Unsupported -> {
                fail("This phone does not support Bluetooth scanning")
                return CompleteScanStartResult.UNAVAILABLE
            }
            is BleAvailability.PermissionDenied -> {
                fail("Nearby-device permission is unavailable")
                return CompleteScanStartResult.UNAVAILABLE
            }
        }

        val operation = ++traceOperation
        discoveryJob?.cancel()
        discoveryJob = null
        stopTrackingJobs()
        traceActive = false
        lastBreadcrumb = null
        latestPose = null
        val previous = _state.value
        _state.value = LocatorUiState(
            screen = AppScreen.COMPLETE_SCAN,
            arStatus = previous.arStatus,
            guidance = "Move around the room to collect signal from different positions",
            recoverableError = _historyError.value,
            lastTraceFile = previous.lastTraceFile,
        )
        viewModelScope.launch {
            val traceError = runCatching {
                traceIo {
                    if (operation != traceOperation) return@traceIo
                    traceRecorder.start(
                        appVersion,
                        deviceModel,
                        TraceMode.COMPLETE_SCAN,
                    )
                }
            }.exceptionOrNull()?.let { TRACE_START_ERROR }
            if (operation != traceOperation) return@launch
            traceActive = traceError == null
            _state.update {
                it.copy(recoverableError = traceError)
            }
            if (traceError == null) startCompleteScanner()
        }
        return CompleteScanStartResult.STARTED
    }

    fun focusCompleteScanDevice(key: DeviceKey) {
        if (_state.value.completeScanDevices.none { it.key == key }) return
        _state.update { it.copy(focusedDeviceKey = key) }
    }

    fun showTracking() {
        if (_state.value.selected != null) _state.update { it.copy(screen = AppScreen.TRACKING) }
    }

    fun showDebug() {
        if (_state.value.selected != null) _state.update { it.copy(screen = AppScreen.DEBUG) }
    }

    fun stopAndReturnToDevices() = completeTrace(TraceOutcome.INCOMPLETE)

    fun onAppStopped() {
        appInForeground = false
        when (_state.value.screen) {
            AppScreen.TRACKING, AppScreen.DEBUG, AppScreen.COMPLETE_SCAN ->
                completeTrace(TraceOutcome.INCOMPLETE)
            AppScreen.DEVICES -> {
                discoveryJob?.cancel()
                discoveryJob = null
                _state.update {
                    it.copy(
                        isDiscovering = false,
                        discoverySecondsRemaining = 0,
                    )
                }
            }
        }
    }

    fun onAppStarted() {
        appInForeground = true
        if (
            runtimeReady &&
            _state.value.screen == AppScreen.DEVICES &&
            discoveryJob?.isActive != true
        ) {
            startDiscovery()
        }
    }

    fun completeTrace(
        outcome: TraceOutcome,
        reason: TraceOutcomeReason? = null,
        note: String? = null,
    ) {
        val operation = ++traceOperation
        val pathLengthMetres = _state.value.metrics.pathLengthMetres
        stopTrackingJobs()
        traceActive = false
        _state.update {
            LocatorUiState(
                screen = AppScreen.DEVICES,
                arStatus = it.arStatus,
                lastTraceFile = it.lastTraceFile,
                guidance = "Select a device to begin",
            )
        }
        viewModelScope.launch {
            val (result, history) = traceIo {
                val completed = traceRecorder.finish(outcome, reason, note, pathLengthMetres)
                completed to historySnapshot(traceRecorder.completedTraces())
            }
            _traceSummaries.value = history.summaries
            _historyError.value = when {
                result.error != null -> TRACE_FINISH_ERROR
                history.hasInvalidTrace -> HISTORY_READ_ERROR
                else -> null
            }
            if (operation != traceOperation) return@launch
            _state.update {
                it.copy(
                    lastTraceFile = result.file ?: it.lastTraceFile,
                    recoverableError = _historyError.value,
                )
            }
        }
        if (runtimeReady) startDiscovery()
    }

    fun deleteTrace(file: File) {
        viewModelScope.launch {
            val (deleted, history) = traceIo {
                val removed = traceRecorder.deleteTrace(file)
                removed to historySnapshot(traceRecorder.completedTraces())
            }
            _traceSummaries.value = history.summaries
            _historyError.value = when {
                !deleted -> TRACE_DELETE_ERROR
                history.hasInvalidTrace -> HISTORY_READ_ERROR
                else -> null
            }
            _state.update {
                it.copy(
                    lastTraceFile = history.files.firstOrNull(),
                    recoverableError = _historyError.value,
                )
            }
        }
    }

    fun deleteLocalTraces() {
        viewModelScope.launch {
            val history = traceIo {
                traceRecorder.deleteAllTraces()
                historySnapshot(traceRecorder.completedTraces())
            }
            _traceSummaries.value = history.summaries
            _historyError.value = when {
                traceRecorder.storageError != null -> TRACE_DELETE_ALL_ERROR
                history.hasInvalidTrace -> HISTORY_READ_ERROR
                else -> null
            }
            _state.update {
                it.copy(
                    lastTraceFile = history.files.firstOrNull(),
                    recoverableError = _historyError.value,
                )
            }
        }
    }

    private fun startSelectedScan(candidate: DeviceCandidate) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.track(candidate.handle)
                .catch { fail("Device tracking stopped. Try the scan again") }
                .collect { packet -> processTrackedPacket(packet) }
        }
        lostTargetJob?.cancel()
        lostTargetJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                _state.update { current -> current.copy(guidance = guidance(current)) }
            }
        }
    }

    private fun startCompleteScanner() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.completeScan()
                .catch {
                    stopRuntimeWork("Complete Scan stopped because Bluetooth scanning failed")
                }
                .collect(::processCompleteScanPacket)
        }
        lostTargetJob?.cancel()
        lostTargetJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                publishCompleteScanDevices()
            }
        }
    }

    private fun onDiscovered(packet: ScanPacket) {
        val accumulator = candidateAccumulators.getOrPut(packet.observation.deviceKey) {
            CandidateAccumulator(packet)
        }
        accumulator.add(packet)
        val now = clock.nowNanos()
        val orderedCandidates = orderCandidates(
            candidateAccumulators.values.map { accumulatorValue ->
                accumulatorValue.snapshot(now)
            },
            discoveryActive = true,
        )
        _state.update {
            it.copy(
                candidates = orderedCandidates,
            )
        }
    }

    private fun orderCandidates(
        candidates: List<DeviceCandidate>,
        discoveryActive: Boolean,
    ): List<DeviceCandidate> {
        candidateOrder = updateCandidateOrder(candidateOrder, candidates, discoveryActive)
        val byKey = candidates.associateBy(DeviceCandidate::key)
        return candidateOrder.mapNotNull(byKey::get)
    }

    private suspend fun processTrackedPacket(packet: ScanPacket) {
        val (joined, estimate) = withContext(computationDispatcher) {
            val synchronized = synchronizer.synchronize(packet.observation)
            val latest = if (synchronized.accepted) {
                locationEngine.update(synchronized)
            } else {
                locationEngine.estimate()
            }
            synchronized to latest
        }
        if (traceActive) {
            try {
                traceIo { traceRecorder.record(joined, estimate) }
            } catch (_: Exception) {
                traceActive = false
                fail("Local session recording stopped")
            }
        }

        _state.update { current ->
            val metrics = current.metrics.copy(
                rawRssiDbm = packet.observation.rssiDbm,
                rollingMedianRssiDbm = joined.filteredRssiDbm,
                receivedCount = current.metrics.receivedCount + 1,
                acceptedCount = current.metrics.acceptedCount + if (joined.accepted) 1 else 0,
                rejectedNoPoseCount = current.metrics.rejectedNoPoseCount +
                    if (joined.rejectionReason == "no_recent_pose") 1 else 0,
                lastSeenElapsedNanos = packet.observation.receivedAtElapsedNanos,
            )
            current.copy(metrics = metrics, estimate = estimate).let { updated ->
                updated.copy(guidance = guidance(updated))
            }
        }
    }

    private suspend fun processCompleteScanPacket(packet: ScanPacket) {
        val identity = RadioIdentity(
            packet.target.transport,
            packet.target.ephemeralAddress,
        )
        val pipeline = completePipelines.getOrPut(identity) {
            CompleteDevicePipeline(
                target = packet.target,
                synchronizer = synchronizerFactory().also { synchronizer ->
                    latestPose?.let(synchronizer::addPose)
                },
                locationEngine = locationEngineFactory(),
            )
        }
        packet.target.advertisedName?.takeIf(String::isNotBlank)?.let {
            pipeline.displayName = it
        }
        val (joined, estimate) = withContext(computationDispatcher) {
            val synchronized = pipeline.synchronizer.synchronize(packet.observation)
            val latest = if (synchronized.accepted) {
                pipeline.locationEngine.update(synchronized)
            } else {
                pipeline.locationEngine.estimate()
            }
            synchronized to latest
        }
        if (traceActive) {
            try {
                traceIo { traceRecorder.record(joined, estimate) }
            } catch (error: Exception) {
                traceActive = false
                stopRuntimeWork("Complete Scan stopped because trace recording failed")
                return
            }
        }
        pipeline.estimate = estimate
        pipeline.metrics = pipeline.metrics.copy(
            rawRssiDbm = packet.observation.rssiDbm,
            rollingMedianRssiDbm = joined.filteredRssiDbm,
            receivedCount = pipeline.metrics.receivedCount + 1,
            acceptedCount = pipeline.metrics.acceptedCount + if (joined.accepted) 1 else 0,
            rejectedNoPoseCount = pipeline.metrics.rejectedNoPoseCount +
                if (joined.rejectionReason == "no_recent_pose") 1 else 0,
            lastSeenElapsedNanos = packet.observation.receivedAtElapsedNanos,
        )
        publishCompleteScanDevices()
    }

    private fun onPose(pose: PoseObservation) {
        latestPose = pose
        if (_state.value.screen == AppScreen.COMPLETE_SCAN) {
            completePipelines.values.forEach { it.synchronizer.addPose(pose) }
        } else {
            synchronizer.addPose(pose)
        }
        if (pose.trackingState != PoseTrackingState.TRACKING ||
            _state.value.screen == AppScreen.DEVICES
        ) return
        val previous = lastBreadcrumb
        val distance = previous?.let { hypot(pose.xMetres - it.xMetres, pose.zMetres - it.zMetres) }
            ?: Double.POSITIVE_INFINITY
        if (distance < BREADCRUMB_SPACING_METRES) return
        lastBreadcrumb = pose
        _state.update { current ->
            current.copy(
                breadcrumbs = (current.breadcrumbs + (pose.xMetres to pose.zMetres)).takeLast(160),
                metrics = current.metrics.copy(
                    pathLengthMetres = current.metrics.pathLengthMetres +
                        if (distance.isFinite()) distance else 0.0,
                ),
            )
        }
    }

    private fun publishCompleteScanDevices() {
        if (_state.value.screen != AppScreen.COMPLETE_SCAN) return
        val now = clock.nowNanos()
        val devices = completePipelines.values.map { pipeline ->
            val lastSeenAt = pipeline.metrics.lastSeenElapsedNanos
            CompleteScanDevice(
                key = pipeline.target.key,
                displayName = pipeline.displayName
                    ?: "Device ${pipeline.target.key.value}",
                transport = pipeline.target.transport,
                metrics = pipeline.metrics,
                estimate = pipeline.estimate,
                lastSeen = lastSeenAt != null &&
                    now - lastSeenAt > COMPLETE_SCAN_LAST_SEEN_NANOS,
            )
        }
        _state.update { current ->
            current.copy(
                completeScanDevices = devices,
                focusedDeviceKey = current.focusedDeviceKey
                    ?.takeIf { focused -> devices.any { it.key == focused } }
                    ?: devices.firstOrNull()?.key,
            )
        }
    }

    private fun guidance(state: LocatorUiState): String {
        val ar = state.arStatus
        if (state.screen != AppScreen.DEVICES && ar.status != ArRuntimeStatus.TRACKING
        ) return ar.detail ?: "AR tracking is unavailable; retry when it recovers"
        val lastSeen = state.metrics.lastSeenElapsedNanos
            ?: state.selected?.lastSeenElapsedNanos
        val targetLostNanos = if (state.selected?.handle?.transport == BluetoothTransport.CLASSIC) {
            CLASSIC_TARGET_LOST_NANOS
        } else {
            TARGET_LOST_NANOS
        }
        if (lastSeen != null && clock.nowNanos() - lastSeen > targetLostNanos) {
            return "Signal lost. Move back toward the last stronger readings"
        }
        if (state.metrics.receivedCount == 0) {
            return if (state.selected?.handle?.transport == BluetoothTransport.CLASSIC) {
                "Waiting for the selected device to answer Bluetooth discovery"
            } else {
                "Waiting for the selected device to advertise"
            }
        }
        if (state.metrics.acceptedCount < 3 || state.metrics.pathLengthMetres < 1.0) {
            return "Signal found. Move sideways to measure from another position"
        }
        return when (state.estimate?.status) {
            ESTIMATE_STATUS_GRID_MULTIMODAL -> "Several areas still fit. Walk around the other side"
            ESTIMATE_STATUS_GRID_UNIMODAL, ESTIMATE_STATUS_EKF -> {
                val area = state.estimate.regionAreaSquareMetres
                if (state.estimate.meanXMetres == null || area == null) {
                    "Collecting a more reliable location region"
                } else {
                    "Likely area found. Keep moving to confirm it"
                }
            }
            else -> "Collecting. Keep moving around the room"
        }
    }

    private fun stopRuntimeWork(message: String) {
        val operation = ++traceOperation
        val pathLengthMetres = _state.value.metrics.pathLengthMetres
        discoveryJob?.cancel()
        discoveryJob = null
        stopTrackingJobs()
        traceActive = false
        _state.update { current ->
            LocatorUiState(
                screen = AppScreen.DEVICES,
                arStatus = current.arStatus,
                lastTraceFile = current.lastTraceFile,
                recoverableError = message,
                guidance = message,
            )
        }
        viewModelScope.launch {
            val result = traceIo {
                if (operation == traceOperation) {
                    val completed = traceRecorder.finish(
                        TraceOutcome.INCOMPLETE,
                        pathLengthMetres = pathLengthMetres,
                    )
                    completed to historySnapshot(traceRecorder.completedTraces())
                } else {
                    null
                }
            } ?: return@launch
            if (operation != traceOperation) return@launch
            _traceSummaries.value = result.second.summaries
            _historyError.value = if (result.second.hasInvalidTrace) HISTORY_READ_ERROR else null
            _state.update {
                it.copy(
                    lastTraceFile = result.first.file ?: it.lastTraceFile,
                    recoverableError = if (result.first.error != null) {
                        "$message. $TRACE_FINISH_ERROR"
                    } else {
                        message
                    },
                )
            }
        }
    }

    private fun stopTrackingJobs() {
        scanJob?.cancel()
        lostTargetJob?.cancel()
        scanJob = null
        lostTargetJob = null
        completePipelines.clear()
    }

    private fun fail(message: String) {
        _state.update { it.copy(recoverableError = message, guidance = message) }
    }

    private suspend fun <T> traceIo(block: () -> T): T =
        traceMutex.withLock { withContext(ioDispatcher) { block() } }

    private fun historySnapshot(files: List<File>): HistorySnapshot {
        var invalid = false
        val summaries = files.take(10).mapNotNull { file ->
            runCatching { TraceSummaryReader.read(file) }.getOrElse {
                invalid = true
                null
            }
        }
        return HistorySnapshot(files, summaries, invalid)
    }

    override fun onCleared() {
        discoveryJob?.cancel()
        stopTrackingJobs()
        poseJob?.cancel()
        arStatusJob?.cancel()
        CoroutineScope(ioDispatcher).launch {
            traceMutex.withLock { traceRecorder.close() }
        }
    }

    private class CandidateAccumulator(initial: ScanPacket) {
        private var handle = initial.target
        private val readings = ArrayDeque<Pair<Long, Int>>()

        fun add(packet: ScanPacket) {
            val betterTransport = packet.target.transport == BluetoothTransport.LOW_ENERGY &&
                handle.transport != BluetoothTransport.LOW_ENERGY
            val betterName = packet.target.transport == handle.transport &&
                packet.target.advertisedName != null &&
                handle.advertisedName == null
            if (betterTransport || betterName) {
                handle = packet.target
            }
            readings.addLast(packet.observation.receivedAtElapsedNanos to packet.observation.rssiDbm)
            val oldest = packet.observation.receivedAtElapsedNanos - CANDIDATE_WINDOW_NANOS
            while (readings.firstOrNull()?.first?.let { it < oldest } == true) readings.removeFirst()
        }

        fun snapshot(nowNanos: Long): DeviceCandidate {
            val sorted = readings.map { it.second }.sorted()
            val median = if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            } else {
                sorted[sorted.size / 2].toDouble()
            }
            val spanSeconds = ((readings.last().first - readings.first().first) / 1e9).coerceAtLeast(1.0)
            return DeviceCandidate(
                handle = handle,
                latestRssiDbm = readings.last().second,
                medianRssiDbm = median,
                observationsPerSecond = if (readings.size > 1) {
                    (readings.size - 1) / spanSeconds
                } else {
                    0.0
                },
                lastSeenElapsedNanos = readings.last().first.coerceAtMost(nowNanos),
            )
        }
    }

    private class CompleteDevicePipeline(
        val target: TargetHandle,
        val synchronizer: SampleSynchronizer,
        val locationEngine: LocationEngine,
        var displayName: String? = target.advertisedName,
        var metrics: TrackingMetrics = TrackingMetrics(),
        var estimate: EstimateUiModel? = null,
    )

    private companion object {
        const val DISCOVERY_SECONDS = 20
        const val BREADCRUMB_SPACING_METRES = 0.12
        const val TARGET_LOST_NANOS = 5_000_000_000L
        const val CLASSIC_TARGET_LOST_NANOS = 20_000_000_000L
        const val CANDIDATE_WINDOW_NANOS = 5_000_000_000L
        const val COMPLETE_SCAN_LAST_SEEN_NANOS = 30_000_000_000L
        const val HISTORY_READ_ERROR = "Session history could not be read"
        const val TRACE_START_ERROR = "Local session recording could not start"
        const val TRACE_FINISH_ERROR = "The session could not be saved"
        const val TRACE_DELETE_ERROR = "The session could not be deleted"
        const val TRACE_DELETE_ALL_ERROR = "Some sessions could not be deleted"
    }
}

private data class HistorySnapshot(
    val files: List<File>,
    val summaries: List<TraceSummary>,
    val hasInvalidTrace: Boolean,
)

enum class CompleteScanStartResult {
    STARTED,
    REQUIRES_PRO,
    UNAVAILABLE,
}

internal fun updateCandidateOrder(
    previous: List<DeviceKey>,
    candidates: List<DeviceCandidate>,
    discoveryActive: Boolean,
    freshScan: Boolean = false,
): List<DeviceKey> {
    val observed = candidates.map(DeviceCandidate::key)
    return when {
        freshScan -> observed
        discoveryActive -> (previous + observed).distinct()
        else -> candidates.sortedWith(
            compareByDescending<DeviceCandidate> { it.handle.bonded }
                .thenByDescending { it.medianRssiDbm }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.key.value },
        ).map(DeviceCandidate::key)
    }
}
