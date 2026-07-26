package dev.arsignallocator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.junit.Rule
import org.junit.Test

class TrackingControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun actionsRouteCallbacksAndExposeTalkBackDescriptions() {
        var found = 0
        var devices = 0
        setControls(onFoundTarget = { found++ }, onLeaveSession = { devices++ })

        compose.onNodeWithContentDescription("Found the selected device")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithContentDescription("Leave tracking and return to devices")
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            assertEquals(1, found)
            assertEquals(1, devices)
        }
    }

    @Test
    fun combinedLossShowsBothRecoveryConditionsAndStaleSignal() {
        val state = trackingState(ArRuntimeStatus.TRACKING_PAUSED)
        val policy = trackingUiPolicy(state, BLE_SIGNAL_TIMEOUT_NANOS + 2L)
        setControls(
            state = state,
            policy = policy,
            content = trackingStatusContent(policy, estimateAvailable = true),
        )

        compose.onNodeWithText("Tracking paused and signal lost").assertIsDisplayed()
        compose.onNodeWithText(
            "Last stable estimate shown. Keep the camera uncovered and move slowly. " +
                "Last seen estimate shown. Move back toward the last stronger signal.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Last seen: Warm").assertIsDisplayed()
    }

    @Test
    fun mergedTrackingStatusIsAPoliteLiveRegion() {
        setControls()

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
        ).assertTextContains("Likely area found", substring = true)
            .assertTextContains("Search within the highlighted area", substring = true)
    }

    @Test
    fun controlsRemainScrollableAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).height(220.dp)) {
                    Controls(
                        modifier = Modifier.testTag("controls"),
                        state = trackingState(ArRuntimeStatus.TRACKING),
                    )
                }
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Devices"))
        compose.onNodeWithText("Devices").assertIsDisplayed()
    }

    @Test
    fun bannerProviderSeamRespectsReservedGeometry() {
        compose.setContent {
            Column(Modifier.width(360.dp)) {
                TrackingBannerSlot(
                    visible = true,
                    sideRail = true,
                    modifier = Modifier.testTag("side-banner"),
                ) {
                    Text("Side banner provider")
                }
                TrackingBannerSlot(
                    visible = true,
                    sideRail = false,
                    modifier = Modifier.testTag("bottom-banner"),
                ) {
                    Text("Bottom banner provider")
                }
            }
        }
        compose.onNodeWithTag("side-banner")
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(50.dp)
        compose.onNodeWithText("Side banner provider").assertIsDisplayed()
        compose.onNodeWithText("Bottom banner provider").assertIsDisplayed()
        compose.onNodeWithTag("bottom-banner")
            .assertWidthIsEqualTo(360.dp)
            .assertHeightIsEqualTo(50.dp)
    }

    @Test
    fun hiddenBannerEmitsNoSlotAndConsumesNoHeight() {
        compose.setContent {
            Column(Modifier.width(360.dp).testTag("banner-stack")) {
                Box(Modifier.fillMaxWidth().height(20.dp))
                TrackingBannerSlot(
                    visible = false,
                    sideRail = false,
                    modifier = Modifier.testTag("hidden-banner"),
                ) {
                    Text("Unavailable provider")
                }
            }
        }

        compose.onAllNodesWithTag("hidden-banner").assertCountEquals(0)
        compose.onAllNodesWithText("Unavailable provider").assertCountEquals(0)
        compose.onNodeWithTag("banner-stack").assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun controlsFillTheirReservedGeometry() {
        compose.setContent {
            Box(Modifier.size(width = 320.dp, height = 400.dp)) {
                Controls(
                    modifier = Modifier.testTag("controls"),
                    state = trackingState(ArRuntimeStatus.TRACKING),
                )
            }
        }
        compose.onNodeWithTag("controls")
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(400.dp)
    }

    private fun setControls(
        state: LocatorUiState = trackingState(ArRuntimeStatus.TRACKING),
        policy: TrackingUiPolicy = trackingUiPolicy(state, 1L),
        content: TrackingStatusContent = trackingStatusContent(policy, true),
        onLeaveSession: () -> Unit = {},
        onFoundTarget: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Controls(
                    state = state,
                    policy = policy,
                    content = content,
                    onLeaveSession = onLeaveSession,
                    onFoundTarget = onFoundTarget,
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Controls(
        state: LocatorUiState,
        modifier: Modifier = Modifier,
        policy: TrackingUiPolicy = trackingUiPolicy(state, 1L),
        content: TrackingStatusContent = trackingStatusContent(policy, true),
        onLeaveSession: () -> Unit = {},
        onFoundTarget: () -> Unit = {},
    ) {
        TrackingControls(
            state = state,
            policy = policy,
            statusContent = content,
            variantPanelOpen = false,
            visualizations = TrackingVisualizations(),
            onVisualizationsChange = {},
            onLeaveSession = onLeaveSession,
            onFoundTarget = onFoundTarget,
            onToggleVariantPanel = {},
            onExport = {},
            modifier = modifier,
        )
    }

    private fun trackingState(arStatus: ArRuntimeStatus): LocatorUiState {
        val handle = TargetHandle(
            key = DeviceKey("target"),
            ephemeralAddress = "ephemeral",
            advertisedName = "Beacon",
            manufacturerIds = emptySet(),
            serviceUuids = emptySet(),
            transport = BluetoothTransport.LOW_ENERGY,
        )
        return LocatorUiState(
            selected = DeviceCandidate(handle, -60, -60.0, 1.0, 0L),
            arStatus = ArStatus(arStatus),
            estimate = estimate(EstimateState.EKF),
            metrics = TrackingMetrics(
                rollingMedianRssiDbm = -60.0,
                acceptedCount = 5,
                lastSeenElapsedNanos = 1L,
                pathLengthMetres = 2.0,
            ),
        )
    }

    private fun estimate(status: EstimateState) = EstimateUiModel(
        status = status,
        meanXMetres = 1.0,
        meanZMetres = 2.0,
        covarianceXX = null,
        covarianceXZ = null,
        covarianceZZ = null,
        regionProbability = 0.9,
        regionAreaSquareMetres = 2.0,
        ambiguity = 0.1,
        probabilityCells = listOf(ProbabilityCell(1.0, 2.0, 0.5, 0.3)),
    )

    private companion object {
        const val BLE_SIGNAL_TIMEOUT_NANOS = 5_000_000_000L
    }
}
