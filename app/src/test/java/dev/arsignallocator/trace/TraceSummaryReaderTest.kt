package dev.arsignallocator.trace

import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.TargetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceSummaryReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads redacted single-target aggregate`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = ArrayDeque(listOf(1_000L, 4_000L))::removeFirst,
            monotonicClockNanos = ArrayDeque(listOf(0L, 3_000_000_000L))::removeFirst,
            sessionIdFactory = { "single-summary" },
        )
        recorder.startSingleTarget(
            "1.0.0",
            "Phone",
            TargetHandle(
                DeviceKey("private-source"),
                "AA:BB:CC:DD:EE:FF",
                "Private advertised name",
                emptySet(),
                emptySet(),
                BluetoothTransport.LOW_ENERGY,
            ),
        )
        val summary = TraceSummaryReader.read(recorder.finish(TraceOutcome.NO).file!!)

        assertEquals(TraceMode.SINGLE_TARGET, summary.mode)
        assertEquals(1_000L, summary.startedAtEpochMillis)
        assertEquals(4_000L, summary.endedAtEpochMillis)
        assertEquals(3_000L, summary.durationMillis)
        assertEquals(TraceOutcome.NO, summary.outcome)
        assertEquals(0, summary.observationCount)
        assertEquals(0, summary.observedDeviceCount)
        assertFalse(summary.toString().contains("AA:BB"))
        assertFalse(summary.toString().contains("Private advertised name"))
    }

    @Test
    fun `reads complete-scan and recovered fixtures`() {
        val complete = TraceSummaryReader.read(sharedFixture("android_trace_v2.ndjson"))
        val recovered = TraceSummaryReader.read(sharedFixture("android_recovered_trace_v2.ndjson"))

        assertEquals(TraceMode.COMPLETE_SCAN, complete.mode)
        assertEquals(1_000L, complete.durationMillis)
        assertEquals(3, complete.observationCount)
        assertEquals(3, complete.acceptedObservationCount)
        assertEquals(2, complete.observedDeviceCount)
        assertEquals(1, complete.localizedDeviceCount)
        assertEquals(TraceOutcome.YES, complete.outcome)

        assertEquals(TraceMode.COMPLETE_SCAN, recovered.mode)
        assertEquals(5_000L, recovered.durationMillis)
        assertEquals(2, recovered.observedDeviceCount)
        assertEquals(1, recovered.localizedDeviceCount)
        assertEquals(TraceOutcome.INCOMPLETE, recovered.outcome)
    }

    @Test
    fun `refuses active and malformed traces`() {
        val active = temporaryFolder.newFile("signal-trace-test.active.ndjson")
        val malformed = temporaryFolder.newFile("signal-trace-test.ndjson").apply {
            writeText("{}\n")
        }

        assertThrows(IllegalArgumentException::class.java) {
            TraceSummaryReader.read(active)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceSummaryReader.read(malformed)
        }
    }

    private fun sharedFixture(name: String) =
        java.io.File(checkNotNull(System.getProperty("sharedFixturesDir")), name)
}
