package dev.arsignallocator

import dev.arsignallocator.trace.TraceMode
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceTransferPureTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `support body contains device context and only redacted session aggregates`() {
        val body = supportBody(
            SupportDetails(
                appVersion = "1.0.0",
                androidVersion = "16",
                phoneModel = "Test phone",
                sessionSummary = summary().redactedDescription(),
            ),
        )

        assertTrue(body.contains("App version: 1.0.0"))
        assertTrue(body.contains("Android version: 16"))
        assertTrue(body.contains("Phone model: Test phone"))
        assertTrue(body.contains("4 of 5 observations used"))
        listOf("address", "advertised name", "AA:BB:CC:DD:EE:FF").forEach {
            assertFalse(body.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `completed trace gate is directory scoped and rejects active files`() {
        val traces = temporaryFolder.newFolder("traces")
        val completed = File(traces, "trace.ndjson").apply { writeText("{}") }
        val active = File(traces, "trace.active.ndjson").apply { writeText("{}") }
        val outside = temporaryFolder.newFile("outside.ndjson")

        assertTrue(isCompletedTrace(completed, traces))
        assertFalse(isCompletedTrace(active, traces))
        assertFalse(isCompletedTrace(outside, traces))
    }

    @Test
    fun `pending export restoration returns only a currently completed trace`() {
        val traces = temporaryFolder.newFolder("traces")
        val completed = File(traces, "trace.ndjson").apply { writeText("{}") }
        val active = File(traces, "trace.active.ndjson").apply { writeText("{}") }
        val outside = temporaryFolder.newFile("outside.ndjson")

        assertEquals(completed.canonicalFile, completedTraceAt(completed.path, traces))
        assertNull(completedTraceAt(active.path, traces))
        assertNull(completedTraceAt(outside.path, traces))
        assertNull(completedTraceAt(null, traces))
    }

    @Test
    fun `content disclosure names included data and privacy exclusions`() {
        listOf(
            "relative phone poses",
            "radio measurements",
            "session-local device keys",
            "manufacturer and service identifiers",
            "outcome",
            "optional note",
            "hardware addresses",
            "advertised names",
            "GPS coordinates",
            "images",
        ).forEach { assertTrue(TRACE_CONTENT_DISCLOSURE.contains(it)) }
    }

    private fun summary() = TraceSummary(
        file = File("session.ndjson"),
        mode = TraceMode.SINGLE_TARGET,
        startedAtEpochMillis = 0L,
        endedAtEpochMillis = 1_000L,
        durationMillis = 1_000L,
        outcome = TraceOutcome.NO,
        observationCount = 5,
        acceptedObservationCount = 4,
        observedDeviceCount = 1,
        localizedDeviceCount = 1,
    )
}
