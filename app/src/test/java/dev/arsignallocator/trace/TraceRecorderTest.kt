package dev.arsignallocator.trace

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.arsignallocator.model.BleObservation
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.PoseObservation
import dev.arsignallocator.model.PoseTrackingState
import dev.arsignallocator.model.SynchronizedObservation
import dev.arsignallocator.model.TargetHandle
import java.io.BufferedWriter
import java.io.IOException
import java.io.Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceRecorderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes redacted schema v2 and completes active file`() {
        val wallTimes = ArrayDeque(listOf(0L, 1_000L))
        val monotonicTimes = ArrayDeque(listOf(0L, 1_000_000_000L))
        val target = target("source-key", "AA:BB:CC:DD:EE:FF", "Beacon one")
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { wallTimes.removeFirst() },
            monotonicClockNanos = { monotonicTimes.removeFirst() },
            sessionIdFactory = { "test-session" },
            bootIdProvider = { "raw-boot" },
        )

        val active = recorder.startSingleTarget("1.0.0", "Test phone", target)
        recorder.record(sample("source-key", "Secret beacon", 20L), null)
        val completed = recorder.finish(TraceOutcome.YES, pathLengthMetres = 1.5).file

        assertTrue(active.name.endsWith(".active.ndjson"))
        assertFalse(active.exists())
        assertTrue(completed!!.name.endsWith("-test-session.ndjson"))
        val payload = completed.readText()
        assertTrue(payload.contains("\"schema_version\":2"))
        assertTrue(payload.contains("\"mode\":\"SINGLE_TARGET\""))
        assertTrue(
            payload.contains(
                "\"boot_id\":\"412fd93a646f8d6e6f183c2a5ac7e7b68d819cad39026cd3784d95428db5c7ff\"",
            ),
        )
        assertFalse(payload.contains("raw-boot"))
        assertTrue(payload.contains("\"device_key\":\"D01\""))
        assertTrue(payload.contains("\"outcome\":\"YES\""))
        assertFalse(payload.contains("AA:BB"))
        assertFalse(payload.contains("Beacon one"))
        assertFalse(payload.contains("Secret beacon"))
        assertFalse(payload.contains("advertised_name"))
        assertFalse(payload.contains("display_name"))
    }

    @Test
    fun `complete scan remaps interleaved devices with one global sequence`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            sessionIdFactory = { "complete-scan" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        recorder.record(sample("hardware-a", "One", 100L), null)
        recorder.record(sample("hardware-a", "Two", 110L, BluetoothTransport.CLASSIC), null)
        recorder.recordEvent(
            appMonotonicNanos = 115L,
            code = TraceEventCode.SIGNAL_LOST,
            deviceKey = DeviceKey("hardware-a"),
            transport = BluetoothTransport.CLASSIC,
            detail = "Two lost at AA:BB:CC:DD:EE:FF\u0000",
        )
        recorder.record(sample("hardware-a", "One", 120L), null)
        val file = recorder.finish(TraceOutcome.INCOMPLETE).file!!

        val lines = file.readLines()
        assertTrue(lines[0].contains("\"mode\":\"COMPLETE_SCAN\""))
        assertTrue(lines[1].contains("\"sequence\":0,\"device_key\":\"D01\""))
        assertTrue(lines[2].contains("\"sequence\":1,\"device_key\":\"D02\""))
        assertTrue(
            lines[3].contains(
                "\"device_key\":\"D02\",\"transport\":\"classic\",\"code\":\"signal_lost\"",
            ),
        )
        assertTrue(lines[4].contains("\"sequence\":2,\"device_key\":\"D01\""))
        assertFalse(lines.joinToString().contains("hardware-"))
        assertFalse(lines.joinToString().contains("\"One\""))
        assertFalse(lines.joinToString().contains("\"Two\""))
        assertFalse(lines.joinToString().contains("AA:BB"))
    }

    @Test
    fun `device event rejects a source key without transport`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            sessionIdFactory = { "ambiguous-event" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        assertThrows(IllegalArgumentException::class.java) {
            recorder.recordEvent(
                appMonotonicNanos = 1L,
                code = TraceEventCode.SIGNAL_LOST,
                deviceKey = DeviceKey("shared-source"),
            )
        }
    }

    @Test
    fun `single target rejects other source identities and transports without advancing`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "single-identity" },
        )
        recorder.startSingleTarget("1.0.0", "Test phone", target("selected", "AA:BB:CC:DD:EE:FF", null))

        assertThrows(IllegalArgumentException::class.java) {
            recorder.record(sample("other", null, 10L), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recorder.record(sample("selected", null, 10L, BluetoothTransport.CLASSIC), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recorder.recordEvent(
                appMonotonicNanos = 10L,
                code = TraceEventCode.SIGNAL_LOST,
                deviceKey = DeviceKey("other"),
                transport = BluetoothTransport.LOW_ENERGY,
            )
        }

        recorder.record(sample("selected", null, 20L), null)
        val payload = recorder.finish(TraceOutcome.INCOMPLETE).file!!.readText()
        assertTrue(payload.contains("\"sequence\":0,\"device_key\":\"D01\""))
        assertFalse(payload.contains("\"device_key\":\"D02\""))
    }

    @Test
    fun `invalid observations never advance recorder state`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "invalid-inputs" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")
        val valid = sample("source", null, 100L)
        val invalid = listOf(
            valid.copy(ble = valid.ble.copy(receivedAtElapsedNanos = -1L)),
            valid.copy(ble = valid.ble.copy(rssiDbm = -128)),
            valid.copy(pose = valid.pose!!.copy(xMetres = Double.NaN)),
            valid.copy(poseAssociationAgeNanos = 3L),
            valid.copy(pose = valid.pose.copy(trackingState = PoseTrackingState.PAUSED)),
            valid.copy(rejectionReason = "unexpected"),
            valid.copy(accepted = false, rejectionReason = null),
            valid.copy(rssiVarianceDb2 = Double.POSITIVE_INFINITY),
        )
        invalid.forEach { observation ->
            assertThrows(IllegalArgumentException::class.java) {
                recorder.record(observation, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            recorder.record(valid, localizedEstimate("UNKNOWN"))
        }

        recorder.record(valid, null)
        val payload = recorder.finish(TraceOutcome.INCOMPLETE).file!!.readText()
        assertEquals(1, "\"record_type\":\"observation\"".toRegex().findAll(payload).count())
        assertTrue(payload.contains("\"sequence\":0,\"device_key\":\"D01\""))
    }

    @Test
    fun `blank event detail and outcome note are encoded as null`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "blank-boundaries" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")
        recorder.recordEvent(1L, TraceEventCode.AR_STATE, detail = " \t ")

        val payload = recorder.finish(TraceOutcome.SKIPPED, note = "\n").file!!.readText()

        assertTrue(payload.contains("\"detail\":null"))
        assertTrue(payload.contains("\"note\":null"))
    }

    @Test
    fun `outcome reason is only accepted for no or not sure`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "reason-combination" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        assertThrows(IllegalArgumentException::class.java) {
            recorder.finish(TraceOutcome.YES, TraceOutcomeReason.OTHER)
        }
        assertTrue(
            recorder.finish(TraceOutcome.NO, TraceOutcomeReason.OTHER).file
                ?.name
                ?.endsWith("-reason-combination.ndjson") == true,
        )
    }

    @Test
    fun `persists every survey answer and reason with Unicode code-point limit`() {
        TraceOutcome.entries.filterNot { it == TraceOutcome.INCOMPLETE }.forEachIndexed {
                index,
                outcome,
            ->
            val directory = temporaryFolder.newFolder("answer-$index")
            val recorder = TraceRecorder(
                traceDirectory = directory,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "answer-$index" },
            )
            recorder.startCompleteScan("1", "phone")
            val payload = recorder.finish(outcome).file!!.readText()
            assertTrue(payload.contains("\"outcome\":\"${outcome.wireValue}\""))
        }
        TraceOutcomeReason.entries.forEachIndexed { index, reason ->
            val directory = temporaryFolder.newFolder("reason-$index")
            val recorder = TraceRecorder(
                traceDirectory = directory,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "reason-$index" },
            )
            recorder.startCompleteScan("1", "phone")
            val payload = recorder.finish(
                if (index % 2 == 0) TraceOutcome.NO else TraceOutcome.NOT_SURE,
                reason,
                "😀".repeat(500),
            ).file!!.readText()
            assertTrue(payload.contains("\"reason\":\"${reason.wireValue}\""))
            assertTrue(payload.contains("😀".repeat(500)))
        }
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.newFolder("too-long"),
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "too-long" },
        )
        recorder.startCompleteScan("1", "phone")
        assertThrows(IllegalArgumentException::class.java) {
            recorder.finish(TraceOutcome.NO, note = "😀".repeat(501))
        }
    }

    @Test
    fun `history enumeration leaves live trace active and writable`() {
        val times = ArrayDeque(listOf(0L, 1_000L))
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { times.removeFirst() },
            sessionIdFactory = { "live" },
        )
        val active = recorder.startCompleteScan("1.0.0", "Test phone")

        assertTrue(recorder.completedTraces().isEmpty())
        assertTrue(active.exists())

        recorder.record(sample("hardware-a", "One", 100L), null)
        val completed = recorder.finish(TraceOutcome.YES).file!!

        assertFalse(active.exists())
        assertEquals(3, completed.readLines().size)
        assertTrue(completed.readText().contains("\"record_type\":\"observation\""))
    }

    @Test
    fun `recovery discards torn final record before appending incomplete outcome`() {
        val active = temporaryFolder.newFile("signal-trace-torn.active.ndjson")
        active.writeText(
            sessionLine("torn") +
                "\n{\"schema_version\":2,\"record_type\":\"observation\",\"session_id\":\"torn\"",
        )

        val recorder = TraceRecorder(
            temporaryFolder.root,
            wallClockMillis = { 1_000L },
            monotonicClockNanos = { 1_000_000_000L },
        )
        val recovered = recorder.recoverAndListCompletedTraces().single()
        val lines = recovered.readLines()

        assertFalse(active.exists())
        assertEquals(2, lines.size)
        assertEquals(sessionLine("torn"), lines.first())
        assertTrue(lines.last().contains("\"outcome\":\"INCOMPLETE\""))
    }

    @Test
    fun `recovery reconstructs device states counts and monotonic duration`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "observed" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")
        recorder.record(sample("source-a", null, 1_000_000_000L), localizedEstimate("GRID_MULTIMODAL"))
        recorder.record(
            sample("source-b", null, 2_000_000_000L, BluetoothTransport.CLASSIC),
            null,
        )
        recorder.record(sample("source-a", null, 3_500_000_000L), localizedEstimate("EKF"))
        recorder.close()

        val recovered = TraceRecorder(
            temporaryFolder.root,
            wallClockMillis = { 5_000L },
            monotonicClockNanos = { 5_000_000_000L },
        ).recoverAndListCompletedTraces().single()
        val outcome = recovered.readLines().last()

        assertTrue(outcome.contains("\"duration_ms\":5000"))
        assertTrue(outcome.contains("\"observed_device_count\":2"))
        assertTrue(outcome.contains("\"localized_device_count\":1"))
        assertTrue(
            outcome.contains(
                "\"device_key\":\"D01\",\"transport\":\"low_energy\",\"status\":\"ekf\"",
            ),
        )
        assertTrue(
            outcome.contains(
                "\"device_key\":\"D02\",\"transport\":\"classic\"," +
                    "\"status\":\"insufficient_evidence\"",
            ),
        )
    }

    @Test
    fun `finalization uses UI localization gates instead of estimator means`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "localization-gates" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")
        recorder.record(
            sample("source-a", null, 100L),
            localizedEstimate("GRID_MULTIMODAL"),
        )

        val outcome = recorder.finish(TraceOutcome.INCOMPLETE).file!!.readLines().last()

        assertTrue(outcome.contains("\"localized_device_count\":0"))
        assertTrue(outcome.contains("\"localized\":false"))
    }

    @Test
    fun `recovery preserves UI localization gate disagreements`() {
        TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "recovered-localization-gates" },
        ).apply {
            startCompleteScan("1.0.0", "Test phone")
            record(
                sample("source-a", null, 100L),
                localizedEstimate("GRID_MULTIMODAL"),
            )
            close()
        }

        val outcome = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 1_000L },
            monotonicClockNanos = { 1_000_000_000L },
        ).recoverAndListCompletedTraces().single().readLines().last()

        assertTrue(outcome.contains("\"localized_device_count\":0"))
        assertTrue(outcome.contains("\"localized\":false"))
    }

    @Test
    fun `recovery does not duplicate fully written outcome`() {
        val active = temporaryFolder.newFile("signal-trace-finished.active.ndjson")
        val outcome = TraceJsonEncoder.outcome(
            sessionId = "finished",
            endedAtUtc = "1970-01-01T00:00:01.000Z",
            durationMillis = 1_000L,
            outcome = TraceOutcome.YES,
            reason = null,
            note = null,
            observedDeviceCount = 0,
            localizedDeviceCount = 0,
            pathLengthMetres = 0.0,
            deviceStates = emptyList(),
        )
        active.writeText("${sessionLine("finished")}\n$outcome\n")

        val recorder = TraceRecorder(temporaryFolder.root)
        val payload = recorder.recoverAndListCompletedTraces().single().readText()

        assertEquals(1, "\"record_type\":\"outcome\"".toRegex().findAll(payload).count())
        assertTrue(payload.contains("\"outcome\":\"YES\""))
    }

    @Test
    fun `recovery does not publish an invalid session record`() {
        val active = temporaryFolder.newFile("signal-trace-invalid.active.ndjson")
        active.writeText("{\"schema_version\":2,\"record_type\":\"session\"}\n")

        val recorder = TraceRecorder(temporaryFolder.root)
        recorder.recoverAndListCompletedTraces()

        assertFalse(active.exists())
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty()
                .single { it.name.endsWith(".quarantined.ndjson") }
                .exists(),
        )
        assertTrue(recorder.completedTraces().isEmpty())
        assertTrue(recorder.storageError!!.contains("quarantined"))
    }

    @Test
    fun `recovery quarantines an invalid complete observation`() {
        val active = temporaryFolder.newFile("signal-trace-invalid-record.active.ndjson")
        active.writeText(
            "${sessionLine("invalid-record")}\n" +
                "{\"schema_version\":2,\"record_type\":\"observation\"," +
                "\"session_id\":\"invalid-record\"}\n",
        )

        val recorder = TraceRecorder(temporaryFolder.root)
        recorder.recoverAndListCompletedTraces()

        assertFalse(active.exists())
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty()
                .any { it.name.endsWith(".quarantined.ndjson") },
        )
        assertTrue(recorder.completedTraces().isEmpty())
        assertTrue(recorder.storageError!!.contains("quarantined"))
    }

    @Test
    fun `recovers active file as incomplete and retains newest ten`() {
        val active = temporaryFolder.newFile("signal-trace-crashed.active.ndjson")
        active.writeText("${sessionLine("crashed")}\n")
        repeat(10) { index ->
            temporaryFolder.newFile("signal-trace-old-$index.ndjson").apply {
                writeText("{}\n")
                setLastModified(index.toLong() + 1L)
            }
        }

        val recorder = TraceRecorder(
            temporaryFolder.root,
            wallClockMillis = { 1_000L },
            monotonicClockNanos = { 1_000_000_000L },
        )
        val history = recorder.recoverAndListCompletedTraces()

        assertEquals(10, history.size)
        val recovered = history.single { it.name.endsWith("-crashed.ndjson") }
        assertEquals(1L, completionGeneration(recovered))
        assertTrue(recovered.readText().contains("\"outcome\":\"INCOMPLETE\""))
        assertTrue(recorder.deleteTrace(recovered))
        assertFalse(recovered.exists())
    }

    @Test
    fun `retention failure stays observable and leaves file visible`() {
        repeat(11) { index ->
            temporaryFolder.newFile("signal-trace-$index.ndjson").apply {
                writeText("{}\n")
                setLastModified(index.toLong())
            }
        }

        val recorder = TraceRecorder(temporaryFolder.root, deleteFile = { false })
        recorder.recoverAndListCompletedTraces()

        assertEquals(11, recorder.completedTraces().size)
        assertTrue(recorder.storageError!!.contains("1 expired"))
    }

    @Test
    fun `completion generations ignore backward wall clock and survive newest deletion`() {
        val sessionIds = ArrayDeque(listOf("first", "second", "third", "fourth"))
        val wallTimes = ArrayDeque(
            listOf(10_000L, 11_000L, 1_000L, 2_000L, 500L, 600L, 100L, 200L),
        )
        val monotonicTimes = ArrayDeque(
            listOf(
                0L,
                1_000_000_000L,
                2_000_000_000L,
                3_000_000_000L,
                4_000_000_000L,
                5_000_000_000L,
                6_000_000_000L,
                7_000_000_000L,
            ),
        )
        val recorder = TraceRecorder(
            temporaryFolder.root,
            wallClockMillis = { wallTimes.removeFirst() },
            monotonicClockNanos = { monotonicTimes.removeFirst() },
            sessionIdFactory = { sessionIds.removeFirst() },
        )

        recorder.startCompleteScan("1", "phone")
        val first = recorder.finish(TraceOutcome.INCOMPLETE).file!!
        recorder.startCompleteScan("1", "phone")
        val second = recorder.finish(TraceOutcome.INCOMPLETE).file!!
        first.setLastModified(Long.MAX_VALUE)
        second.setLastModified(0L)

        assertEquals(listOf(second, first), recorder.completedTraces())
        assertTrue(recorder.deleteTrace(second))
        recorder.startCompleteScan("1", "phone")
        val third = recorder.finish(TraceOutcome.INCOMPLETE).file!!

        assertEquals(3L, completionGeneration(third))
        assertEquals(listOf(third, first), recorder.completedTraces())

        assertTrue(recorder.deleteAllTraces().isEmpty())
        recorder.startCompleteScan("1", "phone")
        val fourth = recorder.finish(TraceOutcome.INCOMPLETE).file!!
        assertEquals(4L, completionGeneration(fourth))
        assertEquals(listOf(fourth), recorder.completedTraces())
    }

    @Test
    fun `pruning uses completion generation instead of last modified`() {
        repeat(11) { index ->
            val generation = index + 1
            temporaryFolder.newFile(
                "signal-trace-g${generation.toString().padStart(20, '0')}-session-$generation.ndjson",
            ).apply {
                writeText("{}\n")
                setLastModified((100 - generation).toLong())
            }
        }

        val history = TraceRecorder(temporaryFolder.root).recoverAndListCompletedTraces()

        assertEquals(10, history.size)
        assertEquals(11L, completionGeneration(history.first()))
        assertFalse(
            temporaryFolder.root.resolve(
                "signal-trace-g00000000000000000001-session-1.ndjson",
            ).exists(),
        )
    }

    @Test
    fun `delete all removes completed active and quarantined traces`() {
        temporaryFolder.newFile("signal-trace-completed.ndjson").writeText("{}\n")
        temporaryFolder.newFile("signal-trace-active.active.ndjson").writeText("{}\n")
        temporaryFolder.newFile("signal-trace-bad.quarantined.ndjson").writeText("{}\n")
        temporaryFolder.newFile("unrelated.ndjson").writeText("{}\n")
        val recorder = TraceRecorder(temporaryFolder.root)

        val remaining = recorder.deleteAllTraces()

        assertTrue(remaining.isEmpty())
        assertEquals(
            listOf("unrelated.ndjson"),
            temporaryFolder.root.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertEquals(null, recorder.storageError)
    }

    @Test
    fun `single target recovery before first packet preserves selected state and duration`() {
        val target = target("source-key", "AA:BB:CC:DD:EE:FF", "Beacon one")
        TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 1_000L },
            monotonicClockNanos = { 2_000_000_000L },
            sessionIdFactory = { "selected-only" },
        ).apply {
            startSingleTarget("1.0.0", "Test phone", target)
            close()
        }

        val recovered = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 6_000L },
            monotonicClockNanos = { 7_000_000_000L },
        ).recoverAndListCompletedTraces().single().readLines().last()

        assertTrue(recovered.contains("\"duration_ms\":5000"))
        assertTrue(recovered.contains("\"observed_device_count\":0"))
        assertTrue(recovered.contains("\"localized_device_count\":0"))
        assertTrue(
            recovered.contains(
                "\"device_states\":[{\"device_key\":\"D01\",\"transport\":\"low_energy\"," +
                    "\"status\":\"insufficient_evidence\",\"estimator\":null}]",
            ),
        )
    }

    @Test
    fun `recovery falls back to wall duration when boot identity changes`() {
        TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 1_000L },
            monotonicClockNanos = { 2_000_000_000L },
            sessionIdFactory = { "rebooted" },
            bootIdProvider = { "boot-a" },
        ).apply {
            startCompleteScan("1.0.0", "Test phone")
            close()
        }

        val outcome = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 6_000L },
            monotonicClockNanos = { 100_000_000_000L },
            bootIdProvider = { "boot-b" },
        ).recoverAndListCompletedTraces().single().readLines().last()

        assertTrue(outcome.contains("\"duration_ms\":5000"))
    }

    @Test
    fun `recovery quarantines parsed invariant violations`() {
        val mutations = listOf<(String) -> String>(
            { it.replace("\"sequence\":1", "\"sequence\":0") },
            { it.replace("\"app_monotonic_ns\":200", "\"app_monotonic_ns\":50") },
            { it.replaceFirst("\"rssi_dbm\":-61", "\"rssi_dbm\":-200") },
            {
                it.replaceFirst(
                    "\"manufacturer_ids\":[]",
                    "\"manufacturer_ids\":[],\"label\":\"AA:BB:CC:DD:EE:FF\"",
                )
            },
            { it.replaceFirst("\"tracking_state\":\"TRACKING\"", "\"tracking_state\":\"LOST\"") },
        )
        mutations.forEachIndexed { index, mutate ->
            val directory = temporaryFolder.newFolder("invalid-recovery-$index")
            TraceRecorder(
                traceDirectory = directory,
                wallClockMillis = { 0L },
                monotonicClockNanos = { 0L },
                sessionIdFactory = { "case-$index" },
                bootIdProvider = { "same-boot" },
            ).apply {
                startCompleteScan("1.0.0", "Test phone")
                record(sample("first", null, 100L), null)
                record(sample("second", null, 200L), null)
                close()
            }
            val active = directory.listFiles().orEmpty().single()
            active.writeText(mutate(active.readText()))

            val recorder = TraceRecorder(directory, bootIdProvider = { "same-boot" })
            assertTrue(recorder.recoverAndListCompletedTraces().isEmpty())
            assertTrue(directory.listFiles().orEmpty().single().name.endsWith(".quarantined.ndjson"))
        }
    }

    @Test
    fun `recovery quarantines an invalid terminal outcome`() {
        val directory = temporaryFolder.newFolder("invalid-terminal")
        val recorder = TraceRecorder(
            traceDirectory = directory,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "invalid-terminal" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")
        recorder.record(sample("source", null, 100L), null)
        val completed = recorder.finish(TraceOutcome.YES).file!!
        val active = java.io.File(directory, "signal-trace-invalid-terminal.active.ndjson")
        assertTrue(completed.renameTo(active))
        active.writeText(
            active.readText().replace(
                "\"observed_device_count\":1",
                "\"observed_device_count\":0",
            ),
        )

        val recovered = TraceRecorder(directory)
        assertTrue(recovered.recoverAndListCompletedTraces().isEmpty())
        assertEquals(
            setOf(
                ".signal-trace-completion-generation",
                "signal-trace-invalid-terminal.quarantined.ndjson",
            ),
            directory.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun `outcome write failure keeps active data and recorder can start again`() {
        var writerNumber = 0
        val sessionIds = ArrayDeque(listOf("write-fails", "write-recovers"))
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { sessionIds.removeFirst() },
            writerFactory = { file ->
                if (writerNumber++ == 0) {
                    BufferedWriter(FailAfterFirstFlushWriter(file))
                } else {
                    file.bufferedWriter()
                }
            },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        val failed = recorder.finish(TraceOutcome.INCOMPLETE)

        assertEquals(null, failed.file)
        assertTrue(failed.error!!.contains("recoverable data kept"))
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty()
                .any { it.name == "signal-trace-write-fails.active.ndjson" },
        )

        recorder.startCompleteScan("1.0.0", "Test phone")
        val completed = recorder.finish(TraceOutcome.INCOMPLETE)

        assertTrue(completed.file?.name?.endsWith("-write-recovers.ndjson") == true)
        assertTrue(
            recorder.completedTraces().any { it.name.endsWith("-write-fails.ndjson") },
        )
    }

    @Test
    fun `prefix then throw rolls back bytes before synchronous recovery publication`() {
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "prefix-failure" },
            writerFactory = { file -> PrefixThenThrowWriter(file) },
        )
        val active = recorder.startCompleteScan("1.0.0", "Test phone")
        val confirmed = active.readBytes()

        val failed = recorder.finish(TraceOutcome.YES)

        assertEquals(null, failed.file)
        assertTrue(failed.error!!.contains("recoverable data kept"))
        assertArrayEquals(confirmed, active.readBytes())
        assertTrue(recorder.completedTraces().isEmpty())

        val recovered = recorder.recoverAndListCompletedTraces().single()
        val lines = recovered.readLines()
        assertFalse(active.exists())
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("\"outcome\":\"INCOMPLETE\""))
        assertFalse(lines.last().contains("\"outcome\":\"YES\""))
    }

    @Test
    fun `rename failure keeps active data and recorder can recover it`() {
        var renameAttempts = 0
        val sessionIds = ArrayDeque(listOf("rename-fails", "rename-recovers"))
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { sessionIds.removeFirst() },
            renameFile = { source, destination ->
                renameAttempts += 1
                renameAttempts != 1 && source.renameTo(destination)
            },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        val failed = recorder.finish(TraceOutcome.INCOMPLETE)

        assertEquals(null, failed.file)
        assertTrue(failed.error!!.contains("recoverable data kept"))
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty()
                .any { it.name == "signal-trace-rename-fails.active.ndjson" },
        )

        recorder.startCompleteScan("1.0.0", "Test phone")
        val completed = recorder.finish(TraceOutcome.INCOMPLETE)

        assertTrue(completed.file?.name?.endsWith("-rename-recovers.ndjson") == true)
        assertTrue(
            recorder.completedTraces().any { it.name.endsWith("-rename-fails.ndjson") },
        )
    }

    @Test
    fun `Android output is byte identical to the shared v2 golden`() {
        val wallTimes = ArrayDeque(listOf(0L, 1_000L))
        val monotonicTimes = ArrayDeque(listOf(0L, 1_000_000_000L))
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { wallTimes.removeFirst() },
            monotonicClockNanos = { monotonicTimes.removeFirst() },
            sessionIdFactory = { "android-v2-fixture" },
            bootIdProvider = { "boot-雪-😀" },
        )
        recorder.startCompleteScan("1.0.0", "Test phone 📱")
        recorder.record(sample("source-a", null, 100L, rssiDbm = -61), null)
        recorder.record(
            sample(
                "source-b",
                null,
                110L,
                BluetoothTransport.CLASSIC,
                rssiDbm = -70,
                filteredRssiDbm = -70.0,
            ),
            null,
        )
        recorder.recordEvent(
            115L,
            TraceEventCode.SIGNAL_LOST,
            DeviceKey("source-b"),
            BluetoothTransport.CLASSIC,
            "No packets — café 😀",
        )
        recorder.record(
            sample(
                "source-a",
                null,
                120L,
                rssiDbm = -58,
                filteredRssiDbm = -59.0,
            ),
            localizedEstimate("GRID_UNIMODAL"),
        )

        val completed = recorder.finish(
            TraceOutcome.YES,
            note = "Found — ✅",
            pathLengthMetres = 2.0,
        ).file!!

        assertArrayEquals(sharedFixture("android_trace_v2.ndjson").readBytes(), completed.readBytes())
    }

    @Test
    fun `recovery decodes UTF-8 and truncates a torn byte suffix`() {
        val session = "{\"schema_version\":2,\"record_type\":\"session\"," +
            "\"session_id\":\"utf8-torn\",\"mode\":\"COMPLETE_SCAN\"," +
            "\"created_at_utc\":\"1970-01-01T00:00:01.000Z\"," +
            "\"started_at_monotonic_ns\":2000000000," +
            "\"boot_id\":\"e5c1e059419f1f85c7352d28c00679f0a95621f9b771fe3e40e7908d5b448b39\"," +
            "\"app_version\":\"1\",\"device_model\":\"Téléphone 📱\"," +
            "\"coordinate_frame\":\"arcore_xz_m\"}"
        val active = temporaryFolder.newFile("signal-trace-utf8-torn.active.ndjson")
        active.writeBytes(
            (
                "$session\n" +
                    "{\"schema_version\":2,\"record_type\":\"event\",\"detail\":\"torn-雪"
                ).toByteArray(),
        )

        val recovered = TraceRecorder(
            temporaryFolder.root,
            wallClockMillis = { 2_000L },
            monotonicClockNanos = { 7_000_000_000L },
            bootIdProvider = { "boot-雪-😀" },
        ).recoverAndListCompletedTraces().single()
        val lines = recovered.readLines()

        assertEquals(session, lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("\"duration_ms\":5000"))
    }

    @Test
    fun `failed record and event appends do not mutate recorder state`() {
        val failures = mutableSetOf("observation", "event")
        val recorder = TraceRecorder(
            traceDirectory = temporaryFolder.root,
            wallClockMillis = { 0L },
            monotonicClockNanos = { 0L },
            sessionIdFactory = { "transactional" },
            writerFactory = { file -> SelectiveFailureWriter(file, failures, append = false) },
            appendWriterFactory = { file -> SelectiveFailureWriter(file, failures, append = true) },
        )
        recorder.startCompleteScan("1.0.0", "Test phone")

        assertThrows(IOException::class.java) {
            recorder.record(sample("failed-observation", null, 100L), localizedEstimate("EKF"))
        }
        recorder.record(sample("recorded-observation", null, 110L), null)
        assertThrows(IOException::class.java) {
            recorder.recordEvent(
                115L,
                TraceEventCode.SIGNAL_LOST,
                DeviceKey("failed-event"),
                BluetoothTransport.CLASSIC,
            )
        }
        recorder.recordEvent(
            120L,
            TraceEventCode.SIGNAL_LOST,
            DeviceKey("recorded-event"),
            BluetoothTransport.CLASSIC,
        )

        val payload = recorder.finish(TraceOutcome.INCOMPLETE).file!!.readText()

        assertTrue(payload.contains("\"sequence\":0,\"device_key\":\"D01\""))
        assertTrue(payload.contains("\"device_key\":\"D02\",\"transport\":\"classic\""))
        assertTrue(payload.contains("\"observed_device_count\":1"))
        assertTrue(payload.contains("\"localized_device_count\":0"))
        assertFalse(payload.contains("\"device_key\":\"D03\""))
    }

    @Test
    fun `Android recovery follows shared strict v2 grammar cases`() {
        val cases = JsonParser.parseString(
            sharedFixture("v2_validation_cases.json").readText(),
        ).asJsonArray
        cases.forEachIndexed { index, element ->
            val case = element.asJsonObject
            val records = goldenRecords()
            records[0].addProperty("created_at_utc", case["created_at_utc"].asString)
            records[1].getAsJsonObject("pose")
                .addProperty("tracking_state", case["tracking_state"].asString)
            records[3].addProperty(
                "detail",
                case["detail_character"].asString.repeat(case["detail_count"].asInt),
            )
            case.get("device_key")?.let { deviceKey ->
                records[1].add("device_key", deviceKey)
                records[4].add("device_key", deviceKey)
                records[5].getAsJsonArray("device_states")[0].asJsonObject
                    .add("device_key", deviceKey)
            }
            case.get("platform_timestamp_ns")?.let { timestamp ->
                records[1].add("platform_timestamp_ns", timestamp)
            }
            val directory = temporaryFolder.newFolder("grammar-$index")
            java.io.File(directory, "signal-trace-android-v2-fixture.active.ndjson").writeText(
                records.joinToString(separator = "\n", postfix = "\n"),
            )

            val recovered = TraceRecorder(directory).recoverAndListCompletedTraces()

            assertEquals(case["name"].asString, case["valid"].asBoolean, recovered.isNotEmpty())
        }
    }

    @Test
    fun `recovery rejects a raw boot identity`() {
        val records = goldenRecords()
        records[0].addProperty("boot_id", "raw-os-boot-uuid")
        val active = temporaryFolder.newFile("signal-trace-android-v2-fixture.active.ndjson")
        active.writeText(records.joinToString(separator = "\n", postfix = "\n"))

        assertTrue(TraceRecorder(temporaryFolder.root).recoverAndListCompletedTraces().isEmpty())
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty()
                .any { it.name.endsWith(".quarantined.ndjson") },
        )
    }

    @Test
    fun `recovery rejects unsupported fields at every v2 privacy boundary`() {
        val mutations = listOf<(List<JsonObject>) -> Unit>(
            { it[0].addProperty("gps_coordinates", "private") },
            {
                it[0].addProperty("mode", "SINGLE_TARGET")
                it[0].add(
                    "target",
                    JsonParser.parseString(
                        "{\"session_key\":\"D01\",\"transport\":\"low_energy\"," +
                            "\"manufacturer_ids\":[],\"service_uuids\":[]," +
                            "\"stable_identifier\":\"private\"}",
                    ),
                )
            },
            {
                it[0].add(
                    "ground_truth",
                    JsonParser.parseString(
                        "{\"x_m\":0.0,\"z_m\":0.0,\"gps_coordinates\":\"private\"}",
                    ),
                )
            },
            {
                it[0].add(
                    "simulation",
                    JsonParser.parseString("{\"email\":\"private\"}"),
                )
            },
            { it[1].addProperty("camera_image", "private") },
            { it[1].getAsJsonObject("pose").addProperty("gps_coordinates", "private") },
            { it[1].getAsJsonObject("advertisement").addProperty("advertising_id", "private") },
            { it[3].addProperty("contact_data", "private") },
            { it[4].getAsJsonObject("estimator").addProperty("play_account", "private") },
            { it[5].addProperty("email", "private") },
            { it[5].getAsJsonArray("device_states")[0].asJsonObject
                .addProperty("stable_identifier", "private") },
            { it[5].getAsJsonArray("device_states")[0].asJsonObject
                .getAsJsonObject("estimator").addProperty("camera_video", "private") },
        )
        mutations.forEachIndexed { index, mutate ->
            val records = goldenRecords()
            mutate(records)
            val directory = temporaryFolder.newFolder("privacy-$index")
            java.io.File(directory, "signal-trace-android-v2-fixture.active.ndjson").writeText(
                records.joinToString(separator = "\n", postfix = "\n"),
            )

            assertTrue(TraceRecorder(directory).recoverAndListCompletedTraces().isEmpty())
            assertTrue(directory.listFiles().orEmpty().single().name.endsWith(".quarantined.ndjson"))
        }
    }

    private fun sharedFixture(name: String) =
        java.io.File(checkNotNull(System.getProperty("sharedFixturesDir")), name)

    private fun completionGeneration(file: java.io.File): Long =
        checkNotNull(
            Regex("""^signal-trace-g([0-9]{20})-.+\.ndjson$""")
                .matchEntire(file.name),
        ).groupValues[1].toLong()

    private fun goldenRecords(): List<JsonObject> =
        sharedFixture("android_trace_v2.ndjson").readLines()
            .filter(String::isNotBlank)
            .map { JsonParser.parseString(it).asJsonObject }

    private fun sessionLine(sessionId: String) =
        "{\"schema_version\":2,\"record_type\":\"session\",\"session_id\":\"$sessionId\"," +
            "\"mode\":\"COMPLETE_SCAN\",\"created_at_utc\":\"1970-01-01T00:00:00.000Z\"," +
            "\"app_version\":\"1\",\"device_model\":\"phone\"," +
            "\"coordinate_frame\":\"arcore_xz_m\"}"

    private fun target(key: String, address: String, name: String?) = TargetHandle(
        key = DeviceKey(key),
        ephemeralAddress = address,
        advertisedName = name,
        manufacturerIds = setOf(76),
        serviceUuids = setOf("180f"),
        transport = BluetoothTransport.LOW_ENERGY,
    )

    private fun localizedEstimate(status: String) = EstimateUiModel(
        status = status,
        meanXMetres = 1.0,
        meanZMetres = 2.0,
        covarianceXX = 0.4,
        covarianceXZ = 0.0,
        covarianceZZ = 0.3,
        regionProbability = 0.8,
        regionAreaSquareMetres = 1.0,
        ambiguity = 0.0,
    )

    private fun sample(
        key: String,
        name: String?,
        receivedAt: Long,
        transport: BluetoothTransport = BluetoothTransport.LOW_ENERGY,
        rssiDbm: Int = -61,
        filteredRssiDbm: Double = -60.0,
    ) = SynchronizedObservation(
        ble = BleObservation(
            receivedAt - 10L,
            receivedAt,
            DeviceKey(key),
            rssiDbm,
            null,
            name,
            emptySet(),
            emptySet(),
            transport,
        ),
        pose = PoseObservation(
            receivedAt - 10L,
            receivedAt - 2L,
            1.0,
            1.4,
            2.0,
            0.0,
            0.0,
            0.0,
            1.0,
            PoseTrackingState.TRACKING,
        ),
        poseAssociationAgeNanos = 2L,
        accepted = true,
        rejectionReason = null,
        filteredRssiDbm = filteredRssiDbm,
        rssiVarianceDb2 = 9.0,
    )

    private class FailAfterFirstFlushWriter(file: java.io.File) : Writer() {
        private val delegate = file.writer()
        private var writes = 0

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (writes++ > 0) throw IOException("simulated outcome write failure")
            delegate.write(buffer, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() {
            runCatching { delegate.close() }
            if (writes > 1) throw IOException("simulated close failure")
        }
    }

    private class SelectiveFailureWriter(
        file: java.io.File,
        private val failures: MutableSet<String>,
        append: Boolean,
    ) : BufferedWriter(java.io.FileOutputStream(file, append).writer()) {

        override fun write(value: String, offset: Int, length: Int) {
            val record = value.substring(offset, offset + length)
            if (
                "observation" in failures &&
                record.contains("\"record_type\":\"observation\"")
            ) {
                failures -= "observation"
                throw IOException("simulated observation append failure")
            }
            if ("event" in failures && record.contains("\"record_type\":\"event\"")) {
                failures -= "event"
                throw IOException("simulated event append failure")
            }
            super.write(value, offset, length)
        }
    }

    private class PrefixThenThrowWriter(file: java.io.File) : BufferedWriter(file.writer()) {
        private var failed = false

        override fun write(value: String, offset: Int, length: Int) {
            if (!failed && value.contains("\"record_type\":\"outcome\"")) {
                failed = true
                super.write(value, offset, minOf(length, 24))
                super.flush()
                throw IOException("simulated prefix write failure")
            }
            super.write(value, offset, length)
        }
    }
}
