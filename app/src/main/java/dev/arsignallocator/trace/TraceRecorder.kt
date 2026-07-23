package dev.arsignallocator.trace

import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

const val TRACE_SCHEMA_VERSION = 1

data class SessionMetadata(
    val sessionId: String,
    val createdAtUtc: String,
    val appVersion: String,
    val deviceModel: String,
    val target: TargetHandle,
)

class TraceRecorder(
    private val traceDirectory: File,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : Closeable {
    private var writer: BufferedWriter? = null
    private var metadata: SessionMetadata? = null
    private var sequence = 0L
    private var activeFile: File? = null

    @Synchronized
    fun start(appVersion: String, deviceModel: String, target: TargetHandle): File {
        close()
        check(traceDirectory.exists() || traceDirectory.mkdirs()) {
            "Could not create trace directory"
        }
        val session = SessionMetadata(
            sessionId = sessionIdFactory(),
            createdAtUtc = formatUtc(wallClockMillis()),
            appVersion = appVersion,
            deviceModel = deviceModel,
            target = target,
        )
        val file = File(traceDirectory, "signal-trace-${safeFilePart(session.sessionId)}.ndjson")
        writer = file.bufferedWriter()
        metadata = session
        sequence = 0L
        activeFile = file
        appendLine(TraceJsonEncoder.session(session))
        return file
    }

    @Synchronized
    fun record(observation: SynchronizedObservation, estimate: EstimateUiModel?) {
        val session = checkNotNull(metadata) { "TraceRecorder.start must be called first" }
        appendLine(TraceJsonEncoder.observation(session.sessionId, sequence++, observation, estimate))
    }

    @Synchronized
    fun stop(): File? {
        val result = activeFile
        close()
        return result
    }

    @Synchronized
    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
        metadata = null
        activeFile = null
    }

    private fun appendLine(line: String) {
        checkNotNull(writer).apply {
            write(line)
            newLine()
            // A feasibility trace is more valuable than a tiny throughput gain if the app dies.
            flush()
        }
    }

    private fun formatUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

    private fun safeFilePart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

object TraceJsonEncoder {
    fun session(metadata: SessionMetadata): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "session")
        field("session_id", metadata.sessionId)
        field("created_at_utc", metadata.createdAtUtc)
        field("app_version", metadata.appVersion)
        field("device_model", metadata.deviceModel)
        field("coordinate_frame", "arcore_xz_m")
        append(",\"target\":{")
        field("session_key", metadata.target.key.value, first = true)
        field(
            "display_name",
            metadata.target.advertisedName?.takeIf(String::isNotBlank)
                ?: "Bluetooth device ${metadata.target.key.value}",
        )
        field("transport", metadata.target.transport.name.lowercase(Locale.ROOT))
        intArrayField("manufacturer_ids", metadata.target.manufacturerIds.sorted())
        stringArrayField("service_uuids", metadata.target.serviceUuids.sorted())
        append("}}")
    }

    fun observation(
        sessionId: String,
        sequence: Long,
        observation: SynchronizedObservation,
        estimate: EstimateUiModel?,
    ): String = buildString {
        append('{')
        field("schema_version", TRACE_SCHEMA_VERSION)
        field("record_type", "observation")
        field("session_id", sessionId)
        field("sequence", sequence)
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
        if (estimate == null) {
            append("null")
        } else {
            append('{')
            field("kind", if (estimate.status == "EKF") "ekf" else "bayesian_grid", first = true)
            field("status", estimate.status.lowercase(Locale.ROOT))
            nullableField("mean_x_m", estimate.meanXMetres)
            nullableField("mean_z_m", estimate.meanZMetres)
            nullableField("covariance_xx_m2", estimate.covarianceXX)
            nullableField("covariance_xz_m2", estimate.covarianceXZ)
            nullableField("covariance_zz_m2", estimate.covarianceZZ)
            nullableField("region_probability", estimate.regionProbability)
            append('}')
        }
        nullableField("pose_association_age_ns", observation.poseAssociationAgeNanos)
        nullableField("filtered_rssi_dbm", observation.filteredRssiDbm)
        nullableField("rssi_variance_db2", observation.rssiVarianceDb2)
        append(",\"advertisement\":{")
        nullableField("advertised_name", observation.ble.advertisedName, first = true)
        field("transport", observation.ble.transport.name.lowercase(Locale.ROOT))
        intArrayField("manufacturer_ids", observation.ble.manufacturerIds.sorted())
        stringArrayField("service_uuids", observation.ble.serviceUuids.sorted())
        append('}')
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

    private fun StringBuilder.intArrayField(name: String, values: List<Int>) {
        append(',')
        appendQuoted(name)
        append(':').append(values.joinToString(prefix = "[", postfix = "]"))
    }

    private fun StringBuilder.stringArrayField(name: String, values: List<String>) {
        append(',')
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
