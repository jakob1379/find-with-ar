package dev.arsignallocator.trace

import dev.arsignallocator.localization.EstimateState
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.isLocalized
import java.util.Locale

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
            field(
                "status",
                state.estimate?.status?.name?.lowercase(Locale.ROOT) ?: "insufficient_evidence",
            )
            append(",\"estimator\":")
            appendEstimator(state.estimate, state.localized)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendEstimator(
        estimate: EstimateUiModel?,
        localized: Boolean = estimate?.isLocalized() == true,
    ) {
        if (estimate == null) {
            append("null")
            return
        }
        append('{')
        field(
            "kind",
            if (estimate.status == EstimateState.EKF) "ekf" else "bayesian_grid",
            first = true,
        )
        field("status", estimate.status.name.lowercase(Locale.ROOT))
        field("localized", localized)
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
