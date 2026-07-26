package dev.arsignallocator.ui

import dev.arsignallocator.localization.EstimateState
import dev.arsignallocator.model.ArRuntimeStatus
import dev.arsignallocator.model.ArStatus
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.EstimateUiModel
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.model.ProbabilityCell
import dev.arsignallocator.model.TargetHandle
import dev.arsignallocator.model.TrackingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingUiPolicyTest {
    @Test
    fun `visualization stays regional until the estimator point gate passes`() {
        assertEquals(EstimateVisualization.HIDDEN, selectEstimateVisualization(null))
        assertEquals(
            EstimateVisualization.REGION,
            selectEstimateVisualization(estimate(status = EstimateState.COLLECTING, mean = false)),
        )
        assertEquals(
            EstimateVisualization.REGION,
            selectEstimateVisualization(
                estimate(status = EstimateState.GRID_MULTIMODAL, mean = true),
            ),
        )
        assertEquals(
            EstimateVisualization.POINT,
            selectEstimateVisualization(estimate(status = EstimateState.EKF, mean = true)),
        )
        assertEquals(
            EstimateVisualization.HIDDEN,
            selectEstimateVisualization(
                estimate(status = EstimateState.EKF, mean = true).copy(
                    probabilityCells = emptyList(),
                    regionAreaSquareMetres = null,
                ),
            ),
        )
    }

    @Test
    fun `plain language state follows existing estimate evidence`() {
        val tracking = trackingState(ArRuntimeStatus.TRACKING)

        assertEquals(
            PublicTrackingState.SIGNAL_FOUND,
            policy(
                tracking.copy(metrics = tracking.metrics.copy(acceptedCount = 2)),
                nowElapsedNanos = 1L,
            ).publicState,
        )
        assertEquals(
            PublicTrackingState.BROAD_AREA,
            policy(
                tracking.copy(
                    estimate = estimate(
                        status = EstimateState.GRID_MULTIMODAL,
                        mean = false,
                        ambiguity = 0.6,
                    ),
                ),
                nowElapsedNanos = 1L,
            ).publicState,
        )
        assertEquals(
            PublicTrackingState.NARROWING,
            policy(
                tracking.copy(
                    estimate = estimate(status = EstimateState.COLLECTING, mean = false),
                ),
                nowElapsedNanos = 1L,
            ).publicState,
        )
        assertEquals(
            PublicTrackingState.LIKELY_AREA_FOUND,
            policy(tracking, nowElapsedNanos = 1L).publicState,
        )
    }

    @Test
    fun `frozen estimate neither shrinks nor refreshes`() {
        val stable = estimate(status = EstimateState.EKF, mean = true)
        val incoming = estimate(status = EstimateState.COLLECTING, mean = false)

        assertSame(stable, visibleEstimate(stable, incoming, frozen = true))
        assertSame(incoming, visibleEstimate(stable, incoming, frozen = false))
        assertNull(visibleEstimate(lastStable = null, current = incoming, frozen = true))
    }

    @Test
    fun `rapid loss transition freezes its simultaneous broader estimate`() {
        val likelyArea = estimate(status = EstimateState.EKF, mean = true)
        val broaderArea = estimate(status = EstimateState.COLLECTING, mean = false)
        val laterEstimate = estimate(status = EstimateState.GRID_MULTIMODAL, mean = false)
        val remembered = RememberedEstimate(likelyArea)

        assertSame(likelyArea, remembered.update(likelyArea, freeze = false))
        assertSame(broaderArea, remembered.update(broaderArea, freeze = true))
        assertSame(broaderArea, remembered.update(laterEstimate, freeze = true))
    }

    @Test
    fun `BLE freshness expires immediately after its named timeout`() {
        val lowEnergy = trackingState(
            ArRuntimeStatus.TRACKING,
            transport = BluetoothTransport.LOW_ENERGY,
            packetLastSeen = null,
        ).copy(metrics = TrackingMetrics())

        assertFalse(policy(lowEnergy, BLE_SIGNAL_TIMEOUT_NANOS).signalLost)
        assertTrue(policy(lowEnergy, BLE_SIGNAL_TIMEOUT_NANOS + 1L).signalLost)
    }

    @Test
    fun `Classic freshness expires immediately after its named timeout`() {
        val classic = trackingState(
            ArRuntimeStatus.TRACKING,
            transport = BluetoothTransport.CLASSIC,
            packetLastSeen = null,
        ).copy(metrics = TrackingMetrics())

        assertFalse(policy(classic, CLASSIC_SIGNAL_TIMEOUT_NANOS).signalLost)
        assertTrue(policy(classic, CLASSIC_SIGNAL_TIMEOUT_NANOS + 1L).signalLost)
    }

    @Test
    fun `combined BLE and AR loss exposes both recoveries and freezes`() {
        assertCombinedLoss(
            transport = BluetoothTransport.LOW_ENERGY,
            timeoutNanos = BLE_SIGNAL_TIMEOUT_NANOS,
        )
    }

    @Test
    fun `combined Classic and AR loss exposes both recoveries and freezes`() {
        assertCombinedLoss(
            transport = BluetoothTransport.CLASSIC,
            timeoutNanos = CLASSIC_SIGNAL_TIMEOUT_NANOS,
        )
    }

    @Test
    fun `lost signal labels retained warmth as last seen and hides the meter`() {
        val lost = signalPresentation(signalLost = true, rollingMedianRssiDbm = -60.0)
        assertEquals("Last seen: Warm", lost.label)
        assertNull(lost.progress)

        val current = signalPresentation(signalLost = false, rollingMedianRssiDbm = -60.0)
        assertEquals("Signal: Warm", current.label)
        assertEquals(40f / 70f, current.progress!!, 0.0001f)
    }

    @Test
    fun `AR detail never replaces curated release recovery copy`() {
        val state = trackingState(ArRuntimeStatus.CAMERA_UNAVAILABLE).copy(
            arStatus = ArStatus(
                ArRuntimeStatus.CAMERA_UNAVAILABLE,
                "vendor_camera_session_17 failed with arbitrary internals",
            ),
        )
        val content = trackingStatusContent(policy(state, nowElapsedNanos = 1L), true)

        assertEquals("Camera unavailable", content.title)
        assertEquals("Close other camera apps, then retry.", content.instruction)
    }

    @Test
    fun `signal loss before an estimate exists uses truthful copy`() {
        val state = trackingState(ArRuntimeStatus.TRACKING).copy(estimate = null)
        val policy = policy(state, nowElapsedNanos = BLE_SIGNAL_TIMEOUT_NANOS + 2L)
        val content = trackingStatusContent(policy, estimateAvailable = false)

        assertEquals("Signal lost", content.title)
        assertEquals(
            "No location estimate is available yet. Move back toward the last stronger signal.",
            content.instruction,
        )
    }

    @Test
    fun `every AR status has state appropriate public copy and freeze behavior`() {
        val expectations = mapOf(
            ArRuntimeStatus.INACTIVE to (PublicTrackingState.AR_INACTIVE to "Starting AR"),
            ArRuntimeStatus.CHECKING to (PublicTrackingState.AR_CHECKING to "Checking AR"),
            ArRuntimeStatus.CAMERA_PERMISSION_REQUIRED to (
                PublicTrackingState.CAMERA_PERMISSION_REQUIRED to "Camera permission needed"
                ),
            ArRuntimeStatus.INSTALL_REQUESTED to (
                PublicTrackingState.INSTALL_REQUESTED to "Finish AR setup"
                ),
            ArRuntimeStatus.UNSUPPORTED to (PublicTrackingState.AR_UNSUPPORTED to "AR unavailable"),
            ArRuntimeStatus.READY to (PublicTrackingState.AR_READY to "Starting tracking"),
            ArRuntimeStatus.TRACKING to (
                PublicTrackingState.LIKELY_AREA_FOUND to "Likely area found"
                ),
            ArRuntimeStatus.TRACKING_PAUSED to (
                PublicTrackingState.TRACKING_PAUSED to "Tracking paused"
                ),
            ArRuntimeStatus.CAMERA_UNAVAILABLE to (
                PublicTrackingState.CAMERA_UNAVAILABLE to "Camera unavailable"
                ),
            ArRuntimeStatus.ERROR to (PublicTrackingState.AR_ERROR to "AR error"),
        )
        assertEquals(ArRuntimeStatus.entries.toSet(), expectations.keys)

        expectations.forEach { (arStatus, expected) ->
            val state = trackingState(arStatus).copy(
                arStatus = ArStatus(arStatus, "arbitrary raw runtime detail"),
            )
            val policy = policy(state, nowElapsedNanos = 1L)
            assertEquals(expected.first, policy.publicState)
            if (arStatus == ArRuntimeStatus.TRACKING) {
                assertFalse(policy.freezesEstimate)
            } else {
                assertTrue(policy.freezesEstimate)
            }
            val content = trackingStatusContent(policy, estimateAvailable = true)
            assertEquals(expected.second, content.title)
            assertEquals(
                if (arStatus == ArRuntimeStatus.TRACKING_PAUSED) {
                    "Last stable estimate shown. ${policy.publicState.instruction}"
                } else {
                    policy.publicState.instruction
                },
                content.instruction,
            )
        }
    }

    @Test
    fun `side rail starts exactly when 480dp remains for landscape camera`() {
        assertEquals(
            TrackingLayout.LANDSCAPE_BOTTOM,
            selectTrackingLayout(widthDp = 799f, heightDp = 400f),
        )
        assertEquals(
            TrackingLayout.LANDSCAPE_SIDE_RAIL,
            selectTrackingLayout(widthDp = 800f, heightDp = 400f),
        )
        assertEquals(
            TrackingLayout.PORTRAIT_BOTTOM,
            selectTrackingLayout(widthDp = 400f, heightDp = 800f),
        )
    }

    private fun assertCombinedLoss(
        transport: BluetoothTransport,
        timeoutNanos: Long,
    ) {
        val state = trackingState(ArRuntimeStatus.TRACKING_PAUSED, transport)
        val arOnlyPolicy = policy(state, nowElapsedNanos = 1L + timeoutNanos)
        assertFalse(arOnlyPolicy.signalLost)
        assertTrue(arOnlyPolicy.freezesEstimate)
        assertEquals(
            "Tracking paused",
            trackingStatusContent(arOnlyPolicy, estimateAvailable = true).title,
        )

        val policy = policy(state, nowElapsedNanos = 1L + timeoutNanos + 1L)
        val content = trackingStatusContent(policy, estimateAvailable = true)

        assertEquals(PublicTrackingState.TRACKING_PAUSED, policy.publicState)
        assertTrue(policy.signalLost)
        assertTrue(policy.freezesEstimate)
        assertEquals("Tracking paused and signal lost", content.title)
        assertTrue(content.instruction.contains("Keep the camera uncovered"))
        assertTrue(content.instruction.contains("Move back toward the last stronger signal"))
        assertEquals(
            "Last seen: Warm",
            signalPresentation(policy.signalLost, -60.0).label,
        )
    }

    private fun estimate(
        status: EstimateState,
        mean: Boolean,
        ambiguity: Double = 0.1,
    ): EstimateUiModel = EstimateUiModel(
        status = status,
        meanXMetres = 1.0.takeIf { mean },
        meanZMetres = 2.0.takeIf { mean },
        covarianceXX = null,
        covarianceXZ = null,
        covarianceZZ = null,
        regionProbability = 0.9,
        regionAreaSquareMetres = 2.0,
        ambiguity = ambiguity,
        probabilityCells = listOf(ProbabilityCell(1.0, 2.0, 0.5, 0.3)),
    )

    private fun trackingState(
        arStatus: ArRuntimeStatus,
        transport: BluetoothTransport = BluetoothTransport.LOW_ENERGY,
        packetLastSeen: Long? = 1L,
    ): LocatorUiState {
        val handle = TargetHandle(
            key = DeviceKey("1"),
            ephemeralAddress = "ephemeral",
            advertisedName = "Beacon",
            manufacturerIds = emptySet(),
            serviceUuids = emptySet(),
            transport = transport,
        )
        return LocatorUiState(
            selected = DeviceCandidate(handle, -60, -60.0, 1.0, 0L),
            arStatus = ArStatus(arStatus),
            estimate = estimate(status = EstimateState.EKF, mean = true),
            metrics = TrackingMetrics(
                acceptedCount = 5,
                lastSeenElapsedNanos = packetLastSeen,
                pathLengthMetres = 2.0,
            ),
        )
    }

    private fun policy(
        state: LocatorUiState,
        nowElapsedNanos: Long,
    ): TrackingUiPolicy = trackingUiPolicy(state, nowElapsedNanos)

    private companion object {
        const val BLE_SIGNAL_TIMEOUT_NANOS = 5_000_000_000L
        const val CLASSIC_SIGNAL_TIMEOUT_NANOS = 20_000_000_000L
    }
}
