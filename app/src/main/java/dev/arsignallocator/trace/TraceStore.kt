package dev.arsignallocator.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.arsignallocator.core.MonotonicClock
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.ESTIMATE_STATUS_COLLECTING
import dev.arsignallocator.model.ESTIMATE_STATUS_EKF
import dev.arsignallocator.model.ESTIMATE_STATUS_GRID_MULTIMODAL
import dev.arsignallocator.model.ESTIMATE_STATUS_GRID_UNIMODAL
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TraceStore(
    private val recorder: TraceRecorder,
    private val clock: MonotonicClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutex = Mutex()
    private val _summaries = MutableStateFlow<List<TraceSummary>>(emptyList())
    val summaries: StateFlow<List<TraceSummary>> = _summaries.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var initialized = false
    @Volatile
    private var latestOperation = 0L
    @Volatile
    private var activeOperation: Long? = null
    @Volatile
    private var recording = false
    private var tracedArState: ArRuntimeStatus? = null
    private var tracedScannerState: String? = null
    private val tracedEstimatorStates =
        mutableMapOf<Pair<DeviceKey, BluetoothTransport>, String>()
    private val tracedLostSignals =
        mutableSetOf<Pair<DeviceKey, BluetoothTransport>>()

    init {
        viewModelScope.launch { withRecorder {} }
    }

    internal suspend fun <T> withRecorder(
        refreshHistory: Boolean = false,
        block: () -> T,
    ): T = mutex.withLock {
        withContext(ioDispatcher) {
            initialize()
            block().also {
                if (refreshHistory) publish(recorder.completedTraces())
            }
        }
    }

    @Synchronized
    fun prepareStart(): Long {
        recording = false
        return ++latestOperation
    }

    @Synchronized
    fun prepareStop(): TraceStopToken {
        recording = false
        return TraceStopToken(activeOperation, ++latestOperation)
    }

    fun isCurrent(operation: Long): Boolean = latestOperation == operation

    fun currentOperation(): Long? = activeOperation.takeIf { recording }

    fun disable(operation: Long) {
        if (activeOperation == operation) recording = false
    }

    suspend fun startSingleTarget(
        operation: Long,
        appVersion: String,
        deviceModel: String,
        target: TargetHandle,
        arState: ArRuntimeStatus,
        arRecoverable: Boolean,
        scannerState: String,
    ): Boolean = start(operation, arState, arRecoverable, scannerState) {
        recorder.startSingleTarget(appVersion, deviceModel, target)
    }

    suspend fun startCompleteScan(
        operation: Long,
        appVersion: String,
        deviceModel: String,
        arState: ArRuntimeStatus,
        arRecoverable: Boolean,
        scannerState: String,
    ): Boolean = start(operation, arState, arRecoverable, scannerState) {
        recorder.startCompleteScan(appVersion, deviceModel)
    }

    suspend fun record(
        operation: Long,
        observation: SynchronizedObservation,
        estimate: EstimateUiModel?,
        target: TargetHandle,
    ): Boolean = withRecorder {
        if (!matches(operation)) return@withRecorder false
        recorder.record(observation, estimate)
        recordEstimatorState(target, estimate)
        recordSignalRecovered(target)
        true
    }

    suspend fun recordArState(
        operation: Long,
        state: ArRuntimeStatus,
        recoverable: Boolean,
    ): Boolean = withRecorder {
        if (!matches(operation)) return@withRecorder false
        recordArState(state, recoverable)
        true
    }

    suspend fun recordScannerState(operation: Long, state: String): Boolean = withRecorder {
        if (!matches(operation)) return@withRecorder false
        recordScannerState(state)
        true
    }

    suspend fun recordSignalState(
        operation: Long,
        target: TargetHandle,
        lost: Boolean,
    ): Boolean = withRecorder {
        if (!matches(operation)) return@withRecorder false
        val identity = target.key to target.transport
        when {
            lost && identity !in tracedLostSignals -> {
                recordDeviceEvent(TraceEventCode.SIGNAL_LOST, target)
                tracedLostSignals += identity
            }
            !lost -> recordSignalRecovered(target)
        }
        true
    }

    suspend fun recordFailure(operation: Long, failure: String): Boolean = withRecorder {
        if (!matches(operation)) return@withRecorder false
        recordEvent(TraceEventCode.RECOVERABLE_FAILURE, detail = failure)
        true
    }

    suspend fun finish(
        stop: TraceStopToken,
        outcome: TraceOutcome,
        reason: TraceOutcomeReason? = null,
        note: String? = null,
        pathLengthMetres: Double = 0.0,
        failure: String? = null,
    ): TraceFinalizationResult = withRecorder(refreshHistory = true) {
        val session = stop.sessionOperation
        if (
            session != null && activeOperation != session ||
            session == null && (!isCurrent(stop.operation) || activeOperation != null)
        ) {
            return@withRecorder TraceFinalizationResult()
        }
        runCatching {
            failure?.let {
                recordEvent(TraceEventCode.RECOVERABLE_FAILURE, detail = it)
            }
            recordScannerState(SCANNER_STOPPED)
        }
        activeOperation = null
        recorder.finish(outcome, reason, note, pathLengthMetres)
    }

    private suspend fun start(
        operation: Long,
        arState: ArRuntimeStatus,
        arRecoverable: Boolean,
        scannerState: String,
        startRecorder: () -> Unit,
    ): Boolean = withRecorder {
        if (!isCurrent(operation)) return@withRecorder false
        startRecorder()
        if (!isCurrent(operation)) {
            return@withRecorder false
        }
        activeOperation = operation
        recording = true
        tracedArState = null
        tracedScannerState = null
        tracedEstimatorStates.clear()
        tracedLostSignals.clear()
        recordArState(arState, arRecoverable)
        recordScannerState(scannerState)
        true
    }

    private fun matches(operation: Long): Boolean =
        recording && operation == activeOperation

    private fun recordArState(state: ArRuntimeStatus, recoverable: Boolean) {
        if (tracedArState == state) return
        recordEvent(TraceEventCode.AR_STATE, detail = state.name)
        tracedArState = state
        if (recoverable) {
            recordEvent(TraceEventCode.RECOVERABLE_FAILURE, detail = FAILURE_AR)
        }
    }

    private fun recordScannerState(state: String) {
        if (tracedScannerState == state) return
        recordEvent(TraceEventCode.SCANNER_STATE, detail = state)
        tracedScannerState = state
    }

    private fun recordSignalRecovered(target: TargetHandle) {
        val identity = target.key to target.transport
        if (identity !in tracedLostSignals) return
        recordDeviceEvent(TraceEventCode.SIGNAL_RECOVERED, target)
        tracedLostSignals -= identity
    }

    private fun recordEstimatorState(target: TargetHandle, estimate: EstimateUiModel?) {
        val state = when (estimate?.status) {
            ESTIMATE_STATUS_COLLECTING,
            ESTIMATE_STATUS_GRID_MULTIMODAL,
            ESTIMATE_STATUS_GRID_UNIMODAL,
            ESTIMATE_STATUS_EKF,
            -> estimate.status
            else -> ESTIMATOR_STATE_INSUFFICIENT_EVIDENCE
        }
        val identity = target.key to target.transport
        if (tracedEstimatorStates[identity] == state) return
        recordDeviceEvent(TraceEventCode.ESTIMATOR_STATE_CHANGED, target, state)
        tracedEstimatorStates[identity] = state
    }

    private fun recordDeviceEvent(
        code: TraceEventCode,
        target: TargetHandle,
        detail: String? = null,
    ) {
        recordEvent(code, target.key, target.transport, detail)
    }

    private fun recordEvent(
        code: TraceEventCode,
        deviceKey: DeviceKey? = null,
        transport: BluetoothTransport? = null,
        detail: String? = null,
    ) {
        recorder.recordEvent(
            appMonotonicNanos = clock.nowNanos(),
            code = code,
            deviceKey = deviceKey,
            transport = transport,
            detail = detail,
        )
    }

    fun deleteTrace(file: File) {
        viewModelScope.launch {
            withRecorder {
                val deleted = recorder.deleteTrace(file)
                publish(
                    recorder.completedTraces(),
                    if (deleted) null else TRACE_DELETE_ERROR,
                )
            }
        }
    }

    fun deleteAllTraces() {
        viewModelScope.launch {
            withRecorder {
                recorder.deleteAllTraces()
                publish(
                    recorder.completedTraces(),
                    if (recorder.storageError == null) null else TRACE_DELETE_ALL_ERROR,
                )
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        val files = runCatching { recorder.initialize() }
        publish(
            files.getOrDefault(emptyList()),
            if (files.isFailure || recorder.storageError != null) HISTORY_READ_ERROR else null,
        )
    }

    private fun publish(files: List<File>, operationError: String? = null) {
        var invalid = false
        _summaries.value = files.take(10).mapNotNull { file ->
            runCatching { TraceSummaryReader.read(file) }.getOrElse {
                invalid = true
                null
            }
        }
        _error.value = operationError ?: HISTORY_READ_ERROR.takeIf { invalid }
    }

    override fun onCleared() {
        recorder.close()
    }

    private companion object {
        const val SCANNER_STOPPED = "STOPPED"
        const val FAILURE_AR = "AR"
        const val ESTIMATOR_STATE_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
        const val HISTORY_READ_ERROR = "Session history could not be read"
        const val TRACE_DELETE_ERROR = "The session could not be deleted"
        const val TRACE_DELETE_ALL_ERROR = "Some sessions could not be deleted"
    }
}

internal data class TraceStopToken(
    val sessionOperation: Long?,
    val operation: Long,
)
