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
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.model.TrackingMetrics
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.allowsCompleteScan
import dev.arsignallocator.ar.PoseSource
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceOutcomeReason
import dev.arsignallocator.trace.TraceStore
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext

class LocatorViewModel internal constructor(
    private val scanner: BleScanner,
    private val locationEngine: LocationEngine,
    private val synchronizer: SampleSynchronizer,
    internal val traceStore: TraceStore,
    private val clock: MonotonicClock,
    private val appVersion: String,
    private val deviceModel: String,
    private val completeScanEntitlement: () -> EntitlementState = { EntitlementState.FREE },
    private val locationEngineFactory: () -> LocationEngine = { LocalizationAdapter() },
    private val synchronizerFactory: () -> SampleSynchronizer = { SampleSynchronizer() },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(LocatorUiState())
    val state: StateFlow<LocatorUiState> = _state.asStateFlow()

    internal constructor(
        scanner: BleScanner,
        locationEngine: LocationEngine,
        synchronizer: SampleSynchronizer,
        traceRecorder: TraceRecorder,
        clock: MonotonicClock,
        appVersion: String,
        deviceModel: String,
        completeScanEntitlement: () -> EntitlementState = { EntitlementState.FREE },
        locationEngineFactory: () -> LocationEngine = { LocalizationAdapter() },
        synchronizerFactory: () -> SampleSynchronizer = { SampleSynchronizer() },
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        scanner = scanner,
        locationEngine = locationEngine,
        synchronizer = synchronizer,
        traceStore = TraceStore(traceRecorder, clock, ioDispatcher),
        clock = clock,
        appVersion = appVersion,
        deviceModel = deviceModel,
        completeScanEntitlement = completeScanEntitlement,
        locationEngineFactory = locationEngineFactory,
        synchronizerFactory = synchronizerFactory,
        computationDispatcher = computationDispatcher,
    )

    private var runtimeReady = false
    private var discoveryJob: Job? = null
    private var scanJob: Job? = null
    private var lostTargetJob: Job? = null
    private var poseJob: Job? = null
    private var arStatusJob: Job? = null
    private var appInForeground = true
    private val candidateAccumulators = linkedMapOf<DeviceKey, CandidateAccumulator>()
    private var candidateOrder = emptyList<DeviceKey>()
    private var lastBreadcrumb: PoseObservation? = null
    private var latestPose: PoseObservation? = null
    private val completePipelines = linkedMapOf<RadioIdentity, CompleteDevicePipeline>()

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
                recordArState(status.status, status.recoverable)
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
        val operation = traceStore.prepareStart()
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
            recoverableError = null,
            lastTraceFile = previousTrace,
        )
        viewModelScope.launch {
            val traceStarted = startTrace(operation) { arState, arRecoverable ->
                traceStore.startSingleTarget(
                    operation,
                    appVersion,
                    deviceModel,
                    candidate.handle,
                    arState,
                    arRecoverable,
                    SCANNER_SINGLE_TARGET,
                )
            }
            if (traceStarted != null) startSelectedScan(candidate)
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

        val operation = traceStore.prepareStart()
        discoveryJob?.cancel()
        discoveryJob = null
        stopTrackingJobs()
        lastBreadcrumb = null
        latestPose = null
        val previous = _state.value
        _state.value = LocatorUiState(
            screen = AppScreen.COMPLETE_SCAN,
            arStatus = previous.arStatus,
            guidance = "Move around the room to collect signal from different positions",
            recoverableError = null,
            lastTraceFile = previous.lastTraceFile,
        )
        viewModelScope.launch {
            val traceStarted = startTrace(operation) { arState, arRecoverable ->
                traceStore.startCompleteScan(
                    operation,
                    appVersion,
                    deviceModel,
                    arState,
                    arRecoverable,
                    SCANNER_COMPLETE_SCAN,
                )
            }
            if (traceStarted == true) startCompleteScanner()
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
        val stop = traceStore.prepareStop()
        val pathLengthMetres = _state.value.metrics.pathLengthMetres
        stopTrackingJobs()
        _state.update {
            LocatorUiState(
                screen = AppScreen.DEVICES,
                arStatus = it.arStatus,
                lastTraceFile = it.lastTraceFile,
                guidance = "Select a device to begin",
            )
        }
        viewModelScope.launch {
            val result = traceStore.finish(stop, outcome, reason, note, pathLengthMetres)
            if (!traceStore.isCurrent(stop.operation)) return@launch
            _state.update {
                it.copy(
                    lastTraceFile = result.file ?: it.lastTraceFile,
                    recoverableError = TRACE_FINISH_ERROR.takeIf { result.error != null },
                )
            }
        }
        if (runtimeReady) startDiscovery()
    }

    private fun startSelectedScan(candidate: DeviceCandidate) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.track(candidate.handle)
                .catch {
                    recordRecoverableFailure(FAILURE_SCANNER)
                    recordScannerState(SCANNER_STOPPED)
                    fail("Device tracking stopped. Try the scan again")
                }
                .collect { packet -> processTrackedPacket(packet) }
        }
        lostTargetJob?.cancel()
        lostTargetJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val current = _state.value
                current.selected?.handle?.let { target ->
                    val lastSeen = current.metrics.lastSeenElapsedNanos
                        ?: current.selected.lastSeenElapsedNanos
                    val timeout = if (target.transport == BluetoothTransport.CLASSIC) {
                        CLASSIC_TARGET_LOST_NANOS
                    } else {
                        TARGET_LOST_NANOS
                    }
                    recordSignalState(
                        target,
                        clock.nowNanos() - lastSeen > timeout,
                    )
                }
                _state.update { state -> state.copy(guidance = guidance(state)) }
            }
        }
    }

    private fun startCompleteScanner() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.completeScan()
                .catch {
                    stopRuntimeWork(
                        "Complete Scan stopped because Bluetooth scanning failed",
                        FAILURE_SCANNER,
                    )
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
        val operation = traceStore.currentOperation()
        val (joined, estimate) = localize(packet, synchronizer, locationEngine)
        if (operation != null) {
            try {
                traceStore.record(operation, joined, estimate, packet.target)
            } catch (_: Exception) {
                recordRecoverableFailure(FAILURE_TRACE_RECORDING)
                traceStore.disable(operation)
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
        val operation = traceStore.currentOperation()
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
        val (joined, estimate) =
            localize(packet, pipeline.synchronizer, pipeline.locationEngine)
        if (operation != null) {
            try {
                traceStore.record(operation, joined, estimate, packet.target)
            } catch (error: Exception) {
                stopRuntimeWork(
                    "Complete Scan stopped because trace recording failed",
                    FAILURE_TRACE_RECORDING,
                )
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

    private suspend fun localize(
        packet: ScanPacket,
        synchronizer: SampleSynchronizer,
        locationEngine: LocationEngine,
    ): Pair<SynchronizedObservation, EstimateUiModel> = withContext(computationDispatcher) {
        val joined = synchronizer.synchronize(packet.observation)
        joined to if (joined.accepted) {
            locationEngine.update(joined)
        } else {
            locationEngine.estimate()
        }
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

    private suspend fun publishCompleteScanDevices() {
        if (_state.value.screen != AppScreen.COMPLETE_SCAN) return
        val now = clock.nowNanos()
        val devices = completePipelines.values.toList().map { pipeline ->
            val lastSeenAt = pipeline.metrics.lastSeenElapsedNanos
            val device = CompleteScanDevice(
                key = pipeline.target.key,
                displayName = pipeline.displayName
                    ?: "Device ${pipeline.target.key.value}",
                transport = pipeline.target.transport,
                metrics = pipeline.metrics,
                estimate = pipeline.estimate,
                signalLost = lastSeenAt != null &&
                    now - lastSeenAt > COMPLETE_SCAN_LAST_SEEN_NANOS,
            )
            recordSignalState(pipeline.target, device.signalLost)
            device
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

    private suspend fun recordArState(state: ArRuntimeStatus, recoverable: Boolean) {
        val operation = traceStore.currentOperation() ?: return
        try {
            traceStore.recordArState(operation, state, recoverable)
        } catch (_: Exception) {
            handleTraceRecordingFailure()
        }
    }

    private suspend fun startTrace(
        operation: Long,
        startRecording: suspend (ArRuntimeStatus, Boolean) -> Boolean,
    ): Boolean? {
        val ar = _state.value.arStatus
        val traceStarted = runCatching {
            startRecording(ar.status, ar.recoverable)
        }.getOrDefault(false)
        if (!traceStore.isCurrent(operation)) return null
        _state.update {
            it.copy(recoverableError = if (traceStarted) null else TRACE_START_ERROR)
        }
        return traceStarted
    }

    private suspend fun recordScannerState(state: String) {
        val operation = traceStore.currentOperation() ?: return
        try {
            traceStore.recordScannerState(operation, state)
        } catch (_: Exception) {
            handleTraceRecordingFailure()
        }
    }

    private suspend fun recordSignalState(target: TargetHandle, lost: Boolean) {
        val operation = traceStore.currentOperation() ?: return
        try {
            traceStore.recordSignalState(operation, target, lost)
        } catch (_: Exception) {
            handleTraceRecordingFailure()
        }
    }

    private suspend fun recordRecoverableFailure(failure: String) {
        val operation = traceStore.currentOperation() ?: return
        try {
            traceStore.recordFailure(operation, failure)
        } catch (_: Exception) {
            handleTraceRecordingFailure()
        }
    }

    private fun handleTraceRecordingFailure() {
        if (_state.value.screen == AppScreen.COMPLETE_SCAN) {
            stopRuntimeWork(
                "Complete Scan stopped because trace recording failed",
                FAILURE_TRACE_RECORDING,
            )
        } else {
            traceStore.currentOperation()?.let(traceStore::disable)
            fail("Local session recording stopped")
        }
    }

    private fun stopRuntimeWork(message: String, failure: String = FAILURE_RUNTIME_REQUIREMENTS) {
        val stop = traceStore.prepareStop()
        val pathLengthMetres = _state.value.metrics.pathLengthMetres
        discoveryJob?.cancel()
        discoveryJob = null
        stopTrackingJobs()
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
            val result = traceStore.finish(
                stop,
                TraceOutcome.INCOMPLETE,
                pathLengthMetres = pathLengthMetres,
                failure = failure,
            )
            if (!traceStore.isCurrent(stop.operation)) return@launch
            _state.update {
                it.copy(
                    lastTraceFile = result.file ?: it.lastTraceFile,
                    recoverableError = if (result.error != null) {
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

    override fun onCleared() {
        traceStore.prepareStop()
        discoveryJob?.cancel()
        stopTrackingJobs()
        poseJob?.cancel()
        arStatusJob?.cancel()
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
        const val SCANNER_SINGLE_TARGET = "SINGLE_TARGET_ACTIVE"
        const val SCANNER_COMPLETE_SCAN = "COMPLETE_SCAN_ACTIVE"
        const val SCANNER_STOPPED = "STOPPED"
        const val FAILURE_SCANNER = "SCANNER"
        const val FAILURE_TRACE_RECORDING = "TRACE_RECORDING"
        const val FAILURE_RUNTIME_REQUIREMENTS = "RUNTIME_REQUIREMENTS"
        const val TRACE_START_ERROR = "Local session recording could not start"
        const val TRACE_FINISH_ERROR = "The session could not be saved"
    }
}

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
