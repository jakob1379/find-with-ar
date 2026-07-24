package dev.arsignallocator.ui

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.arsignallocator.model.BluetoothTransport
import dev.arsignallocator.model.DeviceCandidate
import dev.arsignallocator.model.DeviceKey
import dev.arsignallocator.model.LocatorUiState
import dev.arsignallocator.model.TargetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UiShellAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingIllustrationAndContinueRemainAccessibleAtCompactLargeText() {
        setContent { OnboardingScreen {} }

        compose.onNodeWithContentDescription(ILLUSTRATION_DESCRIPTION).assertIsDisplayed()
        compose.onNode(hasText("Continue") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget()
    }

    @Test
    fun permissionRecoveryRemainsScrollableAtCompactLargeText() {
        setContent {
            PermissionScreen(
                missingPermissions = listOf(Manifest.permission.CAMERA),
                permissionWasRequested = true,
                onRequest = {},
                onOpenSettings = {},
            )
        }

        compose.onNode(hasText("Open app settings") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget()
    }

    @Test
    fun bluetoothRecoveryRemainsScrollableAtCompactLargeText() {
        setContent { BluetoothScreen(unsupported = false, onEnable = {}) }

        compose.onNode(hasText("Turn on Bluetooth") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget()
    }

    @Test
    fun pickerReachesItsFinalDeviceAndGuidanceAtCompactLargeText() {
        setContent { Picker() }

        compose.onNode(hasText("Last beacon") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget()
        compose.onNodeWithText(PICKER_FINAL_GUIDANCE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun pickerKeepsLabelsFocusOrderClickSemanticsAndTouchTargets() {
        setContent(size = DpSize(320.dp, 800.dp)) { Picker() }

        val completeScan = compose.onNode(hasText("Complete Scan") and hasClickAction())
        val scanAgain = compose.onNode(hasText("Scan again") and hasClickAction())
        val settings = compose.onNode(hasText("Settings") and hasClickAction())
        val search = compose.onNode(hasSetTextAction())
        val firstDevice = compose.onNode(hasText("First beacon") and hasClickAction())
        val lastDevice = compose.onNode(hasText("Last beacon") and hasClickAction())
        val expected = listOf(
            completeScan,
            scanAgain,
            settings,
            search,
            firstDevice,
            lastDevice,
        ).map { it.fetchSemanticsNode().id }
        val actual = compose.onAllNodes(hasClickAction() or hasSetTextAction())
            .fetchSemanticsNodes()
            .map { it.id }
            .filter { it in expected }

        assertEquals(expected, actual)
        listOf(completeScan, scanAgain, settings, firstDevice, lastDevice).forEach {
            it.assert(hasClickAction()).assertMinimumTouchTarget()
        }
        search.assert(hasSetTextAction()).assertMinimumTouchTarget()
        compose.onNodeWithText("Complete Scan", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Pro", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun setContent(
        size: DpSize = COMPACT_VIEWPORT,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides TEST_DENSITY) {
                Box(Modifier.size(size)) {
                    MaterialTheme { content() }
                }
            }
        }
    }

    @Composable
    private fun Picker() {
        DevicePickerScreen(
            state = LocatorUiState(
                candidates = listOf(
                    candidate("First beacon", "D01"),
                    candidate("Last beacon", "D02"),
                ),
                guidance = "Discovery complete",
            ),
            onRefresh = {},
            onSelect = {},
            onCompleteScan = {},
            onSettings = {},
            onExport = {},
            onDelete = {},
        )
    }

    private fun SemanticsNodeInteraction.assertMinimumTouchTarget() = apply {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(TEST_DENSITY) { 48.dp.toPx() }
        assertTrue("Expected at least 48dp touch width, was $bounds", bounds.width >= minimum)
        assertTrue("Expected at least 48dp touch height, was $bounds", bounds.height >= minimum)
    }

    private fun candidate(name: String, key: String) = DeviceCandidate(
        handle = TargetHandle(
            key = DeviceKey(key),
            ephemeralAddress = key,
            advertisedName = name,
            manufacturerIds = emptySet(),
            serviceUuids = emptySet(),
            transport = BluetoothTransport.LOW_ENERGY,
        ),
        latestRssiDbm = -60,
        medianRssiDbm = -60.0,
        observationsPerSecond = 1.0,
        lastSeenElapsedNanos = 0L,
    )

    private companion object {
        val TEST_DENSITY = Density(density = 2f, fontScale = 2f)
        val COMPACT_VIEWPORT = DpSize(320.dp, 120.dp)
        const val ILLUSTRATION_DESCRIPTION =
            "A person holds a phone and moves it sideways around a stationary device " +
                "while a broad likely area narrows"
        const val PICKER_FINAL_GUIDANCE =
            "Low Energy targets must advertise. Classic targets must remain discoverable, " +
                "usually in pairing mode. A paired but silent device cannot be found."
    }
}
