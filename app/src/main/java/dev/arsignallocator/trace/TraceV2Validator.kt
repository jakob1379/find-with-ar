package dev.arsignallocator.trace

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.arsignallocator.localization.EstimateState
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val BOOT_DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")

private data class RecoveredEstimate(
    val model: EstimateUiModel,
    val localized: Boolean,
)

internal object TraceV2Validator {
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
        "kind", "status", "localized", "mean_x_m", "mean_z_m", "covariance_xx_m2",
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

    fun parseUtcMillis(value: String): Long = parseUtc(value)

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
            estimate = estimate?.model,
            localized = estimate?.localized == true,
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
            require(
                estimate == null ||
                    estimate.model.status.name.lowercase(Locale.ROOT) == status
            ) {
                "Device state status and estimator status disagree"
            }
            require(
                states.put(
                    key,
                    DeviceState(
                        key,
                        transport,
                        estimate?.model,
                        estimate?.localized == true,
                    ),
                ) == null,
            ) {
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
            require(recoveredStates[key]?.localized == state.localized) {
                "Outcome device localization changed"
            }
        }
        require(observedCount == observedKeys.size.toLong()) {
            "Outcome observed device count is invalid"
        }
        val derivedLocalizedCount = states.values.count(DeviceState::localized)
        require(localizedCount == derivedLocalizedCount.toLong()) {
            "Outcome localized device count is invalid"
        }
    }

    private fun estimator(record: JsonObject): RecoveredEstimate {
        requireAllowedFields(record, estimatorFields, "estimator")
        val kind = record.requiredString("kind")
        val status = record.requiredString("status")
        val localized = record.requiredBoolean("localized")
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
        return RecoveredEstimate(
            model = EstimateUiModel(
                status = EstimateState.valueOf(status.uppercase(Locale.ROOT)),
                meanXMetres = meanX,
                meanZMetres = meanZ,
                covarianceXX = covariance[0],
                covarianceXZ = covariance[1],
                covarianceZZ = covariance[2],
                regionProbability = probability,
                regionAreaSquareMetres = null,
                ambiguity = null,
            ),
            localized = localized,
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

internal fun JsonObject.requiredString(name: String): String {
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
