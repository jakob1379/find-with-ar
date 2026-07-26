package dev.arsignallocator.trace

import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.model.isLocalized
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

const val TRACE_SCHEMA_VERSION = 2
private const val MAX_COMPLETED_TRACES = 10
internal const val MAX_EVENT_DETAIL_LENGTH = 200
internal const val MAX_OUTCOME_NOTE_LENGTH = 500
private const val ACTIVE_SUFFIX = ".active.ndjson"
private const val QUARANTINE_SUFFIX = ".quarantined.ndjson"
private const val COMPLETION_GENERATION_FILE = ".signal-trace-completion-generation"
internal val BLUETOOTH_ADDRESS_PATTERN = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
private val COMPLETED_GENERATION_PATTERN =
    Regex("""^signal-trace-g([0-9]{20})-.+\.ndjson$""")
private fun readProcessBootId(): String = runCatching {
    File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf(String::isNotBlank)
}.getOrNull() ?: UUID.randomUUID().toString()

enum class TraceMode { SINGLE_TARGET, COMPLETE_SCAN }

enum class TraceEventCode(val wireValue: String) {
    AR_STATE("ar_state"),
    SCANNER_STATE("scanner_state"),
    SIGNAL_LOST("signal_lost"),
    SIGNAL_RECOVERED("signal_recovered"),
    ESTIMATOR_STATE_CHANGED("estimator_state_changed"),
    RECOVERABLE_FAILURE("recoverable_failure"),
}

enum class TraceOutcome(val wireValue: String) {
    YES("YES"),
    NO("NO"),
    NOT_SURE("NOT_SURE"),
    SKIPPED("SKIPPED"),
    INCOMPLETE("INCOMPLETE"),
}

enum class TraceOutcomeReason(val wireValue: String) {
    DEVICE_DISAPPEARED("device_disappeared"),
    DEVICE_HARD_TO_IDENTIFY("device_hard_to_identify"),
    GUIDANCE_UNCLEAR("guidance_unclear"),
    ESTIMATED_AREA_WRONG("estimated_area_wrong"),
    CAMERA_AR_BLUETOOTH_OR_APP_ERROR("camera_ar_bluetooth_or_app_error"),
    OTHER("other"),
}

data class SessionMetadata(
    val sessionId: String,
    val createdAtUtc: String,
    val startedAtMonotonicNanos: Long,
    val bootId: String,
    val appVersion: String,
    val deviceModel: String,
    val mode: TraceMode,
    val target: TargetHandle?,
)

internal data class DeviceState(
    val key: DeviceKey,
    val transport: BluetoothTransport,
    val estimate: EstimateUiModel?,
    val localized: Boolean = estimate?.isLocalized() == true,
)

private data class SourceDevice(
    val key: DeviceKey,
    val transport: BluetoothTransport,
)

private data class RecoverySummary(
    val deviceStates: List<DeviceState>,
    val observedDeviceCount: Int,
    val hasOutcome: Boolean,
)

internal data class RecoverySession(
    val sessionId: String,
    val createdAtMillis: Long,
    val startedAtMonotonicNanos: Long?,
    val bootId: String?,
    val mode: TraceMode,
    val selectedState: DeviceState?,
)

internal data class RecoveryObservation(
    val state: DeviceState,
    val sequence: Long,
    val appMonotonicNanos: Long,
)

internal data class RecoveryEvent(
    val key: DeviceKey?,
    val transport: BluetoothTransport?,
)

data class TraceFinalizationResult(
    val file: File? = null,
    val error: String? = null,
)

class TraceRecorder internal constructor(
    private val traceDirectory: File,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockNanos: () -> Long = System::nanoTime,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val bootIdProvider: () -> String = ::readProcessBootId,
    private val deleteFile: (File) -> Boolean = { it.delete() },
    private val renameFile: (File, File) -> Boolean = File::renameTo,
    private val writerFactory: (File) -> BufferedWriter = File::bufferedWriter,
    private val appendWriterFactory: (File) -> BufferedWriter = {
        FileOutputStream(it, true).bufferedWriter(StandardCharsets.UTF_8)
    },
) : Closeable {
    private var writer: BufferedWriter? = null
    private var metadata: SessionMetadata? = null
    private var sequence = 0L
    private var activeFile: File? = null
    private var startedAtMonotonicNanos = 0L
    private var lastObservationAppNanos = -1L
    private val deviceKeys = linkedMapOf<SourceDevice, DeviceKey>()
    private val transports = linkedMapOf<DeviceKey, BluetoothTransport>()
    private val observedKeys = linkedSetOf<DeviceKey>()
    private val latestEstimates = linkedMapOf<DeviceKey, EstimateUiModel?>()
    private val redactions = linkedSetOf<String>()
    var storageError: String? = null
        private set

    @Synchronized
    fun recoverAndListCompletedTraces(): List<File> {
        storageError = null
        recoverActiveFiles()
        return completedFiles()
    }

    fun startSingleTarget(appVersion: String, deviceModel: String, target: TargetHandle): File =
        startSession(appVersion, deviceModel, TraceMode.SINGLE_TARGET, target)

    fun startCompleteScan(appVersion: String, deviceModel: String): File =
        startSession(appVersion, deviceModel, TraceMode.COMPLETE_SCAN)

    @Synchronized
    private fun startSession(
        appVersion: String,
        deviceModel: String,
        mode: TraceMode,
        target: TargetHandle? = null,
    ): File {
        require(appVersion.isNotBlank() && deviceModel.isNotBlank()) {
            "App version and device model must not be blank"
        }
        require(
            target == null ||
                target.key.value.isNotBlank() &&
                target.serviceUuids.all(String::isNotBlank) &&
                target.serviceUuids.none(BLUETOOTH_ADDRESS_PATTERN::containsMatchIn),
        ) {
            "Target identity metadata is invalid"
        }
        close()
        recoverActiveFiles()
        ensureTraceDirectory()
        val sessionId = sessionIdFactory()
        require(sessionId == safeFilePart(sessionId)) { "Session ID contains unsafe characters" }
        val startedAtMillis = wallClockMillis()
        startedAtMonotonicNanos = monotonicClockNanos()
        require(startedAtMonotonicNanos >= 0L) { "Session monotonic timestamp must be non-negative" }
        val rawBootId = bootIdProvider()
        require(rawBootId.isNotBlank()) { "Boot identity must not be blank" }
        require(!BLUETOOTH_ADDRESS_PATTERN.containsMatchIn(appVersion + deviceModel)) {
            "Session metadata must not contain a Bluetooth address"
        }
        val session = SessionMetadata(
            sessionId = sessionId,
            createdAtUtc = formatUtc(startedAtMillis),
            startedAtMonotonicNanos = startedAtMonotonicNanos,
            bootId = bootDigest(sessionId, rawBootId),
            appVersion = appVersion,
            deviceModel = deviceModel,
            mode = mode,
            target = target,
        )
        if (target != null) {
            deviceKeys[SourceDevice(target.key, target.transport)] = DeviceKey("D01")
            transports[DeviceKey("D01")] = target.transport
            redactions += target.ephemeralAddress
            target.advertisedName?.takeIf(String::isNotBlank)?.let(redactions::add)
        }
        val file = File(traceDirectory, "signal-trace-$sessionId$ACTIVE_SUFFIX")
        try {
            writer = writerFactory(file)
            metadata = session
            sequence = 0L
            activeFile = file
            appendLine(TraceJsonEncoder.session(session))
        } catch (error: Exception) {
            runCatching { writer?.close() }
            writer = null
            clearSession()
            storageError = "Could not start trace recording: ${error.message ?: "storage error"}"
            throw error
        }
        return file
    }

    @Synchronized
    fun record(observation: SynchronizedObservation, estimate: EstimateUiModel?) {
        val session = checkNotNull(metadata) { "TraceRecorder.start must be called first" }
        validateObservationInput(session, observation, estimate)
        val source = SourceDevice(observation.ble.deviceKey, observation.ble.transport)
        val deviceKey = deviceKeys[source] ?: nextDeviceKey()
        val recordSequence = sequence
        appendLine(
            TraceJsonEncoder.observation(
                session.sessionId,
                recordSequence,
                deviceKey,
                observation,
                estimate,
            ),
        )
        deviceKeys.putIfAbsent(source, deviceKey)
        observedKeys += deviceKey
        transports[deviceKey] = observation.ble.transport
        latestEstimates[deviceKey] = estimate
        observation.ble.advertisedName?.takeIf(String::isNotBlank)?.let(redactions::add)
        sequence = recordSequence + 1
        lastObservationAppNanos = observation.ble.receivedAtElapsedNanos
    }

    @Synchronized
    fun recordEvent(
        appMonotonicNanos: Long,
        code: TraceEventCode,
        deviceKey: DeviceKey? = null,
        transport: BluetoothTransport? = null,
        detail: String? = null,
    ) {
        require(appMonotonicNanos >= 0) { "Event time must be non-negative" }
        require((deviceKey == null) == (transport == null)) {
            "Device-scoped events require both deviceKey and transport"
        }
        val session = checkNotNull(metadata) { "TraceRecorder.start must be called first" }
        requireSelectedTarget(session, deviceKey, transport)
        val source = deviceKey?.let { SourceDevice(it, checkNotNull(transport)) }
        val mappedDeviceKey = source?.let { deviceKeys[it] ?: nextDeviceKey() }
        val normalizedDetail = detail?.let(::sanitizeDetail)?.takeIf(String::isNotBlank)
        appendLine(
            TraceJsonEncoder.event(
                sessionId = session.sessionId,
                appMonotonicNanos = appMonotonicNanos,
                code = code,
                deviceKey = mappedDeviceKey,
                transport = transport,
                detail = normalizedDetail,
            ),
        )
        if (source != null && mappedDeviceKey != null) {
            deviceKeys.putIfAbsent(source, mappedDeviceKey)
            transports[mappedDeviceKey] = checkNotNull(transport)
        }
    }

    @Synchronized
    fun finish(
        outcome: TraceOutcome,
        reason: TraceOutcomeReason? = null,
        note: String? = null,
        pathLengthMetres: Double = 0.0,
    ): TraceFinalizationResult {
        require(note == null || unicodeLength(note) <= MAX_OUTCOME_NOTE_LENGTH) {
            "Outcome note must be at most $MAX_OUTCOME_NOTE_LENGTH characters"
        }
        require(reason == null || outcome == TraceOutcome.NO || outcome == TraceOutcome.NOT_SURE) {
            "Outcome reasons are only valid for NO or NOT_SURE"
        }
        require(pathLengthMetres.isFinite() && pathLengthMetres >= 0.0) {
            "Path length must be finite and non-negative"
        }
        val session = metadata ?: return TraceFinalizationResult()
        val file = checkNotNull(activeFile)
        val endedAtMillis = wallClockMillis()
        val endedAtMonotonicNanos = monotonicClockNanos()
        val states = transports.map { (key, transport) ->
            DeviceState(key, transport, latestEstimates[key])
        }
        var failure = runCatching {
            appendLine(
                TraceJsonEncoder.outcome(
                    sessionId = session.sessionId,
                    endedAtUtc = formatUtc(endedAtMillis),
                    durationMillis = (
                        (endedAtMonotonicNanos - startedAtMonotonicNanos) / 1_000_000L
                        ).coerceAtLeast(0L),
                    outcome = outcome,
                    reason = reason,
                    note = note
                        ?.let { sanitize(it, MAX_OUTCOME_NOTE_LENGTH) }
                        ?.takeIf(String::isNotBlank),
                    observedDeviceCount = observedKeys.size,
                    localizedDeviceCount = states.count { it.estimate?.isLocalized() == true },
                    pathLengthMetres = pathLengthMetres,
                    deviceStates = states,
                ),
            )
        }.exceptionOrNull()
        val closeFailure = runCatching { writer?.close() }.exceptionOrNull()
        writer = null
        if (failure == null) failure = closeFailure
        var completed: File? = null
        if (failure == null) {
            val destination = runCatching { completedFile(file) }
            failure = destination.exceptionOrNull()
            completed = destination.getOrNull()
        }
        if (
            failure == null &&
            !runCatching { renameFile(file, checkNotNull(completed)) }.getOrDefault(false)
        ) {
            failure = IllegalStateException("Could not publish completed trace file")
        }
        clearSession()
        if (failure != null) {
            val message = "Could not finalize trace; recoverable data kept in ${file.name}: " +
                (failure.message ?: "storage error")
            storageError = message
            return TraceFinalizationResult(error = message)
        }
        runCatching { pruneCompleted() }.onFailure { error ->
            storageError = "Could not prune local traces: ${error.message ?: "storage error"}"
        }
        return TraceFinalizationResult(file = completed, error = storageError)
    }

    @Synchronized
    fun stop(): TraceFinalizationResult = finish(TraceOutcome.INCOMPLETE)

    @Synchronized
    fun completedTraces(): List<File> = completedFiles()

    @Synchronized
    fun deleteTrace(file: File): Boolean {
        val deleted = runCatching {
            file.parentFile?.canonicalFile == traceDirectory.canonicalFile &&
                file.name.endsWith(".ndjson") &&
                !file.name.endsWith(ACTIVE_SUFFIX) &&
                deleteFile(file)
        }.getOrDefault(false)
        if (!deleted) storageError = "Could not delete local trace ${file.name}"
        return deleted
    }

    @Synchronized
    fun deleteAllTraces(): List<File> {
        storageError = null
        val files = managedTraceFiles()
        if (storageError != null) return emptyList()
        val failed = files.filterNot { file ->
            runCatching { deleteFile(file) }.getOrDefault(false)
        }
        storageError = if (failed.isEmpty()) {
            null
        } else {
            "Could not delete ${failed.size} local trace file(s)"
        }
        return completedFiles()
    }

    @Synchronized
    override fun close() {
        val failure = runCatching {
            writer?.flush()
            writer?.close()
        }.exceptionOrNull()
        writer = null
        clearSession()
        if (failure != null) {
            storageError = "Could not close active trace: ${failure.message ?: "storage error"}"
        }
    }

    private fun appendLine(line: String) {
        val file = checkNotNull(activeFile)
        val confirmedBytes = file.length()
        try {
            checkNotNull(writer).apply {
                write(line)
                newLine()
                flush()
            }
        } catch (error: Exception) {
            runCatching { writer?.close() }
            writer = null
            val recovered = runCatching {
                RandomAccessFile(file, "rw").use { it.setLength(confirmedBytes) }
                writer = appendWriterFactory(file)
            }.isSuccess
            if (!recovered) {
                quarantine(file)
                clearSession()
            }
            throw error
        }
    }

    private fun nextDeviceKey() =
        DeviceKey("D${(deviceKeys.size + 1).toString().padStart(2, '0')}")

    private fun sanitize(value: String, maxLength: Int): String {
        var sanitized = BLUETOOTH_ADDRESS_PATTERN.replace(
            value.filterNot(Char::isISOControl),
            "[redacted]",
        )
        redactions.sortedByDescending(String::length).forEach { sensitive ->
            sanitized = sanitized.replace(sensitive, "[redacted]", ignoreCase = true)
        }
        return sanitized.takeCodePoints(maxLength)
    }

    private fun sanitizeDetail(detail: String): String = sanitize(detail, MAX_EVENT_DETAIL_LENGTH)

    private fun validateObservationInput(
        session: SessionMetadata,
        observation: SynchronizedObservation,
        estimate: EstimateUiModel?,
    ) {
        val ble = observation.ble
        require(ble.deviceKey.value.isNotBlank()) { "Observation source identity must not be blank" }
        require(ble.observedAtPlatformNanos >= 0L && ble.receivedAtElapsedNanos >= 0L) {
            "Observation timestamps must be non-negative"
        }
        require(ble.receivedAtElapsedNanos >= lastObservationAppNanos) {
            "Observation app timestamps must not decrease"
        }
        require(ble.rssiDbm in -127..20) { "RSSI must be between -127 and 20 dBm" }
        require(ble.serviceUuids.all(String::isNotBlank)) {
            "Service UUIDs must not be blank"
        }
        require(ble.serviceUuids.none(BLUETOOTH_ADDRESS_PATTERN::containsMatchIn)) {
            "Advertisement data must not contain Bluetooth addresses"
        }

        val pose = observation.pose
        val associationAge = observation.poseAssociationAgeNanos
        require((pose == null) == (associationAge == null)) {
            "Pose and pose association age must be present together"
        }
        if (pose != null) {
            validatePose(pose)
            require(associationAge == absoluteDifference(
                pose.receivedAtElapsedNanos,
                ble.receivedAtElapsedNanos,
            )) {
                "Pose association age must match callback timestamps"
            }
        }
        require(associationAge == null || associationAge >= 0L) {
            "Pose association age must be non-negative"
        }
        require(observation.filteredRssiDbm?.isFinite() != false) {
            "Filtered RSSI must be finite"
        }
        require(
            observation.rssiVarianceDb2 == null ||
                observation.rssiVarianceDb2.isFinite() && observation.rssiVarianceDb2 > 0.0,
        ) {
            "RSSI variance must be finite and positive"
        }
        if (observation.accepted) {
            require(
                pose?.trackingState == PoseTrackingState.TRACKING &&
                    associationAge != null &&
                    observation.rssiVarianceDb2 != null &&
                    observation.rejectionReason == null,
            ) {
                "Accepted observations require a tracking pose, association age, " +
                    "positive RSSI variance, and no rejection reason"
            }
        } else {
            require(!observation.rejectionReason.isNullOrBlank()) {
                "Rejected observations require a rejection reason"
            }
        }
        require(
            observation.rejectionReason == null ||
                !BLUETOOTH_ADDRESS_PATTERN.containsMatchIn(observation.rejectionReason),
        ) {
            "Rejection reasons must not contain Bluetooth addresses"
        }
        validateEstimate(estimate)
        requireSelectedTarget(session, ble.deviceKey, ble.transport)
    }

    private fun validatePose(pose: PoseObservation) {
        require(pose.capturedAtPlatformNanos >= 0L && pose.receivedAtElapsedNanos >= 0L) {
            "Pose timestamps must be non-negative"
        }
        val values = listOf(
            pose.xMetres,
            pose.yMetres,
            pose.zMetres,
            pose.qx,
            pose.qy,
            pose.qz,
            pose.qw,
        )
        require(values.all(Double::isFinite)) { "Pose values must be finite" }
        val quaternionNormSquared =
            pose.qx * pose.qx + pose.qy * pose.qy + pose.qz * pose.qz + pose.qw * pose.qw
        require(quaternionNormSquared in 0.8..1.2) {
            "Pose quaternion must be approximately normalized"
        }
    }

    private fun validateEstimate(estimate: EstimateUiModel?) {
        if (estimate == null) return
        val mean = listOf(estimate.meanXMetres, estimate.meanZMetres)
        require(mean.all { it == null } || mean.all { it?.isFinite() == true }) {
            "Estimator mean requires two finite values or two nulls"
        }
        val covariance = listOf(estimate.covarianceXX, estimate.covarianceXZ, estimate.covarianceZZ)
        require(covariance.all { it == null } || covariance.all { it?.isFinite() == true }) {
            "Estimator covariance requires three finite values or three nulls"
        }
        if (covariance.all { it != null }) {
            val (xx, xz, zz) = covariance.map { checkNotNull(it) }
            require(xx >= 0.0 && zz >= 0.0 && xx * zz - xz * xz >= -1e-10) {
                "Estimator covariance must be positive semidefinite"
            }
        }
        require(
            estimate.regionProbability == null ||
                estimate.regionProbability.isFinite() &&
                estimate.regionProbability > 0.0 &&
                estimate.regionProbability < 1.0,
        ) {
            "Estimator region probability must be between zero and one"
        }
    }

    private fun requireSelectedTarget(
        session: SessionMetadata,
        sourceKey: DeviceKey?,
        transport: BluetoothTransport?,
    ) {
        if (session.mode != TraceMode.SINGLE_TARGET || sourceKey == null) return
        val target = checkNotNull(session.target)
        require(sourceKey == target.key && transport == target.transport) {
            "Single-target traces only accept the selected target identity and transport"
        }
    }

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first

    private fun recoverActiveFiles() {
        ensureTraceDirectory()
        traceDirectory.listFiles { file -> file.name.endsWith(ACTIVE_SUFFIX) }
            .orEmpty()
            .forEach(::recoverActiveFile)
        pruneCompleted()
        val quarantinedCount = traceDirectory.listFiles { file ->
            file.name.endsWith(QUARANTINE_SUFFIX)
        }.orEmpty().size
        if (quarantinedCount > 0 && storageError == null) {
            storageError = "$quarantinedCount malformed local trace file(s) quarantined"
        }
    }

    private fun recoverActiveFile(file: File) {
        val recovered = runCatching { inspectActiveFile(file) }.getOrElse { error ->
            storageError = "Could not read active trace ${file.name}: " +
                (error.message ?: "storage error")
            return
        }
        if (recovered == null) {
            quarantine(file)
            return
        }
        val (session, summary) = recovered
        val endedAtMillis = wallClockMillis()
        val fileSessionId = file.name
            .removePrefix("signal-trace-")
            .removeSuffix(ACTIVE_SUFFIX)
        if (session.sessionId != fileSessionId) {
            quarantine(file)
            return
        }
        if (!summary.hasOutcome) {
            val appended = runCatching {
                file.appendText(
                    TraceJsonEncoder.outcome(
                        sessionId = session.sessionId,
                        endedAtUtc = formatUtc(endedAtMillis),
                        durationMillis = recoveryDurationMillis(
                            session,
                            endedAtMillis,
                            monotonicClockNanos(),
                        ),
                        outcome = TraceOutcome.INCOMPLETE,
                        reason = null,
                        note = null,
                        observedDeviceCount = summary.observedDeviceCount,
                        localizedDeviceCount = summary.deviceStates.count(DeviceState::localized),
                        pathLengthMetres = 0.0,
                        deviceStates = summary.deviceStates,
                    ) + "\n",
                )
            }
            if (appended.isFailure) {
                storageError = "Could not close recovered trace ${file.name}: " +
                    (appended.exceptionOrNull()?.message ?: "storage error")
                return
            }
        }
        val completed = runCatching { completedFile(file) }.getOrElse { error ->
            storageError = "Could not allocate recovered trace generation: " +
                (error.message ?: "storage error")
            return
        }
        if (!runCatching { renameFile(file, completed) }.getOrDefault(false)) {
            storageError = "Could not publish recovered trace ${file.name}"
        }
    }

    private fun inspectActiveFile(file: File): Pair<RecoverySession, RecoverySummary>? {
        var session: RecoverySession? = null
        val states = linkedMapOf<DeviceKey, DeviceState>()
        val observedKeys = linkedSetOf<DeviceKey>()
        var previousSequence = -1L
        var previousAppMonotonicNanos = -1L
        var hasOutcome = false
        RandomAccessFile(file, "rw").use { active ->
            val endedWithNewline = active.length() > 0L && run {
                active.seek(active.length() - 1L)
                active.readByte() == '\n'.code.toByte()
            }
            active.seek(0L)
            while (true) {
                val lineStart = active.filePointer
                val recordResult = runCatching { active.readUtf8Line() }
                if (recordResult.isFailure) {
                    if (active.filePointer == active.length() && !endedWithNewline && session != null) {
                        active.setLength(lineStart)
                        break
                    }
                    return null
                }
                val record = recordResult.getOrThrow() ?: break
                if (record.isBlank()) continue
                val parsed = runCatching { TraceV2Validator.parseObject(record) }.getOrNull()
                if (parsed == null) {
                    if (active.filePointer == active.length() && !endedWithNewline && session != null) {
                        active.setLength(lineStart)
                        break
                    }
                    return null
                }
                if (session == null) {
                    session = runCatching { TraceV2Validator.session(parsed) }.getOrNull()
                        ?: return null
                    session.selectedState?.let { states[it.key] = it }
                    continue
                }
                if (hasOutcome) return null
                when (runCatching { parsed.requiredString("record_type") }.getOrNull()) {
                    "observation" -> {
                        val observation = runCatching {
                            TraceV2Validator.observation(parsed, session)
                        }.getOrNull() ?: return null
                        if (
                            observation.sequence <= previousSequence ||
                            observation.appMonotonicNanos < previousAppMonotonicNanos
                        ) {
                            return null
                        }
                        val state = observation.state
                        if (states[state.key]?.transport?.let { it != state.transport } == true) {
                            return null
                        }
                        states[state.key] = state
                        observedKeys += state.key
                        previousSequence = observation.sequence
                        previousAppMonotonicNanos = observation.appMonotonicNanos
                    }
                    "event" -> {
                        val event = runCatching {
                            TraceV2Validator.event(parsed, session)
                        }.getOrNull() ?: return null
                        val key = event.key
                        val transport = event.transport
                        if (key != null && transport != null) {
                            if (states[key]?.transport?.let { it != transport } == true) return null
                            states.putIfAbsent(key, DeviceState(key, transport, null))
                        }
                    }
                    "outcome" -> {
                        if (runCatching {
                                TraceV2Validator.outcome(
                                    parsed,
                                    session,
                                    states,
                                    observedKeys,
                                )
                            }.isFailure
                        ) {
                            return null
                        }
                        hasOutcome = true
                    }
                    else -> return null
                }
            }
            if (session == null) return null
            val currentlyEndsWithNewline = active.length() > 0L && run {
                active.seek(active.length() - 1L)
                active.readByte() == '\n'.code.toByte()
            }
            if (active.length() > 0L && !currentlyEndsWithNewline) {
                active.seek(active.length())
                active.writeByte('\n'.code)
            }
        }
        return checkNotNull(session) to RecoverySummary(
            deviceStates = states.values.toList(),
            observedDeviceCount = observedKeys.size,
            hasOutcome = hasOutcome,
        )
    }

    private fun recoveryDurationMillis(
        session: RecoverySession,
        endedAtMillis: Long,
        endedAtMonotonicNanos: Long,
    ): Long {
        val monotonicStart = session.startedAtMonotonicNanos
        return if (
            monotonicStart != null &&
            session.bootId != null &&
            session.bootId == bootDigest(session.sessionId, bootIdProvider()) &&
            endedAtMonotonicNanos >= monotonicStart
        ) {
            (endedAtMonotonicNanos - monotonicStart) / 1_000_000L
        } else {
            (endedAtMillis - session.createdAtMillis).coerceAtLeast(0L)
        }
    }

    private fun quarantine(file: File) {
        val quarantined = File(
            traceDirectory,
            file.name.removeSuffix(ACTIVE_SUFFIX) + QUARANTINE_SUFFIX,
        )
        storageError = if (runCatching { renameFile(file, quarantined) }.getOrDefault(false)) {
            "Malformed local trace ${file.name} was quarantined"
        } else {
            "Could not quarantine malformed local trace ${file.name}"
        }
    }

    private fun completedFiles(): List<File> {
        val files = runCatching {
            traceDirectory.listFiles { file ->
                file.name.startsWith("signal-trace-") &&
                    file.name.endsWith(".ndjson") &&
                    !file.name.endsWith(ACTIVE_SUFFIX) &&
                    !file.name.endsWith(QUARANTINE_SUFFIX)
            }
        }.getOrNull() ?: run {
            storageError = "Could not read local trace storage"
            return emptyList()
        }
        return files.sortedWith(
            compareByDescending<File> { completionGeneration(it) ?: Long.MIN_VALUE }
                .thenByDescending { if (completionGeneration(it) == null) it.lastModified() else 0L }
                .thenBy(File::getName),
        )
    }

    private fun managedTraceFiles(): List<File> =
        runCatching {
            traceDirectory.listFiles { file ->
                file.name.startsWith("signal-trace-") && file.name.endsWith(".ndjson")
            }
        }.getOrNull()?.toList() ?: run {
            storageError = "Could not read local trace storage"
            emptyList()
        }

    private fun pruneCompleted() {
        val failed = completedFiles().drop(MAX_COMPLETED_TRACES).filterNot { file ->
            runCatching { deleteFile(file) }.getOrDefault(false)
        }
        if (failed.isNotEmpty()) {
            storageError = "Could not delete ${failed.size} expired local trace file(s)"
        }
    }

    private fun completedFile(active: File): File {
        val generation = nextCompletionGeneration()
        val sessionId = active.name.removePrefix("signal-trace-").removeSuffix(ACTIVE_SUFFIX)
        return File(
            traceDirectory,
            "signal-trace-g${generation.toString().padStart(20, '0')}-$sessionId.ndjson",
        )
    }

    private fun nextCompletionGeneration(): Long {
        val counter = File(traceDirectory, COMPLETION_GENERATION_FILE)
        val persisted = runCatching { counter.readText().trim().toLong() }.getOrDefault(0L)
        val encoded = traceDirectory.listFiles().orEmpty()
            .mapNotNull(::completionGeneration)
            .maxOrNull()
            ?: 0L
        val current = maxOf(persisted, encoded)
        check(current < Long.MAX_VALUE) { "Trace completion generation exhausted" }
        return (current + 1L).also { counter.writeText(it.toString()) }
    }

    private fun completionGeneration(file: File): Long? =
        COMPLETED_GENERATION_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    private fun ensureTraceDirectory() {
        check(traceDirectory.exists() || traceDirectory.mkdirs()) {
            "Could not create trace directory"
        }
    }

    private fun clearSession() {
        metadata = null
        activeFile = null
        sequence = 0L
        startedAtMonotonicNanos = 0L
        lastObservationAppNanos = -1L
        deviceKeys.clear()
        transports.clear()
        observedKeys.clear()
        latestEstimates.clear()
        redactions.clear()
    }

    private fun formatUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

    private fun safeFilePart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun bootDigest(sessionId: String, rawBootId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$sessionId\u0000$rawBootId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

internal fun unicodeLength(value: String) = value.codePointCount(0, value.length)

private fun String.takeCodePoints(maximumLength: Int): String =
    if (unicodeLength(this) <= maximumLength) this
    else substring(0, offsetByCodePoints(0, maximumLength))

private fun RandomAccessFile.readUtf8Line(): String? {
    val bytes = readLine()?.toByteArray(StandardCharsets.ISO_8859_1) ?: return null
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}
