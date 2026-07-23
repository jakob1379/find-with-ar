package dev.arsignallocator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.arsignallocator.ble.BleAvailability
import dev.arsignallocator.ble.BleScanner
import dev.arsignallocator.ble.ScanPacket
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.localization.LocationEngine
import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.TrackingMetrics
import dev.arsignallocator.ar.PoseSource
import dev.arsignallocator.sync.SampleSynchronizer
import dev.arsignallocator.trace.TraceRecorder
import java.io.File
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

class LocatorViewModel(
    private val scanner: BleScanner,
    private val locationEngine: LocationEngine,
    private val synchronizer: SampleSynchronizer,
    private val traceRecorder: TraceRecorder,
    private val clock: MonotonicClock,
    private val appVersion: String,
    private val deviceModel: String,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(LocatorUiState())
    val state: StateFlow<LocatorUiState> = _state.asStateFlow()

    private var runtimeReady = false
    private var discoveryJob: Job? = null
    private var scanJob: Job? = null
    private var lostTargetJob: Job? = null
    private var poseJob: Job? = null
    private var arStatusJob: Job? = null
    private var traceActive = false
    private val candidateAccumulators = linkedMapOf<DeviceKey, CandidateAccumulator>()
    private var lastBreadcrumb: PoseObservation? = null

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
        } else if (_state.value.screen == AppScreen.DEVICES) {
            startDiscovery()
        }
    }

    fun startDiscovery() {
        if (!runtimeReady) return
        when (val availability = scanner.availability()) {
            BleAvailability.Available -> Unit
            BleAvailability.Disabled -> return fail("Bluetooth is turned off")
            BleAvailability.Unsupported -> return fail("This phone does not support Bluetooth scanning")
            is BleAvailability.PermissionDenied -> return fail(availability.detail)
        }

        stopTrackingJobs()
        discoveryJob?.cancel()
        candidateAccumulators.clear()
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
                .catch { error -> fail(error.message ?: "Bluetooth discovery failed") }
                .collect(::onDiscovered)
            countdown.cancel()
            _state.update {
                it.copy(
                    isDiscovering = false,
                    discoverySecondsRemaining = 0,
                    guidance = if (it.candidates.isEmpty()) {
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
        discoveryJob?.cancel()
        discoveryJob = null
        synchronizer.reset()
        locationEngine.reset()
        lastBreadcrumb = null
        var traceError: String? = null
        val traceFile = try {
            traceRecorder.start(appVersion, deviceModel, candidate.handle).also { traceActive = true }
        } catch (error: Exception) {
            traceActive = false
            traceError = "Could not start trace recording: ${error.message}"
            null
        }
        _state.value = LocatorUiState(
            screen = AppScreen.TRACKING,
            selected = candidate,
            isDiscovering = false,
            arStatus = _state.value.arStatus,
            guidance = "Signal found. Walk sideways while keeping the camera uncovered",
            recoverableError = traceError,
            lastTraceFile = traceFile,
        )
        startSelectedScan(candidate)
    }

    fun showTracking() {
        if (_state.value.selected != null) _state.update { it.copy(screen = AppScreen.TRACKING) }
    }

    fun showDebug() {
        if (_state.value.selected != null) _state.update { it.copy(screen = AppScreen.DEBUG) }
    }

    fun stopAndReturnToDevices() {
        stopTrackingJobs()
        val traceFile = traceRecorder.stop()
        traceActive = false
        _state.update {
            LocatorUiState(
                screen = AppScreen.DEVICES,
                arStatus = it.arStatus,
                lastTraceFile = traceFile ?: it.lastTraceFile,
                guidance = "Select a device to begin",
            )
        }
        if (runtimeReady) startDiscovery()
    }

    fun deleteLocalTraces(traceDirectory: File) {
        stopTrackingJobs()
        traceRecorder.stop()
        traceActive = false
        val failedDeletes = traceDirectory.listFiles { file -> file.extension == "ndjson" }
            .orEmpty()
            .count { !it.delete() }
        _state.update {
            it.copy(
                lastTraceFile = null,
                recoverableError = if (failedDeletes == 0) null else {
                    "Could not delete $failedDeletes local trace file(s)"
                },
            )
        }
    }

    private fun startSelectedScan(candidate: DeviceCandidate) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.track(candidate.handle)
                .catch { error -> fail(error.message ?: "The selected-device scan failed") }
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

    private fun onDiscovered(packet: ScanPacket) {
        val accumulator = candidateAccumulators.getOrPut(packet.observation.deviceKey) {
            CandidateAccumulator(packet)
        }
        accumulator.add(packet)
        val now = clock.nowNanos()
        _state.update {
            it.copy(
                candidates = candidateAccumulators.values
                    .map { accumulatorValue -> accumulatorValue.snapshot(now) }
                    .sortedWith(
                        compareByDescending<DeviceCandidate> { candidate -> candidate.medianRssiDbm }
                            .thenBy(DeviceCandidate::displayName),
                    ),
            )
        }
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
                withContext(ioDispatcher) { traceRecorder.record(joined, estimate) }
            } catch (error: Exception) {
                traceActive = false
                fail("Trace recording stopped: ${error.message}")
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

    private fun onPose(pose: PoseObservation) {
        synchronizer.addPose(pose)
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

    private fun guidance(state: LocatorUiState): String {
        val ar = state.arStatus
        if (state.screen != AppScreen.DEVICES && ar.status != ArRuntimeStatus.TRACKING
        ) return ar.detail ?: "AR tracking is unavailable; retry when it recovers"
        val lastSeen = state.metrics.lastSeenElapsedNanos
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
            "GRID_MULTIMODAL" -> "Several areas still fit. Walk around the other side"
            "GRID_UNIMODAL", "EKF" -> {
                val area = state.estimate.regionAreaSquareMetres
                if (state.estimate.meanXMetres == null || area == null) {
                    "Collecting a more reliable location region"
                } else {
                    "Likely region found (~${"%.1f".format(area)} m² model area)"
                }
            }
            else -> "Collecting. Keep moving around the room"
        }
    }

    private fun stopRuntimeWork(message: String) {
        discoveryJob?.cancel()
        discoveryJob = null
        stopTrackingJobs()
        val completedTrace = traceRecorder.stop()
        traceActive = false
        _state.update { current ->
            LocatorUiState(
                screen = AppScreen.DEVICES,
                arStatus = current.arStatus,
                lastTraceFile = completedTrace ?: current.lastTraceFile,
                recoverableError = message,
                guidance = message,
            )
        }
    }

    private fun stopTrackingJobs() {
        scanJob?.cancel()
        lostTargetJob?.cancel()
        scanJob = null
        lostTargetJob = null
    }

    private fun fail(message: String) {
        _state.update { it.copy(recoverableError = message, guidance = message) }
    }

    override fun onCleared() {
        discoveryJob?.cancel()
        stopTrackingJobs()
        poseJob?.cancel()
        arStatusJob?.cancel()
        traceRecorder.close()
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

    private companion object {
        const val DISCOVERY_SECONDS = 20
        const val BREADCRUMB_SPACING_METRES = 0.12
        const val TARGET_LOST_NANOS = 5_000_000_000L
        const val CLASSIC_TARGET_LOST_NANOS = 20_000_000_000L
        const val CANDIDATE_WINDOW_NANOS = 5_000_000_000L
    }
}
