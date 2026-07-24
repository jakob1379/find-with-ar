package dev.arsignallocator.trace

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.ESTIMATE_STATUS_EKF
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.StringReader
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
private const val MAX_EVENT_DETAIL_LENGTH = 200
private const val MAX_OUTCOME_NOTE_LENGTH = 500
private const val ACTIVE_SUFFIX = ".active.ndjson"
private const val QUARANTINE_SUFFIX = ".quarantined.ndjson"
private const val COMPLETION_GENERATION_FILE = ".signal-trace-completion-generation"
private val BLUETOOTH_ADDRESS_PATTERN = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
private val BOOT_DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")
private val COMPLETED_GENERATION_PATTERN =
    Regex("""^signal-trace-g([0-9]{20})-.+\.ndjson$""")
private val PROCESS_BOOT_ID = runCatching {
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

private data class RecoverySession(
    val sessionId: String,
    val createdAtMillis: Long,
    val startedAtMonotonicNanos: Long?,
    val bootId: String?,
    val mode: TraceMode,
    val selectedState: DeviceState?,
)

private data class RecoveryObservation(
    val state: DeviceState,
    val sequence: Long,
    val appMonotonicNanos: Long,
)

private data class RecoveryEvent(
    val key: DeviceKey?,
    val transport: BluetoothTransport?,
)

data class TraceFinalizationResult(
    val file: File? = null,
    val error: String? = null,
)

class TraceRecorder(
    private val traceDirectory: File,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockNanos: () -> Long = System::nanoTime,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val bootIdProvider: () -> String = { PROCESS_BOOT_ID },
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
    fun initialize(): List<File> {
        storageError = null
        recoverActiveFiles()
        return completedFiles()
    }

    @Synchronized
    fun start(appVersion: String, deviceModel: String, target: TargetHandle): File =
        start(appVersion, deviceModel, TraceMode.SINGLE_TARGET, target)

    @Synchronized
    fun start(
        appVersion: String,
        deviceModel: String,
        mode: TraceMode,
        target: TargetHandle? = null,
    ): File {
        require((mode == TraceMode.SINGLE_TARGET) == (target != null)) {
            "SINGLE_TARGET requires a target; COMPLETE_SCAN must not persist one"
        }
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
                    localizedDeviceCount = states.count {
                        it.estimate?.meanXMetres != null && it.estimate.meanZMetres != null
                    },
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
        require(estimate.status in setOf("COLLECTING", "GRID_MULTIMODAL", "GRID_UNIMODAL", "EKF")) {
            "Estimator status is invalid"
        }
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
                        localizedDeviceCount = summary.deviceStates.count {
                            it.estimate?.meanXMetres != null && it.estimate.meanZMetres != null
                        },
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

private object TraceV2Validator {
    private val eventCodes = TraceEventCode.entries.map(TraceEventCode::wireValue).toSet()
    private val outcomes = TraceOutcome.entries.map(TraceOutcome::wireValue).toSet()
    private val reasons = TraceOutcomeReason.entries.map(TraceOutcomeReason::wireValue).toSet()
    private val sessionFields = setOf(
        "schema_version", "record_type", "session_id", "mode", "created_at_utc",
        "started_at_monotonic_ns", "boot_id", "app_version", "device_model",
        "coordinate_frame", "target", "environment", "ground_truth", "simulation",
    )
    private val targetFields =
        setOf("session_key", "transport", "manufacturer_ids", "service_uuids")
    private val groundTruthFields = setOf("x_m", "z_m")
    private val simulationFields = setOf(
        "seed", "target_y_m", "rssi_at_1m_dbm", "path_loss_exponent", "student_t_df",
        "noise_scale_db", "outlier_probability", "outlier_scale_db",
    )
    private val observationFields = setOf(
        "schema_version", "record_type", "session_id", "sequence", "device_key", "transport",
        "app_monotonic_ns", "platform_timestamp_ns", "rssi_dbm", "estimator_rssi_dbm",
        "tx_power_dbm", "accepted", "rejection_reason", "pose", "estimator",
        "pose_association_age_ns", "filtered_rssi_dbm", "rssi_variance_db2", "advertisement",
    )
    private val poseFields =
        setOf("x_m", "y_m", "z_m", "qx", "qy", "qz", "qw", "tracking_state")
    private val advertisementFields =
        setOf("manufacturer_ids", "service_uuids", "synthetic_outlier")
    private val estimatorFields = setOf(
        "kind", "status", "mean_x_m", "mean_z_m", "covariance_xx_m2",
        "covariance_xz_m2", "covariance_zz_m2", "region_probability",
    )
    private val eventFields = setOf(
        "schema_version", "record_type", "session_id", "app_monotonic_ns",
        "device_key", "transport", "code", "detail",
    )
    private val outcomeFields = setOf(
        "schema_version", "record_type", "session_id", "ended_at_utc", "duration_ms",
        "outcome", "reason", "note", "observed_device_count", "localized_device_count",
        "path_length_m", "device_states",
    )
    private val deviceStateFields = setOf("device_key", "transport", "status", "estimator")

    fun parseObject(record: String): JsonObject {
        val reader = JsonReader(StringReader(record)).apply { strictness = Strictness.STRICT }
        val parsed = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trace record has trailing data" }
        require(parsed.isJsonObject) { "Trace record must be a JSON object" }
        return parsed.asJsonObject.also {
            rejectPrivateData(it)
            requireSigned64Integers(it)
        }
    }

    fun session(record: JsonObject): RecoverySession {
        requireRecord(record, "session")
        requireAllowedFields(record, sessionFields, "session")
        val sessionId = record.requiredString("session_id")
        require(sessionId.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-"
            }
        ) {
            "Session ID contains unsafe characters"
        }
        val mode = runCatching { TraceMode.valueOf(record.requiredString("mode")) }.getOrNull()
        requireNotNull(mode) { "Trace mode is invalid" }
        val coordinateFrame = record.requiredString("coordinate_frame")
        require(coordinateFrame == "arcore_xz_m") { "Coordinate frame is invalid" }
        record.requiredString("app_version")
        record.requiredString("device_model")
        val createdAtMillis = parseUtc(record.requiredString("created_at_utc"))
        val startedAtMonotonicNanos = record.optionalLong("started_at_monotonic_ns")
        require(startedAtMonotonicNanos == null || startedAtMonotonicNanos >= 0L) {
            "Session monotonic timestamp must be non-negative"
        }
        val bootId = record.optionalString("boot_id")
        require(bootId == null || BOOT_DIGEST_PATTERN.matches(bootId)) {
            "Boot identity must be a lowercase SHA-256 digest"
        }
        record.optionalString("environment")
        record.optionalObject("ground_truth")?.let {
            requireAllowedFields(it, groundTruthFields, "ground_truth")
            it.requiredDouble("x_m")
            it.requiredDouble("z_m")
        }
        record.optionalObject("simulation")?.let {
            requireAllowedFields(it, simulationFields, "simulation")
        }
        val target = record.optionalObject("target")
        require((mode == TraceMode.SINGLE_TARGET) == (target != null)) {
            "Single-target mode and target metadata disagree"
        }
        val selectedState = target?.let {
            requireAllowedFields(it, targetFields, "target")
            validateIdentityArrays(it)
            DeviceState(
                key = it.requiredDeviceKey("session_key"),
                transport = it.requiredTransport(),
                estimate = null,
            )
        }
        return RecoverySession(
            sessionId = sessionId,
            createdAtMillis = createdAtMillis,
            startedAtMonotonicNanos = startedAtMonotonicNanos,
            bootId = bootId,
            mode = mode,
            selectedState = selectedState,
        )
    }

    fun observation(record: JsonObject, session: RecoverySession): RecoveryObservation {
        requireRecord(record, "observation", session.sessionId)
        requireAllowedFields(record, observationFields, "observation")
        val sequence = record.requiredLong("sequence")
        val appMonotonicNanos = record.requiredLong("app_monotonic_ns")
        val platformTimestampNanos = record.requiredLong("platform_timestamp_ns")
        require(sequence >= 0L && appMonotonicNanos >= 0L && platformTimestampNanos >= 0L) {
            "Observation sequence and timestamps must be non-negative"
        }
        require(record.requiredLong("rssi_dbm") in -127L..20L) { "RSSI is out of range" }
        record.optionalLong("tx_power_dbm")
        val estimatorRssi = record.optionalDouble("estimator_rssi_dbm")
        val accepted = record.requiredBoolean("accepted")
        val rejectionReason = record.optionalString("rejection_reason")
        val pose = record.optionalObject("pose")
        if (pose != null) validatePose(pose)
        val associationAge = record.optionalLong("pose_association_age_ns")
        require(associationAge == null || associationAge >= 0L) {
            "Pose association age must be non-negative"
        }
        record.optionalDouble("filtered_rssi_dbm")
        val variance = record.optionalDouble("rssi_variance_db2")
        require(variance == null || variance > 0.0) { "RSSI variance must be positive" }
        if (accepted) {
            require(
                pose?.requiredString("tracking_state")?.uppercase(Locale.ROOT) == "TRACKING" &&
                    estimatorRssi != null &&
                    associationAge != null &&
                    variance != null &&
                    rejectionReason == null,
            ) {
                "Accepted observation fields are inconsistent"
            }
        } else {
            require(rejectionReason != null) { "Rejected observations require a reason" }
            require(estimatorRssi == null) { "Rejected observations cannot have estimator RSSI" }
        }
        val estimate = record.optionalObject("estimator")?.let(::estimator)
        record.get("advertisement")?.let {
            require(it.isJsonObject) { "Advertisement must be an object" }
            requireAllowedFields(it.asJsonObject, advertisementFields, "advertisement")
            it.asJsonObject.get("synthetic_outlier")?.let { outlier ->
                require(outlier.isJsonPrimitive && outlier.asJsonPrimitive.isBoolean) {
                    "Synthetic outlier marker must be a boolean"
                }
            }
            validateIdentityArrays(it.asJsonObject)
        }
        val state = DeviceState(
            key = record.requiredDeviceKey("device_key"),
            transport = record.requiredTransport(),
            estimate = estimate,
        )
        requireSelectedIdentity(session, state.key, state.transport)
        return RecoveryObservation(state, sequence, appMonotonicNanos)
    }

    fun event(record: JsonObject, session: RecoverySession): RecoveryEvent {
        requireRecord(record, "event", session.sessionId)
        requireAllowedFields(record, eventFields, "event")
        require(record.requiredLong("app_monotonic_ns") >= 0L) {
            "Event timestamp must be non-negative"
        }
        require(record.requiredString("code") in eventCodes) { "Event code is invalid" }
        val key = record.optionalString("device_key")?.let {
            requireValidDeviceKey(it)
            DeviceKey(it)
        }
        val transport = record.optionalTransport()
        require((key == null) == (transport == null)) {
            "Device-scoped event identity is incomplete"
        }
        record.optionalBoundaryText("detail", MAX_EVENT_DETAIL_LENGTH)
        if (key != null && transport != null) requireSelectedIdentity(session, key, transport)
        return RecoveryEvent(key, transport)
    }

    fun outcome(
        record: JsonObject,
        session: RecoverySession,
        recoveredStates: Map<DeviceKey, DeviceState>,
        observedKeys: Set<DeviceKey>,
    ) {
        requireRecord(record, "outcome", session.sessionId)
        requireAllowedFields(record, outcomeFields, "outcome")
        parseUtc(record.requiredString("ended_at_utc"))
        val durationMillis = record.requiredLong("duration_ms")
        val outcome = record.requiredString("outcome")
        val reason = record.optionalString("reason")
        record.optionalBoundaryText("note", MAX_OUTCOME_NOTE_LENGTH)
        val observedCount = record.requiredLong("observed_device_count")
        val localizedCount = record.requiredLong("localized_device_count")
        val pathLength = record.requiredDouble("path_length_m")
        require(
            durationMillis >= 0L &&
                observedCount >= 0L &&
                localizedCount >= 0L &&
                localizedCount <= observedCount &&
                pathLength >= 0.0,
        ) {
            "Outcome duration, counts, and path length are invalid"
        }
        require(outcome in outcomes) { "Outcome is invalid" }
        require(reason == null || reason in reasons) { "Outcome reason is invalid" }
        require(reason == null || outcome == "NO" || outcome == "NOT_SURE") {
            "Outcome reason does not match outcome"
        }

        val states = linkedMapOf<DeviceKey, DeviceState>()
        record.requiredArray("device_states").forEach { element ->
            require(element.isJsonObject) { "Device state must be an object" }
            val stateRecord = element.asJsonObject
            requireAllowedFields(stateRecord, deviceStateFields, "device state")
            val key = stateRecord.requiredDeviceKey("device_key")
            val transport = stateRecord.requiredTransport()
            val status = stateRecord.requiredString("status")
            val estimate = stateRecord.optionalObject("estimator")?.let(::estimator)
            require((status == "insufficient_evidence") == (estimate == null)) {
                "Device state status and estimator disagree"
            }
            require(estimate == null || estimate.status.lowercase(Locale.ROOT) == status) {
                "Device state status and estimator status disagree"
            }
            require(states.put(key, DeviceState(key, transport, estimate)) == null) {
                "Outcome repeats a device key"
            }
        }

        val expectedKeys = if (session.mode == TraceMode.SINGLE_TARGET) {
            setOf(checkNotNull(session.selectedState).key)
        } else {
            recoveredStates.keys
        }
        require(states.keys == expectedKeys) { "Outcome device states do not match trace identities" }
        states.forEach { (key, state) ->
            require(recoveredStates[key]?.transport == state.transport) {
                "Outcome device transport changed"
            }
        }
        require(observedCount == observedKeys.size.toLong()) {
            "Outcome observed device count is invalid"
        }
        val derivedLocalizedCount = states.values.count {
            it.estimate?.meanXMetres != null && it.estimate.meanZMetres != null
        }
        require(localizedCount == derivedLocalizedCount.toLong()) {
            "Outcome localized device count is invalid"
        }
    }

    private fun estimator(record: JsonObject): EstimateUiModel {
        requireAllowedFields(record, estimatorFields, "estimator")
        val kind = record.requiredString("kind")
        val status = record.requiredString("status")
        require(
            kind == "bayesian_grid" &&
                status in setOf("collecting", "grid_multimodal", "grid_unimodal") ||
                kind == "ekf" && status == "ekf",
        ) {
            "Estimator kind and status disagree"
        }
        val meanX = record.optionalDouble("mean_x_m")
        val meanZ = record.optionalDouble("mean_z_m")
        require((meanX == null) == (meanZ == null)) { "Estimator mean is incomplete" }
        val covariance = listOf(
            record.optionalDouble("covariance_xx_m2"),
            record.optionalDouble("covariance_xz_m2"),
            record.optionalDouble("covariance_zz_m2"),
        )
        require(covariance.all { it == null } || covariance.all { it != null }) {
            "Estimator covariance is incomplete"
        }
        if (covariance.all { it != null }) {
            val (xx, xz, zz) = covariance.map { checkNotNull(it) }
            require(xx >= 0.0 && zz >= 0.0 && xx * zz - xz * xz >= -1e-10) {
                "Estimator covariance is invalid"
            }
        }
        val probability = record.optionalDouble("region_probability")
        require(probability == null || probability > 0.0 && probability < 1.0) {
            "Estimator region probability is invalid"
        }
        return EstimateUiModel(
            status = status.uppercase(Locale.ROOT),
            meanXMetres = meanX,
            meanZMetres = meanZ,
            covarianceXX = covariance[0],
            covarianceXZ = covariance[1],
            covarianceZZ = covariance[2],
            regionProbability = probability,
            regionAreaSquareMetres = null,
            ambiguity = null,
        )
    }

    private fun validatePose(record: JsonObject) {
        requireAllowedFields(record, poseFields, "pose")
        val values = listOf("x_m", "y_m", "z_m", "qx", "qy", "qz", "qw")
            .associateWith(record::requiredDouble)
        require(record.requiredString("tracking_state") in setOf("TRACKING", "PAUSED", "STOPPED")) {
            "Pose tracking state is invalid"
        }
        val norm = values.getValue("qx") * values.getValue("qx") +
            values.getValue("qy") * values.getValue("qy") +
            values.getValue("qz") * values.getValue("qz") +
            values.getValue("qw") * values.getValue("qw")
        require(norm in 0.8..1.2) { "Pose quaternion is invalid" }
    }

    private fun validateIdentityArrays(record: JsonObject) {
        record.get("manufacturer_ids")?.let { values ->
            require(values.isJsonArray) { "Manufacturer IDs must be an array" }
            values.asJsonArray.forEach { it.requiredLong() }
        }
        record.get("service_uuids")?.let { values ->
            require(values.isJsonArray) { "Service UUIDs must be an array" }
            values.asJsonArray.forEach {
                require(
                    it.isJsonPrimitive &&
                        it.asJsonPrimitive.isString &&
                        it.asString.isNotBlank(),
                ) {
                    "Service UUID must be a non-empty string"
                }
            }
        }
        record.get("transport")?.takeUnless(JsonElement::isJsonNull)?.let {
            record.requiredTransport()
        }
    }

    private fun requireRecord(record: JsonObject, type: String, sessionId: String? = null) {
        require(record.requiredLong("schema_version") == TRACE_SCHEMA_VERSION.toLong()) {
            "Schema version is invalid"
        }
        require(record.requiredString("record_type") == type) { "Record type is invalid" }
        if (sessionId != null) {
            require(record.requiredString("session_id") == sessionId) { "Session identity changed" }
        }
    }

    private fun requireSelectedIdentity(
        session: RecoverySession,
        key: DeviceKey,
        transport: BluetoothTransport,
    ) {
        if (session.mode != TraceMode.SINGLE_TARGET) return
        val selected = checkNotNull(session.selectedState)
        require(key == selected.key && transport == selected.transport) {
            "Single-target record identity changed"
        }
    }

    private fun requireAllowedFields(
        record: JsonObject,
        allowed: Set<String>,
        objectName: String,
    ) {
        val unsupported = record.keySet().firstOrNull { it !in allowed }
        require(unsupported == null) { "$objectName contains unsupported field $unsupported" }
    }

    private fun rejectPrivateData(value: JsonElement) {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                require(
                    key.lowercase(Locale.ROOT) !in
                        setOf("address", "ephemeral_address", "advertised_name", "display_name"),
                ) {
                    "Trace contains a private field"
                }
                rejectPrivateData(child)
            }
            value.isJsonArray -> value.asJsonArray.forEach(::rejectPrivateData)
            value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                require(!BLUETOOTH_ADDRESS_PATTERN.containsMatchIn(value.asString)) {
                    "Trace contains a Bluetooth address"
                }
        }
    }

    private fun requireSigned64Integers(value: JsonElement) {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet()
                .forEach { (_, child) -> requireSigned64Integers(child) }
            value.isJsonArray -> value.asJsonArray.forEach(::requireSigned64Integers)
            value.isJsonPrimitive &&
                value.asJsonPrimitive.isNumber &&
                INTEGER_PATTERN.matches(value.asString) ->
                require(value.asString.toLongOrNull() != null) {
                    "Integer must fit a signed 64-bit value"
                }
        }
    }

    private fun parseUtc(value: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = runCatching { formatter.parse(value) }.getOrNull()
        require(parsed != null && formatter.format(parsed) == value) { "UTC timestamp is invalid" }
        return parsed.time
    }
}

private fun JsonObject.requiredString(name: String): String {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "$name must be a string"
    }
    return value.asString.also { require(it.isNotBlank()) { "$name must not be blank" } }
}

private fun JsonObject.optionalString(name: String): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
    return value.asString.also { require(it.isNotBlank()) { "$name must not be blank" } }
}

private fun JsonObject.optionalBoundaryText(name: String, maximumLength: Int): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
    return value.asString.takeIf(String::isNotBlank)?.also {
        require(unicodeLength(it) <= maximumLength && it.none(Char::isISOControl)) {
            "$name is invalid"
        }
    }
}

private fun unicodeLength(value: String) = value.codePointCount(0, value.length)

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

private fun JsonObject.requiredLong(name: String): Long =
    requireNotNull(get(name)) { "$name is required" }.requiredLong()

private fun JsonElement.requiredLong(): Long {
    require(isJsonPrimitive && asJsonPrimitive.isNumber) { "Value must be an integer" }
    return requireNotNull(asString.toLongOrNull()) { "Value must be an integer" }
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = get(name) ?: return null
    return if (value.isJsonNull) null else value.requiredLong()
}

private fun JsonObject.requiredDouble(name: String): Double {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
        "$name must be a number"
    }
    return requireNotNull(value.asString.toDoubleOrNull()?.takeIf(Double::isFinite)) {
        "$name must be finite"
    }
}

private fun JsonObject.optionalDouble(name: String): Double? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    return requiredDouble(name)
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
        "$name must be a boolean"
    }
    return value.asBoolean
}

private fun JsonObject.optionalObject(name: String): JsonObject? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonObject) { "$name must be an object" }
    return value.asJsonObject
}

private fun JsonObject.requiredArray(name: String) =
    requireNotNull(optionalArray(name)) { "$name must be an array" }

private fun JsonObject.optionalArray(name: String) = get(name)?.let {
    if (it.isJsonNull) return@let null
    require(it.isJsonArray) { "$name must be an array" }
    it.asJsonArray
}

private fun JsonObject.requiredDeviceKey(name: String): DeviceKey =
    requiredString(name).also(::requireValidDeviceKey).let(::DeviceKey)

private fun requireValidDeviceKey(value: String) {
    require(value.length >= 3 && value.first() == 'D' && value.drop(1).all { it in '0'..'9' }) {
        "Device key is invalid"
    }
}

private fun JsonObject.requiredTransport(): BluetoothTransport =
    requireNotNull(optionalTransport()) { "Transport is required" }

private fun JsonObject.optionalTransport(): BluetoothTransport? =
    when (optionalString("transport")) {
        null -> null
        "low_energy" -> BluetoothTransport.LOW_ENERGY
        "classic" -> BluetoothTransport.CLASSIC
        else -> error("Transport is invalid")
    }

object TraceJsonEncoder {
    fun session(metadata: SessionMetadata): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "session")
        field("session_id", metadata.sessionId)
        field("mode", metadata.mode.name)
        field("created_at_utc", metadata.createdAtUtc)
        field("started_at_monotonic_ns", metadata.startedAtMonotonicNanos)
        field("boot_id", metadata.bootId)
        field("app_version", metadata.appVersion)
        field("device_model", metadata.deviceModel)
        field("coordinate_frame", "arcore_xz_m")
        metadata.target?.let { target ->
            append(",\"target\":{")
            field("session_key", "D01", first = true)
            field("transport", target.transport.name.lowercase(Locale.ROOT))
            intArrayField("manufacturer_ids", target.manufacturerIds.sorted())
            stringArrayField("service_uuids", target.serviceUuids.sorted())
            append('}')
        }
        append('}')
    }

    fun observation(
        sessionId: String,
        sequence: Long,
        deviceKey: DeviceKey,
        observation: SynchronizedObservation,
        estimate: EstimateUiModel?,
    ): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "observation")
        field("session_id", sessionId)
        field("sequence", sequence)
        field("device_key", deviceKey.value)
        field("transport", observation.ble.transport.name.lowercase(Locale.ROOT))
        field("app_monotonic_ns", observation.ble.receivedAtElapsedNanos)
        field("platform_timestamp_ns", observation.ble.observedAtPlatformNanos)
        field("rssi_dbm", observation.ble.rssiDbm)
        nullableField(
            "estimator_rssi_dbm",
            observation.ble.rssiDbm.takeIf { observation.accepted },
        )
        nullableField("tx_power_dbm", observation.ble.txPowerDbm)
        field("accepted", observation.accepted)
        nullableField("rejection_reason", observation.rejectionReason)
        append(",\"pose\":")
        val pose = observation.pose
        if (pose == null) {
            append("null")
        } else {
            append('{')
            field("x_m", pose.xMetres, first = true)
            field("y_m", pose.yMetres)
            field("z_m", pose.zMetres)
            field("qx", pose.qx)
            field("qy", pose.qy)
            field("qz", pose.qz)
            field("qw", pose.qw)
            field("tracking_state", pose.trackingState.name)
            append('}')
        }
        append(",\"estimator\":")
        appendEstimator(estimate)
        nullableField("pose_association_age_ns", observation.poseAssociationAgeNanos)
        nullableField("filtered_rssi_dbm", observation.filteredRssiDbm)
        nullableField("rssi_variance_db2", observation.rssiVarianceDb2)
        append(",\"advertisement\":{")
        intArrayField("manufacturer_ids", observation.ble.manufacturerIds.sorted(), first = true)
        stringArrayField("service_uuids", observation.ble.serviceUuids.sorted())
        append("}}")
    }

    fun event(
        sessionId: String,
        appMonotonicNanos: Long,
        code: TraceEventCode,
        deviceKey: DeviceKey?,
        transport: BluetoothTransport?,
        detail: String?,
    ): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "event")
        field("session_id", sessionId)
        field("app_monotonic_ns", appMonotonicNanos)
        nullableField("device_key", deviceKey?.value)
        nullableField("transport", transport?.name?.lowercase(Locale.ROOT))
        field("code", code.wireValue)
        nullableField("detail", detail)
        append('}')
    }

    internal fun outcome(
        sessionId: String,
        endedAtUtc: String,
        durationMillis: Long,
        outcome: TraceOutcome,
        reason: TraceOutcomeReason?,
        note: String?,
        observedDeviceCount: Int,
        localizedDeviceCount: Int,
        pathLengthMetres: Double,
        deviceStates: List<DeviceState>,
    ): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "outcome")
        field("session_id", sessionId)
        field("ended_at_utc", endedAtUtc)
        field("duration_ms", durationMillis)
        field("outcome", outcome.wireValue)
        nullableField("reason", reason?.wireValue)
        nullableField("note", note)
        field("observed_device_count", observedDeviceCount)
        field("localized_device_count", localizedDeviceCount)
        field("path_length_m", pathLengthMetres)
        append(",\"device_states\":[")
        deviceStates.forEachIndexed { index, state ->
            if (index > 0) append(',')
            append('{')
            field("device_key", state.key.value, first = true)
            field("transport", state.transport.name.lowercase(Locale.ROOT))
            field("status", state.estimate?.status?.lowercase(Locale.ROOT) ?: "insufficient_evidence")
            append(",\"estimator\":")
            appendEstimator(state.estimate)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendEstimator(estimate: EstimateUiModel?) {
        if (estimate == null) {
            append("null")
            return
        }
        append('{')
        field("kind", if (estimate.status == ESTIMATE_STATUS_EKF) "ekf" else "bayesian_grid", first = true)
        field("status", estimate.status.lowercase(Locale.ROOT))
        nullableField("mean_x_m", estimate.meanXMetres)
        nullableField("mean_z_m", estimate.meanZMetres)
        nullableField("covariance_xx_m2", estimate.covarianceXX)
        nullableField("covariance_xz_m2", estimate.covarianceXZ)
        nullableField("covariance_zz_m2", estimate.covarianceZZ)
        nullableField("region_probability", estimate.regionProbability)
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String, first: Boolean = false) {
        separator(first)
        appendQuoted(name)
        append(':')
        appendQuoted(value)
    }

    private fun StringBuilder.field(name: String, value: Boolean, first: Boolean = false) {
        separator(first)
        appendQuoted(name)
        append(':').append(value)
    }

    private fun StringBuilder.field(name: String, value: Number, first: Boolean = false) {
        separator(first)
        appendQuoted(name)
        append(':').append(jsonNumber(value))
    }

    private fun StringBuilder.nullableField(name: String, value: String?, first: Boolean = false) {
        separator(first)
        appendQuoted(name)
        append(':')
        if (value == null) append("null") else appendQuoted(value)
    }

    private fun StringBuilder.nullableField(name: String, value: Number?, first: Boolean = false) {
        separator(first)
        appendQuoted(name)
        append(':').append(value?.let(::jsonNumber) ?: "null")
    }

    private fun StringBuilder.intArrayField(
        name: String,
        values: List<Int>,
        first: Boolean = false,
    ) {
        separator(first)
        appendQuoted(name)
        append(':').append(values.joinToString(prefix = "[", postfix = "]"))
    }

    private fun StringBuilder.stringArrayField(
        name: String,
        values: List<String>,
        first: Boolean = false,
    ) {
        separator(first)
        appendQuoted(name)
        append(":[")
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendQuoted(value)
        }
        append(']')
    }

    private fun StringBuilder.separator(first: Boolean) {
        if (!first && length > 1 && last() != '{') append(',')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun jsonNumber(value: Number): String {
        val double = value.toDouble()
        return if (double.isFinite()) value.toString() else "null"
    }
}
