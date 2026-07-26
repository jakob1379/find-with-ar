package dev.arsignallocator.trace

import dev.arsignallocator.model.DeviceKey
import java.io.File

data class TraceSummary(
    val file: File,
    val mode: TraceMode,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMillis: Long,
    val outcome: TraceOutcome,
    val observationCount: Int,
    val acceptedObservationCount: Int,
    val observedDeviceCount: Int,
    val localizedDeviceCount: Int,
)

object TraceSummaryReader {
    fun read(file: File): TraceSummary {
        require(
            file.isFile &&
                file.name.endsWith(".ndjson") &&
                !file.name.endsWith(".active.ndjson"),
        ) {
            "Only completed traces can be summarized"
        }
        val records = file.useLines { lines ->
            lines.filter(String::isNotBlank).map(TraceV2Validator::parseObject).toList()
        }
        require(records.size >= 2) { "Completed trace is missing records" }
        val session = TraceV2Validator.session(records.first())
        val states = linkedMapOf<DeviceKey, DeviceState>()
        session.selectedState?.let { states[it.key] = it }
        val observedKeys = linkedSetOf<DeviceKey>()
        var previousSequence = -1L
        var previousAppNanos = -1L
        var observations = 0
        var accepted = 0

        records.drop(1).dropLast(1).forEach { record ->
            when (record["record_type"]?.asString) {
                "observation" -> {
                    val observation = TraceV2Validator.observation(record, session)
                    require(
                        observation.sequence > previousSequence &&
                            observation.appMonotonicNanos >= previousAppNanos,
                    ) {
                        "Observation order is invalid"
                    }
                    require(
                        states[observation.state.key]?.transport
                            ?.let { it == observation.state.transport } != false,
                    ) {
                        "Device transport changed"
                    }
                    states[observation.state.key] = observation.state
                    observedKeys += observation.state.key
                    previousSequence = observation.sequence
                    previousAppNanos = observation.appMonotonicNanos
                    observations += 1
                    if (record["accepted"].asBoolean) accepted += 1
                }
                "event" -> {
                    val event = TraceV2Validator.event(record, session)
                    if (event.key != null && event.transport != null) {
                        require(
                            states[event.key]?.transport?.let { it == event.transport } != false,
                        ) {
                            "Device transport changed"
                        }
                        states.putIfAbsent(event.key, DeviceState(event.key, event.transport, null))
                    }
                }
                else -> error("Completed trace contains an unsupported record")
            }
        }

        val outcomeRecord = records.last()
        TraceV2Validator.outcome(outcomeRecord, session, states, observedKeys)
        return TraceSummary(
            file = file,
            mode = session.mode,
            startedAtEpochMillis = session.createdAtMillis,
            endedAtEpochMillis = TraceV2Validator.parseUtcMillis(
                outcomeRecord["ended_at_utc"].asString,
            ),
            durationMillis = outcomeRecord["duration_ms"].asLong,
            outcome = TraceOutcome.valueOf(outcomeRecord["outcome"].asString),
            observationCount = observations,
            acceptedObservationCount = accepted,
            observedDeviceCount = outcomeRecord["observed_device_count"].asInt,
            localizedDeviceCount = outcomeRecord["localized_device_count"].asInt,
        )
    }
}
